package com.nextcraft.forge.runtime

import android.content.Context
import android.util.Log
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ProviderProtocol
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

internal object ProviderRuntimeErrorDetector {
    fun detect(line: String): String? {
        val trimmed = line.trim()
        val json = runCatching { JSONObject(trimmed) }.getOrNull()
        // Structured stream-json lines carry assistant text and tool output. Scanning those
        // would abort the session whenever a file or answer merely mentions "quota" or
        // "rate limit". Only error-bearing fields are inspected; raw (non-JSON) lines are
        // runtime stderr and are scanned whole, except truncated stream-json lines.
        if (json == null && trimmed.startsWith("{")) {
            return null
        }
        val combined = if (json == null) {
            trimmed.lowercase()
        } else buildString {
            append(json.stringOrEmpty("error"))
            if (json.stringOrEmpty("type") == "error") {
                append(' ')
                append(json.stringOrEmpty("message"))
                json.optJSONObject("error")?.let { errObj ->
                    append(' ')
                    append(errObj.stringOrEmpty("type"))
                    append(' ')
                    append(errObj.stringOrEmpty("message"))
                }
            }
            if (json.stringOrEmpty("type") == "result" && json.optBoolean("is_error")) {
                append(' ')
                append(json.stringOrEmpty("result"))
            }
        }.lowercase()
        return when {
            "user not found" in combined -> "User not found. Check the API key and provider account."
            "authentication_failed" in combined ||
                "authentication failed" in combined ||
                "authentication_error" in combined ||
                "invalid bearer token" in combined ||
                "oauth access token is invalid" in combined ||
                "oauth token has been revoked" in combined ||
                "invalid api key" in combined ||
                "invalid_api_key" in combined ||
                "invalid x-api-key" in combined ||
                "oauth token has expired" in combined ||
                "token has expired" in combined ||
                "api key expired" in combined ||
                "not logged in" in combined ||
                "api error: 401" in combined ||
                "http 401" in combined ||
                "status 401" in combined ||
                "401 unauthorized" in combined ||
                (json?.stringOrEmpty("subtype") == "api_retry" && json.optInt("error_status") == 401) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        com.nextcraft.forge.data.ApiKeyVault(context).rotateNext(profile.kind.name)
    },
    private val autoApproveAllTools: () -> Boolean = {
        runCatching { com.nextcraft.forge.data.AppPreferences(context).autoApproveAllTools }.getOrDefault(true)
    },
    private val claudeEffort: () -> String = {
        runCatching { com.nextcraft.forge.data.AppPreferences(context).claudeEffort }.getOrDefault("")
    },
    private val interactiveHighRiskApprovals: () -> Boolean = {
        runCatching { com.nextcraft.forge.data.AppPreferences(context).interactiveHighRiskApprovals }.getOrDefault(true)
    },
) : RuntimeBridge {
    private val installer by lazy { RuntimeInstaller(context) }
    private val checkpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    private val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    private val pending = ConcurrentHashMap<String, PendingPermission>()
    private val toolNames = ConcurrentHashMap<String, String>()
    private val seenToolCalls = ConcurrentHashMap.newKeySet<String>()
    private val finishedSessions = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var activeProcess: Process? = null

    /**
     * Steering: the prompt goes in as stream-json on stdin and stdin stays open, so a message sent while Claude works
     * joins the running session (it is answered right after the current step) instead of stopping it. When a turn ends
     * with no steering message waiting, stdin is closed and the CLI exits as before.
     */
    @Volatile private var steerInput: java.io.OutputStream? = null
    @Volatile private var steersPending = 0
    /** After a turn that had steering messages: when to give up waiting for the follow-up turn (0 = not waiting). */
    @Volatile private var idleCloseAt = 0L
    @Volatile private var activeSessionId: String? = null
    @Volatile private var userStopRequested: Boolean = false
    @Volatile private var turnCompletedFromStream: Boolean = false
    private val foreground = ForegroundSession(context, "ClaudeBridge")

    @Volatile private var activeProjectSlug: String? = null
    private val streamedText = StringBuilder()
    private val streamedThinking = StringBuilder()
    private var lastReasoningTokens = 0
    private var lastReasoningUpdateAt = 0L
    private var lastThinkingUpdateAt = 0L
    private var currentThinkingBlockId = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        liveSessions.add(sessionId)
        userStopRequested = false
        turnCompletedFromStream = false
        steerInput = null
        steersPending = 0
        idleCloseAt = 0L
        activeProjectSlug = projectSlug
        foreground.begin()
        toolNames.clear()
        seenToolCalls.clear()
        lastReasoningTokens = 0
        lastReasoningUpdateAt = 0L
        lastThinkingUpdateAt = 0L
        currentThinkingBlockId = 0L
        streamedText.clear()
        streamedThinking.clear()
        eventBus.emit(RuntimeEvent.SessionStarted(sessionId))
        pushForegroundProgress("Starting Claude Code…")
        val storedLogin = usesStoredClaudeLogin(provider)
        val secret = if (storedLogin) "" else secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (secret.isBlank() && !storedLogin) {
            val message = if (provider.kind == ProviderKind.CLAUDE) {
                "Not signed in to Claude. Open Agent → AI provider and sign in, or add a subscription token."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, message))
            activeSessionId = null
            liveSessions.remove(sessionId)
            foreground.clearStart()
            return@withContext sessionId
        }

        var formatGateway: LocalFormatGateway? = null
        var openRouterGateway: OpenRouterRoutingGateway? = null
        var spawnedOutputFile: File? = null
        var stopHandler: (() -> Unit)? = null
        runCatching {
            stopHandler = {
                userStopRequested = true
                val running = activeProcess
                if (running != null) {
                    Thread({
                        runCatching {
                            running.destroy()
                            Thread.sleep(500)
                            if (running.isAlive) running.destroyForcibly()
                        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    }, "forge-claude-stop").apply { isDaemon = true }.start()
                }
            }
            stopHandler?.let { RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sessionId", it) }
            startForegroundRuntime(projectSlug)
            val installed = installer.installedRuntime()
            installer.ensureSettingsAndHooks()
            // Requests left by a run that was killed before its watcher saw them would be shown as this session's.
            // Only requests of sessions that are no longer running: another chat's live session may be waiting on one.
            File(context.filesDir, "runtime-bridge")
                .listFiles { f -> f.name.endsWith(".request") && !isLiveSessionRequest(f.name) }.orEmpty()
                .forEach { runCatching { it.delete() } }
            val workspace = checkpoints.ensureWorkspace(projectId)
            checkpoints.createCheckpoint(projectId, workspace)
            val before = checkpoints.snapshot(workspace)
            formatGateway = if (provider.kind.protocol in setOf(
                    ProviderProtocol.OPENAI_CHAT,
                    ProviderProtocol.OPENAI_RESPONSES,
                )
            ) LocalFormatGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            openRouterGateway = if (
                provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()
            ) OpenRouterRoutingGateway(provider, secret) { rotateSecretFor(provider) }.start() else null
            val launch = RuntimeLaunchConfigBuilder.build(
                provider,
                authToken = secret,
                localGatewayUrl = formatGateway?.url ?: openRouterGateway?.url,
                useStoredLogin = storedLogin,
                claudeEffort = claudeEffort(),
            )
            Log.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}")
            Log.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            val guestWorkspacePath = "/workspace/$projectSlug"
            val contextPrompt = buildContextPrompt(prompt, conversationHistory, guestWorkspacePath, projectKind)

            val command = buildList {
                add(launch.executable)
                // Do not use --bare here: Claude Code bare mode ignores CLAUDE_CODE_OAUTH_TOKEN,
                // which is how setup-token authenticates Claude subscriptions.
                // Prompt goes through stdin: a single argv entry is capped at 128 KB by the kernel
                // and the history budget is 160k chars; stdin also keeps it out of `ps`.
                add("-p")
                add("--input-format")
                add("stream-json")
                add("--output-format")
                add("stream-json")
                add("--include-partial-messages")
                add("--verbose")
                add("--model")
                add(launch.environment["ANTHROPIC_MODEL"] ?: provider.model)
                add("--max-turns")
                add("25")
            }
            Log.d("ClaudeBridge", "Launching Claude Code (${command.size} args, prompt=${contextPrompt.length} chars)")
            val process = installer.process(
                installed.chrootExec,
                installed.rootfs,
                workspace,
                // The permission hook reads this to tag its requests with this session (see requestPrefix).
                launch.environment + ("NEXTCRAFT_SESSION_ID" to sessionId),
                command,
                guestWorkspacePath = guestWorkspacePath,
                claudeMcpServers = runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull(),
            )
            activeProcess = process
            runCatching {
                process.outputStream.write(streamJsonUserMessage(contextPrompt))
                process.outputStream.flush()
                steerInput = process.outputStream
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            if (userStopRequested) process.destroy()
            coroutineScope {
                val permissionWatcher = launch { watchPermissionRequests(sessionId) }
                var lastDiagnostic = ""
                val nativeProcess = process as? NativeSpawnProcess
                    ?: error("Unsupported Android runtime process")
                spawnedOutputFile = nativeProcess.outputFile
                suspend fun processClaudeLine(rawLine: String) {
                    val line = rawLine.trimEnd('\r')
                    if (line.isBlank()) return
                    idleCloseAt = 0L // the follow-up turn for a steering message has started
                    ProviderRuntimeErrorDetector.detect(line)?.let { reason ->
                        process.destroyForcibly()
                        throw ProviderSessionException(reason)
                    }
                    // The result frame repeats the whole answer; past the reader's line cap it is cut and no longer
                    // parses, which would leave the turn running until the CLI exits on its own.
                    if (line.endsWith("... [truncated]") && line.take(200).contains("\"type\":\"result\"")) {
                        if (!line.take(400).contains("\"is_error\":true")) turnCompletedFromStream = true
                        endTurnOrContinue()
                        return
                    }
                    if (!consumeClaudeEvent(sessionId, line)) {
                        lastDiagnostic = sanitizeForDisplay(line.takeLast(500))
                        terminalStatus(line)?.let { (title, detail) ->
                            eventBus.emit(RuntimeEvent.RuntimeLog(sessionId, title, detail))
                        }
                    }
                }
                InotifyLogTailer(nativeProcess.outputFile).use { tailer ->
                    while (process.isAlive || tailer.hasUnreadBytes()) {
                        for (line in tailer.readLines(timeoutMs = 250L)) {
                            processClaudeLine(line)
                        }
                        // A steering message that Claude folded into the turn that just ended brings no new turn.
                        val waitUntil = idleCloseAt
                        if (waitUntil > 0L && android.os.SystemClock.elapsedRealtime() > waitUntil) {
                            idleCloseAt = 0L
                            closeSteerInput()
                            terminateActiveProcessGracefully()
                        }
                    }
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        processClaudeLine(line)
                    }
                }
                val exit = process.waitFor()
                Log.d("ClaudeBridge", "Process exited with code $exit")
                permissionWatcher.cancelAndJoin()
                pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                    // A failed write here must not turn a finished run into a failure.
                    runCatching { permission.response.writeText("deny") }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    pending.remove(permission.request.approvalId)
                    eventBus.emit(RuntimeEvent.ToolRejected(sessionId, permission.request.approvalId))
                }
                val changed = checkpoints.changedFiles(workspace, before)
                if (changed.isNotEmpty()) {
                    Log.d("ClaudeBridge", "Changed files (${changed.size})")
                    checkpoints.saveChangedPaths(projectId, changed)
                    val details = loadPendingChanges(projectId)
                    eventBus.emit(RuntimeEvent.FilesChanged(sessionId, details))
                } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
                    acceptLastChanges(projectId)
                }
                if (exit == 0 || (turnCompletedFromStream && !userStopRequested)) {
                    emitCompletedOnce(sessionId)
                    finishForegroundRuntimeRes(true, projectSlug, com.nextcraft.forge.R.string.notif_agent_finished_in, "Claude Code", projectSlug)
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    error(lastDiagnostic.ifBlank { "Claude Code stopped with exit code $exit" })
                }
            }
        }.onFailure { error ->
            Log.e("ClaudeBridge", "Session failed: ${error::class.simpleName}")
            // A failure after launch (parser/IO/provider error) must not leave the agent running unattended in the chroot.
            activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
            pending.values.filter { it.request.sessionId == sessionId }.forEach { permission ->
                runCatching { permission.response.writeText("deny") }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                pending.remove(permission.request.approvalId)
                eventBus.emit(RuntimeEvent.ToolRejected(sessionId, permission.request.approvalId))
            }
            val message = friendlyError(error)
            emitFailureOnce(sessionId, message)
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = projectSlug,
                    detail = message,
                )
            }
        }
        runCatching { formatGateway?.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        runCatching { openRouterGateway?.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        runCatching { spawnedOutputFile?.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        activeProcess = null
        activeSessionId = null
        liveSessions.remove(sessionId)
        RuntimeTaskController.release(stopHandler)
        sessionId
    }

    /**
     * Runs a one-turn "reply ok" prompt with the saved credentials to prove Claude Code can
     * authenticate inside Root Chroot.
     */
    /**
     * True for the Claude subscription provider when no token was entered by hand but `claude auth login` left a login
     * in the guest: the CLI then authenticates (and refreshes) with its own credentials file.
     */
    private fun usesStoredClaudeLogin(provider: ProviderProfile): Boolean {
        if (provider.kind != ProviderKind.CLAUDE) return false
        val vault = com.nextcraft.forge.data.ApiKeyVault(context)
        val manualToken = listOf(ProviderKind.CLAUDE, ProviderKind.ANTHROPIC).any { !vault.get(it.name).isNullOrBlank() }
        return !manualToken && installer.hasClaudeLoginCredential()
    }

    suspend fun hello(provider: ProviderProfile, timeoutMillis: Long = 90_000L): String = withContext(Dispatchers.IO) {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        val storedLogin = usesStoredClaudeLogin(provider)
        // Same fallback as startSession: local servers (LM Studio, Ollama) have no key, so test them with the placeholder.
        val secret = if (storedLogin) "" else secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        check(secret.isNotBlank() || storedLogin) { "Not signed in to Claude." }
        val installed = installer.installedRuntime()
        // chroot-exec.sh bind-mounts this directory once and keeps the mount: deleting it after a test left a stale mount
        // ("current working directory was deleted"), so it is created once and only emptied, never removed.
        val probeDir = File(context.filesDir, "runtime-probes/claude-check").apply { mkdirs() }
        val outputFile = File(context.cacheDir, "claude-hello-${System.nanoTime()}.log")
        val launch = RuntimeLaunchConfigBuilder.build(provider, authToken = secret, useStoredLogin = storedLogin, claudeEffort = claudeEffort())
        val command = listOf(
            launch.executable,
            "-p", "Reply with exactly: ok",
            "--output-format", "json",
            "--max-turns", "1",
            "--model", launch.environment["ANTHROPIC_MODEL"] ?: provider.model,
        )
        val process = installer.process(
            installed.chrootExec,
            installed.rootfs,
            probeDir,
            launch.environment,
            command,
            guestWorkspacePath = "/workspace/claude-check",
            emulateHardLinks = false,
            outputFile = outputFile,
        )
        try {
            runCatching { process.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            withTimeout(timeoutMillis) {
                while (process.isAlive) delay(200)
            }
            val output = outputFile.takeIf(File::exists)?.readText().orEmpty()
            val result = output.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.stringOrEmpty("type") == "result" }
            when {
                result == null -> error(output.trim().takeLast(300).ifBlank { "Claude Code did not answer." })
                result.optBoolean("is_error") -> error(result.stringOrEmpty("result").ifBlank { result.stringOrEmpty("subtype") })
                else -> result.stringOrEmpty("result").trim().ifBlank { "ok" }
            }
        } catch (e: TimeoutCancellationException) {
            error("Claude Code did not answer in time. Check your connection and try again.")
        } finally {
            runCatching { process.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { if (process.isAlive) process.destroyForcibly() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { outputFile.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { probeDir.listFiles()?.forEach { it.deleteRecursively() } }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            Log.d("ClaudeBridge", "Connection test took ${android.os.SystemClock.elapsedRealtime() - startedAt} ms")
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val permission = pending.remove(request.approvalId) ?: return@withContext
        permission.response.writeText(if (approved) "allow" else "deny")
        eventBus.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) {
            checkpoints.acceptAll(projectId)
        }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    private suspend fun watchPermissionRequests(sessionId: String) {
        val bridge = File(context.filesDir, "runtime-bridge")
        val handledApprovals = HashSet<String>()
        while (kotlin.coroutines.coroutineContext.isActive) {
            expirePendingPermissions(sessionId)
            // The permission hook prefixes every request with the id of the session that raised it, so parallel
            // sessions never pick up (or answer) each other's requests.
            bridge.listFiles { file -> file.name.endsWith(".request") && file.name.startsWith(requestPrefix(sessionId)) }.orEmpty().forEach { file ->
                val approvalId = file.name.removeSuffix(".request")
                if (!handledApprovals.add(approvalId)) {
                    runCatching { file.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    return@forEach
                }
                processPermissionRequestFile(sessionId, file)
            }
            delay(50)
        }
    }

    /**
     * Responses live in a sibling directory that the chroot mounts read-only, so the guest can
     * request a decision but cannot write its own approval next to the request.
     */
    private fun responseFile(request: File, approvalId: String): File {
        val requestDir = request.parentFile ?: File(".")
        val dir = File(requestDir.parentFile, requestDir.name + "-responses").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - STALE_RESPONSE_MS
        dir.listFiles { f -> f.name.endsWith(".response") && f.lastModified() < cutoff }.orEmpty()
            .forEach { runCatching { it.delete() } }
        return File(dir, "$approvalId.response")
    }

    internal suspend fun expirePendingPermissions(
        sessionId: String,
        nowElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
        timeoutMs: Long = HIGH_RISK_APPROVAL_TIMEOUT_MS,
    ) {
        pending.values
            .filter { it.request.sessionId == sessionId && nowElapsedRealtime - it.requestedAtElapsedRealtime >= timeoutMs }
            .forEach { expired ->
                if (pending.remove(expired.request.approvalId, expired)) {
                    runCatching { expired.response.writeText("deny") }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    eventBus.emit(RuntimeEvent.ToolRejected(sessionId, expired.request.approvalId))
                    eventBus.emit(RuntimeEvent.PermissionDenied(sessionId, expired.request.toolName, "no answer was given in time"))
                }
            }
    }

    internal suspend fun processPermissionRequestFile(sessionId: String, file: File) {
        val approvalId = file.name.removeSuffix(".request")
        runCatching {
            val json = JSONObject(file.readText())
            val toolName = json.stringOrEmpty("tool_name", "Tool")
            val input = json.optJSONObject("tool_input") ?: JSONObject()
            val command = input.stringOrEmpty("command").ifBlank { null }
            val paths = listOf("file_path", "path", "notebook_path")
                .mapNotNull { key -> input.stringOrEmpty(key).takeIf(String::isNotBlank) }
            val explanation = input.stringOrEmpty("description")
                .ifBlank { command.orEmpty() }
                .ifBlank { "$toolName running in project" }

            val risk = classifyRisk(toolName, command, paths)
            val response = responseFile(file, approvalId)
            if (risk == RiskLevel.HIGH && !autoApproveAllTools()) {
                if (interactiveHighRiskApprovals()) {
                    val toolRequest = ToolRequest(
                        approvalId = approvalId,
                        sessionId = sessionId,
                        toolName = toolName,
                        explanation = sanitizeForDisplay(explanation),
                        affectedPaths = paths,
                        commandPreview = command,
                        risk = risk,
                    )
                    pending[approvalId] = PendingPermission(toolRequest, response)
                    runCatching { file.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    Log.i("ClaudeBridge", "Requesting interactive user approval $approvalId for high-risk $toolName ($paths)")
                    eventBus.emit(RuntimeEvent.ToolRequested(sessionId, toolRequest))
                } else {
                    Log.w("ClaudeBridge", "Denied high-risk permission request $approvalId for $toolName ($paths)")
                    response.writeText("deny")
                    runCatching { file.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    eventBus.emit(
                        RuntimeEvent.PermissionDenied(
                            sessionId,
                            toolName,
                            sanitizeForDisplay("high-risk request blocked: $explanation"),
                        ),
                    )
                }
            } else {
                Log.d("ClaudeBridge", "Approving permission request $approvalId for $toolName ($paths, risk=$risk)")
                response.writeText("allow")
                runCatching { file.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                // Completion is reported by the stream's tool_result; emitting it at approval time marked long
                // commands as finished before they had run and produced a duplicate row later.
            }
        }.onFailure {
            // With auto-approve on, a request this app failed to parse must not become a refusal for the agent.
            val decision = if (autoApproveAllTools()) "allow" else "deny"
            runCatching { responseFile(file, approvalId).writeText(decision) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { file.delete() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }
    }

    private suspend fun consumeClaudeEvent(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        consumeClaudeJsonEvent(sessionId, json)
        return true
    }

    private suspend fun consumeClaudeJsonEvent(sessionId: String, json: JSONObject) {
        when (json.stringOrEmpty("type")) {
            "stream_event" -> json.optJSONObject("event")?.let { consumeClaudeJsonEvent(sessionId, it) }
            "system" -> when (json.stringOrEmpty("subtype")) {
                "init" -> Unit
                "thinking_tokens" -> emitReasoningProgress(sessionId, json.optInt("estimated_tokens"))
                "permission_denied" -> eventBus.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        sanitizeForDisplay(json.stringOrEmpty("decision_reason").ifBlank { json.stringOrEmpty("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = json.optJSONObject("content_block")
                when (block?.stringOrEmpty("type")) {
                    "thinking" -> {
                        currentThinkingBlockId += 1
                        streamedThinking.clear()
                        emitReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let {
                            streamedThinking.append(it)
                            emitReasoningSummary(sessionId, it, force = true)
                        }
                    }
                    "text" -> streamedText.clear()
                }
            }
            "content_block_delta" -> {
                val delta = json.optJSONObject("delta")
                when (delta?.stringOrEmpty("type")) {
                    "thinking_delta" -> delta.stringOrEmpty("thinking").takeIf(String::isNotEmpty)?.let {
                        streamedThinking.append(it)
                        emitReasoningSummary(sessionId, streamedThinking.toString())
                    }
                    "text_delta", "" -> delta.stringOrEmpty("text").takeIf(String::isNotEmpty)?.let {
                        streamedText.append(it)
                        eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                    }
                }
            }
            "content_block_stop" -> {
                if (streamedThinking.isNotBlank()) {
                    emitReasoningSummary(sessionId, streamedThinking.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val message = json.optJSONObject("message") ?: return
                val content = message.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    when (block.stringOrEmpty("type")) {
                        "text" -> if (streamedText.isEmpty()) {
                            block.stringOrEmpty("text").takeIf(String::isNotBlank)?.let {
                                eventBus.emit(RuntimeEvent.AssistantDelta(sessionId, it))
                            }
                        }
                        "thinking" -> block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let {
                            if (currentThinkingBlockId == 0L || streamedThinking.toString() != it) {
                                currentThinkingBlockId += 1
                                streamedThinking.clear()
                                streamedThinking.append(it)
                                emitReasoningSummary(
                                    sessionId,
                                    it,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> emitToolStarted(sessionId, block)
                    }
                }
                streamedText.clear()
                if (message.stringOrEmpty("stop_reason") == "end_turn") {
                    turnCompletedFromStream = true
                }
            }
            "user" -> {
                val content = json.optJSONObject("message")?.optJSONArray("content") ?: return
                for (index in 0 until content.length()) {
                    val block = content.optJSONObject(index) ?: continue
                    if (block.stringOrEmpty("type") == "tool_result") {
                        val toolId = block.stringOrEmpty("tool_use_id")
                        val toolName = toolNames.remove(toolId) ?: "Tool"
                        val contentArray = block.optJSONArray("content")
                        val extractedContent = if (contentArray != null) {
                            buildString {
                                for (itemIndex in 0 until contentArray.length()) {
                                    val item = contentArray.optJSONObject(itemIndex)
                                    val text = item?.stringOrEmpty("text")?.takeIf(String::isNotBlank)
                                        ?: contentArray.optString(itemIndex).takeIf(String::isNotBlank)
                                    if (text != null) {
                                        if (isNotEmpty()) append('\n')
                                        append(text)
                                    }
                                }
                            }
                        } else {
                            block.stringOrEmpty("content")
                        }
                        val result = extractedContent
                            .ifBlank { if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully" }
                        eventBus.emit(RuntimeEvent.ToolCompleted(sessionId, toolName, sanitizeForDisplay(result)))
                        if (block.optBoolean("is_error") && isPermissionDenialText(result)) {
                            eventBus.emit(RuntimeEvent.PermissionDenied(sessionId, toolName, sanitizeForDisplay(result)))
                        }
                    }
                }
            }
            "result" -> {
                if (json.optBoolean("is_error")) {
                    val message = json.stringOrEmpty("result").ifBlank { "Claude Code reported an error" }
                    if (isPermissionDenialText(message)) {
                        // A refusal is not a failed connection: tell the user and let the session carry on.
                        eventBus.emit(RuntimeEvent.PermissionDenied(sessionId, "Tool", sanitizeForDisplay(message)))
                    } else {
                        throw IllegalStateException(message)
                    }
                }
                turnCompletedFromStream = true
                endTurnOrContinue()
            }
        }
    }

    /** A turn ended: run the steering messages that arrived meanwhile, otherwise close stdin so the CLI exits. */
    private fun endTurnOrContinue() {
        synchronized(this) {
            if (steersPending > 0) {
                steersPending = 0
                idleCloseAt = android.os.SystemClock.elapsedRealtime() + STEER_FOLLOW_UP_WAIT_MS
                return
            }
        }
        closeSteerInput()
        terminateActiveProcessGracefully()
    }

    private fun closeSteerInput() {
        synchronized(this) {
            val input = steerInput ?: return
            steerInput = null
            runCatching { input.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }
    }

    /**
     * Adds [text] to the running Claude Code session without stopping it. False when no session takes input (none is
     * running, or its turn already ended): the caller then starts a new turn instead.
     */
    fun steer(text: String): Boolean = synchronized(this) {
        val input = steerInput ?: return false
        if (activeProcess?.isAlive != true || text.isBlank()) return false
        val guarded = "$text\n\n[Reminder: ${ANTI_HALLUCINATION_RULES.first()} ${ANTI_HALLUCINATION_RULES[1]} ${ANTI_HALLUCINATION_RULES[3]}]"
        runCatching {
            input.write(streamJsonUserMessage(guarded))
            input.flush()
        }.onFailure { return false }
        steersPending++
        idleCloseAt = 0L
        true
    }

    private suspend fun emitReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val summary = sanitizeForDisplay(text).trim().take(2_000)
        if (summary.isBlank() && !startsNewBlock) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || now - lastThinkingUpdateAt >= 150) {
            lastThinkingUpdateAt = now
            eventBus.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = summary,
                    blockId = currentThinkingBlockId,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitReasoningProgress(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (tokens - lastReasoningTokens >= 25 || now - lastReasoningUpdateAt >= 500) {
            lastReasoningTokens = tokens
            lastReasoningUpdateAt = now
            eventBus.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            pushForegroundProgress("Thinking…")
        }
    }

    private suspend fun emitToolStarted(sessionId: String, block: JSONObject) {
        val id = block.stringOrEmpty("id")
        if (id.isNotBlank() && !seenToolCalls.add(id)) return
        val name = block.stringOrEmpty("name", "Tool")
        if (id.isNotBlank()) toolNames[id] = name
        val input = block.optJSONObject("input") ?: JSONObject()
        val detail = when (name) {
            "Bash" -> input.stringOrEmpty("command").ifBlank { input.stringOrEmpty("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> input.stringOrEmpty("file_path").ifBlank { input.stringOrEmpty("notebook_path") }
            "Glob" -> input.stringOrEmpty("pattern")
            "Grep" -> input.stringOrEmpty("pattern").let { pattern ->
                input.stringOrEmpty("path").takeIf(String::isNotBlank)?.let { "$pattern in $it" } ?: pattern
            }
            else -> input.stringOrEmpty("description").ifBlank { "Running $name" }
        }
        eventBus.emit(RuntimeEvent.ToolStarted(sessionId, name, sanitizeForDisplay(detail.ifBlank { "Running $name" })))
        val compactDetail = detail.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { name }
        pushForegroundProgress("Running $name: $compactDetail")
    }

    @Suppress("UNUSED_PARAMETER")
    private fun terminalStatus(line: String): Pair<String, String>? = null

    private suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            val slug = activeProjectSlug?.takeIf { it.isNotBlank() }
            if (slug != null) {
                finishForegroundRuntimeRes(true, slug, com.nextcraft.forge.R.string.notif_agent_finished_in, "Claude Code", slug)
            } else {
                finishForegroundRuntimeRes(true, "Project", com.nextcraft.forge.R.string.notif_agent_finished, "Claude Code")
            }
        }
    }

    private suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    private fun sanitizeForDisplay(value: String): String {
        return value
            .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
            .replace(Regex("(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), "gh_••••")
            // Also covers JSON ("api_key":"x") and "Authorization: Bearer <token>", where the token follows the scheme word.
            .replace(Regex("(?i)(authorization|api[_-]?key|(?:access|refresh|auth)[_-]?token|secret|password)[\"']?\\s*[:=]\\s*[\"']?(?:(?:bearer|basic|token)\\s+)?[^\\s,}\"']+"), "${'$'}1: ••••")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(600)
    }

    private fun buildContextPrompt(
        currentPrompt: String,
        history: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
    ): String = buildAgentWorkspacePrompt(
        currentPrompt = currentPrompt,
        history = history,
        guestWorkspacePath = guestWorkspacePath,
        projectKind = projectKind,
        androidStackInstalled = installer.isStackInstalled(DevStack.ANDROID),
        maxHistoryChars = MAX_CONVERSATION_HISTORY_CHARACTERS,
    )

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    private fun classifyRisk(tool: String, command: String?, paths: List<String>): RiskLevel =
        classifyToolRisk(tool, command, paths)

    private fun friendlyError(error: Throwable): String {
        val message = error.message.orEmpty()
        return when {
            error is ProviderSessionException -> message
            message.contains("user not found", true) -> "User not found. Check the API key and provider account."
            message.contains("checksum", true) -> "Runtime verification failed. Nothing unverified was executed."
            message.contains("HTTP 401", true) || message.contains("authentication", true) -> "The provider rejected the saved API key."
            message.isBlank() -> "The real Claude Code runtime could not start."
            else -> message.take(500)
        }
    }

    private fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        foreground.progress(activeProjectSlug, detailRaw)
    }

    /** One stream-json input line: a user message with [text]. */
    private fun streamJsonUserMessage(text: String): ByteArray =
        (
            JSONObject()
                .put("type", "user")
                .put("message", JSONObject().put("role", "user").put("content", org.json.JSONArray().put(JSONObject().put("type", "text").put("text", text))))
                .toString() + "\n"
            ).toByteArray(Charsets.UTF_8)

    private fun terminateActiveProcessGracefully() {
        val running = activeProcess ?: return
        Thread {
            runCatching {
                Thread.sleep(500)
                if (running.isAlive) running.destroy()
                Thread.sleep(3_000)
                if (running.isAlive) running.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }.apply { isDaemon = true }.start()
    }

    private fun startForegroundRuntime(projectName: String) = foreground.start(projectName)

    private fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) =
        foreground.finish(completed, projectName, detail)

    private fun finishForegroundRuntimeRes(completed: Boolean, projectName: String, @androidx.annotation.StringRes detailRes: Int, vararg args: String) =
        foreground.finishRes(completed, projectName, detailRes, *args)

    private fun cancelForegroundRuntime() = foreground.cancel()

    private data class PendingPermission(
        val request: ToolRequest,
        val response: File,
        val requestedAtElapsedRealtime: Long = android.os.SystemClock.elapsedRealtime(),
    )
    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        /** Sessions running right now in any ClaudeRuntimeBridge instance (one instance per parallel chat). */
        private val liveSessions: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /** Request files are named `<sessionId>_<uuid>.request` by the permission hook. */
        internal fun requestPrefix(sessionId: String): String = "${sessionId}_"

        /** True when a request file belongs to a session that is still running (so it must not be cleaned up). */
        internal fun isLiveSessionRequest(fileName: String): Boolean =
            liveSessions.any { fileName.startsWith(requestPrefix(it)) }

        private const val HIGH_RISK_APPROVAL_TIMEOUT_MS = 40_000L
        private const val STALE_RESPONSE_MS = 600_000L
        private const val MAX_CONVERSATION_HISTORY_CHARACTERS = 160_000
    }
}

private val PERMISSION_DENIAL_REGEX = Regex(
    "permission.{0,60}denied|denied.{0,40}permission|not allowed|doesn't want to proceed|user rejected|blocked by|requires approval",
    RegexOption.IGNORE_CASE,
)

/** True for Claude Code's text when a tool call was refused by permissions rather than failing for a technical reason. */
internal fun isPermissionDenialText(text: String): Boolean = PERMISSION_DENIAL_REGEX.containsMatchIn(text)

private val DANGEROUS_PATTERNS = listOf(
    "git push", "git reset", "git clean", "git checkout --", "sudo", "curl ", "wget ", "chmod ", "chown ", "mkfs",
    "dd if=", "/dev/block", "/dev/loop", "/dev/sd", "/dev/zero", "/dev/mem", "/dev/kmem", "/proc/", "umount",
    "| sh", "| bash", "find -exec", "find -delete", "pocketdev-bridge", "nextcraft-bridge", "pocket-bridge",
    "/opt/pocket", "/opt/nextcraft", ".claude", ".git-credentials",
    // Root shell on Android itself (Settings → Android system access).
    "android-shell", "android-screencap", "android-dumpsys", "nsenter",
    // Credential stores the Bash route could cat: the SSH keys and the GitHub CLI token.
    ".ssh/", "/.ssh", ".config/gh",
)

private val SAFE_STREAM_REDIRECT_REGEX =
    Regex("(?:[12]?>>?\\s*/dev/null|&>\\s*/dev/null|[12]>&[12]|<\\s*/dev/null)")

// Commands whose first word is auto-approved; everything else (python, node, sh, find -exec ...) needs the user.
private val SAFE_BASH_COMMANDS = setOf(
    "ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort", "uniq", "diff", "cd",
    "true", "false", "test", "which", "type", "stat", "file", "tree", "mkdir", "touch", "date", "printf",
    "basename", "dirname", "realpath", "readlink", "cut", "tr", "awk", "sed", "find", "xargs", "jq", "du", "df",
    "env", "md5sum", "sha256sum", "cp", "mv", "gradle", "javac", "kotlinc",
)
private val UNSAFE_TOOL_FLAG_REGEX = Regex("""--pre(?:=.*)?|--hostname-bin(?:=.*)?|--compress-program(?:=.*)?|--output(?:=.*)?|-fprint0?|-fprintf|-fls""")
private val ENV_ASSIGNMENT_REGEX = Regex("[A-Za-z_][A-Za-z0-9_]*=\\S*")

// awk and sed are safe for plain text processing, but their scripts can run commands or write files.
private val AWK_WORD_REGEX = Regex("\\bawk\\b")
private val AWK_EXEC_REGEX = Regex("""\|\s*\\?"|system\s*\(|getline|\|&|\bpopen\b|\bENVIRON\b""")
private val SED_WORD_REGEX = Regex("\\bsed\\b")
private val SED_EXEC_OR_WRITE_REGEX = Regex("""(?:^|[;{}'"\s/0-9$,!])(?:e(?:['";}\s]|$)|[wWrR]\s)""")

// jq can print the process environment (`jq -n env`, `$ENV`), which holds provider keys.
private val JQ_WORD_REGEX = Regex("\\bjq\\b")
private val JQ_ENV_REGEX = Regex("""\benv\b|[$]ENV|\binput_filename\b""")

// A shell variable expansion can print an exported secret (`echo "$ANTHROPIC_API_KEY"`); those commands need the user.
private val VARIABLE_EXPANSION_REGEX = Regex("""\$[A-Za-z_{@*#?0-9!-]""")

private fun scriptCanEscapeSandbox(text: String): Boolean =
    (JQ_WORD_REGEX.containsMatchIn(text) && JQ_ENV_REGEX.containsMatchIn(text)) ||
    (AWK_WORD_REGEX.containsMatchIn(text) && AWK_EXEC_REGEX.containsMatchIn(text)) ||
        (SED_WORD_REGEX.containsMatchIn(text) && SED_EXEC_OR_WRITE_REGEX.containsMatchIn(text))

/** Commands that create, overwrite or move files; their path arguments must stay inside the project. */
/** The option letters of a short-option cluster (`-Ei` gives "Ei"); empty for a long option or a non-option word. */
private fun String.shortFlagCluster(): String =
    if (startsWith("-") && !startsWith("--")) drop(1).takeWhile { it.isLetter() } else ""

private fun mutatesFiles(first: String, words: List<String>): Boolean =
    first in setOf("cp", "mv", "mkdir", "touch", "uniq") ||
        (first == "sed" && words.any { it.startsWith("--in-place") || it.trim('\'', '"').shortFlagCluster().contains('i') })

private fun isOutsideWorkspacePath(word: String): Boolean {
    val arg = word.trim('\'', '"')
    if (arg.startsWith("-") || arg.isEmpty()) return false
    // ~/.bashrc and friends: the tilde expands to the home of root, which is outside the project.
    if (arg == "~" || arg.startsWith("~/")) return true
    if (isSensitivePath(arg)) return true
    return arg.startsWith("/") && !arg.startsWith("/workspace/") && !arg.startsWith("/tmp/")
}

private val SAFE_GIT_SUBCOMMANDS = setOf(
    "status", "diff", "log", "show", "add", "commit", "branch", "rev-parse", "ls-files", "ls-tree", "blame",
    "remote", "fetch", "pull", "stash", "restore", "switch", "tag",
)
private val SENSITIVE_PATH_PREFIXES = listOf(
    "/opt/", "/etc/", "/proc/", "/sys/", "/dev/", "/usr/", "/bin", "/sbin", "/lib", "/var/",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge",
)
private val SENSITIVE_PATH_PARTS = listOf("..", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook", "/.git/")

// A file written under .git/ (hooks, config with core.fsmonitor / diff.external) runs the next time git is auto-approved.
private fun isSensitivePath(path: String): Boolean =
    SENSITIVE_PATH_PREFIXES.any(path::startsWith) || SENSITIVE_PATH_PARTS.any(path::contains) || path.startsWith(".git/")

/** Files whose content must never reach the model; a glob or quote trick in a command is matched against them. */
private val PROTECTED_LITERAL_PATHS = listOf(
    "/proc/self/environ", "/proc/1/environ", "/proc/thread-self/environ",
    "/root/.claude/.credentials.json", "~/.claude/.credentials.json", "/root/.claude.json", "~/.claude.json",
    "/root/.ssh/id_ed25519", "~/.ssh/id_ed25519", "/root/.ssh/id_rsa", "~/.ssh/id_rsa",
    "/root/.git-credentials", "~/.git-credentials", "/root/.config/gh/hosts.yml", "~/.config/gh/hosts.yml",
)

// A word with glob characters that can expand to a protected file (a '?' standing in for a letter of /proc/self/environ, or a
// '*' inside the name of the Claude credentials folder).
private fun commandTargetsProtectedFile(command: String): Boolean {
    val words = command.split(Regex("\\s+")).map { it.trim('\'', '"') }
    return words.any { word ->
        if (word.none { it == '*' || it == '?' || it == '[' }) return@any false
        val regex = runCatching {
            Regex(buildString {
                var i = 0
                while (i < word.length) {
                    when (val c = word[i]) {
                        '*' -> append("[^/]*")
                        '?' -> append("[^/]")
                        '[' -> {
                            val end = word.indexOf(']', i + 1)
                            if (end > i) { append(word.substring(i, end + 1)); i = end } else append("\\[")
                        }
                        else -> append(Regex.escape(c.toString()))
                    }
                    i++
                }
            })
        }.getOrNull() ?: return@any true
        PROTECTED_LITERAL_PATHS.any(regex::matches)
    }
}

private val CREDENTIAL_PATH_PARTS = listOf(
    "/proc/", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge", "/.gradle/gradle.properties",
)

private fun isCredentialPath(path: String): Boolean = CREDENTIAL_PATH_PARTS.any(path::contains)

private fun maskQuotedAndEscapedSeparators(input: String): String {
    val out = StringBuilder(input.length)
    var inSingleQuote = false
    var inDoubleQuote = false
    var escaped = false
    for (ch in input) {
        if (escaped) {
            out.append(if (ch == '|' || ch == '&' || ch == ';') '_' else ch)
            escaped = false
            continue
        }
        if (ch == '\\' && !inSingleQuote) {
            escaped = true
            out.append(ch)
            continue
        }
        if (ch == '\'' && !inDoubleQuote) {
            inSingleQuote = !inSingleQuote
            out.append(ch)
            continue
        }
        if (ch == '"' && !inSingleQuote) {
            inDoubleQuote = !inDoubleQuote
            out.append(ch)
            continue
        }
        if ((inSingleQuote || inDoubleQuote) && (ch == '|' || ch == '&' || ch == ';')) {
            out.append('_')
        } else {
            out.append(ch)
        }
    }
    return out.toString()
}

/** System directories whose programs may be named by full path; any other path could point at a file the agent wrote. */
/** Options of env/xargs that take a value as the next word. */
private val WRAPPER_OPTIONS_WITH_VALUE = setOf("-u", "-C", "-S", "-I", "-i", "-E", "-e", "-d", "-L", "-l", "-n", "-P", "-s", "-a")
private val WRAPPER_LONG_OPTIONS_WITH_VALUE = setOf("unset", "chdir", "split-string", "replace", "eof", "delimiter", "max-lines", "max-args", "max-procs", "max-chars", "arg-file")

private val TRUSTED_COMMAND_DIRS = listOf("/usr/bin/", "/bin/", "/usr/local/bin/")

private fun isSafeBashSegment(segment: String): Boolean {
    val words = segment.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    // A leading VAR=value (LD_PRELOAD=./x.so cat f, PATH=./bin ls) changes what the "safe" command really runs.
    if (words.firstOrNull()?.matches(ENV_ASSIGNMENT_REGEX) == true) return false
    val firstWord = words.firstOrNull()?.trim('\'', '"') ?: return false
    // `./ls` or `/workspace/cat` is a program the agent may have just written, not the allowlisted tool of that name.
    if ('/' in firstWord && TRUSTED_COMMAND_DIRS.none(firstWord::startsWith)) return false
    // `/usr/bin/../../workspace/x/cat` starts with a trusted directory but runs a file of the project.
    if (".." in firstWord) return false
    val first = firstWord.substringAfterLast('/')
    if (first == "env" || first == "xargs") {
        // Wrappers run another command, so judge that command. A bare `env` dumps every exported
        // variable (including provider API keys) into the model context and is never auto-approved.
        // `env VAR=value cmd` is refused for the same reason as a leading assignment.
        if (words.drop(1).any { it.matches(ENV_ASSIGNMENT_REGEX) }) return false
        val options = words.drop(1).takeWhile { it.startsWith("-") }
        // `env -u cat sh -c ...` / `xargs -I cat sh -c ...`: an option's value would be mistaken for the wrapped command.
        if (options.any { it in WRAPPER_OPTIONS_WITH_VALUE || (it.startsWith("--") && '=' !in it && it.removePrefix("--") in WRAPPER_LONG_OPTIONS_WITH_VALUE) }) return false
        val wrapped = words.drop(1).dropWhile { it.startsWith("-") }
        if (wrapped.isEmpty()) return first == "xargs"
        return isSafeBashSegment(wrapped.joinToString(" "))
    }
    // Allowlisted tools that can still run a program (rg --pre, sort --compress-program) or write a file
    // (find -fprint, git diff --output, sort/tree -o): never auto-approved.
    val args = words.drop(1).map { it.trim('\'', '"') }
    if (args.any { UNSAFE_TOOL_FLAG_REGEX.matches(it) }) return false
    // -o may sit inside a cluster (-uo out) and getopt_long accepts prefixes of --output.
    if ((first == "sort" || first == "tree") && args.any { it.startsWith("--out") || it.shortFlagCluster().contains('o') }) return false
    // `date -s` sets the system clock.
    if (first == "date" && args.any { it.shortFlagCluster().contains('s') || it.startsWith("--set") }) return false
    if (mutatesFiles(first, words) && words.drop(1).any(::isOutsideWorkspacePath)) return false
    if (first == "git") return words.getOrNull(1)?.trim('\'', '"') in SAFE_GIT_SUBCOMMANDS
    if (first == "find" && words.any { it.trim('\'', '"') in setOf("-exec", "-execdir", "-delete", "-ok", "-okdir") }) return false
    return first in SAFE_BASH_COMMANDS
}

/**
 * Default-deny for Bash: only simple commands from [SAFE_BASH_COMMANDS] chained without
 * substitution or redirection are auto-approved; anything else is HIGH (user prompt or deny).
 */
internal fun classifyToolRisk(tool: String, command: String?, paths: List<String> = emptyList()): RiskLevel {
    val preview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    if (DANGEROUS_PATTERNS.any(preview::contains)) return RiskLevel.HIGH
    if (tool == "Bash") {
        // `cat /pro''c/self/environ` and `cat $'/proc/self/environ'` read the same file as the plain spelling.
        val unquoted = preview.replace(Regex("""\$?['"\\]"""), "")
        if (unquoted != preview && DANGEROUS_PATTERNS.any(unquoted::contains)) return RiskLevel.HIGH
        if (commandTargetsProtectedFile(command.orEmpty())) return RiskLevel.HIGH
    }
    if (tool in setOf("Write", "Edit", "NotebookEdit") && paths.any(::isSensitivePath)) return RiskLevel.HIGH
    // Reads and searches must not reach credentials or the process environment either (/proc/self/environ, ~/.claude).
    if (tool != "Bash" && paths.any(::isCredentialPath)) return RiskLevel.HIGH
    return when (tool) {
        "Bash" -> {
            val text = command.orEmpty().replace(SAFE_STREAM_REDIRECT_REGEX, " ")
            val hasUnsafeSyntax = listOf("`", "$(", "<(", ">", "<", "\n", "\r").any(text::contains) ||
                VARIABLE_EXPANSION_REGEX.containsMatchIn(text) ||
                scriptCanEscapeSandbox(text)
            val masked = maskQuotedAndEscapedSeparators(text)
            val segments = masked.split(Regex("&&|\\|\\||[;|&]")).filter(String::isNotBlank)
            if (text.isBlank() || hasUnsafeSyntax || segments.isEmpty() || !segments.all(::isSafeBashSegment)) {
                RiskLevel.HIGH
            } else {
                RiskLevel.REVIEW
            }
        }
        "Write", "Edit", "NotebookEdit" -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}

/** How long to wait for the follow-up turn of a steering message before ending the session. */
private const val STEER_FOLLOW_UP_WAIT_MS = 20_000L
