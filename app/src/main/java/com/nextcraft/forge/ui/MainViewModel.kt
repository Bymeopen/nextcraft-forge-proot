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
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.runtime.asAccountApi
import com.nextcraft.forge.network.GeminiImageClient
import com.nextcraft.forge.runtime.ConnectorStore
import com.nextcraft.forge.network.GeminiVideoClient
import com.nextcraft.forge.network.ProviderApiClient
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AntigravityAuthController
import com.nextcraft.forge.runtime.ClaudeAuthController
import com.nextcraft.forge.runtime.SshController
import com.nextcraft.forge.runtime.SnapshotsController
import com.nextcraft.forge.runtime.RomStudioController
import com.nextcraft.forge.runtime.DiagnosticsController
import com.nextcraft.forge.runtime.CiCdController
import com.nextcraft.forge.runtime.BuildMatrixController
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.schedule.ScheduleRuntime
import com.nextcraft.forge.schedule.ScheduleStore
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.ChatBranches
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import org.json.JSONObject
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.RuntimeEngine
import com.nextcraft.forge.runtime.RuntimeEngineChoice
import com.nextcraft.forge.runtime.supportsArm64Runtime
import com.nextcraft.forge.update.AppUpdateInfo
import com.nextcraft.forge.update.AppUpdater
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nextcraft.forge.R

internal data class RunKey(val projectId: String, val chatId: String)

internal class ProjectBuildHandle(
    val projectId: String,
    val stopHook: () -> Unit,
) {
    var job: Job? = null
    @Volatile var process: Process? = null
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    internal val preferences = AppPreferences(application)
    internal val scheduleStore = ScheduleStore(application)
    internal val vault = ApiKeyVault(application)
    internal val installer = RuntimeInstaller(application)
    internal val checkpoints = WorkspaceCheckpoints(application.filesDir, application)
    internal val chatBranches = ChatBranches(application)
    /** Serialises commits and clone setup per chat so a new turn never starts while the last one is being committed. */
    internal val branchLocks = HashMap<String, kotlinx.coroutines.sync.Mutex>()
    internal fun branchLock(projectId: String, chatId: String) = branchLocks.getOrPut("$projectId:$chatId") { kotlinx.coroutines.sync.Mutex() }
    internal fun branchLock(chatId: String) = branchLock(_state.value.activeProjectId, chatId)
    internal val providerApi = ProviderApiClient(application)
    internal val githubClient = GitHubClient(application)
    /** GitHub sign-in and the repository list; this class mirrors its state into [_state] for the screens. */
    val githubAuth = com.nextcraft.forge.runtime.GitHubAuthController(
        scope = viewModelScope,
        api = githubClient.asAccountApi(),
        saveToken = { token ->
            vault.put("GITHUB_TOKEN", token)
            connectorStore.syncGitHubConnector()
            val login = preferences.githubLogin.ifBlank { "x-access-token" }
            runCatching { installer.configureGitHubAccess(token, login) }
            loadConnectors()
        },
        clearToken = {
            vault.remove("GITHUB_TOKEN")
            connectorStore.removeGitHubConnectorIfAuto()
            runCatching { installer.clearGitHubAccess() }
            loadConnectors()
        },
        savedLogin = { preferences.githubLogin },
        saveLogin = { login ->
            preferences.githubLogin = login
            val token = vault.get("GITHUB_TOKEN").orEmpty()
            if (token.isNotBlank() && login.isNotBlank()) {
                runCatching { installer.configureGitHubAccess(token, login) }
            }
        },
        clearGuestAccess = {
            if (vault.get("GITHUB_TOKEN").isNullOrBlank()) {
                installer.clearGitHubAccess()
            }
        },
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
    internal fun str(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        LocaleHelper.wrapContext(getApplication<Application>()).getString(id, *args)

    /** A known English sentence from a lower layer in the app language; anything else unchanged. */
    internal fun localizeMsg(raw: String): String = MessageText.localize(LocaleHelper.wrapContext(getApplication<Application>()), raw)

    /** A failure's own text (translated when it is a known sentence), else the generic "unknown error". */
    internal fun errText(err: Throwable): String = err.message?.let(::localizeMsg) ?: str(R.string.msg_unknown_error)

    internal fun quantity(@androidx.annotation.PluralsRes id: Int, count: Int): String =
        LocaleHelper.wrapContext(getApplication<Application>()).resources.getQuantityString(id, count, count)

    /** True when Claude runs on the user's subscription login (no key, or the OAuth token read from the credentials file). */
    internal fun isClaudeSubscriptionLogin(profile: ProviderProfile, apiKey: String): Boolean =
        profile.kind == ProviderKind.CLAUDE && (apiKey.isBlank() || apiKey.startsWith("sk-ant-oat"))

    internal fun resolveProviderSecret(profile: ProviderProfile): String? {
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

    internal fun rotateProviderSecret(profile: ProviderProfile): String? {
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
    val diagnostics = DiagnosticsController(application, viewModelScope)
    val rea = com.nextcraft.forge.runtime.ReaController(application, viewModelScope) { changedProjectId ->
        viewModelScope.launch {
            if (_state.value.activeProjectId == changedProjectId) {
                refreshWorkspaceFilesAndChanges(changedProjectId)
            }
        }
    }
    val buildMatrix = BuildMatrixController(
        application,
        { _state.value.activeProject },
        { checkpoints.ensureWorkspace(it) },
        viewModelScope,
        onWorkspaceChanged = { changedProjectId ->
            viewModelScope.launch {
                if (_state.value.activeProjectId == changedProjectId) {
                    refreshWorkspaceFilesAndChanges(changedProjectId)
                }
            }
        },
        onBuildSucceeded = { builtProjectId ->
            if (_state.value.autoSyncGitHub) {
                syncProjectWithGitHub(builtProjectId, "Auto-sync after build matrix from NextCraft Forge")
            }
        },
    )
    val cicd = CiCdController(application, { _state.value.activeProject }, { checkpoints.ensureWorkspace(it) }, viewModelScope)
    val ssh = SshController(application, viewModelScope)
    val rom = RomStudioController(application, viewModelScope)
    val backup = BackupController(application, viewModelScope)
    val snapshots = SnapshotsController(
        application,
        { _state.value.activeProject },
        { checkpoints.ensureWorkspace(it) },
        { isProjectBusy(_state.value.activeProjectId) },
        viewModelScope,
        onRestored = { restoredProjectId ->
            checkpoints.acceptAll(restoredProjectId)
            viewModelScope.launch {
                if (_state.value.activeProjectId == restoredProjectId) {
                    refreshWorkspaceFilesAndChanges(restoredProjectId)
                    _state.value.selectedFilePath?.let { loadWorkspaceFile(it) }
                }
            }
        },
    )

    internal val claudeRuntime = ClaudeRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    )
    internal val dshRuntime = DshRuntimeBridge(
        context = application,
        secretFor = ::resolveProviderSecret,
        rotateSecretFor = ::rotateProviderSecret,
    )
    internal val antigravityRuntime = AntigravityRuntimeBridge(
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

    internal val _state = MutableStateFlow(
        ForgeUiState(
            themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
            glassSolid = preferences.glassSolid,
            languageCode = AppStrings.languageCode(application),
            runtimeEngineChoice = RuntimeEngineChoice.fromStored(preferences.runtimeEngine).id,
        ),
    )
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    /** Agent turns in progress across all projects, keyed by (projectId, chatId). Only touched on the main thread. */
    internal val runs = LinkedHashMap<RunKey, ChatRun>()
    internal fun runKey(chatId: String) = RunKey(_state.value.activeProjectId, chatId)
    internal fun runKey(projectId: String, chatId: String) = RunKey(projectId, chatId)
    internal fun isProjectBusy(projectId: String): Boolean =
        runs.keys.any { it.projectId == projectId } || projectBuildHandles.containsKey(projectId)

    internal val projectBuildHandles = java.util.concurrent.ConcurrentHashMap<String, ProjectBuildHandle>()
    @Volatile internal var installingProjectId: String? = null
    @Volatile internal var activeTerminalProcess: Process? = null
    internal var activeLogcatJob: Job? = null
    internal var activeLogcatProcess: Process? = null
    internal val logLineIdCounter = AtomicLong(1L)

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
                val targetId = installingProjectId ?: _state.value.activeProjectId
                _state.update {
                    val baseRecord = it.projectBuilds[targetId] ?: it.androidBuild
                    val updatedRecord = baseRecord.copy(
                        message = statusMsg,
                        stage = if (event.success) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                    )
                    it.copy(
                        androidBuild = if (it.activeProjectId == targetId) updatedRecord else it.androidBuild,
                        projectBuilds = if (targetId.isNotBlank()) it.projectBuilds + (targetId to updatedRecord) else it.projectBuilds,
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

    internal fun bootstrap() {
        viewModelScope.launch {
            // Projects come first: they only need local storage. A fresh install has no rootfs yet, and a root prompt or
            // an installer check that throws or hangs must not leave the front page without its default project.
            val projects = loadOrCreateDefaultProject()
            _state.update { if (it.projects.isEmpty()) it.copy(projects = projects) else it }
            val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
            val hwProfile = withContext(Dispatchers.IO) { com.nextcraft.forge.runtime.DeviceHardwareOptimizer.inspect(getApplication()) }
            val ramOk = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.meetsMinimumRam(hwProfile.totalRamMb)
            val androidOk = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.meetsMinimumAndroidVersion()
            val engine = withContext(Dispatchers.IO) { installer.runtimeEngine() }
            val rootOk = engine == RuntimeEngine.CHROOT
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

            val connectors = withContext(Dispatchers.IO) { runCatching { connectorStore.list() }.getOrDefault(emptyList()) }
            val skills = withContext(Dispatchers.IO) { runCatching { skillStore()?.list() }.getOrNull().orEmpty() }

            _state.update {
                it.copy(
                    arm64Supported = arm64,
                    ramSupported = ramOk,
                    totalRamLabel = hwProfile.ramTier.label,
                    androidVersionSupported = androidOk,
                    rootAvailable = rootOk,
                    prootAvailable = engine == RuntimeEngine.PROOT,
                    runtimeInstalled = installed,
                    runtimeStatusLoaded = true,
                    selectedDevStacks = savedStacks,
                    installedDevStacks = installedStacks,
                    agentKind = savedAgent,
                    routing = preferences.routing,
                    themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
                    glassSolid = preferences.glassSolid,
                    // Bootstrap can wait on a root prompt; if the user already opened or deleted a project meanwhile, keep that.
                    projects = if (it.activeProjectId.isBlank()) projects else it.projects,
                    activeProjectId = if (it.activeProjectId.isBlank()) firstProject?.id.orEmpty() else it.activeProjectId,
                    chats = if (it.activeProjectId.isBlank()) chats else it.chats,
                    activeChatId = if (it.activeProjectId.isBlank()) activeChatId else it.activeChatId,
                    messages = if (it.activeProjectId.isBlank()) messages else it.messages,
                    androidBuild = if (it.androidBuild.phase == AndroidBuildPhase.BUILDING) it.androidBuild else initialBuildRecord,
                    providerProfile = profile,
                    savedApiKeys = keys,
                    connectors = connectors,
                    skills = skills,
                    languageCode = AppStrings.languageCode(getApplication()),
                    interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
                    autoApproveAllTools = preferences.autoApproveAllTools,
                    autoVerifyBuild = preferences.autoVerifyBuild,
                    autoFixBuild = preferences.autoFixBuild,
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
            applyChatSettings(_state.value.activeProjectId, _state.value.activeChatId)
            val savedGitHubToken = vault.get("GITHUB_TOKEN").orEmpty()
            if (savedGitHubToken.isNotBlank()) {
                val savedLogin = preferences.githubLogin.ifBlank { "x-access-token" }
                withContext(Dispatchers.IO) {
                    connectorStore.syncGitHubConnector()
                    runCatching { installer.configureGitHubAccess(savedGitHubToken, savedLogin) }
                        .onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
                }
                loadConnectors()
                githubAuth.restoreRepos(savedGitHubToken)
                if (preferences.autoSyncGitHub) {
                    projects.forEach { proj ->
                        val ws = checkpoints.ensureWorkspace(proj.id)
                        if (File(ws, ".git").isDirectory) {
                            syncProjectWithGitHub(proj.id, "Sync project changes from NextCraft Forge")
                        }
                    }
                }
            }
            firstProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
            silentUpdateCheck()
        }
    }

    /** A clean install starts with no projects: the home screen offers Quick project / New project instead of a sample. */
    internal suspend fun loadOrCreateDefaultProject(): List<Project> = withContext(Dispatchers.IO) {
        preferences.loadProjects().onEach { checkpoints.ensureWorkspace(it.id) }
    }

    internal fun configureAllProjectRoots(projects: List<Project>) {
        projects.forEach { project ->
            checkpoints.configureProjectRoot(project.id, project.rootPath)
            claudeRuntime.configureProjectRoot(project.id, project.rootPath)
            dshRuntime.configureProjectRoot(project.id, project.rootPath)
            antigravityRuntime.configureProjectRoot(project.id, project.rootPath)
        }
    }

    internal var lastResumeRefreshAt = 0L

    /** onResume fires on every return to the app; each refresh spawns `su`, so don't repeat it within a few seconds. */
    fun refreshRuntimeStatusOnResume() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastResumeRefreshAt != 0L && now - lastResumeRefreshAt < RESUME_REFRESH_MIN_INTERVAL_MS) return
        lastResumeRefreshAt = now
        refreshRuntimeStatus()
        silentUpdateCheck()
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
            val hwProfile = withContext(Dispatchers.IO) { com.nextcraft.forge.runtime.DeviceHardwareOptimizer.inspect(getApplication(), forceRefresh = true) }
            val ramOk = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.meetsMinimumRam(hwProfile.totalRamMb)
            val androidOk = com.nextcraft.forge.runtime.DeviceHardwareOptimizer.meetsMinimumAndroidVersion()
            val engine = withContext(Dispatchers.IO) { installer.runtimeEngine() }
            val rootOk = engine == RuntimeEngine.CHROOT
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
                    ramSupported = ramOk,
                    totalRamLabel = hwProfile.ramTier.label,
                    androidVersionSupported = androidOk,
                    rootAvailable = rootOk,
                    prootAvailable = engine == RuntimeEngine.PROOT,
                    runtimeInstalled = installed,
                    runtimeStatusLoaded = true,
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

    internal val _backgroundPrompts = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires once, when the runtime setup is first started: the Activity then asks for notifications and the battery exemption. */
    val backgroundPrompts: SharedFlow<Unit> = _backgroundPrompts

    internal fun requestBackgroundPromptsOnce() {
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
    internal fun canRunRuntimeOperation(): Boolean {
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
    internal fun startServiceSafely(context: android.content.Context, intent: Intent) {
        runCatching { ContextCompat.startForegroundService(context, intent) }
            .onFailure { android.util.Log.w("MainViewModel", "Could not start ${intent.component?.shortClassName}: ${it::class.simpleName}") }
    }

    internal fun startRuntimeOperation(scope: SetupScope, configure: Intent.() -> Unit) {
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
    internal fun installCoreWith(stacks: Set<DevStack>, agent: AgentKind) {
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

    internal fun runStackOperation(serviceAction: String, stack: DevStack) {
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

    internal val reservedSlugs = mutableSetOf<String>()

    internal data class PendingDelete(val project: Project, val index: Int)

    internal var pendingDelete: PendingDelete? = null
    internal var pendingDeleteJob: Job? = null

    internal data class PendingChatDelete(val project: Project, val chat: ProjectChat, val index: Int)

    internal var pendingChatDelete: PendingChatDelete? = null
    internal var pendingChatDeleteJob: Job? = null

    // ── Chat branches ────────────────────────────────────────────────────────────────────────────

    internal fun projectOf(id: String): Project? = _state.value.projects.firstOrNull { it.id == id }

    internal val chatImageCache = object : android.util.LruCache<String, androidx.compose.ui.graphics.ImageBitmap>(24) {}
    internal val connectorStore by lazy { ConnectorStore(getApplication()) }
    internal val geminiImage = GeminiImageClient()
    internal val geminiVideo = GeminiVideoClient()
    internal val autoRebuildProjects = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    internal val autoFixRounds = java.util.concurrent.ConcurrentHashMap<RunKey, Int>()
    internal val autoRetriedPrompts = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    internal var workspaceRefreshRequest = 0
    internal var openFileRequest = 0
    internal var afterUnsavedChoice: (() -> Unit)? = null
    internal val terminals = HashMap<String, TerminalHandle>()
    val terminalSessionClient = ForgeTerminalSessionClient(application) { session -> onTerminalSessionEnded(session) }
    internal val chrootProcessFiles: MutableMap<Process, Pair<File, File>> =
        java.util.Collections.synchronizedMap(java.util.WeakHashMap())
    internal val githubOperationLock = java.util.concurrent.locks.ReentrantLock()
    internal var lastImportUri: android.net.Uri? = null

    override fun onCleared() {
        ScheduleRuntime.tickerActive = false
        // viewModelScope is cancelled here, so a project or chat still in its undo window is wiped on a plain thread.
        pendingDelete?.project?.let { project -> Thread { wipeProjectData(project) }.start() }
        pendingChatDelete?.let { pending -> Thread { wipeChatData(pending.project.id, pending.chat.id) }.start() }
        RuntimeTaskController.requestStop()
        diagnostics.cancelHealthCheck()
        rea.cancel()
        buildMatrix.cancel()
        rom.cancel()
        ssh.stopAllTunnels()
        projectBuildHandles.values.forEach { stopChrootProcess(it.process) }
        activeLogcatProcess?.destroyForcibly()
        stopChrootProcess(activeTerminalProcess)
        terminals.values.forEach { it.session?.finishIfRunning() }
        super.onCleared()
    }

    internal companion object {
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

internal const val AGENT_TURN_END_GRACE_MS = 2_000L

/** Upper bound on concurrent agent sessions across all projects; hardware RAM is the primary governor. */
internal const val MAX_PARALLEL_CHATS = 12
internal const val SCHEDULE_TICK_MS = 30_000L

/** Free RAM needed before another parallel run starts; below it the message waits in the queue. */
internal const val MIN_FREE_BYTES_FOR_CLAUDE_RUN = 650L * 1024 * 1024
internal const val MIN_FREE_BYTES_FOR_LIGHT_RUN = 450L * 1024 * 1024

