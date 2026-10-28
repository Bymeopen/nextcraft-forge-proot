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

// --- Scheduled tasks: a prompt sent again to a chat every N minutes (by the user or by the agent) ---

/** Runs due tasks while the app process lives; [com.nextcraft.forge.schedule.ScheduleTickWorker] covers a killed process. */
internal fun MainViewModel.startScheduleTicker() {
    ScheduleRuntime.tickerActive = true
    viewModelScope.launch {
        while (true) {
            delay(SCHEDULE_TICK_MS)
            runCatching { runDueScheduledTasks() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
    }
}

internal fun MainViewModel.saveScheduled(tasks: List<ScheduledTask>) {
    scheduleStore.save(tasks)
    _state.update { it.copy(scheduledTasks = tasks) }
}

internal fun MainViewModel.dropScheduledTasks(predicate: (ScheduledTask) -> Boolean) {
    val current = _state.value.scheduledTasks
    if (current.any(predicate)) saveScheduled(current.filterNot(predicate))
}

/**
 * Starts every due task across any project whose chat is idle, as long as hardware memory allows.
 */
internal fun MainViewModel.runDueScheduledTasks() {
    val st = _state.value
    val activeProjectId = st.activeProjectId
    val activeChatIds = st.chats.map { it.id }.toSet()
    // A deleted chat leaves no orphan timer behind.
    if (activeProjectId.isNotBlank()) {
        dropScheduledTasks { it.projectId == activeProjectId && it.chatId !in activeChatIds }
    }
    // Timers of a project that no longer exists (deleted) are never run again.
    val knownProjects = st.projects.associateBy { it.id }
    dropScheduledTasks { it.projectId !in knownProjects.keys }
    val now = System.currentTimeMillis()
    var startedThisTick = false
    val (updated, started) = ScheduleLogic.takeDue(_state.value.scheduledTasks, now) { task ->
        val proj = knownProjects[task.projectId]
        val projectChatIds = if (task.projectId == activeProjectId) activeChatIds else preferences.loadProjectChats(task.projectId).map { it.id }.toSet()
        val ok = !startedThisTick && proj != null && !runs.containsKey(runKey(task.projectId, task.chatId)) &&
            canStartAnotherRun(tentativeAgent(task.prompt)) && task.chatId in projectChatIds
        if (ok) startedThisTick = true
        ok
    }
    if (started.isEmpty()) return
    saveScheduled(updated)
    started.forEach { task ->
        val proj = knownProjects[task.projectId] ?: return@forEach
        if (!runs.containsKey(runKey(proj.id, task.chatId))) {
            startRun(proj, task.chatId, QueuedPrompt(text = task.prompt, projectId = proj.id, chatId = task.chatId))
        }
    }
}

/** Reads `[[schedule: …]]` / `[[unschedule]]` from the agent's finished reply and keeps the timers in step. */
internal fun MainViewModel.applyAgentSchedule(run: ChatRun) {
    if (!preferences.scheduleEnabled) return
    applyPendingText(run)
    val reply = run.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
    val directives = ScheduleDirectives.parse(reply)
    if (directives.isEmpty()) return
    val before = _state.value.scheduledTasks
    val after = ScheduleLogic.applyDirectives(before, directives, run.projectId, run.chatId, System.currentTimeMillis())
    if (after == before) return
    saveScheduled(after)
    _state.update { it.copy(bannerMessage = UiText.res(R.string.schedule_agent_changed)) }
}


/** Prepend `<workspace>/.forge/memory.md` (if present and non-blank, up to 4 KB) to the prompt context. */
internal fun MainViewModel.augmentWithProjectMemory(projectId: String, prompt: String, chatId: String = _state.value.activeChatId): String {
    val memText = runCatching {
        val proj = projectOf(projectId)
        val memFile = if (proj != null) {
            resolveWorkspaceFile(proj, ".forge/memory.md", chatId)
        } else {
            checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(projectId), ".forge/memory.md")
        }
        if (memFile.isFile) memFile.readBoundedText(4096).trim() else ""
    }.getOrDefault("")
    if (memText.isEmpty()) return prompt
    return buildString {
        append("[Project Memory (.forge/memory.md) — persistent architectural & build notes for this project. You may append new notes by outputting `[[memory: concise note]]`]:\n")
        append(memText)
        append("\n\n---\n\n")
        append(prompt)
    }
}

/** Appends any `[[memory: ...]]` directives emitted by the agent into `<workspace>/.forge/memory.md`. */
internal fun MainViewModel.applyAgentProjectMemory(run: ChatRun) {
    applyPendingText(run)
    val reply = run.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
    val notes = Regex("""\[\[\s*memory\s*:\s*([^\]]+?)\s*]]""", RegexOption.IGNORE_CASE)
        .findAll(reply)
        .map { it.groupValues[1].trim() }
        .filter { it.isNotEmpty() }
        .toList()
    if (notes.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val proj = projectOf(run.projectId)
            val ws = if (proj != null) activeWorkspaceRoot(proj, run.chatId) else checkpoints.ensureWorkspace(run.projectId)
            val memFile = checkpoints.safeWorkspaceFile(ws, ".forge/memory.md")
            memFile.parentFile?.mkdirs()
            val existing = if (memFile.isFile) memFile.readBoundedText(16 * 1024) else "# Project Memory\n"
            val additions = notes.filterNot { existing.contains(it) }.joinToString("\n") { "- $it" }
            if (additions.isNotEmpty()) {
                val updated = existing.trimEnd() + "\n" + additions + "\n"
                memFile.writeText(updated)
            }
        }
    }
}

/** Opens `.forge/memory.md` in the project file editor, creating a starter template if it does not exist yet. */
fun MainViewModel.openProjectMemory() {
    val project = _state.value.activeProject ?: return
    val path = ".forge/memory.md"
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
                val target = checkpoints.safeWorkspaceFile(ws, path)
                target.parentFile?.mkdirs()
                if (!target.exists()) {
                    target.writeText(
                        "# ${project.name} — Project Memory\n\n" +
                            "- Key architecture, build flags, and conventions remembered across all chats.\n" +
                            "- Agents can also append notes using `[[memory: note]]`.\n",
                    )
                }
            }
        }
        refreshWorkspaceFilesAndChanges(project.id)
        openWorkspaceFile(path)
        selectProjectPane(ProjectPane.FILES)
    }
}


/**
 * Local CI: after a turn that changed an Android project's files, build and unit-test it in one Gradle run. A
 * failure in the code goes back to the same chat's agent (see [onBuildVerified]).
 */
internal fun MainViewModel.maybeVerifyBuild(run: ChatRun) {
    if (!preferences.autoVerifyBuild || !run.changedFiles || run.isolated) return
    val project = projectOf(run.projectId) ?: return
    val key = runKey(run.projectId, run.chatId)
    if (!BuildVerification.isAutoFixPrompt(run.lastPrompt.text)) autoFixRounds.remove(key)
    viewModelScope.launch {
        val android = withContext(Dispatchers.IO) {
            runCatching { findAndroidProjectRoot(checkpoints.ensureWorkspace(project.id)) != null }.getOrDefault(false)
        }
        if (!android) return@launch
        _state.update { it.copy(bannerMessage = UiText.res(R.string.verify_running)) }
        runAndroidBuild(BuildVerification.VERIFY_TASKS, targetProject = project) { outcome ->
            onBuildVerified(project, run.chatId, key, outcome)
        }
    }
}

internal fun MainViewModel.onBuildVerified(project: Project, chatId: String, key: RunKey, outcome: BuildOutcome) {
    val banner = when {
        outcome.cancelled -> null
        outcome.succeeded -> UiText.res(R.string.verify_passed).also { autoFixRounds.remove(key) }
        outcome.failure == BuildVerification.FailureKind.CODE && preferences.autoFixBuild -> {
            val round = (autoFixRounds[key] ?: 0) + 1
            if (round > BuildVerification.MAX_FIX_ROUNDS) {
                autoFixRounds.remove(key)
                UiText.res(R.string.verify_gave_up, BuildVerification.MAX_FIX_ROUNDS)
            } else {
                autoFixRounds[key] = round
                val errors = outcome.issues.filter { it.severity == AndroidIssueSeverity.ERROR }.map { it.formatForAgentPrompt() }
                sendAutoFix(project, chatId, BuildVerification.fixPrompt(round, errors, outcome.log))
                UiText.res(R.string.verify_fixing, round, BuildVerification.MAX_FIX_ROUNDS)
            }
        }
        outcome.failure == BuildVerification.FailureKind.OUT_OF_MEMORY -> UiText.res(R.string.verify_failed_memory)
        outcome.failure == BuildVerification.FailureKind.DEPENDENCY -> UiText.res(R.string.verify_failed_dependency)
        else -> UiText.res(R.string.verify_failed)
    }
    banner?.let { message -> _state.update { it.copy(bannerMessage = message) } }
}

/** Like [sendPrompt], for a chat that need not be the open one. */
internal fun MainViewModel.sendAutoFix(project: Project, chatId: String, text: String) {
    val prompt = QueuedPrompt(text = text, projectId = project.id, chatId = chatId)
    if (runs.containsKey(runKey(project.id, chatId)) || !canStartAnotherRun(tentativeAgent(text))) enqueuePrompt(prompt)
    else startRun(project, chatId, prompt)
}

/** Sends the current Android build failure (parsed issues or raw Gradle tail log) to the coding agent, optionally scheduling an automatic rebuild on completion. */
fun MainViewModel.fixBuildWithAgent(autoRebuild: Boolean = false) {
    val project = _state.value.activeProject ?: return
    val build = _state.value.androidBuild
    if (autoRebuild) autoRebuildProjects.add(project.id)
    val prompt = if (build.issues.isNotEmpty()) {
        build.issues.joinToString("\n\n") { it.formatForAgentPrompt() }
    } else {
        val tailLog = build.log.lineSequence().toList().takeLast(65).joinToString("\n")
        "Fix this Android Gradle build failure in project `${project.name}`:\n\n```text\n$tailLog\n```"
    }
    sendPrompt(prompt)
    openConversation()
}

/** Sends the latest ERROR / FATAL logcat crash lines of the active project to the coding agent and opens the chat. */
fun MainViewModel.fixLogcatCrashWithAgent() {
    val logcat = _state.value.androidLogcat
    val errorLines = logcat.lines.filter {
        it.level == AndroidLogLevel.ERROR || it.level == AndroidLogLevel.FATAL
    }.takeLast(45)
    if (errorLines.isEmpty()) return
    val pkg = logcat.packageName ?: _state.value.activeProject?.name ?: "app"
    val trace = errorLines.joinToString("\n") { it.text }
    val prompt = "Fix this Android runtime crash / error from Logcat (`$pkg`):\n\n```text\n$trace\n```"
    sendPrompt(prompt)
    openConversation()
}

/** Invokes a secondary ready agent (or senior reviewer pass) to audit the uncommitted workspace diff. */
fun MainViewModel.reviewChangesWithSecondAgent() {
    val pending = _state.value.changedItems.filter { it.accepted == null }
    if (pending.isEmpty()) return
    val currentPrimary = AgentRouter.primaryAgent(_state.value.routing, _state.value.agentKind, _state.value.readyAgents)
    val secondary = (_state.value.readyAgents - currentPrimary).firstOrNull()
    val tagPrefix = when (secondary) {
        AgentKind.CLAUDE_CODE -> "@claude "
        AgentKind.ANTIGRAVITY -> "@agy "
        AgentKind.DEEPSEEK_HARNESS -> "@deepseek "
        null -> ""
    }
    if (secondary != null && _state.value.routing.mode == RouteMode.SINGLE) {
        setRouteMode(RouteMode.MANUAL)
    }
    val filesSummary = pending.take(10).joinToString("\n") { item ->
        val preview = item.diffLines.take(20).joinToString("\n") { line ->
            val sign = when (line.type) {
                com.nextcraft.forge.model.DiffLineType.ADDITION -> "+"
                com.nextcraft.forge.model.DiffLineType.DELETION -> "-"
                else -> " "
            }
            "$sign${line.text}"
        }
        "### `${item.path}` (+${item.additions} / -${item.deletions})\n```diff\n$preview\n```"
    }
    val prompt = "${tagPrefix}Perform a concise senior code review of the uncommitted changes in this workspace. " +
        "Check for bugs, regressions, edge cases, and concurrency or UI issues:\n\n$filesSummary"
    sendPrompt(prompt)
}

/** Accepts all pending checkpoint changes and immediately commits them to the project's Git repository. */
fun MainViewModel.acceptAndCommitChanges() {
    val project = _state.value.activeProject ?: return
    val chatId = _state.value.activeChatId
    val pending = _state.value.changedItems.filter { it.accepted == null }
    if (pending.isEmpty()) return
    val fileNames = pending.take(3).joinToString(", ") { it.path.substringAfterLast('/') }
    val more = if (pending.size > 3) " +${pending.size - 3} more" else ""
    val commitMsg = "Apply agent changes ($fileNames$more)"
    val checkpointId = activeCheckpointProjectId(project, chatId)
    val isolated = _state.value.chats.firstOrNull { it.id == chatId }?.isolated == true &&
        chatBranches.branchFor(project, chatId).cloneDir.isDirectory
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_keep)) { checkpoints.acceptAll(checkpointId) } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
        if (isolated) commitChatBranch(chatId, commitMsg) else commitProjectRepo(commitMsg)
    }
}

/** Compacts older messages of the active chat into a single summary turn, keeping the last 4 messages intact and resetting the CLI session ID so the next turn uses a lean context window. */
fun MainViewModel.compactActiveChat() {
    val project = _state.value.activeProject ?: return
    val chatId = _state.value.activeChatId
    if (runs.containsKey(runKey(project.id, chatId))) return
    val current = currentMessagesOf(project.id, chatId).filterNot { it.id == "streaming-assistant" }
    if (current.size <= 4) {
        _state.update { it.copy(bannerMessage = UiText.Raw("Context is already compact (${current.size} messages)")) }
        return
    }
    val older = current.dropLast(4)
    val recent = current.takeLast(4)
    val summaryBullets = older.takeLast(12).joinToString("\n") { msg ->
        val role = if (msg.fromUser) "User" else "Agent"
        val snippet = msg.text.replace(Regex("\\s+"), " ").trim().take(160).ifEmpty { "(tool/attachment turn)" }
        "- **$role**: $snippet"
    }
    val summaryMsg = ChatMessage(
        fromUser = false,
        text = "📦 **Compacted Context Summary (${older.size} earlier messages)**:\n$summaryBullets",
    )
    val compacted = listOf(summaryMsg) + recent
    AgentKind.entries.forEach { agent ->
        preferences.saveAgentConversation(agent, project.id, chatId, null)
    }
    preferences.saveMessages(project.id, chatId, compacted)
    _state.update {
        it.copy(
            messages = compacted,
            retryPrompts = it.retryPrompts - chatId,
            bannerMessage = UiText.Raw("Compacted ${older.size} messages into summary"),
        )
    }
}

/** Handles quick slash commands (/build, /branch, /diff, /terminal, /clear, /memory, /review, /compact) typed in the chat composer, including IME-duplicated `/cmd/cmd` forms. */
internal fun MainViewModel.handleQuickSlashCommand(text: String): Boolean {
    val raw = text.trim().lowercase()
    // Deduplicate accidental IME composing repetition such as `/compact/compact` -> `/compact`.
    val cmd = Regex("""^(/[a-z0-9_-]+)\1+$""").matchEntire(raw)?.groupValues?.get(1) ?: raw
    val chatId = _state.value.activeChatId
    return when (cmd) {
        "/build" -> {
            _state.update { it.copy(activeTab = ForgeTab.ANDROID) }
            runAndroidBuild("assembleDebug", installAfterBuild = true)
            true
        }
        "/branch" -> {
            enableChatBranch(chatId)
            true
        }
        "/diff", "/files" -> {
            selectProjectPane(ProjectPane.FILES)
            true
        }
        "/terminal" -> {
            selectProjectPane(ProjectPane.TERMINAL)
            true
        }
        "/memory" -> {
            openProjectMemory()
            true
        }
        "/review" -> {
            reviewChangesWithSecondAgent()
            true
        }
        "/compact" -> {
            compactActiveChat()
            true
        }
        "/clear" -> {
            clearActiveChat()
            true
        }
        else -> false
    }
}

/** `/loop 30m prompt` creates a task in this chat, `/loop off` removes this chat's tasks. True when the text was a command. */
internal fun MainViewModel.handleLoopCommand(text: String): Boolean {
    val match = Regex("""^/loop\s+(\S+)(?:\s+([\s\S]+))?$""", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return false
    val first = match.groupValues[1]
    if (first.equals("off", ignoreCase = true)) {
        val projectId = _state.value.activeProjectId
        val chatId = _state.value.activeChatId
        dropScheduledTasks { it.projectId == projectId && it.chatId == chatId }
        _state.update { it.copy(bannerMessage = UiText.res(R.string.schedule_cleared)) }
        return true
    }
    val minutes = ScheduleRules.parseInterval(first)
    val prompt = match.groupValues[2].trim()
    if (minutes == null || prompt.isEmpty()) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.schedule_usage)) }
        return true
    }
    addScheduledTask(prompt, minutes)
    return true
}

fun MainViewModel.addScheduledTask(prompt: String, minutes: Int) {
    val project = _state.value.activeProject ?: return
    val clean = prompt.trim().take(ScheduleRules.MAX_PROMPT_LENGTH)
    if (clean.isEmpty() || minutes !in ScheduleRules.MIN_INTERVAL_MINUTES..ScheduleRules.MAX_INTERVAL_MINUTES) return
    val task = ScheduledTask(
        projectId = project.id, chatId = _state.value.activeChatId, prompt = clean,
        intervalMinutes = minutes, nextRunAtMillis = System.currentTimeMillis() + minutes * 60_000L,
    )
    saveScheduled(_state.value.scheduledTasks + task)
    _state.update { it.copy(bannerMessage = UiText.res(R.string.schedule_added, ScheduleRules.label(minutes))) }
}

fun MainViewModel.removeScheduledTask(id: String) = dropScheduledTasks { it.id == id }

fun MainViewModel.setScheduledTaskEnabled(id: String, enabled: Boolean) {
    val now = System.currentTimeMillis()
    saveScheduled(_state.value.scheduledTasks.map {
        // Turning a task back on waits one full interval instead of firing at once for all the time it was off.
        if (it.id == id) it.copy(enabled = enabled, nextRunAtMillis = if (enabled) now + it.intervalMinutes * 60_000L else it.nextRunAtMillis) else it
    })
}

fun MainViewModel.setScheduleEnabled(enabled: Boolean) {
    preferences.scheduleEnabled = enabled
    _state.update { it.copy(scheduleEnabled = enabled) }
}

// --- Routing of messages between agents (single agent unless the user picked another mode in the chat) ---

fun MainViewModel.setRouteMode(mode: RouteMode) {
    updateRouting(_state.value.routing.copy(mode = mode))
    if (mode != RouteMode.SINGLE) refreshReadyAgents()
}

/** [agents] is the priority/rotation order; an agent left out of the list is not used by Failover or Balanced. */
fun MainViewModel.setRouteAgents(agents: List<AgentKind>) = updateRouting(_state.value.routing.copy(agents = agents.distinct()))

/** Balanced mode: [weight] (0..9) is the agent's share of messages relative to the others; equal weights are 50/50. */
fun MainViewModel.setRouteWeight(agent: AgentKind, weight: Int) {
    val current = _state.value.routing
    updateRouting(current.copy(weights = current.weights + (agent to weight.coerceIn(0, RoutingConfig.MAX_WEIGHT))))
}

/** Adds a rule: messages containing one of the comma-separated [keywords] go to [agent]. Returns false for no keywords. */
fun MainViewModel.addRouteRule(keywords: String, agent: AgentKind): Boolean {
    val words = PromptRule.parseKeywords(keywords)
    if (words.isEmpty()) return false
    val current = _state.value.routing
    updateRouting(current.copy(rules = current.rules + PromptRule(words, agent)))
    return true
}

fun MainViewModel.removeRouteRule(index: Int) {
    val current = _state.value.routing
    if (index in current.rules.indices) updateRouting(current.copy(rules = current.rules.filterIndexed { i, _ -> i != index }))
}

/** Balanced mode: every agent weighs the same (50/50 with two agents). */
fun MainViewModel.resetRouteWeights() = updateRouting(_state.value.routing.copy(weights = emptyMap()))

internal fun MainViewModel.updateRouting(config: RoutingConfig) {
    preferences.routing = config
    // Routing is per chat: saving only the global copy let one chat's mode leak into every other chat.
    val here = _state.value
    if (here.activeProjectId.isNotBlank()) preferences.saveChatRouting(here.activeProjectId, here.activeChatId, config)
    _state.update { it.copy(routing = config) }
}

/** Checks off the main thread which agents have credentials (it reads the vault and Claude's login file). */
fun MainViewModel.refreshReadyAgents() {
    viewModelScope.launch {
        val ready = withContext(Dispatchers.IO) { AgentKind.entries.filter { agentReady(it) }.toSet() }
        _state.update { it.copy(readyAgents = ready) }
    }
}

/** Reads every agent's profile off the main thread (the vault is touched for the key check). */
fun MainViewModel.refreshAgentProfiles() {
    viewModelScope.launch {
        val profiles = withContext(Dispatchers.IO) { AgentKind.entries.associateWith { agentProfile(it) } }
        _state.update { it.copy(agentProfiles = profiles) }
    }
}

/**
 * Sets the model of [agent], which need not be the selected one: with several agents in a chat each answers with its
 * own model. Antigravity keeps its model (and effort) globally; the others keep a profile per agent.
 */
fun MainViewModel.setAgentModel(agent: AgentKind, modelId: String) {
    if (agent == _state.value.agentKind) {
        updateProviderConfig(baseUrl = _state.value.providerProfile.baseUrl, model = modelId)
    } else if (agent == AgentKind.ANTIGRAVITY) {
        selectAntigravityModel(modelId)
    } else {
        val stored = preferences.loadProvider(vault, agent)
        preferences.saveAgentProfile(stored.copy(model = modelId.trim()), agent)
    }
    refreshAgentProfiles()
}

internal fun MainViewModel.agentProfile(agent: AgentKind): ProviderProfile {
    if (agent == _state.value.agentKind) return _state.value.providerProfile
    val loaded = preferences.loadProvider(vault, agent)
    return loaded.copy(hasSecret = !resolveProviderSecret(loaded).isNullOrBlank())
}

/**
 * Whether [agent] can run: Claude needs a key or its subscription login (the login file belongs to root, so it is
 * checked with su), Antigravity its own login, DeepSeek a key. Runs su, so call it off the main thread only.
 */
internal fun MainViewModel.agentReady(agent: AgentKind): Boolean = when (agent) {
    AgentKind.CLAUDE_CODE -> agentProfile(agent).hasSecret || claudeAuth.hasCredential()
    AgentKind.ANTIGRAVITY ->
        antigravityAuth.state.value.status == com.nextcraft.forge.runtime.AntigravityAuthStatus.SIGNED_IN ||
            antigravityAuth.hasOfficialCredential()
    AgentKind.DEEPSEEK_HARNESS -> agentProfile(agent).hasSecret
}

/** The routing decision for [text]; null when no agent is left (all failed or none has credentials). */
internal fun MainViewModel.routeFor(text: String, tried: Set<AgentKind> = emptySet()): RoutedMessage? {
    val here = _state.value
    // Readiness is checked off the main thread (refreshReadyAgents); until the first check only the selected agent counts.
    val ready = here.readyAgents.ifEmpty { setOf(here.agentKind) }
    return AgentRouter.route(text, here.routing, here.agentKind, ready, preferences.routeCounter, tried)
}

/** The agent a new message would most likely go to; only used to decide whether a run slot is free. */
internal fun MainViewModel.tentativeAgent(text: String): AgentKind =
    if (_state.value.routing.mode == RouteMode.SINGLE) _state.value.agentKind else routeFor(text)?.agent ?: _state.value.agentKind

