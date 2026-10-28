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

/**
 * Runs a workspace/checkpoint operation on IO and turns a failure into a banner. These throw for paths the agent
 * turned into symlinks or that escape the workspace, and for IO errors; uncaught in viewModelScope that kills
 * the app.
 */
internal suspend fun <T> MainViewModel.workspaceIo(failure: String, block: () -> T): T? = try {
    withContext(Dispatchers.IO) { block() }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Log.w("MainViewModel", failure, e)
    _state.update { it.copy(bannerMessage = UiText.Raw("$failure: ${e.message ?: e.javaClass.simpleName}")) }
    null
}


/**
 * `build` is hidden from the file tree, but the way down to `build/outputs/apk` stays open
 * so a freshly built APK can be found (and installed from the row menu).
 */
internal fun MainViewModel.isApkOutputPath(ws: File, dir: File): Boolean {
    val parts = dir.relativeTo(ws).invariantSeparatorsPath.split('/')
    val at = parts.indexOf("build")
    if (at < 0) return false
    if (parts.take(at).any { it in setOf(".git", ".gradle", "node_modules", "__pycache__") }) return false
    val below = parts.drop(at + 1)
    return below.isEmpty() || (below.size <= 3 && below[0] == "outputs" && (below.size == 1 || below[1] == "apk"))
}

/**
 * Returns the active chat's isolated branch directory when that chat is isolated and its clone exists on disk,
 * otherwise the project's main workspace directory.
 */
internal fun MainViewModel.activeWorkspaceRoot(project: Project, chatId: String = _state.value.activeChatId): File {
    val chat = if (_state.value.activeProjectId == project.id) {
        _state.value.chats.firstOrNull { it.id == chatId }
    } else {
        preferences.loadProjectChats(project.id).firstOrNull { it.id == chatId }
    }
    if (chat?.isolated == true) {
        val cloneDir = chatBranches.branchFor(project, chatId).cloneDir
        if (cloneDir.isDirectory) return cloneDir
    }
    return checkpoints.ensureWorkspace(project.id)
}

/**
 * Returns the checkpoint project ID for [project] and [chatId] (the isolated branch's [ChatBranch.pseudoProjectId]
 * when that branch clone exists on disk, otherwise [Project.id]).
 */
internal fun MainViewModel.activeCheckpointProjectId(project: Project, chatId: String = _state.value.activeChatId): String {
    val chat = if (_state.value.activeProjectId == project.id) {
        _state.value.chats.firstOrNull { it.id == chatId }
    } else {
        preferences.loadProjectChats(project.id).firstOrNull { it.id == chatId }
    }
    if (chat?.isolated == true) {
        val branch = chatBranches.branchFor(project, chatId)
        if (branch.cloneDir.isDirectory) return branch.pseudoProjectId
    }
    return project.id
}

/**
 * Returns the workspace directory and guest `/workspace/<slug>` name for [project] and [chatId] (the isolated
 * branch's clone directory and slug when present on disk, otherwise the main project workspace and slug).
 */
internal fun MainViewModel.activeWorkspaceTarget(project: Project, chatId: String = _state.value.activeChatId): Pair<File, String> {
    val chat = if (_state.value.activeProjectId == project.id) {
        _state.value.chats.firstOrNull { it.id == chatId }
    } else {
        preferences.loadProjectChats(project.id).firstOrNull { it.id == chatId }
    }
    if (chat?.isolated == true) {
        val branch = chatBranches.branchFor(project, chatId)
        if (branch.cloneDir.isDirectory) return branch.cloneDir to branch.slug
    }
    return checkpoints.ensureWorkspace(project.id) to project.slug
}

/**
 * Resolves [relativePath] inside the active chat's isolated branch first (if it exists there), falling back
 * to the project's main workspace directory.
 */
internal fun MainViewModel.resolveWorkspaceFile(project: Project, relativePath: String, chatId: String = _state.value.activeChatId): File {
    val clean = relativePath.trim().trimStart('/')
    val primaryRoot = activeWorkspaceRoot(project, chatId)
    val primary = checkpoints.safeWorkspaceFile(primaryRoot, clean)
    if (primary.exists()) return primary
    val fallbackRoot = checkpoints.ensureWorkspace(project.id)
    if (fallbackRoot != primaryRoot) {
        val fallback = checkpoints.safeWorkspaceFile(fallbackRoot, clean)
        if (fallback.exists()) return fallback
    }
    return primary
}

/** Finds a built debug APK in the active chat's isolated branch or in the main project workspace. */
internal fun MainViewModel.findExistingProjectApk(project: Project, chatId: String = _state.value.activeChatId): File? {
    val primaryRoot = activeWorkspaceRoot(project, chatId)
    findAndroidProjectRoot(primaryRoot)?.let(::findDebugApk)?.let { return it }
    val mainWs = checkpoints.ensureWorkspace(project.id)
    if (mainWs != primaryRoot) {
        findAndroidProjectRoot(mainWs)?.let(::findDebugApk)?.let { return it }
    }
    // Also scan any isolated chat branches of this project in case the APK was built in another chat tab.
    val chats = if (_state.value.activeProjectId == project.id) _state.value.chats else preferences.loadProjectChats(project.id)
    for (chat in chats) {
        if (chat.isolated && chat.id != chatId) {
            val cloneDir = chatBranches.branchFor(project, chat.id).cloneDir
            if (cloneDir.isDirectory) {
                findAndroidProjectRoot(cloneDir)?.let(::findDebugApk)?.let { return it }
            }
        }
    }
    return null
}

/** Refreshes the file list of the open project (the REA file picker reads it). */
fun MainViewModel.refreshWorkspaceFiles() {
    _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
}

fun MainViewModel.refreshWorkspaceFilesAndChanges(projectId: String) {
    val requestId = ++workspaceRefreshRequest
    viewModelScope.launch {
        val (entries, changes) = workspaceIo(str(R.string.err_ws_read)) {
            val proj = _state.value.projects.firstOrNull { it.id == projectId }
            val ws = if (proj != null) activeWorkspaceRoot(proj) else checkpoints.ensureWorkspace(projectId)
            val rootCanonical = runCatching { ws.canonicalFile.toPath() }.getOrNull()
            val ignored = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")
            val files = if (rootCanonical == null) {
                emptyList()
            } else {
                ws.walkTopDown()
                    .onEnter { dir ->
                        dir == ws || (
                            (dir.name !in ignored || isApkOutputPath(ws, dir)) &&
                                !Files.isSymbolicLink(dir.toPath()) &&
                                runCatching { dir.canonicalFile.toPath().startsWith(rootCanonical) }.getOrDefault(false)
                            )
                    }
                    .maxDepth(5)
                    .filter { it != ws && !Files.isSymbolicLink(it.toPath()) }
                    // The walk is depth-first, so cutting it at 250 hid top-level entries of big projects.
                    // Read a bounded number, then keep the shallowest 250 (the list is sorted for display below).
                    .take(4000)
                    .sortedBy { f -> f.relativeTo(ws).invariantSeparatorsPath.count { it == '/' } }
                    .take(250)
                    .map { f ->
                        val rel = f.relativeTo(ws).invariantSeparatorsPath
                        val depth = rel.count { it == '/' }
                        WorkspaceEntry(
                            path = rel,
                            name = f.name,
                            isDirectory = f.isDirectory,
                            depth = depth,
                            sizeBytes = if (f.isFile) f.length() else 0L,
                        )
                    }
                    .toList()
                    .sortedWith { a, b ->
                        val aParts = a.path.split('/')
                        val bParts = b.path.split('/')
                        val minLen = minOf(aParts.size, bParts.size)
                        var cmp = 0
                        for (i in 0 until minLen) {
                            if (aParts[i] != bParts[i]) {
                                val aDir = (i < aParts.lastIndex) || a.isDirectory
                                val bDir = (i < bParts.lastIndex) || b.isDirectory
                                cmp = if (aDir != bDir) {
                                    if (aDir) -1 else 1
                                } else {
                                    val ci = aParts[i].compareTo(bParts[i], ignoreCase = true)
                                    if (ci != 0) ci else aParts[i].compareTo(bParts[i])
                                }
                                break
                            }
                        }
                        if (cmp != 0) cmp else aParts.size.compareTo(bParts.size)
                    }
            }
            val checkpointId = if (proj != null) activeCheckpointProjectId(proj) else projectId
            val changedPaths = checkpoints.readChangedPaths(checkpointId)
            val changeDetails = if (changedPaths.isNotEmpty()) {
                checkpoints.buildChangeDetails(checkpointId, ws, changedPaths)
            } else {
                emptyList()
            }
            files to changeDetails
        } ?: return@launch
        // A slower walk of another project, or an older walk of this one, must not overwrite the newer result.
        if (requestId != workspaceRefreshRequest || _state.value.activeProjectId != projectId) return@launch
        _state.update {
            it.copy(
                workspaceFiles = entries,
                changedItems = changes,
            )
        }
        // Agent, terminal, revert or git pull may have rewritten the open file; saving the stale copy would undo it.
        _state.value.selectedFilePath?.takeIf { !_state.value.selectedFileDirty }?.let {
            loadWorkspaceFile(it, onlyIfClean = true)
        }
    }
}

/**
 * The direct children of [relativeDir] ("" is the workspace root) in the active project, for the file manager.
 * Unlike [refreshWorkspaceFilesAndChanges] this lists one folder at a time with no depth or entry cap per tree, so a
 * folder's item count is always its real one. Returns null when the folder no longer exists or is outside the
 * workspace. Symbolic links are skipped, like everywhere else in the workspace tools.
 */
suspend fun MainViewModel.listWorkspaceDirectory(relativeDir: String): WorkspaceDirListing? {
    val project = _state.value.activeProject ?: return null
    return withContext(Dispatchers.IO) {
        runCatching {
            val root = activeWorkspaceRoot(project).canonicalFile
            val clean = relativeDir.trim('/')
            if (clean.split('/').any { it == ".." }) return@runCatching null
            val dir = if (clean.isEmpty()) root else File(root, clean).canonicalFile
            if (!dir.toPath().startsWith(root.toPath()) || !dir.isDirectory) return@runCatching null
            val children = dir.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) }
            val entries = children.take(MainViewModel.MAX_DIRECTORY_ENTRIES).map { child ->
                val isDir = child.isDirectory
                WorkspaceEntry(
                    path = child.relativeTo(root).invariantSeparatorsPath,
                    name = child.name,
                    isDirectory = isDir,
                    depth = 0,
                    sizeBytes = if (isDir) 0L else child.length(),
                    childCount = if (isDir) child.list()?.size ?: 0 else null,
                    modifiedMillis = child.lastModified(),
                )
            }
            WorkspaceDirListing(entries, truncated = children.size > MainViewModel.MAX_DIRECTORY_ENTRIES)
        }.getOrNull()
    }
}


/** What to do once the user has answered [UiState.unsavedChangesPrompt]. */

/** Runs [action] now, or first asks to save or discard unsaved edits in the open file. */
internal fun MainViewModel.leaveOpenFile(action: () -> Unit) {
    val current = _state.value
    val path = current.selectedFilePath
    if (path == null || !current.selectedFileDirty || !current.selectedFileEditable) {
        action()
        return
    }
    afterUnsavedChoice = action
    _state.update { it.copy(unsavedChangesPrompt = path) }
}

fun MainViewModel.saveAndContinue() {
    val next = afterUnsavedChoice
    afterUnsavedChoice = null
    _state.update { it.copy(unsavedChangesPrompt = null) }
    saveSelectedFile(onSaved = next)
}

fun MainViewModel.discardAndContinue() {
    val next = afterUnsavedChoice
    afterUnsavedChoice = null
    _state.update { it.copy(unsavedChangesPrompt = null, selectedFileDirty = false) }
    next?.invoke()
}

fun MainViewModel.cancelLeavingFile() {
    afterUnsavedChoice = null
    _state.update { it.copy(unsavedChangesPrompt = null) }
}

/** Opens one chat of any project directly (used by the Dynamic Multi-Agent Live Activity HUD). */
fun MainViewModel.openProjectChat(projectId: String, chatId: String) = leaveOpenFile {
    if (_state.value.activeProjectId != projectId) {
        selectProject(projectId)
    }
    selectChat(chatId)
    _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
}

/** Opens a project's Android Build screen directly (used by the Dynamic Multi-Agent Live Activity HUD). */
fun MainViewModel.openProjectBuild(projectId: String) = leaveOpenFile {
    if (_state.value.activeProjectId != projectId) {
        selectProject(projectId)
    }
    _state.update { it.copy(activeTab = ForgeTab.ANDROID) }
}

/** Opens a project from the project list, asking about unsaved edits first; shows the agent only if it switched. */
fun MainViewModel.openProject(projectId: String) = leaveOpenFile {
    selectProject(projectId)
    if (_state.value.activeProjectId == projectId) {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
    }
}

/** Opens a workspace in the Tools file manager, asking about unsaved edits in the current file first. */
fun MainViewModel.openWorkspaceInFileManager(projectId: String, onOpened: () -> Unit) = leaveOpenFile {
    selectProject(projectId)
    if (_state.value.activeProjectId == projectId) onOpened()
}

/** Switches the in-project bottom bar; Files and Preview refresh their data like the global tabs do. */
fun MainViewModel.selectProjectPane(pane: ProjectPane) {
    _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectPane = pane) }
    refreshPaneData(pane)
}

/** Two-pane layout: the workspace pane appears beside the chat without changing [ForgeUiState.projectPane]. */
fun MainViewModel.onSidePaneShown(pane: ProjectPane) = refreshPaneData(pane)

internal fun MainViewModel.refreshPaneData(pane: ProjectPane) {
    if (pane == ProjectPane.FILES) _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
    if (pane == ProjectPane.PREVIEW) scanLocalPreviewPorts()
}

/** Shows the active chat's conversation under the Projects tab. */
fun MainViewModel.openConversation() {
    _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
}

/** Opens one chat of the active project and shows its conversation. */
fun MainViewModel.openChat(chatId: String) {
    selectChat(chatId)
    openConversation()
}

/** One step up inside the Projects tab; false when already on the project list. */
fun MainViewModel.projectBack(paneBesideChat: Boolean = false): Boolean {
    if (_state.value.projectPane != ProjectPane.CHAT) {
        _state.update { it.copy(projectPane = ProjectPane.CHAT) }
        // Two-pane layout: the chat is already visible, so closing the pane would look like a no-op Back.
        if (!paneBesideChat) return true
    }
    val next = when (_state.value.projectView) {
        // The chat list is a drawer inside the conversation now, so Back leaves the project.
        ProjectView.CONVERSATION, ProjectView.CHATS -> ProjectView.LIST
        ProjectView.LIST -> return false
    }
    _state.update { it.copy(projectView = next) }
    return true
}

/** Opens [relativePath] in the Files editor (from the read-only code viewer's Edit action). */
fun MainViewModel.openFilesTabForEditing(relativePath: String) {
    openWorkspaceFile(relativePath)
    if (_state.value.activeProjectId.isNotBlank()) {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.FILES) }
    } else {
        selectTab(ForgeTab.FILES)
    }
}

fun MainViewModel.openWorkspaceFile(relativePath: String) {
    if (relativePath == _state.value.selectedFilePath) {
        // Tapping the file that is already open keeps its unsaved edits; otherwise it is re-read from disk.
        if (!_state.value.selectedFileDirty) loadWorkspaceFile(relativePath)
        return
    }
    leaveOpenFile { loadWorkspaceFile(relativePath) }
}

/** Loads [relativePath] into the editor, replacing whatever is open (callers decide about unsaved edits). */
internal fun MainViewModel.loadWorkspaceFile(relativePath: String, onlyIfClean: Boolean = false) {
    val project = _state.value.activeProject ?: return
    val requestId = ++openFileRequest
    viewModelScope.launch {
        // Saving a truncated, binary or unreadable file back as text would destroy it, so those open read-only.
        val (content, editable) = withContext(Dispatchers.IO) {
            runCatching {
                val target = resolveWorkspaceFile(project, relativePath)
                if (!target.isFile) {
                    "" to true
                } else if (isImageFilePath(relativePath) || isVideoFilePath(relativePath)) {
                    "" to false
                } else {
                    val head = ByteArray(8192)
                    val headCount = target.inputStream().use { it.read(head) }
                    val binary = (0 until maxOf(headCount, 0)).any { head[it] == 0.toByte() }
                    if (binary || target.length() > 256 * 1024) {
                        "[Read-only: binary file or larger than 256 KB]" to false
                    } else {
                        target.readBoundedText(256 * 1024) to true
                    }
                }
            }.getOrDefault("[Read-only: file could not be read]" to false)
        }
        // A slower earlier read must not overwrite the file the user opened afterwards, nor show up (and later be
        // saved) in another project the user switched to meanwhile.
        if (requestId != openFileRequest || _state.value.activeProjectId != project.id) return@launch
        // A background reload must never replace what the user has started typing meanwhile.
        if (onlyIfClean && (_state.value.selectedFileDirty || _state.value.selectedFilePath != relativePath)) return@launch
        _state.update {
            it.copy(
                selectedFilePath = relativePath,
                selectedFileContent = content,
                selectedFileEditable = editable,
                selectedFileDirty = false,
                fileSavedBanner = null,
            )
        }
    }
}

fun MainViewModel.updateSelectedFileContent(newContent: String) {
    _state.update {
        it.copy(
            selectedFileContent = newContent,
            selectedFileDirty = true,
            fileSavedBanner = null,
        )
    }
}

fun MainViewModel.saveSelectedFile(onSaved: (() -> Unit)? = null) {
    val project = _state.value.activeProject ?: return
    val path = _state.value.selectedFilePath ?: return
    if (!_state.value.selectedFileEditable) {
        _state.update { it.copy(fileSavedBanner = str(R.string.msg_file_readonly, path)) }
        return
    }
    val content = _state.value.selectedFileContent
    viewModelScope.launch {
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
                val target = checkpoints.safeWorkspaceFile(ws, path)
                target.parentFile?.mkdirs()
                target.writeText(content)
                true
            }.getOrDefault(false)
        }
        _state.update {
            it.copy(
                // Only clear dirty when the editor still holds what was written; newer typing stays unsaved.
                selectedFileDirty = if (saved && it.selectedFilePath == path && it.selectedFileContent == content) {
                    false
                } else {
                    it.selectedFileDirty
                },
                fileSavedBanner = if (saved) str(R.string.msg_file_saved_path, path) else str(R.string.msg_file_save_failed_path, path),
            )
        }
        refreshWorkspaceFilesAndChanges(project.id)
        if (saved && preferences.autoSyncGitHub) {
            syncProjectWithGitHub(project.id, "Update $path")
        }
        if (saved) onSaved?.invoke()
    }
}

fun MainViewModel.createNewWorkspaceFile(relativePath: String, initialContent: String = "") {
    val project = _state.value.activeProject ?: return
    val cleanPath = relativePath.trim().trimStart('/')
    if (cleanPath.isEmpty()) return
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
                val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                target.parentFile?.mkdirs()
                if (!target.exists()) target.writeText(initialContent)
                true
            }.getOrDefault(false)
        }
        if (ok) {
            refreshWorkspaceFilesAndChanges(project.id)
            openWorkspaceFile(cleanPath)
        }
    }
}

/** Creates an empty folder inside the workspace. */
fun MainViewModel.createWorkspaceFolder(relativePath: String) {
    val project = _state.value.activeProject ?: return
    val cleanPath = relativePath.trim().trimStart('/').trimEnd('/')
    if (cleanPath.isEmpty()) return
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching { checkpoints.safeWorkspaceFile(activeWorkspaceRoot(project), cleanPath).mkdirs() }.getOrDefault(false)
        }
        if (ok) refreshWorkspaceFilesAndChanges(project.id)
    }
}

/** Renames a file or folder in place (same parent); the open editor follows the file. */
fun MainViewModel.renameWorkspaceEntry(relativePath: String, newName: String) {
    val project = _state.value.activeProject ?: return
    val cleanPath = relativePath.trim().trimStart('/')
    val name = newName.trim()
    // Every refusal says so: the dialog closes either way, so silence looked like a rename that did nothing.
    if (runs.keys.any { it.projectId == project.id } || cleanPath.isEmpty() || name.isEmpty() || name.contains('/') || name == "." || name == "..") {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_rename_failed)) }
        return
    }
    val parent = cleanPath.substringBeforeLast('/', "")
    val newPath = if (parent.isEmpty()) name else "$parent/$name"
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
                val from = checkpoints.safeWorkspaceFile(ws, cleanPath)
                val to = checkpoints.safeWorkspaceFile(ws, newPath)
                from.exists() && !to.exists() && from.renameTo(to)
            }.getOrDefault(false)
        }
        if (!ok) _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_rename_failed)) }
        if (ok) {
            // Also when a folder holding the open file was renamed: a stale path would make the next save
            // recreate the old folder with the file in it.
            val open = _state.value.selectedFilePath
            if (open == cleanPath) {
                _state.update { it.copy(selectedFilePath = newPath) }
            } else if (open != null && open.startsWith("$cleanPath/")) {
                _state.update { it.copy(selectedFilePath = newPath + open.removePrefix(cleanPath)) }
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }
}

/** Copies picked documents (any type) into [targetDir] of the workspace; names are de-duplicated. */
fun MainViewModel.importFilesToWorkspace(uris: List<android.net.Uri>, targetDir: String = "") {
    val project = _state.value.activeProject ?: return
    if (uris.isEmpty()) return
    val dir = targetDir.trim().trimStart('/').trimEnd('/')
    viewModelScope.launch {
        val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, dir) }
        if (copied.isNotEmpty()) {
            val msg = quantity(R.plurals.msg_files_uploaded, copied.size)
            _state.update { it.copy(fileSavedBanner = msg, bannerMessage = UiText.Raw(msg)) }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }
}

/** Picks files for the next prompt: stored under attachments/ so the agent can read them. */
fun MainViewModel.attachFilesToPrompt(uris: List<android.net.Uri>) {
    val project = _state.value.activeProject ?: return
    if (uris.isEmpty()) return
    viewModelScope.launch {
        val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, "attachments") }
        if (copied.isNotEmpty()) {
            _state.update { it.copy(pendingAttachments = it.pendingAttachments + copied) }
        }
    }
}
