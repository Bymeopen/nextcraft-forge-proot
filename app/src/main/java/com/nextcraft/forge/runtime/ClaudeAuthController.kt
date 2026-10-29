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
        mutableState.value = if (hasCredential()) {
            mutableState.value.copy(status = ClaudeAuthStatus.SIGNED_IN, message = null)
        } else {
            ClaudeAuthState()
        }
    }

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

    /**
     * Fills in the account email of a login that was already on disk (an earlier session, or `claude auth login` in
     * the terminal): the email is otherwise only read right after signing in from this screen. The remembered value is
     * shown at once and replaced by what `claude auth status` reports.
     */
    suspend fun loadAccountEmail() = withContext(Dispatchers.IO) {
        if (!hasCredential()) return@withContext
        val prefs = com.nextcraft.forge.data.AppPreferences(context)
        fun show(email: String) {
            val current = mutableState.value
            if (current.status == ClaudeAuthStatus.SIGNED_IN || current.status == ClaudeAuthStatus.SIGNED_OUT) {
                mutableState.value = current.copy(accountEmail = email)
            }
        }
        prefs.claudeAccountEmail.takeIf { it.isNotBlank() }?.let(::show)
        readAccountEmail()?.let {
            prefs.claudeAccountEmail = it
            show(it)
        }
    }

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
                    if (clean.contains("authentication failed", true) || clean.contains("login failed", true) ||
                        clean.contains("invalid code", true) || clean.contains("failed to exchange", true)
                    ) {
                        throw UiTextException(UiText.res(R.string.rt_auth_claude_failed_retry))
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    val exited = !running.isAlive
                    val exitZero = exited && runCatching { running.exitValue() }.getOrDefault(-1) == 0
                    val looksDone = clean.contains("login successful", true) || clean.contains("logged in as", true)
                    if (exited || looksDone || now - lastCredentialCheck > CREDENTIAL_POLL_MS) {
                        lastCredentialCheck = now
                        // A first login (stateBeforeLogin == null) keeps the lenient rule: any exit with a credential counts.
                        // A re-login must not trust the credential of the previous session when the CLI died or failed, so an
                        // exit alone only counts when it was clean and the browser step had been reached.
                        val allowedToFinish = looksDone ||
                            mutableState.value.status == ClaudeAuthStatus.COMPLETING ||
                            (exited && (stateBeforeLogin == null ||
                                (exitZero && mutableState.value.status == ClaudeAuthStatus.AWAITING_BROWSER)))
                        if (allowedToFinish && hasCredential()) {
                            val account = readAccountEmail()
                            account?.let { com.nextcraft.forge.data.AppPreferences(context).claudeAccountEmail = it }
                            mutableState.value = ClaudeAuthState(
                                ClaudeAuthStatus.SIGNED_IN,
                                message = account?.let { UiText.res(R.string.rt_auth_connected_as, it) } ?: UiText.res(R.string.rt_auth_claude_connected),
                                accountEmail = account,
                            )
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

    /** Sends the code shown on the sign-in page (used when the automatic redirect did not reach the CLI). */
    @Synchronized
    fun submitCode(code: String) {
        val value = code.trim()
        if (value.isBlank()) throw UiTextException(UiText.res(R.string.rt_auth_claude_code_empty))
        try {
            val running = process ?: throw UiTextException(UiText.res(R.string.rt_auth_session_inactive))
            if (!running.isAlive) throw UiTextException(UiText.res(R.string.rt_auth_session_expired))
            running.outputStream.write((value + "\r").toByteArray())
            running.outputStream.flush()
        } catch (error: Throwable) {
            mutableState.value = ClaudeAuthState(
                ClaudeAuthStatus.ERROR,
                message = error.toUiText(R.string.rt_auth_code_submit_failed),
            )
            throw error
        }
        mutableState.value = mutableState.value.copy(
            status = ClaudeAuthStatus.COMPLETING,
            message = UiText.res(R.string.rt_auth_claude_completing),
        )
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
        val output = File(context.cacheDir, "claude-status-${System.nanoTime()}.log")
        try {
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
            val text = if (output.isFile) output.readText() else ""
            val braceIdx = text.indexOf('{')
            if (braceIdx < 0) return@runCatching null
            val json = org.json.JSONObject(text.substring(braceIdx))
            json.optString("email").takeIf { it.isNotBlank() }
                ?: json.optJSONObject("account")?.optString("email")?.takeIf { it.isNotBlank() }
        } finally {
            output.delete()
        }
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
