package com.nextcraft.forge.runtime

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.StringRes
import com.nextcraft.forge.R
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.data.AppPreferences
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
import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

internal object ProviderRuntimeErrorDetector {
    private val AUTH_FAILURE_PHRASES = listOf(
        "authentication_failed",
        "authentication failed",
        "authentication_error",
        "invalid bearer token",
        "oauth access token is invalid",
        "oauth token has been revoked",
        "invalid api key",
        "invalid_api_key",
        "invalid x-api-key",
        "oauth token has expired",
        "token has expired",
        "api key expired",
        "not logged in",
        "api error: 401",
        "http 401",
        "status 401",
        "401 unauthorized",
    )

    fun detect(line: String): String? {
        val clean = line.trim()
        val parsed = runCatching { JSONObject(clean) }.getOrNull()
        if (parsed == null && clean.startsWith("{")) return null
        val textToInspect = if (parsed == null) {
            clean.lowercase()
        } else {
            buildString {
                append(parsed.stringOrEmpty("error"))
                if (parsed.stringOrEmpty("type") == "error") {
                    append(' ').append(parsed.stringOrEmpty("message"))
                    parsed.optJSONObject("error")?.let { nested ->
                        append(' ').append(nested.stringOrEmpty("type"))
                        append(' ').append(nested.stringOrEmpty("message"))
                    }
                }
                if (parsed.stringOrEmpty("type") == "result" && parsed.optBoolean("is_error")) {
                    append(' ').append(parsed.stringOrEmpty("result"))
                }
            }.lowercase()
        }
        return when {
            "user not found" in textToInspect -> "User not found. Check the API key and provider account."
            AUTH_FAILURE_PHRASES.any { it in textToInspect } ||
                (parsed?.stringOrEmpty("subtype") == "api_retry" && parsed.optInt("error_status") == 401) ->
                "The provider rejected the saved API key."
            else -> null
        }
    }
}

class ClaudeRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { profile ->
        ApiKeyVault(context).rotateNext(profile.kind.name)
    },
    private val autoApproveAllTools: () -> Boolean = {
        runCatching { AppPreferences(context).autoApproveAllTools }.getOrDefault(true)
    },
    private val claudeEffort: () -> String = {
        runCatching { AppPreferences(context).claudeEffort }.getOrDefault("")
    },
    private val savedConversation: () -> String? = { null },
    private val saveConversation: (String?) -> Unit = {},
    private val interactiveHighRiskApprovals: () -> Boolean = {
        runCatching { AppPreferences(context).interactiveHighRiskApprovals }.getOrDefault(true)
    },
) : RuntimeBridge {
    private val runtimeInstaller by lazy { RuntimeInstaller(context) }
    private val workspaceCheckpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    private val sharedEvents = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = sharedEvents
    private val awaitingApprovals = ConcurrentHashMap<String, AwaitingApprovalEntry>()
    private val activeToolLabels = ConcurrentHashMap<String, String>()
    private val processedToolCallIds = ConcurrentHashMap.newKeySet<String>()
    private val completedSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile private var runningProcess: Process? = null
    @Volatile private var liveStdinStream: OutputStream? = null
    @Volatile private var queuedSteerCount = 0
    @Volatile private var steerFollowUpDeadlineMs = 0L
    @Volatile private var currentSessionId: String? = null
    @Volatile private var stopRequestedByUser: Boolean = false
    @Volatile private var sawResultFrame: Boolean = false
    @Volatile private var currentSlug: String? = null

    private val fgNotifier = ForegroundSession(context, "ClaudeBridge")
    private var sessionConvId = ""
    private var isResumedSession = false
    private val assistantTextBuffer = StringBuilder()
    private val reasoningTextBuffer = StringBuilder()
    private var prevReasoningTokenCount = 0
    private var prevReasoningTokenEmitMs = 0L
    private var prevReasoningSummaryEmitMs = 0L
    private var reasoningBlockSeq = 0L

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sessionId = UUID.randomUUID().toString()
        completedSessionIds.remove(sessionId)
        currentSessionId = sessionId
        liveSessions.add(sessionId)
        stopRequestedByUser = false
        sawResultFrame = false
        liveStdinStream = null
        queuedSteerCount = 0
        steerFollowUpDeadlineMs = 0L
        currentSlug = projectSlug
        fgNotifier.begin()
        activeToolLabels.clear()
        processedToolCallIds.clear()
        prevReasoningTokenCount = 0
        prevReasoningTokenEmitMs = 0L
        prevReasoningSummaryEmitMs = 0L
        reasoningBlockSeq = 0L
        assistantTextBuffer.clear()
        reasoningTextBuffer.clear()
        sharedEvents.emit(RuntimeEvent.SessionStarted(sessionId))
        updateForegroundStatus("Starting Claude Code…")

        val hasGuestOauthLogin = canUseGuestClaudeLogin(provider)
        val resolvedSecret = if (hasGuestOauthLogin) {
            ""
        } else {
            secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        }
        if (resolvedSecret.isBlank() && !hasGuestOauthLogin) {
            val missingAuthMsg = if (provider.kind == ProviderKind.CLAUDE) {
                "Not signed in to Claude. Open Agent → AI provider and sign in, or add a subscription token."
            } else {
                "No API key is saved for ${provider.kind.title}."
            }
            sharedEvents.emit(RuntimeEvent.SessionFailed(sessionId, missingAuthMsg))
            currentSessionId = null
            liveSessions.remove(sessionId)
            fgNotifier.clearStart()
            return@withContext sessionId
        }

        var openAiBridge: LocalFormatGateway? = null
        var openRouterBridge: OpenRouterRoutingGateway? = null
        var sessionLogFile: File? = null
        var cancelCallback: (() -> Unit)? = null

        runCatching {
            cancelCallback = {
                stopRequestedByUser = true
                runningProcess?.let { proc ->
                    Thread({
                        runCatching {
                            proc.destroy()
                            Thread.sleep(500)
                            if (proc.isAlive) proc.destroyForcibly()
                        }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    }, "forge-claude-stop").apply { isDaemon = true }.start()
                }
            }
            cancelCallback?.let { RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sessionId", it) }
            fgNotifier.start(projectSlug)
            val rt = runtimeInstaller.installedRuntime()
            runtimeInstaller.ensureSettingsAndHooks()
            File(context.filesDir, "runtime-bridge")
                .listFiles { f -> f.name.endsWith(".request") && !isLiveSessionRequest(f.name) }
                .orEmpty()
                .forEach { runCatching { it.delete() } }

            val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
            workspaceCheckpoints.createCheckpoint(projectId, wsDir)
            val preSnapshot = workspaceCheckpoints.snapshot(wsDir)

            openAiBridge = if (
                provider.kind.protocol == ProviderProtocol.OPENAI_CHAT ||
                provider.kind.protocol == ProviderProtocol.OPENAI_RESPONSES
            ) {
                LocalFormatGateway(provider, resolvedSecret) { rotateSecretFor(provider) }.start()
            } else {
                null
            }
            openRouterBridge = if (provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()) {
                OpenRouterRoutingGateway(provider, resolvedSecret) { rotateSecretFor(provider) }.start()
            } else {
                null
            }
            val proxyActive = openAiBridge != null || openRouterBridge != null
            val tokenForGuest = openAiBridge?.sessionToken ?: openRouterBridge?.sessionToken ?: resolvedSecret
            val launchCfg = RuntimeLaunchConfigBuilder.build(
                provider = provider,
                authToken = tokenForGuest,
                localGatewayUrl = openAiBridge?.url ?: openRouterBridge?.url,
                useStoredLogin = hasGuestOauthLogin && !proxyActive,
                claudeEffort = claudeEffort(),
            )

            val guestWorkdir = "/workspace/$projectSlug"
            val scopeTag = "${provider.kind.name}:$guestWorkdir"
            val decodedConv = SavedConversation.decode(savedConversation())?.takeIf { it.tag == scopeTag }
            isResumedSession = decodedConv != null
            sessionConvId = decodedConv?.id ?: UUID.randomUUID().toString()
            val fullPrompt = assembleWorkspacePrompt(
                currentPrompt = prompt,
                priorMessages = conversationHistory.unseenBy(decodedConv),
                guestWorkspacePath = guestWorkdir,
                projectKind = projectKind,
                resumed = decodedConv != null,
            )

            val cliArgs = buildList {
                add(launchCfg.executable)
                addAll(listOf("-p", "--input-format", "stream-json", "--output-format", "stream-json"))
                addAll(listOf("--include-partial-messages", "--verbose"))
                addAll(listOf("--model", launchCfg.environment["ANTHROPIC_MODEL"] ?: provider.model))
                addAll(listOf("--max-turns", "25"))
                if (decodedConv != null) {
                    addAll(listOf("--resume", decodedConv.id))
                } else {
                    addAll(listOf("--session-id", sessionConvId))
                }
            }
            val proc = runtimeInstaller.process(
                rt.chrootExec,
                rt.rootfs,
                wsDir,
                launchCfg.environment + ("NEXTCRAFT_SESSION_ID" to sessionId),
                cliArgs,
                guestWorkspacePath = guestWorkdir,
                claudeMcpServers = runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull(),
            )
            runningProcess = proc
            runCatching {
                proc.outputStream.write(encodeStreamJsonUserTurn(fullPrompt))
                proc.outputStream.flush()
                liveStdinStream = proc.outputStream
            }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            if (stopRequestedByUser) proc.destroy()

            coroutineScope {
                val approvalJob = launch { pollPermissionRequestsLoop(sessionId) }
                var lastDiagText = ""
                val nativeProc = proc as? NativeSpawnProcess ?: error("Unsupported Android runtime process")
                sessionLogFile = nativeProc.outputFile

                suspend fun handleStreamLine(rawLine: String) {
                    val line = rawLine.trimEnd('\r')
                    if (line.isBlank()) return
                    steerFollowUpDeadlineMs = 0L
                    ProviderRuntimeErrorDetector.detect(line)?.let { fatalMsg ->
                        proc.destroyForcibly()
                        throw ProviderSessionException(fatalMsg)
                    }
                    if (line.endsWith("... [truncated]") && line.take(200).contains("\"type\":\"result\"")) {
                        if (!line.take(400).contains("\"is_error\":true")) sawResultFrame = true
                        finishTurnOrAwaitSteer()
                        return
                    }
                    if (!parseAndDispatchClaudeLine(sessionId, line)) {
                        lastDiagText = scrubSensitiveDisplay(line.takeLast(500))
                    }
                }

                InotifyLogTailer(nativeProc.outputFile).use { tailer ->
                    while (proc.isAlive || tailer.hasUnreadBytes()) {
                        for (line in tailer.readLines(timeoutMs = 250L)) {
                            handleStreamLine(line)
                        }
                        val deadline = steerFollowUpDeadlineMs
                        if (deadline > 0L && SystemClock.elapsedRealtime() > deadline) {
                            steerFollowUpDeadlineMs = 0L
                            closeLiveStdin()
                            shutdownProcessGracefully()
                        }
                    }
                    for (line in tailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                        handleStreamLine(line)
                    }
                }

                val exitCode = proc.waitFor()
                approvalJob.cancelAndJoin()
                awaitingApprovals.values.filter { it.request.sessionId == sessionId }.forEach { entry ->
                    runCatching { entry.decisionFile.writeText("deny") }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    awaitingApprovals.remove(entry.request.approvalId)
                    sharedEvents.emit(RuntimeEvent.ToolRejected(sessionId, entry.request.approvalId))
                }

                val diffPaths = workspaceCheckpoints.changedFiles(wsDir, preSnapshot)
                if (diffPaths.isNotEmpty()) {
                    workspaceCheckpoints.saveChangedPaths(projectId, diffPaths)
                    sharedEvents.emit(RuntimeEvent.FilesChanged(sessionId, loadPendingChanges(projectId)))
                } else if (workspaceCheckpoints.readChangedPaths(projectId).isEmpty()) {
                    acceptLastChanges(projectId)
                }

                if (exitCode == 0 || (sawResultFrame && !stopRequestedByUser)) {
                    saveConversation(SavedConversation(sessionConvId, conversationHistory.size + 1, scopeTag).encode())
                    dispatchSessionCompletedOnce(sessionId)
                    fgNotifier.finishRes(true, projectSlug, R.string.notif_agent_finished_in, "Claude Code", projectSlug)
                } else {
                    if (stopRequestedByUser) throw ProviderSessionException("Stopped by user")
                    if (isResumedSession && lastDiagText.contains("No conversation found", ignoreCase = true)) {
                        saveConversation(null)
                        throw ProviderSessionException(SESSION_LOST_MESSAGE)
                    }
                    error(lastDiagText.ifBlank { "Claude Code stopped with exit code $exitCode" })
                }
            }
        }.onFailure { err ->
            Log.e("ClaudeBridge", "Session failed: ${err::class.simpleName}")
            runningProcess?.let { proc -> runCatching { if (proc.isAlive) proc.destroyForcibly() } }
            awaitingApprovals.values.filter { it.request.sessionId == sessionId }.forEach { entry ->
                runCatching { entry.decisionFile.writeText("deny") }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                awaitingApprovals.remove(entry.request.approvalId)
                sharedEvents.emit(RuntimeEvent.ToolRejected(sessionId, entry.request.approvalId))
            }
            val friendlyMsg = mapFriendlySessionError(err)
            dispatchSessionFailedOnce(sessionId, friendlyMsg)
            if (stopRequestedByUser) {
                fgNotifier.cancel()
            } else {
                fgNotifier.finish(completed = false, projectName = projectSlug, detail = friendlyMsg)
            }
        }

        runCatching { openAiBridge?.close() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        runCatching { openRouterBridge?.close() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        runCatching { sessionLogFile?.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        runningProcess = null
        currentSessionId = null
        liveSessions.remove(sessionId)
        RuntimeTaskController.release(cancelCallback)
        sessionId
    }

    private fun canUseGuestClaudeLogin(provider: ProviderProfile): Boolean {
        if (provider.kind != ProviderKind.CLAUDE) return false
        val keyStore = ApiKeyVault(context)
        val hasSavedKey = listOf(ProviderKind.CLAUDE, ProviderKind.ANTHROPIC).any { !keyStore.get(it.name).isNullOrBlank() }
        return !hasSavedKey && runtimeInstaller.hasClaudeLoginCredential()
    }

    suspend fun hello(provider: ProviderProfile, timeoutMillis: Long = 90_000L): String = withContext(Dispatchers.IO) {
        val guestOauth = canUseGuestClaudeLogin(provider)
        val resolvedSecret = if (guestOauth) "" else secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        check(resolvedSecret.isNotBlank() || guestOauth) { "Not signed in to Claude." }
        val rt = runtimeInstaller.installedRuntime()
        val probeWorkspace = File(context.filesDir, "runtime-probes/claude-check").apply { mkdirs() }
        val logOut = File(context.cacheDir, "claude-hello-${System.nanoTime()}.log")
        val launchCfg = RuntimeLaunchConfigBuilder.build(
            provider = provider,
            authToken = resolvedSecret,
            useStoredLogin = guestOauth,
            claudeEffort = claudeEffort(),
        )
        val cmdArgs = listOf(
            launchCfg.executable,
            "-p", "Reply with exactly: ok",
            "--output-format", "json",
            "--max-turns", "1",
            "--model", launchCfg.environment["ANTHROPIC_MODEL"] ?: provider.model,
        )
        val proc = runtimeInstaller.process(
            rt.chrootExec,
            rt.rootfs,
            probeWorkspace,
            launchCfg.environment,
            cmdArgs,
            guestWorkspacePath = "/workspace/claude-check",
            emulateHardLinks = false,
            outputFile = logOut,
        )
        try {
            runCatching { proc.outputStream.close() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            withTimeout(timeoutMillis) {
                while (proc.isAlive) delay(200)
            }
            val rawOutput = logOut.takeIf(File::exists)?.readText().orEmpty()
            val resultJson = rawOutput.lineSequence()
                .map(String::trim)
                .filter { it.startsWith("{") }
                .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                .lastOrNull { it.stringOrEmpty("type") == "result" }
            when {
                resultJson == null -> error(rawOutput.trim().takeLast(300).ifBlank { "Claude Code did not answer." })
                resultJson.optBoolean("is_error") -> error(resultJson.stringOrEmpty("result").ifBlank { resultJson.stringOrEmpty("subtype") })
                else -> resultJson.stringOrEmpty("result").trim().ifBlank { "ok" }
            }
        } catch (_: TimeoutCancellationException) {
            error("Claude Code did not answer in time. Check your connection and try again.")
        } finally {
            runCatching { proc.destroy() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { if (proc.isAlive) proc.destroyForcibly() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { logOut.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { probeWorkspace.listFiles()?.forEach { it.deleteRecursively() } }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = withContext(Dispatchers.IO) {
        val entry = awaitingApprovals.remove(request.approvalId) ?: return@withContext
        entry.decisionFile.writeText(if (approved) "allow" else "deny")
        sharedEvents.emit(
            if (approved) RuntimeEvent.ToolApproved(request.sessionId, request.approvalId)
            else RuntimeEvent.ToolRejected(request.sessionId, request.approvalId),
        )
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (currentSessionId == sessionId) {
            stopRequestedByUser = true
            runningProcess?.destroy()
            delay(500)
            if (runningProcess?.isAlive == true) runningProcess?.destroyForcibly()
            dispatchSessionFailedOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        currentSessionId?.let { stopSession(it) }
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.revertLatestTurn(projectId, wsDir).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) = withContext(Dispatchers.IO) {
        workspaceCheckpoints.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        val paths = workspaceCheckpoints.readChangedPaths(projectId).filterNot(workspaceCheckpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else workspaceCheckpoints.buildChangeDetails(projectId, wsDir, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.revertFile(projectId, wsDir, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val wsDir = workspaceCheckpoints.ensureWorkspace(projectId)
        workspaceCheckpoints.acceptFile(projectId, wsDir, path)
    }

    private suspend fun pollPermissionRequestsLoop(sessionId: String) {
        val bridgeFolder = File(context.filesDir, "runtime-bridge")
        val seenIds = HashSet<String>()
        while (kotlin.coroutines.coroutineContext.isActive) {
            expirePendingPermissions(sessionId)
            val prefix = requestPrefix(sessionId)
            bridgeFolder.listFiles { f -> f.name.endsWith(".request") && f.name.startsWith(prefix) }
                .orEmpty()
                .forEach { reqFile ->
                    val approvalId = reqFile.name.removeSuffix(".request")
                    if (!seenIds.add(approvalId)) {
                        runCatching { reqFile.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                        return@forEach
                    }
                    processPermissionRequestFile(sessionId, reqFile)
                }
            delay(50)
        }
    }

    private fun resolveResponseFile(requestFile: File, approvalId: String): File {
        val parentDir = requestFile.parentFile ?: File(".")
        val respFolder = File(parentDir.parentFile, "${parentDir.name}-responses").apply { mkdirs() }
        val expiryThreshold = System.currentTimeMillis() - STALE_DECISION_TTL_MS
        respFolder.listFiles { f -> f.name.endsWith(".response") && f.lastModified() < expiryThreshold }
            .orEmpty()
            .forEach { runCatching { it.delete() } }
        return File(respFolder, "$approvalId.response")
    }

    internal suspend fun expirePendingPermissions(
        sessionId: String,
        nowElapsedRealtime: Long = SystemClock.elapsedRealtime(),
        timeoutMs: Long = INTERACTIVE_APPROVAL_TIMEOUT_MS,
    ) {
        awaitingApprovals.values
            .filter { it.request.sessionId == sessionId && nowElapsedRealtime - it.createdAtElapsedMs >= timeoutMs }
            .forEach { expired ->
                if (awaitingApprovals.remove(expired.request.approvalId, expired)) {
                    runCatching { expired.decisionFile.writeText("deny") }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    sharedEvents.emit(RuntimeEvent.ToolRejected(sessionId, expired.request.approvalId))
                    sharedEvents.emit(RuntimeEvent.PermissionDenied(sessionId, expired.request.toolName, "no answer was given in time"))
                }
            }
    }

    internal suspend fun processPermissionRequestFile(sessionId: String, file: File) {
        val approvalId = file.name.removeSuffix(".request")
        runCatching {
            val payload = JSONObject(file.readText())
            val toolName = payload.stringOrEmpty("tool_name", "Tool")
            val toolInput = payload.optJSONObject("tool_input") ?: JSONObject()
            val cmdText = toolInput.stringOrEmpty("command").ifBlank { null }
            val targetPaths = listOf("file_path", "path", "notebook_path")
                .mapNotNull { k -> toolInput.stringOrEmpty(k).takeIf(String::isNotBlank) }
            val summary = toolInput.stringOrEmpty("description")
                .ifBlank { cmdText.orEmpty() }
                .ifBlank { "$toolName running in project" }

            val riskLevel = classifyToolRisk(toolName, cmdText, targetPaths)
            val decisionTarget = resolveResponseFile(file, approvalId)
            if (riskLevel == RiskLevel.HIGH && !autoApproveAllTools()) {
                if (interactiveHighRiskApprovals()) {
                    val reqObj = ToolRequest(
                        approvalId = approvalId,
                        sessionId = sessionId,
                        toolName = toolName,
                        explanation = scrubSensitiveDisplay(summary),
                        affectedPaths = targetPaths,
                        commandPreview = cmdText,
                        risk = riskLevel,
                    )
                    awaitingApprovals[approvalId] = AwaitingApprovalEntry(reqObj, decisionTarget)
                    runCatching { file.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    sharedEvents.emit(RuntimeEvent.ToolRequested(sessionId, reqObj))
                } else {
                    decisionTarget.writeText("deny")
                    runCatching { file.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
                    sharedEvents.emit(
                        RuntimeEvent.PermissionDenied(
                            sessionId,
                            toolName,
                            scrubSensitiveDisplay("high-risk request blocked: $summary"),
                        ),
                    )
                }
            } else {
                decisionTarget.writeText("allow")
                runCatching { file.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            }
        }.onFailure {
            val fallbackDecision = if (autoApproveAllTools()) "allow" else "deny"
            runCatching { resolveResponseFile(file, approvalId).writeText(fallbackDecision) }
                .onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
            runCatching { file.delete() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }
    }

    private suspend fun parseAndDispatchClaudeLine(sessionId: String, line: String): Boolean {
        val json = runCatching { JSONObject(line) }.getOrNull() ?: return false
        handleClaudeEventNode(sessionId, json)
        return true
    }

    private suspend fun handleClaudeEventNode(sessionId: String, node: JSONObject) {
        when (node.stringOrEmpty("type")) {
            "stream_event" -> node.optJSONObject("event")?.let { handleClaudeEventNode(sessionId, it) }
            "system" -> when (node.stringOrEmpty("subtype")) {
                "thinking_tokens" -> publishReasoningTokenCount(sessionId, node.optInt("estimated_tokens"))
                "permission_denied" -> sharedEvents.emit(
                    RuntimeEvent.RuntimeLog(
                        sessionId,
                        "Permission denied",
                        scrubSensitiveDisplay(node.stringOrEmpty("decision_reason").ifBlank { node.stringOrEmpty("message") }),
                    ),
                )
            }
            "content_block_start" -> {
                val block = node.optJSONObject("content_block")
                when (block?.stringOrEmpty("type")) {
                    "thinking" -> {
                        reasoningBlockSeq += 1
                        reasoningTextBuffer.clear()
                        publishReasoningSummary(sessionId, "", startsNewBlock = true)
                        block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let { initial ->
                            reasoningTextBuffer.append(initial)
                            publishReasoningSummary(sessionId, initial, force = true)
                        }
                    }
                    "text" -> assistantTextBuffer.clear()
                }
            }
            "content_block_delta" -> {
                val delta = node.optJSONObject("delta")
                when (delta?.stringOrEmpty("type")) {
                    "thinking_delta" -> delta.stringOrEmpty("thinking").takeIf(String::isNotEmpty)?.let { chunk ->
                        reasoningTextBuffer.append(chunk)
                        publishReasoningSummary(sessionId, reasoningTextBuffer.toString())
                    }
                    "text_delta", "" -> delta.stringOrEmpty("text").takeIf(String::isNotEmpty)?.let { chunk ->
                        assistantTextBuffer.append(chunk)
                        sharedEvents.emit(RuntimeEvent.AssistantDelta(sessionId, chunk))
                    }
                }
            }
            "content_block_stop" -> {
                if (reasoningTextBuffer.isNotBlank()) {
                    publishReasoningSummary(sessionId, reasoningTextBuffer.toString(), force = true, isFinal = true)
                }
            }
            "assistant" -> {
                val msgObj = node.optJSONObject("message") ?: return
                val blocks = msgObj.optJSONArray("content") ?: return
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    when (block.stringOrEmpty("type")) {
                        "text" -> if (assistantTextBuffer.isEmpty()) {
                            block.stringOrEmpty("text").takeIf(String::isNotBlank)?.let { txt ->
                                sharedEvents.emit(RuntimeEvent.AssistantDelta(sessionId, txt))
                            }
                        }
                        "thinking" -> block.stringOrEmpty("thinking").takeIf(String::isNotBlank)?.let { thought ->
                            if (reasoningBlockSeq == 0L || reasoningTextBuffer.toString() != thought) {
                                reasoningBlockSeq += 1
                                reasoningTextBuffer.clear()
                                reasoningTextBuffer.append(thought)
                                publishReasoningSummary(
                                    sessionId = sessionId,
                                    text = thought,
                                    force = true,
                                    startsNewBlock = true,
                                    isFinal = true,
                                )
                            }
                        }
                        "tool_use" -> publishToolStarted(sessionId, block)
                    }
                }
                assistantTextBuffer.clear()
                if (msgObj.stringOrEmpty("stop_reason") == "end_turn") {
                    sawResultFrame = true
                }
            }
            "user" -> {
                val blocks = node.optJSONObject("message")?.optJSONArray("content") ?: return
                for (i in 0 until blocks.length()) {
                    val block = blocks.optJSONObject(i) ?: continue
                    if (block.stringOrEmpty("type") == "tool_result") {
                        val callId = block.stringOrEmpty("tool_use_id")
                        val toolLabel = activeToolLabels.remove(callId) ?: "Tool"
                        val contentArr = block.optJSONArray("content")
                        val flattened = if (contentArr != null) {
                            buildString {
                                for (j in 0 until contentArr.length()) {
                                    val elem = contentArr.optJSONObject(j)
                                    val piece = elem?.stringOrEmpty("text")?.takeIf(String::isNotBlank)
                                        ?: contentArr.optString(j).takeIf(String::isNotBlank)
                                    if (piece != null) {
                                        if (isNotEmpty()) append('\n')
                                        append(piece)
                                    }
                                }
                            }
                        } else {
                            block.stringOrEmpty("content")
                        }
                        val finalOut = flattened.ifBlank {
                            if (block.optBoolean("is_error")) "Tool failed" else "Completed successfully"
                        }
                        sharedEvents.emit(RuntimeEvent.ToolCompleted(sessionId, toolLabel, scrubSensitiveDisplay(finalOut)))
                        if (block.optBoolean("is_error") && isPermissionDenialText(finalOut)) {
                            sharedEvents.emit(RuntimeEvent.PermissionDenied(sessionId, toolLabel, scrubSensitiveDisplay(finalOut)))
                        }
                    }
                }
            }
            "result" -> {
                if (node.optBoolean("is_error")) {
                    val errText = node.stringOrEmpty("result").ifBlank { "Claude Code reported an error" }
                    if (isPermissionDenialText(errText)) {
                        sharedEvents.emit(RuntimeEvent.PermissionDenied(sessionId, "Tool", scrubSensitiveDisplay(errText)))
                    } else {
                        throw IllegalStateException(errText)
                    }
                }
                sawResultFrame = true
                finishTurnOrAwaitSteer()
            }
        }
    }

    private fun finishTurnOrAwaitSteer() {
        synchronized(this) {
            if (queuedSteerCount > 0) {
                queuedSteerCount = 0
                steerFollowUpDeadlineMs = SystemClock.elapsedRealtime() + STEER_IDLE_TIMEOUT_MS
                return
            }
        }
        closeLiveStdin()
        shutdownProcessGracefully()
    }

    private fun closeLiveStdin() {
        synchronized(this) {
            val stream = liveStdinStream ?: return
            liveStdinStream = null
            runCatching { stream.close() }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }
    }

    fun steer(text: String): Boolean = synchronized(this) {
        val stream = liveStdinStream ?: return false
        if (runningProcess?.isAlive != true || text.isBlank()) return false
        val remindedText = "$text\n\n[Reminder: ${ANTI_HALLUCINATION_RULES.first()} ${ANTI_HALLUCINATION_RULES[1]} ${ANTI_HALLUCINATION_RULES[3]}]"
        runCatching {
            stream.write(encodeStreamJsonUserTurn(remindedText))
            stream.flush()
        }.onFailure { return false }
        queuedSteerCount++
        steerFollowUpDeadlineMs = 0L
        true
    }

    private suspend fun publishReasoningSummary(
        sessionId: String,
        text: String,
        force: Boolean = false,
        startsNewBlock: Boolean = false,
        isFinal: Boolean = false,
    ) {
        val cleanSummary = scrubSensitiveDisplay(text).trim().take(2_000)
        if (cleanSummary.isBlank() && !startsNewBlock) return
        val nowMs = SystemClock.elapsedRealtime()
        if (startsNewBlock || isFinal || force || nowMs - prevReasoningSummaryEmitMs >= 150L) {
            prevReasoningSummaryEmitMs = nowMs
            sharedEvents.emit(
                RuntimeEvent.ReasoningSummary(
                    sessionId = sessionId,
                    summary = cleanSummary,
                    blockId = reasoningBlockSeq,
                    startsNewBlock = startsNewBlock,
                    isFinal = isFinal,
                ),
            )
            updateForegroundStatus("Thinking…")
        }
    }

    private suspend fun publishReasoningTokenCount(sessionId: String, tokens: Int) {
        if (tokens <= 0) return
        val nowMs = SystemClock.elapsedRealtime()
        if (tokens - prevReasoningTokenCount >= 25 || nowMs - prevReasoningTokenEmitMs >= 500L) {
            prevReasoningTokenCount = tokens
            prevReasoningTokenEmitMs = nowMs
            sharedEvents.emit(RuntimeEvent.ReasoningProgress(sessionId, tokens))
            updateForegroundStatus("Thinking…")
        }
    }

    private suspend fun publishToolStarted(sessionId: String, block: JSONObject) {
        val callId = block.stringOrEmpty("id")
        if (callId.isNotBlank() && !processedToolCallIds.add(callId)) return
        val toolName = block.stringOrEmpty("name", "Tool")
        if (callId.isNotBlank()) activeToolLabels[callId] = toolName
        val inputObj = block.optJSONObject("input") ?: JSONObject()
        val summary = when (toolName) {
            "Bash" -> inputObj.stringOrEmpty("command").ifBlank { inputObj.stringOrEmpty("description") }
            "Write", "Edit", "Read", "NotebookEdit" -> inputObj.stringOrEmpty("file_path").ifBlank { inputObj.stringOrEmpty("notebook_path") }
            "Glob" -> inputObj.stringOrEmpty("pattern")
            "Grep" -> {
                val pat = inputObj.stringOrEmpty("pattern")
                val scope = inputObj.stringOrEmpty("path")
                if (scope.isNotBlank()) "$pat in $scope" else pat
            }
            else -> inputObj.stringOrEmpty("description").ifBlank { "Running $toolName" }
        }
        sharedEvents.emit(RuntimeEvent.ToolStarted(sessionId, toolName, scrubSensitiveDisplay(summary.ifBlank { "Running $toolName" })))
        val shortSummary = summary.replace(Regex("\\s+"), " ").trim().take(80).ifBlank { toolName }
        updateForegroundStatus("Running $toolName: $shortSummary")
    }

    private suspend fun dispatchSessionCompletedOnce(sessionId: String) {
        if (completedSessionIds.add(sessionId)) {
            sharedEvents.emit(RuntimeEvent.SessionCompleted(sessionId))
            val slug = currentSlug?.takeIf(String::isNotBlank)
            if (slug != null) {
                fgNotifier.finishRes(true, slug, R.string.notif_agent_finished_in, "Claude Code", slug)
            } else {
                fgNotifier.finishRes(true, "Project", R.string.notif_agent_finished, "Claude Code")
            }
        }
    }

    private suspend fun dispatchSessionFailedOnce(sessionId: String, reason: String) {
        if (completedSessionIds.add(sessionId)) {
            sharedEvents.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (stopRequestedByUser) {
                fgNotifier.cancel()
            } else {
                fgNotifier.finish(completed = false, projectName = currentSlug ?: "Project", detail = reason)
            }
        }
    }

    private fun scrubSensitiveDisplay(raw: String): String = raw
        .replace(Regex("sk-[A-Za-z0-9_-]{8,}"), "sk-••••")
        .replace(Regex("(gh[pousr]_[A-Za-z0-9]{20,}|github_pat_[A-Za-z0-9_]{20,})"), "gh_••••")
        .replace(
            Regex("(?i)(authorization|api[_-]?key|(?:access|refresh|auth)[_-]?token|secret|password)[\"']?\\s*[:=]\\s*[\"']?(?:(?:bearer|basic|token)\\s+)?[^\\s,}\"']+"),
            "$1: ••••",
        )
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(600)

    private fun assembleWorkspacePrompt(
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
        androidStackInstalled = runtimeInstaller.isStackInstalled(DevStack.ANDROID),
        maxHistoryChars = HISTORY_CHARACTER_BUDGET,
        resumed = resumed,
    )

    fun configureProjectRoot(projectId: String, rootPath: String) {
        workspaceCheckpoints.configureProjectRoot(projectId, rootPath)
    }

    private fun mapFriendlySessionError(err: Throwable): String {
        val msg = err.message.orEmpty()
        return when {
            err is ProviderSessionException -> msg
            msg.contains("user not found", ignoreCase = true) -> "User not found. Check the API key and provider account."
            msg.contains("checksum", ignoreCase = true) -> "Runtime verification failed. Nothing unverified was executed."
            msg.contains("HTTP 401", ignoreCase = true) || msg.contains("authentication", ignoreCase = true) ->
                "The provider rejected the saved API key."
            msg.isBlank() -> "The real Claude Code runtime could not start."
            else -> msg.take(500)
        }
    }

    private fun updateForegroundStatus(statusText: String) {
        if (currentSessionId == null) return
        fgNotifier.progress(currentSlug, statusText)
    }

    private fun encodeStreamJsonUserTurn(contentText: String): ByteArray {
        val textBlock = JSONObject().put("type", "text").put("text", contentText)
        val msgNode = JSONObject().put("role", "user").put("content", JSONArray().put(textBlock))
        val envelope = JSONObject().put("type", "user").put("message", msgNode)
        return ("$envelope\n").toByteArray(Charsets.UTF_8)
    }

    private fun shutdownProcessGracefully() {
        val proc = runningProcess ?: return
        Thread {
            runCatching {
                Thread.sleep(500)
                if (proc.isAlive) proc.destroy()
                Thread.sleep(3_000)
                if (proc.isAlive) proc.destroyForcibly()
            }.onFailure { ErrorLog.swallowed("ClaudeRuntimeBridge", it) }
        }.apply { isDaemon = true }.start()
    }

    private data class AwaitingApprovalEntry(
        val request: ToolRequest,
        val decisionFile: File,
        val createdAtElapsedMs: Long = SystemClock.elapsedRealtime(),
    )

    private class ProviderSessionException(message: String) : IllegalStateException(message)

    companion object {
        private val liveSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

        internal fun requestPrefix(sessionId: String): String = "${sessionId}_"

        internal fun isLiveSessionRequest(fileName: String): Boolean =
            liveSessions.any { fileName.startsWith(requestPrefix(it)) }

        private const val INTERACTIVE_APPROVAL_TIMEOUT_MS = 40_000L
        private const val STALE_DECISION_TTL_MS = 600_000L
        private const val HISTORY_CHARACTER_BUDGET = 160_000
        const val SESSION_LOST_MESSAGE = "The saved agent session was not found; continuing from the chat history."
    }
}

private val REFUSAL_TEXT_PATTERN = Regex(
    "permission.{0,60}denied|denied.{0,40}permission|not allowed|doesn't want to proceed|user rejected|blocked by|requires approval",
    RegexOption.IGNORE_CASE,
)

internal fun isPermissionDenialText(text: String): Boolean = REFUSAL_TEXT_PATTERN.containsMatchIn(text)

private val HIGH_RISK_SUBSTRINGS = listOf(
    "git push", "git reset", "git clean", "git checkout --", "sudo", "curl ", "wget ", "chmod ", "chown ", "mkfs",
    "dd if=", "/dev/block", "/dev/loop", "/dev/sd", "/dev/zero", "/dev/mem", "/dev/kmem", "/proc/", "umount",
    "| sh", "| bash", "find -exec", "find -delete", "pocketdev-bridge", "nextcraft-bridge", "pocket-bridge",
    "/opt/pocket", "/opt/nextcraft", ".claude", ".git-credentials",
    "android-shell", "android-screencap", "android-dumpsys", "nsenter",
    ".ssh/", "/.ssh", ".config/gh",
)

private val HARMLESS_REDIRECT_PATTERN =
    Regex("(?:[12]?>>?\\s*/dev/null|&>\\s*/dev/null|[12]>&[12]|<\\s*/dev/null)")

private val ALLOWLISTED_SHELL_BINARIES = setOf(
    "ls", "cat", "pwd", "echo", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg", "sort", "uniq", "diff", "cd",
    "true", "false", "test", "which", "type", "stat", "file", "tree", "mkdir", "touch", "date", "printf",
    "basename", "dirname", "realpath", "readlink", "cut", "tr", "awk", "sed", "find", "xargs", "jq", "du", "df",
    "env", "md5sum", "sha256sum", "cp", "mv", "gradle", "javac", "kotlinc",
)

private val DANGEROUS_CLI_FLAGS_PATTERN =
    Regex("""--pre(?:=.*)?|--hostname-bin(?:=.*)?|--compress-program(?:=.*)?|--output(?:=.*)?|-fprint0?|-fprintf|-fls""")
private val SHELL_VAR_ASSIGNMENT_PATTERN = Regex("[A-Za-z_][A-Za-z0-9_]*=\\S*")

private val AWK_TOKEN_PATTERN = Regex("\\bawk\\b")
private val AWK_DANGEROUS_CONSTRUCTS = Regex("""\|\s*\\?"|system\s*\(|getline|\|&|\bpopen\b|\bENVIRON\b""")
private val SED_TOKEN_PATTERN = Regex("\\bsed\\b")
private val SED_DANGEROUS_CONSTRUCTS = Regex("""(?:^|[;{}'"\s/0-9$,!])(?:e(?:['";}\s]|$)|[wWrR]\s)""")
private val JQ_TOKEN_PATTERN = Regex("\\bjq\\b")
private val JQ_DANGEROUS_CONSTRUCTS = Regex("""\benv\b|[$]ENV|\binput_filename\b""")
private val SHELL_EXPANSION_PATTERN = Regex("""\$[A-Za-z_{@*#?0-9!-]""")

private fun hasInlineScriptEscape(cmd: String): Boolean =
    (JQ_TOKEN_PATTERN.containsMatchIn(cmd) && JQ_DANGEROUS_CONSTRUCTS.containsMatchIn(cmd)) ||
        (AWK_TOKEN_PATTERN.containsMatchIn(cmd) && AWK_DANGEROUS_CONSTRUCTS.containsMatchIn(cmd)) ||
        (SED_TOKEN_PATTERN.containsMatchIn(cmd) && SED_DANGEROUS_CONSTRUCTS.containsMatchIn(cmd))

private fun String.extractShortOptionLetters(): String =
    if (startsWith("-") && !startsWith("--")) drop(1).takeWhile(Char::isLetter) else ""

private fun commandModifiesFilesystem(binaryName: String, tokens: List<String>): Boolean =
    binaryName in setOf("cp", "mv", "mkdir", "touch", "uniq") ||
        (binaryName == "sed" && tokens.any { it.startsWith("--in-place") || it.trim('\'', '"').extractShortOptionLetters().contains('i') })

private fun pointsOutsideWorkspace(rawArg: String): Boolean {
    val cleanArg = rawArg.trim('\'', '"')
    if (cleanArg.startsWith("-") || cleanArg.isEmpty()) return false
    if (cleanArg == "~" || cleanArg.startsWith("~/") || isProtectedSystemPath(cleanArg)) return true
    return cleanArg.startsWith("/") && !cleanArg.startsWith("/workspace/") && !cleanArg.startsWith("/tmp/")
}

private val ALLOWLISTED_GIT_VERBS = setOf(
    "status", "diff", "log", "show", "add", "commit", "branch", "rev-parse", "ls-files", "ls-tree", "blame",
    "remote", "fetch", "pull", "stash", "restore", "switch", "tag",
)

private val PROTECTED_SYSTEM_PREFIXES = listOf(
    "/opt/", "/etc/", "/proc/", "/sys/", "/dev/", "/usr/", "/bin", "/sbin", "/lib", "/var/",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge",
)

private val PROTECTED_PATH_FRAGMENTS = listOf(
    "..", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook", "/.git/",
)

private fun isProtectedSystemPath(path: String): Boolean =
    PROTECTED_SYSTEM_PREFIXES.any(path::startsWith) || PROTECTED_PATH_FRAGMENTS.any(path::contains) || path.startsWith(".git/")

private val SECRET_FILE_PATHS = listOf(
    "/proc/self/environ", "/proc/1/environ", "/proc/thread-self/environ",
    "/root/.claude/.credentials.json", "~/.claude/.credentials.json", "/root/.claude.json", "~/.claude.json",
    "/root/.ssh/id_ed25519", "~/.ssh/id_ed25519", "/root/.ssh/id_rsa", "~/.ssh/id_rsa",
    "/root/.git-credentials", "~/.git-credentials", "/root/.config/gh/hosts.yml", "~/.config/gh/hosts.yml",
)

private fun matchesSecretFileViaGlob(command: String): Boolean {
    val tokens = command.split(Regex("\\s+")).map { it.trim('\'', '"') }
    return tokens.any { token ->
        if (token.none { it == '*' || it == '?' || it == '[' }) return@any false
        val globRegex = runCatching {
            Regex(
                buildString {
                    var idx = 0
                    while (idx < token.length) {
                        when (val c = token[idx]) {
                            '*' -> append("[^/]*")
                            '?' -> append("[^/]")
                            '[' -> {
                                val closeIdx = token.indexOf(']', idx + 1)
                                if (closeIdx > idx) {
                                    append(token.substring(idx, closeIdx + 1))
                                    idx = closeIdx
                                } else {
                                    append("\\[")
                                }
                            }
                            else -> append(Regex.escape(c.toString()))
                        }
                        idx++
                    }
                },
            )
        }.getOrNull() ?: return@any true
        SECRET_FILE_PATHS.any(globRegex::matches)
    }
}

private val CREDENTIAL_STORE_FRAGMENTS = listOf(
    "/proc/", "/.claude", "/.ssh", ".git-credentials", "/.config/gh", "permission-hook",
    "/pocketdev-bridge", "/nextcraft-bridge", "/pocket-bridge", "/.gradle/gradle.properties",
)

private fun touchesCredentialStore(path: String): Boolean = CREDENTIAL_STORE_FRAGMENTS.any(path::contains)

private fun neutralizeQuotedSeparators(cmd: String): String {
    val sb = StringBuilder(cmd.length)
    var singleQuoted = false
    var doubleQuoted = false
    var backslashActive = false
    for (ch in cmd) {
        if (backslashActive) {
            sb.append(if (ch == '|' || ch == '&' || ch == ';') '_' else ch)
            backslashActive = false
            continue
        }
        if (ch == '\\' && !singleQuoted) {
            backslashActive = true
            sb.append(ch)
            continue
        }
        if (ch == '\'' && !doubleQuoted) {
            singleQuoted = !singleQuoted
            sb.append(ch)
            continue
        }
        if (ch == '"' && !singleQuoted) {
            doubleQuoted = !doubleQuoted
            sb.append(ch)
            continue
        }
        if ((singleQuoted || doubleQuoted) && (ch == '|' || ch == '&' || ch == ';')) {
            sb.append('_')
        } else {
            sb.append(ch)
        }
    }
    return sb.toString()
}

private val WRAPPER_SHORT_VALUE_FLAGS = setOf("-u", "-C", "-S", "-I", "-i", "-E", "-e", "-d", "-L", "-l", "-n", "-P", "-s", "-a")
private val WRAPPER_LONG_VALUE_FLAGS = setOf(
    "unset", "chdir", "split-string", "replace", "eof", "delimiter", "max-lines", "max-args", "max-procs", "max-chars", "arg-file",
)
private val SYSTEM_BIN_PREFIXES = listOf("/usr/bin/", "/bin/", "/usr/local/bin/")

private fun isAllowlistedPipelineSegment(segment: String): Boolean {
    val tokens = segment.trim().split(Regex("\\s+")).filter(String::isNotEmpty)
    if (tokens.firstOrNull()?.matches(SHELL_VAR_ASSIGNMENT_PATTERN) == true) return false
    val rawBinary = tokens.firstOrNull()?.trim('\'', '"') ?: return false
    if ('/' in rawBinary && SYSTEM_BIN_PREFIXES.none(rawBinary::startsWith)) return false
    if (".." in rawBinary) return false
    val binaryName = rawBinary.substringAfterLast('/')
    if (binaryName == "env" || binaryName == "xargs") {
        if (tokens.drop(1).any { it.matches(SHELL_VAR_ASSIGNMENT_PATTERN) }) return false
        val flagTokens = tokens.drop(1).takeWhile { it.startsWith("-") }
        val hasValueConsumingFlag = flagTokens.any {
            it in WRAPPER_SHORT_VALUE_FLAGS ||
                (it.startsWith("--") && '=' !in it && it.removePrefix("--") in WRAPPER_LONG_VALUE_FLAGS)
        }
        if (hasValueConsumingFlag) return false
        val innerTokens = tokens.drop(1).dropWhile { it.startsWith("-") }
        if (innerTokens.isEmpty()) return binaryName == "xargs"
        return isAllowlistedPipelineSegment(innerTokens.joinToString(" "))
    }
    val cleanArgs = tokens.drop(1).map { it.trim('\'', '"') }
    if (cleanArgs.any(DANGEROUS_CLI_FLAGS_PATTERN::matches)) return false
    if ((binaryName == "sort" || binaryName == "tree") && cleanArgs.any { it.startsWith("--out") || it.extractShortOptionLetters().contains('o') }) {
        return false
    }
    if (binaryName == "date" && cleanArgs.any { it.extractShortOptionLetters().contains('s') || it.startsWith("--set") }) {
        return false
    }
    if (commandModifiesFilesystem(binaryName, tokens) && tokens.drop(1).any(::pointsOutsideWorkspace)) return false
    if (binaryName == "git") return tokens.getOrNull(1)?.trim('\'', '"') in ALLOWLISTED_GIT_VERBS
    if (binaryName == "find" && tokens.any { it.trim('\'', '"') in setOf("-exec", "-execdir", "-delete", "-ok", "-okdir") }) return false
    return binaryName in ALLOWLISTED_SHELL_BINARIES
}

internal fun classifyToolRisk(tool: String, command: String?, paths: List<String> = emptyList()): RiskLevel {
    val normalizedPreview = "${tool.lowercase()} ${command.orEmpty().lowercase()}"
    if (HIGH_RISK_SUBSTRINGS.any(normalizedPreview::contains)) return RiskLevel.HIGH
    if (tool == "Bash") {
        val strippedQuotes = normalizedPreview.replace(Regex("""\$?['"\\]"""), "")
        if (strippedQuotes != normalizedPreview && HIGH_RISK_SUBSTRINGS.any(strippedQuotes::contains)) return RiskLevel.HIGH
        if (matchesSecretFileViaGlob(command.orEmpty())) return RiskLevel.HIGH
    }
    if (tool in setOf("Write", "Edit", "NotebookEdit") && paths.any(::isProtectedSystemPath)) return RiskLevel.HIGH
    if (tool != "Bash" && paths.any(::touchesCredentialStore)) return RiskLevel.HIGH
    return when (tool) {
        "Bash" -> {
            val cleanedCmd = command.orEmpty().replace(HARMLESS_REDIRECT_PATTERN, " ")
            val containsForbiddenSyntax = listOf("`", "$(", "<(", ">", "<", "\n", "\r").any(cleanedCmd::contains) ||
                SHELL_EXPANSION_PATTERN.containsMatchIn(cleanedCmd) ||
                hasInlineScriptEscape(cleanedCmd)
            val subCommands = neutralizeQuotedSeparators(cleanedCmd)
                .split(Regex("&&|\\|\\||[;|&]"))
                .filter(String::isNotBlank)
            if (cleanedCmd.isBlank() || containsForbiddenSyntax || subCommands.isEmpty() || !subCommands.all(::isAllowlistedPipelineSegment)) {
                RiskLevel.HIGH
            } else {
                RiskLevel.REVIEW
            }
        }
        "Write", "Edit", "NotebookEdit" -> RiskLevel.REVIEW
        else -> RiskLevel.SAFE
    }
}

private const val STEER_IDLE_TIMEOUT_MS = 20_000L
