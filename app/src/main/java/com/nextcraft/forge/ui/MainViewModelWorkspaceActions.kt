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
 * Walks through the app's main screens, saves a picture of each in the project's attachments folder and adds them
 * to the message being written, so the agent can look at the UI. The screen the person was on is restored.
 */
fun MainViewModel.captureAppScreens(window: android.view.Window) {
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
            delay(MainViewModel.KEYBOARD_CLOSE_MS)
            appCaptureSteps().forEachIndexed { index, step ->
                if (step.pane != null) {
                    _state.update { it.copy(projectView = ProjectView.CONVERSATION) }
                    selectProjectPane(step.pane)
                } else {
                    if (step.tab == ForgeTab.PROJECTS) _state.update { it.copy(projectView = ProjectView.LIST) }
                    selectTab(step.tab)
                }
                delay(MainViewModel.CAPTURE_SETTLE_MS + step.extraSettleMs)
                val bitmap = captureWindow(window) ?: return@forEachIndexed
                val name = "%02d-%s.png".format(index + 1, step.name)
                try {
                    withContext(Dispatchers.IO) { savePng(project.id, "$folder/$name", name, bitmap) }?.let { added += it }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
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

internal fun MainViewModel.savePng(projectId: String, relativePath: String, displayName: String, bitmap: android.graphics.Bitmap): ChatAttachment? =
    runCatching {
        val target = checkpoints.safeWorkspaceFile(checkpoints.ensureWorkspace(projectId), relativePath)
        target.parentFile?.mkdirs()
        target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        ChatAttachment(displayName = displayName, relativePath = relativePath, mimeType = "image/png", sizeBytes = target.length())
    }.onFailure { Log.w("MainViewModel", "Could not save $relativePath", it) }.getOrNull()

fun MainViewModel.removePendingAttachment(id: String) {
    _state.update { st -> st.copy(pendingAttachments = st.pendingAttachments.filterNot { it.id == id }) }
}

internal fun MainViewModel.copyUrisIntoWorkspace(projectId: String, uris: List<android.net.Uri>, dir: String): List<ChatAttachment> {
    val resolver = getApplication<Application>().contentResolver
    val proj = _state.value.projects.firstOrNull { it.id == projectId }
    val ws = if (proj != null && dir != "attachments") activeWorkspaceRoot(proj) else checkpoints.ensureWorkspace(projectId)
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

internal fun MainViewModel.queryDisplayName(uri: android.net.Uri): String? =
    runCatching {
        getApplication<Application>().contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

/** Writes a ZIP of [paths] (or the whole workspace when empty) to a document the user chose. */
fun MainViewModel.exportWorkspaceZip(target: android.net.Uri, paths: List<String>) {
    val project = _state.value.activeProject ?: return
    viewModelScope.launch {
        val count = withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
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
        val msg = if (count >= 0) quantity(R.plurals.msg_zip_saved, count) else str(R.string.msg_zip_failed)
        _state.update {
            it.copy(
                fileSavedBanner = msg,
                bannerMessage = UiText.Raw(msg),
            )
        }
    }
}

/** Copies one workspace file to a document the user chose (Storage Access Framework "save as"). */
fun MainViewModel.exportWorkspaceFile(target: android.net.Uri, relativePath: String) {
    val project = _state.value.activeProject ?: return
    viewModelScope.launch {
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val source = resolveWorkspaceFile(project, relativePath)
                check(source.isFile && !Files.isSymbolicLink(source.toPath())) { "Not a regular file" }
                getApplication<Application>().contentResolver.openOutputStream(target)?.use { os ->
                    source.inputStream().use { it.copyTo(os) }
                } ?: error("Cannot open $target")
            }.onFailure { Log.w("MainViewModel", "File export failed", it) }.isSuccess
        }
        val msg = if (ok) str(R.string.msg_file_saved, relativePath.substringAfterLast('/')) else str(R.string.msg_file_failed)
        _state.update {
            it.copy(
                fileSavedBanner = msg,
                bannerMessage = UiText.Raw(msg),
            )
        }
    }
}

fun MainViewModel.deleteWorkspaceFile(relativePath: String) {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    val cleanPath = relativePath.trim().trimStart('/')
    if (cleanPath.isEmpty()) return
    viewModelScope.launch {
        val deleted = withContext(Dispatchers.IO) {
            runCatching {
                val ws = activeWorkspaceRoot(project)
                val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                target.exists() && target.deleteRecursivelyNoFollow()
            }.getOrDefault(false)
        }
        if (deleted) {
            val msg = str(R.string.msg_file_deleted, cleanPath)
            if (_state.value.selectedFilePath == cleanPath || _state.value.selectedFilePath?.startsWith("$cleanPath/") == true) {
                _state.update {
                    it.copy(
                        selectedFilePath = null,
                        selectedFileContent = "",
                        selectedFileDirty = false,
                        fileSavedBanner = msg,
                        bannerMessage = UiText.Raw(msg),
                    )
                }
            } else {
                _state.update { it.copy(fileSavedBanner = msg, bannerMessage = UiText.Raw(msg)) }
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }
}

fun MainViewModel.revertDiffHunk(path: String, hunkIndex: Int) {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_revert_file, path)) {
            val ws = activeWorkspaceRoot(project)
            val checkpointId = activeCheckpointProjectId(project)
            checkpoints.revertHunk(checkpointId, ws, path, hunkIndex)
        } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
        if (_state.value.selectedFilePath == path) {
            loadWorkspaceFile(path)
        }
    }
}

fun MainViewModel.cleanIdleDaemons() {
    com.nextcraft.forge.runtime.GradleDaemonReaper.stopIdleNow {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.resource_daemons_cleaned)) }
    }
}

fun MainViewModel.fixCiFailureWithAgent(workflowName: String, branch: String, url: String) {
    val prompt = buildString {
        append("GitHub Actions workflow '").append(workflowName).append("' failed on branch '").append(branch).append("'")
        if (url.isNotBlank()) append(" (").append(url).append(")")
        append(".\nPlease inspect the workspace code, build configuration, and unit tests to diagnose and fix the CI failure.")
    }
    sendPrompt(prompt)
    openConversation()
}

fun MainViewModel.captureUiInspectorHierarchy() {
    if (_state.value.uiInspectorBusy) return
    if (!RuntimeEngines.isRootEngine()) {
        _state.update { it.copy(uiInspectorNodes = emptyList(), bannerMessage = UiText.res(R.string.mt_root_required)) }
        return
    }
    _state.update { it.copy(uiInspectorBusy = true) }
    viewModelScope.launch {
        val nodes = withContext(Dispatchers.IO) {
            runCatching {
                val outFile = File(getApplication<Application>().cacheDir, "forge_ui_dump.xml")
                outFile.delete()
                val proc = ProcessBuilder(
                    "su",
                    "-c",
                    "uiautomator dump /data/local/tmp/forge_ui_dump.xml >/dev/null 2>&1 && cat /data/local/tmp/forge_ui_dump.xml; rm -f /data/local/tmp/forge_ui_dump.xml",
                ).redirectErrorStream(true).redirectOutput(outFile).start()
                runCatching { proc.outputStream.close() }
                val finished = proc.waitFor(12, java.util.concurrent.TimeUnit.SECONDS)
                if (!finished) proc.destroyForcibly()
                val xml = if (outFile.isFile) outFile.readText() else ""
                outFile.delete()
                parseUiInspectorXml(xml)
            }.getOrDefault(emptyList())
        }
        _state.update {
            it.copy(
                uiInspectorNodes = nodes,
                uiInspectorBusy = false,
                bannerMessage = if (nodes.isEmpty()) UiText.res(R.string.inspector_empty_hint) else it.bannerMessage,
            )
        }
    }
}

fun MainViewModel.editUiElementWithAgent(node: UiInspectorNode, instruction: String) {
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

internal fun MainViewModel.unescapeXmlAttr(raw: String): String {
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

internal fun MainViewModel.parseUiInspectorXml(xml: String): List<UiInspectorNode> {
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

fun MainViewModel.revertSingleFile(path: String) {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_revert_file, path)) {
            val ws = activeWorkspaceRoot(project)
            val checkpointId = activeCheckpointProjectId(project)
            checkpoints.revertFile(checkpointId, ws, path)
        } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
        if (_state.value.selectedFilePath == path) {
            // The user asked to revert this file, so the editor follows the file on disk.
            loadWorkspaceFile(path)
        }
    }
}

fun MainViewModel.keepSingleFile(path: String) {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_keep_file, path)) {
            val ws = activeWorkspaceRoot(project)
            val checkpointId = activeCheckpointProjectId(project)
            checkpoints.acceptFile(checkpointId, ws, path)
        } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
    }
}

fun MainViewModel.keepAllChanges() {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_keep)) { checkpoints.acceptAll(activeCheckpointProjectId(project)) } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
    }
}

fun MainViewModel.revertAllChanges() {
    val project = _state.value.activeProject ?: return
    if (runs.keys.any { it.projectId == project.id }) return
    viewModelScope.launch {
        workspaceIo(str(R.string.err_ws_revert)) {
            val ws = activeWorkspaceRoot(project)
            val checkpointId = activeCheckpointProjectId(project)
            checkpoints.revertLatestTurn(checkpointId, ws)
        } ?: return@launch
        refreshWorkspaceFilesAndChanges(project.id)
        _state.value.selectedFilePath?.let { loadWorkspaceFile(it) }
    }
}
