package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class AntigravityAuthStatus { SIGNED_OUT, STARTING, AWAITING_CODE, COMPLETING, SIGNED_IN, ERROR }

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
    data class ToolStarted(val name: String, val detail: String) : AntigravityParsedEvent
    data class ToolCompleted(val name: String, val detail: String) : AntigravityParsedEvent
    data class Result(
        val conversationId: String?,
        val status: String,
        val response: String?,
        val error: String?,
    ) : AntigravityParsedEvent
}

internal object AntigravityEventParser {
    fun parse(line: String): AntigravityParsedEvent? {
        val root = runCatching { JSONObject(line) }.getOrNull() ?: return null
        return when (root.stringOrEmpty("event")) {
            "init" -> root.stringOrEmpty("conversation_id")
                .takeIf(String::isNotBlank)
                ?.let(AntigravityParsedEvent::Initialized)
            "step_update" -> {
                val step = root.optJSONObject("step_update") ?: return null
                val state = step.stringOrEmpty("state")
                val type = step.stringOrEmpty("step_type")
                val delta = step.stringOrEmpty("text_delta")
                if (delta.isNotEmpty()) {
                    return AntigravityParsedEvent.Text(
                        value = delta,
                        done = type.equals("agent_response", true) && state == "DONE",
                    )
                }
                if (type.equals("agent_response", true) && state == "DONE") {
                    return AntigravityParsedEvent.ResponseDone
                }
                if (type.contains("tool", true) || type.contains("command", true)) {
                    val rawName = step.stringOrEmpty("tool_name").ifBlank {
                        step.optJSONObject("tool_info")?.stringOrEmpty("name").orEmpty()
                    }.ifBlank { type.ifBlank { "Tool" } }
                    val name = antigravityToolDisplayName(rawName)
                    val detail = antigravityToolDetail(step, rawName).ifBlank { name }
                    if (state in setOf("DONE", "ERROR")) {
                        val output = step.optJSONObject("tool_info")
                            ?.stringOrEmpty("output")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::antigravityToolOutput)
                        val error = step.optJSONObject("tool_info")
                            ?.optJSONObject("error")
                            ?.stringOrEmpty("message")
                            ?.takeIf(String::isNotBlank)
                            ?.let(::redactToolDetail)
                        AntigravityParsedEvent.ToolCompleted(name, output ?: error ?: detail)
                    } else {
                        AntigravityParsedEvent.ToolStarted(name, detail)
                    }
                } else null
            }
            "result" -> {
                val result = root.optJSONObject("result") ?: root
                AntigravityParsedEvent.Result(
                    result.stringOrEmpty("conversation_id").takeIf(String::isNotBlank),
                    result.stringOrEmpty("status", "ERROR"),
                    result.stringOrEmpty("response").takeIf(String::isNotBlank),
                    result.stringOrEmpty("error").takeIf(String::isNotBlank),
                )
            }
            else -> null // Forward compatible with new agy event types.
        }
    }
}

private fun antigravityToolDisplayName(name: String): String = when (name.lowercase()) {
    "run_command" -> "Bash"
    "write_to_file", "replace_file_content", "multi_replace_file_content" -> "Write"
    "view_file" -> "Read"
    "list_dir" -> "List files"
    "grep_search", "search_files" -> "Search"
    "schedule" -> "Wait"
    "manage_task" -> "Manage task"
    else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

private fun antigravityToolDetail(step: JSONObject, rawName: String): String {
    step.stringOrEmpty("description").takeIf(String::isNotBlank)?.let { return redactToolDetail(it) }
    val info = step.optJSONObject("tool_info")
    val parameters = info?.optJSONObject("parameters")
    val preferredKeys = when (rawName.lowercase()) {
        "run_command" -> listOf("CommandLine", "command", "cmd")
        "write_to_file", "replace_file_content", "multi_replace_file_content" ->
            listOf("TargetFile", "AbsolutePath", "path", "file")
        "view_file" -> listOf("AbsolutePath", "TargetFile", "path", "file")
        "list_dir" -> listOf("DirectoryPath", "AbsolutePath", "path")
        "grep_search", "search_files" -> listOf("Query", "Pattern", "query", "pattern")
        "schedule" -> listOf("Prompt", "DurationSeconds")
        "manage_task" -> listOf("Action", "TaskId")
        else -> emptyList()
    }
    preferredKeys.forEach { key ->
        parameters?.opt(key)?.toString()?.takeIf { it.isNotBlank() && it != "null" }?.let {
            val prefix = when {
                rawName.equals("schedule", true) && key == "DurationSeconds" -> "Wait "
                rawName.equals("manage_task", true) -> "$key: "
                else -> ""
            }
            val suffix = if (rawName.equals("schedule", true) && key == "DurationSeconds") "s" else ""
            return redactToolDetail(prefix + it + suffix)
        }
    }
    return parameters?.toString()?.takeUnless { it == "{}" }?.let(::redactToolDetail)
        ?: step.optJSONObject("tool")?.toString()?.let(::redactToolDetail)
        .orEmpty()
}

private fun redactToolDetail(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
    .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
    .replace(Regex("\\s+"), " ")
    .trim()
    .take(500)

private fun antigravityToolOutput(value: String): String {
    val redacted = value
        .replace(Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\"]+)"), "$1$2••••")
        .replace(Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\"]+"), "$1••••")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .trim()
    return if (redacted.length <= 6_000) redacted else "…\n" + redacted.takeLast(6_000)
}

/** Official Antigravity CLI bridge running inside NextCraft Forge Root Chroot. OAuth and credentials remain owned by agy. */
private const val HELLO_TIMEOUT_MILLIS = 90_000L

/**
 * Stream-json's result event is terminal, but agy may keep its input loop alive
 * after emitting it. Stop the entire spawned process group so task completion is
 * driven by the protocol rather than an implementation-specific process exit.
 */
private suspend fun stopAfterTerminalResult(process: Process) {
    if (!process.isAlive) return
    process.destroy()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
    if (process.isAlive) process.destroyForcibly()
    repeat(20) {
        if (!process.isAlive) return
        delay(50)
    }
}

class AntigravityRuntimeBridge(
    private val context: Context,
    private val model: () -> String,
    private val effort: () -> String,
    /** The chat's saved agy conversation ([SavedConversation.encode]; a bare id from older builds also works). */
    private val conversationId: (String) -> String?,
    private val saveConversationId: (String, String?) -> Unit,
) : RuntimeBridge {
    private val installer = RuntimeInstaller(context)
    private val checkpoints = WorkspaceCheckpoints(context.filesDir, context)
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val finished = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested = false
    private val foreground = ForegroundSession(context, "AntigravityBridge")

    @Volatile private var activeProjectSlug = ""

    /**
     * agy kept alive after its turn: the next message to the same project, model and conversation is written to its
     * stdin, skipping the cold start (sign-in, quota and model lookup, conversation resume) of a new process.
     */
    private class WarmAgy(
        val process: Process,
        val tailer: InotifyLogTailer,
        val outputFile: File,
        val projectId: String,
        val model: String,
        val effort: String,
        val conversationId: String?,
    )
    @Volatile private var warm: WarmAgy? = null
    @Volatile private var warmGeneration = 0L
    /**
     * null until known; false once agy showed it only starts on end of input, so every turn closes stdin. Saved per
     * agy version, so the 20 s probe that finds this out runs once, not after every app restart.
     */
    private val warmModeFile = File(context.filesDir, "agy-warm-mode")
    @Volatile private var warmSupported: Boolean? = null
    @Volatile private var warmModeLoaded = false

    private fun agyVersion(): String = installer.installedAgentVersions()[AgentKind.ANTIGRAVITY].orEmpty()

    private fun readWarmMode(): Boolean? = runCatching {
        val (version, mode) = warmModeFile.readText().trim().split(' ', limit = 2)
        if (version != agyVersion()) null else mode.toBooleanStrictOrNull()
    }.getOrNull()

    private fun rememberWarmMode(supported: Boolean) {
        warmSupported = supported
        runCatching { warmModeFile.writeText("${agyVersion()} $supported") }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
    }

    private fun closeWarm(entry: WarmAgy?) {
        entry ?: return
        runCatching { entry.tailer.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        runCatching { entry.process.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        Thread {
            runCatching {
                Thread.sleep(500)
                if (entry.process.isAlive) entry.process.destroyForcibly()
                entry.outputFile.delete()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }.apply { isDaemon = true }.start()
    }

    /**
     * Reaps orphaned `/root/.local/bin/agy --input-format stream-json` processes whose parent died (PPID == 1) and
     * trims CLI logs older than 3 days.
     */
    private fun reapOrphanedAgyProcessesAndOldLogs() {
        Thread({
            runCatching {
                val script = "for p in \$(pgrep -f '[/]root/[.]local/bin/agy.*--input-format stream-json' 2>/dev/null); do " +
                    "ppid=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status 2>/dev/null); " +
                    "[ \"\$ppid\" = \"1\" ] && kill -9 \$p 2>/dev/null; done; true"
                val proc = ProcessBuilder("su", "-c", script)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            runCatching {
                val cutoff = System.currentTimeMillis() - 3L * 24 * 60 * 60 * 1_000L
                val logDir = File(installer.installedRuntime().rootfs, "root/.gemini/antigravity-cli/log")
                logDir.listFiles()?.forEach { file ->
                    if (file.isFile && file.name.startsWith("cli-") && file.lastModified() < cutoff) {
                        file.delete()
                    }
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }, "forge-agy-orphan-reaper").apply { isDaemon = true }.start()
    }

    /** An idle warm agy holds memory in the chroot; let it go after a while without messages. */
    private fun scheduleWarmExpiry(entry: WarmAgy) {
        val generation = ++warmGeneration
        Thread {
            runCatching { Thread.sleep(WARM_IDLE_MS) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            if (warmGeneration == generation && warm === entry) {
                warm = null
                closeWarm(entry)
            }
        }.apply { isDaemon = true }.start()
    }

    fun configureProjectRoot(projectId: String, rootPath: String) = checkpoints.configureProjectRoot(projectId, rootPath)

    /**
     * Lightweight connectivity probe: sends a tiny hello to agy and returns its
     * reply text. No foreground service, no checkpoints, no saved conversation.
     */
    suspend fun hello(timeoutMillis: Long = HELLO_TIMEOUT_MILLIS): String = withContext(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            throw IllegalStateException("Antigravity CLI is not installed.")
        }
        val startedAtMillis = System.currentTimeMillis()
        try {
            withTimeout(timeoutMillis) {
                val installed = installer.installedRuntime()
                val probeDir = File(context.cacheDir, "agy-hello").apply { mkdirs() }
                val command = buildList {
                    add(RuntimeInstaller.AGY_GUEST_PATH)
                    addAll(listOf("--input-format", "stream-json"))
                    addAll(listOf("--output-format", "stream-json"))
                    addAll(listOf("--print-timeout", "2m"))
                    add("--dangerously-skip-permissions")
                    addAntigravitySelection(model(), effort())
                    add("--new-project")
                }
                val outputFile = File(context.cacheDir, "agy-hello-${System.nanoTime()}.log")
                val process = installer.process(
                    installed.chrootExec,
                    installed.rootfs,
                    probeDir,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/hello",
                    emulateHardLinks = false,
                    outputFile = outputFile,
                    trustClaudeWorkspace = false,
                )
                try {
                    val request = JSONObject()
                        .put("event", "user")
                        .put("message", JSONObject().put("content", "Reply with exactly: ok"))
                        .toString() + "\n"
                    process.outputStream.write(request.toByteArray())
                    process.outputStream.flush()
                    process.outputStream.close()
                    var reply: String? = null
                    var lastDiagnostic = ""
                    fun handleLine(rawLine: String): Boolean {
                        val line = rawLine.trimEnd('\r')
                        if (line.isBlank()) return false
                        lastDiagnostic = line.takeLast(500)
                        when (val event = AntigravityEventParser.parse(line)) {
                            is AntigravityParsedEvent.Text -> if (reply == null && event.value.isNotBlank()) reply = event.value
                            is AntigravityParsedEvent.Result -> {
                                if (event.status.equals("SUCCESS", ignoreCase = true)) {
                                    if (reply == null && !event.response.isNullOrBlank()) reply = event.response
                                    return true
                                }
                                throw AntigravitySessionException(friendlyError(event.error ?: event.status))
                            }
                            else -> Unit
                        }
                        return false
                    }
                    var done = false
                    InotifyLogTailer(outputFile).use { tailer ->
                        while (!done && (process.isAlive || tailer.hasUnreadBytes())) {
                            // readLines blocks without suspending, so withTimeout (and a cancel) could never fire here.
                            ensureActive()
                            for (line in tailer.readLines(timeoutMs = 50L)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                        if (!done) {
                            for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                                if (handleLine(line)) {
                                    done = true
                                    break
                                }
                            }
                        }
                    }
                    check(done) { friendlyError(lastDiagnostic.ifBlank { "Antigravity exited without answering" }) }
                    stopAfterTerminalResult(process)
                    reply?.trim().takeUnless { it.isNullOrEmpty() } ?: "ok"
                } finally {
                    runCatching { process.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching {
                        if (process.isAlive) process.destroyForcibly()
                    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching { outputFile.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    runCatching { probeDir.deleteRecursively() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                }
            }
        } catch (e: TimeoutCancellationException) {
            // The probe's own timeout ("Timed out waiting for 45000 ms") is not the 60-minute task limit: classify
            // only what agy logged (network, server error, sign-in) and otherwise say the probe got no answer.
            val diagnostic = recentAntigravityDiagnostic(startedAtMillis)
            val noAnswer = "Antigravity did not answer within ${timeoutMillis / 1000} s. Check the connection and try again."
            val message = if (diagnostic.isBlank()) {
                noAnswer
            } else {
                antigravityFriendlyError("", diagnostic).takeUnless { it == ANTIGRAVITY_GENERIC_FAILURE } ?: noAnswer
            }
            throw AntigravitySessionException(message)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw AntigravitySessionException(
                antigravityFriendlyError(e.message.orEmpty(), recentAntigravityDiagnostic(startedAtMillis)),
            )
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
        val sessionStartedAtMillis = System.currentTimeMillis()
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        foreground.begin()
        finished.remove(sessionId)
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            emitFailure(sessionId, "Antigravity CLI is not installed. Open Settings → Coding agent to install it.")
            activeSessionId = null
            activeProjectSlug = ""
            foreground.clearStart()
            return@withContext sessionId
        }

        var spawnedOutputFile: File? = null
        var openTailer: InotifyLogTailer? = null
        var keptWarm = false
        var savedConversation: SavedConversation? = null
        var stopHandler: (() -> Unit)? = null
        runCatching {
            stopHandler = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread {
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                    }.apply { isDaemon = true }.start()
                }
            }
            stopHandler?.let { RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sessionId", it) }
            startForegroundRuntime(projectSlug)
            pushForegroundProgress("Starting Antigravity…")
            val installed = installer.installedRuntime()
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            // Read on the IO thread (it checks the installed agy version), once per app run.
            if (!warmModeLoaded) {
                warmSupported = readWarmMode()
                warmModeLoaded = true
            }
            val currentModel = model()
            val currentEffort = effort()
            savedConversation = SavedConversation.decode(conversationId(projectId))
            val currentConversation = savedConversation?.id
            // agy only remembers its own conversation: messages it has not seen (all of them in a chat begun with
            // another agent, or the turns another agent answered since) go along as text.
            val unseen = conversationHistory.unseenBy(savedConversation).recentWithinCharacterBudget(MAX_HISTORY_CHARACTERS)
            val promptWithHistory = StringBuilder()
                .apply { appendConversationHistory(unseen, "/workspace/$projectSlug", resumed = savedConversation != null) }
                .append(prompt).toString()
            // The chat will hold the history plus this answer once the turn ends.
            fun remember(id: String) = saveConversationId(projectId, SavedConversation(id, conversationHistory.size + 1).encode())
            val previous = warm
            warm = null
            warmGeneration++
            val reuse = previous?.takeIf {
                warmSupported == true && it.process.isAlive && it.projectId == projectId &&
                    it.model == currentModel && it.effort == currentEffort && it.conversationId == currentConversation
            }
            if (reuse == null) {
                closeWarm(previous)
                reapOrphanedAgyProcessesAndOldLogs()
            }
            val process: Process
            val tailer: InotifyLogTailer
            if (reuse != null) {
                process = reuse.process
                tailer = reuse.tailer
                spawnedOutputFile = reuse.outputFile
                tailer.readLines(timeoutMs = 0L) // drop anything agy printed after the previous result
            } else {
                val command = antigravityCommand(currentModel, currentEffort, currentConversation)
                process = installer.process(
                    installed.chrootExec,
                    installed.rootfs,
                    workspace,
                    emptyMap(),
                    command,
                    guestWorkspacePath = "/workspace/$projectSlug",
                    emulateHardLinks = false,
                    trustClaudeWorkspace = false,
                )
                // Registered before anything below can throw: onFailure kills activeProcess, so a failing tailer must not
                // leave agy running unattended.
                activeProcess = process
                val native = process as? NativeSpawnProcess ?: error("Unsupported Antigravity process")
                spawnedOutputFile = native.outputFile
                tailer = InotifyLogTailer(native.outputFile)
            }
            openTailer = tailer
            activeProcess = process
            if (userStopRequested) process.destroy()
            val request = JSONObject()
                .put("event", "user")
                .put("message", JSONObject().put("content", antigravityWorkspacePrompt(projectSlug, promptWithHistory)))
                .toString() + "\n"
            process.outputStream.write(request.toByteArray())
            process.outputStream.flush()
            // Keep stdin open so agy can take the next message; once it proved it waits for end of input, close it.
            var stdinOpen = warmSupported != false
            if (!stdinOpen) process.outputStream.close()
            val promptSentAtMillis = System.currentTimeMillis()
            var eventSeen = false
            var lastDiagnostic = ""
            var resultSeen = false
            var assistantTextSeen = false
            var lastAgentResponseDone = false
            var activeToolName: String? = null
            var activeToolDetail = ""
            var activeToolStartedAtMillis = 0L
            var lastToolProgressAtMillis = 0L
            var lastRetryCheckAtMillis = 0L
            var lastRetryStatus: String? = null
            suspend fun handleLine(rawLine: String) {
                val line = rawLine.trimEnd('\r')
                if (line.isBlank()) return
                lastDiagnostic = line.takeLast(1_000)
                val parsed = AntigravityEventParser.parse(line)
                if (parsed != null) eventSeen = true
                when (val event = parsed) {
                    is AntigravityParsedEvent.Initialized -> remember(event.conversationId)
                    is AntigravityParsedEvent.Text -> {
                        assistantTextSeen = true
                        lastAgentResponseDone = event.done
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.value))
                    }
                    is AntigravityParsedEvent.ResponseDone -> {
                        if (assistantTextSeen) lastAgentResponseDone = true
                    }
                    is AntigravityParsedEvent.ToolStarted -> {
                        lastAgentResponseDone = false
                        activeToolName = event.name
                        activeToolDetail = event.detail
                        activeToolStartedAtMillis = System.currentTimeMillis()
                        lastToolProgressAtMillis = activeToolStartedAtMillis
                        pushForegroundProgress("Running ${event.name}: ${event.detail}")
                        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.ToolCompleted -> {
                        if (activeToolName == event.name) activeToolName = null
                        pushForegroundProgress("Antigravity is working…")
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, event.name, event.detail))
                    }
                    is AntigravityParsedEvent.Result -> {
                        event.conversationId?.let(::remember)
                        val completedBeforeDisconnect = assistantTextSeen && lastAgentResponseDone && activeToolName == null
                        if (event.status.equals("SUCCESS", ignoreCase = true) || completedBeforeDisconnect) {
                            if (!assistantTextSeen && !event.response.isNullOrBlank()) {
                                assistantTextSeen = true
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, event.response))
                            }
                            resultSeen = true
                        } else throw AntigravitySessionException(
                            friendlyError(event.error ?: event.status, System.currentTimeMillis() - sessionStartedAtMillis),
                        )
                    }
                    null -> Unit
                }
            }
            run {
                outputLoop@ while (process.isAlive || tailer.hasUnreadBytes()) {
                    val lines = tailer.readLines(timeoutMs = 250L)
                    if (lines.isEmpty()) {
                        val now = System.currentTimeMillis()
                        if (stdinOpen && !eventSeen && now - promptSentAtMillis >= WARM_PROBE_MS) {
                            if (reuse != null) {
                                // A kept-alive agy can think for a long time before its first event on a heavy prompt, so 20 s of
                                // silence alone proves nothing. It is called stuck only after a longer quiet period with no sign
                                // of life in its own log. The warm mode stays on: one slow answer must not switch it off for good.
                                val quietMs = now - promptSentAtMillis
                                val stillWorking = quietMs < WARM_REUSE_MAX_WAIT_MS &&
                                    (quietMs < WARM_REUSE_QUIET_MS || agyLogActive(now))
                                if (!stillWorking) {
                                    throw AntigravitySessionException(
                                        "Antigravity stopped responding. Send the message again.",
                                    )
                                }
                            } else {
                                // This agy only starts on end of input; give it that and remember for later turns.
                                rememberWarmMode(false)
                                stdinOpen = false
                                runCatching { process.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
                            }
                        }
                        pushForegroundProgress(
                            activeToolName?.let { name -> "Running $name: $activeToolDetail" }
                                ?: "Antigravity is working…",
                        )
                        // agy retries failed requests silently; surface them so a long wait has a visible reason.
                        if (now - lastRetryCheckAtMillis >= RETRY_CHECK_INTERVAL_MS) {
                            lastRetryCheckAtMillis = now
                            antigravityRetryStatus(recentAntigravityDiagnostic(sessionStartedAtMillis))
                                ?.takeIf { it != lastRetryStatus }
                                ?.let { status ->
                                    lastRetryStatus = status
                                    pushForegroundProgress(status)
                                    eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, "Antigravity", status))
                                }
                        }
                        if (activeToolName == "Bash" && activeToolDetail.contains("gradle", ignoreCase = true) &&
                            now - activeToolStartedAtMillis >= 10_000L && now - lastToolProgressAtMillis >= 10_000L
                        ) {
                            lastToolProgressAtMillis = now
                            eventBus.emit(
                                RuntimeEvent.ToolProgress(
                                    sessionId,
                                    "Bash",
                                    antigravityGradleProgress(activeToolDetail, now - activeToolStartedAtMillis),
                                ),
                            )
                        }
                        continue
                    }
                    for (line in lines) {
                        handleLine(line)
                        if (resultSeen) break@outputLoop
                    }
                }
                if (!resultSeen) {
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        handleLine(line)
                        if (resultSeen) break
                    }
                }
            }
            if (resultSeen && stdinOpen && process.isAlive) {
                // agy answered without end of input and is still running: keep it for the next message.
                rememberWarmMode(true)
                val entry = WarmAgy(
                    process, tailer, spawnedOutputFile!!, projectId, currentModel, currentEffort,
                    SavedConversation.decode(conversationId(projectId))?.id,
                )
                warm = entry
                keptWarm = true
                scheduleWarmExpiry(entry)
            } else if (resultSeen) {
                stopAfterTerminalResult(process)
            } else if (assistantTextSeen && lastAgentResponseDone && activeToolName == null) {
                // agy finished streaming the final agent_response (state=DONE) before the connection or process dropped.
                resultSeen = true
                stopAfterTerminalResult(process)
            } else {
                val exit = process.waitFor()
                check(exit == 0 && resultSeen) {
                    friendlyError(
                        lastDiagnostic.ifBlank { "Antigravity exited with code $exit" },
                        System.currentTimeMillis() - sessionStartedAtMillis,
                    )
                }
            }
            val paths = checkpoints.changedFiles(workspace, before)
            if (paths.isNotEmpty()) {
                checkpoints.saveChangedPaths(projectId, paths)
                val allPending = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
                eventBus.emit(RuntimeEvent.FilesChanged(sessionId, checkpoints.buildChangeDetails(projectId, workspace, allPending)))
            } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }
            emitCompleted(sessionId)
            finishForegroundRuntimeRes(true, projectSlug, com.nextcraft.forge.R.string.notif_agent_finished_in, "Antigravity", projectSlug)
        }.onFailure {
            val diagnostic = recentAntigravityDiagnostic(sessionStartedAtMillis)
            // A failure after launch (parser/IO/provider error) must not leave the agent running unattended in the chroot.
            activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
            val lost = savedConversation != null &&
                LOST_CONVERSATION.containsMatchIn("${it.message.orEmpty()}\n$diagnostic")
            if (lost) saveConversationId(projectId, null) // the retry starts a new one with the chat history
            val message = when {
                userStopRequested -> "Stopped by user"
                lost -> ClaudeRuntimeBridge.SESSION_LOST_MESSAGE
                // Already classified when it was thrown; classifying the friendly text again could pick up log noise.
                it is AntigravitySessionException -> it.message.orEmpty()
                else -> antigravityFriendlyError(
                    it.message.orEmpty(), diagnostic, System.currentTimeMillis() - sessionStartedAtMillis,
                )
            }
            emitFailure(sessionId, message)
            if (userStopRequested) cancelForegroundRuntime()
            else finishForegroundRuntime(false, projectSlug, message)
        }
        if (!keptWarm) {
            runCatching { openTailer?.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
            runCatching { spawnedOutputFile?.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AntigravityRuntimeBridge", it) }
        }
        activeProcess = null
        activeSessionId = null
        activeProjectSlug = ""
        foreground.clearStart()
        RuntimeTaskController.release(stopHandler)
        sessionId
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) {
        // agy headless streaming runs with --dangerously-skip-permissions inside Chroot.
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailure(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() = activeSessionId?.let { stopSession(it) } ?: Unit

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String): Unit = withContext(Dispatchers.IO) {
        checkpoints.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, checkpoints.ensureWorkspace(projectId), paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun emitCompleted(sessionId: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
    }

    private suspend fun emitFailure(sessionId: String, reason: String) {
        if (finished.add(sessionId)) eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
    }

    private fun startForegroundRuntime(projectName: String) = foreground.start(projectName)

    /** Keeps long, silent Antigravity turns visibly active in Android's foreground service. */
    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        foreground.progress(activeProjectSlug, detailRaw)
    }

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) =
        foreground.finish(completed, projectName, detail)

    private fun finishForegroundRuntimeRes(completed: Boolean, projectName: String, @androidx.annotation.StringRes detailRes: Int, vararg args: String) =
        foreground.finishRes(completed, projectName, detailRes, *args)

    private fun cancelForegroundRuntime() = foreground.cancel()

    private fun friendlyError(raw: String, elapsedMs: Long = 0L): String =
        antigravityFriendlyError(raw, elapsedMs = elapsedMs)

    /** True when agy wrote to its own log in the last 45 s: it is alive and busy (a retry, a stream), not stuck. */
    private fun agyLogActive(nowMillis: Long): Boolean = runCatching {
        val logDir = File(installer.installedRuntime().rootfs, "root/.gemini/antigravity-cli/log")
        logDir.listFiles()?.any { it.isFile && it.name.startsWith("cli-") && nowMillis - it.lastModified() < 45_000L } == true
    }.getOrDefault(false)

    private fun recentAntigravityDiagnostic(sinceMillis: Long): String = runCatching {
        val logDir = File(
            installer.installedRuntime().rootfs,
            "root/.gemini/antigravity-cli/log",
        )
        val latest = logDir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("cli-") && it.lastModified() >= sinceMillis - 2_000L }
            ?.maxByOrNull(File::lastModified)
            ?: return@runCatching ""
        latest.readTailText(12_000)
    }.getOrDefault("")
}

internal fun antigravityFriendlyError(
    raw: String,
    diagnostic: String = "",
    elapsedMs: Long = Long.MAX_VALUE,
): String {
    val rawValue = raw.replace(Regex("\\s+"), " ").trim()
    // The error itself decides first. The agy log tail is only a fallback and never decides "timeout": the
    // prompt tells the agent to run `timeout 15m gradle`, so that word is routinely in the log.
    // "60-minute limit" only fits a turn that really ran that long; a quick timeout is something else, show it.
    val ranToLimit = elapsedMs >= ANTIGRAVITY_TASK_LIMIT_MS - 5 * 60_000L
    return classifyAntigravityError(rawValue, allowTimeout = ranToLimit)
        ?: classifyAntigravityError("$rawValue ${antigravityDiagnosticSignal(diagnostic)}", allowTimeout = false)
        ?: rawValue.ifBlank { ANTIGRAVITY_GENERIC_FAILURE }.take(500)
}

/**
 * The agy log tail without startup noise: every agy start logs "You are not logged into Antigravity" until its
 * silent auth finishes, so those lines only mean "not signed in" when the log never reports a successful auth.
 */
private fun antigravityDiagnosticSignal(diagnostic: String): String {
    val lines = diagnostic.lineSequence()
    val authenticated = diagnostic.contains("silent auth succeeded", ignoreCase = true)
    return lines
        .filterNot { authenticated && it.contains("not logged in", ignoreCase = true) }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private const val ANTIGRAVITY_TASK_LIMIT_MS = 60 * 60_000L

private fun classifyAntigravityError(value: String, allowTimeout: Boolean): String? = when {
    ANTIGRAVITY_NETWORK_ERRORS.any { value.contains(it, ignoreCase = true) } ->
        "Antigravity could not reach Google. Check your internet or DNS connection, then try again."
    ANTIGRAVITY_SERVER_ERRORS.any { value.contains(it, ignoreCase = true) } ->
        "Google's Antigravity server returned an error. Wait a moment and try again; your files were kept."
    // Google's stream sent nothing for 5 minutes (a long think on a big context): agy gives up on that request.
    value.contains("stream idle timeout", true) || value.contains("context deadline exceeded", true) ->
        "Google sent nothing for 5 minutes while the model was thinking, so Antigravity dropped the request. Your files were kept."
    value.contains("stream was interrupted", true) ->
        "The connection to Google dropped mid-answer. Send the message again to continue."
    value.contains("authentication required", true) ||
        value.contains("authentication failed", true) ||
        value.contains("not signed in", true) ||
        value.contains("not logged in", true) ->
        "Antigravity needs Google sign-in. Open Settings → Coding agent."
    value.contains("out of credits", true) || value.contains("quota", true) ->
        "Your Antigravity account is out of credits. Check the account plan or wait for credits to reset."
    allowTimeout && (value.contains("timed out", true) || value.contains("timeout", true)) ->
        "Antigravity reached the 60-minute task limit. Your files were kept."
    // Only from agy's own error: its log mentions models routinely ("model ... not in local config, using default"),
    // which made unrelated failures read as "model unavailable".
    allowTimeout && value.contains("model", true) && (value.contains("invalid", true) || value.contains("unknown", true)) ->
        "The selected Antigravity model is unavailable. Refresh models in Settings."
    else -> null
}

private val ANTIGRAVITY_NETWORK_ERRORS = listOf(
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

private val ANTIGRAVITY_SERVER_ERRORS = listOf(
    "code 500",
    "code 502",
    "code 503",
    "internal error encountered",
    "unavailable (code",
)

private const val RETRY_CHECK_INTERVAL_MS = 5_000L

/** Without any agy event this long after the prompt, agy is waiting for end of input (or a kept one is stuck). */
private const val WARM_PROBE_MS = 20_000L

/** A kept-alive agy gets this long to show a first event before its log decides whether it is still working. */
private const val WARM_REUSE_QUIET_MS = 90_000L

/** Upper bound for a kept-alive agy that shows no event at all, however active its log looks. */
private const val WARM_REUSE_MAX_WAIT_MS = 10 * 60_000L

/** A kept-alive agy is stopped after this long without a message (3 min balances warm reuse vs ~400 MB RSS). */
private const val WARM_IDLE_MS = 3 * 60_000L

private const val ANTIGRAVITY_GENERIC_FAILURE = "Antigravity could not complete the task."

private val ANTIGRAVITY_RETRY_LINE = Regex("""Run: attempt (\d+) failed \((.*)\), retrying in (\w+)""")

/**
 * The newest "Run: attempt N failed (…), retrying in Xs" line in an agy log tail, as a short status for the
 * user (agy only writes these retries to its log, never to the stream), or null when there is none.
 */
internal fun antigravityRetryStatus(logTail: String): String? {
    val match = ANTIGRAVITY_RETRY_LINE.findAll(logTail).lastOrNull() ?: return null
    val (attempt, reason, delay) = match.destructured
    val cause = when {
        ANTIGRAVITY_SERVER_ERRORS.any { reason.contains(it, ignoreCase = true) } -> "Google server error"
        ANTIGRAVITY_NETWORK_ERRORS.any { reason.contains(it, ignoreCase = true) } -> "Connection to Google failed"
        else -> "Request failed"
    }
    return "$cause, retrying in $delay (attempt $attempt)"
}

private class AntigravitySessionException(message: String) : IllegalStateException(message)

/** Same budget as the other agents' history. */
private const val MAX_HISTORY_CHARACTERS = 160_000

/** agy's wording when `--conversation` names a conversation it no longer has. */
private val LOST_CONVERSATION = Regex("conversation[^\n]{0,60}(not found|does not exist|no such|unknown)", RegexOption.IGNORE_CASE)

internal fun antigravityCommand(model: String, effort: String, conversationId: String?): List<String> = buildList {
    add(RuntimeInstaller.AGY_GUEST_PATH)
    addAll(listOf("--input-format", "stream-json"))
    addAll(listOf("--output-format", "stream-json"))
    addAll(listOf("--print-timeout", "60m"))
    add("--dangerously-skip-permissions")
    addAntigravitySelection(model, effort)
    conversationId?.takeIf(String::isNotBlank)?.let {
        addAll(listOf("--conversation", it))
    } ?: add("--new-project")
}

private fun MutableList<String>.addAntigravitySelection(model: String, effort: String) {
    if (model.isNotBlank()) {
        addAll(listOf("--model", model))
    }
    // A model id that already names its effort (gemini-4-argon-high) gets no second, possibly different --effort.
    val modelHasEffort = Regex("-(low|medium|high)$").containsMatchIn(model)
    if (!modelHasEffort && effort in setOf("low", "medium", "high")) {
        addAll(listOf("--effort", effort))
    }
}

internal fun antigravityWorkspacePrompt(projectSlug: String, prompt: String): String {
    val safeSlug = projectSlug
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
    return """
    <nextcraft_workspace>
    The active project workspace is /workspace/$safeSlug. Create, edit, read, run, and build project files only inside this directory. Do not create project output under ~/.gemini/antigravity-cli/scratch or any other scratch directory.

    For Android projects, never use plain `gradle build` or start a Gradle daemon. Build only the debug APK. First check whether `app/build/outputs/apk/debug/` already contains an APK.
    - For a first build with no existing debug APK, allow dependency resolution and use:
      `timeout 15m gradle ${AndroidToolchain.GRADLE_BASE_FLAGS} assembleDebug --console=plain --stacktrace`
    - For a later build with an existing debug APK, prefer the cached fast path:
      `timeout 5m gradle --offline --init-script ${AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH} -Pandroid.aapt2FromMavenOverride=${AndroidToolchain.AAPT2_GUEST_PATH} assembleDebug --console=plain --stacktrace`
    NextCraft Forge bundles the standard Android dependencies locally (AGP ${AndroidToolchain.AGP_VERSION}, Kotlin ${AndroidToolchain.KOTLIN_VERSION}, compileSdk ${AndroidToolchain.COMPILE_SDK}, JDK ${AndroidToolchain.JDK_MAJOR}). New projects must use these versions and the AGP 9 DSL (built-in Kotlin, no org.jetbrains.kotlin.android plugin, no kotlinOptions, Compose via org.jetbrains.kotlin.plugin.compose). Projects made with AGP 8.x / Kotlin 1.9 are not cached offline anymore: migrate them or build them online once. If and only if the cached build finishes with a clear missing-cached-dependency error, retry once online with `timeout 15m`, removing only `--offline` and retaining all other flags. Do not retry a timed-out or resource-failed build unchanged.
    Start the optimized Android build with `run_command` normally inside the Root Chroot workspace. If Antigravity moves the command into a managed task, immediately use `manage_task` to check its status and obtain its log path. Poll that managed task and tail its log with short commands about every 10 seconds so the user receives progress, then read the final output and exit status. Never wait silently on a long foreground command.
    Operate with the precision, speed, and surgical execution style of Claude Code: target under 10 seconds per turn, combine related operations into a single tool step whenever possible, and deliver concise, high-signal responses.
    ${ANTI_HALLUCINATION_RULES.joinToString("\n    ")}
    </nextcraft_workspace>

    $prompt
""".trimIndent()
}

internal fun antigravityGradleProgress(command: String, elapsedMillis: Long): String {
    val elapsedSeconds = (elapsedMillis.coerceAtLeast(0L) / 1_000L)
    return "Gradle is running · ${elapsedSeconds}s\n$command\nFull output will appear when the command completes."
}
