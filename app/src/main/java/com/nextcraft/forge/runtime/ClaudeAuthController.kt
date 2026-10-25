package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.ui.UiTextException
import com.nextcraft.forge.ui.toUiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.model.AgentKind
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class ClaudeAuthStatus { SIGNED_OUT, STARTING, AWAITING_BROWSER, COMPLETING, SIGNED_IN, ERROR }

/** How `claude auth login` should authenticate. */
enum class ClaudeLoginMode(val flag: String?) {
    /** Claude subscription (Pro, Max, Team, Enterprise) with the normal email / Google login. */
    CLAUDE_ACCOUNT("--claudeai"),
    /** Single sign-on of an organization (`--sso`), optionally with the email pre-filled. */
    SSO("--sso"),
    /** Anthropic Console account, billed per API usage. */
    CONSOLE("--console"),
}

data class ClaudeAuthState(
    val status: ClaudeAuthStatus = ClaudeAuthStatus.SIGNED_OUT,
    /** Link to open in the Android browser; its redirect to http://localhost:<port>/callback finishes sign-in on its own. */
    val authorizationUrl: String? = null,
    /** Fallback link whose page shows a code to paste when the automatic redirect cannot reach the CLI. */
    val manualUrl: String? = null,
    val message: UiText? = null,
    val accountEmail: String? = null,
)

/**
 * Drives Claude Code's official `claude auth login` inside the Root Chroot. Browser sign-in (including SSO) is
 * completed either by the localhost redirect, which reaches the CLI because the chroot shares the device's network
 * namespace, or by pasting the code shown on the sign-in page. The tokens stay in `~/.claude/.credentials.json`,
 * managed (and refreshed) by the CLI itself; this class never reads them.
 */
class ClaudeAuthController(
    private val context: Context,
    private val onSignedInChanged: (Boolean) -> Unit = {},
) {
    private val installer = RuntimeInstaller(context)
    private val mutableState = MutableStateFlow(ClaudeAuthState())
    val state: StateFlow<ClaudeAuthState> = mutableState.asStateFlow()
    private val loginGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val authOutput = File(context.cacheDir, "claude-auth-output.log")
    private val urlFile = File(RuntimeInstaller.bridgeRootDir(context), "claude-login-url")
    @Volatile private var process: Process? = null
    private val loginMutex = Mutex()
    @Volatile private var stateBeforeLogin: ClaudeAuthState? = null
    // Backgrounding the app to open the OAuth browser leaves nothing holding it awake; some OEMs (seen on Motorola)
    // freeze a backgrounded app's network within seconds, so the CLI's own localhost callback server stops accepting
    // the browser's connection and the whole sign-in times out. A foreground service for the wait keeps it alive.
    private val foreground = ForegroundSession(context, "ClaudeAuthController")

    init {
        authOutput.delete()
    }

    fun hasCredential(): Boolean = installer.hasClaudeLoginCredential()

    /** Marks the state as signed in when a previous session left a login behind (checked off the main thread). */
    suspend fun refreshFromDisk() = withContext(Dispatchers.IO) {
        val current = mutableState.value.status
        if (current != ClaudeAuthStatus.SIGNED_OUT && current != ClaudeAuthStatus.SIGNED_IN) return@withContext
        if (!hasCredential()) {
            mutableState.value = ClaudeAuthState()
            return@withContext
        }
        // Show the account at once from the cache, then confirm it with the CLI (a few seconds under PRoot).
        val cached = accountPrefs.claudeAccountEmail.takeIf { it.isNotBlank() }
        mutableState.value = signedInState(cached)
        if (mutableState.value.accountEmail == null || cached == null) {
            val live = readAccountEmail()
            if (live != null) {
                accountPrefs.claudeAccountEmail = live
                if (mutableState.value.status == ClaudeAuthStatus.SIGNED_IN) mutableState.value = signedInState(live)
            }
        }
    }

    private fun signedInState(account: String?) = ClaudeAuthState(
        ClaudeAuthStatus.SIGNED_IN,
        message = account?.let { UiText.res(R.string.rt_auth_connected_as, it) } ?: UiText.res(R.string.rt_auth_claude_connected),
        accountEmail = account,
    )

    private val accountPrefs by lazy { com.nextcraft.forge.data.AppPreferences(context) }

    /** Taps back into the app from the Custom Tab, which never returns there on its own after sign-in. */
    private fun notifySignedIn(account: String?) = runCatching {
        val manager = context.getSystemService(android.app.NotificationManager::class.java) ?: return@runCatching
        manager.createNotificationChannel(
            android.app.NotificationChannel(
                SIGN_IN_CHANNEL_ID,
                context.getString(R.string.notif_channel_results),
                android.app.NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return@runCatching
        val contentIntent = android.app.PendingIntent.getActivity(
            context, 3, launchIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = androidx.core.app.NotificationCompat.Builder(context, SIGN_IN_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_task_completed))
            .setContentText(account?.let { context.getString(R.string.rt_auth_connected_as, it) } ?: context.getString(R.string.rt_auth_claude_connected))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(SIGN_IN_NOTIFICATION_ID, notification)
    }

    /** Upstream entry point; the card refreshes through [refreshFromDisk], which also fills in the email. */
    suspend fun loadAccountEmail() = refreshFromDisk()

    suspend fun beginLogin(mode: ClaudeLoginMode, email: String = "") = withContext(Dispatchers.IO) {
        // Signal any in-flight attempt to stop right away: the old reader loop polls loginGeneration every ~120ms
        // and exits as soon as it sees a mismatch, releasing loginMutex below within a fraction of a second. Without
        // this, a login already in progress (which can run for up to LOGIN_TIMEOUT_MS) would make a second tap on
        // "Sign in with SSO" wait on the mutex for that whole time with zero feedback — the button would look dead.
        loginGeneration.incrementAndGet()
        process?.takeIf { it.isAlive }?.let { running ->
            Thread {
                runCatching {
                    running.destroy()
                    Thread.sleep(200)
                    if (running.isAlive) running.destroyForcibly()
                    running.waitFor()
                }
            }.apply { isDaemon = true }.start()
        }
        loginMutex.withLock {
            beginLoginLocked(mode, email.trim())
        }
    }

    private suspend fun beginLoginLocked(mode: ClaudeLoginMode, email: String) {
        // The kill triggered in beginLogin() needs a moment to be noticed by the old reader loop (it polls every
        // ~120ms) and for its `finally` block to null `process` out; give it a bit more room than that.
        if (process?.isAlive == true) {
            val giveUp = android.os.SystemClock.elapsedRealtime() + RESTART_WAIT_MS
            while (process != null && android.os.SystemClock.elapsedRealtime() < giveUp) delay(50)
            if (process?.isAlive == true) {
                mutableState.value = ClaudeAuthState(ClaudeAuthStatus.ERROR, message = UiText.res(R.string.rt_auth_prev_closing))
                return
            }
            process = null
        }
        if (!installer.isAgentInstalled(AgentKind.CLAUDE_CODE)) {
            mutableState.value = ClaudeAuthState(ClaudeAuthStatus.ERROR, message = UiText.res(R.string.rt_auth_claude_install_first))
            return
        }
        if (email.isNotEmpty() && !EMAIL_PATTERN.matches(email)) {
            mutableState.value = ClaudeAuthState(ClaudeAuthStatus.ERROR, message = UiText.res(R.string.rt_auth_email_invalid))
            return
        }
        stateBeforeLogin = mutableState.value.takeIf { it.status == ClaudeAuthStatus.SIGNED_IN }
        mutableState.value = ClaudeAuthState(ClaudeAuthStatus.STARTING, message = UiText.res(R.string.rt_auth_claude_starting))
        authOutput.delete()
        urlFile.delete()
        val running = try {
            installer.ensureSettingsAndHooks() // installs the BROWSER helper
            val runtime = installer.installedRuntime()
            val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
            val command = buildList {
                add(CLAUDE_BINARY)
                add("auth")
                add("login")
                mode.flag?.let(::add)
                if (email.isNotEmpty()) {
                    add("--email")
                    add(email)
                }
            }
            val spawned = installer.process(
                runtime.chrootExec,
                runtime.rootfs,
                workspace,
                mapOf(
                    "BROWSER" to "/opt/nextcraft/open-url.sh",
                    "TERM" to "xterm-256color",
                    "NO_COLOR" to "1",
                    "DISABLE_AUTOUPDATER" to "1",
                ),
                command,
                guestWorkspacePath = "/workspace/claude-auth",
                emulateHardLinks = false,
                outputFile = authOutput,
                pseudoTerminal = true,
                ptyRows = 40,
                ptyColumns = 160,
            )
            if (spawned !is NativeSpawnProcess) {
                runCatching { spawned.destroyForcibly() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
                throw UiTextException(UiText.res(R.string.rt_auth_unsupported_process))
            }
            spawned
        } catch (error: Throwable) {
            mutableState.value = ClaudeAuthState(
                ClaudeAuthStatus.ERROR,
                message = error.toUiText(R.string.rt_auth_claude_start_failed),
            )
            return
        }
        process = running
        foreground.begin()
        foreground.start("Claude Code")
        // cancel()/logout() bump this so a loop that is still winding down cannot overwrite their state.
        val generation = loginGeneration.get()
        var offset = 0L
        val output = StringBuilder()
        val deadline = android.os.SystemClock.elapsedRealtime() + LOGIN_TIMEOUT_MS
        var lastCredentialCheck = 0L
        try {
            RandomAccessFile(running.outputFile, "r").use { file ->
                while (true) {
                    if (generation != loginGeneration.get()) break
                    if (android.os.SystemClock.elapsedRealtime() > deadline) throw UiTextException(UiText.res(R.string.rt_auth_timeout))
                    val available = running.outputFile.length() - offset
                    if (available > 0) {
                        val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                        file.seek(offset)
                        val count = file.read(bytes)
                        if (count > 0) {
                            val safe = completeUtf8PrefixLength(bytes, count).let { if (it > 0) it else count }
                            offset += safe
                            output.append(bytes.decodeToString(0, safe))
                            if (output.length > MAX_OUTPUT_CHARS) output.delete(0, output.length - MAX_OUTPUT_CHARS / 2)
                        }
                    }
                    val clean = sanitizeClaudeAuthOutput(output.toString())
                    val manual = extractClaudeManualUrl(clean)
                    val automatic = readAutomaticUrl()
                    val current = mutableState.value
                    val link = automatic ?: manual
                    if (link != null &&
                        (current.status == ClaudeAuthStatus.STARTING || current.status == ClaudeAuthStatus.AWAITING_BROWSER) &&
                        (current.authorizationUrl != link || current.manualUrl != manual)
                    ) {
                        mutableState.value = ClaudeAuthState(
                            ClaudeAuthStatus.AWAITING_BROWSER,
                            authorizationUrl = link,
                            manualUrl = manual,
                            message = UiText.res(R.string.rt_auth_claude_open_page),
                        )
                    }
                    val failureLine = clean.lines().lastOrNull { line ->
                        line.contains("authentication failed", true) || line.contains("login failed", true) ||
                            line.contains("invalid code", true) || line.contains("failed to exchange", true)
                    }
                    if (failureLine != null) {
                        // The CLI's own reason (an organization without Claude Code access, a Console-only account,
                        // a token it could not store) is the only thing that tells the user what to change.
                        throw UiTextException(UiText.res(R.string.rt_auth_claude_failed_reason, failureLine.trim().take(240)))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val exited = !running.isAlive
                    val looksDone = clean.contains("login successful", true) || clean.contains("logged in as", true)
                    if (exited || looksDone || now - lastCredentialCheck > CREDENTIAL_POLL_MS) {
                        lastCredentialCheck = now
                        if ((exited || looksDone || mutableState.value.status == ClaudeAuthStatus.COMPLETING) && hasCredential()) {
                            val account = readAccountEmail()
                            accountPrefs.claudeAccountEmail = account.orEmpty()
                            mutableState.value = signedInState(account)
                            onSignedInChanged(true)
                            // The sign-in page shows "you can close this window" but a Custom Tab never closes itself
                            // or returns to the app on its own; a notification is the only way back for someone who
                            // does not remember to switch apps manually.
                            notifySignedIn(account)
                            break
                        }
                    }
                    if (exited && running.outputFile.length() <= offset) {
                        throw UiTextException(UiText.res(R.string.rt_auth_claude_ended, runCatching { running.exitValue() }.getOrDefault(-1)))
                    }
                    delay(120)
                }
            }
        } catch (cancelled: CancellationException) {
            if (mutableState.value.status != ClaudeAuthStatus.SIGNED_IN) {
                mutableState.value = stateBeforeLogin ?: ClaudeAuthState()
            }
            throw cancelled
        } catch (error: Throwable) {
            if (generation == loginGeneration.get() && mutableState.value.status != ClaudeAuthStatus.SIGNED_IN) {
                mutableState.value = ClaudeAuthState(
                    ClaudeAuthStatus.ERROR,
                    message = error.toUiText(R.string.rt_auth_claude_failed),
                )
            }
        } finally {
            runCatching { running.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            if (running.isAlive) {
                runCatching { running.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
                runCatching { if (running.isAlive) running.destroyForcibly() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            }
            runCatching { running.waitFor() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            running.outputFile.delete() // OAuth terminal output is intentionally ephemeral.
            urlFile.delete()
            process = null
            foreground.cancel()
        }
    }

    /** URL the CLI handed to its BROWSER helper: the one whose redirect is the CLI's own localhost listener. */
    private fun readAutomaticUrl(): String? = runCatching {
        urlFile.takeIf(File::isFile)?.readText()?.trim()?.takeIf { it.length < 4_096 && isTrustedSignInUrl(it) }
    }.getOrNull()

    /**
     * The URL file is writable by any process in the chroot (including a prompt-injected agent), so only offer links
     * to Anthropic's own sign-in hosts instead of whatever https:// string is found there.
     */
    private fun isTrustedSignInUrl(url: String): Boolean {
        val host = runCatching { java.net.URI(url) }.getOrNull()?.takeIf { it.scheme == "https" }?.host?.lowercase() ?: return false
        return TRUSTED_SIGN_IN_HOSTS.any { host == it || host.endsWith(".$it") }
    }

    /**
     * Completes a browser sign-in whose final redirect to `http://localhost:<port>/callback` did not load in the
     * browser (some Android browsers refuse plain-http localhost pages). The user pastes that page's address, or
     * its `code#state` value, and the app delivers it to the CLI's listener over loopback itself.
     *
     * `claude auth login` (2.1.112) has no "paste code" prompt of its own: it only completes through that listener.
     */
    suspend fun submitCode(code: String) = withContext(Dispatchers.IO) {
        val value = code.trim()
        if (value.isBlank()) throw UiTextException(UiText.res(R.string.rt_auth_claude_code_empty))
        try {
            val running = process ?: throw UiTextException(UiText.res(R.string.rt_auth_session_inactive))
            if (!running.isAlive) throw UiTextException(UiText.res(R.string.rt_auth_session_expired))
            val callback = buildCallbackUrl(value, mutableState.value.authorizationUrl)
                ?: throw UiTextException(UiText.res(R.string.rt_auth_claude_code_unrecognized))
            mutableState.value = mutableState.value.copy(
                status = ClaudeAuthStatus.COMPLETING,
                message = UiText.res(R.string.rt_auth_claude_completing),
            )
            val connection = java.net.URL(callback).openConnection() as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 10_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                val status = connection.responseCode
                if (status !in 200..399) throw UiTextException(UiText.res(R.string.rt_auth_claude_code_rejected, status))
            } finally {
                connection.disconnect()
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            mutableState.value = ClaudeAuthState(
                ClaudeAuthStatus.ERROR,
                message = error.toUiText(R.string.rt_auth_code_submit_failed),
            )
            throw error
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        loginGeneration.incrementAndGet()
        process?.let { running ->
            runCatching { running.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            runCatching { if (running.isAlive) running.destroyForcibly() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
        }
        val previous = mutableState.value
        mutableState.value = previous.copy(status = ClaudeAuthStatus.STARTING, message = UiText.res(R.string.rt_auth_signing_out))
        try {
            val runtime = installer.installedRuntime()
            val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
            val output = File(context.cacheDir, "claude-logout-output.log")
            val logout = installer.process(
                runtime.chrootExec,
                runtime.rootfs,
                workspace,
                mapOf("DISABLE_AUTOUPDATER" to "1"),
                listOf(CLAUDE_BINARY, "auth", "logout"),
                guestWorkspacePath = "/workspace/claude-auth",
                emulateHardLinks = false,
                outputFile = output,
            )
            runCatching { logout.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            if (!logout.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) logout.destroyForcibly()
            output.delete()
            if (hasCredential()) throw UiTextException(UiText.res(R.string.rt_auth_claude_signout_incomplete))
            accountPrefs.claudeAccountEmail = ""
            onSignedInChanged(false)
            com.nextcraft.forge.data.AppPreferences(context).claudeAccountEmail = ""
            mutableState.value = ClaudeAuthState(message = UiText.res(R.string.rt_auth_claude_signed_out))
        } catch (error: Throwable) {
            mutableState.value = previous.copy(message = error.toUiText(R.string.rt_auth_claude_signout_failed))
            throw error
        }
    }

    fun cancel() {
        loginGeneration.incrementAndGet()
        val running = process
        if (running != null) {
            Thread {
                runCatching {
                    running.destroy()
                    Thread.sleep(200)
                    if (running.isAlive) running.destroyForcibly()
                    running.waitFor()
                }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
            }.apply { isDaemon = true }.start()
        }
        mutableState.value = stateBeforeLogin ?: ClaudeAuthState()
    }

    /** Email of the signed-in account from `claude auth status --json`; null when it cannot be determined. */
    private fun readAccountEmail(): String? = runCatching {
        val runtime = installer.installedRuntime()
        val workspace = File(context.filesDir, "workspaces/claude-auth").apply { mkdirs() }
        val output = File(context.cacheDir, "claude-status-output.log")
        val status = installer.process(
            runtime.chrootExec,
            runtime.rootfs,
            workspace,
            mapOf("DISABLE_AUTOUPDATER" to "1"),
            listOf(CLAUDE_BINARY, "auth", "status", "--json"),
            guestWorkspacePath = "/workspace/claude-auth",
            emulateHardLinks = false,
            outputFile = output,
        )
        runCatching { status.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeAuthController", it) }
        if (!status.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) status.destroyForcibly()
        val text = output.readText()
        output.delete()
        val json = org.json.JSONObject(text.substring(text.indexOf('{')))
        json.optString("email").takeIf { it.isNotBlank() }
            ?: json.optJSONObject("account")?.optString("email")?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private companion object {
        const val CLAUDE_BINARY = "/usr/local/bin/claude"
        val TRUSTED_SIGN_IN_HOSTS = listOf("claude.ai", "claude.com", "anthropic.com")
        const val LOGIN_TIMEOUT_MS = 15 * 60 * 1_000L
        const val RESTART_WAIT_MS = 5_000L
        const val CREDENTIAL_POLL_MS = 2_000L
        const val SIGN_IN_CHANNEL_ID = "auth-results"
        const val SIGN_IN_NOTIFICATION_ID = 43
        const val MAX_OUTPUT_CHARS = 100_000
        val EMAIL_PATTERN = Regex("[A-Za-z0-9._%+'-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
    }
}

/**
 * The loopback callback the app delivers for a pasted [input]: either the full `http://localhost:<port>/callback?…`
 * address the browser ended on, or `code#state` combined with the port of the sign-in link's redirect_uri.
 */
internal fun buildCallbackUrl(input: String, authorizationUrl: String?): String? {
    val pastedUrl = runCatching { java.net.URI(input) }.getOrNull()
    if (pastedUrl != null && pastedUrl.scheme == "http" && pastedUrl.host in setOf("localhost", "127.0.0.1") &&
        pastedUrl.port in 1..65535 && pastedUrl.path == "/callback" && !pastedUrl.rawQuery.isNullOrBlank()
    ) {
        return "http://127.0.0.1:${pastedUrl.port}/callback?${pastedUrl.rawQuery}"
    }
    val parts = input.split('#', limit = 2)
    if (parts.size != 2 || parts.any { it.isBlank() || !it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }) return null
    val redirect = authorizationUrl?.let { url ->
        runCatching { java.net.URI(url) }.getOrNull()?.rawQuery?.split('&')
            ?.firstOrNull { it.startsWith("redirect_uri=") }
            ?.removePrefix("redirect_uri=")
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
    } ?: return null
    val port = runCatching { java.net.URI(redirect) }.getOrNull()?.takeIf { it.host == "localhost" }?.port ?: return null
    if (port !in 1..65535) return null
    val encode = { v: String -> java.net.URLEncoder.encode(v, "UTF-8") }
    return "http://127.0.0.1:$port/callback?code=${encode(parts[0])}&state=${encode(parts[1])}"
}

// OSC sequences (the hyperlink wrapper around the URL) are removed lazily so the visible URL text between the
// opening and closing hyperlink escapes survives; other CSI/charset escapes are dropped.
private val CLAUDE_AUTH_ANSI = Regex(
    "\\u001B\\][^\\u0007\\u001B]*(?:\\u0007|\\u001B\\\\)|\\u001B\\[[0-?]*[ -/]*[@-~]|\\u001B[()][A-Z0-9]",
)

internal fun sanitizeClaudeAuthOutput(text: String): String = text
    .replace(CLAUDE_AUTH_ANSI, "")
    .filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 0x20 }

/**
 * The fallback link printed after "visit:". It is long enough to wrap in the terminal, so everything up to the
 * "Paste code" prompt is joined without whitespace.
 */
internal fun extractClaudeManualUrl(cleanOutput: String): String? {
    val start = cleanOutput.indexOf("https://claude.")
    if (start < 0) return null
    val end = cleanOutput.indexOf("Paste code", start).takeIf { it > start } ?: return null
    val joined = cleanOutput.substring(start, end).replace(Regex("\\s+"), "")
    // If the terminal printed the link twice (hyperlink target and visible text), keep the first copy only.
    val second = joined.indexOf("https://", startIndex = 8)
    val url = if (second > 0) joined.substring(0, second) else joined
    return url.takeIf { "state=" in it && "code_challenge=" in it }
}
