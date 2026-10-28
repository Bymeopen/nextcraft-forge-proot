package com.nextcraft.forge.runtime

import android.content.Context
import androidx.annotation.StringRes
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.ui.UiText
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal fun antigravityFriendlyError(
    raw: String,
    diagnostic: String = "",
    elapsedMs: Long = Long.MAX_VALUE,
): String {
    val normalizedRaw = raw.replace(Regex("\\s+"), " ").trim()
    val hitTurnCeiling = elapsedMs >= AGY_TURN_LIMIT_MS - 5 * 60_000L
    return matchKnownAgyFailure(normalizedRaw, considerTimeout = hitTurnCeiling)
        ?: matchKnownAgyFailure("$normalizedRaw ${stripSilentAuthStartupNoise(diagnostic)}", considerTimeout = false)
        ?: normalizedRaw.ifBlank { DEFAULT_AGY_ERROR_TEXT }.take(500)
}

private fun stripSilentAuthStartupNoise(rawDiag: String): String {
    val silentAuthOk = rawDiag.contains("silent auth succeeded", ignoreCase = true)
    return rawDiag.lineSequence()
        .filterNot { silentAuthOk && it.contains("not logged in", ignoreCase = true) }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private const val AGY_TURN_LIMIT_MS = 60 * 60_000L

private fun matchKnownAgyFailure(text: String, considerTimeout: Boolean): String? = when {
    NETWORK_FAILURE_TOKENS.any { text.contains(it, ignoreCase = true) } ->
        "Antigravity could not reach Google. Check your internet or DNS connection, then try again."
    UPSTREAM_SERVER_TOKENS.any { text.contains(it, ignoreCase = true) } ->
        "Google's Antigravity server returned an error. Wait a moment and try again; your files were kept."
    text.contains("invalid model selection", ignoreCase = true) || text.contains("not recognized as a known model", ignoreCase = true) ->
        "Antigravity could not fetch the model catalog from Google (network/DNS timeout). Check your connection and try again."
    text.contains("stream idle timeout", ignoreCase = true) || text.contains("context deadline exceeded", ignoreCase = true) ->
        "Google sent nothing for 5 minutes while the model was thinking, so Antigravity dropped the request. Your files were kept."
    text.contains("stream was interrupted", ignoreCase = true) ->
        "The connection to Google dropped mid-answer. Send the message again to continue."
    text.contains("authentication required", ignoreCase = true) ||
        text.contains("authentication failed", ignoreCase = true) ||
        text.contains("not signed in", ignoreCase = true) ||
        text.contains("not logged in", ignoreCase = true) ->
        "Antigravity needs Google sign-in. Open Settings → Coding agent."
    text.contains("out of credits", ignoreCase = true) || text.contains("quota", ignoreCase = true) ->
        "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
    considerTimeout && (text.contains("timed out", ignoreCase = true) || text.contains("timeout", ignoreCase = true)) ->
        "Antigravity reached the 60-minute task limit. Your files were kept."
    considerTimeout && text.contains("model", ignoreCase = true) && (text.contains("invalid", ignoreCase = true) || text.contains("unknown", ignoreCase = true)) ->
        "The selected Antigravity model is unavailable. Refresh models in Settings."
    else -> null
}

private val NETWORK_FAILURE_TOKENS = listOf(
    "network issue connecting to the server",
    "dial tcp",
    "read tcp",
    "connection timed out",
    "connection reset",
    "tls handshake timeout",
    "i/o timeout",
    "network is unreachable",
    "no address associated with hostname",
    "temporary failure in name resolution",
    "unknownhost",
    "failed to fetch user info",
    "userinfo request failed",
)

private val UPSTREAM_SERVER_TOKENS = listOf(
    "code 500",
    "code 502",
    "code 503",
    "internal error encountered",
    "unavailable (code",
)

internal const val RETRY_POLL_INTERVAL_MS = 5_000L
internal const val WARM_INITIAL_PROBE_MS = 20_000L
internal const val WARM_REUSE_SILENCE_MS = 90_000L
internal const val WARM_REUSE_CEILING_MS = 10 * 60_000L
internal const val WARM_STANDBY_TTL_MS = 3 * 60_000L
internal const val DEFAULT_AGY_ERROR_TEXT = "Antigravity could not complete the task."

private val LOG_RETRY_REGEX = Regex("""Run: attempt (\d+) failed \((.*)\), retrying in (\w+)""")

internal fun antigravityRetryStatus(logTail: String): String? {
    val lastMatch = LOG_RETRY_REGEX.findAll(logTail).lastOrNull() ?: return null
    val (attemptNum, errCause, backoffDelay) = lastMatch.destructured
    val label = when {
        UPSTREAM_SERVER_TOKENS.any { errCause.contains(it, ignoreCase = true) } -> "Google server error"
        NETWORK_FAILURE_TOKENS.any { errCause.contains(it, ignoreCase = true) } -> "Connection to Google failed"
        else -> "Request failed"
    }
    return "$label, retrying in $backoffDelay (attempt $attemptNum)"
}

internal class AntigravitySessionException(message: String) : IllegalStateException(message)

internal const val HISTORY_CHAR_BUDGET = 160_000
internal val MISSING_CONVERSATION_PATTERN = Regex("conversation[^\n]{0,60}(not found|does not exist|no such|unknown)", RegexOption.IGNORE_CASE)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "60m"))
    add("--dangerously-skip-permissions")
    appendModelAndEffortFlags(model, effort)
    if (!conversationId.isNullOrBlank()) {
        addAll(listOf("--conversation", conversationId))
    } else {
        add("--new-project")
    }
}

internal fun MutableList<String>.appendModelAndEffortFlags(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    }
    val hasEmbeddedEffort = Regex("-(low|medium|high)$").containsMatchIn(model)
    if (!hasEmbeddedEffort && effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String {
    val escapedSlug = buildString(projectSlug.length) {
        for (ch in projectSlug) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
    return buildString {
        appendLine("<nextcraft_workspace>")
        appendLine("The active project workspace is /workspace/$escapedSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.")
        appendLine()
        appendLine("For Android projects, never use plain `gradle build` or start a Gradle daemon. Build only the debug APK. First check whether `app/build/outputs/apk/debug/` already contains an APK.")
        appendLine("- For a first build with no existing debug APK, allow dependency resolution and use:")
        appendLine("  `timeout --foreground -k 10s 15m gradle ${AndroidToolchain.GRADLE_BASE_FLAGS} assembleDebug --console=plain --stacktrace < /dev/null`")
        appendLine("- For a later build with an existing debug APK, prefer the cached fast path:")
        appendLine("  `timeout --foreground -k 10s 5m gradle --offline --init-script ${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH} -Pandroid.aapt2FromMavenOverride=${AndroidToolchain.AAPT2_GUEST_PATH} assembleDebug --console=plain --stacktrace < /dev/null`")
        appendLine("NextCraft Forge bundles the standard Android dependencies locally (AGP ${AndroidToolchain.AGP_VERSION}, Kotlin ${AndroidToolchain.KOTLIN_VERSION}, compileSdk ${AndroidToolchain.COMPILE_SDK}, JDK ${AndroidToolchain.JDK_MAJOR}). New projects must use these versions and the AGP 9 DSL (built-in Kotlin, no org.jetbrains.kotlin.android plugin, no kotlinOptions, Compose via org.jetbrains.kotlin.plugin.compose). Projects made with AGP 8.x / Kotlin 1.9 are not cached offline anymore: migrate them or build them online once. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout --foreground -k 10s 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.")
        appendLine("Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.")
        appendLine("When scheduling a delayed or periodic follow-up (such as a 15-minute GitHub CI/PR check), NEVER call the built-in `schedule` tool; instead, end your reply directly with `[[schedule: 15m | <prompt>]]` (or `[[unschedule]]` once complete) so the foreground turn finishes immediately and NextCraft Forge's background scheduler wakes up the chat after the delay.")
        appendLine("Operate with the precision, speed, and surgical execution style of Claude Code: target under 10 seconds per turn, combine related operations into a single tool step whenever possible, and deliver concise, high-signal responses.")
        ANTI_HALLUCINATION_RULES.forEach { appendLine(it) }
        appendLine("</nextcraft_workspace>")
        appendLine()
        append(prompt)
    }
}

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val sec = elapsedMillis.coerceAtLeast(0L) / 1_000L
    return "Gradle is running · ${sec}s\n$command\nFull output will appear when the command completes."
}
