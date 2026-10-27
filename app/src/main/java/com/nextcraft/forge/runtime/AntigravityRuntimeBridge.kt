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

enum class AntigravityAuthStatus {
    SIGNED_OUT,
    STARTING,
    AWAITING_CODE,
    COMPLETING,
    SIGNED_IN,
    ERROR,
}

data class AntigravityAuthState(
    val status: AntigravityAuthStatus = AntigravityAuthStatus.SIGNED_OUT,
    val authorizationUrl: String? = null,
    val message: UiText? = null,
    val accountEmail: String? = null,
)

internal sealed interface AntigravityParsedEvent {
    data class Initialized(val conversationId: String) : AntigravityParsedEvent
    data class Text(val value: String, val done: Boolean = false) : AntigravityParsedEvent
    data object ResponseDone : AntigravityParsedEvent
    data class ToolStarted(val name: String, val detail: String, val rawName: String = name) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String, val rawName: String = name) : AntigravityParsedEvent
    data class ScheduledWakeup(val intervalMinutes: Int, val prompt: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (json.stringOrEmpty("event")) {
            "init" -> json.stringOrEmpty("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)

            "step_update" -> {
                val payload = json.optJSONObject("step_update") ?: return null
                val stepState = payload.stringOrEmpty("state")
                val stepKind = payload.stringOrEmpty("step_type")
                val chunk = payload.stringOrEmpty("text_delta")
                if (chunk.isNotEmpty()) {
                    return AntigravityParsedEvent.Text(
                        value = chunk,
                        done = stepKind.equals("agent_response", ignoreCase = true) && stepState == "DONE",
                    )
                }
                if (stepKind.equals("agent_response", ignoreCase = true) && stepState == "DONE") {
                    return AntigravityParsedEvent.ResponseDone
                }
                if (stepKind.contains("tool", ignoreCase = true) || stepKind.contains("command", ignoreCase = true)) {
                    val toolId = payload.stringOrEmpty("tool_name").ifBlank {
                        payload.optJSONObject("tool_info")?.stringOrEmpty("name").orEmpty()
                    }.ifBlank { stepKind.ifBlank { "Tool" } }
                    if (toolId.equals("schedule", ignoreCase = true)) {
                        val argNode = payload.optJSONObject("tool_info")?.optJSONObject("parameters")
                        val wakePrompt = argNode?.stringOrEmpty("Prompt")?.trim()?.removeSurrounding("\"")?.trim().orEmpty()
                        val waitSec = argNode?.opt("DurationSeconds")?.toString()?.trim()?.toLongOrNull() ?: 0L
                        if (wakePrompt.isNotBlank() && waitSec >= 60L) {
                            val clampedMins = ((waitSec + 59L) / 60L).coerceIn(5L, 7L * 24L * 60L).toInt()
                            return AntigravityParsedEvent.ScheduledWakeup(clampedMins, wakePrompt)
                        }
                    }
                    val uiLabel = formatAgyToolLabel(toolId)
                    val summary = extractAgyToolSummary(payload, toolId).ifBlank { uiLabel }
                    if (stepState == "DONE" || stepState == "ERROR") {
                        val infoObj = payload.optJSONObject("tool_info")
                        val rawOut = infoObj?.stringOrEmpty("output")?.takeIf(String::isNotBlank)?.let(::scrubAgyToolOutput)
                        val rawErr = infoObj?.optJSONObject("error")?.stringOrEmpty("message")?.takeIf(String::isNotBlank)?.let(::scrubAgyToolSummary)
                        AntigravityParsedEvent.ToolCompleted(uiLabel, rawOut ?: rawErr ?: summary, toolId)
                    } else {
                        AntigravityParsedEvent.ToolStarted(uiLabel, summary, toolId)
                    }
                } else {
                    null
                }
            }

            "result" -> {
                val resNode = json.optJSONObject("result") ?: json
                AntigravityParsedEvent.Result(
                    conversationId = resNode.stringOrEmpty("conversation_id").takeIf(String::isNotBlank),
                    status = resNode.stringOrEmpty("status", "ERROR"),
                    response = resNode.stringOrEmpty("response").takeIf(String::isNotBlank),
                    error = resNode.stringOrEmpty("error").takeIf(String::isNotBlank),
                )
            }

            else -> null
        }
    }
}

private fun formatAgyToolLabel(rawToolId: String): String = when (rawToolId.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content", "write_file", "edit_file" -> "Write"
    "view_file", "read_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    "call_mcp_tool" -> "MCP"
    else -> if (rawToolId.startsWith("mcp_", ignoreCase = true)) {
        "MCP · " + rawToolId.removePrefix("mcp_").replace('_', ' ')
    } else {
        rawToolId.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

private fun extractAgyToolSummary(stepNode: JSONObject, rawToolId: String): String {
    stepNode.stringOrEmpty("description").takeIf(String::isNotBlank)?.let { return scrubAgyToolSummary(it) }
    val toolInfo = stepNode.optJSONObject("tool_info")
    val params = toolInfo?.optJSONObject("parameters")
    if (rawToolId.equals("call_mcp_tool", ignoreCase = true) && params != null) {
        val srv = params.stringOrEmpty("ServerName")
        val fn = params.stringOrEmpty("ToolName")
        if (srv.isNotBlank() || fn.isNotBlank()) {
            return scrubAgyToolSummary(listOf(srv, fn).filter(String::isNotBlank).joinToString(" / "))
        }
    }
    val candidateKeys = when (rawToolId.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" -> listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    for (k in candidateKeys) {
        val v = params?.opt(k)?.toString()?.takeIf { it.isNotBlank() && it != "null" } ?: continue
        val lead = when {
            rawToolId.equals("schedule", ignoreCase = true) && k == "DurationSeconds" -> "Wait "
            rawToolId.equals("manage_task", ignoreCase = true) -> "$k: "
            else -> ""
        }
        val tail = if (rawToolId.equals("schedule", ignoreCase = true) && k == "DurationSeconds") "s" else ""
        return scrubAgyToolSummary("$lead$v$tail")
    }
    return params?.toString()?.takeUnless { it == "{}" }?.let(::scrubAgyToolSummary)
        ?: stepNode.optJSONObject("tool")?.toString()?.let(::scrubAgyToolSummary).orEmpty()
}

private val SECRET_KEY_VALUE_REGEX = Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)")
private val BEARER_HEADER_REGEX = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+")

private fun scrubAgyToolSummary(raw: String): String = raw
    .replace(SECRET_KEY_VALUE_REGEX, "$1$2••••")
    .replace(BEARER_HEADER_REGEX, "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

private fun scrubAgyToolOutput(raw: String): String {
    val cleaned = raw
        .replace(SECRET_KEY_VALUE_REGEX, "$1$2••••")
        .replace(BEARER_HEADER_REGEX, "$1••••")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    return if (cleaned.length <= 6_000) cleaned else "…\n" + cleaned.takeLast(6_000)
}

private const val DEFAULT_HELLO_TIMEOUT_MS = 90_000L

private suspend fun terminateProcessAfterResult(proc: Process) {
    if (!proc.isAlive) return
    proc.destroy()
    repeat(20) {
        if (!proc.isAlive) return
        delay(50)
    }
    if (proc.isAlive) proc.destroyForcibly()
    repeat(20) {
        if (!proc.isAlive) return
        delay(50)
    }
}

class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String?) -> Unit,
) : RuntimeBridge {
    private val runtimeInstaller = RuntimeInstaller(context)
    private val workspaceCheckpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val sharedEvents = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = sharedEvents
    private val completedSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var runningProcess: Process? = null
    @Volatile private var currentSessionId: String? = null
    @Volatile private var stopRequestedByUser = false
    @Volatile private var currentSlug = ""
    private val fgNotifier = ForegroundSession(context, "AntigravityBridge")

    private class WarmAgyInstance(
        val proc: Process,
        val logTailer: InotifyLogTailer,
        val logFile: File,
        val projectId: String,
        val modelId: String,
        val effortLevel: String,
        val convId: String?,
    )

    @Volatile private var standbyInstance: WarmAgyInstance? = null
    @Volatile private var standbyEpoch = 0L
    private val warmSupportCacheFile = File(context.filesDir, "agy-warm-mode")
    @Volatile private var supportsWarmStdin: Boolean? = null
    @Volatile private var warmCacheChecked = false

    private fun installedAgyVersion(): String =
        runtimeInstaller.installedAgentVersions()[AgentKind.ANTIGRAVITY].orEmpty()

    private fun loadCachedWarmSupport(): Boolean? = runCatching {
        val parts = warmSupportCacheFile.readText().trim().split(' ', limit = 2)
        if (parts.size == 2 && parts[0] == installedAgyVersion()) parts[1].toBooleanStrictOrNull() else null
    }.getOrNull()

    private fun persistWarmSupport(supported: Boolean) {
        supportsWarmStdin = supported
        runCatching { warmSupportCacheFile.writeText("${installedAgyVersion()} $supported") }
            .onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
    }

    private fun disposeWarmInstance(instance: WarmAgyInstance?) {
        instance ?: return
        runCatching { instance.logTailer.close() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        runCatching { instance.proc.destroy() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        Thread {
            runCatching {
                Thread.sleep(500)
                if (instance.proc.isAlive) instance.proc.destroyForcibly()
                instance.logFile.delete()
            }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }.apply { isDaemon = true }.start()
    }

    private fun sweepOrphanedProcessesAndLogs() {
        Thread({
            runCatching {
                val cmd = "for p in \$(pgrep -f '[/]root/[.]local/bin/agy.*--input-format stream-json' 2>/dev/null); do " +
                    "ppid=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status 2>/dev/null); " +
                    "[ \"\$ppid\" = \"1\" ] && kill -9 \$p 2>/dev/null; done; true"
                val shellArgs = if (RuntimeEngines.isRootEngine()) listOf("su", "-c", cmd) else listOf("/system/bin/sh", "-c", cmd)
                val reaper = ProcessBuilder(shellArgs)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                if (!reaper.waitFor(5, TimeUnit.SECONDS)) reaper.destroyForcibly()
            }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            runCatching {
                val thresholdMs = System.currentTimeMillis() - 3L * 24L * 3600L * 1000L
                val cliLogsDir = File(runtimeInstaller.installedRuntime().rootfs, "root/.gemini/antigravity-cli/log")
                cliLogsDir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.startsWith("cli-") && f.lastModified() < thresholdMs) f.delete()
                }
            }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }, "forge-agy-orphan-reaper").apply { isDaemon = true }.start()
    }

    private fun applyPowerAffinityProfile(lowPowerStandby: Boolean) {
        Thread({
            runCatching {
                val cpuMask = if (lowPowerStandby) "0f" else "ff"
                val cmd = buildString {
                    append("for p in \$(pgrep -f '[/]root/[.]local/bin/agy.*--input-format stream-json' 2>/dev/null); do ")
                    append("taskset -ap $cpuMask \$p >/dev/null 2>&1 || true; ")
                    append("done; ")
                    if (lowPowerStandby) {
                        append("for r in /sys/class/remoteproc/remoteproc*/power/control; do [ -w \"\$r\" ] && echo auto > \"\$r\" 2>/dev/null || true; done; ")
                    }
                    append("true")
                }
                val shellArgs = if (RuntimeEngines.isRootEngine()) listOf("su", "-c", cmd) else listOf("/system/bin/sh", "-c", cmd)
                val tuner = ProcessBuilder(shellArgs)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                if (!tuner.waitFor(3, TimeUnit.SECONDS)) tuner.destroyForcibly()
            }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }, "forge-agy-standby-power").apply { isDaemon = true }.start()
    }

    private fun armStandbyEvictionTimer(instance: WarmAgyInstance) {
        applyPowerAffinityProfile(lowPowerStandby = true)
        val epoch = ++standbyEpoch
        Thread {
            runCatching { Thread.sleep(WARM_STANDBY_TTL_MS) }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            if (standbyEpoch == epoch && standbyInstance === instance) {
                standbyInstance = null
                disposeWarmInstance(instance)
            }
        }.apply { isDaemon = true }.start()
    }

    fun configureProjectRoot(projectId: String, rootPath: String) =
        workspaceCheckpoints.configureProjectRoot(projectId, rootPath)

    suspend fun hello(timeoutMillis: Long = DEFAULT_HELLO_TIMEOUT_MS): String = withContext(Dispatchers.IO) {
        check(runtimeInstaller.isAgentInstalled(AgentKind.ANTIGRAVITY)) { "Antigravity CLI is not installed." }
        val startMs = System.currentTimeMillis()
        try {
            withTimeout(timeoutMillis) {
                val rt = runtimeInstaller.installedRuntime()
                val scratchDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val cmdArgs = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json", "--output-format", "stream-json", "--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    appendModelAndEffortFlags(model(), effort())
                    add("--new-project")
                }
                val logOut = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val proc = runtimeInstaller.process(
                    rt.chrootExec,
                    rt.rootfs,
                    scratchDir,
                    emptyMap(),
                    cmdArgs,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = logOut,
                    trustClaudeWorkspace = false,
                )
                try {
                    val helloPayload = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    proc.outputStream.write(helloPayload.toByteArray())
                    proc.outputStream.flush()
                    proc.outputStream.close()
                    var capturedReply: String? = null
                    var lastRawLine = ""
                    fun inspectLine(raw: String): Boolean {
                        val trimmed = raw.trimEnd('\r')
                        if (trimmed.isBlank()) return false
                        lastRawLine = trimmed.takeLast(500)
                        when (val ev = AntigravityEventParser.parse(trimmed)) {
                            is AntigravityParsedEvent.Text -> if (capturedReply == null && ev.value.isNotBlank()) capturedReply = ev.value
                            is AntigravityParsedEvent.Result -> {
                                if (ev.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (capturedReply == null && !ev.response.isNullOrBlank()) capturedReply = ev.response
                                    return true
                                }
                                throw AntigravitySessionException(formatFriendlyError(ev.error ?: ev.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var completed = false
                    InotifyLogTailer(logOut).use { tailer ->
                        while (!completed && (proc.isAlive || tailer.hasUnreadBytes())) {
                            ensureActive()
                            for (line in tailer.readLines(timeoutMs = 50L)) {
                                if (inspectLine(line)) {
                                    completed = true
                                    break
                                }
                            }
                        }
                        if (!completed) {
                            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                                if (inspectLine(line)) {
                                    completed = true
                                    break
                                }
                            }
                        }
                    }
                    check(completed) { formatFriendlyError(lastRawLine.ifBlank { "Antigravity exited without answering" }) }
                    terminateProcessAfterResult(proc)
                    capturedReply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { proc.destroy() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching { if (proc.isAlive) proc.destroyForcibly() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching { logOut.delete() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching { scratchDir.deleteRecursively() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                }
            }
        } catch (_: TimeoutCancellationException) {
            val diag = readRecentCliLogTail(startMs)
            val fallbackMsg = "Antigravity did not answer within ${timeoutMillis / 1000} s. Check the connection and try again."
            val resolvedMsg = if (diag.isBlank()) {
                fallbackMsg
            } else {
                antigravityFriendlyError("", diag).takeUnless { it == DEFAULT_AGY_ERROR_TEXT } ?: fallbackMsg
            }
            throw AntigravitySessionException(resolvedMsg)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            throw AntigravitySessionException(antigravityFriendlyError(err.message.orEmpty(), readRecentCliLogTail(startMs)))
        }
    }

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        val turnStartMs = System.currentTimeMillis()
        currentSessionId = sessionId
        stopRequestedByUser = false
        currentSlug = projectSlug
        fgNotifier.begin()
        completedSessionIds.remove(sessionId)
        sharedEvents.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!runtimeInstaller.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            dispatchSessionFailed(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            currentSessionId = null
            currentSlug = ""
            fgNotifier.clearStart()
            return@withContext sessionId
        }

        var sessionLogFile: File? = null
        var activeTailer: InotifyLogTailer? = null
        var preservedForWarmStandby = false
        var decodedConv: SavedConversation? = null
        var cancelCallback: (() -> Unit)? = null

        runCatching {
            cancelCallback = {
                stopRequestedByUser = true
                runningProcess?.let { proc ->
                    Thread {
                        runCatching {
                            proc.destroy()
                            Thread.sleep(500)
                            if (proc.isAlive) proc.destroyForcibly()
                        }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    }.apply { isDaemon = true }.start()
                }
            }
            cancelCallback?.let { RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sessionId", it) }
            fgNotifier.start(projectSlug)
            updateForegroundStatus("Starting Antigravity…")
            val rt = runtimeInstaller.installedRuntime()
            val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
            workspaceCheckpoints.createCheckpoint(projectId, wsDir)
            val preSnapshot = workspaceCheckpoints.snapshot(wsDir)
            if (!warmCacheChecked) {
                supportsWarmStdin = loadCachedWarmSupport()
                warmCacheChecked = true
            }
            val selectedModel = model()
            val selectedEffort = effort()
            decodedConv = SavedConversation.decode(conversationId(projectId))
            val existingConvId = decodedConv?.id
            val unseenMessages = conversationHistory.unseenBy(decodedConv).recentWithinCharacterBudget(HISTORY_CHAR_BUDGET)
            val fullUserPrompt = StringBuilder()
                .apply { appendConversationHistory(unseenMessages, "/workspace/$projectSlug", resumed = decodedConv != null) }
                .append(prompt)
                .toString()

            fun persistConvId(id: String) =
                saveConversationId(projectId, SavedConversation(id, conversationHistory.size + 1).encode())

            val prevWarm = standbyInstance
            standbyInstance = null
            standbyEpoch++
            val reusableWarm = prevWarm?.takeIf {
                supportsWarmStdin == true &&
                    it.proc.isAlive &&
                    it.projectId == projectId &&
                    it.modelId == selectedModel &&
                    it.effortLevel == selectedEffort &&
                    it.convId == existingConvId
            }
            if (reusableWarm == null) {
                disposeWarmInstance(prevWarm)
                sweepOrphanedProcessesAndLogs()
            }
            val proc: Process
            val tailer: InotifyLogTailer
            if (reusableWarm != null) {
                applyPowerAffinityProfile(lowPowerStandby = false)
                proc = reusableWarm.proc
                tailer = reusableWarm.logTailer
                sessionLogFile = reusableWarm.logFile
                tailer.readLines(timeoutMs = 0L)
            } else {
                val cmdArgs = antigravityCommand(selectedModel, selectedEffort, existingConvId)
                proc = runtimeInstaller.process(
                    rt.chrootExec,
                    rt.rootfs,
                    wsDir,
                    emptyMap(),
                    cmdArgs,
                    guestWorkspacePath = "/workspace/$projectSlug",
                    emulateHardLinks = false,
                    trustClaudeWorkspace = false,
                )
                runningProcess = proc
                val nativeProc = proc as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
                sessionLogFile = nativeProc.outputFile
                tailer = InotifyLogTailer(nativeProc.outputFile)
            }
            activeTailer = tailer
            runningProcess = proc
            if (stopRequestedByUser) proc.destroy()
            val userEnvelope = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, fullUserPrompt)))
                .toString() + "\n"
            proc.outputStream.write(userEnvelope.toByteArray())
            proc.outputStream.flush()
            var stdinKeptOpen = supportsWarmStdin != false
            if (!stdinKeptOpen) proc.outputStream.close()

            val promptDispatchedAtMs = System.currentTimeMillis()
            var receivedAnyEvent = false
            var lastRawLine = ""
            var sawTerminalResult = false
            var emittedAssistantText = false
            var finalResponseMarkedDone = false
            var runningToolLabel: String? = null
            var runningToolSummary = ""
            var toolStartTimestampMs = 0L
            var lastGradlePingMs = 0L
            var lastRetryScanMs = 0L
            var prevRetryBanner: String? = null
            var pendingScheduleWakeup: AntigravityParsedEvent.ScheduledWakeup? = null
            var scheduleTriggeredAtMs = 0L

            suspend fun consumeStreamLine(rawLine: String) {
                val cleanLine = rawLine.trimEnd('\r')
                if (cleanLine.isBlank()) return
                lastRawLine = cleanLine.takeLast(1_000)
                val parsedEvent = AntigravityEventParser.parse(cleanLine)
                if (parsedEvent != null) receivedAnyEvent = true
                when (val ev = parsedEvent) {
                    is AntigravityParsedEvent.Initialized -> persistConvId(ev.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        emittedAssistantText = true
                        finalResponseMarkedDone = ev.done
                        if (pendingScheduleWakeup != null && ev.done) sawTerminalResult = true
                        updateForegroundStatus("Antigravity is working…")
                        sharedEvents.emit(RuntimeEvent.AssistantDelta(sessionId, ev.value))
                    }
                    is AntigravityParsedEvent.ResponseDone -> {
                        if (emittedAssistantText) finalResponseMarkedDone = true
                        if (pendingScheduleWakeup != null) sawTerminalResult = true
                    }
                    is AntigravityParsedEvent.ScheduledWakeup -> {
                        pendingScheduleWakeup = ev
                        scheduleTriggeredAtMs = System.currentTimeMillis()
                        val infoText = "Scheduled in ${ev.intervalMinutes}m: ${ev.prompt}"
                        sharedEvents.emit(RuntimeEvent.ToolStarted(sessionId, "Schedule", infoText))
                        sharedEvents.emit(RuntimeEvent.ToolCompleted(sessionId, "Schedule", infoText))
                        sharedEvents.emit(RuntimeEvent.AssistantDelta(sessionId, "\n\n[[schedule: ${ev.intervalMinutes}m | ${ev.prompt}]]"))
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        finalResponseMarkedDone = false
                        runningToolLabel = ev.name
                        runningToolSummary = ev.detail
                        toolStartTimestampMs = System.currentTimeMillis()
                        lastGradlePingMs = toolStartTimestampMs
                        updateForegroundStatus("Running ${ev.name}: ${ev.detail}")
                        sharedEvents.emit(RuntimeEvent.ToolStarted(sessionId, ev.name, ev.detail))
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        if (runningToolLabel == ev.name) runningToolLabel = null
                        updateForegroundStatus("Antigravity is working…")
                        sharedEvents.emit(RuntimeEvent.ToolCompleted(sessionId, ev.name, ev.detail))
                    }
                    is AntigravityParsedEvent.Result -> {
                        ev.conversationId?.let(::persistConvId)
                        val finishedCleanlyBeforeDrop = emittedAssistantText && finalResponseMarkedDone && runningToolLabel == null
                        if (ev.status.equals("SUCCESS", ignoreCase = true) || finishedCleanlyBeforeDrop) {
                            if (!emittedAssistantText && !ev.response.isNullOrBlank()) {
                                emittedAssistantText = true
                                sharedEvents.emit(RuntimeEvent.AssistantDelta(sessionId, ev.response))
                            }
                            sawTerminalResult = true
                        } else {
                            throw AntigravitySessionException(
                                formatFriendlyError(
                                    ev.error ?: ev.status,
                                    readRecentCliLogTail(turnStartMs),
                                    System.currentTimeMillis() - turnStartMs,
                                ),
                            )
                        }
                    }
                    null -> Unit
                }
            }

            run {
                readLoop@ while (proc.isAlive || tailer.hasUnreadBytes()) {
                    val batch = tailer.readLines(timeoutMs = 250L)
                    if (batch.isEmpty()) {
                        val nowMs = System.currentTimeMillis()
                        if (pendingScheduleWakeup != null && nowMs - scheduleTriggeredAtMs >= 3_500L) {
                            sawTerminalResult = true
                            break@readLoop
                        }
                        if (stdinKeptOpen && !receivedAnyEvent && nowMs - promptDispatchedAtMs >= WARM_INITIAL_PROBE_MS) {
                            if (reusableWarm != null) {
                                val silenceDurationMs = nowMs - promptDispatchedAtMs
                                val aliveInLogs = silenceDurationMs < WARM_REUSE_CEILING_MS &&
                                    (silenceDurationMs < WARM_REUSE_SILENCE_MS || isCliLogRecentlyModified(nowMs))
                                if (!aliveInLogs) {
                                    throw AntigravitySessionException("Antigravity stopped responding. Send the message again.")
                                }
                            } else {
                                persistWarmSupport(false)
                                stdinKeptOpen = false
                                runCatching { proc.outputStream.close() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                            }
                        }
                        updateForegroundStatus(
                            runningToolLabel?.let { lbl -> "Running $lbl: $runningToolSummary" } ?: "Antigravity is working…",
                        )
                        if (nowMs - lastRetryScanMs >= RETRY_POLL_INTERVAL_MS) {
                            lastRetryScanMs = nowMs
                            antigravityRetryStatus(readRecentCliLogTail(turnStartMs))
                                ?.takeIf { it != prevRetryBanner }
                                ?.let { banner ->
                                    prevRetryBanner = banner
                                    updateForegroundStatus(banner)
                                    sharedEvents.emit(RuntimeEvent.RuntimeLog(sessionId, "Antigravity", banner))
                                }
                        }
                        if (
                            runningToolLabel == "Bash" &&
                            runningToolSummary.contains("gradle", ignoreCase = true) &&
                            nowMs - toolStartTimestampMs >= 10_000L &&
                            nowMs - lastGradlePingMs >= 10_000L
                        ) {
                            lastGradlePingMs = nowMs
                            sharedEvents.emit(
                                RuntimeEvent.ToolProgress(
                                    sessionId,
                                    "Bash",
                                    antigravityGradleProgress(runningToolSummary, nowMs - toolStartTimestampMs),
                                ),
                            )
                        }
                        continue
                    }
                    for (line in batch) {
                        consumeStreamLine(line)
                        if (sawTerminalResult) break@readLoop
                    }
                }
                if (!sawTerminalResult) {
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        consumeStreamLine(line)
                        if (sawTerminalResult) break
                    }
                }
            }

            if (pendingScheduleWakeup != null) {
                terminateProcessAfterResult(proc)
            } else if (sawTerminalResult && stdinKeptOpen && proc.isAlive) {
                persistWarmSupport(true)
                val warmEntry = WarmAgyInstance(
                    proc = proc,
                    logTailer = tailer,
                    logFile = sessionLogFile!!,
                    projectId = projectId,
                    modelId = selectedModel,
                    effortLevel = selectedEffort,
                    convId = SavedConversation.decode(conversationId(projectId))?.id,
                )
                standbyInstance = warmEntry
                preservedForWarmStandby = true
                armStandbyEvictionTimer(warmEntry)
            } else if (sawTerminalResult) {
                terminateProcessAfterResult(proc)
            } else if (emittedAssistantText && finalResponseMarkedDone && runningToolLabel == null) {
                sawTerminalResult = true
                terminateProcessAfterResult(proc)
            } else {
                val exitCode = proc.waitFor()
                check(exitCode == 0 && sawTerminalResult) {
                    formatFriendlyError(
                        lastRawLine.ifBlank { "Antigravity exited with code $exitCode" },
                        readRecentCliLogTail(turnStartMs),
                        System.currentTimeMillis() - turnStartMs,
                    )
                }
            }

            val modifiedPaths = workspaceCheckpoints.changedFiles(wsDir, preSnapshot)
            if (modifiedPaths.isNotEmpty()) {
                workspaceCheckpoints.saveChangedPaths(projectId, modifiedPaths)
                val visiblePending = workspaceCheckpoints.readChangedPaths(projectId).filterNot(workspaceCheckpoints::isInternalRuntimePath)
                sharedEvents.emit(RuntimeEvent.FilesChanged(sessionId, workspaceCheckpoints.buildChangeDetails(projectId, wsDir, visiblePending)))
            } else if (workspaceCheckpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            dispatchSessionCompleted(sessionId)
            fgNotifier.finishRes(true, projectSlug, R.string.notif_agent_finished_in, "Antigravity", projectSlug)
        }.onFailure { err ->
            val diag = readRecentCliLogTail(turnStartMs)
            runningProcess?.let { proc -> runCatching { if (proc.isAlive) proc.destroyForcibly() } }
            val convMissing = decodedConv != null && MISSING_CONVERSATION_PATTERN.containsMatchIn("${err.message.orEmpty()}\n$diag")
            if (convMissing) saveConversationId(projectId, null)
            val errBanner = when {
                stopRequestedByUser -> "Stopped by user"
                convMissing -> ClaudeRuntimeBridge.SESSION_LOST_MESSAGE
                err is AntigravitySessionException -> err.message.orEmpty()
                else -> antigravityFriendlyError(err.message.orEmpty(), diag, System.currentTimeMillis() - turnStartMs)
            }
            dispatchSessionFailed(sessionId, errBanner)
            if (stopRequestedByUser) fgNotifier.cancel() else fgNotifier.finish(false, projectSlug, errBanner)
        }

        if (!preservedForWarmStandby) {
            runCatching { activeTailer?.close() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            runCatching { sessionLogFile?.delete() }.onFailure { ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }
        runningProcess = null
        currentSessionId = null
        currentSlug = ""
        fgNotifier.clearStart()
        RuntimeTaskController.release(cancelCallback)
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (currentSessionId == sessionId) {
            stopRequestedByUser = true
            runningProcess?.destroy()
            delay(500)
            if (runningProcess?.isAlive == true) runningProcess?.destroyForcibly()
            dispatchSessionFailed(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        currentSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.revertLatestTurn(projectId, wsDir).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        workspaceCheckpoints.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = workspaceCheckpoints.readChangedPaths(projectId).filterNot(workspaceCheckpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else workspaceCheckpoints.buildChangeDetails(projectId, workspaceCheckpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.revertFile(projectId, wsDir, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.acceptFile(projectId, wsDir, path)
    }

    private suspend fun dispatchSessionCompleted(sessionId: String) {
        if (completedSessionIds.add(sessionId)) sharedEvents.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun dispatchSessionFailed(sessionId: String, reason: String) {
        if (completedSessionIds.add(sessionId)) sharedEvents.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun updateForegroundStatus(statusText: String) {
        if (currentSessionId == null) return
        fgNotifier.progress(currentSlug, statusText)
    }

    private fun formatFriendlyError(raw: String, diagnostic: String = "", elapsedMs: Long = 0L): String =
        antigravityFriendlyError(raw, diagnostic, elapsedMs = elapsedMs)

    private fun isCliLogRecentlyModified(nowMs: Long): Boolean = runCatching {
        val logFolder = File(runtimeInstaller.installedRuntime().rootfs, "root/.gemini/antigravity-cli/log")
        logFolder.listFiles()?.any { it.isFile && it.name.startsWith("cli-") && nowMs - it.lastModified() < 45_000L } == true
    }.getOrDefault(false)

    private fun readRecentCliLogTail(sinceMs: Long): String = runCatching {
        val logFolder = File(runtimeInstaller.installedRuntime().rootfs, "root/.gemini/antigravity-cli/log")
        val newestLog = logFolder.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cli-") && it.lastModified() >= sinceMs - 2_000L }
            ?.maxByOrNull(File::lastModified)
            ?: return@runCatching ""
        newestLog.readTailText(12_000)
    }.getOrDefault("")
}

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

private const val RETRY_POLL_INTERVAL_MS = 5_000L
private const val WARM_INITIAL_PROBE_MS = 20_000L
private const val WARM_REUSE_SILENCE_MS = 90_000L
private const val WARM_REUSE_CEILING_MS = 10 * 60_000L
private const val WARM_STANDBY_TTL_MS = 3 * 60_000L
private const val DEFAULT_AGY_ERROR_TEXT = "Antigravity could not complete the task."

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

private class AntigravitySessionException(message: String) : IllegalStateException(message)

private const val HISTORY_CHAR_BUDGET = 160_000
private val MISSING_CONVERSATION_PATTERN = Regex("conversation[^\n]{0,60}(not found|does not exist|no such|unknown)", RegexOption.IGNORE_CASE)

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

private fun MutableList<String>.appendModelAndEffortFlags(model: String, effort: String) {
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
        appendLine("  `timeout 15m gradle ${AndroidToolchain.GRADLE_BASE_FLAGS} assembleDebug --console=plain --stacktrace`")
        appendLine("- For a later build with an existing debug APK, prefer the cached fast path:")
        appendLine("  `timeout 5m gradle --offline --init-script ${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH} -Pandroid.aapt2FromMavenOverride=${AndroidToolchain.AAPT2_GUEST_PATH} assembleDebug --console=plain --stacktrace`")
        appendLine("NextCraft Forge bundles the standard Android dependencies locally (AGP ${AndroidToolchain.AGP_VERSION}, Kotlin ${AndroidToolchain.KOTLIN_VERSION}, compileSdk ${AndroidToolchain.COMPILE_SDK}, JDK ${AndroidToolchain.JDK_MAJOR}). New projects must use these versions and the AGP 9 DSL (built-in Kotlin, no org.jetbrains.kotlin.android plugin, no kotlinOptions, Compose via org.jetbrains.kotlin.plugin.compose). Projects made with AGP 8.x / Kotlin 1.9 are not cached offline anymore: migrate them or build them online once. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.")
        appendLine("Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.")
        appendLine("When scheduling a delayed or periodic follow-up (such as a 15-minute GitHub CI/PR check), prefer ending your reply with `[[schedule: 15m | <prompt>]]` (or `[[unschedule]]` once complete) instead of keeping an in-process timer running, so the current agent turn stops cleanly and NextCraft Forge's app-level scheduler wakes up the chat after the delay.")
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
