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
import com.nextcraft.forge.runtime.GoProjectTemplateGenerator
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = AppPreferences(application)
    private val scheduleStore = ScheduleStore(application)
    private val vault = ApiKeyVault(application)
    private val installer = RuntimeInstaller(application)
    private val checkpoints = WorkspaceCheckpoints(application.filesDir, application)
    private val chatBranches = ChatBranches(application)
    /** Serialises commits and clone setup per chat so a new turn never starts while the last one is being committed. */
    private val branchLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun branchLock(projectId: String, chatId: String) = branchLocks.getOrPut("$projectId:$chatId") { kotlinx.coroutines.sync.Mutex() }
    private fun branchLock(chatId: String) = branchLock(_state.value.activeProjectId, chatId)
    private val providerApi = ProviderApiClient(application)
    private val githubClient = GitHubClient(application)
    /** GitHub sign-in and the repository list; this class mirrors its state into [_state] for the screens. */
    val githubAuth = com.nextcraft.forge.runtime.GitHubAuthController(
        scope = viewModelScope,
        api = githubClient.asAccountApi(),
        saveToken = { vault.put("GITHUB_TOKEN", it) },
        clearToken = { vault.remove("GITHUB_TOKEN") },
        savedLogin = { preferences.githubLogin },
        saveLogin = { preferences.githubLogin = it },
        clearGuestAccess = { installer.clearGitHubAccess() },
        unnamedUserLabel = { str(R.string.msg_gh_user) },
        errorText = ::errText,
    )

    /** Finding and installing a newer build of the app; mirrored into [_state] like [githubAuth]. */
    val appUpdate = com.nextcraft.forge.update.AppUpdateController(
        scope = viewModelScope,
        source = object : com.nextcraft.forge.update.AppUpdateSource {
            override fun check(manifestUrlOverride: String) = AppUpdater(application, manifestUrlOverride).check()
            override fun download(info: com.nextcraft.forge.update.AppUpdateInfo, progress: (Long, Long) -> Unit) =
                AppUpdater(application).download(info, progress)
            override fun install(apk: File) {
                AndroidAppInstaller.install(context = application, apk = apk, launchAfterInstall = false)
            }
        },
        prefs = object : com.nextcraft.forge.update.UpdatePrefs {
            override var notifications: Boolean
                get() = preferences.updateNotifications
                set(value) { preferences.updateNotifications = value }
            override var autoInstall: Boolean
                get() = preferences.autoInstallUpdates
                set(value) { preferences.autoInstallUpdates = value }
        },
        scheduleBackgroundCheck = { com.nextcraft.forge.update.UpdateCheckWorker.schedule(application) },
        cancelBackgroundCheck = { com.nextcraft.forge.update.UpdateCheckWorker.cancel(application) },
        announce = { com.nextcraft.forge.update.UpdateNotifier.notifyIfNew(application, it) },
        text = { id, args -> str(id, *args) },
        localize = ::localizeMsg,
    )
    val antigravityAuth = AntigravityAuthController(
        context = application,
        initiallySignedIn = preferences.antigravitySignedIn,
        initialAccountEmail = preferences.antigravityAccountEmail,
        onSignedInChanged = { signedIn, email ->
            preferences.antigravitySignedIn = signedIn
            preferences.antigravityAccountEmail = email.orEmpty()
        },
    )

    /**
     * Text for state that is built outside Compose. The in-app language is applied only to Activity contexts, so the
     * plain application context would follow the system language instead; this wraps it with the chosen one.
     */
    private fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        LocaleHelper.wrapContext(getApplication<Application>()).getString(id, *args)

    /** A known English sentence from a lower layer in the app language; anything else unchanged. */
    private fun localizeMsg(raw: String): String = MessageText.localize(LocaleHelper.wrapContext(getApplication<Application>()), raw)

    /** A failure's own text (translated when it is a known sentence), else the generic "unknown error". */
    private fun errText(err: Throwable): String = err.message?.let(::localizeMsg) ?: str(R.string.msg_unknown_error)

    private fun quantity(@androidx.annotation.PluralsRes id: Int, count: Int): String =
        LocaleHelper.wrapContext(getApplication<Application>()).resources.getQuantityString(id, count, count)

    /** True when Claude runs on the user's subscription login (no key, or the OAuth token read from the credentials file). */
    private fun isClaudeSubscriptionLogin(profile: ProviderProfile, apiKey: String): Boolean =
        profile.kind == ProviderKind.CLAUDE && (apiKey.isBlank() || apiKey.startsWith("sk-ant-oat"))

    private fun resolveProviderSecret(profile: ProviderProfile): String? {
        vault.get(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.get(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.get(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (profile.kind == ProviderKind.CLAUDE || profile.kind == ProviderKind.ANTHROPIC) {
            val credsFile = File(RuntimeInstaller.rootfsDir(getApplication()), "root/.claude/.credentials.json")
            val guestToken = runCatching {
                JSONObject(credsFile.readText())
                    .optJSONObject("claudeAiOauth")
                    ?.stringOrEmpty("accessToken")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (guestToken != null) return guestToken
        }
        return null
    }

    private fun rotateProviderSecret(profile: ProviderProfile): String? {
        vault.rotateNext(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.rotateNext(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.rotateNext(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return resolveProviderSecret(profile)
    }

    val claudeAuth = ClaudeAuthController(
        context = application,
        onSignedInChanged = { signedIn ->
            if (signedIn) {
                ReturnToApp.afterSignIn(application, LocaleHelper.wrapContext(application).getString(R.string.rt_auth_claude_connected))
            }
            // A finished sign-in only helps if Claude Code actually uses it: switch to the subscription provider.
            if (signedIn && _state.value.agentKind == AgentKind.CLAUDE_CODE && _state.value.providerProfile.kind != ProviderKind.CLAUDE) {
                updateProviderKind(ProviderKind.CLAUDE)
            }
        },
    )

    // Developer tools (Tools tab). Each owns its state flow and runs its commands in the chroot.
    val diagnostics = DiagnosticsController(application)
    val rea = com.nextcraft.forge.runtime.ReaController(application)
    val buildMatrix = BuildMatrixController(application, { _state.value.activeProject }, { checkpoints.ensureWorkspace(it) })
    val cicd = CiCdController(application, { _state.value.activeProject }, { checkpoints.ensureWorkspace(it) })
    val ssh = SshController(application)
    val rom = RomStudioController(application)
    val backup = BackupController(application, viewModelScope)
    val snapshots = SnapshotsController(
        application,
        { _state.value.activeProject },
        { checkpoints.ensureWorkspace(it) },
        { isProjectBusy(_state.value.activeProjectId) },
    )

    private val claudeRuntime = ClaudeRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    )
    private val dshRuntime = DshRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    )
    private val antigravityRuntime = AntigravityRuntimeBridge(
        context = application,
        model = { preferences.antigravityModel },
        effort = { preferences.antigravityEffort },
        conversationId = { projectId ->
            preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, projectId, _state.value.activeChatId)
        },
        saveConversationId = { projectId, convId ->
            preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, projectId, _state.value.activeChatId, convId)
        },
    )

    private val _state = MutableStateFlow(
        ForgeUiState(
            themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
            languageCode = AppStrings.languageCode(application),
        ),
    )
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    private data class RunKey(val projectId: String, val chatId: String)

    private class ProjectBuildHandle(
        val projectId: String,
        val stopHook: () -> Unit,
    ) {
        var job: Job? = null
        @Volatile var process: Process? = null
    }

    /** Agent turns in progress across all projects, keyed by (projectId, chatId). Only touched on the main thread. */
    private val runs = LinkedHashMap<RunKey, ChatRun>()
    private fun runKey(chatId: String) = RunKey(_state.value.activeProjectId, chatId)
    private fun runKey(projectId: String, chatId: String) = RunKey(projectId, chatId)
    private fun isProjectBusy(projectId: String): Boolean =
        runs.keys.any { it.projectId == projectId } || projectBuildHandles.containsKey(projectId)

    private val projectBuildHandles = java.util.concurrent.ConcurrentHashMap<String, ProjectBuildHandle>()
    @Volatile private var activeTerminalProcess: Process? = null
    private var activeLogcatJob: Job? = null
    private var activeLogcatProcess: Process? = null
    private val logLineIdCounter = AtomicLong(1L)

    init {
        _state.update { it.copy(scheduledTasks = scheduleStore.load()) }
        startScheduleTicker()
        // A project removed while its undo window was open and the app then died keeps its files; finish that wipe.
        preferences.pendingProjectWipe?.let { id ->
            viewModelScope.launch(Dispatchers.IO) {
                if (preferences.loadProjects().none { it.id == id }) wipeProjectData(id)
                preferences.pendingProjectWipe = null
            }
        }
        RuntimeSetupController.restore(application)
        viewModelScope.launch {
            RuntimeSetupController.snapshot.collect { snap ->
                val completed = snap.status == RuntimeSetupStatus.COMPLETE
                _state.update { it.copy(setupSnapshot = snap) }
                if (completed) {
                    _state.update { it.copy(agentUpdates = emptyMap()) }
                    refreshRuntimeStatus()
                }
            }
        }
        viewModelScope.launch {
            AndroidAppInstaller.events.collect { event ->
                val statusMsg = event.message ?: if (event.success) {
                    val pkg = event.packageName ?: "APK"
                    if (event.launched) "Installed & launched $pkg" else "Installed $pkg"
                } else {
                    "APK installation failed"
                }
                _state.update {
                    val updatedRecord = it.androidBuild.copy(
                        message = statusMsg,
                        stage = if (event.success) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                    )
                    it.copy(
                        androidBuild = updatedRecord,
                        projectBuilds = if (it.activeProjectId.isNotBlank()) it.projectBuilds + (it.activeProjectId to updatedRecord) else it.projectBuilds,
                        bannerMessage = UiText.Raw(statusMsg),
                    )
                }
            }
        }
        viewModelScope.launch {
            appUpdate.state.collect { u ->
                _state.update {
                    it.copy(
                        updateNotifications = u.notifications,
                        autoInstallUpdates = u.autoInstall,
                        updateInfo = u.info,
                        updateStatusMessage = u.statusMessage,
                        updateProgress = u.progress,
                        checkingAppUpdate = u.checking,
                    )
                }
            }
        }
        // The screens still read these four fields from ForgeUiState; the controller is the only writer.
        viewModelScope.launch {
            githubAuth.state.collect { g ->
                _state.update { it.copy(githubLogin = g.login, githubDeviceCode = g.deviceCode, githubRepos = g.repos, githubStatusMessage = g.statusMessage) }
            }
        }
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            // Projects come first: they only need local storage. A fresh install has no rootfs yet, and a root prompt or
            // an installer check that throws or hangs must not leave the front page without its default project.
            val projects = loadOrCreateDefaultProject()
            _state.update { if (it.projects.isEmpty()) it.copy(projects = projects) else it }
            val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val savedAgent = AgentKind.fromStored(preferences.agentKind)
            val savedStacks = preferences.selectedDevStacks
                .mapNotNull { runCatching { DevStack.valueOf(it) }.getOrNull() }
                .toSet()
                .ifEmpty { setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID) }
            val installed = withContext(Dispatchers.IO) {
                runCatching {
                    installer.migrateLegacyToolMarkers()
                    installer.isInstalled().also { ready ->
                        if (ready) installer.cleanupLegacyWorkspaceScaffolding()
                    }
                }.getOrDefault(false)
            }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { runCatching { installer.installedStacks() }.getOrDefault(emptySet()) }
            } else {
                emptySet()
            }
            // No project is possible: the user may have deleted them all.
            val firstProject = projects.firstOrNull()
            configureAllProjectRoots(projects)
            val chats = firstProject?.let { preferences.loadProjectChats(it.id) }.orEmpty()
            val activeChatId = chats.firstOrNull()?.id ?: "main"
            val messages = firstProject?.let { preferences.loadMessages(it.id, activeChatId) }.orEmpty()
            val loadedProfile = preferences.loadProvider(vault, savedAgent)
            val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
            val keys = vault.list(profile.kind.name)
            val initialBuildRecord = withContext(Dispatchers.IO) {
                val ws = firstProject?.let { checkpoints.ensureWorkspace(it.id) }
                val androidRoot = ws?.let { findAndroidProjectRoot(it) }
                val existingApk = androidRoot?.let { findDebugApk(it) }
                if (existingApk != null) {
                    AndroidBuildRecord(
                        phase = AndroidBuildPhase.SUCCEEDED,
                        stage = AndroidBuildStage.COMPLETE,
                        apkPath = existingApk.absolutePath,
                        apkSizeBytes = existingApk.length(),
                        message = str(R.string.build_existing_apk, existingApk.name, existingApk.length() / 1024),
                    )
                } else {
                    AndroidBuildRecord()
                }
            }

            _state.update {
                it.copy(
                    arm64Supported = arm64,
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    selectedDevStacks = savedStacks,
                    installedDevStacks = installedStacks,
                    agentKind = savedAgent,
                    routing = preferences.routing,
                    themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
                    // Bootstrap can wait on a root prompt; if the user already opened or deleted a project meanwhile, keep that.
                    projects = if (it.activeProjectId.isBlank()) projects else it.projects,
                    activeProjectId = if (it.activeProjectId.isBlank()) firstProject?.id.orEmpty() else it.activeProjectId,
                    chats = if (it.activeProjectId.isBlank()) chats else it.chats,
                    activeChatId = if (it.activeProjectId.isBlank()) activeChatId else it.activeChatId,
                    messages = if (it.activeProjectId.isBlank()) messages else it.messages,
                    androidBuild = if (it.androidBuild.phase == AndroidBuildPhase.BUILDING) it.androidBuild else initialBuildRecord,
                    providerProfile = profile,
                    savedApiKeys = keys,
                    languageCode = AppStrings.languageCode(getApplication()),
                    interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
                    autoApproveAllTools = preferences.autoApproveAllTools,
                    autoSyncGitHub = preferences.autoSyncGitHub,
                    gameTemplatesEnabled = preferences.gameTemplatesEnabled,
                    androidHostTools = preferences.androidHostTools,
                    autoKeepChanges = preferences.autoKeepChanges,
                    reaChatHint = preferences.reaChatHint,
                    scheduleEnabled = preferences.scheduleEnabled,
                    autoBranchChats = preferences.autoBranchChats,
                    antigravityModel = preferences.antigravityModel,
                    antigravityEffort = preferences.antigravityEffort,
                    claudeEffort = preferences.claudeEffort,
                )
            }
            if (preferences.routing.mode != RouteMode.SINGLE) refreshReadyAgents()
            val savedGitHubToken = vault.get("GITHUB_TOKEN").orEmpty()
            if (savedGitHubToken.isNotBlank()) githubAuth.restoreRepos(savedGitHubToken)
            firstProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
            silentUpdateCheck()
        }
    }

    /** A clean install starts with no projects: the home screen offers Quick project / New project instead of a sample. */
    private suspend fun loadOrCreateDefaultProject(): List<Project> = withContext(Dispatchers.IO) {
        preferences.loadProjects().onEach { checkpoints.ensureWorkspace(it.id) }
    }

    private fun configureAllProjectRoots(projects: List<Project>) {
        projects.forEach { project ->
            checkpoints.configureProjectRoot(project.id, project.rootPath)
            claudeRuntime.configureProjectRoot(project.id, project.rootPath)
            dshRuntime.configureProjectRoot(project.id, project.rootPath)
            antigravityRuntime.configureProjectRoot(project.id, project.rootPath)
        }
    }

    private var lastResumeRefreshAt = 0L

    /** onResume fires on every return to the app; each refresh spawns `su`, so don't repeat it within a few seconds. */
    fun refreshRuntimeStatusOnResume() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastResumeRefreshAt != 0L && now - lastResumeRefreshAt < RESUME_REFRESH_MIN_INTERVAL_MS) return
        lastResumeRefreshAt = now
        refreshRuntimeStatus()
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val installed = withContext(Dispatchers.IO) { installer.isInstalled() }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { installer.installedStacks() }
            } else {
                emptySet()
            }
            val installedAgents = withContext(Dispatchers.IO) {
                AgentKind.entries.filter { installer.isAgentInstalled(it) }.toSet()
            }
            _state.update {
                it.copy(
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    installedDevStacks = installedStacks,
                    installedAgents = installedAgents,
                )
            }
        }
    }

    fun toggleDevStack(stack: DevStack) {
        _state.update { current ->
            val updated = if (stack in current.selectedDevStacks) {
                current.selectedDevStacks - stack
            } else {
                current.selectedDevStacks + stack
            }
            preferences.selectedDevStacks = updated.map { it.name }.toSet()
            current.copy(selectedDevStacks = updated)
        }
    }

    private val _backgroundPrompts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once, when the runtime setup is first started: the Activity then asks for notifications and the battery exemption. */
    val backgroundPrompts: SharedFlow<Unit> = _backgroundPrompts

    private fun requestBackgroundPromptsOnce() {
        // The Activity marks the prompts as shown once it actually displays them, so a missed emission is retried.
        if (!preferences.backgroundPromptsShown) _backgroundPrompts.tryEmit(Unit)
    }

    fun startRuntimeSetup() {
        requestBackgroundPromptsOnce()
        val current = _state.value
        val stacksCsv = current.selectedDevStacks.joinToString(",") { it.name }
        preferences.selectedDevStacks = current.selectedDevStacks.map { it.name }.toSet()
        preferences.agentKind = current.agentKind.stableId
        startServiceSafely(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacksCsv)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, current.agentKind.name),
        )
    }

    /** True when a manual runtime operation may start; otherwise posts the reason as a banner. */
    private fun canRunRuntimeOperation(): Boolean {
        val current = _state.value
        val reason = when {
            !current.runtimeInstalled -> UiText.res(R.string.msg_core_not_installed)
            current.sessionRunning || current.terminalRunning || current.androidBuild.phase == AndroidBuildPhase.BUILDING ->
                UiText.res(R.string.msg_wait_for_running_task)
            current.setupSnapshot.status == RuntimeSetupStatus.RUNNING -> UiText.res(R.string.msg_runtime_op_running)
            else -> return true
        }
        _state.update {
            it.copy(
                bannerMessage = reason,
                bannerAction = if (!current.runtimeInstalled) BannerAction(BannerActionKind.SET_UP_RUNTIME, reason) else null,
            )
        }
        return false
    }

    /** Android 8+ throws when the app is not allowed to start a foreground service (12+: also from the background). */
    private fun startServiceSafely(context: android.content.Context, intent: Intent) {
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { android.util.Log.w("MainViewModel", "Could not start ${intent.component?.shortClassName}: ${it::class.simpleName}") }
    }

    private fun startRuntimeOperation(scope: SetupScope, configure: Intent.() -> Unit) {
        _state.update { it.copy(setupScope = scope) }
        startServiceSafely(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java).apply(configure),
        )
    }

    /** Manually installs one agent bundle (Claude Code, DeepSeek or Antigravity) on top of the Core runtime. */
    fun installAgent(agent: AgentKind) {
        if (!_state.value.runtimeInstalled) return installCoreWith(emptySet(), agent)
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_INSTALL_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
        }
    }

    fun removeAgent(agent: AgentKind) {
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_REMOVE_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
        }
    }

    fun updateAgent(agent: AgentKind) {
        val info = _state.value.agentUpdates[agent] ?: return
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.AGENT) {
            action = RuntimeSetupService.ACTION_UPDATE_AGENT
            putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name)
            putExtra(RuntimeSetupService.EXTRA_VERSION, info.latestVersion)
        }
    }

    fun checkAgentUpdates() {
        if (_state.value.checkingAgentUpdates) return
        _state.update { it.copy(checkingAgentUpdates = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { installer.checkAgentUpdates() } }
            _state.update {
                it.copy(
                    checkingAgentUpdates = false,
                    agentUpdates = result.getOrDefault(emptyMap()),
                    bannerMessage = when {
                        result.isFailure -> UiText.res(R.string.msg_agent_update_check_failed, result.exceptionOrNull()?.message.orEmpty())
                        result.getOrThrow().isEmpty() -> UiText.res(R.string.msg_agents_up_to_date)
                        else -> it.bannerMessage
                    },
                )
            }
            if (result.isFailure) {
                _state.update { s -> s.copy(bannerAction = s.bannerMessage?.let { BannerAction(BannerActionKind.RETRY_UPDATE_CHECK, it) }) }
            }
        }
    }

    /** Installs or removes a single dev stack after onboarding, independent of the stack toggles. */
    fun installStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_INSTALL_STACK, stack)

    fun removeStack(stack: DevStack) = runStackOperation(RuntimeSetupService.ACTION_REMOVE_STACK, stack)

    /**
     * Core is missing (onboarding skipped): run the full setup, which installs Core first and then the requested
     * stack/agent, instead of refusing the click.
     */
    private fun installCoreWith(stacks: Set<DevStack>, agent: AgentKind) {
        if (_state.value.setupSnapshot.status == RuntimeSetupStatus.RUNNING) return
        requestBackgroundPromptsOnce()
        _state.update { it.copy(setupScope = SetupScope.RUNTIME) }
        startServiceSafely(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacks.joinToString(",") { it.name })
                .putExtra(RuntimeSetupService.EXTRA_AGENT, agent.name),
        )
    }

    private fun runStackOperation(serviceAction: String, stack: DevStack) {
        if (serviceAction == RuntimeSetupService.ACTION_INSTALL_STACK && !_state.value.runtimeInstalled) {
            return installCoreWith(setOf(stack), _state.value.agentKind)
        }
        if (!canRunRuntimeOperation()) return
        startRuntimeOperation(SetupScope.RUNTIME) {
            action = serviceAction
            putExtra(RuntimeSetupService.EXTRA_STACKS, stack.name)
        }
    }

    fun pauseRuntimeSetup() {
        getApplication<Application>().startService(
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_STOP),
        )
    }

    fun repairRootfs() {
        // Repair force-unmounts the chroot; under a running agent its /workspace and /proc would vanish mid-turn.
        if (_state.value.sessionRunning || runs.isNotEmpty()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_repair_busy)) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_repairing_chroot)) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootfs = File(getApplication<Application>().filesDir, "runtime/ubuntu")
                    installer.ensureChrootScripts()
                    if (RootfsRepair.ensureUnmounted(rootfs)) {
                        UiText.res(R.string.mt_repair_ok)
                    } else {
                        UiText.res(R.string.mt_repair_incomplete)
                    }
                }.getOrElse { UiText.res(R.string.msg_repair_note, it.message.orEmpty()) }
            }
            refreshRuntimeStatus()
            _state.update { it.copy(bannerMessage = result) }
        }
    }

    fun selectTab(tab: ForgeTab) {
        _state.update { it.copy(activeTab = tab) }
        if (tab == ForgeTab.FILES || tab == ForgeTab.ANDROID) {
            _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
        }
        if (tab == ForgeTab.PREVIEW) {
            scanLocalPreviewPorts()
        }
    }

    fun selectAgent(agent: AgentKind) {
        // Switching bridges mid-run on the same chat would misroute pending tool approvals; other chats/projects can keep running.
        if (_state.value.activeChatRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_agent_switch_busy)) }
            return
        }
        preferences.agentKind = agent.stableId
        val loadedProfile = preferences.loadProvider(vault, agent)
        val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
        val keys = vault.list(profile.kind.name)
        _state.update {
            it.copy(
                agentKind = agent,
                providerProfile = profile,
                savedApiKeys = keys,
                apiStatusMessage = null,
            )
        }
    }

    fun selectProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val switchingProject = projectId != _state.value.activeProjectId
        // Re-selecting the open project must not reload chats from disk: that would replace the streaming message
        // of a running turn and wipe the build log, selected chat and open file.
        if (!switchingProject) return
        activeLogcatProcess?.destroyForcibly()
        activeLogcatProcess = null
        activeLogcatJob?.cancel()
        // A file read still running for the previous project must not land in this one.
        openFileRequest++

        val chats = preferences.loadProjectChats(project.id)
        val chatId = chats.firstOrNull()?.id ?: "main"
        val run = runs[runKey(project.id, chatId)]?.also { applyPendingText(it) }
        val messages = run?.messages ?: preferences.loadMessages(project.id, chatId)
        val existingProjectBuild = _state.value.projectBuilds[project.id]
        _state.update {
            it.copy(
                activeProjectId = project.id,
                chats = chats,
                activeChatId = chatId,
                messages = messages,
                activeStatusLine = run?.statusLine,
                pendingToolRequest = run?.pendingTool,
                selectedFilePath = null,
                selectedFileContent = "",
                selectedFileDirty = false,
                fileSavedBanner = null,
                androidBuild = existingProjectBuild ?: AndroidBuildRecord(),
                androidLogcat = AndroidLogcatState(),
                branchStatus = emptyMap(),
                branchDiff = emptyMap(),
            )
        }
        publishRunningFlags()
        if (chats.firstOrNull { it.id == chatId }?.isolated == true) refreshBranchStatus(chatId)
        if (existingProjectBuild == null || existingProjectBuild.phase != AndroidBuildPhase.BUILDING) {
            viewModelScope.launch {
                val buildRecord = withContext(Dispatchers.IO) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val androidRoot = findAndroidProjectRoot(ws)
                    val existingApk = androidRoot?.let { findDebugApk(it) }
                    if (existingApk != null) {
                        AndroidBuildRecord(
                            phase = AndroidBuildPhase.SUCCEEDED,
                            stage = AndroidBuildStage.COMPLETE,
                            apkPath = existingApk.absolutePath,
                            apkSizeBytes = existingApk.length(),
                            message = str(R.string.build_existing_apk, existingApk.name, existingApk.length() / 1024),
                        )
                    } else {
                        AndroidBuildRecord()
                    }
                }
                if (_state.value.activeProjectId == project.id && _state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
                    _state.update {
                        it.copy(
                            androidBuild = buildRecord,
                            projectBuilds = it.projectBuilds + (project.id to buildRecord),
                        )
                    }
                }
            }
        }
        refreshWorkspaceFilesAndChanges(project.id)
    }

    fun createProject(
        name: String,
        description: String,
        isAndroid: Boolean,
        androidTemplate: AndroidTemplate = AndroidTemplate.COMPOSE,
        starter: StarterTemplate = StarterTemplate.EMPTY,
    ) {
        val cleanName = name.trim().ifBlank { "New Project" }
        viewModelScope.launch {
            var reservedSlug: String? = null
            val newProject = try {
                withContext(Dispatchers.IO) {
                val slug = reserveProjectSlug(projectSlug(cleanName)).also { reservedSlug = it }
                val project = Project(
                    name = cleanName,
                    description = description.trim().ifBlank {
                        when {
                            isAndroid -> "Android (${androidTemplate.label}) project"
                            starter == StarterTemplate.GO -> "Go command-line app"
                            starter == StarterTemplate.GAME_LIBGDX -> "libGDX game (Android + PC)"
                            else -> "General coding workspace"
                        }
                    },
                    language = when {
                        isAndroid -> "Kotlin"
                        starter == StarterTemplate.GO -> "Go"
                        starter == StarterTemplate.GAME_LIBGDX -> "Java (libGDX)"
                        else -> "Multi-language"
                    },
                    slug = slug,
                    // A libGDX game has an Android :app module, so it gets the Android build-and-install flow.
                    type = if (isAndroid || starter == StarterTemplate.GAME_LIBGDX) ProjectType.ANDROID else ProjectType.GENERAL,
                    androidTemplate = if (isAndroid) androidTemplate else null,
                )
                val wsDir = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
                if (isAndroid) {
                    wsDir.deleteRecursively()
                    AndroidProjectTemplateGenerator.generate(
                        destination = wsDir,
                        appName = cleanName,
                        template = androidTemplate,
                    )
                } else {
                    checkpoints.ensureWorkspace(project.id)
                    if (starter == StarterTemplate.GO) {
                        GoProjectTemplateGenerator.generate(wsDir, cleanName)
                    } else if (starter == StarterTemplate.GAME_LIBGDX) {
                        GameProjectTemplateGenerator.generate(wsDir, cleanName)
                    } else {
                        File(wsDir, "README.md").writeText("# $cleanName\n\n${project.description}\n")
                    }
                }
                project
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Disk full or an unwritable workspace used to escape viewModelScope and crash the app, and the
                // reserved slug stayed taken.
                reservedSlug?.let { slug -> synchronized(reservedSlugs) { reservedSlugs -= slug } }
                _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_project_create_failed, e.message ?: e.javaClass.simpleName)) }
                return@launch
            }
            val updated = listOf(newProject) + _state.value.projects
            preferences.saveProjects(updated)
            configureAllProjectRoots(updated)
            _state.update {
                it.copy(
                    projects = updated,
                    activeTab = ForgeTab.PROJECTS,
                    projectView = ProjectView.CONVERSATION,
                )
            }
            leaveOpenFile { selectProject(newProject.id) }
        }
    }

    private val reservedSlugs = mutableSetOf<String>()

    /**
     * Picks a slug no project (nor a create/clone/import still in flight) uses. Two quick Create taps or clones of
     * the same repo would otherwise both see the slug as free and share one guest workspace path.
     */
    private fun reserveProjectSlug(baseSlug: String): String = synchronized(reservedSlugs) {
        val used = _state.value.projects.map { it.slug }.toSet() + reservedSlugs
        var slug = baseSlug
        var suffix = 2
        while (slug in used) slug = "$baseSlug-${suffix++}"
        reservedSlugs += slug
        slug
    }

    /**
     * Prepends [project] to the list atomically and persists the result. Callers used to read the list after a
     * suspend point and write it back, which erased renames or deletes made while an import or clone was running.
     */
    private fun prependProject(project: Project): List<Project> {
        var updated: List<Project> = emptyList()
        _state.update { current ->
            updated = listOf(project) + current.projects
            current.copy(projects = updated)
        }
        preferences.saveProjects(updated)
        return updated
    }

    fun deleteProject(projectId: String) {
        // The workspace would be wiped under a running agent or build in that project.
        if (isProjectBusy(projectId)) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_busy_before_delete)) }
            return
        }
        val currentProjects = _state.value.projects
        val index = currentProjects.indexOfFirst { it.id == projectId }
        if (index < 0) return
        val removed = currentProjects[index]
        closeProjectTerminals(removed.id)
        // A second delete inside the undo window makes the first one final.
        finalizePendingDelete()
        val updated = currentProjects.filterNot { it.id == projectId }
        preferences.saveProjects(updated)
        preferences.pendingProjectWipe = removed.id
        pendingDelete = PendingDelete(removed, index)
        pendingDeleteJob = viewModelScope.launch {
            delay(UNDO_WINDOW_MS)
            finalizePendingDelete()
        }
        _state.update { it.copy(projects = updated, undoDelete = ProjectUndo(removed.id, removed.name)) }
        if (_state.value.activeProjectId == projectId) {
            if (updated.isNotEmpty()) {
                selectProject(updated.first().id)
            } else {
                // The last project is gone: leave the project context empty (the Projects screen offers a new one).
                _state.update {
                    it.copy(
                        activeProjectId = "",
                        chats = emptyList(),
                        activeChatId = "main",
                        messages = emptyList(),
                        selectedFilePath = null,
                        selectedFileContent = "",
                        selectedFileDirty = false,
                        changedItems = emptyList(),
                        workspaceFiles = emptyList(),
                        androidBuild = AndroidBuildRecord(),
                        branchStatus = emptyMap(),
                        branchDiff = emptyMap(),
                        queuedPrompts = emptyList(),
                        retryPrompts = emptyMap(),
                    )
                }
            }
        }
    }

    /** Restores the project removed by [deleteProject] if its undo window is still open. */
    fun undoDeleteProject() {
        val pending = pendingDelete ?: return
        pendingDeleteJob?.cancel()
        pendingDelete = null
        preferences.pendingProjectWipe = null
        val restored = _state.value.projects.toMutableList().also {
            it.add(pending.index.coerceIn(0, it.size), pending.project)
        }
        preferences.saveProjects(restored)
        _state.update { it.copy(projects = restored, undoDelete = null) }
        // Undoing the deletion of the only project: open it again.
        if (_state.value.activeProjectId.isBlank()) selectProject(pending.project.id)
    }

    /** The Snackbar was dismissed or timed out on its own; the deletion stays pending until its window ends. */
    fun clearUndoPrompt() {
        _state.update { it.copy(undoDelete = null) }
    }

    /** Wipes the files of the project waiting in the undo window. Safe to call when nothing is pending. */
    private fun finalizePendingDelete() {
        val pending = pendingDelete ?: return
        pendingDelete = null
        pendingDeleteJob?.cancel()
        val project = pending.project
        synchronized(reservedSlugs) { reservedSlugs -= project.slug }
        _state.update { it.copy(undoDelete = null) }
        viewModelScope.launch(Dispatchers.IO) {
            wipeProjectData(project)
            preferences.pendingProjectWipe = null
        }
    }

    /** Blocking: removes chats, workspace and checkpoints of [project]. Call off the main thread. */
    private fun wipeProjectData(project: Project) = wipeProjectData(project.id)

    private fun wipeProjectData(projectId: String) {
        // Each saveAgentConversation is a synchronous commit().
        runCatching {
            preferences.loadProjectChats(projectId).forEach { chat ->
                AgentKind.entries.forEach { agent ->
                    preferences.saveAgentConversation(agent, projectId, chat.id, null)
                }
            }
            preferences.deleteProjectChats(projectId)
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        runCatching {
            File(getApplication<Application>().filesDir, "workspaces/${projectId}").deleteRecursivelyNoFollow()
            checkpoints.checkpointDir(projectId).deleteRecursively()
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
    }

    private data class PendingDelete(val project: Project, val index: Int)

    private var pendingDelete: PendingDelete? = null
    private var pendingDeleteJob: Job? = null

    /** Renames the display name only; the slug (the /workspace/<slug> path agents and chats are bound to) stays. */
    fun renameProject(projectId: String, newName: String) {
        val cleanName = newName.trim().take(80)
        if (cleanName.isEmpty()) return
        val current = _state.value.projects
        val target = current.firstOrNull { it.id == projectId } ?: return
        if (target.name == cleanName) return
        val updated = current.map { if (it.id == projectId) it.copy(name = cleanName) else it }
        preferences.saveProjects(updated)
        _state.update { it.copy(projects = updated) }
    }

    fun selectChat(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.chats.none { it.id == chatId }) return
        // A chat that is working shows its live copy, status and pending approval, not the last saved file.
        val run = runs[runKey(project.id, chatId)]?.also { applyPendingText(it) }
        _state.update {
            it.copy(
                activeChatId = chatId,
                messages = run?.messages ?: emptyList(),
                activeStatusLine = run?.statusLine,
                pendingToolRequest = run?.pendingTool,
            )
        }
        // A long chat's JSON is megabytes: parse it off the main thread, then show it if the chat is still open.
        if (run == null) loadChatMessagesAsync(project.id, chatId)
        if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) refreshBranchStatus(chatId)
    }

    /** Chat whose saved messages are still being read; until then the state holds no messages for it. */
    @Volatile private var loadingChatId: String? = null

    private fun loadChatMessagesAsync(projectId: String, chatId: String) {
        loadingChatId = chatId
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) { preferences.loadMessages(projectId, chatId) }
            if (loadingChatId == chatId) loadingChatId = null
            if (_state.value.activeChatId == chatId && _state.value.activeProjectId == projectId && runs[runKey(projectId, chatId)] == null) {
                _state.update { it.copy(messages = loaded) }
            }
        }
    }

    /** The open chat's messages, or the saved ones while they are still loading (never an empty stand-in). */
    private fun currentMessagesOf(projectId: String, chatId: String): List<ChatMessage> =
        if (projectId == _state.value.activeProjectId && chatId == _state.value.activeChatId && loadingChatId != chatId) _state.value.messages
        else preferences.loadMessages(projectId, chatId)

    fun createChat(title: String = "") {
        val project = _state.value.activeProject ?: return
        // The first "Chat N" no chat uses: counting chats repeated a number after one was deleted.
        val usedTitles = _state.value.chats.map { it.title }.toSet()
        val nextIndex = generateSequence(_state.value.chats.size + 1) { it + 1 }.first { "Chat $it" !in usedTitles }
        val chat = ProjectChat(
            title = title.trim().ifBlank { "Chat $nextIndex" },
        )
        val updatedChats = _state.value.chats + chat
        preferences.saveProjectChats(project.id, updatedChats)
        preferences.saveMessages(project.id, chat.id, emptyList())
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = chat.id,
                messages = emptyList(),
                activeStatusLine = null,
                pendingToolRequest = null,
                activeTab = ForgeTab.PROJECTS,
                projectView = ProjectView.CONVERSATION,
            )
        }
    }

    fun deleteChat(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (runs.containsKey(runKey(project.id, chatId))) return
        if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated != true) {
            deleteChatNow(chatId)
            return
        }
        // Deleting the chat discards its branch clone; never do that over commits that were not merged.
        viewModelScope.launch {
            if (branchHoldsUnmergedWork(project, chatId)) {
                _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_unmerged)) }
            } else if (_state.value.activeProjectId == project.id) {
                // The user may have opened another project during the check; "main" exists in every project.
                deleteChatNow(chatId)
            }
        }
    }

    /** True when the chat's clone exists and either has work not in the project or its state cannot be read. */
    private suspend fun branchHoldsUnmergedWork(project: Project, chatId: String): Boolean {
        val cloneExists = File(chatBranches.branchFor(project, chatId).cloneDir, ".git").exists()
        if (!cloneExists) return false
        val status = runCatching { chatBranches.status(project, chatId) }.getOrNull() ?: return true
        return status.commits > 0 || status.uncommitted > 0 || status.conflicts.isNotEmpty()
    }

    private fun deleteChatNow(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (runs.containsKey(runKey(project.id, chatId))) return
        val currentChats = _state.value.chats
        if (currentChats.size <= 1) return
        val updatedChats = currentChats.filterNot { it.id == chatId }
        if (updatedChats.size == currentChats.size) return
        // Its shell may sit in the branch folder that is about to be removed.
        closeChatTerminal(chatId)
        if (currentChats.firstOrNull { it.id == chatId }?.isolated == true) {
            viewModelScope.launch { runCatching { chatBranches.discard(project, chatId) } }
        }
        preferences.saveProjectChats(project.id, updatedChats)
        viewModelScope.launch(Dispatchers.IO) {
            AgentKind.entries.forEach { agent ->
                preferences.saveAgentConversation(agent, project.id, chatId, null)
            }
            runCatching {
                File(getApplication<Application>().filesDir, "chats/${project.id}/$chatId.json").delete()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
        val nextChatId = if (_state.value.activeChatId == chatId) {
            updatedChats.first().id
        } else {
            _state.value.activeChatId
        }
        val nextRun = runs[runKey(project.id, nextChatId)]
        val messages = nextRun?.messages ?: preferences.loadMessages(project.id, nextChatId)
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = nextChatId,
                messages = messages,
                activeStatusLine = nextRun?.statusLine,
                pendingToolRequest = nextRun?.pendingTool,
                queuedPrompts = it.queuedPrompts.filterNot { q -> q.projectId == project.id && q.chatId == chatId },
            )
        }
    }

    // ── Chat branches ────────────────────────────────────────────────────────────────────────────

    private fun projectOf(id: String): Project? = _state.value.projects.firstOrNull { it.id == id }

    /** One commit per finished turn, then a fresh status for the branch card. */
    private fun commitBranchTurn(run: ChatRun) {
        val project = projectOf(run.projectId) ?: return
        viewModelScope.launch {
            branchLock(run.projectId, run.chatId).withLock {
                runCatching { chatBranches.commitTurn(project, run.chatId, run.lastPrompt.text) }
                    .onFailure { Log.w("MainViewModel", "Could not commit the chat branch", it) }
            }
            if (_state.value.activeProjectId == run.projectId) {
                refreshBranchStatus(run.chatId)
            }
        }
    }

    fun refreshBranchStatus(chatId: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val status = runCatching { chatBranches.status(project, chatId) }.getOrNull()
            if (_state.value.activeProjectId == project.id) {
                _state.update { st ->
                    st.copy(branchStatus = if (status != null) st.branchStatus + (chatId to status) else st.branchStatus - chatId)
                }
            }
        }
    }

    private fun <T> withBranchBusy(chatId: String, block: suspend () -> T) {
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
    fun enableChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (runs.containsKey(runKey(project.id, chatId))) return
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = true) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated, bannerMessage = UiText.res(R.string.branch_enabled)) }
    }

    /** Back to the project folder; only when the branch holds nothing that would be lost. */
    fun disableChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            // The cached status may be missing (app restarted) or stale, so ask git again before discarding anything.
            if (branchHoldsUnmergedWork(project, chatId)) {
                _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_unmerged)) }
                return@withBranchBusy
            }
            chatBranches.discard(project, chatId)
            val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
            preferences.saveProjectChats(project.id, updated)
            _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId) }
        }
    }

    private val chatImageCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24) {}

    /**
     * The image behind a chat `src`. Workspace files are read through the same path checks as the file editor (no
     * `..`, no symlinks) from the chat's own branch first, then from the project; remote addresses are only fetched
     * after the reader tapped them.
     */
    suspend fun loadChatImage(src: String): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        val project = _state.value.activeProject ?: return@withContext null
        val chatId = _state.value.activeChatId
        val source = classifyImageSource(src, project.slug) ?: return@withContext null
        runCatching {
            when (source) {
                is ChatImageSource.Data -> {
                    val key = "data|${source.base64.length}|${source.base64.hashCode()}"
                    chatImageCache.get(key) ?: ChatImageDecoder.decode(android.util.Base64.decode(source.base64, android.util.Base64.DEFAULT))
                        ?.also { chatImageCache.put(key, it) }
                }
                is ChatImageSource.Remote -> {
                    val key = "url|${source.url}"
                    chatImageCache.get(key) ?: ChatImageDecoder.fetch(source.url)?.let(ChatImageDecoder::decode)
                        ?.also { chatImageCache.put(key, it) }
                }
                is ChatImageSource.Workspace -> {
                    val roots = buildList {
                        if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) {
                            add(chatBranches.branchFor(project, chatId).cloneDir)
                        }
                        add(checkpoints.ensureWorkspace(project.id))
                    }
                    val file = roots.firstNotNullOfOrNull { root ->
                        runCatching { checkpoints.safeWorkspaceFile(root, source.relativePath) }.getOrNull()?.takeIf { it.isFile }
                    } ?: return@runCatching null
                    if (file.length() > ChatImageDecoder.MAX_BYTES) return@runCatching null
                    val key = "file|${file.absolutePath}|${file.lastModified()}|${file.length()}"
                    chatImageCache.get(key) ?: ChatImageDecoder.decode(file.readBytes())?.also { chatImageCache.put(key, it) }
                }
            }
        }.getOrNull()
    }

    // ── Connectors (MCP) and skills for Claude Code ──────────────────────────────────────────────

    private val connectorStore by lazy { ConnectorStore(getApplication()) }

    private fun skillStore(): ClaudeSkillStore? = runCatching {
        ClaudeSkillStore(File(installer.installedRuntime().rootfs, "root/.claude/skills"))
    }.getOrNull()

    fun loadConnectors() {
        viewModelScope.launch {
            val (connectors, skills) = withContext(Dispatchers.IO) {
                connectorStore.list() to skillStore()?.list().orEmpty()
            }
            _state.update { it.copy(connectors = connectors, skills = skills) }
        }
    }

    /** Adds or updates a connector; a blank [secret] keeps the saved one when editing and means none when new. */
    fun saveConnector(name: String, transport: McpTransport, target: String, secretName: String, secret: String) {
        val id = McpNames.id(name)
        val cleanTarget = target.trim()
        val invalid = when {
            id == null -> R.string.connector_invalid_name
            cleanTarget.isEmpty() -> R.string.connector_invalid_target
            transport == McpTransport.HTTP && !com.nextcraft.forge.network.LocalNetwork.isPlainHttpAllowed(cleanTarget) ->
                R.string.connector_invalid_url
            transport == McpTransport.COMMAND && secret.isNotBlank() && !McpNames.isEnvName(secretName.trim()) ->
                R.string.connector_invalid_env
            else -> null
        }
        if (invalid != null || id == null) {
            _state.update { it.copy(bannerMessage = UiText.res(invalid ?: R.string.connector_invalid_name)) }
            return
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val existing = connectorStore.list().firstOrNull { it.id == id }
                connectorStore.upsert(
                    McpConnector(id, name.trim().take(40), transport, cleanTarget, secretName.trim(), existing?.enabled ?: true),
                    secret.takeIf { it.isNotBlank() },
                )
            }
            _state.update { it.copy(bannerMessage = UiText.res(R.string.connector_saved)) }
            loadConnectors()
        }
    }

    fun setConnectorEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { connectorStore.setEnabled(id, enabled) }
            loadConnectors()
        }
    }

    fun deleteConnector(id: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { connectorStore.delete(id) }
            loadConnectors()
        }
    }

    fun saveSkill(name: String, description: String, body: String) {
        val skillName = ClaudeSkillStore.skillName(name)
        if (skillName == null || body.isBlank()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.skill_invalid)) }
            return
        }
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching { skillStore()?.save(skillName, description, body) != null }.getOrDefault(false)
            }
            _state.update { it.copy(bannerMessage = UiText.res(if (saved) R.string.skill_saved else R.string.skill_save_failed)) }
            loadConnectors()
        }
    }

    fun deleteSkill(name: String) {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { runCatching { skillStore()?.delete(name) == true }.getOrDefault(false) }
            if (!deleted) _state.update { it.copy(bannerMessage = UiText.res(R.string.skill_save_failed)) }
            loadConnectors()
        }
    }

    suspend fun loadSkillBody(name: String): String = withContext(Dispatchers.IO) { skillStore()?.body(name).orEmpty() }

    // ── Image generation (Nano Banana) ───────────────────────────────────────────────────────────

    private val geminiImage = GeminiImageClient()

    fun hasGeminiImageKey(): Boolean = vault.contains(GeminiImageModels.KEY_ID)

    fun saveGeminiImageKey(key: String) {
        val clean = key.trim()
        if (clean.isEmpty()) return
        runCatching { vault.put(GeminiImageModels.KEY_ID, clean) }
            .onFailure { _state.update { st -> st.copy(bannerMessage = UiText.res(R.string.msg_api_key_save_failed)) } }
        _state.update { it.copy(geminiKeyVersion = it.geminiKeyVersion + 1) }
    }

    /**
     * Creates (or, with an image attached, edits) a picture with Nano Banana, saves it in the chat's workspace under
     * `images/` and adds it to the chat as a message.
     */
    fun generateImage(prompt: String, model: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        val clean = prompt.trim()
        if (clean.isEmpty() || _state.value.imageGenerating) return
        if (runs.containsKey(runKey(project.id, chatId))) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.image_busy_chat)) }
            return
        }
        val key = vault.get(GeminiImageModels.KEY_ID)
        if (key.isNullOrBlank()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.image_need_key)) }
            return
        }
        val attachment = _state.value.pendingAttachments.firstOrNull { it.mimeType.startsWith("image/") }
        _state.update { it.copy(imageGenerating = true, bannerMessage = UiText.res(R.string.image_working)) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val root = if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) {
                        chatBranches.branchFor(project, chatId).cloneDir
                    } else {
                        checkpoints.ensureWorkspace(project.id)
                    }
                    val input = attachment?.let { att ->
                        val file = checkpoints.safeWorkspaceFile(root, att.relativePath)
                        if (file.isFile && file.length() <= ChatImageDecoder.MAX_BYTES) file.readBytes() to att.mimeType else null
                    }
                    when (val result = geminiImage.generate(key, model, clean, input?.first, input?.second ?: "image/png")) {
                        is GeminiImageResult.Failure -> Result.failure(IllegalStateException(result.message))
                        is GeminiImageResult.Success -> {
                            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                            val name = "images/nanobanana-$stamp.${com.nextcraft.forge.network.imageExtension(result.mimeType)}"
                            val target = checkpoints.safeWorkspaceFile(root, name)
                            target.parentFile?.mkdirs()
                            target.writeBytes(result.bytes)
                            Result.success(name to result.note)
                        }
                    }
                }.getOrElse { Result.failure(it) }
            }
            outcome.fold(
                onSuccess = { (path, note) ->
                    val alt = clean.replace(Regex("[\\[\\]()\\n]"), " ").take(80)
                    val reply = ChatMessage(fromUser = false, text = "![$alt]($path)" + if (note.isNotBlank()) "\n\n$note" else "")
                    appendLocalMessages(project.id, chatId, listOf(ChatMessage(fromUser = true, text = clean), reply))
                    _state.update { it.copy(imageGenerating = false, pendingAttachments = if (attachment != null) emptyList() else it.pendingAttachments) }
                    refreshWorkspaceFilesAndChanges(project.id)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(imageGenerating = false, bannerMessage = UiText.res(R.string.image_failed, error.message ?: error.javaClass.simpleName))
                    }
                },
            )
        }
    }

    private val geminiVideo = GeminiVideoClient()

    /**
     * Creates a video clip with Gemini Omni (image-to-video when an image is attached), saves it under `videos/` in the
     * chat's workspace and adds it to the chat. The call is synchronous on Google's side and can take minutes.
     */
    fun generateVideo(prompt: String, aspectRatio: String, resolution: String) {
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        val clean = prompt.trim()
        if (clean.isEmpty() || _state.value.imageGenerating) return
        if (runs.containsKey(runKey(project.id, chatId))) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.image_busy_chat)) }
            return
        }
        val key = vault.get(GeminiImageModels.KEY_ID)
        if (key.isNullOrBlank()) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.image_need_key)) }
            return
        }
        val attachment = _state.value.pendingAttachments.firstOrNull { it.mimeType.startsWith("image/") }
        _state.update { it.copy(imageGenerating = true, bannerMessage = UiText.res(R.string.video_working)) }
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val root = if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) {
                        chatBranches.branchFor(project, chatId).cloneDir
                    } else {
                        checkpoints.ensureWorkspace(project.id)
                    }
                    val input = attachment?.let { att ->
                        val file = checkpoints.safeWorkspaceFile(root, att.relativePath)
                        if (file.isFile && file.length() <= ChatImageDecoder.MAX_BYTES) file.readBytes() to att.mimeType else null
                    }
                    val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
                    val name = "videos/omni-$stamp.mp4"
                    val target = checkpoints.safeWorkspaceFile(root, name)
                    target.parentFile?.mkdirs()
                    when (val result = geminiVideo.generate(key, clean, target, aspectRatio, resolution, input?.first, input?.second ?: "image/png")) {
                        is GeminiVideoResult.Failure -> Result.failure(IllegalStateException(result.message))
                        GeminiVideoResult.Success -> Result.success(name)
                    }
                }.getOrElse { Result.failure(it) }
            }
            outcome.fold(
                onSuccess = { path ->
                    appendLocalMessages(project.id, chatId, listOf(ChatMessage(fromUser = true, text = clean), ChatMessage(fromUser = false, text = path)))
                    _state.update { it.copy(imageGenerating = false, pendingAttachments = if (attachment != null) emptyList() else it.pendingAttachments) }
                    refreshWorkspaceFilesAndChanges(project.id)
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(imageGenerating = false, bannerMessage = UiText.res(R.string.video_failed, error.message ?: error.javaClass.simpleName))
                    }
                },
            )
        }
    }

    /** The file behind a chat video `src` (same path checks and branch-first lookup as images), or null. */
    private fun resolveChatMediaFile(src: String): java.io.File? {
        val project = _state.value.activeProject ?: return null
        val chatId = _state.value.activeChatId
        val source = classifyImageSource(src, project.slug) as? ChatImageSource.Workspace ?: return null
        val roots = buildList {
            if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) add(chatBranches.branchFor(project, chatId).cloneDir)
            add(checkpoints.ensureWorkspace(project.id))
        }
        return roots.firstNotNullOfOrNull { root ->
            runCatching { checkpoints.safeWorkspaceFile(root, source.relativePath) }.getOrNull()?.takeIf { it.isFile }
        }
    }

    /** A still frame of a chat video, for its card. */
    suspend fun loadChatVideoThumbnail(src: String): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
        runCatching {
            val file = resolveChatMediaFile(src) ?: return@runCatching null
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(file.absolutePath)
                retriever.getFrameAtTime(0)?.asImageBitmap()
            } finally {
                runCatching { retriever.release() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
            }
        }.getOrNull()
    }

    /** Opens a chat video in the device's video player. */
    fun openChatVideo(context: android.content.Context, src: String) {
        val file = runCatching { resolveChatMediaFile(src) }.getOrNull()
        if (file == null) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.chat_video_missing)) }
            return
        }
        runCatching {
            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, "video/mp4")
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { _state.update { it.copy(bannerMessage = UiText.res(R.string.chat_video_no_player)) } }
    }

    /** Adds finished messages to a chat that has no agent run (they are saved like any other message). */
    private fun appendLocalMessages(projectId: String, chatId: String, added: List<ChatMessage>) {
        val current = currentMessagesOf(projectId, chatId)
        val updated = current.filterNot { it.id == "streaming-assistant" } + added
        preferences.saveMessages(projectId, chatId, updated)
        if (_state.value.activeProjectId == projectId && _state.value.activeChatId == chatId) {
            _state.update { it.copy(messages = updated) }
        }
    }

    /** Loads the project repository and the state of every chat branch (the local pull requests). */
    fun refreshProjectRepo() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(repoBusy = true) }
        viewModelScope.launch {
            val info = runCatching { chatBranches.repoInfo(project) }.getOrNull()
            _state.update { st -> st.copy(projectRepo = info ?: st.projectRepo, repoBusy = false) }
            _state.value.chats.filter { it.isolated }.forEach { refreshBranchStatus(it.id) }
        }
    }

    /** Runs a branch operation on the project repository; refused while a chat writes in the project folder. */
    private fun repoBranchOp(success: Int, op: suspend (Project) -> RepoOpResult) {
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
            if (result == RepoOpResult.Done) refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun createRepoBranch(name: String) = repoBranchOp(R.string.repo_branch_created) { chatBranches.createBranch(it, name) }

    fun switchRepoBranch(name: String) = repoBranchOp(R.string.repo_branch_switched) { chatBranches.switchBranch(it, name) }

    fun deleteRepoBranch(name: String) = repoBranchOp(R.string.repo_branch_deleted) { chatBranches.deleteBranch(it, name) }

    /** Commits everything in the project folder with the user's message. */
    fun commitProjectRepo(message: String) {
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
            val result = runCatching { chatBranches.commitProject(project, clean) }
            val failure = result.exceptionOrNull()?.message ?: result.getOrNull()?.takeIf { !it.ok }?.output?.trim()?.takeLast(200)
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
    fun commitChatBranch(chatId: String, message: String) {
        val project = _state.value.activeProject ?: return
        val clean = message.trim()
        if (clean.isEmpty()) return
        withBranchBusy(chatId) {
            branchLock(chatId).withLock { chatBranches.commitNow(project, chatId, clean) }
            _state.update { it.copy(bannerMessage = UiText.res(R.string.branch_committed)) }
            refreshBranchStatus(chatId)
        }
    }

    /** Merge and update snapshot the project folder; they must not run while a chat writes in it. */
    private fun projectFolderBusy(projectId: String): Boolean {
        val busy = runs.values.any { it.projectId == projectId && !it.isolated }
        if (busy) _state.update { it.copy(bannerMessage = UiText.res(R.string.repo_busy_agent)) }
        return busy
    }

    fun mergeChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (projectFolderBusy(project.id)) return
        val title = _state.value.chats.firstOrNull { it.id == chatId }?.title.orEmpty()
        withBranchBusy(chatId) {
            val result = branchLock(chatId).withLock {
                val saved = chatBranches.commitTurn(project, chatId, "Forge: save work before merge")
                // Conflict markers still in the branch must be fixed first; merging now would put them in the project.
                if (!saved.ok && saved.output.contains("unresolved conflict")) {
                    BranchMergeResult.Failed(saved.output.trim().takeLast(200))
                } else {
                    chatBranches.mergeIntoMain(project, chatId, title)
                }
            }
            val message = when (result) {
                BranchMergeResult.Done -> UiText.res(R.string.branch_merged)
                is BranchMergeResult.Conflict -> UiText.res(R.string.branch_merge_conflict, result.files.joinToString(", ").take(200))
                is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
            }
            _state.update { it.copy(bannerMessage = message) }
            if (result == BranchMergeResult.Done) refreshWorkspaceFilesAndChanges(project.id)
            refreshBranchStatus(chatId)
        }
    }

    fun updateChatBranchFromMain(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (projectFolderBusy(project.id)) return
        withBranchBusy(chatId) {
            val result = branchLock(chatId).withLock { chatBranches.updateFromMain(project, chatId) }
            val message = when (result) {
                BranchMergeResult.Done -> UiText.res(R.string.branch_updated)
                is BranchMergeResult.Conflict -> UiText.res(R.string.branch_update_conflict, result.files.joinToString(", ").take(200))
                is BranchMergeResult.Failed -> UiText.res(R.string.branch_failed, result.message)
            }
            _state.update { it.copy(bannerMessage = message) }
            refreshBranchStatus(chatId)
        }
    }

    fun discardChatBranch(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            chatBranches.discard(project, chatId)
            val updated = _state.value.chats.map { if (it.id == chatId) it.copy(isolated = false) else it }
            preferences.saveProjectChats(project.id, updated)
            _state.update { it.copy(chats = updated, branchStatus = it.branchStatus - chatId, branchDiff = it.branchDiff - chatId) }
        }
    }

    fun loadBranchDiff(chatId: String) {
        val project = _state.value.activeProject ?: return
        withBranchBusy(chatId) {
            val text = chatBranches.diff(project, chatId)
            _state.update { it.copy(branchDiff = it.branchDiff + (chatId to text)) }
        }
    }

    /** Asks the chat's agent to finish a merge that stopped on conflicts inside its branch. */
    fun askAgentToResolveConflicts(chatId: String) {
        val files = _state.value.branchStatus[chatId]?.conflicts.orEmpty()
        if (files.isEmpty()) return
        if (_state.value.activeChatId != chatId) selectChat(chatId)
        if (_state.value.activeChatId != chatId) return
        val text = getApplication<Application>().getString(R.string.branch_resolve_prompt, files.joinToString(", "))
        _state.update { it.copy(pendingAttachments = emptyList()) }
        sendPrompt(text)
    }

    /** Renames a chat of the open project; a renamed chat keeps its name (it is no longer auto-titled). */
    fun renameChat(chatId: String, title: String) {
        val project = _state.value.activeProject ?: return
        val clean = title.trim().take(60)
        if (clean.isEmpty()) return
        val updated = _state.value.chats.map { if (it.id == chatId) it.copy(title = clean) else it }
        preferences.saveProjectChats(project.id, updated)
        _state.update { it.copy(chats = updated) }
    }

    fun clearActiveChat() {
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
            )
        }
    }

    fun sendPrompt(promptText: String) {
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


    // --- Scheduled tasks: a prompt sent again to a chat every N minutes (by the user or by the agent) ---

    /** Runs due tasks while the app process lives; [com.nextcraft.forge.schedule.ScheduleTickWorker] covers a killed process. */
    private fun startScheduleTicker() {
        ScheduleRuntime.tickerActive = true
        viewModelScope.launch {
            while (true) {
                delay(SCHEDULE_TICK_MS)
                runCatching { runDueScheduledTasks() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
            }
        }
    }

    private fun saveScheduled(tasks: List<ScheduledTask>) {
        scheduleStore.save(tasks)
        _state.update { it.copy(scheduledTasks = tasks) }
    }

    private fun dropScheduledTasks(predicate: (ScheduledTask) -> Boolean) {
        val current = _state.value.scheduledTasks
        if (current.any(predicate)) saveScheduled(current.filterNot(predicate))
    }

    /**
     * Starts every due task across any project whose chat is idle, as long as hardware memory allows.
     */
    private fun runDueScheduledTasks() {
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
    private fun applyAgentSchedule(run: ChatRun) {
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

    /** Projects waiting for an automatic APK rebuild & install right after the agent finishes fixing a build error. */
    private val autoRebuildProjects = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /** Prepend `<workspace>/.forge/memory.md` (if present and non-blank, up to 4 KB) to the prompt context. */
    private fun augmentWithProjectMemory(projectId: String, prompt: String): String {
        val memText = runCatching {
            val ws = checkpoints.ensureWorkspace(projectId)
            val memFile = checkpoints.safeWorkspaceFile(ws, ".forge/memory.md")
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
    private fun applyAgentProjectMemory(run: ChatRun) {
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
                val ws = checkpoints.ensureWorkspace(run.projectId)
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
    fun openProjectMemory() {
        val project = _state.value.activeProject ?: return
        val path = ".forge/memory.md"
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
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

    /** Sends the current Android build failure (parsed issues or raw Gradle tail log) to the coding agent, optionally scheduling an automatic rebuild on completion. */
    fun fixBuildWithAgent(autoRebuild: Boolean = false) {
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
    fun fixLogcatCrashWithAgent() {
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
    fun reviewChangesWithSecondAgent() {
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
    fun acceptAndCommitChanges() {
        val project = _state.value.activeProject ?: return
        val pending = _state.value.changedItems.filter { it.accepted == null }
        if (pending.isEmpty()) return
        val fileNames = pending.take(3).joinToString(", ") { it.path.substringAfterLast('/') }
        val more = if (pending.size > 3) " +${pending.size - 3} more" else ""
        val commitMsg = "Apply agent changes ($fileNames$more)"
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_keep)) { checkpoints.acceptAll(project.id) } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
            commitProjectRepo(commitMsg)
        }
    }

    /** Compacts older messages of the active chat into a single summary turn, keeping the last 4 messages intact and resetting the CLI session ID so the next turn uses a lean context window. */
    fun compactActiveChat() {
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
    private fun handleQuickSlashCommand(text: String): Boolean {
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
    private fun handleLoopCommand(text: String): Boolean {
        val match = Regex("""^/loop\s+(\S+)(?:\s+([\s\S]+))?$""", RegexOption.IGNORE_CASE).matchEntire(text.trim()) ?: return false
        val first = match.groupValues[1]
        if (first.equals("off", ignoreCase = true)) {
            val chatId = _state.value.activeChatId
            dropScheduledTasks { it.chatId == chatId }
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

    fun addScheduledTask(prompt: String, minutes: Int) {
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

    fun removeScheduledTask(id: String) = dropScheduledTasks { it.id == id }

    fun setScheduledTaskEnabled(id: String, enabled: Boolean) {
        val now = System.currentTimeMillis()
        saveScheduled(_state.value.scheduledTasks.map {
            // Turning a task back on waits one full interval instead of firing at once for all the time it was off.
            if (it.id == id) it.copy(enabled = enabled, nextRunAtMillis = if (enabled) now + it.intervalMinutes * 60_000L else it.nextRunAtMillis) else it
        })
    }

    fun setScheduleEnabled(enabled: Boolean) {
        preferences.scheduleEnabled = enabled
        _state.update { it.copy(scheduleEnabled = enabled) }
    }

    // --- Routing of messages between agents (single agent unless the user picked another mode in the chat) ---

    fun setRouteMode(mode: RouteMode) {
        updateRouting(_state.value.routing.copy(mode = mode))
        if (mode != RouteMode.SINGLE) refreshReadyAgents()
    }

    /** [agents] is the priority/rotation order; an agent left out of the list is not used by Failover or Balanced. */
    fun setRouteAgents(agents: List<AgentKind>) = updateRouting(_state.value.routing.copy(agents = agents.distinct()))

    /** Balanced mode: [weight] (0..9) is the agent's share of messages relative to the others; equal weights are 50/50. */
    fun setRouteWeight(agent: AgentKind, weight: Int) {
        val current = _state.value.routing
        updateRouting(current.copy(weights = current.weights + (agent to weight.coerceIn(0, RoutingConfig.MAX_WEIGHT))))
    }

    /** Adds a rule: messages containing one of the comma-separated [keywords] go to [agent]. Returns false for no keywords. */
    fun addRouteRule(keywords: String, agent: AgentKind): Boolean {
        val words = PromptRule.parseKeywords(keywords)
        if (words.isEmpty()) return false
        val current = _state.value.routing
        updateRouting(current.copy(rules = current.rules + PromptRule(words, agent)))
        return true
    }

    fun removeRouteRule(index: Int) {
        val current = _state.value.routing
        if (index in current.rules.indices) updateRouting(current.copy(rules = current.rules.filterIndexed { i, _ -> i != index }))
    }

    /** Balanced mode: every agent weighs the same (50/50 with two agents). */
    fun resetRouteWeights() = updateRouting(_state.value.routing.copy(weights = emptyMap()))

    private fun updateRouting(config: RoutingConfig) {
        preferences.routing = config
        _state.update { it.copy(routing = config) }
    }

    /** Checks off the main thread which agents have credentials (it reads the vault and Claude's login file). */
    fun refreshReadyAgents() {
        viewModelScope.launch {
            val ready = withContext(Dispatchers.IO) { AgentKind.entries.filter(::agentReady).toSet() }
            _state.update { it.copy(readyAgents = ready) }
        }
    }

    /** Reads every agent's profile off the main thread (the vault is touched for the key check). */
    fun refreshAgentProfiles() {
        viewModelScope.launch {
            val profiles = withContext(Dispatchers.IO) { AgentKind.entries.associateWith { agentProfile(it) } }
            _state.update { it.copy(agentProfiles = profiles) }
        }
    }

    /**
     * Sets the model of [agent], which need not be the selected one: with several agents in a chat each answers with its
     * own model. Antigravity keeps its model (and effort) globally; the others keep a profile per agent.
     */
    fun setAgentModel(agent: AgentKind, modelId: String) {
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

    private fun agentProfile(agent: AgentKind): ProviderProfile {
        if (agent == _state.value.agentKind) return _state.value.providerProfile
        val loaded = preferences.loadProvider(vault, agent)
        return loaded.copy(hasSecret = !resolveProviderSecret(loaded).isNullOrBlank())
    }

    /**
     * Whether [agent] can run: Claude needs a key or its subscription login (the login file belongs to root, so it is
     * checked with su), Antigravity its own login, DeepSeek a key. Runs su, so call it off the main thread only.
     */
    private fun agentReady(agent: AgentKind): Boolean = when (agent) {
        AgentKind.CLAUDE_CODE -> agentProfile(agent).hasSecret || claudeAuth.hasCredential()
        AgentKind.ANTIGRAVITY ->
            antigravityAuth.state.value.status == com.nextcraft.forge.runtime.AntigravityAuthStatus.SIGNED_IN ||
                antigravityAuth.hasOfficialCredential()
        AgentKind.DEEPSEEK_HARNESS -> agentProfile(agent).hasSecret
    }

    /** The routing decision for [text]; null when no agent is left (all failed or none has credentials). */
    private fun routeFor(text: String, tried: Set<AgentKind> = emptySet()): RoutedMessage? {
        val here = _state.value
        // Readiness is checked off the main thread (refreshReadyAgents); until the first check only the selected agent counts.
        val ready = here.readyAgents.ifEmpty { setOf(here.agentKind) }
        return AgentRouter.route(text, here.routing, here.agentKind, ready, preferences.routeCounter, tried)
    }

    /** The agent a new message would most likely go to; only used to decide whether a run slot is free. */
    private fun tentativeAgent(text: String): AgentKind =
        if (_state.value.routing.mode == RouteMode.SINGLE) _state.value.agentKind else routeFor(text)?.agent ?: _state.value.agentKind

    /**
     * Moves a failed turn to the next agent when the mode allows it. The failed reply stays in the chat with a note,
     * and the same message is sent again without being added to the history a second time.
     */
    /** Messages that already got their one automatic retry (see [tryAutoRetry]). */
    private val autoRetriedPrompts = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * A turn that failed for a passing reason (Google's stream went idle, a dropped connection, a 5xx) is sent again
     * once by itself: the agent resumes the same conversation, so nothing it did is lost. A second failure of the same
     * message is shown with "Try again" as before.
     */
    private fun tryAutoRetry(run: ChatRun, reason: String): Boolean {
        if (!isTransientAgentFailure(reason) || !autoRetriedPrompts.add(run.lastPrompt.id)) return false
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

    private fun tryFailover(run: ChatRun, reason: String): Boolean {
        if (!AgentRouter.failsOver(_state.value.routing.mode)) return false
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

    private fun enqueuePrompt(prompt: QueuedPrompt) {
        _state.update { it.copy(queuedPrompts = it.queuedPrompts + prompt) }
    }

    /**
     * All agents (Claude Code, Antigravity CLI, DeepSeek Harness) can work on multiple chats and multiple projects
     * concurrently (one isolated bridge instance and process per chat run). Hardware RAM is the governor so the
     * system never kills processes under low memory.
     */
    private fun canStartAnotherRun(agent: AgentKind = _state.value.agentKind, ignoringKey: RunKey? = null): Boolean {
        val active = if (ignoringKey == null) runs else runs.filterKeys { it != ignoringKey }
        if (active.isEmpty()) return true
        if (active.size >= MAX_PARALLEL_CHATS) return false
        val memory = android.app.ActivityManager.MemoryInfo()
        getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(memory)
        val minFreeBytes = when (agent) {
            AgentKind.CLAUDE_CODE -> MIN_FREE_BYTES_FOR_CLAUDE_RUN
            AgentKind.ANTIGRAVITY, AgentKind.DEEPSEEK_HARNESS -> MIN_FREE_BYTES_FOR_LIGHT_RUN
        }
        return !memory.lowMemory && memory.availMem >= minFreeBytes
    }

    private fun newClaudeBridge(project: Project): ClaudeRuntimeBridge = ClaudeRuntimeBridge(
        context = getApplication(),
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    ).also { it.configureProjectRoot(project.id, project.rootPath) }

    private fun newDshBridge(project: Project): DshRuntimeBridge = DshRuntimeBridge(
        context = getApplication(),
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    ).also { it.configureProjectRoot(project.id, project.rootPath) }

    private fun newAntigravityBridge(project: Project, chatId: String): AntigravityRuntimeBridge = AntigravityRuntimeBridge(
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

    private fun startRun(project: Project, chatId: String, prompt: QueuedPrompt, tried: Set<AgentKind> = emptySet(), resend: Boolean = false) {
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
        val promptWithMemory = augmentWithProjectMemory(project.id, basePrompt)
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
            AgentKind.CLAUDE_CODE -> newClaudeBridge(project)
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
                withContext(Dispatchers.IO) { githubOperationLock.withLock { } }
                // An isolated chat works in its own clone of the project (created on first use).
                val branch = if (run.isolated) branchLock(project.id, chatId).withLock { chatBranches.ensure(project, chatId) } else null
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
                    val app = getApplication<Application>()
                    if (tryFailover(run, error.message ?: app.getString(R.string.agent_turn_error_unknown))) return@launch
                    appendAssistantText(run, "\n\n" + app.getString(R.string.agent_turn_error, error.message ?: app.getString(R.string.agent_turn_error_unknown)))
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
    private fun showRunView(run: ChatRun) {
        if (run.projectId != _state.value.activeProjectId || run.chatId != _state.value.activeChatId) return
        _state.update { it.copy(activeStatusLine = run.statusLine, pendingToolRequest = run.pendingTool) }
    }

    private fun setRunStatus(run: ChatRun, line: UiText?) {
        run.statusLine = line
        if (run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId) {
            _state.update { it.copy(activeStatusLine = line) }
        }
        publishRunningFlags()
    }

    private fun setRunPendingTool(run: ChatRun, request: ToolRequest?) {
        run.pendingTool = request
        if (run.projectId == _state.value.activeProjectId && run.chatId == _state.value.activeChatId) {
            _state.update { it.copy(pendingToolRequest = request) }
        }
        publishRunningFlags()
    }

    /** Applies [change] to the chat's messages: kept in the run, and shown when that chat is the visible one. */
    private fun updateRunMessages(run: ChatRun, change: (List<ChatMessage>) -> List<ChatMessage>) {
        applyPendingText(run)
        run.messages = change(run.messages)
        publishRunMessages(run)
    }

    /** Folds streamed text that is still buffered into the last assistant message (see [appendAssistantText]). */
    private fun applyPendingText(run: ChatRun) {
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
     * Shows [ChatRun.messages] in the open chat at most every [MESSAGE_PUBLISH_INTERVAL_MS].
     */
    private fun publishRunMessages(run: ChatRun, immediate: Boolean = false) {
        if (run.projectId != _state.value.activeProjectId || run.chatId != _state.value.activeChatId) return
        val now = android.os.SystemClock.uptimeMillis()
        val wait = run.lastPublishAt + MESSAGE_PUBLISH_INTERVAL_MS - now
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

    private fun publishRunningFlags() {
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

    private fun offerRetry(run: ChatRun) {
        if (run.projectId == _state.value.activeProjectId) {
            _state.update { it.copy(retryPrompts = it.retryPrompts + (run.chatId to RetryPrompt(run.chatId, run.lastPrompt))) }
        }
    }

    /** "Try again" after a failed turn: sends the same message (and attachments) once more. */
    fun retryLastPrompt() {
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

    /** Stops the Android build of the active project only; a running agent session keeps going. */
    fun stopAndroidBuild() {
        val projectId = _state.value.activeProjectId
        val handle = projectBuildHandles[projectId]
        if (handle != null) {
            handle.stopHook()
        } else {
            RuntimeTaskController.stop(RuntimeTaskController.BUILD)
        }
    }

    fun removeQueuedPrompt(id: String) {
        _state.update { st -> st.copy(queuedPrompts = st.queuedPrompts.filterNot { it.id == id }) }
    }

    /** Sends what was queued for each chat across all projects as one follow-up prompt, as soon as that chat is idle and a slot is free. */
    private fun dispatchQueuedPrompts() {
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
    private fun discardQueuedPrompts(projectId: String, chatId: String) {
        if (_state.value.queuedPrompts.none { (it.projectId ?: _state.value.activeProjectId) == projectId && it.chatId == chatId }) return
        _state.update { st ->
            st.copy(
                queuedPrompts = st.queuedPrompts.filterNot { (it.projectId ?: st.activeProjectId) == projectId && it.chatId == chatId },
                bannerMessage = UiText.res(R.string.msg_queue_discarded),
            )
        }
    }

    /** Stops the agent turn of the chat on screen only; other chats keep working and a running build keeps going. */
    fun stopActiveTask() {
        val run = runs[runKey(_state.value.activeProjectId, _state.value.activeChatId)] ?: return
        stopRun(run, discardQueue = true)
    }

    /** Stops the agent turn of any specific project and chat (used by the Dynamic Multi-Agent Live Activity HUD). */
    fun stopProjectChatRun(projectId: String, chatId: String) {
        val run = runs[runKey(projectId, chatId)] ?: return
        stopRun(run, discardQueue = true)
    }

    /**
     * "Steer": sends a queued message now instead of after the turn. The running turn is stopped (what it already did stays
     * in the workspace and in the chat) and the message starts a new turn right away with the conversation so far; the other
     * queued messages keep waiting. This works for every agent, since none of the CLIs takes a new message in the middle of a
     * turn that was started with its prompt on stdin.
     */
    fun steerQueuedPrompt(id: String) {
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

    private fun stopRun(run: ChatRun, discardQueue: Boolean, afterStop: (() -> Unit)? = null) {
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

    fun respondToToolRequest(approved: Boolean) {
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

    private fun onRuntimeEvent(run: ChatRun, event: RuntimeEvent) {
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
                // An isolated chat's changes live in its branch; the project's review list is not theirs.
                if (!run.isolated && _state.value.activeProjectId == run.projectId) {
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
    private fun appendFailureReason(run: ChatRun, reason: String) {
        applyPendingText(run)
        val reply = run.messages.lastOrNull { !it.fromUser }?.text.orEmpty()
        val head = reason.trim().lineSequence().firstOrNull().orEmpty().take(80)
        val shown = head.isNotBlank() && reply.contains(head)
        if (!shown) appendAssistantText(run, "\n\n[$reason]")
    }

    /** Buffers a streamed delta; it reaches [ChatRun.messages] at the next publish or message change. */
    private fun appendAssistantText(run: ChatRun, delta: String) {
        if (delta.isEmpty()) return
        run.pendingText.append(delta)
        publishRunMessages(run)
    }

    private fun recordActivityItem(run: ChatRun, title: String, rawDetail: String, isComplete: Boolean) {
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

    private fun finishRun(run: ChatRun, stoppedByUser: Boolean = false) {
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
        if (preferences.autoKeepChanges && projectIdle) {
            // Auto-save: the agent's edits are kept right away instead of waiting in "Review changes".
            viewModelScope.launch {
                withContext(Dispatchers.IO) { runCatching { checkpoints.acceptAll(run.projectId) } }
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

    /**
     * Runs a workspace/checkpoint operation on IO and turns a failure into a banner. These throw for paths the agent
     * turned into symlinks or that escape the workspace, and for IO errors; uncaught in viewModelScope that kills
     * the app.
     */
    private suspend fun <T> workspaceIo(failure: String, block: () -> T): T? = try {
        withContext(Dispatchers.IO) { block() }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("MainViewModel", failure, e)
        _state.update { it.copy(bannerMessage = UiText.Raw("$failure: ${e.message ?: e.javaClass.simpleName}")) }
        null
    }

    private var workspaceRefreshRequest = 0

    /**
     * `build` is hidden from the file tree, but the way down to `build/outputs/apk` stays open
     * so a freshly built APK can be found (and installed from the row menu).
     */
    private fun isApkOutputPath(ws: File, dir: File): Boolean {
        val parts = dir.relativeTo(ws).invariantSeparatorsPath.split('/')
        val at = parts.indexOf("build")
        if (at < 0) return false
        if (parts.take(at).any { it in setOf(".git", ".gradle", "node_modules", "__pycache__") }) return false
        val below = parts.drop(at + 1)
        return below.isEmpty() || (below.size <= 3 && below[0] == "outputs" && (below.size == 1 || below[1] == "apk"))
    }

    /** Refreshes the file list of the open project (the REA file picker reads it). */
    fun refreshWorkspaceFiles() {
        _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
    }

    fun refreshWorkspaceFilesAndChanges(projectId: String) {
        val requestId = ++workspaceRefreshRequest
        viewModelScope.launch {
            val (entries, changes) = workspaceIo(str(R.string.err_ws_read)) {
                val ws = checkpoints.ensureWorkspace(projectId)
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
                val changedPaths = checkpoints.readChangedPaths(projectId)
                val changeDetails = if (changedPaths.isNotEmpty()) {
                    checkpoints.buildChangeDetails(projectId, ws, changedPaths)
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
    suspend fun listWorkspaceDirectory(relativeDir: String): WorkspaceDirListing? {
        val project = _state.value.activeProject ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val root = checkpoints.ensureWorkspace(project.id).canonicalFile
                val clean = relativeDir.trim('/')
                if (clean.split('/').any { it == ".." }) return@runCatching null
                val dir = if (clean.isEmpty()) root else File(root, clean).canonicalFile
                if (!dir.toPath().startsWith(root.toPath()) || !dir.isDirectory) return@runCatching null
                val children = dir.listFiles().orEmpty().filter { !Files.isSymbolicLink(it.toPath()) }
                val entries = children.take(MAX_DIRECTORY_ENTRIES).map { child ->
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
                WorkspaceDirListing(entries, truncated = children.size > MAX_DIRECTORY_ENTRIES)
            }.getOrNull()
        }
    }

    private var openFileRequest = 0

    /** What to do once the user has answered [UiState.unsavedChangesPrompt]. */
    private var afterUnsavedChoice: (() -> Unit)? = null

    /** Runs [action] now, or first asks to save or discard unsaved edits in the open file. */
    private fun leaveOpenFile(action: () -> Unit) {
        val current = _state.value
        val path = current.selectedFilePath
        if (path == null || !current.selectedFileDirty || !current.selectedFileEditable) {
            action()
            return
        }
        afterUnsavedChoice = action
        _state.update { it.copy(unsavedChangesPrompt = path) }
    }

    fun saveAndContinue() {
        val next = afterUnsavedChoice
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null) }
        saveSelectedFile(onSaved = next)
    }

    fun discardAndContinue() {
        val next = afterUnsavedChoice
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null, selectedFileDirty = false) }
        next?.invoke()
    }

    fun cancelLeavingFile() {
        afterUnsavedChoice = null
        _state.update { it.copy(unsavedChangesPrompt = null) }
    }

    /** Opens one chat of any project directly (used by the Dynamic Multi-Agent Live Activity HUD). */
    fun openProjectChat(projectId: String, chatId: String) = leaveOpenFile {
        if (_state.value.activeProjectId != projectId) {
            selectProject(projectId)
        }
        selectChat(chatId)
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
    }

    /** Opens a project's Android Build screen directly (used by the Dynamic Multi-Agent Live Activity HUD). */
    fun openProjectBuild(projectId: String) = leaveOpenFile {
        if (_state.value.activeProjectId != projectId) {
            selectProject(projectId)
        }
        _state.update { it.copy(activeTab = ForgeTab.ANDROID) }
    }

    /** Opens a project from the project list, asking about unsaved edits first; shows the agent only if it switched. */
    fun openProject(projectId: String) = leaveOpenFile {
        selectProject(projectId)
        if (_state.value.activeProjectId == projectId) {
            _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
        }
    }

    /** Switches the in-project bottom bar; Files and Preview refresh their data like the global tabs do. */
    fun selectProjectPane(pane: ProjectPane) {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectPane = pane) }
        refreshPaneData(pane)
    }

    /** Two-pane layout: the workspace pane appears beside the chat without changing [ForgeUiState.projectPane]. */
    fun onSidePaneShown(pane: ProjectPane) = refreshPaneData(pane)

    private fun refreshPaneData(pane: ProjectPane) {
        if (pane == ProjectPane.FILES) _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
        if (pane == ProjectPane.PREVIEW) scanLocalPreviewPorts()
    }

    /** Shows the active chat's conversation under the Projects tab. */
    fun openConversation() {
        _state.update { it.copy(activeTab = ForgeTab.PROJECTS, projectView = ProjectView.CONVERSATION, projectPane = ProjectPane.CHAT) }
    }

    /** Opens one chat of the active project and shows its conversation. */
    fun openChat(chatId: String) {
        selectChat(chatId)
        openConversation()
    }

    /** One step up inside the Projects tab; false when already on the project list. */
    fun projectBack(paneBesideChat: Boolean = false): Boolean {
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
    fun openFilesTabForEditing(relativePath: String) {
        openWorkspaceFile(relativePath)
        selectTab(ForgeTab.FILES)
    }

    fun openWorkspaceFile(relativePath: String) {
        if (relativePath == _state.value.selectedFilePath) {
            // Tapping the file that is already open keeps its unsaved edits; otherwise it is re-read from disk.
            if (!_state.value.selectedFileDirty) loadWorkspaceFile(relativePath)
            return
        }
        leaveOpenFile { loadWorkspaceFile(relativePath) }
    }

    /** Loads [relativePath] into the editor, replacing whatever is open (callers decide about unsaved edits). */
    private fun loadWorkspaceFile(relativePath: String, onlyIfClean: Boolean = false) {
        val project = _state.value.activeProject ?: return
        val requestId = ++openFileRequest
        viewModelScope.launch {
            // Saving a truncated, binary or unreadable file back as text would destroy it, so those open read-only.
            val (content, editable) = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, relativePath)
                    if (!target.isFile) {
                        "" to true
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

    fun updateSelectedFileContent(newContent: String) {
        _state.update {
            it.copy(
                selectedFileContent = newContent,
                selectedFileDirty = true,
                fileSavedBanner = null,
            )
        }
    }

    fun saveSelectedFile(onSaved: (() -> Unit)? = null) {
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
                    val ws = checkpoints.ensureWorkspace(project.id)
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

    fun createNewWorkspaceFile(relativePath: String, initialContent: String = "") {
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
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
    fun createWorkspaceFolder(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/').trimEnd('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching { checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(project.id), cleanPath).mkdirs() }.getOrDefault(false)
            }
            if (ok) refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    /** Renames a file or folder in place (same parent); the open editor follows the file. */
    fun renameWorkspaceEntry(relativePath: String, newName: String) {
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
                    val ws = checkpoints.ensureWorkspace(project.id)
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
    fun importFilesToWorkspace(uris: List<android.net.Uri>, targetDir: String = "") {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty()) return
        val dir = targetDir.trim().trimStart('/').trimEnd('/')
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, dir) }
            if (copied.isNotEmpty()) {
                _state.update { it.copy(fileSavedBanner = quantity(R.plurals.msg_files_uploaded, copied.size)) }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    /** Picks files for the next prompt: stored under attachments/ so the agent can read them. */
    fun attachFilesToPrompt(uris: List<android.net.Uri>) {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val copied = withContext(Dispatchers.IO) { copyUrisIntoWorkspace(project.id, uris, "attachments") }
            if (copied.isNotEmpty()) {
                _state.update { it.copy(pendingAttachments = it.pendingAttachments + copied) }
            }
        }
    }

    /**
     * Walks through the app's main screens, saves a picture of each in the project's attachments folder and adds them
     * to the message being written, so the agent can look at the UI. The screen the person was on is restored.
     */
    fun captureAppScreens(window: android.view.Window) {
        val project = _state.value.activeProject ?: return
        if (_state.value.capturingScreens) return
        val before = _state.value
        val originalTab = before.activeTab
        val originalView = before.projectView
        val originalPane = before.projectPane
        _state.update { it.copy(capturingScreens = true) }
        viewModelScope.launch {
            val folder = "attachments/app-screens-" + java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val added = mutableListOf<ChatAttachment>()
            try {
                // An open keyboard hides the bottom bar and leaves a black gap in the pictures; close it first.
                window.currentFocus?.clearFocus()
                getApplication<Application>().getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                    ?.hideSoftInputFromWindow(window.decorView.windowToken, 0)
                delay(KEYBOARD_CLOSE_MS)
                appCaptureSteps().forEachIndexed { index, step ->
                    if (step.pane != null) {
                        _state.update { it.copy(projectView = ProjectView.CONVERSATION) }
                        selectProjectPane(step.pane)
                    } else {
                        if (step.tab == ForgeTab.PROJECTS) _state.update { it.copy(projectView = ProjectView.LIST) }
                        selectTab(step.tab)
                    }
                    delay(CAPTURE_SETTLE_MS + step.extraSettleMs)
                    val bitmap = captureWindow(window) ?: return@forEachIndexed
                    val name = "%02d-%s.png".format(index + 1, step.name)
                    withContext(Dispatchers.IO) { savePng(project.id, "$folder/$name", name, bitmap) }?.let { added += it }
                }
            } finally {
                _state.update {
                    it.copy(
                        activeTab = originalTab,
                        projectView = originalView,
                        projectPane = originalPane,
                        capturingScreens = false,
                        pendingAttachments = it.pendingAttachments + added,
                        bannerMessage = if (added.isEmpty()) UiText.res(R.string.capture_failed) else UiText.res(R.string.capture_done, added.size),
                    )
                }
            }
        }
    }

    private fun savePng(projectId: String, relativePath: String, displayName: String, bitmap: android.graphics.Bitmap): ChatAttachment? =
        runCatching {
            val target = checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(projectId), relativePath)
            target.parentFile?.mkdirs()
            target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            ChatAttachment(displayName = displayName, relativePath = relativePath, mimeType = "image/png", sizeBytes = target.length())
        }.onFailure { Log.w("MainViewModel", "Could not save $relativePath", it) }.getOrNull()

    fun removePendingAttachment(id: String) {
        _state.update { st -> st.copy(pendingAttachments = st.pendingAttachments.filterNot { it.id == id }) }
    }

    private fun copyUrisIntoWorkspace(projectId: String, uris: List<android.net.Uri>, dir: String): List<ChatAttachment> {
        val resolver = getApplication<Application>().contentResolver
        val ws = checkpoints.ensureWorkspace(projectId)
        val out = mutableListOf<ChatAttachment>()
        uris.forEach { uri ->
            runCatching {
                val display = queryDisplayName(uri) ?: "file-${System.currentTimeMillis()}"
                val safeName = display.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "file" }
                var rel = if (dir.isEmpty()) safeName else "$dir/$safeName"
                var target = checkpoints.safeWorkspaceFile(ws, rel)
                var n = 2
                while (target.exists()) {
                    val base = safeName.substringBeforeLast('.', safeName)
                    val ext = safeName.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
                    rel = (if (dir.isEmpty()) "" else "$dir/") + "$base-$n$ext"
                    target = checkpoints.safeWorkspaceFile(ws, rel)
                    n++
                }
                target.parentFile?.mkdirs()
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("No stream for $uri")
                out += ChatAttachment(
                    displayName = display,
                    relativePath = rel,
                    mimeType = resolver.getType(uri) ?: "application/octet-stream",
                    sizeBytes = target.length(),
                )
            }.onFailure { Log.w("MainViewModel", "Import failed for $uri", it) }
        }
        return out
    }

    private fun queryDisplayName(uri: android.net.Uri): String? =
        runCatching {
            getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** Writes a ZIP of [paths] (or the whole workspace when empty) to a document the user chose. */
    fun exportWorkspaceZip(target: android.net.Uri, paths: List<String>) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val ignored = setOf(".git", ".gradle", "build", "node_modules", "__pycache__")
                    val roots = if (paths.isEmpty()) listOf(ws) else paths.map { checkpoints.safeWorkspaceFile(ws, it) }
                    var written = 0
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                        java.util.zip.ZipOutputStream(os.buffered()).use { zip ->
                            roots.forEach { root ->
                                root.walkTopDown()
                                    .onEnter { d -> d == root || (d.name !in ignored && !Files.isSymbolicLink(d.toPath())) }
                                    .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
                                    .forEach { file ->
                                        zip.putNextEntry(java.util.zip.ZipEntry(file.relativeTo(ws).invariantSeparatorsPath))
                                        file.inputStream().use { it.copyTo(zip) }
                                        zip.closeEntry()
                                        written++
                                    }
                            }
                        }
                    } ?: error("Cannot open $target")
                    written
                }.getOrElse { Log.w("MainViewModel", "ZIP export failed", it); -1 }
            }
            _state.update {
                it.copy(
                    fileSavedBanner = if (count >= 0) quantity(R.plurals.msg_zip_saved, count) else str(R.string.msg_zip_failed),
                )
            }
        }
    }

    /** Copies one workspace file to a document the user chose (Storage Access Framework "save as"). */
    fun exportWorkspaceFile(target: android.net.Uri, relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val source = checkpoints.safeWorkspaceFile(ws, relativePath.trim().trimStart('/'))
                    check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Not a regular file" }
                    getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                        source.inputStream().use { it.copyTo(os) }
                    } ?: error("Cannot open $target")
                }.onFailure { Log.w("MainViewModel", "File export failed", it) }.isSuccess
            }
            val app = getApplication<Application>()
            _state.update {
                it.copy(
                    fileSavedBanner = if (ok) app.getString(R.string.msg_file_saved, relativePath.substringAfterLast('/')) else app.getString(R.string.msg_file_failed),
                )
            }
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    target.exists() && target.deleteRecursivelyNoFollow()
                }.getOrDefault(false)
            }
            if (deleted) {
                if (_state.value.selectedFilePath == cleanPath || _state.value.selectedFilePath?.startsWith("$cleanPath/") == true) {
                    _state.update {
                        it.copy(
                            selectedFilePath = null,
                            selectedFileContent = "",
                            selectedFileDirty = false,
                            fileSavedBanner = str(R.string.msg_file_deleted, cleanPath),
                        )
                    }
                } else {
                    _state.update { it.copy(fileSavedBanner = str(R.string.msg_file_deleted, cleanPath)) }
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun revertDiffHunk(path: String, hunkIndex: Int) {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_revert_file, path)) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertHunk(project.id, ws, path, hunkIndex)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
            if (_state.value.selectedFilePath == path) {
                loadWorkspaceFile(path)
            }
        }
    }

    fun cleanIdleDaemons() {
        com.nextcraft.forge.runtime.GradleDaemonReaper.stopIdleNow {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.resource_daemons_cleaned)) }
        }
    }

    fun fixCiFailureWithAgent(workflowName: String, branch: String, url: String) {
        val prompt = buildString {
            append("GitHub Actions workflow '").append(workflowName).append("' failed on branch '").append(branch).append("'")
            if (url.isNotBlank()) append(" (").append(url).append(")")
            append(".\nPlease inspect the workspace code, build configuration, and unit tests to diagnose and fix the CI failure.")
        }
        sendPrompt(prompt)
        openConversation()
    }

    fun captureUiInspectorHierarchy() {
        if (_state.value.uiInspectorBusy) return
        _state.update { it.copy(uiInspectorBusy = true) }
        viewModelScope.launch {
            val nodes = withContext(Dispatchers.IO) {
                runCatching {
                    val outFile = File(getApplication<Application>().cacheDir, "forge_ui_dump.xml")
                    outFile.delete()
                    val proc = ProcessBuilder(
                        "su",
                        "-c",
                        "uiautomator dump /sdcard/forge_ui_dump.xml >/dev/null 2>&1 && cat /sdcard/forge_ui_dump.xml; rm -f /sdcard/forge_ui_dump.xml",
                    ).redirectErrorStream(true).redirectOutput(outFile).start()
                    runCatching { proc.outputStream.close() }
                    val finished = proc.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
                    if (!finished) proc.destroyForcibly()
                    val xml = if (outFile.isFile) outFile.readText() else ""
                    outFile.delete()
                    parseUiInspectorXml(xml)
                }.getOrDefault(emptyList())
            }
            _state.update { it.copy(uiInspectorNodes = nodes, uiInspectorBusy = false) }
        }
    }

    fun editUiElementWithAgent(node: UiInspectorNode, instruction: String) {
        val cleanInstruction = instruction.trim()
        if (cleanInstruction.isEmpty()) return
        val prompt = buildString {
            append("Please update this UI element in the project:\n")
            append("- Class: `").append(node.className).append("`\n")
            if (node.id.isNotBlank()) append("- Resource ID / Tag: `").append(node.id).append("`\n")
            if (node.text.isNotBlank()) append("- Visible Text / Content Description: \"").append(node.text).append("\"\n")
            append("- Screen Bounds: `").append(node.bounds).append("`\n\n")
            append("Requested change: ").append(cleanInstruction)
        }
        sendPrompt(prompt)
        openConversation()
    }

    private fun unescapeXmlAttr(raw: String): String {
        if (!raw.contains('&')) return raw
        return raw
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace(Regex("&#(\\d+);")) { m ->
                m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value
            }
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value
            }
            .replace("&amp;", "&")
    }

    private fun parseUiInspectorXml(xml: String): List<UiInspectorNode> {
        if (xml.isBlank()) return emptyList()
        val nodeRegex = Regex("""<node\s+([^>]+?)/?>""")
        val attrRegex = Regex("""([a-zA-Z0-9_-]+)="([^"]*)"""")
        val boundsRegex = Regex("""\[(\d+),(\d+)]\[(\d+),(\d+)]""")
        val results = ArrayList<UiInspectorNode>()
        for (match in nodeRegex.findAll(xml)) {
            val attrs = HashMap<String, String>()
            for (attr in attrRegex.findAll(match.groupValues[1])) {
                attrs[attr.groupValues[1]] = unescapeXmlAttr(attr.groupValues[2])
            }
            val rawId = attrs["resource-id"].orEmpty().substringAfterLast('/')
            val rawClass = attrs["class"].orEmpty().substringAfterLast('.')
            val rawText = attrs["text"].orEmpty().ifBlank { attrs["content-desc"].orEmpty() }.trim()
            val rawBounds = attrs["bounds"].orEmpty()
            if (rawId.isEmpty() && rawText.isEmpty()) continue
            val bMatch = boundsRegex.find(rawBounds)
            val l = bMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val t = bMatch?.groupValues?.get(2)?.toIntOrNull() ?: 0
            val r = bMatch?.groupValues?.get(3)?.toIntOrNull() ?: 0
            val b = bMatch?.groupValues?.get(4)?.toIntOrNull() ?: 0
            if (r <= l || b <= t) continue
            results.add(
                UiInspectorNode(
                    id = rawId,
                    className = rawClass.ifBlank { "View" },
                    text = rawText,
                    bounds = rawBounds,
                    left = l,
                    top = t,
                    right = r,
                    bottom = b,
                ),
            )
            if (results.size >= 80) break
        }
        return results
    }

    fun revertSingleFile(path: String) {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_revert_file, path)) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertFile(project.id, ws, path)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
            if (_state.value.selectedFilePath == path) {
                // The user asked to revert this file, so the editor follows the file on disk.
                loadWorkspaceFile(path)
            }
        }
    }

    fun keepSingleFile(path: String) {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_keep_file, path)) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.acceptFile(project.id, ws, path)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun keepAllChanges() {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_keep)) { checkpoints.acceptAll(project.id) } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun revertAllChanges() {
        val project = _state.value.activeProject ?: return
        if (runs.keys.any { it.projectId == project.id }) return
        viewModelScope.launch {
            workspaceIo(str(R.string.err_ws_revert)) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertLatestTurn(project.id, ws)
            } ?: return@launch
            refreshWorkspaceFilesAndChanges(project.id)
            _state.value.selectedFilePath?.let { loadWorkspaceFile(it) }
        }
    }

    // --- Interactive terminal ---------------------------------------------------------------------------------
    // One general-purpose shell for the whole app (not tied to a project): the root chroot's home (/root) when the
    // runtime is installed and root is granted, otherwise the Android shell. It is run by the Termux terminal emulator
    // on its own PTY, so editors, pagers and TUIs (vim, htop, tmux, Claude Code) work. The UI (TerminalScreen)
    // attaches a TerminalView to the session; the shell starts when the view is first laid out. Projects live under
    // /workspace/<name>: use "cd" or the project chip, or "Run in terminal" from chat, which changes into the project.

    /** A line to type: [inProject] lines first change into the terminal's folder (resolved when typed). */
    private class TerminalInput(val text: String, val inProject: Boolean)

    /**
     * One shell. [chatId] null is the general terminal of the Terminal tab (the chroot's home); otherwise the shell
     * belongs to that chat and starts in the chat's own folder (its branch clone when the chat is isolated, else the
     * project folder), so every chat has a separate terminal.
     */
    private class TerminalHandle(val chatId: String?) {
        var session: TerminalSession? = null
        var finished = false
        var starting = false
        var inChroot = false

        /** Chat terminals: the folder as seen from the shell (resolved when the shell starts). */
        var guestDir: String? = null
        var hostDir: File? = null

        /** Commands sent before the shell exists (e.g. "Run in terminal" from chat). */
        val pending = mutableListOf<TerminalInput>()
    }

    private val terminals = HashMap<String, TerminalHandle>()

    /** Chat ids are only unique inside a project (every project has a chat called "main"), so the key holds both. */
    private fun terminalKey(chatId: String?): String = chatId?.let { "chat:${_state.value.activeProjectId}:$it" } ?: "main"

    private fun terminalHandle(chatId: String?): TerminalHandle =
        terminals.getOrPut(terminalKey(chatId)) { TerminalHandle(chatId) }

    val terminalSessionClient = ForgeTerminalSessionClient(application) { session -> onTerminalSessionEnded(session) }

    fun currentTerminalSession(chatId: String? = null): TerminalSession? = terminalHandle(chatId).session

    fun isTerminalFinished(chatId: String? = null): Boolean = terminalHandle(chatId).finished

    /** Where the terminal's folder is as seen from its shell: the project for the general terminal, the chat folder for a chat's. */
    fun terminalProjectDir(chatId: String? = null): String? {
        val handle = terminalHandle(chatId)
        if (chatId != null) return if (handle.inChroot) handle.guestDir else handle.hostDir?.absolutePath
        val project = _state.value.activeProject ?: return null
        // Known once the shell exists (commands are only typed then): the chroot mounts projects at /workspace/<name>.
        return if (handle.inChroot) "/workspace/${project.slug}" else checkpoints.ensureWorkspace(project.id).absolutePath
    }

    /** Starts the shell if there is none. An ended shell is replaced only by [restartTerminalSession]. */
    fun ensureTerminalSession() = ensureTerminalSession(null)

    fun ensureTerminalSession(chatId: String?) {
        val handle = terminalHandle(chatId)
        if (handle.session != null || handle.starting) return
        handle.starting = true
        viewModelScope.launch {
            try {
                // An isolated chat gets its clone at its first turn; make it now so the shell does not start in the main folder.
                if (chatId != null) prepareChatBranchFolder(chatId)
                val spec = withContext(Dispatchers.IO) { buildTerminalLaunchSpec(handle) }
                handle.inChroot = spec.inChroot
                handle.session = TerminalSession(
                    spec.shell,
                    spec.cwd,
                    spec.args,
                    spec.env,
                    TERMINAL_TRANSCRIPT_ROWS,
                    terminalSessionClient,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w("MainViewModel", "terminal session failed to start", e)
                _state.update { it.copy(bannerMessage = UiText.res(R.string.terminal_start_failed, e.message.orEmpty())) }
            } finally {
                handle.starting = false
                _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
            }
        }
    }

    private suspend fun prepareChatBranchFolder(chatId: String) {
        val project = _state.value.activeProject ?: return
        if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated != true) return
        runCatching { branchLock(chatId).withLock { chatBranches.ensure(project, chatId) } }
            .onFailure { Log.w("MainViewModel", "chat branch for the terminal failed", it) }
    }

    /** "Restart" after the shell ended: discards the dead session and starts a fresh one. */
    fun restartTerminalSession() = restartTerminalSession(null)

    fun restartTerminalSession(chatId: String?) {
        val handle = terminalHandle(chatId)
        handle.session?.finishIfRunning()
        handle.session = null
        handle.finished = false
        handle.pending.clear()
        _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
        ensureTerminalSession(chatId)
    }

    /** Ends the shell of a chat that was deleted. */
    private fun closeChatTerminal(chatId: String) {
        terminals.remove(terminalKey(chatId))?.let { handle ->
            handle.pending.clear()
            handle.session?.finishIfRunning()
        }
    }

    /** Ends every chat shell of [projectId] (its folders are about to be deleted). */
    private fun closeProjectTerminals(projectId: String) {
        terminals.keys.filter { it.startsWith("chat:$projectId:") }.forEach { key ->
            terminals.remove(key)?.let { handle ->
                handle.pending.clear()
                handle.session?.finishIfRunning()
            }
        }
    }

    /** Types [command] and Enter into the shell. Queued (and the shell started) when it is not up yet. */
    fun runTerminalCommand(command: String) = runTerminalCommand(command, null)

    fun runTerminalCommand(command: String, chatId: String?) = queueTerminalInput(command, inProject = false, chatId = chatId)

    private fun queueTerminalInput(command: String, inProject: Boolean, chatId: String? = null) {
        val clean = command.trim()
        if (clean.isEmpty()) return
        val handle = terminalHandle(chatId)
        // Asking for a command on an ended shell means "run it": start a fresh shell rather than dropping the request.
        if (handle.finished) restartTerminalSession(chatId)
        handle.pending += TerminalInput(clean, inProject)
        ensureTerminalSession(chatId)
        flushTerminalInput(chatId)
    }

    /**
     * Opens [relativePath] (relative to the active project) in a terminal editor: nano when installed, else vi.
     * The Termux terminal draws it, so arrows, Ctrl and Esc work through the key row.
     */
    fun editInTerminal(relativePath: String) {
        val file = shellSingleQuote(relativePath)
        runTerminalCommandInProject("if command -v nano >/dev/null 2>&1; then nano -- $file; else vi -- $file; fi")
        selectTab(ForgeTab.TERMINAL)
    }

    /** Files row menu: picks [relativePath] as the target of the REA screen and opens it. */
    fun analyzeWithRea(relativePath: String) {
        rea.selectTarget(relativePath)
        selectTab(ForgeTab.REA)
    }

    /** Runs [command] in the active project's folder in the general terminal. */
    fun runTerminalCommandInProject(command: String) = queueTerminalInput(command, inProject = true)

    /** "Run in terminal" from chat: runs [command] in the terminal of the chat on screen, which starts in that chat's folder. */
    fun runInChatTerminal(command: String) {
        val chatId = _state.value.activeChatId
        queueTerminalInput(command, inProject = false, chatId = chatId)
        selectProjectPane(ProjectPane.TERMINAL)
    }

    /** Sends queued input once the emulator behind the session exists (it is created on first layout). */
    fun flushTerminalInput() = flushTerminalInput(null)

    fun flushTerminalInput(chatId: String?) {
        val handle = terminalHandle(chatId)
        val session = handle.session ?: return
        if (session.emulator == null || handle.finished) return
        val queued = handle.pending.toList()
        handle.pending.clear()
        queued.forEach { input ->
            val dir = if (input.inProject) terminalProjectDir(chatId) else null
            session.write((if (dir == null) input.text else "cd ${shellSingleQuote(dir)} && ${input.text}") + "\r")
        }
    }

    fun clearTerminal() = clearTerminal(null)

    fun clearTerminal(chatId: String?) {
        // reset() clears the screen and attributes; the shell redraws its prompt on the next key.
        terminalHandle(chatId).session?.reset()
    }

    private fun onTerminalSessionEnded(session: TerminalSession) {
        val handle = terminals.values.firstOrNull { it.session === session } ?: return
        handle.finished = true
        handle.pending.clear()
        _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
    }

    private class TerminalLaunchSpec(
        val shell: String,
        val cwd: String,
        val args: Array<String>,
        val env: Array<String>,
        val inChroot: Boolean,
    )

    /** Blocking (file IO, root probe): call off the main thread. */
    private fun buildTerminalLaunchSpec(handle: TerminalHandle): TerminalLaunchSpec {
        val app = getApplication<Application>()
        val env = System.getenv().toMutableMap().apply {
            put("TERM", "xterm-256color")
            put("COLORTERM", "truecolor")
            put("LANG", "C.UTF-8")
        }
        val chroot = installer.isInstalled() && isRootAccessAvailable() && installer.installedRuntime().chrootExec.isFile
        return if (chroot) {
            // The guest environment (PATH, HOME, TERM, provider keys) travels in a one-shot env file sourced by su.
            // /root is the chroot's home, so the shell is not tied to any project.
            // The general terminal is not tied to a project; a chat's terminal starts in that chat's folder.
            val folder = handle.chatId?.let { chatTerminalFolder(it) }
            handle.guestDir = folder?.let { "/workspace/${it.second}" }
            handle.hostDir = folder?.first
            val (_, suCommand) = installer.prepareChrootShellCommand(
                workspace = folder?.first ?: File(RuntimeInstaller.rootfsDir(app), "root"),
                guestWorkspacePath = folder?.let { "/workspace/${it.second}" } ?: "/root",
                environment = activeChrootEnvironment(),
                guestCommand = listOf("/bin/bash", "-l"),
            )
            TerminalLaunchSpec("su", app.filesDir.absolutePath, arrayOf("su", "-mm", "-c", suCommand), env.toEnvArray(), inChroot = true)
        } else {
            val folder = handle.chatId?.let { chatTerminalFolder(it) }
            handle.hostDir = folder?.first
            val home = folder?.first?.absolutePath ?: app.filesDir.absolutePath
            env["HOME"] = app.filesDir.absolutePath
            TerminalLaunchSpec("/system/bin/sh", home, arrayOf("sh"), env.toEnvArray(), inChroot = false)
        }
    }

    /** Folder of [chatId] on the host and its name under /workspace: the branch clone of an isolated chat, else the project. */
    private fun chatTerminalFolder(chatId: String): Pair<File, String>? {
        val project = _state.value.activeProject ?: return null
        val chat = _state.value.chats.firstOrNull { it.id == chatId }
        if (chat?.isolated == true) {
            val branch = chatBranches.branchFor(project, chatId)
            if (branch.cloneDir.isDirectory) return branch.cloneDir to branch.slug
        }
        return checkpoints.ensureWorkspace(project.id) to project.slug
    }

    private fun Map<String, String>.toEnvArray(): Array<String> = map { "${it.key}=${it.value}" }.toTypedArray()

    private fun activeChrootEnvironment(): Map<String, String> {
        val profile = _state.value.providerProfile
        // A Claude login made with `claude auth login` must stay in charge in the terminal too: an access token copied
        // into CLAUDE_CODE_OAUTH_TOKEN is never refreshed and would shadow it once it expires.
        val storedLogin = profile.kind == ProviderKind.CLAUDE &&
            listOf(ProviderKind.CLAUDE, ProviderKind.ANTHROPIC).none { !vault.get(it.name).isNullOrBlank() } &&
            installer.hasClaudeLoginCredential()
        val secret = if (storedLogin) null else resolveProviderSecret(profile)
        return runCatching {
            RuntimeLaunchConfigBuilder.build(profile, secret, useStoredLogin = storedLogin).environment
        }.getOrDefault(emptyMap())
    }

    private val chrootProcessFiles: MutableMap<Process, Pair<File, File>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())

    private fun stopChrootProcess(process: Process?) {
        if (process == null) return
        val files = chrootProcessFiles[process]
        if (files != null) {
            com.nextcraft.forge.runtime.GuestProcess.signal(files.second, 15, forceAfterSeconds = 3)
        }
        process.destroyForcibly()
    }

    private fun cleanupChrootProcess(process: Process?) {
        if (process == null) return
        val files = chrootProcessFiles.remove(process) ?: return
        files.first.delete()
        files.second.delete()
    }

    /** Starts [command] inside the Root Chroot (Gradle, git and the SDK only exist there). */
    private fun startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process {
        check(installer.isInstalled() && isRootAccessAvailable()) {
            "Root access and the installed Linux runtime are required to run this."
        }
        val (envFile, suCommand) = installer.prepareChrootShellCommand(
            workspace = workspaceDir,
            guestWorkspacePath = "/workspace/$projectSlug",
            environment = activeChrootEnvironment(),
            guestCommand = listOf("/bin/bash", "-lc", command),
        )
        val process = try {
            ProcessBuilder("su", "-mm", "-c", suCommand).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
        chrootProcessFiles[process] = envFile to com.nextcraft.forge.runtime.GuestProcess.pidFileFor(envFile)
        return process
    }

    private fun runCommandInChrootOrHost(
        projectSlug: String,
        workspaceDir: File,
        command: String,
        onOutputChunk: ((String) -> Unit)? = null,
    ): String {
        return runCatching {
            val process = if (installer.isInstalled() && isRootAccessAvailable()) {
                val installed = installer.installedRuntime()
                if (installed.chrootExec.isFile) {
                    startChrootProcess(projectSlug, workspaceDir, command)
                } else {
                    ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
                }
            } else {
                ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
            }
            activeTerminalProcess = process
            val buffer = StringBuilder()
            // The read below blocks until the command closes its output, so the timeout needs its own watchdog.
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread({
                try {
                    if (!process.waitFor(60, TimeUnit.SECONDS)) {
                        timedOut.set(true)
                        stopChrootProcess(process)
                    }
                } catch (_: InterruptedException) {
                }
            }, "forge-terminal-watchdog").apply { isDaemon = true; start() }
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val chunk = CharArray(4096)
                    while (buffer.length < MAX_TERMINAL_CHARS) {
                        val n = reader.read(chunk)
                        if (n <= 0) break
                        val text = String(chunk, 0, n)
                        buffer.append(text)
                        onOutputChunk?.invoke(text)
                    }
                }
            } finally {
                if (process.isAlive && !timedOut.get()) stopChrootProcess(process)
                watchdog.interrupt()
                cleanupChrootProcess(process)
                if (activeTerminalProcess === process) activeTerminalProcess = null
            }
            if (timedOut.get()) {
                val timeoutNotice = "\n[Command timed out after 60s]"
                buffer.append(timeoutNotice)
                onOutputChunk?.invoke(timeoutNotice)
            }
            buffer.toString()
        }.getOrElse { "Error executing command: ${it.message}" }
    }

    /** Runs [command] like the terminal, but throws when it exits non-zero (the plain runner only returns output). */
    private fun runCheckedCommand(projectSlug: String, workspaceDir: File, command: String): String {
        val wrapped = "{ $command\n} 2>&1; printf '\\n[forge-exit:%s]\\n' \"\$?\""
        val raw = runCommandInChrootOrHost(projectSlug, workspaceDir, wrapped)
        val code = Regex("""\[forge-exit:(\d+)]\s*$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val output = raw.substringBeforeLast("[forge-exit:").trim()
        check(code == 0) {
            output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" | ")
                .ifBlank { "command failed" }
        }
        return output
    }

    /**
     * Writes the GitHub PAT into the rootfs only while [block] runs, then removes it so agent
     * processes (UID 0 inside the chroot) can never read a long-lived credential file.
     */
    private inline fun <T> withGitHubCredentials(token: String, login: String, block: () -> T): T =
        // One GitHub operation at a time: a finishing sync would otherwise delete the credentials of another
        // one mid-push, and two git processes on the same workspace collide on .git/index.lock.
        githubOperationLock.withLock {
            installer.configureGitHubAccess(token, login)
            try {
                block()
            } finally {
                installer.clearGitHubAccess()
            }
        }

    private val githubOperationLock = java.util.concurrent.locks.ReentrantLock()

    /** The running-task notification names the build stage, so the lock screen shows more than "Running assembleDebug". */
    private fun pushBuildStageToNotification(stage: AndroidBuildStage, projectName: String) {
        val label = when (stage) {
            AndroidBuildStage.PREPARING -> R.string.build_stage_preparing
            AndroidBuildStage.RESOLVING -> R.string.build_stage_resolving
            AndroidBuildStage.COMPILING -> R.string.build_stage_compiling
            AndroidBuildStage.RESOURCES -> R.string.build_stage_resources
            AndroidBuildStage.PACKAGING -> R.string.build_stage_packaging
            AndroidBuildStage.FINDING_APK -> R.string.build_stage_finding_apk
            else -> return
        }
        startServiceSafely(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_PROGRESS)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, projectName)
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL, "$projectName · ${str(label)}"),
        )
    }

    fun runAndroidBuild(
        task: String = "assembleDebug",
        installAfterBuild: Boolean = false,
        targetProject: Project? = _state.value.activeProject,
    ) {
        val project = targetProject ?: return
        if (project.id in _state.value.buildingProjectIds || projectBuildHandles.containsKey(project.id)) return
        val startedAt = System.currentTimeMillis()
        val initialRecord = AndroidBuildRecord(
            phase = AndroidBuildPhase.BUILDING,
            stage = AndroidBuildStage.PREPARING,
            action = if (installAfterBuild) AndroidAction.BUILD_AND_RUN else AndroidAction.BUILD,
            startedAtMillis = startedAt,
            message = str(R.string.build_running_gradle, task),
            log = "",
        )
        _state.update {
            it.copy(
                androidBuild = if (it.activeProjectId == project.id) initialRecord else it.androidBuild,
                buildingProjectIds = it.buildingProjectIds + project.id,
                projectBuilds = it.projectBuilds + (project.id to initialRecord),
            )
        }
        startServiceSafely(
            getApplication(),
            Intent(getApplication(), RuntimeExecutionService::class.java)
                .setAction(RuntimeExecutionService.ACTION_START)
                .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                .putExtra(RuntimeExecutionService.EXTRA_TITLE_RES, R.string.notif_building_apk)
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL_RES, R.string.notif_running_task_in)
                .putExtra(RuntimeExecutionService.EXTRA_DETAIL_ARGS, arrayOf(task, project.name))
                .putExtra(RuntimeExecutionService.EXTRA_CAN_STOP, true),
        )
        lateinit var handle: ProjectBuildHandle
        val buildStopHook: () -> Unit = {
            stopChrootProcess(handle.process)
            handle.job?.cancel()
        }
        handle = ProjectBuildHandle(project.id, buildStopHook)
        projectBuildHandles[project.id] = handle
        RuntimeTaskController.register(RuntimeTaskController.BUILD, buildStopHook)

        val job = viewModelScope.launch {
            var succeeded = false
            var cancelled = false
            var builtApk: File? = null
            var fullLog = ""
            var exitCode = -1
            var androidRoot: File? = null

            try {
                val ws = withContext(Dispatchers.IO) { checkpoints.ensureWorkspace(project.id) }
                val root = withContext(Dispatchers.IO) { findAndroidProjectRoot(ws) ?: ws }
                androidRoot = root
                val cmd = androidGradleCommand(root, task)
                withContext(Dispatchers.IO) {
                    val relativeRoot = root.relativeTo(ws).invariantSeparatorsPath
                    val guestCommand = if (relativeRoot.isEmpty()) cmd else "cd ${shellSingleQuote(relativeRoot)} && $cmd"
                    val process = startChrootProcess(project.slug, ws, guestCommand)
                    handle.process = process
                    if (!isActive) {
                        stopChrootProcess(process)
                        throw CancellationException("Build stopped")
                    }
                    val logBuilder = StringBuilder()
                    var lastPublishAt = 0L
                    fun publishLog(stage: AndroidBuildStage) {
                        lastPublishAt = android.os.SystemClock.elapsedRealtime()
                        val tail = logBuilder.takeLast(64 * 1024).toString()
                        _state.update { current ->
                            val prev = current.projectBuilds[project.id] ?: initialRecord
                            val updatedRecord = prev.copy(stage = stage, log = tail)
                            current.copy(
                                androidBuild = if (current.activeProjectId == project.id) updatedRecord else current.androidBuild,
                                projectBuilds = current.projectBuilds + (project.id to updatedRecord),
                            )
                        }
                    }
                    var currentStage = initialRecord.stage
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (!isActive) {
                                stopChrootProcess(process)
                                throw CancellationException("Build stopped")
                            }
                            logBuilder.append(line).append('\n')
                            val stage = inferAndroidBuildStage(line, currentStage)
                            val stageChanged = stage != currentStage
                            if (stageChanged) pushBuildStageToNotification(stage, project.name)
                            currentStage = stage
                            if (stageChanged || android.os.SystemClock.elapsedRealtime() - lastPublishAt >= LOG_PUBLISH_MS) publishLog(stage)
                        }
                    }
                    publishLog(currentStage)
                    exitCode = process.waitFor()
                    fullLog = logBuilder.toString()
                    builtApk = if (exitCode == 0) findDebugApk(root) else null
                    succeeded = exitCode == 0 && (task == "clean" || builtApk != null)
                }
            } catch (e: CancellationException) {
                cancelled = true
            } catch (e: Throwable) {
                if (!isActive) {
                    cancelled = true
                } else {
                    fullLog += "\nBuild exception: ${e.message}"
                }
            } finally {
                if (!isActive) {
                    cancelled = true
                    stopChrootProcess(handle.process)
                }
                cleanupChrootProcess(handle.process)
                handle.process = null
                projectBuildHandles.remove(project.id)
                RuntimeTaskController.release(buildStopHook)
            }

            val resolvedRoot = androidRoot
            val issues = if (!succeeded && !cancelled && task != "clean" && resolvedRoot != null) {
                parseAndroidBuildIssues(fullLog, resolvedRoot, exitCode)
            } else {
                emptyList()
            }
            val finalApk = builtApk ?: if (!cancelled && task != "clean" && resolvedRoot != null) {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { findDebugApk(resolvedRoot) }
            } else {
                null
            }
            _state.update { current ->
                val prev = current.projectBuilds[project.id] ?: initialRecord
                val finishedRecord = prev.copy(
                    phase = when {
                        cancelled -> AndroidBuildPhase.CANCELLED
                        succeeded -> AndroidBuildPhase.SUCCEEDED
                        else -> AndroidBuildPhase.FAILED
                    },
                    stage = when {
                        cancelled -> AndroidBuildStage.CANCELLED
                        succeeded -> AndroidBuildStage.COMPLETE
                        else -> AndroidBuildStage.FAILED
                    },
                    finishedAtMillis = System.currentTimeMillis(),
                    apkPath = finalApk?.absolutePath,
                    apkSizeBytes = finalApk?.length(),
                    message = when {
                        cancelled -> str(R.string.build_cancelled_by_user)
                        succeeded && finalApk != null -> str(R.string.build_apk_ready, finalApk.name, finalApk.length() / 1024)
                        succeeded -> str(R.string.build_gradle_completed, task)
                        else -> issues.firstOrNull()?.detail?.let(::localizeMsg) ?: str(R.string.build_failed_exit, exitCode)
                    },
                    issues = issues,
                )
                current.copy(
                    androidBuild = if (current.activeProjectId == project.id) finishedRecord else current.androidBuild,
                    buildingProjectIds = current.buildingProjectIds - project.id,
                    projectBuilds = current.projectBuilds + (project.id to finishedRecord),
                )
            }
            if (projectBuildHandles.isEmpty()) {
                runCatching {
                    getApplication<Application>().startService(
                        Intent(getApplication(), RuntimeExecutionService::class.java)
                            .setAction(
                                when {
                                    cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                                    succeeded -> RuntimeExecutionService.ACTION_COMPLETE
                                    else -> RuntimeExecutionService.ACTION_FAILED
                                },
                            )
                            .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                            .putExtra(
                                RuntimeExecutionService.EXTRA_DETAIL_RES,
                                when {
                                    cancelled -> R.string.notif_build_cancelled
                                    succeeded -> R.string.notif_build_succeeded
                                    else -> R.string.notif_build_failed
                                },
                            ),
                    )
                }.onFailure { error ->
                    Log.w("MainViewModel", "Could not post the build result notification", error)
                    runCatching {
                        getApplication<Application>().stopService(Intent(getApplication(), RuntimeExecutionService::class.java))
                    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
                }
            }
            if (succeeded && !cancelled && installAfterBuild && finalApk != null) {
                installBuiltApk(finalApk.absolutePath)
            }
        }
        handle.job = job
    }

    /** Installs an APK that lives in the active project (e.g. one the agent built or copied), from the Files list. */
    fun installWorkspaceApk(relativePath: String) {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val apk = withContext(Dispatchers.IO) {
                runCatching { checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(project.id), relativePath) }.getOrNull()
            }
            if (apk != null && apk.isFile) installBuiltApk(apk.absolutePath)
        }
    }

    fun installBuiltApk(apkPath: String? = _state.value.androidBuild.apkPath) {
        val path = apkPath ?: return
        val apkFile = File(path)
        if (!apkFile.isFile) return
        viewModelScope.launch {
            _state.update {
                it.copy(
                    androidBuild = it.androidBuild.copy(
                        stage = AndroidBuildStage.INSTALLING,
                        message = str(R.string.build_installing_apk, apkFile.name),
                    ),
                )
            }
            val errorMsg = withContext(Dispatchers.IO) {
                runCatching {
                    AndroidAppInstaller.install(
                        context = getApplication(),
                        apk = apkFile,
                        launchAfterInstall = true,
                    )
                    null
                }.getOrElse { err -> err.message ?: str(R.string.build_apk_install_failed) }
            }
            if (errorMsg != null) {
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            stage = AndroidBuildStage.FAILED,
                            message = errorMsg,
                        ),
                        bannerMessage = UiText.Raw(errorMsg),
                        bannerAction = BannerAction(BannerActionKind.RETRY_APK_INSTALL, UiText.Raw(errorMsg)),
                    )
                }
            }
        }
    }

    private fun resolveProjectAndroidPackageName(project: Project): String {
        val builtApkPath = _state.value.androidBuild.apkPath
        if (!builtApkPath.isNullOrBlank()) {
            val apkFile = File(builtApkPath)
            if (apkFile.isFile) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), apkFile)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
        }
        val ws = runCatching { checkpoints.ensureWorkspace(project.id) }.getOrNull()
        val androidRoot = ws?.let { findAndroidProjectRoot(it) ?: it }
        if (androidRoot != null) {
            val existingApk = findDebugApk(androidRoot)
            if (existingApk != null) {
                val apkPkg = AndroidAppInstaller.packageName(getApplication(), existingApk)
                if (!apkPkg.isNullOrBlank()) return apkPkg
            }
            val candidates = listOf(
                File(androidRoot, "app/build.gradle.kts"),
                File(androidRoot, "app/build.gradle"),
                File(androidRoot, "build.gradle.kts"),
                File(androidRoot, "build.gradle"),
                File(androidRoot, "app/src/main/AndroidManifest.xml"),
            )
            val appIdRegex = Regex("""(?:applicationId|namespace|package)\s*=?\s*["']([a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+)["']""")
            for (file in candidates) {
                if (file.isFile) {
                    val text = runCatching { file.readBoundedText(64 * 1024) }.getOrNull().orEmpty()
                    val match = appIdRegex.find(text)?.groupValues?.getOrNull(1)
                    if (!match.isNullOrBlank()) return match
                }
            }
        }
        return AndroidProjectTemplateGenerator.packageName(project.name)
    }

    fun startProjectLogcat() {
        val project = _state.value.activeProject ?: return
        activeLogcatProcess?.destroyForcibly()
        activeLogcatJob?.cancel()
        activeLogcatJob = viewModelScope.launch {
            val pkg = withContext(Dispatchers.IO) { resolveProjectAndroidPackageName(project) }
            val canRead = withContext(Dispatchers.IO) { hasAndroidLogcatAccess(getApplication()) }
            if (!canRead) {
                _state.update {
                    it.copy(
                        androidLogcat = it.androidLogcat.copy(
                            available = false,
                            running = false,
                            packageName = pkg,
                            message = str(R.string.logcat_permission_required),
                        ),
                    )
                }
                return@launch
            }
            _state.update {
                it.copy(
                    androidLogcat = it.androidLogcat.copy(
                        available = true,
                        running = true,
                        packageName = pkg,
                        message = str(R.string.logcat_streaming, pkg),
                    ),
                )
            }
            withContext(Dispatchers.IO) {
                runCatching {
                    val proc = startPackageLogcatProcess(getApplication(), pkg)
                    activeLogcatProcess = proc
                    // A cancel between starting the process and publishing it above would leave nobody to stop it.
                    if (!isActive) proc.destroyForcibly()
                    try {
                        proc.inputStream.bufferedReader().useLines { lines ->
                            for (raw in lines) {
                                // return@forEach only skipped one line and kept blocking on the stream.
                                if (!isActive) break
                                val level = when {
                                    " F " in raw -> AndroidLogLevel.FATAL
                                    " E " in raw -> AndroidLogLevel.ERROR
                                    " W " in raw -> AndroidLogLevel.WARNING
                                    " I " in raw -> AndroidLogLevel.INFO
                                    " D " in raw -> AndroidLogLevel.DEBUG
                                    else -> AndroidLogLevel.VERBOSE
                                }
                                val entry = AndroidLogLine(
                                    id = logLineIdCounter.getAndIncrement(),
                                    text = raw,
                                    level = level,
                                )
                                _state.update { current ->
                                    current.copy(
                                        androidLogcat = current.androidLogcat.copy(
                                            lines = (current.androidLogcat.lines + entry).takeLast(300),
                                        ),
                                    )
                                }
                            }
                        }
                        if (isActive) {
                            _state.update {
                                it.copy(
                                    androidLogcat = it.androidLogcat.copy(
                                        running = false,
                                        message = str(R.string.logcat_ended, pkg),
                                    ),
                                )
                            }
                        }
                    } finally {
                        proc.destroyForcibly()
                        if (activeLogcatProcess === proc) activeLogcatProcess = null
                    }
                }.onFailure { err ->
                    _state.update {
                        it.copy(
                            androidLogcat = it.androidLogcat.copy(
                                running = false,
                                message = err.message ?: str(R.string.logcat_attach_failed, pkg),
                            ),
                        )
                    }
                }
            }
        }
    }

    fun stopProjectLogcat() {
        activeLogcatProcess?.destroyForcibly()
        activeLogcatProcess = null
        activeLogcatJob?.cancel()
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = str(R.string.logcat_paused)))
        }
    }

    fun clearProjectLogcat() {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList()))
        }
    }

    fun setLogcatMinimumLevel(level: AndroidLogLevel) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = level))
        }
    }

    fun setLogcatQuery(query: String) {
        _state.update {
            it.copy(androidLogcat = it.androidLogcat.copy(query = query))
        }
    }

    fun updatePreviewUrl(url: String) {
        _state.update { it.copy(previewUrl = url) }
    }

    fun scanLocalPreviewPorts() {
        viewModelScope.launch {
            val openPorts = withContext(Dispatchers.IO) { LocalNetwork.scanOpenLocalPorts() }
            _state.update { current ->
                val newUrl = if (openPorts.isNotEmpty() && current.previewUrl == "http://127.0.0.1:3000") {
                    "http://127.0.0.1:${openPorts.first()}"
                } else {
                    current.previewUrl
                }
                current.copy(activeLocalPorts = openPorts, previewUrl = newUrl)
            }
        }
    }

    fun updateProviderKind(kind: ProviderKind) {
        val candidate = ProviderProfile(
            kind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
        )
        val updated = candidate.copy(hasSecret = !resolveProviderSecret(candidate).isNullOrBlank())
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updated,
                savedApiKeys = vault.list(kind.name),
                discoveredModels = emptyList(),
                apiStatusMessage = null,
            )
        }
    }

    fun updateProviderConfig(
        baseUrl: String,
        model: String,
        openRouterOrder: String = _state.value.providerProfile.openRouterProviderOrder,
        openRouterAllowFallbacks: Boolean = _state.value.providerProfile.openRouterAllowFallbacks,
        dshApi: String = _state.value.providerProfile.dshApi,
    ) {
        val current = _state.value.providerProfile
        val updated = current.copy(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            openRouterProviderOrder = openRouterOrder.trim(),
            openRouterAllowFallbacks = openRouterAllowFallbacks,
            dshApi = dshApi.trim().ifBlank { current.dshApi },
            hasSecret = !resolveProviderSecret(current).isNullOrBlank(),
        )
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update { it.copy(providerProfile = updated) }
    }

    fun validateAntigravityConnection() {
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_agy_probing)) }
        viewModelScope.launch {
            val msg = runCatching {
                val reply = antigravityRuntime.hello(45_000L)
                UiText.res(R.string.msg_agy_verified, reply.take(100))
            }.getOrElse { err ->
                UiText.res(R.string.msg_agy_probe_failed, err.message ?: "CLI error")
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = msg) }
        }
    }

    fun addApiKey(label: String, secret: String) {
        val cleanSecret = secret.trim()
        if (cleanSecret.isEmpty()) return
        val providerId = _state.value.providerProfile.kind.name
        runCatching { vault.add(providerId, label.trim().ifBlank { "Primary Key" }, cleanSecret) }
            .onFailure { err ->
                _state.update { it.copy(apiStatusMessage = err.message?.let { UiText.Raw(it) } ?: UiText.res(R.string.msg_api_key_save_failed)) }
                return
            }
        val keys = vault.list(providerId)
        val updatedProfile = _state.value.providerProfile.copy(hasSecret = keys.isNotEmpty())
        preferences.saveProvider(updatedProfile, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updatedProfile,
                savedApiKeys = keys,
                apiStatusMessage = UiText.res(R.string.msg_api_key_saved),
            )
        }
    }

    fun selectActiveApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.activate(providerId, keyId)
        _state.update { it.copy(savedApiKeys = vault.list(providerId)) }
    }

    fun removeApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.remove(providerId, keyId)
        val keys = vault.list(providerId)
        _state.update {
            it.copy(
                providerProfile = it.providerProfile.copy(hasSecret = !resolveProviderSecret(it.providerProfile).isNullOrBlank()),
                savedApiKeys = keys,
            )
        }
    }

    fun discoverProviderModels() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        // A Claude subscription login is an OAuth token: Anthropic's /v1/models rejects it, so the catalog is Claude
        // Code's own model list (shown in the picker) and no API call is made.
        if (isClaudeSubscriptionLogin(profile, apiKey)) {
            _state.update { it.copy(apiBusy = false, apiStatusMessage = UiText.res(R.string.msg_subscription_models)) }
            return
        }
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_fetching_models)) }
        viewModelScope.launch {
            when (val res = providerApi.discoverModels(profile.resolvedBaseUrl, apiKey, protocol)) {
                is ModelDiscoveryResult.Success -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            discoveredModels = res.models,
                            apiStatusMessage = UiText.res(R.string.msg_discovered_models, res.models.size, res.endpoint),
                        )
                    }
                }
                is ModelDiscoveryResult.Failure -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            apiStatusMessage = UiText.Raw(listOfNotNull(res.message, res.providerMessage).joinToString(" — ") { localizeMsg(it) }),
                        )
                    }
                }
            }
        }
    }

    fun validateProviderConnection() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = UiText.res(R.string.msg_validating)) }
        viewModelScope.launch {
            // Subscription login: verify through Claude Code itself; the Messages API would reject the OAuth token.
            if (_state.value.agentKind == AgentKind.CLAUDE_CODE && isClaudeSubscriptionLogin(profile, apiKey)) {
                // Keep the real reason: swallowing it left "Rejected" on screen with no way to see what failed.
                val probe = if (_state.value.runtimeInstalled) {
                    runCatching { claudeRuntime.hello(profile, 45_000L) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
                } else {
                    Result.failure(IllegalStateException(str(R.string.msg_runtime_not_installed)))
                }
                val text = probe.fold(
                    onSuccess = { UiText.res(R.string.msg_claude_cli_verified, it.take(80)) },
                    onFailure = { UiText.res(R.string.msg_claude_cli_unverified, (it.message ?: it.javaClass.simpleName).take(200)) },
                )
                _state.update { it.copy(apiBusy = false, apiStatusMessage = text) }
                return@launch
            }
            var res = providerApi.validate(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = apiKey,
                protocol = protocol,
                discoveredModels = _state.value.discoveredModels,
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
            if (res is ConnectionValidation.Failure &&
                _state.value.agentKind == AgentKind.CLAUDE_CODE &&
                _state.value.runtimeInstalled &&
                (profile.kind == ProviderKind.CLAUDE || apiKey.startsWith("sk-ant-oat"))
            ) {
                val cliProbe = runCatching { claudeRuntime.hello(profile, 45_000L) }.getOrNull()
                if (!cliProbe.isNullOrBlank()) {
                    res = ConnectionValidation.Success(str(R.string.msg_cli_verified_paren, cliProbe.take(80)))
                }
            }
            val msg = when (res) {
                is ConnectionValidation.Success -> "✓ ${localizeMsg(res.message)}"
                is ConnectionValidation.Failure -> "✕ ${localizeMsg(res.label)}: ${localizeMsg(res.message)} ${res.providerMessage?.let(::localizeMsg).orEmpty()}".trim()
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = UiText.Raw(msg)) }
        }
    }

    fun startGitHubDeviceSignIn(clientId: String = "") = githubAuth.startDeviceSignIn(clientId)

    /** See [com.nextcraft.forge.runtime.GitHubAuthController.resumeDeviceSignIn]. */
    fun resumeGitHubDeviceSignIn() = githubAuth.resumeDeviceSignIn()

    fun connectGitHubWithToken(personalAccessToken: String) = githubAuth.connectWithToken(personalAccessToken)

    fun disconnectGitHub() = githubAuth.disconnect()

    fun createGitHubRepository(
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
                            append("git init -b main && ")
                            append("git config user.name ${shellSingleQuote(login)} && ")
                            append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                            append("(git remote remove origin 2>/dev/null || true) && ")
                            append("git remote add origin ${shellSingleQuote(repo.cloneUrl)} && ")
                            append("git add -A && ")
                            append("(git diff --cached --quiet || git commit -m 'Initial commit from NextCraft Forge') && ")
                            append("git push -u origin main")
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

    fun syncProjectWithGitHub(
        projectId: String = _state.value.activeProjectId,
        commitMessage: String = "Sync project changes from NextCraft Forge",
    ) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) return
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(project.id)
                val gitDir = File(ws, ".git")
                if (!gitDir.isDirectory) return@runCatching
                val login = githubAuth.state.value.login.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val syncCmd = buildString {
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("branch=\$(git symbolic-ref --short HEAD 2>/dev/null || echo main); ")
                    append("git config user.name ${shellSingleQuote(login)} && ")
                    append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                    append("git add -A && ")
                    append("(git diff --cached --quiet || git commit -m ${shellSingleQuote(commitMessage)}) && ")
                    append("git pull --rebase --autostash origin \"\$branch\" >/dev/null 2>&1 || git rebase --abort >/dev/null 2>&1; ")
                    append("git push -u origin \"HEAD:\$branch\"; ")
                    append("fi")
                }
                withGitHubCredentials(token, login) {
                    runCommandInChrootOrHost(project.slug, ws, syncCmd)
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
        }
    }

    fun pullProjectFromGitHub(projectId: String = _state.value.activeProjectId) {
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
                        append("if git remote get-url origin >/dev/null 2>&1; then ")
                        append("branch=\$(git symbolic-ref --short HEAD 2>/dev/null || echo main); ")
                        append("git fetch origin && (git pull --rebase --autostash origin \"\$branch\" || ")
                        append("{ git rebase --abort 2>/dev/null; false; }); ")
                        append("else echo 'No origin remote configured in workspace'; fi")
                    }
                    withGitHubCredentials(token, login) {
                        runCheckedCommand(project.slug, ws, pullCmd)
                    }
                }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
            }
            refreshWorkspaceFilesAndChanges(project.id)
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

    fun cloneGitHubRepository(repo: GitHubRepository) {
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
    fun createQuickProject() {
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
    fun cloneGitUrl(rawUrl: String) {
        val url = rawUrl.trim()
        val uriScheme = android.net.Uri.parse(url).scheme?.lowercase()
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

    /** Remembered so the banner's "Try again" can re-run a failed import without the file picker. */
    private var lastImportUri: android.net.Uri? = null

    /** Unpacks a user-picked ZIP into a new workspace project named after the file. */
    fun importZipProject(uri: android.net.Uri) {
        lastImportUri = uri
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val displayName = app.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (c.moveToFirst() && idx >= 0) c.getString(idx) else null
                    } ?: "import.zip"
                    val magic = app.contentResolver.openInputStream(uri)?.use { it.readNBytes(4) } ?: ByteArray(0)
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
                                if (++entries > MAX_IMPORT_ENTRIES) throw IllegalArgumentException("Archive has too many files")
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
                                            if (written > MAX_IMPORT_BYTES) throw IllegalArgumentException("Archive is larger than ${MAX_IMPORT_BYTES / (1024 * 1024)} MB when extracted")
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
                    project
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

    fun setLanguageCode(code: String) {
        val clean = code.trim().ifEmpty { "system" }
        AppStrings.saveLanguageCode(getApplication(), clean)
        _state.update { it.copy(languageCode = clean) }
    }

    fun setInteractiveHighRiskApprovals(enabled: Boolean) {
        preferences.interactiveHighRiskApprovals = enabled
        if (enabled && preferences.autoApproveAllTools) {
            preferences.autoApproveAllTools = false
        }
        _state.update {
            it.copy(
                interactiveHighRiskApprovals = enabled,
                autoApproveAllTools = preferences.autoApproveAllTools,
            )
        }
    }

    fun setAutoApproveAllTools(enabled: Boolean) {
        preferences.autoApproveAllTools = enabled
        if (enabled && preferences.interactiveHighRiskApprovals) {
            preferences.interactiveHighRiskApprovals = false
        }
        _state.update {
            it.copy(
                autoApproveAllTools = enabled,
                interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
            )
        }
    }

    /** Measures the Gradle/npm/pip/apt download caches in the rootfs (Settings → Linux runtime → Clear caches). */
    fun loadRuntimeCacheSizes() {
        _state.update { it.copy(runtimeCacheSizes = null) }
        viewModelScope.launch {
            val rootfs = RuntimeInstaller.rootfsDir(getApplication())
            val sizes = withContext(Dispatchers.IO) { com.nextcraft.forge.runtime.RuntimeCaches.sizes(rootfs) }
            _state.update { it.copy(runtimeCacheSizes = sizes.mapKeys { (kind, _) -> kind.name }) }
        }
    }

    /** Deletes every download cache; refused while an agent runs, since a build may be using them. */
    fun clearRuntimeCaches() {
        if (_state.value.sessionRunning) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_clear_caches_busy)) }
            return
        }
        viewModelScope.launch {
            val rootfs = RuntimeInstaller.rootfsDir(getApplication())
            withContext(Dispatchers.IO) {
                com.nextcraft.forge.runtime.RuntimeCaches.clear(rootfs, com.nextcraft.forge.runtime.RuntimeCaches.Kind.entries.toSet())
            }
            _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_caches_cleared)) }
            loadRuntimeCacheSizes()
        }
    }

    fun setAutoKeepChanges(enabled: Boolean) {
        preferences.autoKeepChanges = enabled
        _state.update { it.copy(autoKeepChanges = enabled) }
    }

    fun setAndroidHostTools(enabled: Boolean) {
        preferences.androidHostTools = enabled
        _state.update { it.copy(androidHostTools = enabled) }
        viewModelScope.launch(Dispatchers.IO) { runCatching { installer.applyAndroidHostTools() } }
    }

    fun setGameTemplatesEnabled(enabled: Boolean) {
        preferences.gameTemplatesEnabled = enabled
        _state.update { it.copy(gameTemplatesEnabled = enabled) }
    }

    fun setUpdateNotifications(enabled: Boolean) = appUpdate.setNotifications(enabled)

    /** Background download and silent install while the app is idle (needs root; without it only the notification shows). */
    fun setAutoInstallUpdates(enabled: Boolean) = appUpdate.setAutoInstall(enabled)

    /** See [com.nextcraft.forge.update.AppUpdateController.silentCheck]. */
    fun silentUpdateCheck(force: Boolean = false) = appUpdate.silentCheck(force)

    fun setReaChatHint(enabled: Boolean) {
        preferences.reaChatHint = enabled
        _state.update { it.copy(reaChatHint = enabled) }
    }

    fun setAutoBranchChats(enabled: Boolean) {
        preferences.autoBranchChats = enabled
        _state.update { it.copy(autoBranchChats = enabled) }
    }

    fun setAutoSyncGitHub(enabled: Boolean) {
        preferences.autoSyncGitHub = enabled
        _state.update { it.copy(autoSyncGitHub = enabled) }
    }

    /** Saves the Claude Code effort level ("" = model default); it reaches the CLI as CLAUDE_CODE_EFFORT_LEVEL. */
    fun setClaudeEffort(level: String) {
        val clean = level.trim().lowercase().takeIf { it in CLAUDE_EFFORT_LEVELS } ?: ""
        preferences.claudeEffort = clean
        _state.update { it.copy(claudeEffort = clean) }
    }

    fun setAntigravityConfig(model: String, effort: String) {
        val cleanEffort = effort.trim().ifEmpty { "high" }
        // Ids such as gemini-4-argon-high carry the effort themselves. Picking "Medium" with that model selected must
        // switch to gemini-4-argon-medium; otherwise the tab looked dead and agy got a model and an effort that clash.
        val cleanModel = model.trim().let { id ->
            val variant = id.replace(AGY_EFFORT_SUFFIX, "-$cleanEffort")
            val known = _state.value.antigravityModels
            if (variant != id && (known.isEmpty() || variant in known)) variant else id
        }
        preferences.antigravityModel = cleanModel
        preferences.antigravityEffort = cleanEffort
        _state.update {
            it.copy(
                antigravityModel = cleanModel,
                antigravityEffort = cleanEffort,
            )
        }
    }

    /** Picks an Antigravity model; an id ending in -low/-medium/-high also sets the matching effort. */
    fun selectAntigravityModel(model: String) {
        val effort = AGY_EFFORT_SUFFIX.find(model)?.groupValues?.get(1) ?: _state.value.antigravityEffort
        setAntigravityConfig(model, effort)
    }

    /** Lists the models the signed-in Google account can use by running `agy models` in the chroot. */
    fun refreshAntigravityModels() {
        if (_state.value.antigravityModelsLoading) return
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            _state.update { it.copy(apiStatusMessage = UiText.res(R.string.msg_install_antigravity_first)) }
            return
        }
        _state.update { it.copy(antigravityModelsLoading = true) }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val runtime = installer.installedRuntime()
                val workspace = File(getApplication<Application>().filesDir, "workspaces/antigravity-models").apply { mkdirs() }
                val output = File(getApplication<Application>().cacheDir, "antigravity-models-${System.nanoTime()}.log")
                val process = installer.process(
                    runtime.chrootExec,
                    runtime.rootfs,
                    workspace,
                    mapOf("NO_COLOR" to "1", "TERM" to "xterm-256color"),
                    listOf(RuntimeInstaller.AGY_GUEST_PATH, "models"),
                    guestWorkspacePath = "/workspace/antigravity-models",
                    emulateHardLinks = false,
                    outputFile = output,
                )
                runCatching { process.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
                try {
                    if (!process.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
                        process.destroyForcibly()
                        throw UiTextException(UiText.res(R.string.agy_models_timeout))
                    }
                    val text = sanitizeClaudeAuthOutput(output.takeIf(File::exists)?.readText().orEmpty())
                    if (text.contains("sign in", ignoreCase = true)) throw UiTextException(UiText.res(R.string.agy_sign_in_first))
                    text.lineSequence()
                        .mapNotNull { line -> line.trim().split(Regex("\\s+"), limit = 2).firstOrNull() }
                        .filter { it.matches(Regex("[a-z0-9][a-z0-9._-]+")) }
                        .distinct()
                        .toList()
                        .also { if (it.isEmpty()) throw UiTextException(UiText.res(R.string.agy_no_models)) }
                } finally {
                    output.delete()
                }
            }
            _state.update { current ->
                result.fold(
                    onSuccess = { models -> current.copy(antigravityModelsLoading = false, antigravityModels = models) },
                    onFailure = { error ->
                        current.copy(
                            antigravityModelsLoading = false,
                            apiStatusMessage = error.toUiText(R.string.agy_models_failed),
                        )
                    },
                )
            }
        }
    }

    fun releaseMemoryCaches() {
        _state.update {
            it.copy(
                androidLogcat = it.androidLogcat.copy(lines = it.androidLogcat.lines.takeLast(100)),
            )
        }
    }

    fun checkForAppUpdates(manifestUrlOverride: String = "") = appUpdate.checkNow(manifestUrlOverride)

    fun downloadAndInstallAppUpdate() = appUpdate.downloadAndInstall()

    fun setThemeMode(mode: String) {
        val resolved = ForgeThemeMode.fromStored(mode).id
        preferences.themeMode = resolved
        _state.update { it.copy(themeMode = resolved) }
    }

    fun toggleThemeMode() {
        val current = ForgeThemeMode.fromStored(_state.value.themeMode)
        val entries = ForgeThemeMode.entries
        val next = entries[(current.ordinal + 1) % entries.size]
        setThemeMode(next.id)
    }

    /** Runs the banner's recovery button. SET_UP_RUNTIME is the UI's job (it reopens the setup screen). */
    fun runBannerAction(kind: BannerActionKind) {
        _state.update { it.copy(bannerMessage = null, bannerAction = null) }
        when (kind) {
            BannerActionKind.RETRY_IMPORT -> lastImportUri?.let(::importZipProject)
            BannerActionKind.RETRY_UPDATE_CHECK -> checkAgentUpdates()
            BannerActionKind.RETRY_APK_INSTALL -> installBuiltApk()
            BannerActionKind.SET_UP_RUNTIME -> Unit
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null, bannerAction = null) }
    }

    override fun onCleared() {
        ScheduleRuntime.tickerActive = false
        // viewModelScope is cancelled here, so a project still in its undo window is wiped on a plain thread.
        pendingDelete?.project?.let { project -> Thread { wipeProjectData(project) }.start() }
        RuntimeTaskController.requestStop()
        projectBuildHandles.values.forEach { stopChrootProcess(it.process) }
        activeLogcatProcess?.destroyForcibly()
        stopChrootProcess(activeTerminalProcess)
        terminals.values.forEach { it.session?.finishIfRunning() }
        super.onCleared()
    }

    private companion object {
        /** Longest a running build waits before the screen sees new log lines. */
        const val LOG_PUBLISH_MS = 200L
        /** Streamed replies refresh the open chat at most ~12 times a second. */
        const val MESSAGE_PUBLISH_INTERVAL_MS = 80L

        /** How long a deleted project stays restorable. */
        const val UNDO_WINDOW_MS = 6_000L

        const val MAX_IMPORT_ENTRIES = 50_000
        const val MAX_DIRECTORY_ENTRIES = 3_000
        const val MAX_IMPORT_BYTES = 2L * 1024 * 1024 * 1024
        const val MAX_TERMINAL_CHARS = 128 * 1024
        const val TERMINAL_TRANSCRIPT_ROWS = 4000

        /** Time for a screen to compose and draw before it is photographed. */
        const val CAPTURE_SETTLE_MS = 900L
        const val KEYBOARD_CLOSE_MS = 500L
        const val RESUME_REFRESH_MIN_INTERVAL_MS = 5_000L
    }
}

private const val AGENT_TURN_END_GRACE_MS = 2_000L

/** Upper bound on concurrent agent sessions across all projects; hardware RAM is the primary governor. */
private const val MAX_PARALLEL_CHATS = 12
private const val SCHEDULE_TICK_MS = 30_000L

/** Free RAM needed before another parallel run starts; below it the message waits in the queue. */
private const val MIN_FREE_BYTES_FOR_CLAUDE_RUN = 650L * 1024 * 1024
private const val MIN_FREE_BYTES_FOR_LIGHT_RUN = 450L * 1024 * 1024

/** One agent turn in progress for a chat: its bridge, session, and the live copy of the chat it streams into. */
private class ChatRun(
    val chatId: String,
    val projectId: String,
    val projectName: String = "",
    val chatTitle: String = "Main chat",
    val bridge: RuntimeBridge,
    /** True when [bridge] was created for this run (Claude Code); its events are collected by the run itself. */
    val ownsBridge: Boolean,
    val lastPrompt: QueuedPrompt,
    /** True when the run works in the chat's own branch (a clone of the project), not in the project folder. */
    val isolated: Boolean = false,
    val agent: AgentKind = AgentKind.CLAUDE_CODE,
    /** Agents whose turn already failed for this message (Failover and Balanced). */
    val tried: Set<AgentKind> = emptySet(),
) {
    var job: Job? = null
    var eventsJob: Job? = null
    var sessionId: String? = null
    var finishedSessionId: String? = null
    var messages: List<ChatMessage> = emptyList()
    /** Streamed text not yet folded into [messages]; keeps one string copy per publish instead of one per delta. */
    val pendingText = StringBuilder()
    var lastPublishAt = 0L
    var publishJob: Job? = null
    var statusLine: UiText? = null
    var pendingTool: ToolRequest? = null
    var finished: Boolean = false

    /** True while the run is being stopped so that a queued message can go out at once (see steerQueuedPrompt). */
    var steering: Boolean = false
}

/** The effort part of an Antigravity model id such as gemini-4-argon-high. */
private val AGY_EFFORT_SUFFIX = Regex("-(low|medium|high)$")

/** Characters of a tool's output kept in the chat: the start (the command and its first lines) and the end (the result). */
private const val TOOL_DETAIL_HEAD = 1_500
private const val TOOL_DETAIL_TAIL = 2_500

/**
 * Shortens a tool's output for the chat. A build or test run can print megabytes; keeping all of it in every message
 * made long chats slow to draw, scroll, save and reopen. The full output stays in the agent's own context and logs.
 */
internal fun clipToolDetail(detail: String): String {
    if (detail.length <= TOOL_DETAIL_HEAD + TOOL_DETAIL_TAIL + 200) return detail
    val head = detail.substring(0, TOOL_DETAIL_HEAD)
    val tail = detail.substring(detail.length - TOOL_DETAIL_TAIL)
    val omittedLines = detail.substring(TOOL_DETAIL_HEAD, detail.length - TOOL_DETAIL_TAIL).count { it == '\n' }
    return "$head\n… ${omittedLines + 1} lines omitted …\n$tail"
}

/** Failures that a plain resend usually fixes: the provider's stream stalled, the connection dropped, or a 5xx. */
internal fun isTransientAgentFailure(reason: String): Boolean {
    val text = reason.lowercase()
    return listOf(
        "stream idle timeout", "context deadline exceeded", "sent nothing for 5 minutes", "stream was interrupted",
        "dropped mid-answer", "connection reset", "server returned an error", "overloaded", "code 500", "code 502",
        "code 503", "internal error encountered",
    ).any { it in text }
}
