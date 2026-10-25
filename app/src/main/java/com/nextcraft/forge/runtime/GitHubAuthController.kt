package com.nextcraft.forge.runtime

import com.nextcraft.forge.R
import com.nextcraft.forge.network.GitHubAccount
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubDeviceCode
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.GitHubTokenPoll
import com.nextcraft.forge.ui.UiText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The four calls the sign-in needs from GitHub; the real [GitHubClient] or a fake in tests. */
interface GitHubAccountApi {
    fun requestDeviceCode(clientId: String): GitHubDeviceCode
    fun pollDeviceToken(clientId: String, deviceCode: String): GitHubTokenPoll
    fun account(token: String): GitHubAccount
    fun repositories(token: String): List<GitHubRepository>
}

fun GitHubClient.asAccountApi(): GitHubAccountApi = object : GitHubAccountApi {
    override fun requestDeviceCode(clientId: String) = this@asAccountApi.requestDeviceCode(clientId)
    override fun pollDeviceToken(clientId: String, deviceCode: String) = this@asAccountApi.pollDeviceToken(clientId, deviceCode)
    override fun account(token: String) = this@asAccountApi.account(token)
    override fun repositories(token: String) = this@asAccountApi.repositories(token)
}

/** What the GitHub section of the UI shows: who is signed in, the code to type in the browser, the repos, one message. */
data class GitHubAuthState(
    val login: String = "",
    val deviceCode: GitHubDeviceCode? = null,
    val repos: List<GitHubRepository> = emptyList(),
    val statusMessage: UiText? = null,
)

/**
 * GitHub sign-in (device flow or a personal access token), sign-out, and the account's repository list. It owns these
 * four pieces of state; the repo operations (create, sync, pull, clone) stay with the project code and report back
 * through [report] and [publish]. Moved out of MainViewModel with its behaviour unchanged, so it can be tested with fakes.
 */
class GitHubAuthController(
    private val scope: CoroutineScope,
    private val api: GitHubAccountApi,
    private val saveToken: (String) -> Unit,
    private val clearToken: () -> Unit,
    private val savedLogin: () -> String,
    private val saveLogin: (String) -> Unit,
    /** Removes any GitHub credentials left inside the rootfs (they are written only for the length of a sync). */
    private val clearGuestAccess: () -> Unit,
    private val unnamedUserLabel: () -> String,
    private val errorText: (Throwable) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(GitHubAuthState(login = savedLogin()))
    val state: StateFlow<GitHubAuthState> = mutable.asStateFlow()

    private var pollJob: Job? = null
    private var clientId: String = GitHubClient.DEFAULT_CLIENT_ID

    /** One message for the section; the repo operations use it for their progress and result. */
    fun report(message: UiText?) = mutable.update { it.copy(statusMessage = message) }

    /** The repository list and the message that goes with it, in one update. */
    fun publish(repos: List<GitHubRepository>, message: UiText?) = mutable.update { it.copy(repos = repos, statusMessage = message) }

    /** At start-up with a saved token: clear leftover guest credentials and load the repository list. */
    suspend fun restoreRepos(token: String) = withContext(io) {
        runCatching { clearGuestAccess() }.onFailure { ErrorLog.swallowed("GitHubAuth", it) }
        val repos = runCatching { api.repositories(token) }.getOrDefault(emptyList())
        if (repos.isNotEmpty()) mutable.update { it.copy(repos = repos) }
    }

    fun startDeviceSignIn(requestedClientId: String = "") {
        val cleanId = requestedClientId.trim().ifEmpty { GitHubClient.DEFAULT_CLIENT_ID }
        pollJob?.cancel()
        pollJob = scope.launch {
            report(UiText.res(R.string.msg_gh_requesting_code))
            val codeResult = withContext(io) {
                runCatching { api.requestDeviceCode(cleanId) }.onFailure { ErrorLog.swallowed("GitHubAuth", it) }
            }
            val deviceCode = codeResult.getOrElse { err ->
                report(UiText.res(R.string.msg_gh_error, errorText(err)))
                return@launch
            }
            clientId = cleanId
            mutable.update {
                it.copy(
                    deviceCode = deviceCode,
                    statusMessage = UiText.res(R.string.msg_gh_open_and_enter, deviceCode.verificationUri, deviceCode.userCode),
                )
            }
            pollDeviceToken(cleanId, deviceCode, pollImmediately = false)
        }
    }

    /**
     * Called when the app returns to the foreground while a device code is still on screen: the user has most likely
     * just approved it in the browser, so poll right away instead of waiting out the interval, and restart the loop if
     * it was lost.
     */
    fun resumeDeviceSignIn() {
        val deviceCode = mutable.value.deviceCode ?: return
        if (mutable.value.login.isNotBlank()) return
        pollJob?.cancel()
        pollJob = scope.launch { pollDeviceToken(clientId, deviceCode, pollImmediately = true) }
    }

    private suspend fun pollDeviceToken(cleanId: String, deviceCode: GitHubDeviceCode, pollImmediately: Boolean) {
        var intervalSec = deviceCode.intervalSeconds
        var networkFailures = 0
        var first = pollImmediately
        val deadline = now() + deviceCode.expiresInSeconds * 1000L
        coroutineScope {
            while (isActive && now() < deadline) {
                if (first) first = false else wait(intervalSec * 1000L)
                val poll = withContext(io) {
                    runCatching { api.pollDeviceToken(cleanId, deviceCode.deviceCode) }
                        .onSuccess { networkFailures = 0 }
                        .getOrElse { err ->
                            // A network blip (e.g. while the user is in the browser entering the code) must not end the sign-in.
                            if (err is java.io.IOException && ++networkFailures <= MAX_POLL_NETWORK_FAILURES) {
                                GitHubTokenPoll.Pending()
                            } else {
                                GitHubTokenPoll.Failure(err.message ?: "Polling error")
                            }
                        }
                }
                when (poll) {
                    is GitHubTokenPoll.Success -> {
                        runCatching { saveToken(poll.accessToken) }.onFailure { err ->
                            mutable.update {
                                it.copy(deviceCode = null, statusMessage = err.message?.let { m -> UiText.Raw(m) } ?: UiText.res(R.string.msg_gh_token_store_failed))
                            }
                            return@coroutineScope
                        }
                        val account = withContext(io) { runCatching { api.account(poll.accessToken) }.getOrNull() }
                        val login = account?.login.orEmpty()
                        withContext(io) {
                            // Credentials are provisioned into the rootfs only during sync/push.
                            runCatching { clearGuestAccess() }.onFailure { ErrorLog.swallowed("GitHubAuth", it) }
                        }
                        val repos = withContext(io) { runCatching { api.repositories(poll.accessToken) }.getOrDefault(emptyList()) }
                        saveLogin(login)
                        mutable.update {
                            it.copy(
                                login = login,
                                deviceCode = null,
                                repos = repos,
                                statusMessage = UiText.res(R.string.msg_gh_connected, login.ifBlank { unnamedUserLabel() }, repos.size),
                            )
                        }
                        return@coroutineScope
                    }
                    is GitHubTokenPoll.Pending -> {
                        if (poll.slowDown) intervalSec += 5L
                    }
                    is GitHubTokenPoll.Failure -> {
                        val text = when (poll.reason) {
                            GitHubTokenPoll.Failure.Reason.EXPIRED -> UiText.res(R.string.msg_gh_code_expired)
                            GitHubTokenPoll.Failure.Reason.DENIED -> UiText.res(R.string.msg_gh_denied)
                            GitHubTokenPoll.Failure.Reason.OTHER -> UiText.Raw(poll.message)
                        }
                        mutable.update { it.copy(deviceCode = null, statusMessage = text) }
                        return@coroutineScope
                    }
                }
            }
            // Reached only when the code expired (success/failure return above); don't leave a dead code on screen.
            if (isActive) mutable.update { it.copy(deviceCode = null, statusMessage = UiText.res(R.string.msg_gh_code_expired)) }
        }
    }

    fun connectWithToken(personalAccessToken: String) {
        val cleanToken = personalAccessToken.trim()
        if (cleanToken.isEmpty()) {
            report(UiText.res(R.string.msg_gh_token_invalid))
            return
        }
        pollJob?.cancel()
        scope.launch {
            report(UiText.res(R.string.msg_gh_verifying_token))
            val result = withContext(io) {
                runCatching {
                    val account = api.account(cleanToken)
                    val repos = api.repositories(cleanToken)
                    // Credentials are provisioned into the rootfs only during sync/push.
                    runCatching { clearGuestAccess() }
                    account to repos
                }.onFailure { ErrorLog.swallowed("GitHubAuth", it) }
            }
            result.onSuccess { (account, repos) ->
                runCatching { saveToken(cleanToken) }.onFailure { err ->
                    report(err.message?.let { m -> UiText.Raw(m) } ?: UiText.res(R.string.msg_gh_token_store_failed))
                    return@onSuccess
                }
                saveLogin(account.login)
                mutable.update {
                    it.copy(
                        login = account.login,
                        deviceCode = null,
                        repos = repos,
                        statusMessage = UiText.res(R.string.msg_gh_connected, account.login, repos.size),
                    )
                }
            }.onFailure { err ->
                report(UiText.res(R.string.msg_gh_token_error, errorText(err)))
            }
        }
    }

    fun disconnect() {
        pollJob?.cancel()
        clearToken()
        saveLogin("")
        scope.launch(io) { runCatching { clearGuestAccess() }.onFailure { ErrorLog.swallowed("GitHubAuth", it) } }
        mutable.update {
            it.copy(login = "", deviceCode = null, repos = emptyList(), statusMessage = UiText.res(R.string.msg_gh_disconnected))
        }
    }

    private companion object {
        const val MAX_POLL_NETWORK_FAILURES = 12
    }
}
