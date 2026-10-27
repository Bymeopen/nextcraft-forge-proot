@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NextCraftForgeApp(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    CrashReportPrompt()

    state.unsavedChangesPrompt?.let { path ->
        ForgeAlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = viewModel::cancelLeavingFile,
            title = { Text(stringResource(R.string.files_unsaved_title)) },
            text = { Text(stringResource(R.string.files_unsaved_message, path)) },
            confirmButton = {
                Button(onClick = viewModel::saveAndContinue) { Text(stringResource(R.string.files_unsaved_save)) }
            },
            dismissButton = {
                Row {
                    ForgeTextButton(onClick = viewModel::discardAndContinue) { Text(stringResource(R.string.files_unsaved_discard)) }
                    ForgeTextButton(onClick = viewModel::cancelLeavingFile) { Text(stringResource(R.string.agent_cancel)) }
                }
            },
        )
    }

    var bypassSetupGate by rememberSaveable { mutableStateOf(false) }
    var showNewProjectDialog by rememberSaveable { mutableStateOf(false) }
    var paletteOpen by rememberSaveable { mutableStateOf(false) }
    var showGitHubDialog by rememberSaveable { mutableStateOf(false) }
    var showFigmaDialog by rememberSaveable { mutableStateOf(false) }
    if (paletteOpen) ForgeCommandPalette(onDismiss = { paletteOpen = false }, onOpen = viewModel::selectTab)

    if ((!state.runtimeInstalled && !bypassSetupGate) || !state.ramSupported || !state.androidVersionSupported) {
        RuntimeOnboardingScreen(
            state = state,
            onToggleStack = viewModel::toggleDevStack,
            onStartSetup = viewModel::startRuntimeSetup,
            onPauseSetup = viewModel::pauseRuntimeSetup,
            onRepairRootfs = viewModel::repairRootfs,
            onRefreshStatus = viewModel::refreshRuntimeStatus,
            onEnterWorkspaceAnyway = { bypassSetupGate = true },
        )
        return
    }

    val imeVisible by rememberImeVisible()
    val backTarget = forgeBackTarget(state.activeTab)
    val insideProject = state.activeTab == ForgeTab.PROJECTS && state.projectView != ProjectView.LIST
    // Tablets, foldables and landscape: a project shows its chat and one workspace pane side by side.
    val expanded = rememberIsExpanded()
    val splitPane = expanded && insideProject
    var lastSidePane by rememberSaveable { mutableStateOf(ProjectPane.FILES.name) }
    SideEffect { if (state.projectPane != ProjectPane.CHAT) lastSidePane = state.projectPane.name }
    val sidePane = if (state.projectPane != ProjectPane.CHAT) state.projectPane else ProjectPane.valueOf(lastSidePane)
    LaunchedEffect(splitPane, sidePane, state.activeProjectId) {
        if (splitPane && state.projectPane == ProjectPane.CHAT) viewModel.onSidePaneShown(sidePane)
    }
    // Inside a project, Back walks conversation → chats → list before the tab rules apply.
    BackHandler(enabled = backTarget != null || insideProject) {
        if (!viewModel.projectBack(paneBesideChat = splitPane)) backTarget?.let(viewModel::selectTab)
    }

    // Undo Snackbar for project and chat deletion: the files stay on disk until the VM's undo window closes.
    val snackbarHostState = remember { SnackbarHostState() }
    val undoDelete = state.undoDelete
    val deletedMessage = undoDelete?.let { stringResource(R.string.msg_project_deleted, it.name) }
    val undoChatDelete = state.undoChatDelete
    val deletedChatMessage = undoChatDelete?.let { stringResource(R.string.msg_project_deleted, it.title) }
    val undoLabel = stringResource(R.string.action_undo)
    LaunchedEffect(undoDelete) {
        if (undoDelete == null || deletedMessage == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteProject() else viewModel.clearUndoPrompt()
    }
    LaunchedEffect(undoChatDelete) {
        if (undoChatDelete == null || deletedChatMessage == null) return@LaunchedEffect
        val result = snackbarHostState.showSnackbar(
            message = deletedChatMessage,
            actionLabel = undoLabel,
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed) viewModel.undoDeleteChat() else viewModel.clearChatUndoPrompt()
    }

    // targetSdk 35+ is edge-to-edge: adjustResize no longer resizes the window, so the IME, status and
    // navigation bars are handled here. imePadding lifts the composer/terminal line above the keyboard.
    // While an agent works the chat redraws often; recording and blurring the whole screen for the floating bar on
    // every one of those frames cost main-thread time, so the bar uses its solid colour until the turn ends.
    val glassBackdrop = rememberForgeBackdrop(enabled = LocalForgeThemeTokens.current.isGlass && !state.sessionRunning)
    CompositionLocalProvider(LocalForgeBackdrop provides glassBackdrop) {
    Scaffold(
        modifier = Modifier.fillMaxSize().imePadding(),
        containerColor = Color.Transparent,
        // System bars plus the display cutout: in landscape the notch sits at the side, not the top.
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {},
        bottomBar = {
            // adjustResize lifts the bar above the keyboard and eats the composer's space; hide it while typing.
            val reduceMotion = rememberReduceMotion()
            androidx.compose.animation.AnimatedVisibility(
                visible = !imeVisible,
                enter = ForgeMotion.expandEnter(reduceMotion),
                exit = ForgeMotion.shrinkExit(reduceMotion),
            ) {
                val inProject = state.activeTab == ForgeTab.PROJECTS && state.projectView != ProjectView.LIST
                if (inProject) {
                    // Inside a project the bar belongs to the project: Chat · Files · Terminal · Preview.
                    ForgeBottomBar(
                        // Two-pane: the chat is always visible, so the bar only switches the workspace pane.
                        entries = if (splitPane) projectEntries.drop(1) else projectEntries,
                        selectedTab = when (if (splitPane) sidePane else state.projectPane) {
                            ProjectPane.CHAT -> ForgeTab.AGENT
                            ProjectPane.FILES -> ForgeTab.FILES
                            ProjectPane.TERMINAL -> ForgeTab.TERMINAL
                            ProjectPane.PREVIEW -> ForgeTab.PREVIEW
                        },
                        changedCount = state.changedItems.count { it.accepted == null },
                        agentRunning = state.runningChatIds.isNotEmpty(),
                        terminalBusy = state.terminalRunning,
                        onSelectTab = { tab ->
                            viewModel.selectProjectPane(
                                when (tab) {
                                    ForgeTab.FILES -> ProjectPane.FILES
                                    ForgeTab.TERMINAL -> ProjectPane.TERMINAL
                                    ForgeTab.PREVIEW -> ProjectPane.PREVIEW
                                    else -> ProjectPane.CHAT
                                },
                            )
                        },
                    )
                } else {
                    ForgeBottomBar(
                        entries = navEntries,
                        selectedTab = state.activeTab,
                        changedCount = state.changedItems.count { it.accepted == null },
                        agentRunning = state.sessionRunning,
                        terminalBusy = state.terminalRunning,
                        onSelectTab = viewModel::selectTab,
                        onLongPressTab = { paletteOpen = true },
                    )
                }
            }
        },
    ) { innerPadding ->
        // The Glass bar blurs whatever is drawn in this layer; the canvas is repeated inside it so the layer is opaque.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .forgeGlassSource(glassBackdrop)
                .then(if (glassBackdrop != null) Modifier.background(LocalForgeThemeTokens.current.canvasBrush) else Modifier),
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // innerPadding already carries the status bar and cutout (contentWindowInsets); adding
                // statusBarsPadding() here doubled the top gap.
                .padding(innerPadding),
        ) {
            // Plain confirmations ("all agents are up to date") clear themselves so they do not follow people from
            // screen to screen. Errors and notices that offer an action stay until read, acted on or dismissed.
            val bannerKey = state.bannerMessage
            val bannerClears = state.bannerAction == null && (bannerKey as? UiText.Res)?.id in transientBanners
            LaunchedEffect(bannerKey, bannerClears) {
                if (bannerKey != null && bannerClears) {
                    kotlinx.coroutines.delay(8_000)
                    viewModel.dismissBanner()
                }
            }
            val reduceMotion = rememberReduceMotion()
            val bannerText = state.bannerMessage?.asString()
            var lastBannerText by remember { mutableStateOf("") }
            if (bannerText != null) lastBannerText = bannerText
            androidx.compose.animation.AnimatedVisibility(
                visible = bannerText != null,
                enter = ForgeMotion.expandEnter(reduceMotion),
                exit = ForgeMotion.shrinkExit(reduceMotion),
            ) {
                val banner = bannerText ?: lastBannerText
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.fillMaxWidth().forgeLive(),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Default.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.weight(1f).padding(vertical = 6.dp),
                            )
                            val bannerAction = state.bannerAction?.takeIf { it.forMessage == state.bannerMessage }
                            if (bannerAction != null) {
                                TextButton(
                                    onClick = {
                                        if (bannerAction.kind == BannerActionKind.SET_UP_RUNTIME) {
                                            bypassSetupGate = false
                                            viewModel.dismissBanner()
                                        } else {
                                            viewModel.runBannerAction(bannerAction.kind)
                                        }
                                    },
                                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer),
                                ) {
                                    Text(stringResource(bannerAction.kind.label), fontWeight = FontWeight.SemiBold)
                                }
                            }
                            IconButton(
                                onClick = viewModel::dismissBanner,
                                modifier = Modifier.size(ForgeSpace.minTouch),
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.shell_dismiss),
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }

            LiveActivityHud(
                state = state,
                onSelectRun = viewModel::openProjectChat,
                onSelectBuild = viewModel::openProjectBuild,
                onStopRun = viewModel::stopProjectChatRun,
                onStopBuild = viewModel::stopAndroidBuild,
            )

            // Screen host: inside Projects, going deeper (list → chats → conversation) or into a Tool sub-screen pushes
            // from the right and Back pops; pane and sibling tab switches slide/drift smoothly left/right.
            val depth = when {
                state.activeTab == ForgeTab.PROJECTS -> when (state.projectView) {
                    ProjectView.LIST -> 0
                    ProjectView.CHATS -> 1
                    ProjectView.CONVERSATION -> 2
                }
                state.activeTab in toolsGroup && state.activeTab != ForgeTab.TOOLS -> 1
                else -> 0
            }
            val paneOrder = state.projectPane.ordinal
            val tabNavIndex = state.activeTab.navIndex()
            var lastDepth by remember { mutableStateOf(depth) }
            var lastPaneOrder by remember { mutableStateOf(paneOrder) }
            var lastTabNavIndex by remember { mutableStateOf(tabNavIndex) }
            var lastActiveTab by remember { mutableStateOf(state.activeTab) }
            val tabGroupChanged = tabNavIndex != lastTabNavIndex
            val depthChanged = !tabGroupChanged && depth != lastDepth
            val paneChanged = !splitPane && state.activeTab == ForgeTab.PROJECTS && state.projectView != ProjectView.LIST && paneOrder != lastPaneOrder
            val toolDrillChanged = (state.activeTab in toolsGroup && lastActiveTab in toolsGroup) && state.activeTab != lastActiveTab
            val forward = when {
                tabGroupChanged -> tabNavIndex >= lastTabNavIndex
                depthChanged -> depth >= lastDepth
                paneChanged -> paneOrder >= lastPaneOrder
                else -> state.activeTab.ordinal >= lastActiveTab.ordinal
            }
            val slide = (state.activeTab == ForgeTab.PROJECTS && (depthChanged || paneChanged)) || toolDrillChanged
            androidx.compose.runtime.SideEffect {
                lastDepth = depth
                lastPaneOrder = paneOrder
                lastTabNavIndex = tabNavIndex
                lastActiveTab = state.activeTab
            }
            // Single-pane screens stop at a readable width on wide displays; workspace screens use all of it.
            val capWidth = expanded && !splitPane && state.activeTab !in fullWidthTabs
            val canSwipePanes = !splitPane && state.activeTab == ForgeTab.PROJECTS && state.projectView != ProjectView.LIST
            val density = androidx.compose.ui.platform.LocalDensity.current
            val panes = ProjectPane.entries
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(
                        if (canSwipePanes) {
                            Modifier.pointerInput(state.projectPane) {
                                val edgePx = with(density) { 44.dp.toPx() }
                                val thresholdPx = with(density) { 64.dp.toPx() }
                                val headerPx = with(density) { 68.dp.toPx() }
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val nearLeftEdge = down.position.x <= edgePx
                                    val nearRightEdge = down.position.x >= size.width - edgePx
                                    val nearTopHeader = down.position.y <= headerPx
                                    if (!nearLeftEdge && !nearRightEdge && !nearTopHeader) return@awaitEachGesture
                                    var totalDx = 0f
                                    var totalDy = 0f
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        totalDx += change.position.x - change.previousPosition.x
                                        totalDy += change.position.y - change.previousPosition.y
                                    }
                                    if (kotlin.math.abs(totalDx) > thresholdPx && kotlin.math.abs(totalDx) > kotlin.math.abs(totalDy) * 1.4f) {
                                        val curIdx = panes.indexOf(state.projectPane)
                                        val nextIdx = if (totalDx < 0f) curIdx + 1 else curIdx - 1
                                        if (nextIdx in panes.indices) {
                                            viewModel.selectProjectPane(panes[nextIdx])
                                        }
                                    }
                                }
                            }
                        } else Modifier,
                    ),
                contentAlignment = Alignment.TopCenter,
            ) {
            ForgeScreenHost(
                key = Triple(state.activeTab, state.projectView, if (splitPane) ProjectPane.CHAT else state.projectPane),
                forward = forward,
                slide = slide,
                modifier = Modifier.widthIn(max = if (capWidth) ForgeBreakpoint.readableMaxWidth else Dp.Infinity).fillMaxSize(),
            ) { (tab, view, pane) ->
                when (tab) {
                    ForgeTab.PROJECTS -> when (view) {
                        ProjectView.LIST -> ProjectsScreen(
                            state = state,
                            onOpenProject = viewModel::openProject,
                            onNewProject = { showNewProjectDialog = true },
                            onQuickProject = viewModel::createQuickProject,
                            onImportZip = viewModel::importZipProject,
                            onCloneGitUrl = viewModel::cloneGitUrl,
                            onConnectGitHub = { showGitHubDialog = true },
                            onConnectFigma = { showFigmaDialog = true },
                            onDeleteProject = viewModel::deleteProject,
                            onRenameProject = viewModel::renameProject,
                            onNewQuickProject = viewModel::createQuickProject,
                            onOpenUpdate = { viewModel.selectTab(ForgeTab.SETTINGS) },
                        )
                        ProjectView.CHATS, ProjectView.CONVERSATION -> if (splitPane) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    if (view == ProjectView.CHATS) {
                                        ProjectChatsScreen(state = state, viewModel = viewModel)
                                    } else {
                                        AgentScreen(state = state, viewModel = viewModel)
                                    }
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .fillMaxHeight()
                                        .background(MaterialTheme.colorScheme.outlineVariant),
                                )
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    when (sidePane) {
                                        ProjectPane.TERMINAL -> TerminalScreen(state = state, viewModel = viewModel, chatId = state.activeChatId)
                                        ProjectPane.PREVIEW -> WebPreviewScreen(state = state, viewModel = viewModel)
                                        else -> FilesAndDiffScreen(state = state, viewModel = viewModel)
                                    }
                                }
                            }
                        } else when (pane) {
                            ProjectPane.CHAT -> if (view == ProjectView.CHATS) {
                                ProjectChatsScreen(state = state, viewModel = viewModel)
                            } else {
                                AgentScreen(state = state, viewModel = viewModel)
                            }
                            ProjectPane.FILES -> FilesAndDiffScreen(state = state, viewModel = viewModel)
                            ProjectPane.TERMINAL -> TerminalScreen(state = state, viewModel = viewModel, chatId = state.activeChatId)
                            ProjectPane.PREVIEW -> WebPreviewScreen(state = state, viewModel = viewModel)
                        }
                    }
                    ForgeTab.AGENT -> AgentConfigScreen(state = state, viewModel = viewModel)
                    ForgeTab.TOOLS -> ToolsScreen(onOpen = viewModel::selectTab)
                    ForgeTab.FILES -> FileManagerScreen(state = state, viewModel = viewModel)
                    ForgeTab.TERMINAL -> TerminalScreen(state = state, viewModel = viewModel)
                    ForgeTab.ANDROID -> AndroidBuildScreen(state = state, viewModel = viewModel)
                    ForgeTab.PREVIEW -> WebPreviewScreen(state = state, viewModel = viewModel)
                    ForgeTab.SETTINGS -> SettingsScreenModern(state = state, viewModel = viewModel)
                    ForgeTab.TOOLCHAINS -> ToolchainsScreen(state = state, viewModel = viewModel)
                    ForgeTab.DIAGNOSTICS -> DiagnosticsScreen(viewModel = viewModel)
                    ForgeTab.BUILD_MATRIX -> BuildMatrixScreen(state = state, viewModel = viewModel)
                    ForgeTab.SSH -> SshScreen(viewModel = viewModel)
                    ForgeTab.CICD -> CiCdScreen(viewModel = viewModel)
                    ForgeTab.ROM -> RomStudioScreen(viewModel = viewModel)
                    ForgeTab.CHECKPOINTS -> CheckpointsScreen(state = state, viewModel = viewModel)
                    ForgeTab.CODE_VIEWER -> CodeViewerScreen(state = state, viewModel = viewModel)
                    ForgeTab.FILE_BROWSER -> FileBrowserScreen(viewModel = viewModel)
                    ForgeTab.BACKUP -> BackupScreen(state = state, viewModel = viewModel)
                    ForgeTab.REA -> ReaScreen(state = state, viewModel = viewModel)
                }
            }
            }
        }
        }
    }
    }

    if (showGitHubDialog) {
        ForgeAlertDialog(
            onDismissRequest = { showGitHubDialog = false },
            title = { Text(stringResource(R.string.settings_github)) },
            text = { GitHubSection(state, viewModel) },
            confirmButton = { ForgeTextButton(onClick = { showGitHubDialog = false }) { Text(stringResource(R.string.agent_close)) } },
        )
    }

    if (showFigmaDialog) {
        ForgeAlertDialog(
            onDismissRequest = { showFigmaDialog = false },
            title = { Text(stringResource(R.string.projects_connect_figma)) },
            text = { FigmaSection(state = state, viewModel = viewModel, onClose = { showFigmaDialog = false }) },
            confirmButton = { ForgeTextButton(onClick = { showFigmaDialog = false }) { Text(stringResource(R.string.agent_close)) } },
        )
    }

    if (showNewProjectDialog) {
        NewProjectDialog(
            gameTemplatesEnabled = state.gameTemplatesEnabled,
            onDismiss = { showNewProjectDialog = false },
            onCreate = { name, desc, isAndroid, template, starter ->
                viewModel.createProject(name, desc, isAndroid, template, starter)
                showNewProjectDialog = false
            },
        )
    }
}

/** Banners that only confirm something; everything else (errors, blocked actions, offers) stays on screen. */
private val transientBanners = setOf(
    R.string.msg_agents_up_to_date,
    R.string.msg_imported,
    R.string.msg_queue_discarded,
    R.string.msg_caches_cleared,
    R.string.msg_parallel_queued,
    R.string.branch_enabled,
    R.string.branch_auto,
    R.string.branch_merged,
    R.string.branch_updated,
    R.string.branch_committed,
    R.string.repo_committed,
    R.string.repo_branch_created,
    R.string.repo_branch_switched,
    R.string.repo_branch_deleted,
    R.string.connector_saved,
    R.string.skill_saved,
    R.string.schedule_added,
    R.string.schedule_cleared,
    R.string.rt_snap_saved,
    R.string.rt_snap_deleted,
)
