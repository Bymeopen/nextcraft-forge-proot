package com.nextcraft.forge.runtime

import android.content.Context
import android.os.SystemClock
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.ui.UiTextException
import com.nextcraft.forge.ui.toUiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AntigravityAuthController(
    private val context: Context,
    initiallySignedIn: Boolean,
    initialAccountEmail: String,
    private val onSignedInChanged: (Boolean, String?) -> Unit,
) {
    private val rtInstaller = RuntimeInstaller(context)
    private val cleanInitialEmail = initialAccountEmail.takeIf { it.isNotBlank() }

    private val authStateFlow = MutableStateFlow(
        AntigravityAuthState(
            status = if (initiallySignedIn) AntigravityAuthStatus.SIGNED_IN else AntigravityAuthStatus.SIGNED_OUT,
            message = cleanInitialEmail?.let { UiText.res(R.string.rt_auth_connected_as, it) },
            accountEmail = cleanInitialEmail,
        ),
    )
    val state: StateFlow<AntigravityAuthState> = authStateFlow.asStateFlow()

    private val ptyTranscriptFile = File(context.cacheDir, "antigravity-auth-output.log")
    private val signoutScratchFile = File(context.cacheDir, "antigravity-logout-output.log")

    @Volatile private var activePtyProc: Process? = null
    @Volatile private var verificationCodeSent = false
    @Volatile private var snapshotPriorToLogin: AntigravityAuthState? = null
    private val loginGate = AtomicBoolean(false)

    init {
        ptyTranscriptFile.delete()
        signoutScratchFile.delete()
    }

    private fun resolveOAuthTokenFile(): File = File(
        context.filesDir,
        "runtime/ubuntu/root/.gemini/antigravity-cli/antigravity-oauth-token",
    )

    fun hasOfficialCredential(): Boolean {
        val tokenPath = resolveOAuthTokenFile()
        if (tokenPath.isFile && tokenPath.length() > 0L) return true
        if (!RuntimeEngines.isRootEngine()) return false
        val escapedPath = shellSingleQuote(tokenPath.absolutePath)
        return runCatching {
            val checkProc = ProcessBuilder("su", "-c", "test -s $escapedPath").start()
            if (!checkProc.waitFor(3L, TimeUnit.SECONDS)) {
                checkProc.destroyForcibly()
                false
            } else {
                checkProc.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    suspend fun beginLogin() = withContext(Dispatchers.IO) {
        if (!loginGate.compareAndSet(false, true)) return@withContext
        try {
            runInteractiveOAuthSession()
        } finally {
            loginGate.set(false)
        }
    }

    private suspend fun runInteractiveOAuthSession() {
        if (activePtyProc?.isAlive == true) return
        if (!rtInstaller.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            authStateFlow.value = AntigravityAuthState(
                status = AntigravityAuthStatus.ERROR,
                message = UiText.res(R.string.rt_auth_agy_install_first),
            )
            return
        }

        snapshotPriorToLogin = authStateFlow.value.takeIf { it.status == AntigravityAuthStatus.SIGNED_IN }
        authStateFlow.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = UiText.res(R.string.rt_auth_agy_starting),
        )
        verificationCodeSent = false
        ptyTranscriptFile.delete()

        val ptyProc = try {
            val env = rtInstaller.installedRuntime()
            val authWorkdir = File(context.filesDir, "workspaces/antigravity-auth").apply { mkdirs() }
            val ptyEnv = mapOf(
                "SSH_CONNECTION" to "127.0.0.1 1 127.0.0.1 1",
                "TERM" to "xterm-256color",
                "NO_COLOR" to "1",
            )
            val proc = rtInstaller.process(
                env.chrootExec,
                env.rootfs,
                authWorkdir,
                ptyEnv,
                listOf(RuntimeInstaller.AGY_GUEST_PATH),
                guestWorkspacePath = "/workspace/antigravity-auth",
                emulateHardLinks = false,
                outputFile = ptyTranscriptFile,
                pseudoTerminal = true,
                ptyRows = 40,
                ptyColumns = 120,
            )
            if (proc !is NativeSpawnProcess) {
                runCatching { proc.destroyForcibly() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                throw UiTextException(UiText.res(R.string.rt_auth_unsupported_process))
            }
            proc
        } catch (err: Throwable) {
            authStateFlow.value = AntigravityAuthState(
                status = AntigravityAuthStatus.ERROR,
                message = err.toUiText(R.string.rt_auth_agy_start_failed),
            )
            return
        }

        activePtyProc = ptyProc
        var readCursor = 0L
        val rawBuffer = StringBuilder()
        var sentHandshakes = 0
        var lastHandshakePos = 0
        var advancedLoginPicker = false
        var pickedTheme = false
        var pickedRenderMode = false
        var acceptedPrivacy = false
        var trustedFolder = false
        val timeoutAtMs = SystemClock.elapsedRealtime() + OAUTH_FLOW_TIMEOUT_MS

        fun writePtyInput(payload: String) {
            ptyProc.outputStream.write(payload.encodeToByteArray())
            ptyProc.outputStream.flush()
        }

        try {
            RandomAccessFile(ptyProc.outputFile, "r").use { raf ->
                while (ptyProc.isAlive || ptyProc.outputFile.length() > readCursor) {
                    if (SystemClock.elapsedRealtime() > timeoutAtMs) {
                        throw UiTextException(UiText.res(R.string.rt_auth_timeout))
                    }
                    val unreadBytes = ptyProc.outputFile.length() - readCursor
                    if (unreadBytes <= 0L) {
                        delay(80L)
                        continue
                    }
                    val chunk = ByteArray(minOf(unreadBytes, 16_384L).toInt())
                    raf.seek(readCursor)
                    val got = raf.read(chunk)
                    if (got <= 0) continue

                    val utf8ValidLen = completeUtf8PrefixLength(chunk, got).let { if (it > 0) it else got }
                    readCursor += utf8ValidLen
                    rawBuffer.append(chunk.decodeToString(0, utf8ValidLen))

                    if (rawBuffer.length > TRANSCRIPT_MAX_CHARS) {
                        val trimCount = rawBuffer.length - TRANSCRIPT_RETAIN_CHARS
                        rawBuffer.delete(0, trimCount)
                        lastHandshakePos = (lastHandshakePos - trimCount).coerceAtLeast(0)
                    }

                    val strippedText = stripAnsiSequences(rawBuffer.toString()).takeLast(40_000)

                    if (sentHandshakes < 5 &&
                        rawBuffer.length - lastHandshakePos > 200 &&
                        rawBuffer.substring(lastHandshakePos).contains("\u001B[?u")
                    ) {
                        writePtyInput(KITTY_DEC_HANDSHAKE_RESPONSE)
                        sentHandshakes++
                        lastHandshakePos = rawBuffer.length
                    }

                    if (sentHandshakes > 0 &&
                        !advancedLoginPicker &&
                        strippedText.contains("Select login method", ignoreCase = true) &&
                        strippedText.contains("> 1. Google OAuth", ignoreCase = true) &&
                        extractGoogleOAuthUrl(strippedText) == null
                    ) {
                        writePtyInput("\r\n")
                        advancedLoginPicker = true
                        if (authStateFlow.value.status == AntigravityAuthStatus.STARTING) {
                            authStateFlow.value = authStateFlow.value.copy(
                                message = UiText.res(R.string.rt_auth_agy_generating),
                            )
                        }
                    }

                    val detectedAuthUrl = extractGoogleOAuthUrl(strippedText)
                    if (detectedAuthUrl != null && authStateFlow.value.authorizationUrl == null) {
                        authStateFlow.value = AntigravityAuthState(
                            status = AntigravityAuthStatus.AWAITING_CODE,
                            authorizationUrl = detectedAuthUrl,
                            message = UiText.res(R.string.rt_auth_agy_open_link),
                        )
                    }

                    if (isSignedInScreen(strippedText)) {
                        val userEmail = findAccountEmail(strippedText)
                        onSignedInChanged(true, userEmail)
                        val connectedText = userEmail?.let { UiText.res(R.string.rt_auth_connected_as, it) }
                            ?: UiText.res(R.string.rt_auth_google_connected)
                        authStateFlow.value = AntigravityAuthState(
                            status = AntigravityAuthStatus.SIGNED_IN,
                            message = connectedText,
                            accountEmail = userEmail,
                        )
                        runCatching { writePtyInput("/quit\r\n") }
                            .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                        for (tick in 0 until 20) {
                            if (!ptyProc.isAlive) break
                            delay(50L)
                        }
                        if (ptyProc.isAlive) ptyProc.destroy()
                        break
                    }

                    if (!pickedTheme && strippedText.contains("Choose your color scheme", ignoreCase = true)) {
                        writePtyInput("\r\n")
                        pickedTheme = true
                    }

                    if (!pickedRenderMode &&
                        strippedText.contains("no flickering (altscreen)", ignoreCase = true) &&
                        strippedText.contains("Native Terminal experience (inline)", ignoreCase = true)
                    ) {
                        writePtyInput("\u001B[B\r\n")
                        pickedRenderMode = true
                    }

                    if (!acceptedPrivacy &&
                        strippedText.contains("Terms of Service & Data Use", ignoreCase = true) &&
                        strippedText.contains("help improve Antigravity CLI", ignoreCase = true)
                    ) {
                        val keystrokes = if (strippedText.contains("[x] Yes", ignoreCase = true)) " \t\t\r\n" else "\t\t\r\n"
                        writePtyInput(keystrokes)
                        acceptedPrivacy = true
                        authStateFlow.value = authStateFlow.value.copy(
                            message = UiText.res(R.string.rt_auth_agy_finishing),
                        )
                    }

                    if (!trustedFolder &&
                        strippedText.contains("Do you trust the contents of this project", ignoreCase = true) &&
                        strippedText.contains("Yes, I trust this folder", ignoreCase = true)
                    ) {
                        writePtyInput("\r\n")
                        trustedFolder = true
                    }

                    if (strippedText.contains("authentication failed", ignoreCase = true) ||
                        strippedText.contains("failed to exchange", ignoreCase = true)
                    ) {
                        throw UiTextException(UiText.res(R.string.rt_auth_agy_failed_retry))
                    }
                }
            }

            when (authStateFlow.value.status) {
                AntigravityAuthStatus.STARTING -> if (!verificationCodeSent) {
                    val code = ptyProc.waitFor()
                    throw UiTextException(UiText.res(R.string.rt_auth_agy_exited_early, code))
                }
                AntigravityAuthStatus.AWAITING_CODE -> throw UiTextException(UiText.res(R.string.rt_auth_agy_no_code))
                AntigravityAuthStatus.COMPLETING -> if (hasOfficialCredential()) {
                    onSignedInChanged(true, null)
                    authStateFlow.value = AntigravityAuthState(
                        status = AntigravityAuthStatus.SIGNED_IN,
                        message = UiText.res(R.string.rt_auth_google_connected),
                    )
                } else {
                    throw UiTextException(UiText.res(R.string.rt_auth_agy_ended))
                }
                else -> Unit
            }
        } catch (ce: CancellationException) {
            if (authStateFlow.value.status != AntigravityAuthStatus.SIGNED_IN) {
                authStateFlow.value = snapshotPriorToLogin ?: AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
            }
            throw ce
        } catch (err: Throwable) {
            if (authStateFlow.value.status != AntigravityAuthStatus.SIGNED_IN) {
                authStateFlow.value = AntigravityAuthState(
                    status = AntigravityAuthStatus.ERROR,
                    message = err.toUiText(R.string.rt_auth_agy_failed),
                )
            }
        } finally {
            runCatching { ptyProc.outputStream.close() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            if (ptyProc.isAlive) {
                runCatching { ptyProc.destroy() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                runCatching { if (ptyProc.isAlive) ptyProc.destroyForcibly() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            }
            runCatching { ptyProc.waitFor() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            ptyProc.outputFile.delete()
            activePtyProc = null
            verificationCodeSent = false
        }
    }

    @Synchronized
    fun submitCode(code: String) {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) throw UiTextException(UiText.res(R.string.rt_auth_agy_code_empty))
        try {
            val proc = activePtyProc ?: throw UiTextException(UiText.res(R.string.rt_auth_session_inactive))
            if (!proc.isAlive) throw UiTextException(UiText.res(R.string.rt_auth_session_expired))
            proc.outputStream.write("$cleanCode\r\n".encodeToByteArray())
            proc.outputStream.flush()
        } catch (err: Throwable) {
            authStateFlow.value = AntigravityAuthState(
                status = AntigravityAuthStatus.ERROR,
                message = err.toUiText(R.string.rt_auth_agy_code_failed),
            )
            throw err
        }
        verificationCodeSent = true
        authStateFlow.value = authStateFlow.value.copy(
            status = AntigravityAuthStatus.COMPLETING,
            message = UiText.res(R.string.rt_auth_google_completing),
        )
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        activePtyProc?.let { proc ->
            runCatching { proc.destroy() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            runCatching { proc.waitFor() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        }
        val priorAccount = authStateFlow.value.accountEmail
        authStateFlow.value = AntigravityAuthState(
            status = AntigravityAuthStatus.STARTING,
            message = UiText.res(R.string.rt_auth_signing_out),
            accountEmail = priorAccount,
        )
        try {
            val tokenFile = resolveOAuthTokenFile()
            if (tokenFile.exists()) tokenFile.delete()
            if (RuntimeEngines.isRootEngine() && hasOfficialCredential()) {
                val escaped = shellSingleQuote(tokenFile.absolutePath)
                runCatching {
                    val rmProc = ProcessBuilder("su", "-c", "rm -f $escaped").start()
                    if (!rmProc.waitFor(5L, TimeUnit.SECONDS)) rmProc.destroyForcibly()
                }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            }
            if (hasOfficialCredential()) throw UiTextException(UiText.res(R.string.rt_auth_agy_signout_incomplete))
            onSignedInChanged(false, null)
            authStateFlow.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_OUT,
                message = UiText.res(R.string.rt_auth_agy_signed_out),
            )
        } catch (err: Throwable) {
            authStateFlow.value = AntigravityAuthState(
                status = AntigravityAuthStatus.SIGNED_IN,
                message = err.toUiText(R.string.rt_auth_agy_signout_failed),
                accountEmail = priorAccount,
            )
            throw err
        } finally {
            activePtyProc = null
            signoutScratchFile.delete()
        }
    }

    fun cancel() {
        val proc = activePtyProc
        if (proc != null) {
            Thread {
                runCatching {
                    proc.destroy()
                    Thread.sleep(200L)
                    if (proc.isAlive) proc.destroyForcibly()
                    proc.waitFor()
                }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
            }.apply { isDaemon = true }.start()
        }
        authStateFlow.value = snapshotPriorToLogin ?: AntigravityAuthState(AntigravityAuthStatus.SIGNED_OUT)
    }

    fun invalidateSession(message: UiText) {
        onSignedInChanged(false, null)
        authStateFlow.value = AntigravityAuthState(status = AntigravityAuthStatus.ERROR, message = message)
    }

    private companion object {
        const val LOG_TAG = "AntigravityAuthController"
    }
}

private const val OAUTH_FLOW_TIMEOUT_MS = 15L * 60L * 1_000L
private const val TRANSCRIPT_MAX_CHARS = 200_000
private const val TRANSCRIPT_RETAIN_CHARS = 100_000
private const val KITTY_DEC_HANDSHAKE_RESPONSE = "\u001B[?2026;1\$y\u001B[?2027;1\$y\u001B[?1u\n"

private val WS_STRIP_PATTERN = Regex("[\\r\\n\\t ]+")
private val GOOGLE_AUTH_PRIMARY_PATTERN = Regex(
    "https://accounts\\.google\\.com/[^\\s\\\"'<>]*?[?&]state=[A-Za-z0-9._~-]+",
)
private val GOOGLE_AUTH_FALLBACK_PATTERN = Regex(
    "https://accounts\\.google\\.com/[^\\s\"']{10,}|https://oauth2\\.googleapis\\.com/[^\\s\"']{10,}",
)
private val EMAIL_EXTRACTOR_PATTERN = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
private val ANSI_CSI_OSC_PATTERN = Regex("\\u001B(?:\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)|\\[[0-?]*[ -/]*[@-~]|[()][A-Z0-9])")
private val ALLOWED_URI_CHARS = (
    "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-._~:/?#[]@!$&'()*+,;=%"
).toSet()
private val OAUTH_PROMPT_HINTS = listOf("browser", "visit", "open", "code", "paste")

internal fun extractGoogleOAuthUrl(terminalText: String): String? {
    val squashed = terminalText.replace(WS_STRIP_PATTERN, "")
    val primaryMatch = GOOGLE_AUTH_PRIMARY_PATTERN.find(squashed)?.value
    if (primaryMatch != null && "client_id=" in primaryMatch && "code_challenge=" in primaryMatch) {
        return primaryMatch
    }
    val hasPromptHint = !terminalText.contains("Select login method", ignoreCase = true) &&
        OAUTH_PROMPT_HINTS.any { hint -> terminalText.contains(hint, ignoreCase = true) }
    if (!hasPromptHint) return null

    val fallbackUrls = GOOGLE_AUTH_FALLBACK_PATTERN.findAll(squashed)
        .map { m -> m.value.trimEnd { ch -> ch !in ALLOWED_URI_CHARS } }
        .filter { u -> u.length >= 30 && '.' in u }
        .toList()
    return fallbackUrls.firstOrNull { "google" in it } ?: fallbackUrls.firstOrNull()
}

internal fun isSignedInScreen(terminalText: String): Boolean {
    val shortcutPos = terminalText.lastIndexOf("for shortcuts", ignoreCase = true)
    val bannerPos = if (terminalText.contains("Google AI", ignoreCase = true)) {
        terminalText.lastIndexOf("Antigravity CLI", ignoreCase = true)
    } else {
        -1
    }
    val anchorIdx = maxOf(shortcutPos, bannerPos)
    if (anchorIdx < 0) return false
    val trailingSlice = terminalText.substring(anchorIdx)
    return !trailingSlice.contains("error", ignoreCase = true) &&
        !trailingSlice.contains("failed", ignoreCase = true)
}

private fun findAccountEmail(terminalText: String): String? =
    EMAIL_EXTRACTOR_PATTERN.findAll(terminalText)
        .map { it.value }
        .firstOrNull { !it.endsWith(".apps.googleusercontent.com", ignoreCase = true) }

private fun stripAnsiSequences(raw: String): String {
    val withoutCodes = raw.replace(ANSI_CSI_OSC_PATTERN, "")
    val sb = StringBuilder(withoutCodes.length)
    for (ch in withoutCodes) {
        if (ch == '\n' || ch == '\r' || ch == '\t' || ch.code >= 0x20) {
            sb.append(ch)
        }
    }
    return sb.toString()
}
