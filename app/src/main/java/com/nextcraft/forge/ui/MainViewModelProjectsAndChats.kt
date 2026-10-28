package com.nextcraft.forge.ui

import com.nextcraft.forge.ui.MainViewModel.PendingChatDelete
import com.nextcraft.forge.ui.MainViewModel.PendingDelete
import com.nextcraft.forge.ui.MainViewModel.Companion.UNDO_WINDOW_MS
import com.nextcraft.forge.runtime.*
import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.StarterTemplate
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.runtime.deleteRecursivelyNoFollow
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidLogcatState
import com.nextcraft.forge.runtime.AndroidProjectTemplateGenerator
import com.nextcraft.forge.runtime.GoProjectTemplateGenerator
import com.nextcraft.forge.runtime.GameProjectTemplateGenerator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nextcraft.forge.R

// Tab/agent selection plus project and chat lifecycle (create, select, delete, undo) for [MainViewModel].
fun MainViewModel.selectTab(tab: ForgeTab) {
    _state.update { it.copy(activeTab = tab) }
    if (tab == ForgeTab.FILES || tab == ForgeTab.ANDROID) {
        _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
    }
    if (tab == ForgeTab.PREVIEW) {
        scanLocalPreviewPorts()
    }
}

fun MainViewModel.selectAgent(agent: AgentKind) {
    // Switching bridges mid-run on the same chat would misroute pending tool approvals; other chats/projects can keep running.
    if (_state.value.activeChatRunning) {
        _state.update { it.copy(bannerMessage = UiText.res(R.string.msg_agent_switch_busy)) }
        return
    }
    preferences.agentKind = agent.stableId
    applyAgent(agent)
    // The choice belongs to this chat only: other chats keep their own agent when they are opened.
    if (_state.value.activeProjectId.isNotBlank()) {
        preferences.saveChatAgent(_state.value.activeProjectId, _state.value.activeChatId, agent)
    }
}

internal fun MainViewModel.applyAgent(agent: AgentKind) {
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

/**
 * Shows the agent and routing mode of chat [chatId]. A chat that never saved its own settings is pinned to the
 * current global defaults, so changing another chat later can no longer change it.
 */
internal fun MainViewModel.applyChatSettings(projectId: String, chatId: String) {
    if (projectId.isBlank()) return
    val routing = preferences.loadChatRouting(projectId, chatId)
        ?: preferences.routing.also { preferences.saveChatRouting(projectId, chatId, it) }
    val agent = preferences.loadChatAgent(projectId, chatId)
        ?: (AgentKind.fromStoredOrNull(preferences.agentKind) ?: _state.value.agentKind)
            .also { preferences.saveChatAgent(projectId, chatId, it) }
    if (agent != _state.value.agentKind) applyAgent(agent)
    _state.update { it.copy(routing = routing) }
    if (routing.mode != RouteMode.SINGLE) refreshReadyAgents()
}

fun MainViewModel.selectProject(projectId: String) {
    val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
    val switchingProject = projectId != _state.value.activeProjectId
    // Re-selecting the open project must not reload chats from disk: that would replace the streaming message
    // of a running turn and wipe the build log, selected chat and open file.
    if (!switchingProject) return
    activeLogcatProcess?.destroyForcibly()
    activeLogcatProcess = null
    activeLogcatJob?.cancel()
    rea.clearAnalysis()
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
    applyChatSettings(project.id, chatId)
    if (chats.firstOrNull { it.id == chatId }?.isolated == true) refreshBranchStatus(chatId)
    if (existingProjectBuild == null || existingProjectBuild.phase != AndroidBuildPhase.BUILDING) {
        viewModelScope.launch {
            val buildRecord = withContext(Dispatchers.IO) {
                val existingApk = findExistingProjectApk(project, chatId)
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

fun MainViewModel.createProject(
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



/**
 * Picks a slug no project (nor a create/clone/import still in flight) uses. Two quick Create taps or clones of
 * the same repo would otherwise both see the slug as free and share one guest workspace path.
 */
internal fun MainViewModel.reserveProjectSlug(baseSlug: String): String = synchronized(reservedSlugs) {
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
internal fun MainViewModel.prependProject(project: Project): List<Project> {
    var updated: List<Project> = emptyList()
    _state.update { current ->
        updated = listOf(project) + current.projects
        current.copy(projects = updated)
    }
    preferences.saveProjects(updated)
    return updated
}

fun MainViewModel.deleteProject(projectId: String) {
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
    if (pendingChatDelete?.project?.id == removed.id) {
        finalizePendingChatDelete()
    }
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
            rea.clearAnalysis()
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
fun MainViewModel.undoDeleteProject() {
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
fun MainViewModel.clearUndoPrompt() {
    _state.update { it.copy(undoDelete = null) }
}

/** Wipes the files of the project waiting in the undo window. Safe to call when nothing is pending. */
internal fun MainViewModel.finalizePendingDelete() {
    val pending = pendingDelete ?: return
    pendingDelete = null
    pendingDeleteJob?.cancel()
    val project = pending.project
    synchronized(reservedSlugs) { reservedSlugs -= project.slug }
    dropScheduledTasks { it.projectId == project.id }
    _state.update { it.copy(undoDelete = null) }
    viewModelScope.launch(Dispatchers.IO) {
        wipeProjectData(project)
        preferences.pendingProjectWipe = null
    }
}

/** Blocking: removes chats, workspace and checkpoints of [project]. Call off the main thread. */
internal fun MainViewModel.wipeProjectData(project: Project) = wipeProjectData(project.id)

internal fun MainViewModel.wipeProjectData(projectId: String) {
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
        val workspace = File(getApplication<Application>().filesDir, "workspaces/${projectId}")
        // The root fallback runs `rm -rf` as root: never let a blank or path-like id widen it to the parent.
        if (projectId.isNotBlank() && '/' !in projectId && !projectId.startsWith(".")) {
            workspace.deleteRecursivelyNoFollowOrRoot()
        } else {
            workspace.deleteRecursivelyNoFollow()
        }
        checkpoints.checkpointDir(projectId).deleteRecursively()
        File(getApplication<Application>().filesDir, "snapshots/${projectId}").deleteRecursively()
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
}


/** Renames the display name only; the slug (the /workspace/<slug> path agents and chats are bound to) stays. */
fun MainViewModel.renameProject(projectId: String, newName: String) {
    val cleanName = newName.trim().take(80)
    if (cleanName.isEmpty()) return
    val current = _state.value.projects
    val target = current.firstOrNull { it.id == projectId } ?: return
    if (target.name == cleanName) return
    val updated = current.map { if (it.id == projectId) it.copy(name = cleanName) else it }
    preferences.saveProjects(updated)
    _state.update { it.copy(projects = updated) }
}

fun MainViewModel.selectChat(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (_state.value.chats.none { it.id == chatId }) return
    if (chatId != _state.value.activeChatId) {
        leaveOpenFile { selectChatInternal(project, chatId) }
    } else {
        selectChatInternal(project, chatId)
    }
}

internal fun MainViewModel.selectChatInternal(project: Project, chatId: String) {
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
    applyChatSettings(project.id, chatId)
    // A long chat's JSON is megabytes: parse it off the main thread, then show it if the chat is still open.
    if (run == null) loadChatMessagesAsync(project.id, chatId)
    if (_state.value.chats.firstOrNull { it.id == chatId }?.isolated == true) refreshBranchStatus(chatId)
    if (_state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
        viewModelScope.launch {
            val existingApk = withContext(Dispatchers.IO) { findExistingProjectApk(project, chatId) }
            if (_state.value.activeProjectId == project.id && _state.value.activeChatId == chatId && _state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
                val buildRecord = if (existingApk != null) {
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

/** Chat whose saved messages are still being read; until then the state holds no messages for it. */
@Volatile internal var loadingChatId: String? = null

internal fun MainViewModel.loadChatMessagesAsync(projectId: String, chatId: String) {
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
internal fun MainViewModel.currentMessagesOf(projectId: String, chatId: String): List<ChatMessage> =
    if (projectId == _state.value.activeProjectId && chatId == _state.value.activeChatId && loadingChatId != chatId) _state.value.messages
    else preferences.loadMessages(projectId, chatId)

fun MainViewModel.createChat(title: String = "") {
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
    applyChatSettings(project.id, chat.id)
}

fun MainViewModel.deleteChat(chatId: String) {
    deleteChatNow(chatId)
}

/** True when the chat's clone exists and either has work not in the project or its state cannot be read. */
internal suspend fun MainViewModel.branchHoldsUnmergedWork(project: Project, chatId: String): Boolean {
    val cloneExists = File(chatBranches.branchFor(project, chatId).cloneDir, ".git").exists()
    if (!cloneExists) return false
    val status = runCatching { chatBranches.status(project, chatId) }.getOrNull() ?: return true
    return status.commits > 0 || status.uncommitted > 0 || status.conflicts.isNotEmpty()
}

internal fun MainViewModel.deleteChatNow(chatId: String) {
    val project = _state.value.activeProject ?: return
    if (runs.containsKey(runKey(project.id, chatId))) return
    val currentChats = _state.value.chats
    if (currentChats.size <= 1) return
    val index = currentChats.indexOfFirst { it.id == chatId }
    if (index < 0) return
    val removed = currentChats[index]
    val updatedChats = currentChats.filterNot { it.id == chatId }
    // Its shell may sit in the branch folder that is about to be removed.
    closeChatTerminal(chatId)
    // A second chat delete inside the undo window makes the first one final.
    finalizePendingChatDelete()
    preferences.saveProjectChats(project.id, updatedChats)
    pendingChatDelete = PendingChatDelete(project, removed, index)
    pendingChatDeleteJob = viewModelScope.launch {
        delay(UNDO_WINDOW_MS)
        finalizePendingChatDelete()
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
            retryPrompts = it.retryPrompts - chatId,
            undoChatDelete = ChatUndo(project.id, removed.id, removed.title),
        )
    }
    applyChatSettings(project.id, nextChatId)
}

/** Restores the chat removed by [deleteChat] if its undo window is still open. */
fun MainViewModel.undoDeleteChat() {
    val pending = pendingChatDelete ?: return
    pendingChatDeleteJob?.cancel()
    pendingChatDelete = null
    val projectId = pending.project.id
    val currentChats = if (_state.value.activeProjectId == projectId) {
        _state.value.chats
    } else {
        preferences.loadProjectChats(projectId)
    }
    if (currentChats.any { it.id == pending.chat.id }) {
        _state.update { it.copy(undoChatDelete = null) }
        return
    }
    val restored = currentChats.toMutableList().also {
        it.add(pending.index.coerceIn(0, it.size), pending.chat)
    }
    preferences.saveProjectChats(projectId, restored)
    if (_state.value.activeProjectId == projectId) {
        val messages = preferences.loadMessages(projectId, pending.chat.id)
        _state.update {
            it.copy(
                chats = restored,
                activeChatId = pending.chat.id,
                messages = messages,
                activeStatusLine = null,
                pendingToolRequest = null,
                undoChatDelete = null,
            )
        }
        applyChatSettings(projectId, pending.chat.id)
        if (pending.chat.isolated) refreshBranchStatus(pending.chat.id)
    } else {
        _state.update { it.copy(undoChatDelete = null) }
    }
}

/** The chat Undo Snackbar was dismissed or timed out on its own; the deletion stays pending until its window ends. */
fun MainViewModel.clearChatUndoPrompt() {
    _state.update { it.copy(undoChatDelete = null) }
}

/** Wipes the files and branch of the chat waiting in the undo window. Safe to call when nothing is pending. */
internal fun MainViewModel.finalizePendingChatDelete() {
    val pending = pendingChatDelete ?: return
    pendingChatDelete = null
    pendingChatDeleteJob?.cancel()
    val project = pending.project
    val chat = pending.chat
    dropScheduledTasks { it.projectId == project.id && it.chatId == chat.id }
    _state.update { it.copy(undoChatDelete = null) }
    if (chat.isolated) {
        viewModelScope.launch { runCatching { chatBranches.discard(project, chat.id) } }
    }
    viewModelScope.launch(Dispatchers.IO) {
        wipeChatData(project.id, chat.id)
    }
}

internal fun MainViewModel.wipeChatData(projectId: String, chatId: String) {
    runCatching {
        AgentKind.entries.forEach { agent ->
            preferences.saveAgentConversation(agent, projectId, chatId, null)
        }
        File(getApplication<Application>().filesDir, "chats/$projectId/$chatId.json").delete()
    }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("MainViewModel", it) }
}
