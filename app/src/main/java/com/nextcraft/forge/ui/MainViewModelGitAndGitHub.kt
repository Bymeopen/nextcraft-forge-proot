package com.nextcraft.forge.ui

import androidx.core.net.toUri
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

/** One commit per finished turn, then a fresh status for the branch card. */
internal fun MainViewModel.commitBranchTurn(run: ChatRun) {
    val project = projectOf(run.projectId) ?: return
    viewModelScope.launch {
        branchLock(run.projectId, run.chatId).withLock {
            runCatching { chatBranches.commitTurn(project, run.chatId, run.lastPrompt.text, { notifyGitWaitingInQueue() }) }
                .onFailure { Log.w("MainViewModel", "Could not commit the chat branch", it) }
        }
        if (_state.value.activeProjectId == run.projectId) {
            refreshBranchStatus(run.chatId)
        }
    }
}

/** Clears pending checkpoint manifests for the main project and any isolated chat branches that are already synchronized (0 uncommitted & 0 ahead). */
internal suspend fun MainViewModel.acceptSyncedCheckpoints(project: Project, includeMain: Boolean = false) {
    withContext(Dispatchers.IO) {
        if (includeMain) {
            runCatching { checkpoints.acceptAll(project.id) }
        }
        val chats = if (_state.value.activeProjectId == project.id) _state.value.chats else preferences.loadProjectChats(project.id)
        for (chat in chats) {
            if (!chat.isolated) continue
            val st = _state.value.branchStatus[chat.id]
            if (st != null && st.commits == 0 && st.uncommitted == 0 && st.conflicts.isEmpty()) {
                runCatching { checkpoints.acceptAll(chatBranches.branchFor(project, chat.id).pseudoProjectId) }
            }
        }
    }
}

fun MainViewModel.refreshBranchStatus(chatId: String) {
    val project = _state.value.activeProject ?: return
    viewModelScope.launch {
        val status = runCatching { chatBranches.status(project, chatId) }.getOrNull()
        if (status != null && status.commits == 0 && status.uncommitted == 0 && status.conflicts.isEmpty() &&
            !runs.containsKey(runKey(project.id, chatId))
        ) {
            val cpId = chatBranches.branchFor(project, chatId).pseudoProjectId
            withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(cpId) } }
            if (_state.value.activeProjectId == project.id && _state.value.activeChatId == chatId && _state.value.changedItems.isNotEmpty()) {
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
        if (_state.value.activeProjectId == project.id) {
            _state.update { st ->
                st.copy(branchStatus = if (status != null) st.branchStatus + (chatId to status) else st.branchStatus - chatId)
            }
        }
    }
}

internal fun <T> MainViewModel.withBranchBusy(chatId: String, block: suspend () -> T) {
    val projectId = _state.value.activeProjectId
    if (chatId in _state.value.branchBusy || runs.containsKey(runKey(projectId, chatId))) return
    _state.update { it.copy(branchBusy = it.branchBusy + chatId) }
    viewModelScope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainViewModel", "Branch operation failed", e)
            _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_failed, e.message ?: e.javaClass.simpleName)) }
        } finally {
            _state.update { it.copy(branchBusy = it.branchBusy - chatId) }
        }
    }
}

/** Puts a chat on its own branch from now on (the clone is made at its next turn). */
fun MainViewModel.enableChatBranch(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (runs.containsKey(runKey(project.id, chatId))) return
    val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = true) else it }
    preferences.saveProjectChats(project.id, updated)
    _state.update { it.copy(chats = updated, bannerMessage = UiText.res(R.string.branch_enabled)) }
}

/** Back to the project folder; only when the branch holds nothing that would be lost. */
fun MainViewModel.disableChatBranch(chatId: String) {
    val project = _state.value.activeProject ?: return
    withBranchBusy(chatId) {
        // The cached status may be missing (app restarted) or stale, so ask git again before discarding anything.
        if (branchHoldsUnmergedWork(project, chatId)) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_unmerged)) }
            return@withBranchBusy
        }
        chatBranches.discard(project, chatId, { notifyGitWaitingInQueue() })
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId) }
    }
}


/** Loads the project repository and the state of every chat branch (the local pull requests). */
fun MainViewModel.refreshProjectRepo() {
    val project = _state.value.activeProject ?: return
    _state.update { it.copy(repoBusy = true) }
    viewModelScope.launch {
        val info = runCatching { chatBranches.repoInfo(project) }.getOrNull()
        if (info != null && info.isRepo && info.uncommitted == 0 &&
            runs.values.none { it.projectId == project.id && !it.isolated }
        ) {
            withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(project.id) } }
            val activeChat = _state.value.chats.firstOrNull { it.id == _state.value.activeChatId }
            if (activeChat?.isolated != true && _state.value.changedItems.isNotEmpty()) {
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
        _state.update { st -> st.copy(projectRepo = info ?: st.projectRepo, repoBusy = false) }
        _state.value.chats.filter { it.isolated }.forEach { refreshBranchStatus(it.id) }
    }
}

/** Notifies the user when a Git operation is waiting in queue behind another Git process in the same repository. */
internal fun MainViewModel.notifyGitWaitingInQueue() {
    _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_git_waiting_queue)) }
}

/** Runs a branch operation on the project repository; refused while a chat writes in the project folder. */
internal fun MainViewModel.repoBranchOp(success: Int, op: suspend (Project) -> RepoOpResult) {
    val project = _state.value.activeProject ?: return
    if (_state.value.repoBusy) return
    if (runs.values.any { it.projectId == project.id && !it.isolated }) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.repo_busy_agent)) }
        return
    }
    _state.update { it.copy(repoBusy = true) }
    viewModelScope.launch {
        val result = runCatching { op(project) }.getOrElse { RepoOpResult.Failed(it.message ?: it.javaClass.simpleName) }
        val banner = when (result) {
            RepoOpResult.Done -> UiText.res(success)
            RepoOpResult.Dirty -> UiText.res(R.string.repo_branch_dirty)
            is RepoOpResult.Failed -> UiText.res(R.string.branch_failed, result.message)
        }
        _state.update { it.copy(repoBusy = false, bannerMessage = banner) }
        refreshProjectRepo()
        if (result == RepoOpResult.Done) {
            refreshWorkspaceFilesAndChanges(project.id)
            _state.value.selectedFilePath?.let { loadWorkspaceFile(it) }
        }
    }
}

fun MainViewModel.createRepoBranch(name: String) = repoBranchOp(R.string.repo_branch_created) { chatBranches.createBranch(it, name, { notifyGitWaitingInQueue() }) }

fun MainViewModel.switchRepoBranch(name: String) = leaveOpenFile { repoBranchOp(R.string.repo_branch_switched) { chatBranches.switchBranch(it, name, { notifyGitWaitingInQueue() }) } }

fun MainViewModel.deleteRepoBranch(name: String) = repoBranchOp(R.string.repo_branch_deleted) { chatBranches.deleteBranch(it, name, { notifyGitWaitingInQueue() }) }

/** Commits everything in the project folder with the user's message. */
fun MainViewModel.commitProjectRepo(message: String) {
    val project = _state.value.activeProject ?: return
    val clean = message.trim()
    if (clean.isEmpty() || _state.value.repoBusy) return
    // A chat that works in the project folder itself may be mid-write; its files would be committed half done.
    if (runs.values.any { it.projectId == project.id && !it.isolated }) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.repo_busy_agent)) }
        return
    }
    _state.update { it.copy(repoBusy = true) }
    viewModelScope.launch {
        val result = runCatching { chatBranches.commitProject(project, clean, { notifyGitWaitingInQueue() }) }
        val failure = result.exceptionOrNull()?.message ?: result.getOrNull()?.takeIf { !it.ok }?.output?.trim()?.takeLast(200)
        if (failure == null) {
            acceptSyncedCheckpoints(project, includeMain = true)
            refreshWorkspaceFilesAndChanges(project.id)
        }
        _state.update {
            it.copy(
                repoBusy = false,
                bannerMessage = if (failure == null) UiText.res(R.string.repo_committed) else UiText.res(R.string.branch_failed, failure),
            )
        }
        refreshProjectRepo()
    }
}

/** Commits what the chat's branch holds right now with the user's own message. */
fun MainViewModel.commitChatBranch(chatId: String, message: String) {
    val project = _state.value.activeProject ?: return
    val clean = message.trim()
    if (clean.isEmpty()) return
    withBranchBusy(chatId) {
        branchLock(chatId).withLock { chatBranches.commitNow(project, chatId, clean, { notifyGitWaitingInQueue() }) }
        val cpId = chatBranches.branchFor(project, chatId).pseudoProjectId
        withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(cpId) } }
        _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_committed)) }
        if (_state.value.activeChatId == chatId) {
            refreshWorkspaceFilesAndChanges(project.id)
        }
        refreshBranchStatus(chatId)
    }
}

/** Merge and update snapshot the project folder; they must not run while a chat writes in it. */
internal fun MainViewModel.projectFolderBusy(projectId: String): Boolean {
    val busy = runs.values.any { it.projectId == projectId && !it.isolated }
    if (busy) _state.update { it.copy(bannerMessage = UiText.res(R.string.repo_busy_agent)) }
    return busy
}

fun MainViewModel.mergeChatBranch(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (projectFolderBusy(project.id)) return
    val title = _state.value.chats.firstOrNull { it.id == chatId }?.title.orEmpty()
    withBranchBusy(chatId) {
        val result = branchLock(chatId).withLock {
            val saved = chatBranches.commitTurn(project, chatId, "Forge: save work before merge", { notifyGitWaitingInQueue() })
            // Conflict markers still in the branch must be fixed first; merging now would put them in the project.
            if (!saved.ok && saved.output.contains("unresolved conflict")) {
                BranchMergeResult.Failed(saved.output.trim().takeLast(200))
            } else {
                chatBranches.mergeIntoMain(project, chatId, title, { notifyGitWaitingInQueue() })
            }
        }
        val message = when (result) {
            BranchMergeResult.Done -> UiText.res(R.string.branch_merged)
            is BranchMergeResult.Conflict -> UiText.res(R.string.branch_merge_conflict, result.files.joinToString(", ").take(200))
            is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
        }
        _state.update { it.copy(bannerMessage = message) }
        if (result == BranchMergeResult.Done) {
            val cpId = chatBranches.branchFor(project, chatId).pseudoProjectId
            withContext(Dispatchers.IO) {
                runCatching { checkpoints.acceptAll(cpId) }
                runCatching { checkpoints.acceptAll(project.id) }
            }
            refreshWorkspaceFilesAndChanges(project.id)
            refreshProjectRepo()
        }
        refreshBranchStatus(chatId)
    }
}

fun MainViewModel.updateChatBranchFromMain(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (projectFolderBusy(project.id)) return
    withBranchBusy(chatId) {
        val result = branchLock(chatId).withLock { chatBranches.updateFromMain(project, chatId, { notifyGitWaitingInQueue() }) }
        val message = when (result) {
            BranchMergeResult.Done -> UiText.res(R.string.branch_updated)
            is BranchMergeResult.Conflict -> UiText.res(R.string.branch_update_conflict, result.files.joinToString(", ").take(200))
            is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
        }
        if (result == BranchMergeResult.Done) {
            val cpId = chatBranches.branchFor(project, chatId).pseudoProjectId
            withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(cpId) } }
            if (_state.value.activeChatId == chatId) {
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
        _state.update { it.copy(bannerMessage = message) }
        refreshBranchStatus(chatId)
    }
}

fun MainViewModel.discardChatBranch(chatId: String) {
    val project = _state.value.activeProject ?: return
    withBranchBusy(chatId) {
        chatBranches.discard(project, chatId, { notifyGitWaitingInQueue() })
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId, branchDiff = it.branchDiff - chatId) }
    }
}

fun MainViewModel.loadBranchDiff(chatId: String) {
    val project = _state.value.activeProject ?: return
    withBranchBusy(chatId) {
        val text = chatBranches.diff(project, chatId)
        _state.update { it.copy(branchDiff = it.branchDiff + (chatId to text)) }
    }
}

/** Asks the chat's agent to finish a merge that stopped on conflicts inside its branch. */
fun MainViewModel.askAgentToResolveConflicts(chatId: String) {
    val files = _state.value.branchStatus[chatId]?.conflicts.orEmpty()
    if (files.isEmpty()) return
    if (_state.value.activeChatId != chatId) selectChat(chatId)
    if (_state.value.activeChatId != chatId) return
    val text = getApplication<Application>().getString(R.string.branch_resolve_prompt, files.joinToString(", "))
    _state.update { it.copy(pendingAttachments = emptyList()) }
    sendPrompt(text)
}


/**
 * Ensures the GitHub PAT is provisioned in the rootfs (`~/.git-credentials` and `~/.config/gh/hosts.yml`, mode 0600)
 * and serializes git/gh operations through [githubOperationLock] so concurrent background syncs or profile refreshes
 * never clobber credentials mid-push.
 */
internal inline fun <T> MainViewModel.withGitHubCredentials(token: String, login: String, block: () -> T): T =
    githubOperationLock.withLock {
        installer.configureGitHubAccess(token, login, persistCredentials = true)
        block()
    }


fun MainViewModel.startGitHubDeviceSignIn(clientId: String = "") = githubAuth.startDeviceSignIn(clientId)

/** See [com.nextcraft.forge.runtime.GitHubAuthController.resumeDeviceSignIn]. */
fun MainViewModel.resumeGitHubDeviceSignIn() = githubAuth.resumeDeviceSignIn()

fun MainViewModel.connectGitHubWithToken(personalAccessToken: String) = githubAuth.connectWithToken(personalAccessToken)

fun MainViewModel.disconnectGitHub() = githubAuth.disconnect()

fun MainViewModel.createGitHubRepository(
    name: String,
    description: String = "",
    isPrivate: Boolean = true,
    pushActiveProject: Boolean = true,
) {
    val token = vault.get("GITHUB_TOKEN").orEmpty()
    if (token.isBlank()) {
        githubAuth.report(UiText.res(R.string.msg_gh_sign_in_first_repo))
        return
    }
    val cleanName = name.trim()
    if (cleanName.isEmpty()) {
        githubAuth.report(UiText.res(R.string.msg_gh_repo_name_empty))
        return
    }
    viewModelScope.launch {
        githubAuth.report(UiText.res(R.string.msg_gh_creating_repo, cleanName))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val repo = githubClient.createRepository(
                    token = token,
                    name = cleanName,
                    description = description,
                    private = isPrivate,
                    autoInit = false,
                )
                val project = _state.value.activeProject
                var pushError: Throwable? = null
                if (pushActiveProject && project != null) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val login = githubAuth.state.value.login.ifBlank { "x-access-token" }
                    val pushCmd = buildString {
                        append("git init && ")
                        append("git checkout -B main && ")
                        append("git config user.name ${shellSingleQuote(login)} && ")
                        append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                        append("(git remote remove origin 2>/dev/null || true) && ")
                        append("git remote add origin ${shellSingleQuote(repo.cloneUrl)} && ")
                        append("git add -A && ")
                        append("(git diff --cached --quiet || git commit -m 'Initial commit from NextCraft Forge') && ")
                        append("git push -u origin HEAD:main")
                    }
                    pushError = runCatching {
                        withGitHubCredentials(token, login) {
                            runCheckedCommand(project.slug, ws, pushCmd)
                        }
                    }.exceptionOrNull()
                }
                val updatedRepos = runCatching { githubClient.repositories(token) }.getOrDefault(listOf(repo))
                Triple(repo, updatedRepos, pushError)
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        result.onSuccess { (repo, updatedRepos, pushError) ->
            if (pushActiveProject && pushError == null) {
                refreshProjectRepo()
                cicd.refresh()
            }
            githubAuth.publish(
                updatedRepos,
                if (pushError == null) {
                    UiText.res(R.string.msg_gh_created_pushed, repo.fullName)
                } else {
                    UiText.res(R.string.msg_gh_created_push_failed, repo.fullName, pushError.message ?: "")
                },
            )
        }.onFailure { err ->
            githubAuth.report(UiText.res(R.string.msg_gh_create_failed, errText(err)))
        }
    }
}

fun MainViewModel.syncProjectWithGitHub(
    projectId: String = _state.value.activeProjectId,
    commitMessage: String = "Sync project changes from NextCraft Forge",
    reportStatus: Boolean = false,
) {
    val token = vault.get("GITHUB_TOKEN").orEmpty()
    if (token.isBlank()) {
        if (reportStatus) githubAuth.report(UiText.res(R.string.msg_gh_connect_first_pull))
        return
    }
    val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
    viewModelScope.launch {
        if (reportStatus) githubAuth.report(UiText.Raw("${project.name} · GitHub sync…"))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(project.id)
                val gitDir = File(ws, ".git")
                if (!gitDir.isDirectory) {
                    if (reportStatus) error(str(R.string.repo_not_repo)) else return@runCatching ""
                }
                val login = githubAuth.state.value.login.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val syncCmd = buildString {
                    append(com.nextcraft.forge.runtime.ChatBranches.GIT_QUEUE_WAIT_SH).append("\n")
                    append("forge_wait_git_lock\n")
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("branch=${'$'}(git symbolic-ref --short HEAD 2>/dev/null || echo main); ")
                    append("git config user.name ${shellSingleQuote(login)} && ")
                    append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                    append("git add -A && ")
                    append("(git diff --cached --quiet || git commit -m ${shellSingleQuote(commitMessage)}) && ")
                    append("if ! git merge-base --is-ancestor \"origin/${'$'}branch\" HEAD 2>/dev/null; then ")
                    append("(git pull --no-rebase --no-edit --autostash origin \"${'$'}branch\" >/dev/null 2>&1 || git merge --abort >/dev/null 2>&1 || true); ")
                    append("fi && ")
                    append("if ! git push -u origin \"HEAD:${'$'}branch\"; then ")
                    append("git push -f origin \"HEAD:ccr-auto-sync-${'$'}branch\" >/dev/null 2>&1 || true; ")
                    append("fi && ")
                    append("if git remote get-url proot >/dev/null 2>&1; then ")
                    append("git branch -f proot-main HEAD >/dev/null 2>&1 || true; ")
                    append("git push -f proot HEAD:main >/dev/null 2>&1 || true; ")
                    append("fi; ")
                    append("else echo 'No origin remote configured in workspace' >&2; false; fi")
                }
                withGitHubCredentials(token, login) {
                    if (reportStatus) {
                        runCheckedCommand(project.slug, ws, syncCmd)
                    } else {
                        runCommandInChrootOrHost(project.slug, ws, syncCmd)
                    }
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        if (result.isSuccess) {
            acceptSyncedCheckpoints(project, includeMain = true)
        }
        if (_state.value.activeProjectId == project.id) {
            refreshWorkspaceFilesAndChanges(project.id)
            refreshProjectRepo()
        }
        if (reportStatus) {
            githubAuth.report(
                result.fold(
                    onSuccess = { out ->
                        val lastLine = out.lines().lastOrNull { l -> l.isNotBlank() } ?: str(R.string.msg_gh_up_to_date)
                        UiText.Raw("✓ ${project.name}: $lastLine")
                    },
                    onFailure = { err ->
                        UiText.res(R.string.msg_gh_error, errText(err))
                    },
                ),
            )
        }
    }
}

fun MainViewModel.pullProjectFromGitHub(projectId: String = _state.value.activeProjectId) {
    val token = vault.get("GITHUB_TOKEN").orEmpty()
    if (token.isBlank()) {
        githubAuth.report(UiText.res(R.string.msg_gh_connect_first_pull))
        return
    }
    val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
    viewModelScope.launch {
        githubAuth.report(UiText.res(R.string.msg_gh_pulling, project.name))
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(project.id)
                val login = githubAuth.state.value.login.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val pullCmd = buildString {
                    append(com.nextcraft.forge.runtime.ChatBranches.GIT_QUEUE_WAIT_SH).append("\n")
                    append("forge_wait_git_lock\n")
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("branch=${'$'}(git symbolic-ref --short HEAD 2>/dev/null || echo main); ")
                    append("git fetch origin && (git pull --rebase --autostash origin \"${'$'}branch\" || ")
                    append("{ git rebase --abort 2>/dev/null; false; }); ")
                    append("else echo 'No origin remote configured in workspace'; fi")
                }
                withGitHubCredentials(token, login) {
                    runCheckedCommand(project.slug, ws, pullCmd)
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        if (result.isSuccess) {
            acceptSyncedCheckpoints(project, includeMain = true)
        }
        refreshWorkspaceFilesAndChanges(project.id)
        if (_state.value.activeProjectId == project.id) {
            refreshProjectRepo()
        }
        githubAuth.report(
            result.fold(
                onSuccess = { out ->
                    val lastLine = out.lines().lastOrNull { l -> l.isNotBlank() }
                        ?: str(R.string.msg_gh_up_to_date)
                    UiText.res(R.string.msg_gh_pulled, lastLine)
                },
                onFailure = { err ->
                    UiText.res(
                        R.string.msg_gh_pull_failed,
                        errText(err),
                    )
                },
            ),
        )
    }
}

fun MainViewModel.cloneGitHubRepository(repo: GitHubRepository) {
    val token = vault.get("GITHUB_TOKEN").orEmpty()
    viewModelScope.launch {
        githubAuth.report(UiText.res(R.string.msg_gh_cloning, repo.fullName))
        val clonedProject = withContext(Dispatchers.IO) {
            runCatching {
                val cleanName = repo.fullName.substringAfterLast('/').ifBlank { repo.fullName }
                val slug = reserveProjectSlug(projectSlug(cleanName))
                val project = Project(
                    name = cleanName,
                    description = repo.description.ifBlank { "Cloned from ${repo.fullName}" },
                    language = "Git Repository",
                    slug = slug,
                )
                val ws = checkpoints.ensureWorkspace(project.id)
                ws.listFiles()?.forEach { it.deleteRecursivelyNoFollow() }
                val login = githubAuth.state.value.login.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val cloneCmd = "git clone ${shellSingleQuote(repo.cloneUrl)} ."
                try {
                    if (token.isNotBlank()) {
                        withGitHubCredentials(token, login) {
                            runCheckedCommand(project.slug, ws, cloneCmd)
                        }
                    } else {
                        runCheckedCommand(project.slug, ws, cloneCmd)
                    }
                } catch (error: Throwable) {
                    // The project is never saved on failure; don't leave its workspace behind on disk.
                    ws.deleteRecursivelyNoFollow()
                    checkpoints.checkpointDir(project.id).deleteRecursively()
                    synchronized(reservedSlugs) { reservedSlugs -= slug }
                    throw error
                }
                val isAndroidRepo = findAndroidProjectRoot(ws) != null
                project.copy(
                    type = if (isAndroidRepo) ProjectType.ANDROID else ProjectType.GENERAL,
                    language = if (isAndroidRepo) "Kotlin / Android" else "Git Repository",
                )
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        clonedProject.onSuccess { newProject ->
            configureAllProjectRoots(prependProject(newProject))
            githubAuth.report(UiText.res(R.string.msg_gh_cloned, repo.fullName, newProject.name))
            leaveOpenFile { selectProject(newProject.id) }
        }.onFailure { err ->
            githubAuth.report(UiText.res(R.string.msg_gh_clone_failed, errText(err)))
        }
    }
}

/** A throwaway chat workspace (kind QUICK_PROJECT) with a generated name; opens straight into Agent. */
fun MainViewModel.createQuickProject() {
    viewModelScope.launch {
        val project = withContext(Dispatchers.IO) {
            val identity = generateQuickChatIdentity(_state.value.projects.map { it.slug }.toSet(), str(R.string.quick_project_name))
            val project = Project(
                name = identity.displayName,
                description = "Quick project",
                language = "Multi-language",
                slug = identity.slug,
                kind = ProjectKind.QUICK_PROJECT,
            )
            checkpoints.ensureWorkspace(project.id)
            project
        }
        configureAllProjectRoots(prependProject(project))
        leaveOpenFile {
            selectProject(project.id)
            openConversation()
        }
    }
}

/** Clones any http(s) git URL into a fresh workspace project (GitHub token is used only for github.com). */
fun MainViewModel.cloneGitUrl(rawUrl: String) {
    val url = rawUrl.trim()
    val uriScheme = url.toUri().scheme?.lowercase()
    if (url.isEmpty() || (uriScheme != "http" && uriScheme != "https")) {
        githubAuth.report(UiText.res(R.string.msg_git_url_invalid))
        return
    }
    val name = url.trimEnd('/').substringAfterLast('/').removeSuffix(".git").ifBlank { "repository" }
    cloneGitHubRepository(
        GitHubRepository(
            fullName = name,
            cloneUrl = url,
            private = false,
            defaultBranch = "",
            description = "",
            updatedAt = "",
        ),
    )
}


/** Unpacks a user-picked ZIP into a new workspace project named after the file. */
fun MainViewModel.importZipProject(uri: android.net.Uri) {
    lastImportUri = uri
    val app = getApplication<Application>()
    viewModelScope.launch {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val displayName = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                } ?: "import.zip"
                val magic = app.contentResolver.openInputStream(uri)?.use { readUpTo(it, 4) } ?: ByteArray(0)
                val isZip = magic.size == 4 && magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte() &&
                    magic[2] == 0x03.toByte() && magic[3] == 0x04.toByte()
                if (!isZip) throw IllegalArgumentException("$displayName is not a ZIP archive")
                val cleanName = displayName.removeSuffix(".zip").ifBlank { "Imported project" }
                val slug = reserveProjectSlug(projectSlug(cleanName))
                val project = Project(
                    name = cleanName,
                    description = "Imported from $displayName",
                    language = "Multi-language",
                    slug = slug,
                )
                val ws = checkpoints.ensureWorkspace(project.id)
                val root = ws.canonicalFile
                try {
                app.contentResolver.openInputStream(uri)?.use { input ->
                    java.util.zip.ZipInputStream(input).use { zip ->
                        var entry = zip.nextEntry
                        var entries = 0
                        var written = 0L
                        while (entry != null) {
                            // Bound entry count and expanded size so a zip bomb cannot fill the device.
                            if (++entries > MainViewModel.MAX_IMPORT_ENTRIES) throw IllegalArgumentException("Archive has too many files")
                            val target = File(root, entry.name).canonicalFile
                            // Zip-slip guard: refuse entries that escape the workspace.
                            if (!target.path.startsWith(root.path + File.separator) && target != root) {
                                throw IllegalArgumentException("Unsafe zip entry: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                target.mkdirs()
                            } else {
                                target.parentFile?.mkdirs()
                                target.outputStream().use { out ->
                                    val buffer = ByteArray(64 * 1024)
                                    while (true) {
                                        val count = zip.read(buffer)
                                        if (count < 0) break
                                        written += count
                                        if (written > MainViewModel.MAX_IMPORT_BYTES) throw IllegalArgumentException("Archive is larger than ${MainViewModel.MAX_IMPORT_BYTES / (1024 * 1024)} MB when extracted")
                                        out.write(buffer, 0, count)
                                    }
                                }
                            }
                            zip.closeEntry()
                            entry = zip.nextEntry
                        }
                    }
                } ?: throw IllegalStateException("Cannot open $displayName")
                } catch (error: Throwable) {
                    // Nothing registered this workspace yet: remove the half-extracted files and free the slug.
                    runCatching { ws.deleteRecursivelyNoFollow() }
                    synchronized(reservedSlugs) { reservedSlugs -= slug }
                    throw error
                }
                // If the archive unpacked into a single top-level wrapper folder (like GitHub's repo-main.zip),
                // hoist its contents up to the workspace root so tools see files directly at /workspace/<slug>.
                val topEntries = ws.listFiles()?.filter { it.name != "__MACOSX" }.orEmpty()
                if (topEntries.size == 1 && topEntries[0].isDirectory) {
                    val wrapper = topEntries[0]
                    wrapper.listFiles()?.forEach { child ->
                        val dest = File(ws, child.name)
                        if (!dest.exists()) child.renameTo(dest)
                    }
                    if (wrapper.listFiles().isNullOrEmpty()) wrapper.delete()
                }
                val isAndroidProject = findAndroidProjectRoot(ws) != null
                project.copy(
                    type = if (isAndroidProject) ProjectType.ANDROID else ProjectType.GENERAL,
                    language = if (isAndroidProject) "Kotlin / Android" else "Multi-language",
                )
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        result.onSuccess { newProject ->
            configureAllProjectRoots(prependProject(newProject))
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_imported, newProject.name)) }
            leaveOpenFile { selectProject(newProject.id) }
        }.onFailure { err ->
            val failed = UiText.res(R.string.msg_import_failed, errText(err))
            _state.update { it.copy(bannerMessage = failed, bannerAction = BannerAction(BannerActionKind.RETRY_IMPORT, failed)) }
        }
    }
}

/** Reads up to [count] bytes (fewer at end of stream). InputStream.readNBytes needs API 33; the app supports API 31. */
internal fun readUpTo(input: java.io.InputStream, count: Int): ByteArray {
    val buf = ByteArray(count)
    var read = 0
    while (read < count) {
        val n = input.read(buf, read, count - read)
        if (n < 0) break
        read += n
    }
    return if (read == count) buf else buf.copyOf(read)
}
