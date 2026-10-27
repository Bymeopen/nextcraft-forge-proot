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
 * The image behind a chat `src`. Workspace files are read through the same path checks as the file editor (no
 * `..`, no symlinks) from the chat's own branch first, then from the project; remote addresses are only fetched
 * after the reader tapped them.
 */
suspend fun MainViewModel.loadChatImage(src: String): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
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


internal fun MainViewModel.skillStore(): ClaudeSkillStore? = runCatching {
    ClaudeSkillStore(File(installer.installedRuntime().rootfs, "root/.claude/skills"))
}.getOrNull()

fun MainViewModel.loadConnectors() {
    viewModelScope.launch {
        val (connectors, skills) = withContext(Dispatchers.IO) {
            connectorStore.list() to skillStore()?.list().orEmpty()
        }
        _state.update { it.copy(connectors = connectors, skills = skills) }
    }
}

/** Adds or updates a connector; a blank [secret] keeps the saved one when editing and means none when new. */
fun MainViewModel.saveConnector(name: String, transport: McpTransport, target: String, secretName: String, secret: String): Boolean {
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
        return false
    }
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            val existing = connectorStore.list().firstOrNull { it.id == id }
            connectorStore.upsert(
                McpConnector(id, name.trim().take(40), transport, cleanTarget, secretName.trim(), existing?.enabled ?: true),
                secret.takeIf { it.isNotBlank() },
            )
            installer.syncMcpConfigs()
        }
        _state.update { it.copy(bannerMessage = UiText.res(R.string.connector_saved)) }
        loadConnectors()
    }
    return true
}

fun MainViewModel.setConnectorEnabled(id: String, enabled: Boolean) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            connectorStore.setEnabled(id, enabled)
            installer.syncMcpConfigs()
        }
        loadConnectors()
    }
}

fun MainViewModel.deleteConnector(id: String) {
    viewModelScope.launch {
        withContext(Dispatchers.IO) {
            connectorStore.delete(id)
            installer.syncMcpConfigs()
        }
        loadConnectors()
    }
}

/** Connects the official open-source Figma MCP server (`figma-developer-mcp`) with a Figma Personal Access Token. */
fun MainViewModel.connectFigma(token: String) {
    val cleanToken = token.trim()
    if (cleanToken.isEmpty()) return
    saveConnector(
        name = "Figma",
        transport = McpTransport.COMMAND,
        target = "npx -y figma-developer-mcp --stdio",
        secretName = "FIGMA_API_KEY",
        secret = cleanToken,
    )
}

/** Disconnects the Figma MCP server and removes its stored token from the Android Keystore vault. */
fun MainViewModel.disconnectFigma() {
    deleteConnector("figma")
}

/** Imports a Figma frame/design URL into the active project (or a new Quick project) and starts a design-to-code turn. */
fun MainViewModel.importFigmaDesignUrl(figmaUrl: String) {
    val cleanUrl = figmaUrl.trim()
    if (cleanUrl.isEmpty()) return
    val prompt = "Implement the UI/UX design from this Figma link ($cleanUrl) using the Figma MCP / design tools, matching layout, typography, colors, spacing, and responsive components."
    val active = _state.value.activeProject
    if (active != null) {
        _state.update {
            it.copy(
                activeTab = ForgeTab.PROJECTS,
                projectView = ProjectView.CONVERSATION,
            )
        }
        sendPrompt(prompt)
    } else {
        viewModelScope.launch {
            val project = withContext(Dispatchers.IO) {
                val identity = generateQuickChatIdentity(_state.value.projects.map { it.slug }.toSet(), str(R.string.quick_project_name))
                val project = Project(
                    name = identity.displayName,
                    description = "Figma design project",
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
                sendPrompt(prompt)
            }
        }
    }
}

fun MainViewModel.saveSkill(name: String, description: String, body: String) {
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

fun MainViewModel.deleteSkill(name: String) {
    viewModelScope.launch {
        val deleted = withContext(Dispatchers.IO) { runCatching { skillStore()?.delete(name) == true }.getOrDefault(false) }
        if (!deleted) _state.update { it.copy(bannerMessage = UiText.res(R.string.skill_save_failed)) }
        loadConnectors()
    }
}

suspend fun MainViewModel.loadSkillBody(name: String): String = withContext(Dispatchers.IO) { skillStore()?.body(name).orEmpty() }

// ── Image generation (Nano Banana) ───────────────────────────────────────────────────────────


fun MainViewModel.hasGeminiImageKey(): Boolean = vault.contains(GeminiImageModels.KEY_ID)

fun MainViewModel.saveGeminiImageKey(key: String) {
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
fun MainViewModel.generateImage(prompt: String, model: String) {
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
                val root = activeWorkspaceRoot(project, chatId)
                val input = attachment?.let { att ->
                    val file = resolveWorkspaceFile(project, att.relativePath, chatId)
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


/**
 * Creates a video clip with Gemini Omni (image-to-video when an image is attached), saves it under `videos/` in the
 * chat's workspace and adds it to the chat. The call is synchronous on Google's side and can take minutes.
 */
fun MainViewModel.generateVideo(prompt: String, aspectRatio: String, resolution: String) {
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
                val root = activeWorkspaceRoot(project, chatId)
                val input = attachment?.let { att ->
                    val file = resolveWorkspaceFile(project, att.relativePath, chatId)
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
internal fun MainViewModel.resolveChatMediaFile(src: String): java.io.File? {
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
suspend fun MainViewModel.loadChatVideoThumbnail(src: String): androidx.compose.ui.graphics.ImageBitmap? = withContext(Dispatchers.IO) {
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
fun MainViewModel.openChatVideo(context: android.content.Context, src: String) {
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
internal fun MainViewModel.appendLocalMessages(projectId: String, chatId: String, added: List<ChatMessage>) {
    val current = currentMessagesOf(projectId, chatId)
    val updated = current.filterNot { it.id == "streaming-assistant" } + added
    preferences.saveMessages(projectId, chatId, updated)
    if (_state.value.activeProjectId == projectId && _state.value.activeChatId == chatId) {
        _state.update { it.copy(messages = updated) }
    }
}

