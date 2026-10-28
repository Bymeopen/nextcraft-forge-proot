package com.nextcraft.forge.ui

import com.nextcraft.forge.runtime.*

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentRouter
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.StarterTemplate
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.PromptRule
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.RoutingConfig
import com.nextcraft.forge.model.RoutedMessage
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.WorkspaceDirListing
import com.nextcraft.forge.model.WorkspaceEntry
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providerProtocolForAgent
import com.nextcraft.forge.network.ConnectionValidation
import com.nextcraft.forge.network.DiscoveredModel
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.runtime.asAccountApi
import com.nextcraft.forge.network.GitHubDeviceCode
import androidx.compose.ui.graphics.asImageBitmap
import com.nextcraft.forge.network.GeminiImageClient
import com.nextcraft.forge.runtime.ClaudeSkillStore
import com.nextcraft.forge.runtime.ConnectorStore
import com.nextcraft.forge.runtime.McpConnector
import com.nextcraft.forge.runtime.McpNames
import com.nextcraft.forge.runtime.McpTransport
import com.nextcraft.forge.network.GeminiImageModels
import com.nextcraft.forge.network.GeminiImageResult
import com.nextcraft.forge.network.GeminiVideoClient
import com.nextcraft.forge.network.GeminiVideoResult
import com.nextcraft.forge.runtime.RepoOpResult
import com.nextcraft.forge.runtime.deleteRecursivelyNoFollow
import com.nextcraft.forge.runtime.shellSingleQuote
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.GitHubTokenPoll
import com.nextcraft.forge.network.LocalNetwork
import com.nextcraft.forge.network.ModelDiscoveryResult
import com.nextcraft.forge.network.ProviderApiClient
import com.nextcraft.forge.runtime.AndroidAction
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidLogLevel
import com.nextcraft.forge.runtime.AndroidLogLine
import com.nextcraft.forge.runtime.AndroidLogcatState
import com.nextcraft.forge.runtime.AndroidProjectTemplateGenerator
import com.nextcraft.forge.runtime.AntigravityAuthController
import com.nextcraft.forge.runtime.ClaudeAuthController
import com.nextcraft.forge.runtime.SshController
import com.nextcraft.forge.runtime.SnapshotsController
import com.nextcraft.forge.runtime.RomStudioController
import com.nextcraft.forge.runtime.DiagnosticsController
import com.nextcraft.forge.runtime.CiCdController
import com.nextcraft.forge.runtime.BuildMatrixController
import com.nextcraft.forge.runtime.BuildOutcome
import com.nextcraft.forge.runtime.BuildVerification
import com.nextcraft.forge.runtime.AndroidIssueSeverity
import com.nextcraft.forge.runtime.GoProjectTemplateGenerator
import com.nextcraft.forge.runtime.isRootAccessAvailable
import com.nextcraft.forge.runtime.GameProjectTemplateGenerator
import com.nextcraft.forge.runtime.sanitizeClaudeAuthOutput
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.ReaChatHint
import com.nextcraft.forge.schedule.ScheduleDirectives
import com.nextcraft.forge.schedule.ScheduleLogic
import com.nextcraft.forge.schedule.ScheduleRules
import com.nextcraft.forge.schedule.ScheduleRuntime
import com.nextcraft.forge.schedule.ScheduleStore
import com.nextcraft.forge.schedule.ScheduledTask
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.BranchMergeResult
import com.nextcraft.forge.runtime.CLAUDE_EFFORT_LEVELS
import com.nextcraft.forge.runtime.ChatBranches
import com.nextcraft.forge.runtime.RuntimeLaunchConfigBuilder
import com.nextcraft.forge.runtime.AgentUpdateInfo
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.termux.terminal.TerminalSession
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import org.json.JSONObject
import com.nextcraft.forge.runtime.androidGradleCommand
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.hasAndroidLogcatAccess
import com.nextcraft.forge.runtime.inferAndroidBuildStage
import com.nextcraft.forge.runtime.RuntimeEngine
import com.nextcraft.forge.runtime.RuntimeEngineChoice
import com.nextcraft.forge.runtime.RuntimeEngines
import com.nextcraft.forge.runtime.parseAndroidBuildIssues
import com.nextcraft.forge.runtime.readBoundedText
import com.nextcraft.forge.runtime.startPackageLogcatProcess
import com.nextcraft.forge.runtime.supportsArm64Runtime
import com.nextcraft.forge.update.AppUpdateInfo
import kotlin.concurrent.withLock
import com.nextcraft.forge.update.AppUpdater
import com.nextcraft.forge.update.NoPublishedReleaseException
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.nextcraft.forge.model.generateQuickChatIdentity
import com.nextcraft.forge.R

import androidx.lifecycle.viewModelScope

/** Renames a chat of the open project; a renamed chat keeps its name (it is no longer auto-titled). */
fun MainViewModel.renameChat(chatId: String, title: String) {
    val project = _state.value.activeProject ?: return
    val clean = title.trim().take(60)
    if (clean.isEmpty()) return
    val updated = _state.value.chats.map { if (it.id == chatId) it.copy(title = clean) else it }
    preferences.saveProjectChats(project.id, updated)
    _state.update { it.copy(chats = updated) }
}

fun MainViewModel.clearActiveChat() {
    val project = _state.value.activeProject ?: return
    val chatId = _state.value.activeChatId
    if (runs.containsKey(runKey(project.id, chatId))) return
    AgentKind.entries.forEach { agent ->
        preferences.saveAgentConversation(agent, project.id, chatId, null)
    }
    preferences.saveMessages(project.id, chatId, emptyList())
    _state.update {
        it.copy(
            messages = emptyList(),
            activeStatusLine = null,
            retryPrompts = it.retryPrompts - chatId,
            queuedPrompts = it.queuedPrompts.filterNot { q -> q.projectId == project.id && q.chatId == chatId },
        )
    }
}

fun MainViewModel.sendPrompt(promptText: String) {
    // Keeps the readiness used by routing current (a login can have changed since); the answer is used by the next message.
    if (_state.value.routing.mode != RouteMode.SINGLE) refreshReadyAgents()
    val attachments = _state.value.pendingAttachments
    val cleanPrompt = promptText.trim()
    if (cleanPrompt.isEmpty() && attachments.isEmpty()) return
    val project = _state.value.activeProject ?: return
    val chatId = _state.value.activeChatId
    if (attachments.isEmpty() && (handleLoopCommand(cleanPrompt) || handleQuickSlashCommand(cleanPrompt))) return
    _state.update { it.copy(pendingAttachments = emptyList()) }
    val prompt = QueuedPrompt(text = cleanPrompt, attachments = attachments, projectId = project.id, chatId = chatId)
    when {
        // This chat is still working: the message is sent automatically when its turn completes.
        runs.containsKey(runKey(project.id, chatId)) -> enqueuePrompt(prompt)
        !canStartAnotherRun(tentativeAgent(cleanPrompt)) -> {
            enqueuePrompt(prompt)
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_parallel_queued)) }
        }
        else -> startRun(project, chatId, prompt)
    }
}


/**
 * Moves a failed turn to the next agent when the mode allows it. The failed reply stays in the chat with a note,
 * and the same message is sent again without being added to the history a second time.
 */

/**
 * A turn that failed for a passing reason (Google's stream went idle, a dropped connection, a 5xx) is sent again
 * once by itself: the agent resumes the same conversation, so nothing it did is lost. A second failure of the same
 * message is shown with "Try again" as before.
 */
internal fun MainViewModel.tryAutoRetry(run: ChatRun, reason: String): Boolean {
    if (!isTransientAgentFailure(reason)) return false
    // Never restart from 0s when the agent already delivered a substantial answer or completed its tool work.
    if (run.hasSubstantialReplyOrCompletedWork()) return false
    if (!autoRetriedPrompts.add(run.lastPrompt.id)) return false
    val project = _state.value.projects.firstOrNull { it.id == run.projectId } ?: return false
    appendAssistantText(run, "\n\n[" + reason + "]\n" + str(R.string.agent_auto_retry_notice))
    finishRun(run)
    viewModelScope.launch {
        run.job?.join()
        run.eventsJob?.cancel()
        val projectChats = if (_state.value.activeProjectId == project.id) _state.value.chats else preferences.loadProjectChats(project.id)
        val still = !runs.containsKey(runKey(project.id, run.chatId)) && projectChats.any { it.id == run.chatId }
        if (still) {
            startRun(project, run.chatId, run.lastPrompt.copy(projectId = project.id, chatId = run.chatId), tried = run.tried, resend = true)
        } else {
            offerRetry(run)
        }
    }
    return true
}

internal fun MainViewModel.tryFailover(run: ChatRun, reason: String): Boolean {
    if (!AgentRouter.failsOver(_state.value.routing.mode)) return false
    if (run.hasSubstantialReplyOrCompletedWork()) return false
    val tried = run.tried + run.agent
    val next = routeFor(run.lastPrompt.text, tried) ?: return false
    val project = _state.value.projects.firstOrNull { it.id == run.projectId } ?: return false
    if (!canStartAnotherRun(next.agent, ignoringKey = runKey(project.id, run.chatId))) return false
    appendAssistantText(run, "\n\n[" + reason + "]\n" + str(R.string.route_failover_notice, next.agent.title))
    finishRun(run)
    viewModelScope.launch {
        run.job?.join()
        run.eventsJob?.cancel()
        val projectChats = if (_state.value.activeProjectId == project.id) _state.value.chats else preferences.loadProjectChats(project.id)
        val still = !runs.containsKey(runKey(project.id, run.chatId)) && projectChats.any { it.id == run.chatId }
        if (still) {
            startRun(project, run.chatId, run.lastPrompt.copy(projectId = project.id, chatId = run.chatId), tried = tried, resend = true)
        } else {
            offerRetry(run)
        }
    }
    return true
}

internal fun MainViewModel.enqueuePrompt(prompt: QueuedPrompt) {
    _state.update { it.copy(queuedPrompts = it.queuedPrompts + prompt) }
}

/**
 * All agents (Claude Code, Antigravity CLI, DeepSeek Harness) can work on multiple chats and multiple projects
 * concurrently (one isolated bridge instance and process per chat run). Hardware RAM is the governor so the
 * system never kills processes under low memory.
 */
internal fun MainViewModel.canStartAnotherRun(agent: AgentKind = _state.value.agentKind, ignoringKey: RunKey? = null): Boolean {
    val active = if (ignoringKey == null) runs else runs.filterKeys { it != ignoringKey }
    if (active.isEmpty()) return true
    val hwProfile = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.inspect(getApplication())
    val maxParallel = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.maxParallelChatsFor(hwProfile)
        .coerceAtMost(MAX_PARALLEL_CHATS)
    if (active.size >= maxParallel) return false
    val memory = android.app.ActivityManager.MemoryInfo()
    getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
    val isHeavyNodeAgent = agent == AgentKind.CLAUDE_CODE
    val minFreeBytes = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.minFreeBytesForRun(hwProfile, isHeavyNodeAgent)
    return !memory.lowMemory && memory.availMem >= minFreeBytes
}

internal fun MainViewModel.newClaudeBridge(project: Project, chatId: String): ClaudeRuntimeBridge = ClaudeRuntimeBridge(
    context = getApplication(),
    secretFor = this::resolveProviderSecret,
    rotateSecretFor = this::rotateProviderSecret,
    savedConversation = { preferences.loadAgentConversation(AgentKind.CLAUDE_CODE, project.id, chatId) },
    saveConversation = { preferences.saveAgentConversation(AgentKind.CLAUDE_CODE, project.id, chatId, it) },
).also { it.configureProjectRoot(project.id, project.rootPath) }

internal fun MainViewModel.newDshBridge(project: Project): DshRuntimeBridge = DshRuntimeBridge(
    context = getApplication(),
    secretFor = this::resolveProviderSecret,
    rotateSecretFor = this::rotateProviderSecret,
).also { it.configureProjectRoot(project.id, project.rootPath) }

internal fun MainViewModel.newAntigravityBridge(project: Project, chatId: String): AntigravityRuntimeBridge = AntigravityRuntimeBridge(
    context = getApplication(),
    model = { preferences.antigravityModel },
    effort = { preferences.antigravityEffort },
    conversationId = { _ ->
        preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, project.id, chatId)
    },
    saveConversationId = { _, convId ->
        preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, project.id, chatId, convId)
    },
).also { it.configureProjectRoot(project.id, project.rootPath) }

internal fun MainViewModel.startRun(project: Project, chatId: String, prompt: QueuedPrompt, tried: Set<AgentKind> = emptySet(), resend: Boolean = false) {
    val cleanPrompt = prompt.text
    val attachments = prompt.attachments
    val now = System.currentTimeMillis()
    val userMsg = ChatMessage(fromUser = true, text = cleanPrompt, attachments = attachments)
    // Single agent: exactly the old behaviour. Otherwise the routing picks the agent (and may strip an @agent tag).
    val routed: RoutedMessage = if (_state.value.routing.mode == RouteMode.SINGLE) {
        RoutedMessage(_state.value.agentKind, cleanPrompt, tagged = false)
    } else {
        routeFor(cleanPrompt, tried) ?: RoutedMessage(_state.value.agentKind, cleanPrompt, tagged = false).also {
            _state.update { st -> st.copy(bannerMessage = UiText.res(R.string.route_none_ready)) }
        }
    }
    val agent = routed.agent
    if (!resend && _state.value.routing.mode == RouteMode.BALANCED) preferences.routeCounter = preferences.routeCounter + 1
    // The agent sees the attachments as workspace paths it can open with its own tools.
    val basePrompt = if (attachments.isEmpty()) {
        routed.text
    } else {
        buildString {
            append(routed.text.ifEmpty { "See the attached files." })
            append("\n\nAttached files (inside the workspace):\n")
            attachments.forEach { append("- ").append(it.relativePath).append(" (").append(it.mimeType).append(")\n") }
        }
    }
    // Binary-related messages also tell the agent that `rea` exists (only when REA is installed).
    val reaInstalled = preferences.reaChatHint &&
        com.nextcraft.forge.runtime.RuntimeMarkers.read(File(RuntimeInstaller.rootfsDir(getApplication()), "root/.nextcraft-rea-version")) != null
    val promptWithMemory = augmentWithProjectMemory(project.id, basePrompt, chatId)
    val agentPrompt = ScheduleDirectives.augment(
        com.nextcraft.forge.runtime.AndroidHostTools.augment(ReaChatHint.augment(promptWithMemory, attachments, reaInstalled), preferences.androidHostTools),
        preferences.scheduleEnabled,
    )
    val assistantPlaceholder = ChatMessage(
        id = "streaming-assistant",
        fromUser = false,
        text = "",
        agent = agent.stableId.takeIf { _state.value.routing.mode != RouteMode.SINGLE },
    )
    // A placeholder left behind by an abnormal end would repeat its LazyColumn key ("Key was already used" crash).
    val base = currentMessagesOf(project.id, chatId)
    val historyBefore = base.filterNot { it.id == "streaming-assistant" }
    val historyForAgent = if (resend) historyBefore else historyBefore + userMsg
    val updatedMessages = historyForAgent + assistantPlaceholder
    preferences.saveMessages(project.id, chatId, historyForAgent)

    val autoTitle = cleanPrompt.lineSequence().firstOrNull()?.trim().orEmpty().let { firstLine ->
        if (firstLine.length > 34) firstLine.take(34).trimEnd() + "…" else firstLine
    }
    val projectChats = if (_state.value.activeProjectId == project.id) _state.value.chats else preferences.loadProjectChats(project.id)
    val wasIsolated = projectChats.firstOrNull { it.id == chatId }?.isolated == true
    val otherChatRunningInSameProject = runs.keys.any { it.projectId == project.id && it.chatId != chatId }
    // Auto-isolate when another chat is already working in the same project, or when autoBranchChats is enabled.
    val isolated = wasIsolated || otherChatRunningInSameProject || preferences.autoBranchChats
    if (isolated && !wasIsolated && _state.value.activeProjectId == project.id) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_auto)) }
    }
    val updatedChats = projectChats.map { chat ->
        if (chat.id == chatId) {
            val shouldRetitle = historyBefore.isEmpty() &&
                (chat.title == "Main chat" || chat.title.matches(Regex("""^Chat \d+$"""))) &&
                autoTitle.isNotEmpty()
            chat.copy(
                title = if (shouldRetitle) autoTitle else chat.title,
                updatedAtMillis = now,
                isolated = wasIsolated || isolated,
            )
        } else {
            chat
        }
    }
    val updatedProjects = _state.value.projects.map { proj ->
        if (proj.id == project.id) proj.copy(updatedAtMillis = now) else proj
    }
    preferences.saveProjectChats(project.id, updatedChats)
    preferences.saveProjects(updatedProjects)

    val bridge: RuntimeBridge = when (agent) {
        AgentKind.CLAUDE_CODE -> newClaudeBridge(project, chatId)
        AgentKind.DEEPSEEK_HARNESS -> newDshBridge(project)
        AgentKind.ANTIGRAVITY -> newAntigravityBridge(project, chatId)
    }
    val run = ChatRun(
        chatId = chatId,
        projectId = project.id,
        projectName = project.name,
        chatTitle = updatedChats.firstOrNull { it.id == chatId }?.title ?: "Main chat",
        bridge = bridge,
        ownsBridge = true,
        lastPrompt = QueuedPrompt(text = cleanPrompt, attachments = attachments, projectId = project.id, chatId = chatId),
        isolated = isolated,
        agent = agent,
        tried = tried,
    )
    run.messages = updatedMessages
    run.statusLine = UiText.res(R.string.agent_status_starting, agent.title)
    runs[runKey(project.id, chatId)] = run
    _state.update {
        it.copy(
            projects = updatedProjects,
            chats = if (it.activeProjectId == project.id) updatedChats else it.chats,
            messages = if (it.activeProjectId == project.id && it.activeChatId == chatId) updatedMessages else it.messages,
            retryPrompts = if (it.activeProjectId == project.id) it.retryPrompts - chatId else it.retryPrompts,
        )
    }
    publishRunningFlags()
    showRunView(run)

    // Every run owns its bridge instance, so events are collected per run and never mixed up across chats or projects.
    if (run.ownsBridge) {
        run.eventsJob = viewModelScope.launch { bridge.events.collect { onRuntimeEvent(run, it) } }
    }

    run.job = viewModelScope.launch {
        try {
            // A GitHub sync writes the token into the rootfs while it runs; never let an agent (root in the
            // same chroot) start before that sync has removed it again.
            val chatMutex = branchLock(project.id, chatId)
            if (githubOperationLock.isLocked || (run.isolated && chatMutex.isLocked)) {
                setRunStatus(run, UiText.res(R.string.msg_git_waiting_queue))
            }
            withContext(Dispatchers.IO) { githubOperationLock.withLock { } }
            // An isolated chat works in its own clone of the project (created on first use).
            val branch = if (run.isolated) {
                chatMutex.withLock {
                    chatBranches.ensure(project, chatId) {
                        setRunStatus(run, UiText.res(R.string.msg_git_waiting_queue))
                        notifyGitWaitingInQueue()
                    }.also { ensured ->
                        if (attachments.isNotEmpty()) {
                            withContext(Dispatchers.IO) {
                                val mainWs = checkpoints.ensureWorkspace(project.id)
                                attachments.forEach { att ->
                                    runCatching {
                                        val src = checkpoints.safeWorkspaceFile(mainWs, att.relativePath)
                                        val dst = checkpoints.safeWorkspaceFile(ensured.cloneDir, att.relativePath)
                                        if (src.isFile && (!dst.exists() || dst.length() != src.length())) {
                                            dst.parentFile?.mkdirs()
                                            src.copyTo(dst, overwrite = true)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                null
            }
            setRunStatus(run, UiText.res(R.string.agent_status_starting, agent.title))
            bridge.startSession(
                projectId = branch?.pseudoProjectId ?: project.id,
                projectSlug = branch?.slug ?: project.slug,
                projectKind = project.kind,
                prompt = agentPrompt,
                conversationHistory = historyForAgent,
                provider = agentProfile(agent),
            )
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                val unknownMsg = str(R.string.agent_turn_error_unknown)
                if (tryFailover(run, error.message ?: unknownMsg)) return@launch
                appendAssistantText(run, "\n\n" + str(R.string.agent_turn_error, error.message ?: unknownMsg))
                offerRetry(run)
                finishRun(run)
                // Messages queued behind this chat would otherwise wait for some other run to end.
                discardQueuedPrompts(run.projectId, run.chatId)
                viewModelScope.launch { dispatchQueuedPrompts() }
            }
            finishRun(run)
        }
        val turnJob = coroutineContext[Job]
        viewModelScope.launch {
            delay(AGENT_TURN_END_GRACE_MS)
            if (run.job === turnJob && !run.finished) {
                finishRun(run)
                dispatchQueuedPrompts()
            }
            run.eventsJob?.cancel()
        }
    }
}

/** Mirrors a run's live state into the visible chat when it is the one on screen. */
internal fun MainViewModel.showRunView(run: ChatRun) {
    if (run.projectId != _state.value.activeProjectId || run.chatId != _state.value.activeChatId) return
    _state.update { it.copy(activeStatusLine = run.statusLine, pendingToolRequest = run.pendingTool) }
}

internal fun MainViewModel.setRunStatus(run: ChatRun, line: UiText?) {
    run.statusLine = line
    if (run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId) {
        _state.update { it.copy(activeStatusLine = line) }
    }
    publishRunningFlags()
}

internal fun MainViewModel.setRunPendingTool(run: ChatRun, request: ToolRequest?) {
    run.pendingTool = request
    if (run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId) {
        _state.update { it.copy(pendingToolRequest = request) }
    }
    publishRunningFlags()
}

/** Applies [change] to the chat's messages: kept in the run, and shown when that chat is the visible one. */
internal fun MainViewModel.updateRunMessages(run: ChatRun, change: (List<ChatMessage>) -> List<ChatMessage>) {
    applyPendingText(run)
    run.messages = change(run.messages)
    publishRunMessages(run)
}

/** Folds streamed text that is still buffered into the last assistant message (see [appendAssistantText]). */
internal fun MainViewModel.applyPendingText(run: ChatRun) {
    if (run.pendingText.isEmpty()) return
    val delta = run.pendingText.toString()
    run.pendingText.setLength(0)
    val list = run.messages.toMutableList()
    val lastIdx = list.indexOfLast { !it.fromUser }
    if (lastIdx >= 0) {
        val prev = list[lastIdx]
        list[lastIdx] = prev.copy(text = prev.text + delta)
    } else {
        list += ChatMessage(fromUser = false, text = delta)
    }
    run.messages = list
}

/**
 * Shows [ChatRun.messages] in the open chat at most every [MainViewModel.MESSAGE_PUBLISH_INTERVAL_MS].
 */
internal fun MainViewModel.publishRunMessages(run: ChatRun, immediate: Boolean = false) {
    if (run.projectId != _state.value.activeProjectId || run.chatId != _state.value.activeChatId) return
    val now = android.os.SystemClock.uptimeMillis()
    val wait = run.lastPublishAt + MainViewModel.MESSAGE_PUBLISH_INTERVAL_MS - now
    if (immediate || wait <= 0) {
        run.publishJob?.cancel()
        run.publishJob = null
        run.lastPublishAt = now
        _state.update { it.copy(messages = run.messages) }
    } else if (run.publishJob == null) {
        run.publishJob = viewModelScope.launch {
            delay(wait)
            run.publishJob = null
            applyPendingText(run)
            run.lastPublishAt = android.os.SystemClock.uptimeMillis()
            if (run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId) {
                _state.update { it.copy(messages = run.messages) }
            }
        }
    }
}

internal fun MainViewModel.publishRunningFlags() {
    val activeProjectId = _state.value.activeProjectId
    val activeProjectChatIds = runs.keys.filter { it.projectId == activeProjectId }.map { it.chatId }.toSet()
    val countsByProject = runs.keys.groupingBy { it.projectId }.eachCount()
    val liveList = runs.values.map { r ->
        LiveAgentRunInfo(
            projectId = r.projectId,
            projectName = _state.value.projects.firstOrNull { it.id == r.projectId }?.name ?: r.projectName,
            chatId = r.chatId,
            chatTitle = if (r.projectId == activeProjectId) {
                _state.value.chats.firstOrNull { it.id == r.chatId }?.title ?: r.chatTitle
            } else r.chatTitle,
            agent = r.agent,
            statusLine = r.statusLine,
            waitingApproval = r.pendingTool != null,
        )
    }
    _state.update {
        it.copy(
            runningChatIds = activeProjectChatIds,
            runningProjectChatCounts = countsByProject,
            liveAgentRuns = liveList,
            sessionRunning = runs.isNotEmpty(),
        )
    }
}

internal fun MainViewModel.offerRetry(run: ChatRun) {
    if (run.projectId == _state.value.activeProjectId) {
        _state.update { it.copy(retryPrompts = it.retryPrompts + (run.chatId to RetryPrompt(run.chatId, run.lastPrompt))) }
    }
}

/** "Try again" after a failed turn: sends the same message (and attachments) once more. */
fun MainViewModel.retryLastPrompt() {
    val retry = _state.value.retryPrompts[_state.value.activeChatId] ?: return
    val project = _state.value.activeProject ?: return
    // Chat ids such as "main" exist in every project; the offer belongs to the project where the turn failed.
    if (retry.prompt.projectId != null && retry.prompt.projectId != project.id) return
    if (runs.containsKey(runKey(project.id, retry.chatId))) return
    if (!canStartAnotherRun(tentativeAgent(retry.prompt.text))) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_parallel_queued)) }
        return
    }
    _state.update { it.copy(retryPrompts = it.retryPrompts - retry.chatId) }
    // The failed turn's message is already in the history: send it again without adding a second bubble.
    startRun(project, retry.chatId, retry.prompt.copy(projectId = project.id, chatId = retry.chatId), resend = true)
}

/** Stops the Android build of [projectId] (defaults to the active project); a running agent session keeps going. */
fun MainViewModel.stopAndroidBuild(projectId: String = _state.value.activeProjectId) {
    val handle = projectBuildHandles[projectId]
    if (handle != null) {
        handle.stopHook()
    } else if (projectId == _state.value.activeProjectId) {
        RuntimeTaskController.stop(RuntimeTaskController.BUILD)
    }
}

fun MainViewModel.removeQueuedPrompt(id: String) {
    _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { it.id == id }) }
}

/** Sends what was queued for each chat across all projects as one follow-up prompt, as soon as that chat is idle and a slot is free. */
internal fun MainViewModel.dispatchQueuedPrompts() {
    val queue = _state.value.queuedPrompts
    if (queue.isEmpty()) return
    val targets = queue.mapNotNull { q ->
        val pid = q.projectId ?: _state.value.activeProjectId
        val cid = q.chatId
        if (pid.isNotBlank() && !cid.isNullOrBlank()) RunKey(pid, cid) else null
    }.distinct()
    for (target in targets) {
        val here = _state.value
        val forChat = queue.filter { (it.projectId ?: here.activeProjectId) == target.projectId && it.chatId == target.chatId }
        if (forChat.isEmpty()) continue
        val project = here.projects.firstOrNull { it.id == target.projectId }
        val projectChats = if (here.activeProjectId == target.projectId) here.chats else preferences.loadProjectChats(target.projectId)
        if (project == null || projectChats.none { it.id == target.chatId }) {
            // The chat or project it was typed for was deleted.
            _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { q -> forChat.any { it.id == q.id } }) }
            continue
        }
        if (runs.containsKey(target)) continue
        if (!canStartAnotherRun(tentativeAgent(forChat.first().text))) break
        val merged = QueuedPrompt(
            text = forChat.map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
            attachments = forChat.flatMap { it.attachments },
            projectId = project.id,
            chatId = target.chatId,
        )
        _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { q -> forChat.any { it.id == q.id } }) }
        startRun(project, target.chatId, merged)
    }
}

/** Drops one chat's queued messages when its turn ended without completing (stopped or failed) and tells the user. */
internal fun MainViewModel.discardQueuedPrompts(projectId: String, chatId: String) {
    if (_state.value.queuedPrompts.none { (it.projectId ?: _state.value.activeProjectId) == projectId && it.chatId == chatId }) return
    _state.update { st ->
        st.copy(
            queuedPrompts = st.queuedPrompts.filterNot { (it.projectId ?: st.activeProjectId) == projectId && it.chatId == chatId },
            bannerMessage = UiText.res(R.string.msg_queue_discarded),
        )
    }
}

/** Stops the agent turn of the chat on screen only; other chats keep working and a running build keeps going. */
fun MainViewModel.stopActiveTask() {
    val run = runs[runKey(_state.value.activeProjectId, _state.value.activeChatId)] ?: return
    stopRun(run, discardQueue = true)
}

/** Stops the agent turn of any specific project and chat (used by the Dynamic Multi-Agent Live Activity HUD). */
fun MainViewModel.stopProjectChatRun(projectId: String, chatId: String) {
    val run = runs[runKey(projectId, chatId)] ?: return
    stopRun(run, discardQueue = true)
}

/**
 * "Steer": sends a queued message now instead of after the turn. The running turn is stopped (what it already did stays
 * in the workspace and in the chat) and the message starts a new turn right away with the conversation so far; the other
 * queued messages keep waiting. This works for every agent, since none of the CLIs takes a new message in the middle of a
 * turn that was started with its prompt on stdin.
 */
fun MainViewModel.steerQueuedPrompt(id: String) {
    val queued = _state.value.queuedPrompts.firstOrNull { it.id == id } ?: return
    val chatId = queued.chatId ?: return
    val project = _state.value.activeProject?.takeIf { it.id == queued.projectId && _state.value.activeChatId == chatId } ?: return
    val prompt = queued.copy(projectId = project.id, chatId = chatId)
    val key = runKey(project.id, chatId)
    val run = runs[key]
    // Claude Code takes the message into the running session: nothing is stopped, the agent answers it right after
    // its current step with everything it has done so far in context.
    val claude = run?.takeIf { it.ownsBridge && prompt.attachments.isEmpty() }?.bridge as? com.nextcraft.forge.runtime.ClaudeRuntimeBridge
    if (run != null && claude != null && claude.steer(prompt.text)) {
        removeQueuedPrompt(id)
        updateRunMessages(run) { messages ->
            // Shown before the reply that is still streaming, which goes on to answer it.
            val at = messages.indexOfLast { !it.fromUser }.takeIf { it >= 0 } ?: messages.size
            messages.toMutableList().apply { add(at, ChatMessage(fromUser = true, text = prompt.text)) }
        }
        return
    }
    removeQueuedPrompt(id)
    if (run == null) {
        if (canStartAnotherRun(tentativeAgent(prompt.text))) startRun(project, chatId, prompt) else enqueuePrompt(prompt)
        return
    }
    stopRun(run, discardQueue = false) {
        if (!runs.containsKey(key)) startRun(project, chatId, prompt) else enqueuePrompt(prompt)
    }
}

internal fun MainViewModel.stopRun(run: ChatRun, discardQueue: Boolean, afterStop: (() -> Unit)? = null) {
    run.steering = !discardQueue
    if (discardQueue) discardQueuedPrompts(run.projectId, run.chatId)
    val pendingApproval = run.pendingTool
    setRunStatus(run, UiText.res(R.string.agent_status_stopping))
    setRunPendingTool(run, null)
    // Bridge sessions run NonCancellable: the run stays registered until the old session really ends,
    // otherwise a new prompt could race with it over shared bridge state.
    viewModelScope.launch {
        if (pendingApproval != null) {
            runCatching { run.bridge.respondToApproval(pendingApproval, false) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        if (run.ownsBridge) {
            runCatching { run.bridge.stopActiveSession() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        } else {
            RuntimeTaskController.stop(RuntimeTaskController.AGENT)
        }
        val job = run.job
        job?.cancel()
        job?.join()
        finishRun(run, stoppedByUser = true)
        if (afterStop != null) afterStop() else dispatchQueuedPrompts()
    }
}

fun MainViewModel.respondToToolRequest(approved: Boolean) {
    val run = runs[runKey(_state.value.activeProjectId, _state.value.activeChatId)]
    val req = run?.pendingTool ?: _state.value.pendingToolRequest ?: return
    val bridge = run?.bridge ?: claudeRuntime
    if (run != null) setRunPendingTool(run, null) else _state.update { it.copy(pendingToolRequest = null) }
    viewModelScope.launch {
        // An unwritable response file must not crash the app; the request then expires on the agent side.
        runCatching { bridge.respondToApproval(req, approved) }.onFailure { err ->
            if (err is CancellationException) throw err
            Log.w("MainViewModel", "Could not answer tool approval", err)
            _state.update { it.copy(bannerMessage = UiText.Raw(err.message ?: err.javaClass.simpleName)) }
        }
    }
}

internal fun MainViewModel.onRuntimeEvent(run: ChatRun, event: RuntimeEvent) {
    if (event is RuntimeEvent.SessionStarted) {
        run.sessionId = event.sessionId
    } else if (event.sessionId != run.sessionId &&
        !(event is RuntimeEvent.FilesChanged && event.sessionId == run.finishedSessionId)
    ) {
        return
    }
    // After the turn ended only the file list of that session is still wanted (Claude sends it after the result).
    if (run.finished && event !is RuntimeEvent.FilesChanged) return
    when (event) {
        is RuntimeEvent.SessionStarted -> setRunStatus(run, UiText.res(R.string.agent_status_started))
        is RuntimeEvent.AssistantDelta -> appendAssistantText(run, event.text)
        is RuntimeEvent.ReasoningProgress -> setRunStatus(run, UiText.res(R.string.agent_status_reasoning, event.estimatedTokens))
        is RuntimeEvent.ReasoningSummary -> setRunStatus(run, UiText.Raw(event.summary.take(120)))
        is RuntimeEvent.ToolStarted -> {
            recordActivityItem(run, event.toolName, event.detail, isComplete = false)
            setRunStatus(run, UiText.Raw("${event.toolName}: ${event.detail}".take(120)))
        }
        is RuntimeEvent.ToolProgress -> setRunStatus(run, UiText.Raw("${event.toolName}: ${event.detail}".take(120)))
        is RuntimeEvent.ToolCompleted -> recordActivityItem(run, event.toolName, event.summary, isComplete = true)
        is RuntimeEvent.RuntimeLog -> setRunStatus(run, UiText.Raw("${event.title}: ${event.detail}".take(120)))
        is RuntimeEvent.ToolRequested -> {
            setRunPendingTool(run, event.request)
            if (run.projectId != _state.value.activeProjectId || run.chatId != _state.value.activeChatId) {
                // The approval dialog belongs to the chat it came from: tell the user which chat/project is waiting.
                val projName = projectOf(run.projectId)?.name.orEmpty()
                val chatList = if (_state.value.activeProjectId == run.projectId) _state.value.chats else preferences.loadProjectChats(run.projectId)
                val chatTitle = chatList.firstOrNull { it.id == run.chatId }?.title.orEmpty()
                val label = if (projName.isNotBlank() && run.projectId != _state.value.activeProjectId) "$projName · $chatTitle" else chatTitle
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_chat_needs_approval, label)) }
            }
        }
        is RuntimeEvent.ToolApproved, is RuntimeEvent.ToolRejected -> setRunPendingTool(run, null)
        is RuntimeEvent.PermissionDenied -> {
            // Say so in the chat instead of failing the session: the agent receives the refusal and carries on.
            appendAssistantText(
                run,
                "\n\n" + str(
                    R.string.msg_permission_denied,
                    event.toolName,
                    event.reason.take(300),
                ) + "\n",
            )
        }
        is RuntimeEvent.FilesChanged -> {
            if (event.changes.isNotEmpty()) run.changedFiles = true
            if (_state.value.activeProjectId == run.projectId && _state.value.activeChatId == run.chatId) {
                _state.update { it.copy(changedItems = event.changes) }
                refreshWorkspaceFilesAndChanges(run.projectId)
            }
        }
        is RuntimeEvent.PreviewStarted -> _state.update { it.copy(previewUrl = event.url) }
        is RuntimeEvent.SessionCompleted -> {
            finishRun(run)
            applyAgentSchedule(run)
            applyAgentProjectMemory(run)
            if (autoRebuildProjects.remove(run.projectId)) {
                projectOf(run.projectId)?.let { proj ->
                    if (_state.value.activeProjectId == proj.id) {
                        _state.update { it.copy(activeTab = ForgeTab.ANDROID) }
                    }
                    runAndroidBuild("assembleDebug", installAfterBuild = true, targetProject = proj)
                }
            } else {
                maybeVerifyBuild(run)
            }
            // Wait for the bridge call to fully unwind so the next turn cannot race its shared state.
            viewModelScope.launch {
                run.job?.join()
                dispatchQueuedPrompts()
            }
        }
        is RuntimeEvent.SessionFailed -> {
            // The bridges report a user stop in English; show it in the app language.
            val reason = if (event.reason == "Stopped by user") str(R.string.agent_stopped_by_user) else event.reason
            if (event.reason != "Stopped by user" && tryFailover(run, reason)) return
            if (event.reason != "Stopped by user" && !run.steering && tryAutoRetry(run, reason)) return
            appendFailureReason(run, reason)
            // A run stopped by "Steer" neither offers a retry nor touches the queue: the steering message starts next.
            if (!run.steering) offerRetry(run)
            finishRun(run)
            if (!run.steering) {
                discardQueuedPrompts(run.projectId, run.chatId)
                viewModelScope.launch {
                    run.job?.join()
                    dispatchQueuedPrompts()
                }
            }
        }
    }
}

/**
 * Adds "[reason]" to the reply unless the agent already printed that error into it (Claude Code streams an API
 * error as reply text and then fails with the same message, which showed the whole error twice).
 */
internal fun MainViewModel.appendFailureReason(run: ChatRun, reason: String) {
    applyPendingText(run)
    val reply = run.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
    val head = reason.trim().lineSequence().firstOrNull().orEmpty().take(80)
    val shown = head.isNotBlank() && reply.contains(head)
    if (!shown) appendAssistantText(run, "\n\n[$reason]")
}

/** Buffers a streamed delta; it reaches [ChatRun.messages] at the next publish or message change. */
internal fun MainViewModel.appendAssistantText(run: ChatRun, delta: String) {
    if (delta.isEmpty()) return
    run.pendingText.append(delta)
    publishRunMessages(run)
}

internal fun MainViewModel.recordActivityItem(run: ChatRun, title: String, rawDetail: String, isComplete: Boolean) {
    val detail = clipToolDetail(rawDetail)
    updateRunMessages(run) { messages ->
        val list = messages.toMutableList()
        val lastIdx = list.indexOfLast { !it.fromUser }
        if (lastIdx >= 0) {
            val prev = list[lastIdx]
            val items = prev.workItems.toMutableList()
            val existingIdx = items.indexOfLast { it.title == title && !it.isComplete }
            if (existingIdx >= 0 && isComplete) {
                items[existingIdx] = items[existingIdx].copy(detail = detail, isComplete = true)
            } else {
                items += ActivityItem(
                    title = title,
                    detail = detail,
                    isComplete = isComplete,
                    isCommand = title.equals("Bash", ignoreCase = true),
                )
            }
            list[lastIdx] = prev.copy(workItems = items)
        }
        list
    }
}

internal fun MainViewModel.finishRun(run: ChatRun, stoppedByUser: Boolean = false) {
    applyPendingText(run)
    publishRunMessages(run, immediate = true)
    // The terminal event and the job's own exit path both call this; the second call must not re-save or re-sync.
    if (run.finished) return
    run.finished = true
    // Late events of this session must not touch the next turn; only its FilesChanged is still wanted.
    run.finishedSessionId = run.sessionId
    val now = System.currentTimeMillis()
    val finalizedMessages = run.messages.map { msg ->
        if (msg.id == "streaming-assistant") {
            val elapsed = (now - msg.createdAt.toEpochMilli()).coerceAtLeast(0L)
            msg.copy(
                id = UUID.randomUUID().toString(),
                text = msg.text.ifBlank { str(R.string.agent_task_finished) },
                workedMillis = elapsed,
            )
        } else {
            msg
        }
    }
    run.messages = finalizedMessages
    // A long turn's JSON is megabytes: writing it on the main thread was part of the "not responding" stalls.
    viewModelScope.launch(Dispatchers.IO) { runCatching { preferences.saveMessages(run.projectId, run.chatId, finalizedMessages) } }
    val key = runKey(run.projectId, run.chatId)
    if (runs[key] === run) runs.remove(key)
    val visible = run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId
    _state.update {
        it.copy(
            messages = if (visible) finalizedMessages else it.messages,
            activeStatusLine = if (visible) (if (stoppedByUser) UiText.res(R.string.agent_status_stopped) else null) else it.activeStatusLine,
            pendingToolRequest = if (visible) null else it.pendingToolRequest,
        )
    }
    publishRunningFlags()
    val projectIdle = runs.values.none { it.projectId == run.projectId }
    val checkpointId = projectOf(run.projectId)?.let { proj ->
        if (run.isolated) chatBranches.branchFor(proj, run.chatId).pseudoProjectId else proj.id
    } ?: run.projectId
    if (preferences.autoKeepChanges && (projectIdle || run.isolated)) {
        // Auto-save: the agent's edits are kept right away instead of waiting in "Review changes".
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(checkpointId) } }
            if (_state.value.activeProjectId == run.projectId) refreshWorkspaceFilesAndChanges(run.projectId)
        }
    } else if (_state.value.activeProjectId == run.projectId) {
        refreshWorkspaceFilesAndChanges(run.projectId)
    }
    if (run.isolated) commitBranchTurn(run)
    // One sync after the last chat of that project finished, not one per chat while others are still editing.
    if (preferences.autoSyncGitHub && projectIdle) {
        syncProjectWithGitHub(run.projectId, "Sync agent changes from NextCraft Forge")
    }
}

