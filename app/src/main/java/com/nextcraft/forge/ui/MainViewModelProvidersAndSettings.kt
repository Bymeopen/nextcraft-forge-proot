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

fun MainViewModel.updateProviderKind(kind: ProviderKind) {
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

fun MainViewModel.updateProviderConfig(
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

fun MainViewModel.validateAntigravityConnection() {
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

fun MainViewModel.addApiKey(label: String, secret: String) {
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

fun MainViewModel.selectActiveApiKey(keyId: String) {
    val providerId = _state.value.providerProfile.kind.name
    vault.activate(providerId, keyId)
    _state.update { it.copy(savedApiKeys = vault.list(providerId)) }
}

fun MainViewModel.removeApiKey(keyId: String) {
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

fun MainViewModel.discoverProviderModels() {
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

fun MainViewModel.validateProviderConnection() {
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
            is ConnectionValidation.Failure -> "✕ ${localizeMsg(res.label)}: ${localizeMsg(res.message)} ${res.providerMessage?.let { localizeMsg(it) }.orEmpty()}".trim()
        }
        _state.update { it.copy(apiBusy = false, apiStatusMessage = UiText.Raw(msg)) }
    }
}


/** Stores the engine choice and re-detects, so the onboarding row and the next launch follow it. */
fun MainViewModel.setRuntimeEngineChoice(id: String) {
    val choice = RuntimeEngineChoice.fromStored(id)
    preferences.runtimeEngine = choice.id
    RuntimeEngines.invalidate()
    _state.update { it.copy(runtimeEngineChoice = choice.id) }
    refreshRuntimeStatus()
}

fun MainViewModel.setLanguageCode(code: String) {
    val clean = code.trim().ifEmpty { "system" }
    AppStrings.saveLanguageCode(getApplication(), clean)
    _state.update { it.copy(languageCode = clean) }
}

fun MainViewModel.setInteractiveHighRiskApprovals(enabled: Boolean) {
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

fun MainViewModel.setAutoVerifyBuild(enabled: Boolean) {
    preferences.autoVerifyBuild = enabled
    _state.update { it.copy(autoVerifyBuild = enabled) }
}

fun MainViewModel.setAutoFixBuild(enabled: Boolean) {
    preferences.autoFixBuild = enabled
    _state.update { it.copy(autoFixBuild = enabled) }
}

/** The local build check (build + unit tests in one Gradle run) of the open project, by hand. */
fun MainViewModel.verifyBuildNow() {
    val project = _state.value.activeProject ?: return
    runAndroidBuild(BuildVerification.VERIFY_TASKS, targetProject = project) { outcome ->
        onBuildVerified(project, _state.value.activeChatId, runKey(project.id, _state.value.activeChatId), outcome)
    }
}

fun MainViewModel.setAutoApproveAllTools(enabled: Boolean) {
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
fun MainViewModel.loadRuntimeCacheSizes() {
    _state.update { it.copy(runtimeCacheSizes = null) }
    viewModelScope.launch {
        val rootfs = RuntimeInstaller.rootfsDir(getApplication())
        val sizes = withContext(Dispatchers.IO) { com.nextcraft.forge.runtime.RuntimeCaches.sizes(rootfs) }
        _state.update { it.copy(runtimeCacheSizes = sizes.mapKeys { (kind, _) -> kind.name }) }
    }
}

/** Deletes every download cache; refused while an agent or Android build runs, since a build may be using them. */
fun MainViewModel.clearRuntimeCaches() {
    if (_state.value.sessionRunning || _state.value.buildingProjectIds.isNotEmpty()) {
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

fun MainViewModel.setAutoKeepChanges(enabled: Boolean) {
    preferences.autoKeepChanges = enabled
    _state.update { it.copy(autoKeepChanges = enabled) }
}

fun MainViewModel.setAndroidHostTools(enabled: Boolean) {
    preferences.androidHostTools = enabled
    _state.update { it.copy(androidHostTools = enabled) }
    viewModelScope.launch(Dispatchers.IO) { runCatching { installer.applyAndroidHostTools() } }
}

fun MainViewModel.setGameTemplatesEnabled(enabled: Boolean) {
    preferences.gameTemplatesEnabled = enabled
    _state.update { it.copy(gameTemplatesEnabled = enabled) }
}

fun MainViewModel.setUpdateNotifications(enabled: Boolean) = appUpdate.setNotifications(enabled)

/** Background download and silent install while the app is idle (needs root; without it only the notification shows). */
fun MainViewModel.setAutoInstallUpdates(enabled: Boolean) = appUpdate.setAutoInstall(enabled)

/** See [com.nextcraft.forge.update.AppUpdateController.silentCheck]. */
fun MainViewModel.silentUpdateCheck(force: Boolean = false) = appUpdate.silentCheck(force)

fun MainViewModel.setReaChatHint(enabled: Boolean) {
    preferences.reaChatHint = enabled
    _state.update { it.copy(reaChatHint = enabled) }
}

fun MainViewModel.setAutoBranchChats(enabled: Boolean) {
    preferences.autoBranchChats = enabled
    _state.update { it.copy(autoBranchChats = enabled) }
}

fun MainViewModel.setAutoSyncGitHub(enabled: Boolean) {
    preferences.autoSyncGitHub = enabled
    _state.update { it.copy(autoSyncGitHub = enabled) }
}

/** Saves the Claude Code effort level ("" = model default); it reaches the CLI as CLAUDE_CODE_EFFORT_LEVEL. */
fun MainViewModel.setClaudeEffort(level: String) {
    val clean = level.trim().lowercase().takeIf { it in CLAUDE_EFFORT_LEVELS } ?: ""
    preferences.claudeEffort = clean
    _state.update { it.copy(claudeEffort = clean) }
}

fun MainViewModel.setAntigravityConfig(model: String, effort: String) {
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
fun MainViewModel.selectAntigravityModel(model: String) {
    val effort = AGY_EFFORT_SUFFIX.find(model)?.groupValues?.get(1) ?: _state.value.antigravityEffort
    setAntigravityConfig(model, effort)
}

/** Lists the models the signed-in Google account can use by running `agy models` in the chroot. */
fun MainViewModel.refreshAntigravityModels() {
    if (_state.value.antigravityModelsLoading) return
    _state.update { it.copy(antigravityModelsLoading = true) }
    viewModelScope.launch(Dispatchers.IO) {
        if (!installer.isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            _state.update {
                it.copy(
                    antigravityModelsLoading = false,
                    apiStatusMessage = UiText.res(R.string.msg_install_antigravity_first),
                )
            }
            return@launch
        }
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

fun MainViewModel.releaseMemoryCaches() {
    _state.update {
        it.copy(
            androidLogcat = it.androidLogcat.copy(lines = it.androidLogcat.lines.takeLast(100)),
        )
    }
}

fun MainViewModel.checkForAppUpdates(manifestUrlOverride: String = "") = appUpdate.checkNow(manifestUrlOverride)

fun MainViewModel.downloadAndInstallAppUpdate() = appUpdate.downloadAndInstall()

fun MainViewModel.setThemeMode(mode: String) {
    val resolved = ForgeThemeMode.fromStored(mode).id
    preferences.themeMode = resolved
    _state.update { it.copy(themeMode = resolved) }
}

fun MainViewModel.setGlassSolid(solid: Boolean) {
    preferences.glassSolid = solid
    glassUserSolid = solid
    _state.update { it.copy(glassSolid = solid) }
}

fun MainViewModel.toggleThemeMode() {
    val current = ForgeThemeMode.fromStored(_state.value.themeMode)
    val entries = ForgeThemeMode.entries
    val next = entries[(current.ordinal + 1) % entries.size]
    setThemeMode(next.id)
}

/** Runs the banner's recovery button. SET_UP_RUNTIME is the UI's job (it reopens the setup screen). */
fun MainViewModel.runBannerAction(kind: BannerActionKind) {
    _state.update { it.copy(bannerMessage = null, bannerAction = null) }
    when (kind) {
        BannerActionKind.RETRY_IMPORT -> lastImportUri?.let(this::importZipProject)
        BannerActionKind.RETRY_UPDATE_CHECK -> checkAgentUpdates()
        BannerActionKind.RETRY_APK_INSTALL -> installBuiltApk()
        BannerActionKind.SET_UP_RUNTIME -> Unit
    }
}

fun MainViewModel.dismissBanner() {
    _state.update { it.copy(bannerMessage = null, bannerAction = null) }
}
