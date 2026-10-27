package com.nextcraft.forge.runtime

import android.content.Context
import android.os.SystemClock
import android.system.Os
import android.util.Log
import androidx.annotation.StringRes
import com.nextcraft.forge.R
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.stringOrEmpty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DshRuntimeBridge(
    private val context: Context,
    private val secretFor: (ProviderProfile) -> String?,
    private val rotateSecretFor: (ProviderProfile) -> String? = { prof ->
        ApiKeyVault(context).rotateNext(prof.kind.name)
    },
) : RuntimeBridge {
    private val rtInstaller = RuntimeInstaller(context)
    private val diffStore = WorkspaceCheckpoints(context.filesDir, context)
    private val streamBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = streamBus

    private val completedSessionIds = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var runningProc: Process? = null
    @Volatile private var currentSid: String? = null
    @Volatile private var cancelledByUser: Boolean = false
    @Volatile private var currentSlug: String? = null
    @Volatile private var prevReasoningEmitMs: Long = 0L

    private val fgNotifier = ForegroundSession(context, LOG_TAG)

    override suspend fun startSession(
        projectId: String,
        projectSlug: String,
        projectKind: ProjectKind,
        prompt: String,
        conversationHistory: List<ChatMessage>,
        provider: ProviderProfile,
    ): String = withContext(Dispatchers.IO + NonCancellable) {
        val sid = UUID.randomUUID().toString()
        completedSessionIds.remove(sid)
        currentSid = sid
        cancelledByUser = false
        currentSlug = projectSlug
        prevReasoningEmitMs = 0L

        fgNotifier.begin()
        streamBus.emit(RuntimeEvent.SessionStarted(sid))
        notifyProgress("Starting DeepSeek…")

        val resolvedSecret = secretFor(provider).orEmpty().ifBlank { localServerPlaceholderKey(provider) }
        if (resolvedSecret.isBlank()) {
            streamBus.emit(RuntimeEvent.SessionFailed(sid, "No API key is saved for ${provider.kind.title}."))
            currentSid = null
            fgNotifier.clearStart()
            return@withContext sid
        }
        if (provider.kind == ProviderKind.CLAUDE) {
            val msg = "Claude subscription login is not supported by DeepSeek. Pick a key-based provider in Settings."
            streamBus.emit(RuntimeEvent.SessionFailed(sid, msg))
            currentSid = null
            fgNotifier.clearStart()
            return@withContext sid
        }

        var routerProxy: OpenRouterRoutingGateway? = null
        var logCaptureFile: File? = null
        var isolatedHomeDir: File? = null
        var abortCallback: (() -> Unit)? = null

        runCatching {
            abortCallback = {
                cancelledByUser = true
                val proc = runningProc
                if (proc != null) {
                    Thread({
                        runCatching {
                            proc.destroy()
                            Thread.sleep(500L)
                            if (proc.isAlive) proc.destroyForcibly()
                        }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
                    }, "forge-dsh-abort").apply { isDaemon = true }.start()
                }
            }
            RuntimeTaskController.register("${RuntimeTaskController.AGENT}:$sid", abortCallback!!)
            fgNotifier.start(projectSlug)

            val runtimeEnv = rtInstaller.installedRuntime()
            check(rtInstaller.isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "DeepSeek is not installed. Open Settings → Coding agent to install it."
            }
            rtInstaller.ensureSettingsAndHooks()
            rtInstaller.ensureDshAndroidCompatibility()

            val projectDir = diffStore.ensureWorkspace(projectId)
            diffStore.createCheckpoint(projectId, projectDir)
            val preTurnSnapshot = diffStore.snapshot(projectDir)

            val initialRoute = DshRouteMapper.forProfile(provider)
            if (provider.kind == ProviderKind.LLM_ROUTER && provider.openRouterProviders.isNotEmpty()) {
                routerProxy = OpenRouterRoutingGateway(provider, resolvedSecret) { rotateSecretFor(provider) }.start()
            }
            val effectiveRoute = if (routerProxy != null && initialRoute.custom != null) {
                initialRoute.copy(custom = initialRoute.custom.copy(baseUrl = routerProxy!!.url))
            } else {
                initialRoute
            }

            val guestSessionHome = "$DSH_HOME_GUEST_PATH/sessions/$sid"
            isolatedHomeDir = provisionDshConfigFiles(runtimeEnv.rootfs, effectiveRoute, provider, guestSessionHome)
            val tokenForGuest = routerProxy?.sessionToken ?: resolvedSecret

            val envMap = linkedMapOf(
                "DSH_HOME" to guestSessionHome,
                "DSH_PERMISSION_MODE" to "danger-full-access",
                effectiveRoute.keyEnv to tokenForGuest,
            )
            if (effectiveRoute.keyEnv != FALLBACK_KEY_ENV) {
                envMap.remove(FALLBACK_KEY_ENV)
            }

            val guestWorkdir = "/workspace/$projectSlug"
            val fullPrompt = assemblePromptWithContext(prompt, conversationHistory.unseenBy(null), guestWorkdir, projectKind)
            val cliArgs = listOf("/usr/local/bin/dsh", "--profile", "sdk")

            val childProc = rtInstaller.process(
                runtimeEnv.chrootExec,
                runtimeEnv.rootfs,
                projectDir,
                envMap,
                cliArgs,
                guestWorkspacePath = guestWorkdir,
                emulateHardLinks = false,
            )
            runningProc = childProc
            logCaptureFile = (childProc as? NativeSpawnProcess)?.outputFile
            if (cancelledByUser) childProc.destroy()

            val activeModel = provider.model.ifBlank { effectiveRoute.defaultModel }
            val outcome = driveJsonRpcSession(
                proc = childProc,
                sid = sid,
                route = effectiveRoute,
                modelName = activeModel,
                guestCwd = guestWorkdir,
                promptText = fullPrompt,
            )
            val exitStatus = childProc.waitFor()

            val modifiedPaths = diffStore.changedFiles(projectDir, preTurnSnapshot)
            if (modifiedPaths.isNotEmpty()) {
                diffStore.saveChangedPaths(projectId, modifiedPaths)
                val visiblePaths = diffStore.readChangedPaths(projectId).filterNot(diffStore::isInternalRuntimePath)
                val diffItems = diffStore.buildChangeDetails(projectId, projectDir, visiblePaths)
                streamBus.emit(RuntimeEvent.FilesChanged(sid, diffItems))
            } else if (diffStore.readChangedPaths(projectId).isEmpty()) {
                acceptLastChanges(projectId)
            }

            if (outcome.completed && outcome.failure.isBlank() && !cancelledByUser) {
                publishCompletionOnce(sid)
                fgNotifier.finishRes(true, projectSlug, R.string.notif_agent_finished_in, "DeepSeek", projectSlug)
            } else {
                if (cancelledByUser) throw DshBridgeAbortedException("Stopped by user")
                error(outcome.failure.ifBlank { "DeepSeek stopped with exit code $exitStatus" })
            }
        }.onFailure { err ->
            Log.e(LOG_TAG, "Session failed: ${err::class.simpleName}")
            runningProc?.let { proc -> runCatching { if (proc.isAlive) proc.destroyForcibly() } }
            val readableMsg = mapReadableDshFailure(err)
            publishFailureOnce(sid, readableMsg)
            if (cancelledByUser) {
                fgNotifier.cancel()
            } else {
                fgNotifier.finish(completed = false, projectName = projectSlug, detail = readableMsg)
            }
        }

        runCatching { routerProxy?.close() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        runCatching { logCaptureFile?.delete() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        runCatching { isolatedHomeDir?.deleteRecursively() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        runningProc = null
        currentSid = null
        RuntimeTaskController.release(abortCallback)
        sid
    }

    private suspend fun driveJsonRpcSession(
        proc: Process,
        sid: String,
        route: DshRoute,
        modelName: String,
        guestCwd: String,
        promptText: String,
    ): DshTurnOutcome {
        val spawned = proc as? NativeSpawnProcess ?: error("Unsupported Android runtime process")
        val stdinWriter = proc.outputStream.bufferedWriter()
        val protocolParser = DshSdkProtocolParser(sid)

        var sentPrompt = false
        var observedRunning = false
        var turnSucceeded = false
        var observedOutput = false
        var sentShutdown = false
        var shutdownTimestamp = 0L
        var forceKilled = false
        var stdinClosed = false
        var errorReason = ""

        fun writeRpcFrame(methodName: String, rpcId: Int, payload: JSONObject? = null) {
            val rpcObj = JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", rpcId)
                .put("method", methodName)
            if (payload != null) rpcObj.put("params", payload)
            stdinWriter.write(rpcObj.toString())
            stdinWriter.newLine()
            stdinWriter.flush()
        }

        fun closeStdinSafely() {
            if (stdinClosed) return
            stdinClosed = true
            runCatching { stdinWriter.close() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        }

        fun initiateShutdown() {
            if (sentShutdown) return
            turnSucceeded = observedOutput && errorReason.isBlank()
            if (!turnSucceeded && errorReason.isBlank()) {
                errorReason = "DeepSeek stopped before processing the prompt"
            }
            sentShutdown = true
            shutdownTimestamp = SystemClock.elapsedRealtime()
            writeRpcFrame("shutdown", RPC_ID_SHUTDOWN)
        }

        writeRpcFrame(
            methodName = "initialize",
            rpcId = RPC_ID_INIT,
            payload = JSONObject()
                .put("cwd", guestCwd)
                .put("provider", route.name)
                .put("model", modelName),
        )

        suspend fun dispatchEvent(ev: DshSdkProtocolEvent) {
            when (ev) {
                DshSdkProtocolEvent.Initialized -> if (!sentPrompt) {
                    val blocks = JSONArray().put(JSONObject().put("type", "text").put("text", promptText))
                    val params = JSONObject().put("sessionId", sid).put("contentBlocks", blocks)
                    writeRpcFrame("session/prompt", RPC_ID_PROMPT, params)
                    sentPrompt = true
                }
                DshSdkProtocolEvent.PromptAccepted -> Unit
                is DshSdkProtocolEvent.Status -> {
                    if (ev.running) {
                        observedRunning = true
                        notifyProgress("DeepSeek is working…")
                    } else if (observedRunning && !sentShutdown) {
                        initiateShutdown()
                    }
                }
                is DshSdkProtocolEvent.Reasoning -> {
                    observedOutput = true
                    publishReasoning(
                        sid = sid,
                        content = ev.text,
                        blockId = ev.blockId,
                        isNew = ev.startsNewBlock,
                        done = ev.isFinal,
                        immediate = ev.startsNewBlock || ev.isFinal,
                    )
                }
                is DshSdkProtocolEvent.ToolStarted -> {
                    observedOutput = true
                    streamBus.emit(RuntimeEvent.ToolStarted(sid, ev.name, ev.detail))
                }
                is DshSdkProtocolEvent.ToolCompleted -> {
                    observedOutput = true
                    streamBus.emit(RuntimeEvent.ToolCompleted(sid, ev.name, ev.summary))
                }
                is DshSdkProtocolEvent.AssistantText -> if (ev.text.isNotEmpty()) {
                    observedOutput = true
                    streamBus.emit(RuntimeEvent.AssistantDelta(sid, ev.text))
                }
                is DshSdkProtocolEvent.Failed -> {
                    errorReason = ev.message
                    initiateShutdown()
                }
                DshSdkProtocolEvent.TurnCompleted -> {
                    observedOutput = true
                    initiateShutdown()
                }
                DshSdkProtocolEvent.ShutdownAcknowledged -> closeStdinSafely()
                DshSdkProtocolEvent.Ignored -> Unit
            }
        }

        suspend fun ingestLine(raw: String) {
            val trimmed = raw.trimEnd('\r')
            if (trimmed.isBlank()) return
            if (trimmed.endsWith("... [truncated]") || trimmed.length > MAX_DSH_LINE_BYTES) {
                val wasDup = protocolParser.consumeDuplicateAssistantMessage(trimmed)
                val prefix = trimmed.take(400)
                when {
                    prefix.contains("\"turn/end\"") -> dispatchEvent(DshSdkProtocolEvent.TurnCompleted)
                    prefix.contains("\"tool/result\"") -> Log.d(LOG_TAG, "Skipped huge tool/result line")
                    !wasDup -> Log.w(LOG_TAG, "Skipped oversized RPC line (${trimmed.length} chars)")
                }
                return
            }
            if (!protocolParser.consumeDuplicateAssistantMessage(trimmed)) {
                dispatchEvent(protocolParser.parseLine(trimmed))
            }
        }

        InotifyLogTailer(spawned.outputFile, maxLineBytes = MAX_DSH_LINE_BYTES).use { logTailer ->
            while (proc.isAlive || logTailer.hasUnreadBytes()) {
                val shutdownTimedOut = proc.isAlive &&
                    !forceKilled &&
                    shutdownTimestamp > 0L &&
                    SystemClock.elapsedRealtime() - shutdownTimestamp >= SHUTDOWN_GRACE_MS
                if (shutdownTimedOut) {
                    closeStdinSafely()
                    forceKilled = true
                    proc.destroyForcibly()
                }
                for (ln in logTailer.readLines(timeoutMs = 250L)) {
                    ingestLine(ln)
                }
            }
            for (ln in logTailer.readLines(timeoutMs = 0L, flushIncomplete = true)) {
                ingestLine(ln)
            }
        }
        closeStdinSafely()
        return DshTurnOutcome(completed = turnSucceeded, failure = errorReason)
    }

    override suspend fun respondToApproval(request: ToolRequest, approved: Boolean) = Unit

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (currentSid == sessionId) {
            cancelledByUser = true
            runningProc?.destroy()
            delay(500L)
            if (runningProc?.isAlive == true) runningProc?.destroyForcibly()
            publishFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        currentSid?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        diffStore.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val dir = diffStore.ensureWorkspace(projectId)
        diffStore.revertLatestTurn(projectId, dir).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) = withContext(Dispatchers.IO) {
        diffStore.acceptAll(projectId)
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val dir = diffStore.ensureWorkspace(projectId)
        val pending = diffStore.readChangedPaths(projectId).filterNot(diffStore::isInternalRuntimePath)
        if (pending.isEmpty()) emptyList() else diffStore.buildChangeDetails(projectId, dir, pending)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val dir = diffStore.ensureWorkspace(projectId)
        diffStore.revertFile(projectId, dir, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val dir = diffStore.ensureWorkspace(projectId)
        diffStore.acceptFile(projectId, dir, path)
    }

    private fun provisionDshConfigFiles(
        rootfsDir: File,
        route: DshRoute,
        profile: ProviderProfile,
        guestHome: String = DSH_HOME_GUEST_PATH,
    ): File {
        val targetHome = File(rootfsDir, guestHome.removePrefix("/")).apply { mkdirs() }
        val chosenModel = quoteYamlSingle(profile.model.ifBlank { route.defaultModel })
        val yamlLines = mutableListOf(
            "agent-default-model:",
            "  provider: ${route.name}",
            "  model: $chosenModel",
        )
        if (route.custom != null) {
            yamlLines.addAll(
                listOf(
                    "llm-pi-ai:",
                    "  providers:",
                    "    ${route.name}:",
                    "      apiKeyEnv: ${route.keyEnv}",
                    "      api: ${route.custom.api}",
                    "      baseURL: ${quoteYamlSingle(route.custom.baseUrl)}",
                    "      models:",
                    "        - id: $chosenModel",
                ),
            )
        }
        val settingsContent = yamlLines.joinToString("\n") + "\n"
        val cordisContent = ForgeMcpServer.toDshCordisPatchYaml(
            runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull(),
        )

        fun writeMode0600(file: File, content: String) {
            file.writeText(content)
            runCatching { Os.chmod(file.absolutePath, 384) }
        }

        writeMode0600(File(targetHome, "settings.yaml"), settingsContent)
        writeMode0600(File(targetHome, "cordis.patch.yml"), cordisContent)

        if (guestHome != DSH_HOME_GUEST_PATH) {
            val sharedHome = File(rootfsDir, DSH_HOME_GUEST_PATH.removePrefix("/")).apply { mkdirs() }
            runCatching {
                writeMode0600(File(sharedHome, "settings.yaml"), settingsContent)
                writeMode0600(File(sharedHome, "cordis.patch.yml"), cordisContent)
            }
        }
        return targetHome
    }

    private fun quoteYamlSingle(raw: String): String = "'${raw.replace("'", "''")}'"

    private suspend fun publishReasoning(
        sid: String,
        content: String,
        blockId: Long,
        isNew: Boolean,
        done: Boolean,
        immediate: Boolean = false,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!immediate && nowMs - prevReasoningEmitMs < 400L) return
        val cleaned = compactDshText(content, REASONING_BUF_CAP, REASONING_SUMMARY_CAP)
        if (cleaned.isBlank()) return
        prevReasoningEmitMs = nowMs
        streamBus.emit(
            RuntimeEvent.ReasoningSummary(
                sessionId = sid,
                summary = cleaned,
                blockId = blockId,
                startsNewBlock = isNew,
                isFinal = done,
            ),
        )
        notifyProgress("Thinking…")
    }

    private suspend fun publishCompletionOnce(sid: String) {
        if (!completedSessionIds.add(sid)) return
        streamBus.emit(RuntimeEvent.SessionCompleted(sid))
        val slug = currentSlug?.takeIf { it.isNotBlank() }
        if (slug != null) {
            fgNotifier.finishRes(true, slug, R.string.notif_agent_finished_in, "DeepSeek", slug)
        } else {
            fgNotifier.finishRes(true, "Project", R.string.notif_agent_finished, "DeepSeek")
        }
    }

    private suspend fun publishFailureOnce(sid: String, detail: String) {
        if (!completedSessionIds.add(sid)) return
        streamBus.emit(RuntimeEvent.SessionFailed(sid, detail))
        if (cancelledByUser) {
            fgNotifier.cancel()
        } else {
            fgNotifier.finish(completed = false, projectName = currentSlug ?: "Project", detail = detail)
        }
    }

    private fun mapReadableDshFailure(err: Throwable): String {
        val raw = err.message.orEmpty()
        val low = raw.lowercase()
        val isAuthRejection = "authentication" in low ||
            "invalid api key" in low ||
            "invalid_api_key" in low ||
            "autherror" in low ||
            "token has expired" in low ||
            "api key expired" in low ||
            (("401" in raw || "403" in raw) && ("auth" in low || "http" in low))

        val isQuotaOrRate = "rate limit" in low ||
            "quota" in low ||
            ("429" in raw && "http" in low)

        return when {
            err is DshBridgeAbortedException && hasNonNullDshText(raw) -> raw
            isAuthRejection -> "The provider rejected the saved API key."
            isQuotaOrRate -> "The provider rate limit or quota was reached. Wait a moment or switch keys."
            "missing_credential" in low -> "No API key reached DeepSeek. Re-save the provider key in Settings."
            "not installed" in low -> raw.take(300)
            !hasNonNullDshText(raw) -> "DeepSeek could not start."
            else -> raw.take(500)
        }
    }

    private fun assemblePromptWithContext(
        userPrompt: String,
        historySlice: List<ChatMessage>,
        guestWorkdir: String,
        kind: ProjectKind,
        isResumed: Boolean = false,
    ): String = buildAgentWorkspacePrompt(
        currentPrompt = userPrompt,
        priorMessages = historySlice,
        guestWorkspacePath = guestWorkdir,
        projectKind = kind,
        androidStackInstalled = rtInstaller.isStackInstalled(DevStack.ANDROID),
        maxHistoryChars = HISTORY_CHAR_BUDGET,
        resumed = isResumed,
    )

    private fun notifyProgress(statusText: String) {
        if (currentSid != null) {
            fgNotifier.progress(currentSlug, statusText)
        }
    }

    private class DshBridgeAbortedException(msg: String) : IllegalStateException(msg)

    companion object {
        private const val LOG_TAG = "DshBridge"
        const val DSH_HOME_GUEST_PATH = "/root/.dsh"
        const val FALLBACK_KEY_ENV = "MH_DSH_API_KEY"
        private const val HISTORY_CHAR_BUDGET = 160_000
        private const val RPC_ID_INIT = 1
        private const val RPC_ID_PROMPT = 2
        private const val RPC_ID_SHUTDOWN = 3
        private const val SHUTDOWN_GRACE_MS = 3_000L
    }
}

private data class DshTurnOutcome(
    val completed: Boolean,
    val failure: String,
)

internal data class DshRoute(
    val name: String,
    val keyEnv: String,
    val defaultModel: String,
    val custom: DshCustomRoute? = null,
)

internal data class DshCustomRoute(
    val api: String,
    val baseUrl: String,
)

private val ALLOWED_DSH_WIRE_FORMATS = setOf(
    "anthropic-messages",
    "openai-completions",
    "openai-responses",
)

internal fun safeDshApi(candidate: String): String {
    val clean = candidate.trim()
    return if (clean in ALLOWED_DSH_WIRE_FORMATS) clean else "anthropic-messages"
}

internal object DshRouteMapper {
    fun forProfile(profile: ProviderProfile): DshRoute {
        val modelId = profile.model.ifBlank { profile.kind.defaultModel }
        val endpoint = profile.resolvedBaseUrl
        return when (profile.kind) {
            ProviderKind.DEEPSEEK -> DshRoute("deepseek-official", "DEEPSEEK_API_KEY", modelId)
            ProviderKind.ANTHROPIC -> DshRoute(
                "mh-anthropic",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute("anthropic-messages", endpoint),
            )
            ProviderKind.LLM_ROUTER -> DshRoute(
                "mh-openrouter",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute("anthropic-messages", endpoint.trimEnd('/').removeSuffix("/v1")),
            )
            ProviderKind.KIMI -> DshRoute(
                "mh-kimi",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute(safeDshApi(profile.dshApi), endpoint),
            )
            ProviderKind.OPENCODE_ZEN -> DshRoute(
                "opencode-zen",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute("openai-responses", endpoint),
            )
            ProviderKind.NVIDIA_NIM -> DshRoute(
                "nvidia-nim",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute("openai-completions", endpoint),
            )
            ProviderKind.CUSTOM -> DshRoute(
                "mh-custom",
                DshRuntimeBridge.FALLBACK_KEY_ENV,
                modelId,
                DshCustomRoute(safeDshApi(profile.dshApi), endpoint),
            )
            ProviderKind.CLAUDE -> throw IllegalArgumentException("Claude subscription login is not supported by DeepSeek")
        }
    }
}

internal sealed interface DshLine {
    data object ReasoningHeading : DshLine
    data class Reasoning(val text: String) : DshLine
    data class Diagnostic(val text: String) : DshLine
    data class Answer(val text: String) : DshLine
}

internal object DshHeadlessParser {
    fun parseLine(raw: String): DshLine {
        val stripped = raw.trim()
        if (!stripped.startsWith("dsh:")) return DshLine.Answer(stripped)
        val afterPrefix = stripped.removePrefix("dsh:").trim()
        if (afterPrefix.startsWith("reasoning:")) {
            val content = afterPrefix.removePrefix("reasoning:").trim()
            return if (content.isEmpty()) DshLine.ReasoningHeading else DshLine.Reasoning(content)
        }
        return DshLine.Diagnostic(afterPrefix.ifEmpty { stripped })
    }
}

internal sealed interface DshSdkProtocolEvent {
    data object Initialized : DshSdkProtocolEvent
    data object PromptAccepted : DshSdkProtocolEvent
    data class Status(val running: Boolean) : DshSdkProtocolEvent
    data class Reasoning(
        val blockId: Long,
        val text: String,
        val startsNewBlock: Boolean,
        val isFinal: Boolean,
    ) : DshSdkProtocolEvent
    data class ToolStarted(val callId: String, val name: String, val detail: String) : DshSdkProtocolEvent
    data class ToolCompleted(val callId: String, val name: String, val summary: String) : DshSdkProtocolEvent
    data class AssistantText(val text: String) : DshSdkProtocolEvent
    data class Failed(val message: String) : DshSdkProtocolEvent
    data object TurnCompleted : DshSdkProtocolEvent
    data object ShutdownAcknowledged : DshSdkProtocolEvent
    data object Ignored : DshSdkProtocolEvent
}

private fun hasNonNullDshText(str: String): Boolean {
    val t = str.trim()
    return t.isNotEmpty() && !t.equals("null", ignoreCase = true)
}

private fun nonBlankDshOrFallback(candidate: String, fallback: String): String =
    if (hasNonNullDshText(candidate)) candidate.trim() else fallback

internal class DshSdkProtocolParser(private val targetSessionId: String) {
    private val reasoningAccumulators = HashMap<Long, StringBuilder>()
    private val textAccumulators = HashMap<Long, StringBuilder>()
    private val activeToolLabels = HashMap<String, String>()
    private var emittedDeltaSinceLastMessage = false

    fun consumeDuplicateAssistantMessage(rawLine: CharSequence): Boolean {
        if (!emittedDeltaSinceLastMessage) return false
        val isAssistantMsgFrame = rawLine.contains("\"method\":\"session.event\"") &&
            rawLine.contains("\"type\":\"assistant/message\"")
        if (!isAssistantMsgFrame) return false
        emittedDeltaSinceLastMessage = false
        return true
    }

    fun parseLine(rawLine: String): DshSdkProtocolEvent {
        val json = runCatching { JSONObject(rawLine) }.getOrNull()
        if (json == null) {
            if (rawLine.startsWith("dsh:", ignoreCase = true)) {
                val errBody = rawLine.substring(4).trim()
                return DshSdkProtocolEvent.Failed(nonBlankDshOrFallback(errBody, "DeepSeek reported an unspecified error"))
            }
            return DshSdkProtocolEvent.Ignored
        }

        if (json.has("id")) {
            val rpcId = json.optInt("id", -1)
            val errObj = json.optJSONObject("error")
            if (errObj != null) {
                val msg = nonBlankDshOrFallback(errObj.stringOrEmpty("message"), "DeepSeek SDK request $rpcId failed")
                return DshSdkProtocolEvent.Failed(msg)
            }
            return when (rpcId) {
                1 -> DshSdkProtocolEvent.Initialized
                2 -> DshSdkProtocolEvent.PromptAccepted
                3 -> DshSdkProtocolEvent.ShutdownAcknowledged
                else -> DshSdkProtocolEvent.Ignored
            }
        }

        val paramsObj = json.optJSONObject("params") ?: return DshSdkProtocolEvent.Ignored
        return when (json.stringOrEmpty("method")) {
            "session.status" -> {
                if (paramsObj.stringOrEmpty("sessionId") != targetSessionId) {
                    DshSdkProtocolEvent.Ignored
                } else {
                    DshSdkProtocolEvent.Status(paramsObj.stringOrEmpty("status") == "running")
                }
            }
            "session.event" -> decodeSessionEvent(paramsObj)
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun decodeSessionEvent(paramsObj: JSONObject): DshSdkProtocolEvent {
        if (paramsObj.stringOrEmpty("sessionId") != targetSessionId) return DshSdkProtocolEvent.Ignored
        val evObj = paramsObj.optJSONObject("event") ?: return DshSdkProtocolEvent.Ignored
        val payload = evObj.optJSONObject("data") ?: return DshSdkProtocolEvent.Ignored

        return when (evObj.stringOrEmpty("type")) {
            "assistant/chunk" -> decodeChunkEvent(payload)
            "assistant/message" -> {
                val blocks = payload.optJSONObject("message")?.optJSONArray("content")
                val extracted = extractBlocksText(blocks)
                when {
                    extracted.isBlank() -> DshSdkProtocolEvent.Ignored
                    emittedDeltaSinceLastMessage -> {
                        emittedDeltaSinceLastMessage = false
                        DshSdkProtocolEvent.Ignored
                    }
                    else -> DshSdkProtocolEvent.AssistantText(extracted)
                }
            }
            "tool/call" -> {
                val id = payload.stringOrEmpty("callId")
                val rawTool = payload.stringOrEmpty("name").ifBlank { "Tool" }
                val rawArgs = payload.stringOrEmpty("arguments")
                val friendlyLabel = resolveFriendlyToolLabel(rawTool, rawArgs)
                activeToolLabels[id] = friendlyLabel
                DshSdkProtocolEvent.ToolStarted(id, friendlyLabel, summarizeToolArgs(rawArgs))
            }
            "tool/result" -> {
                val msgObj = payload.optJSONObject("message")
                val firstContent = msgObj?.optJSONArray("content")?.optJSONObject(0)
                val id = firstContent?.stringOrEmpty("toolCallId").orEmpty()
                val label = activeToolLabels.remove(id) ?: "Tool"
                val errText = payload.optJSONObject("error")?.stringOrEmpty("message").orEmpty()
                val bodyText = extractBlocksText(firstContent?.optJSONArray("content"))
                val combined = nonBlankDshOrFallback(errText, bodyText)
                val summary = compactDshText(combined, TOOL_RAW_CAP, 180).ifBlank { "$label completed" }
                DshSdkProtocolEvent.ToolCompleted(id, label, summary)
            }
            "turn/end" -> {
                val reasonObj = payload.optJSONObject("reason")
                when (reasonObj?.stringOrEmpty("kind")) {
                    "error" -> {
                        val errMsg = reasonObj.optJSONObject("error")?.stringOrEmpty("message").orEmpty()
                        DshSdkProtocolEvent.Failed(nonBlankDshOrFallback(errMsg, "DeepSeek turn failed"))
                    }
                    "blocked" -> DshSdkProtocolEvent.Failed("DeepSeek was blocked from completing the task")
                    else -> DshSdkProtocolEvent.TurnCompleted
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun decodeChunkEvent(payload: JSONObject): DshSdkProtocolEvent {
        val chunkObj = payload.optJSONObject("chunk") ?: return DshSdkProtocolEvent.Ignored
        val blockKey = payload.optInt("turn", 0) * 1_000_000L +
            payload.optInt("step", 0) * 1_000L +
            chunkObj.optInt("index", 0)

        return when (chunkObj.stringOrEmpty("type")) {
            "text-delta" -> {
                val piece = chunkObj.stringOrEmpty("text")
                if (piece.isEmpty()) return DshSdkProtocolEvent.Ignored
                val buf = textAccumulators.getOrPut(blockKey) { StringBuilder() }
                appendCapped(buf, piece, TEXT_BLOCK_CAP)
                emittedDeltaSinceLastMessage = true
                DshSdkProtocolEvent.AssistantText(piece)
            }
            "reasoning-delta" -> {
                val buf = reasoningAccumulators.getOrPut(blockKey) { StringBuilder() }
                val isFirst = buf.isEmpty()
                appendCapped(buf, chunkObj.stringOrEmpty("text"), REASONING_BUF_CAP)
                DshSdkProtocolEvent.Reasoning(blockKey, buf.toString(), isFirst, isFinal = false)
            }
            "block-end" -> {
                val innerBlock = chunkObj.optJSONObject("block") ?: return DshSdkProtocolEvent.Ignored
                when (innerBlock.stringOrEmpty("type")) {
                    "text" -> {
                        val prior = textAccumulators.remove(blockKey)?.toString().orEmpty()
                        val full = innerBlock.stringOrEmpty("text")
                        val remainder = if (prior.isEmpty()) full.takeLast(TEXT_BLOCK_CAP) else ""
                        if (remainder.isBlank()) {
                            DshSdkProtocolEvent.Ignored
                        } else {
                            emittedDeltaSinceLastMessage = true
                            DshSdkProtocolEvent.AssistantText(remainder)
                        }
                    }
                    "reasoning" -> {
                        val fullReasoning = innerBlock.stringOrEmpty("text")
                            .ifBlank { reasoningAccumulators[blockKey]?.toString().orEmpty() }
                            .takeLast(REASONING_BUF_CAP)
                        val wasUnseen = !reasoningAccumulators.containsKey(blockKey)
                        reasoningAccumulators.remove(blockKey)
                        if (fullReasoning.isBlank()) {
                            DshSdkProtocolEvent.Ignored
                        } else {
                            DshSdkProtocolEvent.Reasoning(blockKey, fullReasoning, wasUnseen, isFinal = true)
                        }
                    }
                    else -> DshSdkProtocolEvent.Ignored
                }
            }
            else -> DshSdkProtocolEvent.Ignored
        }
    }

    private fun resolveFriendlyToolLabel(rawName: String, argsJson: String): String {
        val subCmd = runCatching { JSONObject(argsJson).stringOrEmpty("command") }.getOrDefault("")
        return when (rawName.lowercase()) {
            "bash", "shell" -> "Bash"
            "read", "view" -> "Read"
            "glob" -> "Glob"
            "grep", "search" -> "Grep"
            "write", "create" -> "Write"
            "edit", "str_replace_editor" -> when (subCmd.lowercase()) {
                "view" -> "Read"
                "create" -> "Write"
                else -> "Edit"
            }
            else -> rawName.replaceFirstChar { it.uppercase() }
        }
    }

    private fun summarizeToolArgs(argsJson: String): String {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull()
        val extracted = obj?.let { node ->
            TOOL_ARG_KEYS.firstNotNullOfOrNull { k -> node.stringOrEmpty(k).takeIf(String::isNotBlank) }
        }.orEmpty()
        return compactDshText(extracted.ifBlank { argsJson }, TOOL_RAW_CAP, 240)
            .ifBlank { "Working in the project" }
    }

    private fun extractBlocksText(arr: JSONArray?): String {
        if (arr == null) return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val segment = when (item.stringOrEmpty("type")) {
                "text" -> item.stringOrEmpty("text")
                "tool-result" -> extractBlocksText(item.optJSONArray("content"))
                else -> ""
            }
            if (segment.isBlank()) continue
            if (sb.isNotEmpty()) sb.append('\n')
            appendCapped(sb, segment, CONTENT_TEXT_CAP)
        }
        return sb.toString()
    }

    private companion object {
        val TOOL_ARG_KEYS = listOf("path", "file_path", "command", "pattern", "query")
    }
}

private const val REASONING_BUF_CAP = 8_000
private const val REASONING_SUMMARY_CAP = 2_000
private const val TOOL_RAW_CAP = 4_000
private const val TEXT_BLOCK_CAP = 8_000
private const val CONTENT_TEXT_CAP = 16_000
private const val MAX_DSH_LINE_BYTES = 256 * 1024

private fun appendCapped(target: StringBuilder, chunk: String, limit: Int) {
    if (chunk.length >= limit) {
        target.clear()
        target.append(chunk.takeLast(limit))
        return
    }
    target.append(chunk)
    val excess = target.length - limit
    if (excess > 0) target.delete(0, excess)
}

private val SK_TOKEN_PATTERN = Regex("sk-[A-Za-z0-9_-]{8,}")
private val KV_SECRET_PATTERN = Regex("(?i)(api[_-]?key|token|secret|password)(\\s*[=:]\\s*)([^\\s'\",}]+)")
private val BEARER_HEADER_PATTERN = Regex("(?i)(authorization:\\s*bearer\\s+)[^\\s'\",}]+")

internal fun compactDshText(rawInput: String, sourceTailCap: Int, finalCap: Int): String {
    if (rawInput.isEmpty() || sourceTailCap <= 0 || finalCap <= 0) return ""
    val startIdx = (rawInput.length - sourceTailCap).coerceAtLeast(0)
    val collapsed = StringBuilder(rawInput.length - startIdx)
    var emitSpace = false
    for (idx in startIdx until rawInput.length) {
        val ch = rawInput[idx]
        if (ch.isWhitespace()) {
            emitSpace = collapsed.isNotEmpty()
        } else {
            if (emitSpace) collapsed.append(' ')
            emitSpace = false
            collapsed.append(ch)
        }
    }
    return collapsed.toString()
        .replace(SK_TOKEN_PATTERN, "sk-••••")
        .replace(KV_SECRET_PATTERN, "$1$2••••")
        .replace(BEARER_HEADER_PATTERN, "$1••••")
        .takeLast(finalCap)
}
