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
    /** The chat's saved Claude Code session ([SavedConversation.encode]), resumed so tool results stay in context. */
    private val savedConversation: () -> String? = { null },
    private val saveConversation: (String?) -> Unit = {},
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
    private var claudeConversationId = ""
    private var resumedConversation = false
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
            val stopAction: () -> Unit = {
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
            stopHandler = stopAction
            RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sessionId", stopAction)
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
            val activeGateway = formatGateway ?: openRouterGateway
            val guestAuthToken = formatGateway?.sessionToken ?: openRouterGateway?.sessionToken ?: secret
            val launch = RuntimeLaunchConfigBuilder.build(
                provider,
                authToken = guestAuthToken,
                localGatewayUrl = formatGateway?.url ?: openRouterGateway?.url,
                useStoredLogin = storedLogin && activeGateway == null,
                claudeEffort = claudeEffort(),
            )
            Log.d("ClaudeBridge", "Provider: ${provider.kind}, Model: ${provider.model}")
            Log.d("ClaudeBridge", "Launch environment keys: ${launch.environment.keys}")

            val guestWorkspacePath = "/workspace/$projectSlug"
            // Resume the chat's own Claude Code session (it keeps tool results and compacts itself when full); only a
            // session made with the same provider in the same workspace, since another API may reject its history.
            val conversationTag = "${provider.kind.name}:$guestWorkspacePath"
            val saved = SavedConversation.decode(savedConversation())?.takeIf { it.tag == conversationTag }
            resumedConversation = saved != null
            claudeConversationId = saved?.id ?: UUID.randomUUID().toString()
            val contextPrompt = buildContextPrompt(
                prompt, conversationHistory.unseenBy(saved), guestWorkspacePath, projectKind, resumed = saved != null,
            )

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
                if (saved != null) addAll(listOf("--resume", saved.id)) else addAll(listOf("--session-id", claudeConversationId))
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
                    // The chat now holds the history plus this answer, all of which the session has seen.
                    saveConversation(SavedConversation(claudeConversationId, conversationHistory.size + 1, conversationTag).encode())
                    emitCompletedOnce(sessionId)
                } else {
                    if (userStopRequested) throw ProviderSessionException("Stopped by user")
                    if (resumedConversation && lastDiagnostic.contains("No conversation found", ignoreCase = true)) {
                        // Gone (runtime reinstalled, workspace moved): the retry sends the chat history instead.
                        saveConversation(null)
                        throw ProviderSessionException(SESSION_LOST_MESSAGE)
                    }
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
        priorMessages: List<ChatMessage>,
        guestWorkspacePath: String,
        projectKind: ProjectKind,
        resumed: Boolean = false,
    ): String = buildAgentWorkspacePrompt(
        currentPrompt = currentPrompt,
        priorMessages = priorMessages,
        guestWorkspacePath = guestWorkspacePath,
        projectKind = projectKind,
        androidStackInstalled = installer.isStackInstalled(DevStack.ANDROID),
        maxHistoryChars = MAX_CONVERSATION_HISTORY_CHARACTERS,
        resumed = resumed,
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
        const val SESSION_LOST_MESSAGE = "The saved agent session was not found; continuing from the chat history."
    }
}
