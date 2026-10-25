package com.nextcraft.forge.runtime

import com.nextcraft.forge.R
import com.nextcraft.forge.network.GitHubAccount
import com.nextcraft.forge.network.GitHubDeviceCode
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.GitHubTokenPoll
import com.nextcraft.forge.ui.UiText
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sign-in flow with a fake GitHub and no waiting: every branch of the device flow and the token path. */
class GitHubAuthControllerTest {
    private val repo = GitHubRepository("octo/hello", "https://github.com/octo/hello.git", false, "main", "", "")
    private val code = GitHubDeviceCode("dev", "ABCD-1234", "https://github.com/login/device", expiresInSeconds = 900, intervalSeconds = 5)

    private class Fake(
        var polls: ArrayDeque<() -> GitHubTokenPoll>,
        var account: () -> GitHubAccount = { GitHubAccount("octocat", "") },
        var repos: () -> List<GitHubRepository> = { emptyList() },
        var deviceCode: () -> GitHubDeviceCode,
    ) : GitHubAccountApi {
        override fun requestDeviceCode(clientId: String) = deviceCode()
        override fun pollDeviceToken(clientId: String, deviceCode: String) = polls.removeFirst().invoke()
        override fun account(token: String) = account()
        override fun repositories(token: String) = repos()
    }

    private class Rig(val api: Fake, saved: String = "") {
        var token: String? = null
        var login = saved
        var cleared = 0
        var tokenSaveFails = false
        val waits = mutableListOf<Long>()
        var clock = 0L
        val controller = GitHubAuthController(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            api = api,
            saveToken = { if (tokenSaveFails) error("keystore locked") else token = it },
            clearToken = { token = null },
            savedLogin = { login },
            saveLogin = { login = it },
            clearGuestAccess = { cleared++ },
            unnamedUserLabel = { "user" },
            errorText = { it.message ?: "?" },
            io = Dispatchers.Unconfined,
            wait = { waits += it; clock += it },
            now = { clock },
        )
        val state get() = controller.state.value
    }

    private fun rig(vararg polls: () -> GitHubTokenPoll, repos: List<GitHubRepository> = emptyList(), saved: String = "") =
        Rig(Fake(ArrayDeque(polls.toList()), repos = { repos }, deviceCode = { code }), saved)

    @Test
    fun startsSignedInAsTheSavedLogin() {
        assertEquals("octocat", rig(saved = "octocat").state.login)
    }

    @Test
    fun deviceFlow_succeedsAfterPendingPolls_andStoresEverything() {
        val r = rig({ GitHubTokenPoll.Pending() }, { GitHubTokenPoll.Pending() }, { GitHubTokenPoll.Success("tok") }, repos = listOf(repo))
        r.controller.startDeviceSignIn()
        assertEquals("tok", r.token)
        assertEquals("octocat", r.login)
        assertEquals("octocat", r.state.login)
        assertNull(r.state.deviceCode)
        assertEquals(listOf(repo), r.state.repos)
        assertEquals(UiText.res(R.string.msg_gh_connected, "octocat", 1), r.state.statusMessage)
        assertEquals(1, r.cleared)
        assertEquals(listOf(5_000L, 5_000L), r.waits.take(2)) // the first poll waits out the interval too
    }

    @Test
    fun slowDownLengthensTheInterval() {
        val r = rig({ GitHubTokenPoll.Pending(slowDown = true) }, { GitHubTokenPoll.Success("t") })
        r.controller.startDeviceSignIn()
        assertEquals(listOf(5_000L, 10_000L), r.waits)
    }

    @Test
    fun theCodeIsShownWhileWaiting() {
        val r = rig({ GitHubTokenPoll.Failure("x") })
        r.controller.startDeviceSignIn()
        // The failure cleared it again; the message before that named the code to type.
        assertNull(r.state.deviceCode)
    }

    @Test
    fun deniedAndExpiredHaveTheirOwnMessages_andClearTheCode() {
        val denied = rig({ GitHubTokenPoll.Failure("no", GitHubTokenPoll.Failure.Reason.DENIED) })
        denied.controller.startDeviceSignIn()
        assertEquals(UiText.res(R.string.msg_gh_denied), denied.state.statusMessage)
        assertNull(denied.state.deviceCode)
        val expired = rig({ GitHubTokenPoll.Failure("late", GitHubTokenPoll.Failure.Reason.EXPIRED) })
        expired.controller.startDeviceSignIn()
        assertEquals(UiText.res(R.string.msg_gh_code_expired), expired.state.statusMessage)
        assertEquals("", expired.state.login)
        assertNull(expired.token)
    }

    @Test
    fun aNetworkBlipDoesNotEndTheSignIn_butTwelveInARowDo() {
        val blips = Array<() -> GitHubTokenPoll>(12) { { throw IOException("offline") } }
        val ok = rig(*blips, { GitHubTokenPoll.Success("t") })
        ok.controller.startDeviceSignIn()
        assertEquals("octocat", ok.state.login)
        val tooMany = Array<() -> GitHubTokenPoll>(13) { { throw IOException("offline") } }
        val dead = rig(*tooMany)
        dead.controller.startDeviceSignIn()
        assertEquals("", dead.state.login)
        assertEquals(UiText.Raw("offline"), dead.state.statusMessage)
    }

    @Test
    fun aCodeThatRunsOutWithoutAnAnswerIsReportedExpired() {
        // Always pending; the fake clock advances by the waits until the 900 s deadline passes.
        val r = Rig(Fake(ArrayDeque(), deviceCode = { code }).also { f -> f.polls = ArrayDeque(List(400) { { GitHubTokenPoll.Pending() } }) })
        r.controller.startDeviceSignIn()
        assertEquals(UiText.res(R.string.msg_gh_code_expired), r.state.statusMessage)
        assertNull(r.state.deviceCode)
    }

    @Test
    fun ifTheTokenCannotBeStored_noLoginIsRecorded() {
        val r = rig({ GitHubTokenPoll.Success("tok") })
        r.tokenSaveFails = true
        r.controller.startDeviceSignIn()
        assertEquals("", r.state.login)
        assertEquals("", r.login)
        assertEquals(UiText.Raw("keystore locked"), r.state.statusMessage)
    }

    @Test
    fun requestingTheCodeCanFail() {
        val r = Rig(Fake(ArrayDeque(), deviceCode = { throw IOException("dns") }))
        r.controller.startDeviceSignIn()
        assertEquals(UiText.res(R.string.msg_gh_error, "dns"), r.state.statusMessage)
        assertNull(r.state.deviceCode)
    }

    @Test
    fun token_blankIsRefused_validSignsIn_invalidReportsTheError() {
        val r = rig(repos = listOf(repo))
        r.controller.connectWithToken("   ")
        assertEquals(UiText.res(R.string.msg_gh_token_invalid), r.state.statusMessage)
        assertNull(r.token)
        r.controller.connectWithToken(" ghp_x ")
        assertEquals("ghp_x", r.token)
        assertEquals("octocat", r.state.login)
        assertEquals(listOf(repo), r.state.repos)
        val bad = Rig(Fake(ArrayDeque(), account = { throw IOException("401") }, deviceCode = { code }))
        bad.controller.connectWithToken("nope")
        assertEquals(UiText.res(R.string.msg_gh_token_error, "401"), bad.state.statusMessage)
        assertNull(bad.token)
    }

    @Test
    fun disconnect_forgetsTheTokenTheLoginAndTheRepos() {
        val r = rig(repos = listOf(repo))
        r.controller.connectWithToken("ghp_x")
        r.controller.disconnect()
        assertNull(r.token)
        assertEquals("", r.login)
        assertEquals("", r.state.login)
        assertTrue(r.state.repos.isEmpty())
        assertEquals(UiText.res(R.string.msg_gh_disconnected), r.state.statusMessage)
    }

    @Test
    fun resumeDoesNothingWithoutACode_orWhenSignedIn() {
        val r = rig({ GitHubTokenPoll.Success("t") })
        r.controller.resumeDeviceSignIn() // no code on screen
        assertEquals("", r.state.login)
        val signed = rig(saved = "octocat")
        signed.controller.resumeDeviceSignIn()
        assertEquals("octocat", signed.state.login)
    }

    @Test
    fun restoreRepos_onlyOverwritesWithANonEmptyList() {
        val r = rig(repos = listOf(repo))
        runBlocking { r.controller.restoreRepos("tok") }
        assertEquals(listOf(repo), r.state.repos)
        assertEquals(1, r.cleared)
        val none = rig(repos = emptyList())
        none.controller.publish(listOf(repo), null)
        runBlocking { none.controller.restoreRepos("tok") }
        assertEquals(listOf(repo), none.state.repos) // an empty answer keeps what the screen already has
    }

    @Test
    fun publishSetsTheReposAndTheMessageTogether() {
        val r = rig()
        r.controller.publish(listOf(repo), UiText.Raw("done"))
        assertEquals(listOf(repo), r.state.repos)
        assertEquals(UiText.Raw("done"), r.state.statusMessage)
    }
}
