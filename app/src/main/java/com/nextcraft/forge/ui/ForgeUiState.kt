package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.runtime.BranchStatus
import com.nextcraft.forge.runtime.ClaudeSkill
import com.nextcraft.forge.runtime.McpConnector
import com.nextcraft.forge.runtime.ProjectRepoInfo
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.RoutingConfig
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.WorkspaceEntry
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.providerProtocolForAgent
import com.nextcraft.forge.network.ConnectionValidation
import com.nextcraft.forge.network.DiscoveredModel
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubDeviceCode
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
import com.nextcraft.forge.runtime.sanitizeClaudeAuthOutput
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionService
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.RuntimeLaunchConfigBuilder
import com.nextcraft.forge.runtime.AgentUpdateInfo
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import org.json.JSONObject
import com.nextcraft.forge.runtime.androidGradleCommand
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.hasAndroidLogcatAccess
import com.nextcraft.forge.runtime.inferAndroidBuildStage
import com.nextcraft.forge.runtime.isRootAccessAvailable
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nextcraft.forge.model.generateQuickChatIdentity
import com.nextcraft.forge.R

/** What the Projects tab shows: the project list, the chats of the open project, or one conversation. */
enum class ProjectView { LIST, CHATS, CONVERSATION }

/** The in-project bottom bar: Chat (chats + conversation), Files, Terminal, Preview. */
enum class ProjectPane { CHAT, FILES, TERMINAL, PREVIEW }

enum class ForgeTab(val title: String) {
    PROJECTS("Projects"),
    AGENT("Agent"),
    TOOLS("Tools"),
    FILES("Files"),
    TERMINAL("Terminal"),
    ANDROID("Android"),
    PREVIEW("Preview"),
    SETTINGS("Settings"),
    TOOLCHAINS("Toolchains"),
    DIAGNOSTICS("Diagnostics"),
    BUILD_MATRIX("Build Matrix"),
    SSH("SSH"),
    CICD("CI/CD"),
    ROM("ROM Studio"),
    CHECKPOINTS("Checkpoints"),
    CODE_VIEWER("Code viewer"),
    FILE_BROWSER("File browser"),
    REA("REA"),
    BACKUP("Backup"),
}

enum class SetupScope { RUNTIME, AGENT }

data class ForgeUiState(
    val arm64Supported: Boolean = true,
    val rootAvailable: Boolean = false,
    /** No root, but the APK's PRoot engine can run the runtime (slower; ADR-007). */
    val prootAvailable: Boolean = false,
    /** Stored engine choice id (auto / chroot / proot). */
    val runtimeEngineChoice: String = "auto",
    val runtimeInstalled: Boolean = false,
    val setupSnapshot: RuntimeSetupSnapshot = RuntimeSetupSnapshot(),
    val selectedDevStacks: Set<DevStack> = setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val installedAgents: Set<AgentKind> = emptySet(),
    val agentUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    /** Which Settings card owns the progress of the current runtime operation. */
    val setupScope: SetupScope = SetupScope.RUNTIME,
    val checkingAgentUpdates: Boolean = false,
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val activeTab: ForgeTab = ForgeTab.PROJECTS,
    val projectView: ProjectView = ProjectView.LIST,
    val projectPane: ProjectPane = ProjectPane.CHAT,
    val themeMode: String = "dark",
    val projects: List<Project> = emptyList(),
    val activeProjectId: String = "",
    val chats: List<ProjectChat> = emptyList(),
    val activeChatId: String = "main",
    val messages: List<ChatMessage> = emptyList(),
    /** True while any agent turn runs (workspace-wide operations stay blocked); see [activeChatRunning] for the open chat. */
    val sessionRunning: Boolean = false,
    /** Chats of the active project whose agent turn is running right now. */
    val runningChatIds: Set<String> = emptySet(),
    /** Active agent turn counts by project id across all projects. */
    val runningProjectChatCounts: Map<String, Int> = emptyMap(),
    /** Live snapshot of all currently running agent turns across all projects (for the Dynamic Live Activity HUD). */
    val liveAgentRuns: List<LiveAgentRunInfo> = emptyList(),
    /** Projects currently running an Android APK build. */
    val buildingProjectIds: Set<String> = emptySet(),
    /** Per-project Android build records so background project builds preserve their state. */
    val projectBuilds: Map<String, AndroidBuildRecord> = emptyMap(),
    /** How chat messages pick an agent (single agent unless the user changed it in the chat). */
    val routing: RoutingConfig = RoutingConfig(),
    /** Agents that have credentials; refreshed when the routing sheet opens. */
    val readyAgents: Set<AgentKind> = emptySet(),
    /** Provider profile (kind, model) of every agent, so the model sheet can edit an agent other than the selected one. */
    val agentProfiles: Map<AgentKind, ProviderProfile> = emptyMap(),
    val activeStatusLine: UiText? = null,
    val pendingToolRequest: ToolRequest? = null,
    val changedItems: List<ChangeItem> = emptyList(),
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: String = "",
    val selectedFileEditable: Boolean = true,
    val selectedFileDirty: Boolean = false,
    /** Set while the user is asked whether to save or discard unsaved editor changes before leaving the file. */
    val unsavedChangesPrompt: String? = null,
    val fileSavedBanner: String? = null,
    /** Files picked for the next prompt; copied into the workspace's attachments/ folder. */
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    /** True while the app walks through its screens taking pictures for the agent. */
    val capturingScreens: Boolean = false,
    /** Messages sent while the agent was busy; delivered as the next turn once the current one completes. */
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    /** Bumped when a terminal session is created, ends or is restarted, so the screen re-reads it. */
    val terminalRevision: Int = 0,
    /** Legacy one-shot runner flag, kept for the "busy" guards. The interactive shell is persistent, so it stays false. */
    val terminalRunning: Boolean = false,
    val androidBuild: AndroidBuildRecord = AndroidBuildRecord(),
    val androidLogcat: AndroidLogcatState = AndroidLogcatState(),
    val previewUrl: String = "http://127.0.0.1:3000",
    val activeLocalPorts: List<Int> = emptyList(),
    val providerProfile: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val savedApiKeys: List<com.nextcraft.forge.data.ApiKeyInfo> = emptyList(),
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val apiStatusMessage: UiText? = null,
    val apiBusy: Boolean = false,
    val githubLogin: String = "",
    val githubDeviceCode: GitHubDeviceCode? = null,
    val githubRepos: List<GitHubRepository> = emptyList(),
    val githubStatusMessage: UiText? = null,
    val languageCode: String = "system",
    val interactiveHighRiskApprovals: Boolean = true,
    val autoApproveAllTools: Boolean = false,
    /** Local CI after agent turns (see MainViewModel.maybeVerifyBuild) and its automatic fix rounds. */
    val autoVerifyBuild: Boolean = false,
    val autoFixBuild: Boolean = false,
    val autoSyncGitHub: Boolean = false,
    val gameTemplatesEnabled: Boolean = false,
    val androidHostTools: Boolean = false,
    val autoKeepChanges: Boolean = true,
    /** Download cache sizes in bytes by RuntimeCaches.Kind name; null while not measured. */
    val runtimeCacheSizes: Map<String, Long>? = null,
    val reaChatHint: Boolean = false,
    /** Scheduled prompts (timers that send a message to a chat again) and whether the agent may create them. */
    val scheduledTasks: List<com.nextcraft.forge.schedule.ScheduledTask> = emptyList(),
    val scheduleEnabled: Boolean = true,
    val updateNotifications: Boolean = true,
    val autoInstallUpdates: Boolean = true,
    val autoBranchChats: Boolean = false,
    val antigravityModel: String = "claude-sonnet-5-5-low",
    val antigravityEffort: String = "low",
    val claudeEffort: String = "",
    /** Branch status of the chats that work in their own branch, by chat id. */
    val branchStatus: Map<String, BranchStatus> = emptyMap(),
    /** Chats with a branch operation (merge, update, diff) in progress. */
    val branchBusy: Set<String> = emptySet(),
    /** Last diff text loaded for a branch (chat id to text). */
    val branchDiff: Map<String, String> = emptyMap(),
    /** The open project's own repository (null until the repository panel loads it). */
    val projectRepo: ProjectRepoInfo? = null,
    val repoBusy: Boolean = false,
    val imageGenerating: Boolean = false,
    /** MCP connectors and Claude Code skills (loaded when the connectors sheet opens). */
    val connectors: List<McpConnector> = emptyList(),
    val skills: List<ClaudeSkill> = emptyList(),
    /** Bumped when the Google image key is saved, so the dialog notices it. */
    val geminiKeyVersion: Int = 0,
    /** Model ids reported by `agy models` (empty until refreshed; the picker then offers built-in presets). */
    val antigravityModels: List<String> = emptyList(),
    val antigravityModelsLoading: Boolean = false,
    val updateInfo: AppUpdateInfo? = null,
    val updateStatusMessage: String? = null,
    /** 0..1 while an app update APK is downloading, null otherwise. */
    val updateProgress: Float? = null,
    val checkingAppUpdate: Boolean = false,
    val bannerMessage: UiText? = null,
    /** Recovery offered with [bannerMessage]; shown only while the banner still carries the message it was made for. */
    val bannerAction: BannerAction? = null,
    /** The last message of a turn that failed (not stopped); offered as "Try again" above the composer. */
    /** The last prompt of each chat whose turn failed (by chat id), so parallel failures do not overwrite each other. */
    val retryPrompts: Map<String, RetryPrompt> = emptyMap(),
    /** Set while a deleted project can still be restored; the UI shows a Snackbar with Undo. */
    val undoDelete: ProjectUndo? = null,
    /** Parsed Android UI hierarchy nodes for the Visual UI Inspector & Tap-to-Edit mode in Preview. */
    val uiInspectorNodes: List<UiInspectorNode> = emptyList(),
    val uiInspectorBusy: Boolean = false,
)
 {
    val activeProject: Project?
        get() = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()

    /** True while the open chat has its own agent turn running (drives its Stop button, status line and composer). */
    val activeChatRunning: Boolean get() = activeChatId in runningChatIds
    /** An agent works in the open project: its files are locked. Agents in other projects do not lock them. */
    val activeProjectAgentRunning: Boolean get() = (runningProjectChatCounts[activeProjectId] ?: 0) > 0
}

/** One interactive/visible Android UI node dumped via uiautomator for Visual Tap-to-Edit with Agent. */
data class UiInspectorNode(
    val id: String,
    val className: String,
    val text: String,
    val bounds: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
)

/** [chatId] keeps the offer in the chat where the failure happened. */
data class RetryPrompt(val chatId: String, val prompt: QueuedPrompt)

/** What the banner's button does. SET_UP_RUNTIME is handled by the UI (it reopens the setup screen). */
enum class BannerActionKind(@androidx.annotation.StringRes val label: Int) {
    SET_UP_RUNTIME(R.string.banner_action_setup),
    RETRY_IMPORT(R.string.action_retry),
    RETRY_UPDATE_CHECK(R.string.action_retry),
    RETRY_APK_INSTALL(R.string.action_retry),
}

/** [forMessage] ties the action to one banner text, so a later unrelated banner never inherits a stale button. */
data class BannerAction(val kind: BannerActionKind, val forMessage: UiText)

/** A message (and its attachments) the user sent while the agent was still working. */
data class QueuedPrompt(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val attachments: List<ChatAttachment> = emptyList(),
    /** Where the message was typed; it is only sent there, never into a chat the user switched to. */
    val projectId: String? = null,
    val chatId: String? = null,
)

/** A project removed from the list whose files are kept until the undo window (UNDO_WINDOW_MS) passes. */
data class ProjectUndo(val projectId: String, val name: String)

/** Snapshot of one active agent turn across any project/chat, displayed in the Dynamic Multi-Agent Live HUD. */
data class LiveAgentRunInfo(
    val projectId: String,
    val projectName: String,
    val chatId: String,
    val chatTitle: String,
    val agent: AgentKind,
    val statusLine: UiText?,
    val waitingApproval: Boolean = false,
)
