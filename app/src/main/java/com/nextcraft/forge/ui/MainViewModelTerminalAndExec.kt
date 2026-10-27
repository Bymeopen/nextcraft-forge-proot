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

/** A line to type: [inProject] lines first change into the terminal's folder (resolved when typed). */
internal class TerminalInput(val text: String, val inProject: Boolean)

/**
 * One shell. [chatId] null is the general terminal of the Terminal tab (the chroot's home); otherwise the shell
 * belongs to that chat and starts in the chat's own folder (its branch clone when the chat is isolated, else the
 * project folder), so every chat has a separate terminal.
 */
internal class TerminalHandle(val chatId: String?) {
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

internal class TerminalLaunchSpec(
    val shell: String,
    val cwd: String,
    val args: Array<String>,
    val env: Array<String>,
    val inChroot: Boolean,
)

// --- Interactive terminal ---------------------------------------------------------------------------------
// One general-purpose shell for the whole app (not tied to a project): the root chroot's home (/root) when the
// runtime is installed and root is granted, otherwise the Android shell. It is run by the Termux terminal emulator
// on its own PTY, so editors, pagers and TUIs (vim, htop, tmux, Claude Code) work. The UI (TerminalScreen)
// attaches a TerminalView to the session; the shell starts when the view is first laid out. Projects live under
// /workspace/<name>: use "cd" or the project chip, or "Run in terminal" from chat, which changes into the project.


/** Chat ids are only unique inside a project (every project has a chat called "main"), so the key holds both. */
internal fun MainViewModel.terminalKey(chatId: String?): String = chatId?.let { "chat:${_state.value.activeProjectId}:$it" } ?: "main"

internal fun MainViewModel.terminalHandle(chatId: String?): TerminalHandle =
    terminals.getOrPut(terminalKey(chatId)) { TerminalHandle(chatId) }


fun MainViewModel.currentTerminalSession(chatId: String? = null): TerminalSession? = terminalHandle(chatId).session

fun MainViewModel.isTerminalFinished(chatId: String? = null): Boolean = terminalHandle(chatId).finished

/** Where the terminal's folder is as seen from its shell: the project for the general terminal, the chat folder for a chat's. */
fun MainViewModel.terminalProjectDir(chatId: String? = null): String? {
    val handle = terminalHandle(chatId)
    if (chatId != null) return if (handle.inChroot) handle.guestDir else handle.hostDir?.absolutePath
    val project = _state.value.activeProject ?: return null
    // Known once the shell exists (commands are only typed then): the chroot mounts projects at /workspace/<name>.
    return if (handle.inChroot) "/workspace/${project.slug}" else checkpoints.ensureWorkspace(project.id).absolutePath
}

/** Starts the shell if there is none. An ended shell is replaced only by [restartTerminalSession]. */
fun MainViewModel.ensureTerminalSession() = ensureTerminalSession(null)

fun MainViewModel.ensureTerminalSession(chatId: String?) {
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
                MainViewModel.TERMINAL_TRANSCRIPT_ROWS,
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

internal suspend fun MainViewModel.prepareChatBranchFolder(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated != true) return
    runCatching { branchLock(chatId).withLock { chatBranches.ensure(project, chatId, { notifyGitWaitingInQueue() }) } }
        .onFailure { Log.w("MainViewModel", "chat branch for the terminal failed", it) }
}

/** "Restart" after the shell ended: discards the dead session and starts a fresh one. */
fun MainViewModel.restartTerminalSession() = restartTerminalSession(null)

fun MainViewModel.restartTerminalSession(chatId: String?) {
    val handle = terminalHandle(chatId)
    handle.session?.finishIfRunning()
    handle.session = null
    handle.finished = false
    handle.pending.clear()
    _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
    ensureTerminalSession(chatId)
}

/** Ends the shell of a chat that was deleted. */
internal fun MainViewModel.closeChatTerminal(chatId: String) {
    terminals.remove(terminalKey(chatId))?.let { handle ->
        handle.pending.clear()
        handle.session?.finishIfRunning()
    }
}

/** Ends every chat shell of [projectId] (its folders are about to be deleted). */
internal fun MainViewModel.closeProjectTerminals(projectId: String) {
    terminals.keys.filter { it.startsWith("chat:$projectId:") }.forEach { key ->
        terminals.remove(key)?.let { handle ->
            handle.pending.clear()
            handle.session?.finishIfRunning()
        }
    }
}

/** Types [command] and Enter into the shell. Queued (and the shell started) when it is not up yet. */
fun MainViewModel.runTerminalCommand(command: String) = runTerminalCommand(command, null)

fun MainViewModel.runTerminalCommand(command: String, chatId: String?) = queueTerminalInput(command, inProject = false, chatId = chatId)

internal fun MainViewModel.queueTerminalInput(command: String, inProject: Boolean, chatId: String? = null) {
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
fun MainViewModel.editInTerminal(relativePath: String) {
    val file = shellSingleQuote(relativePath)
    runTerminalCommandInProject("if command -v nano >/dev/null 2>&1; then nano -- $file; else vi -- $file; fi")
    selectTab(ForgeTab.TERMINAL)
}

/** Files row menu: picks [relativePath] as the target of the REA screen and opens it. */
fun MainViewModel.analyzeWithRea(relativePath: String) {
    rea.selectTarget(relativePath)
    selectTab(ForgeTab.REA)
}

/** Runs [command] in the active project's folder in the general terminal. */
fun MainViewModel.runTerminalCommandInProject(command: String) = queueTerminalInput(command, inProject = true)

/** "Run in terminal" from chat: runs [command] in the terminal of the chat on screen, which starts in that chat's folder. */
fun MainViewModel.runInChatTerminal(command: String) {
    val chatId = _state.value.activeChatId
    queueTerminalInput(command, inProject = false, chatId = chatId)
    selectProjectPane(ProjectPane.TERMINAL)
}

/** Sends queued input once the emulator behind the session exists (it is created on first layout). */
fun MainViewModel.flushTerminalInput() = flushTerminalInput(null)

fun MainViewModel.flushTerminalInput(chatId: String?) {
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

fun MainViewModel.clearTerminal() {
    terminals.values.forEach { it.session?.let(::wipeTerminalScreen) }
    _state.update { it.copy(bannerMessage = UiText.res(R.string.terminal_clear_terminal)) }
}

fun MainViewModel.clearTerminal(chatId: String?) {
    terminalHandle(chatId).session?.let(::wipeTerminalScreen)
}

/**
 * Clears the visible screen and the scrollback. TerminalSession.reset() only restores modes and colours and leaves the
 * screen buffer as it was (the menu item looked dead), so the clear sequence is fed to the emulator directly, then Ctrl+L
 * makes an idle shell redraw its prompt at the top.
 */
private fun wipeTerminalScreen(session: TerminalSession) {
    val emulator = session.emulator ?: return
    val clear = "\u001b[3J\u001b[2J\u001b[H".toByteArray(Charsets.US_ASCII)
    emulator.append(clear, clear.size)
    session.write("\u000c")
}

internal fun MainViewModel.onTerminalSessionEnded(session: TerminalSession) {
    val handle = terminals.values.firstOrNull { it.session === session } ?: return
    handle.finished = true
    handle.pending.clear()
    _state.update { it.copy(terminalRevision = it.terminalRevision + 1) }
}


/** Blocking (file IO, root probe): call off the main thread. */
internal fun MainViewModel.buildTerminalLaunchSpec(handle: TerminalHandle): TerminalLaunchSpec {
    val app = getApplication<Application>()
    val env = System.getenv().toMutableMap().apply {
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("LANG", "C.UTF-8")
    }
    val runtimeAvailable = installer.isInstalled() && installer.isRuntimeAccessAvailable() && installer.installedRuntime().chrootExec.isFile
    return if (runtimeAvailable) {
        // The guest environment (PATH, HOME, TERM, provider keys) travels in a one-shot env file sourced by su / sh.
        // /root is the runtime's home, so the shell is not tied to any project.
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
        val argv = installer.launchArgv(suCommand)
        TerminalLaunchSpec(argv.first(), app.filesDir.absolutePath, argv.toTypedArray(), env.toEnvArray(), inChroot = true)
    } else {
        val folder = handle.chatId?.let { chatTerminalFolder(it) }
        handle.hostDir = folder?.first
        val home = folder?.first?.absolutePath ?: app.filesDir.absolutePath
        env["HOME"] = app.filesDir.absolutePath
        TerminalLaunchSpec("/system/bin/sh", home, arrayOf("sh"), env.toEnvArray(), inChroot = false)
    }
}

/** Folder of [chatId] on the host and its name under /workspace: the branch clone of an isolated chat, else the project. */
internal fun MainViewModel.chatTerminalFolder(chatId: String): Pair<File, String>? {
    val project = _state.value.activeProject ?: return null
    val chat = _state.value.chats.firstOrNull { it.id == chatId }
    if (chat?.isolated == true) {
        val branch = chatBranches.branchFor(project, chatId)
        if (branch.cloneDir.isDirectory) return branch.cloneDir to branch.slug
    }
    return checkpoints.ensureWorkspace(project.id) to project.slug
}

internal fun Map<String, String>.toEnvArray(): Array<String> = map { "${it.key}=${it.value}" }.toTypedArray()

internal fun MainViewModel.activeChrootEnvironment(): Map<String, String> {
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


internal fun MainViewModel.stopChrootProcess(process: Process?) {
    if (process == null) return
    val files = chrootProcessFiles[process]
    if (files != null) {
        com.nextcraft.forge.runtime.GuestProcess.signal(files.second, 15, forceAfterSeconds = 3)
    }
    process.destroyForcibly()
}

internal fun MainViewModel.cleanupChrootProcess(process: Process?) {
    if (process == null) return
    val files = chrootProcessFiles.remove(process) ?: return
    files.first.delete()
    files.second.delete()
}

/** Starts [command] inside the Root Chroot (Gradle, git and the SDK only exist there). */
internal fun MainViewModel.startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process {
    check(installer.isInstalled() && installer.isRuntimeAccessAvailable()) {
        "Root access (or the PRoot engine) and the installed Linux runtime are required to run this."
    }
    val (envFile, suCommand) = installer.prepareChrootShellCommand(
        workspace = workspaceDir,
        guestWorkspacePath = "/workspace/$projectSlug",
        environment = activeChrootEnvironment(),
        guestCommand = listOf("/bin/bash", "-lc", command),
    )
    val process = try {
        ProcessBuilder(installer.launchArgv(suCommand)).redirectErrorStream(true).start()
    } catch (error: Throwable) {
        envFile.delete()
        throw error
    }
    chrootProcessFiles[process] = envFile to com.nextcraft.forge.runtime.GuestProcess.pidFileFor(envFile)
    return process
}

internal fun MainViewModel.runCommandInChrootOrHost(
    projectSlug: String,
    workspaceDir: File,
    command: String,
    onOutputChunk: ((String) -> Unit)? = null,
): String {
    return runCatching {
        val process = if (installer.isInstalled() && installer.isRuntimeAccessAvailable()) {
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
                while (buffer.length < MainViewModel.MAX_TERMINAL_CHARS) {
                    val n = reader.read(chunk)
                    if (n <= 0) break
                    val text = String(chunk, 0, n)
                    if (text.contains(com.nextcraft.forge.runtime.ChatBranches.WAIT_TOKEN)) {
                        notifyGitWaitingInQueue()
                    }
                    val cleaned = com.nextcraft.forge.runtime.ChatBranches.stripWaitTokens(text)
                    buffer.append(cleaned)
                    onOutputChunk?.invoke(cleaned)
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
internal fun MainViewModel.runCheckedCommand(projectSlug: String, workspaceDir: File, command: String): String {
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

