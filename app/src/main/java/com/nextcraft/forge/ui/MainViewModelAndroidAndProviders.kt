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

/** The running-task notification names the build stage, so the lock screen shows more than "Running assembleDebug". */
internal fun MainViewModel.pushBuildStageToNotification(stage: AndroidBuildStage, projectName: String) {
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

fun MainViewModel.runAndroidBuild(
    task: String = "assembleDebug",
    installAfterBuild: Boolean = false,
    targetProject: Project? = _state.value.activeProject,
    lowMemory: Boolean = false,
    onFinished: ((BuildOutcome) -> Unit)? = null,
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
            val (ws, buildSlug) = withContext(Dispatchers.IO) { activeWorkspaceTarget(project) }
            val root = withContext(Dispatchers.IO) { findAndroidProjectRoot(ws) ?: ws }
            androidRoot = root
            val cmd = androidGradleCommand(root, task, if (lowMemory) BuildVerification.LOW_MEMORY_FLAGS else "")
            withContext(Dispatchers.IO) {
                val relativeRoot = root.relativeTo(ws).invariantSeparatorsPath
                val guestCommand = if (relativeRoot.isEmpty()) cmd else "cd ${shellSingleQuote(relativeRoot)} && $cmd"
                val process = startChrootProcess(buildSlug, ws, guestCommand)
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
                        if (stageChanged || android.os.SystemClock.elapsedRealtime() - lastPublishAt >= MainViewModel.LOG_PUBLISH_MS) publishLog(stage)
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
                    else -> issues.firstOrNull()?.detail?.let { localizeMsg(it) } ?: str(R.string.build_failed_exit, exitCode)
                },
                issues = issues,
            )
            current.copy(
                androidBuild = if (current.activeProjectId == project.id) finishedRecord else current.androidBuild,
                buildingProjectIds = current.buildingProjectIds - project.id,
                projectBuilds = current.projectBuilds + (project.id to finishedRecord),
            )
        }
            val finalAction = when {
                cancelled -> RuntimeExecutionService.ACTION_CANCELLED
                succeeded -> RuntimeExecutionService.ACTION_COMPLETE
                else -> RuntimeExecutionService.ACTION_FAILED
            }
            val finalDetailRes = when {
                cancelled -> R.string.notif_build_cancelled
                succeeded -> R.string.notif_build_succeeded
                else -> R.string.notif_build_failed
            }
            if (projectBuildHandles.isEmpty()) {
                runCatching {
                    getApplication<Application>().startService(
                        Intent(getApplication(), RuntimeExecutionService::class.java)
                            .setAction(finalAction)
                            .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                            .putExtra(RuntimeExecutionService.EXTRA_DETAIL_RES, finalDetailRes),
                    )
                }.onFailure { error ->
                    Log.w("MainViewModel", "Could not post the build result notification", error)
                    runCatching {
                        getApplication<Application>().stopService(Intent(getApplication(), RuntimeExecutionService::class.java))
                    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
                }
            } else {
                startServiceSafely(
                    getApplication(),
                    Intent(getApplication(), RuntimeExecutionService::class.java)
                        .setAction(finalAction)
                        .putExtra(RuntimeExecutionService.EXTRA_PROJECT_NAME, project.name)
                        .putExtra(RuntimeExecutionService.EXTRA_DETAIL_RES, finalDetailRes),
                )
            }
        val failureKind = if (succeeded || cancelled) null else BuildVerification.classify(
            fullLog, exitCode, issues.count { it.severity == AndroidIssueSeverity.ERROR },
        )
        // Out of memory is the phone, not the code: try once more with the lightest Gradle settings.
        if (failureKind == BuildVerification.FailureKind.OUT_OF_MEMORY && !lowMemory) {
            _state.update { it.copy(bannerMessage = UiText.res(R.string.build_retry_low_memory)) }
            runAndroidBuild(task, installAfterBuild, project, lowMemory = true, onFinished = onFinished)
            return@launch
        }
        if (succeeded && !cancelled && task != "clean" && _state.value.autoSyncGitHub) {
            syncProjectWithGitHub(project.id, "Auto-sync after Android build ($task) from NextCraft Forge")
        }
        if (succeeded && !cancelled && installAfterBuild && finalApk != null) {
            installBuiltApk(finalApk.absolutePath, project.id)
        }
        onFinished?.invoke(BuildOutcome(succeeded, cancelled, failureKind, issues, fullLog))
    }
    handle.job = job
}

/** Installs an APK that lives in the active project (e.g. one the agent built or copied), from the Files list. */
fun MainViewModel.installWorkspaceApk(relativePath: String) {
    val project = _state.value.activeProject ?: return
    viewModelScope.launch {
        val apk = withContext(Dispatchers.IO) {
            runCatching { resolveWorkspaceFile(project, relativePath) }.getOrNull()
        }
        if (apk != null && apk.isFile) installBuiltApk(apk.absolutePath, project.id)
    }
}

/** Copies the built (or existing debug) APK of the active project to a document URI chosen by the user. */
fun MainViewModel.exportBuiltApk(target: android.net.Uri) {
    val project = _state.value.activeProject ?: return
    val currentApkPath = _state.value.androidBuild.apkPath
    viewModelScope.launch {
        val savedName = withContext(Dispatchers.IO) {
            runCatching {
                val apkFile = currentApkPath?.let(::File)?.takeIf { it.isFile }
                    ?: findExistingProjectApk(project)
                    ?: error("APK not found")
                check(apkFile.isFile && !Files.isSymbolicLink(apkFile.toPath())) { "Not a regular APK file" }
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                    apkFile.inputStream().use { it.copyTo(os) }
                } ?: error("Cannot open $target")
                apkFile.name
            }.onFailure { Log.w("MainViewModel", "APK export failed", it) }.getOrNull()
        }
        val msg = if (savedName != null) str(R.string.msg_file_saved, savedName) else str(R.string.msg_file_failed)
        _state.update {
            it.copy(
                fileSavedBanner = msg,
                bannerMessage = UiText.Raw(msg),
            )
        }
    }
}

fun MainViewModel.installBuiltApk(
    apkPath: String? = _state.value.androidBuild.apkPath,
    projectId: String = _state.value.activeProjectId,
) {
    val path = apkPath ?: return
    val apkFile = File(path)
    if (!apkFile.isFile) return
    installingProjectId = projectId
    viewModelScope.launch {
        _state.update {
            val baseRecord = it.projectBuilds[projectId] ?: it.androidBuild
            val updatedRecord = baseRecord.copy(
                stage = AndroidBuildStage.INSTALLING,
                message = str(R.string.build_installing_apk, apkFile.name),
            )
            it.copy(
                androidBuild = if (it.activeProjectId == projectId) updatedRecord else it.androidBuild,
                projectBuilds = if (projectId.isNotBlank()) it.projectBuilds + (projectId to updatedRecord) else it.projectBuilds,
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
                val baseRecord = it.projectBuilds[projectId] ?: it.androidBuild
                val failedRecord = baseRecord.copy(
                    stage = AndroidBuildStage.FAILED,
                    message = errorMsg,
                )
                it.copy(
                    androidBuild = if (it.activeProjectId == projectId) failedRecord else it.androidBuild,
                    projectBuilds = if (projectId.isNotBlank()) it.projectBuilds + (projectId to failedRecord) else it.projectBuilds,
                    bannerMessage = UiText.Raw(errorMsg),
                    bannerAction = BannerAction(BannerActionKind.RETRY_APK_INSTALL, UiText.Raw(errorMsg)),
                )
            }
        }
    }
}

internal fun MainViewModel.resolveProjectAndroidPackageName(project: Project): String {
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

fun MainViewModel.startProjectLogcat() {
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

fun MainViewModel.stopProjectLogcat() {
    activeLogcatProcess?.destroyForcibly()
    activeLogcatProcess = null
    activeLogcatJob?.cancel()
    _state.update {
        it.copy(androidLogcat = it.androidLogcat.copy(running = false, message = str(R.string.logcat_paused)))
    }
}

fun MainViewModel.clearProjectLogcat() {
    _state.update {
        it.copy(androidLogcat = it.androidLogcat.copy(lines = emptyList()))
    }
}

fun MainViewModel.setLogcatMinimumLevel(level: AndroidLogLevel) {
    _state.update {
        it.copy(androidLogcat = it.androidLogcat.copy(minimumLevel = level))
    }
}

fun MainViewModel.setLogcatQuery(query: String) {
    _state.update {
        it.copy(androidLogcat = it.androidLogcat.copy(query = query))
    }
}

fun MainViewModel.updatePreviewUrl(url: String) {
    _state.update { it.copy(previewUrl = url) }
}

fun MainViewModel.scanLocalPreviewPorts() {
    viewModelScope.launch {
        val openPorts = withContext(Dispatchers.IO) { LocalNetwork.scanOpenLocalPorts() }
        _state.update { current ->
            val localPortMatch = Regex("""^https?://(?:127\.0\.0\.1|localhost):(\d+)(?:/.*)?$""", RegexOption.IGNORE_CASE)
                .matchEntire(current.previewUrl.trim())
            val currentLocalPort = localPortMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
            val newUrl = if (openPorts.isNotEmpty() && (currentLocalPort != null && currentLocalPort !in openPorts)) {
                "http://127.0.0.1:${openPorts.first()}"
            } else {
                current.previewUrl
            }
            current.copy(activeLocalPorts = openPorts, previewUrl = newUrl)
        }
    }
}
