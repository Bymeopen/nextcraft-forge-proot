@file:OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.snap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.AntigravityAuthState
import com.nextcraft.forge.runtime.AntigravityAuthStatus
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem

@Composable
fun AgentScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var promptInput by rememberSaveable { mutableStateOf("") }
    // Coming back to a chat (leaving the app, another tab) recreates the list; open it at the newest message,
    // not at the first one.
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.messages.lastIndex.coerceAtLeast(0))
    val agyAuthState by viewModel.antigravityAuth.state.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalForgeThemeTokens.current
    // hasOfficialCredential() may spawn `su` and block for seconds; never call it during composition.
    val hasAgyCredential by produceState(
        initialValue = true,
        state.agentKind,
        agyAuthState.status,
    ) {
        value = if (state.agentKind == AgentKind.ANTIGRAVITY) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                viewModel.antigravityAuth.hasOfficialCredential()
            }
        } else {
            true
        }
    }

    // Every time the chat is opened (or another chat is picked) start at the newest message. rememberLazyListState
    // is saveable, so on return it restores the old position (often the top) and ignores its initial index;
    // this plain remember is reset on each entry and forces the jump once the messages are there.
    var openedAtEnd by remember(state.activeProjectId, state.activeChatId) { mutableStateOf(false) }
    LaunchedEffect(state.activeProjectId, state.activeChatId, state.messages.isNotEmpty()) {
        if (!openedAtEnd && state.messages.isNotEmpty()) {
            listState.scrollToItem(state.messages.lastIndex, Int.MAX_VALUE)
            openedAtEnd = true
        }
    }

    var previousMessageCount by remember(state.activeProjectId, state.activeChatId) { mutableStateOf(state.messages.size) }
    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) {
            val lastIdx = state.messages.lastIndex
            if (state.messages.size != previousMessageCount) {
                // A chat that just loaded jumps straight to its end; only new messages animate.
                val firstLoad = previousMessageCount == 0
                previousMessageCount = state.messages.size
                if (firstLoad) listState.scrollToItem(lastIdx, Int.MAX_VALUE) else listState.animateScrollToItem(lastIdx)
            } else {
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                if (lastVisible >= lastIdx - 1) {
                    listState.scrollToItem(lastIdx, Int.MAX_VALUE)
                }
            }
        } else {
            previousMessageCount = 0
        }
    }

    val drawerState = androidx.compose.material3.rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    var scheduleOpen by rememberSaveable { mutableStateOf(false) }
    var renameOpen by rememberSaveable { mutableStateOf(false) }
    var modelSheetOpen by rememberSaveable { mutableStateOf(false) }
    var routeSheetOpen by rememberSaveable { mutableStateOf(false) }
    var connectorsSheetOpen by rememberSaveable { mutableStateOf(false) }
    // The chip shows the model of the primary agent, which can differ from the selected one in the multi-agent modes.
    androidx.compose.runtime.LaunchedEffect(state.routing) { viewModel.refreshAgentProfiles(); viewModel.refreshReadyAgents() }
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.loadConnectors() }
    var branchSheetOpen by rememberSaveable { mutableStateOf(false) }
    androidx.activity.compose.BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    androidx.compose.material3.ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            androidx.compose.material3.ModalDrawerSheet {
                ChatDrawerContent(state = state, viewModel = viewModel, onClose = { scope.launch { drawerState.close() } })
            }
        },
    ) {
    Column(modifier = Modifier.fillMaxSize()) {
        ChatTopBar(
            state = state,
            onOpenDrawer = { scope.launch { drawerState.open() } },
            onOpenChanges = { viewModel.selectProjectPane(ProjectPane.FILES) },
            onRename = { renameOpen = true },
            onUsage = { usageOpen = true },
            onSchedule = { scheduleOpen = true },
            onClear = viewModel::clearActiveChat,
            onDelete = { viewModel.deleteChat(state.activeChatId) },
            onBranch = {
                viewModel.refreshBranchStatus(state.activeChatId)
                branchSheetOpen = true
            },
            onEnableBranch = { viewModel.enableChatBranch(state.activeChatId) },
            onOpenRea = { viewModel.selectTab(ForgeTab.REA) },
            onOpenMemory = viewModel::openProjectMemory,
        )

        if (state.agentKind == AgentKind.ANTIGRAVITY &&
            agyAuthState.status != AntigravityAuthStatus.SIGNED_IN &&
            !hasAgyCredential
        ) {
            AntigravityOAuthBanner(
                authState = agyAuthState,
                onStartLogin = { scope.launch { viewModel.antigravityAuth.beginLogin() } },
                onSubmitCode = { code -> runCatching { viewModel.antigravityAuth.submitCode(code) } },
                onCancel = { viewModel.antigravityAuth.cancel() },
            )
        }

        val reduceMotion = rememberReduceMotion()
        val statusText = state.activeStatusLine?.asString()
        var lastStatusText by remember { mutableStateOf("") }
        if (statusText != null) lastStatusText = statusText
        AnimatedVisibility(
            visible = statusText != null,
            enter = ForgeMotion.expandEnter(reduceMotion),
            exit = ForgeMotion.shrinkExit(reduceMotion),
        ) {
            val activeText = statusText ?: lastStatusText
            // A slim status line: no sliding bar (the spinner by the chat title shows that it is working), small text,
            // and a compact Stop (the composer's round button also stops).
            Surface(
                color = tokens.cardElevatedColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 12.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = activeText,
                            fontSize = ForgeType.caption,
                            color = tokens.accentText,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).padding(vertical = 4.dp),
                        )
                        if (state.activeChatRunning) {
                            ForgeTextButton(
                                onClick = viewModel::stopActiveTask,
                                modifier = Modifier.heightIn(min = 32.dp),
                            ) {
                                Text(stringResource(R.string.agent_stop), fontSize = ForgeType.caption)
                            }
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        state.pendingToolRequest?.let { req ->
            ToolApprovalSheet(
                request = req,
                onApprove = { viewModel.respondToToolRequest(true) },
                onReject = { viewModel.respondToToolRequest(false) },
            )
        }

        if (state.messages.isEmpty()) {
            // Empty chat: one quiet greeting from the agent, nothing else competing with the composer.
            Column(modifier = Modifier.weight(1f).fillMaxWidth().padding(12.dp)) {
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = tokens.cardContainerColor,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = tokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = stringResource(R.string.agent_greeting),
                        fontSize = ForgeType.rowTitle,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                    )
                }
            }
        } else {
            val pendingChanges = state.changedItems.filter { it.accepted == null }
            val lastAssistantId = state.messages.lastOrNull { !it.fromUser }?.id
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        Column(modifier = Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChatMessageCard(
                                message = message,
                                agentName = state.agentKind.displayTitle(),
                                onRunSnippet = { cmd ->
                                    viewModel.runInChatTerminal(cmd)
                                },
                            )
                            // A chat with its own branch shows what the branch holds instead of the project's review list.
                            val branchStatus = state.branchStatus[state.activeChatId]
                            if (message.id == lastAssistantId && !state.activeChatRunning && branchStatus != null &&
                                (branchStatus.commits > 0 || branchStatus.uncommitted > 0 || branchStatus.conflicts.isNotEmpty())
                            ) {
                                BranchSummaryCard(status = branchStatus, onOpen = { branchSheetOpen = true })
                            }
                            // Review right where the agent said it was done; the Files tab keeps the full diff view.
                            if (message.id == lastAssistantId && pendingChanges.isNotEmpty() && state.runningChatIds.isEmpty()) {
                                ChangedFilesCard(
                                    changes = pendingChanges,
                                    enabled = state.runningChatIds.isEmpty(),
                                    onOpenFile = { path ->
                                        viewModel.openWorkspaceFile(path)
                                        viewModel.selectProjectPane(ProjectPane.FILES)
                                    },
                                    onKeepFile = viewModel::keepSingleFile,
                                    onRevertFile = viewModel::revertSingleFile,
                                    onKeepAll = viewModel::keepAllChanges,
                                    onRevertAll = viewModel::revertAllChanges,
                                    onEditInTerminal = viewModel::editInTerminal,
                                    onAcceptAndCommit = viewModel::acceptAndCommitChanges,
                                    onRevertHunk = viewModel::revertDiffHunk,
                                )
                            }
                        }
                    }
                }
                // "Latest" pill: shown once the reader has scrolled up from the newest message.
                val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                val awayFromEnd = state.messages.size > 1 && lastVisible < state.messages.lastIndex
                // Count only what arrived after the reader scrolled away; remember(awayFromEnd) snapshots the size at that moment.
                val sizeWhenAway = remember(awayFromEnd) { state.messages.size }
                val newCount = (state.messages.size - sizeWhenAway).coerceAtLeast(0)
                val latestInteraction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                androidx.compose.animation.AnimatedVisibility(
                    visible = awayFromEnd,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    enter = fadeIn(tween(ForgeMotion.FADE_MS)) + androidx.compose.animation.scaleIn(animationSpec = ForgeMotion.springSnappy(), initialScale = 0.88f),
                    exit = fadeOut(tween(ForgeMotion.FADE_MS / 2)) + androidx.compose.animation.scaleOut(animationSpec = tween(ForgeMotion.FADE_MS / 2), targetScale = 0.88f),
                ) {
                    Surface(
                        shape = RoundedCornerShape(100),
                        // Opaque (composited over the page) and raised: the glass card colour is translucent and let the
                        // last message show through the pill.
                        color = tokens.cardElevatedColor.compositeOver(MaterialTheme.colorScheme.surface),
                        contentColor = tokens.accentText,
                        border = tokens.cardBorderStroke,
                        shadowElevation = 4.dp,
                        modifier = Modifier
                            .forgePressScale(latestInteraction, pressedScale = 0.94f, haptic = true)
                            .clip(RoundedCornerShape(100))
                            .clickable(interactionSource = latestInteraction, indication = androidx.compose.material3.ripple(), role = Role.Button) {
                                scope.launch { listState.animateScrollToItem(state.messages.lastIndex) }
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (newCount > 0) quantityStringResource(R.plurals.chat_new_messages, newCount, newCount) else stringResource(R.string.agent_latest),
                                fontWeight = FontWeight.SemiBold,
                                fontSize = ForgeType.control,
                                modifier = Modifier.forgeLive(),
                            )
                        }
                    }
                }
            }
        }

        // Composer: one capsule, attach + field + round send/stop button. The agent name is in the header.
        val running = state.activeChatRunning
        val canSend = promptInput.isNotBlank() || state.pendingAttachments.isNotEmpty()
        val attachPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) viewModel.attachFilesToPrompt(uris)
        }
        // Camera: the photo is written to a FileProvider URI in the app cache, then imported like any picked file.
        val cameraContext = LocalContext.current
        var cameraUri by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmCapture by remember { mutableStateOf(false) }
        if (confirmCapture) {
            val captureTarget = cameraContext.findWindow()
            ForgeAlertDialog(
                onDismissRequest = { confirmCapture = false },
                title = { Text(stringResource(R.string.capture_confirm_title)) },
                text = { Text(stringResource(R.string.capture_confirm_message)) },
                confirmButton = {
                    TextButton(onClick = { confirmCapture = false; captureTarget?.let(viewModel::captureAppScreens) }) {
                        Text(stringResource(R.string.capture_start))
                    }
                },
                dismissButton = { TextButton(onClick = { confirmCapture = false }) { Text(stringResource(R.string.agent_cancel)) } },
            )
        }
        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
            val uri = cameraUri?.let(android.net.Uri::parse)
            if (saved && uri != null) viewModel.attachFilesToPrompt(listOf(uri))
            cameraUri = null
        }
        fun launchCamera() {
            runCatching {
                val dir = java.io.File(cameraContext.cacheDir, "camera").apply { mkdirs() }
                val photo = java.io.File.createTempFile("IMG_${System.currentTimeMillis()}_", ".jpg", dir)
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    cameraContext,
                    "${cameraContext.packageName}.fileprovider",
                    photo,
                )
                cameraUri = uri.toString()
                cameraLauncher.launch(uri)
            }.onFailure { cameraUri = null }
        }
        val retry = state.retryPrompts[state.activeChatId]?.takeIf {
            !state.activeChatRunning && (it.prompt.projectId == null || it.prompt.projectId == state.activeProjectId)
        }
        AnimatedVisibility(
            visible = retry != null,
            enter = ForgeMotion.expandEnter(reduceMotion),
            exit = ForgeMotion.shrinkExit(reduceMotion),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).forgeLive(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.agent_turn_failed),
                    fontSize = ForgeType.secondary,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.weight(1f),
                )
                ForgeTextButton(onClick = viewModel::retryLastPrompt) { Text(stringResource(R.string.action_retry)) }
            }
        }
        val chatQueue = state.queuedPrompts.filter {
            it.chatId == state.activeChatId && (it.projectId == null || it.projectId == state.activeProjectId)
        }
        if (chatQueue.isNotEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    stringResource(R.string.agent_queued_title, chatQueue.size),
                    fontSize = ForgeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                chatQueue.forEach { queued ->
                    val label = queued.text.ifBlank { queued.attachments.joinToString { it.displayName } }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(modifier = Modifier.weight(1f, fill = false)) {
                            AttachmentChip(name = label, size = 0L, onRemove = { viewModel.removeQueuedPrompt(queued.id) }, showSize = false)
                        }
                        // Steer: stop the running turn and send this message now instead of waiting for the queue.
                        if (running) {
                            ForgeTextButton(onClick = { viewModel.steerQueuedPrompt(queued.id) }) {
                                Text(stringResource(R.string.agent_steer), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                if (running) {
                    Text(
                        stringResource(R.string.agent_steer_hint),
                        fontSize = ForgeType.caption,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (state.pendingAttachments.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.pendingAttachments.forEach { att ->
                    AttachmentChip(name = att.displayName, size = att.sizeBytes, onRemove = { viewModel.removePendingAttachment(att.id) })
                }
            }
        }
        if (!running && state.pendingToolRequest == null) {
            val chips = buildList {
                if (state.messages.isEmpty()) {
                    // Starter ideas fill the composer instead of sending, so the user can edit first.
                    add(QuickChip(stringResource(R.string.starter_explain), ForgeGlyph.SPARK) { promptInput = stringOf(R.string.starter_explain, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_fix), ForgeGlyph.BUILD) { promptInput = stringOf(R.string.starter_fix, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_feature), ForgeGlyph.PLUS) { promptInput = stringOf(R.string.starter_feature, cameraContext) })
                    add(QuickChip(stringResource(R.string.starter_tests), ForgeGlyph.CODE) { promptInput = stringOf(R.string.starter_tests, cameraContext) })
                } else {
                    val changed = state.changedItems.count { it.accepted == null }
                    if (changed > 0) {
                        add(QuickChip(quantityStringResource(R.plurals.chip_review_changes, changed, changed), ForgeGlyph.FILE) { viewModel.selectProjectPane(ProjectPane.FILES) })
                        add(QuickChip(stringResource(R.string.chip_review_2nd_agent), ForgeGlyph.SPARK) { viewModel.reviewChangesWithSecondAgent() })
                        add(QuickChip(stringResource(R.string.chip_explain_changes), ForgeGlyph.SPARK) { viewModel.sendPrompt(stringOf(R.string.prompt_explain_changes, cameraContext)) })
                    }
                    if (state.activeLocalPorts.isNotEmpty()) {
                        add(QuickChip(stringResource(R.string.chip_open_preview), ForgeGlyph.GLOBE) { viewModel.selectProjectPane(ProjectPane.PREVIEW) })
                    }
                    if (state.activeProject?.type == com.nextcraft.forge.model.ProjectType.ANDROID) {
                        add(QuickChip(stringResource(R.string.chip_build_run), ForgeGlyph.BUILD) { viewModel.selectTab(ForgeTab.ANDROID) })
                    } else {
                        add(QuickChip(stringResource(R.string.chip_run_tests), ForgeGlyph.CODE) { viewModel.sendPrompt(stringOf(R.string.prompt_run_tests, cameraContext)) })
                    }
                }
            }
            QuickActionRow(chips, modifier = Modifier.padding(top = 4.dp))
        }
        // Voice: words appear in the box while speaking; "stop", "clear" and a trailing "send now" act as commands.
        var voiceBase by remember { mutableStateOf("") }
        var handsFreeSend by rememberSaveable { mutableStateOf(false) }
        var imageDialogOpen by rememberSaveable { mutableStateOf(false) }
        fun joinVoice(base: String, spoken: String) = listOf(base.trim(), spoken.trim()).filter { it.isNotEmpty() }.joinToString(" ")
        val voice = rememberVoiceController(
            languageCode = state.languageCode,
            onPartial = { promptInput = joinVoice(voiceBase, it) },
            onFinal = { spoken ->
                // true = keep listening for the next sentence; commands that end the dictation return false.
                when (val command = VoiceCommands.parse(spoken)) {
                    VoiceCommand.Stop -> { promptInput = voiceBase; viewModel.stopActiveTask(); false }
                    VoiceCommand.Clear -> { promptInput = ""; voiceBase = ""; true }
                    is VoiceCommand.QuickSlash -> { promptInput = ""; voiceBase = ""; viewModel.sendPrompt(command.slash); false }
                    is VoiceCommand.Dictate -> {
                        val text = joinVoice(voiceBase, command.text)
                        if ((command.send || handsFreeSend) && (text.isNotEmpty() || state.pendingAttachments.isNotEmpty())) {
                            promptInput = ""
                            voiceBase = ""
                            viewModel.sendPrompt(text)
                            false
                        } else {
                            promptInput = text
                            voiceBase = text // the next sentence continues after this one
                            !command.send
                        }
                    }
                }
            },
        )
        ChatComposer(
            promptInput = promptInput,
            onPromptChange = { promptInput = it },
            agentName = primaryAgent(state).displayTitle(),
            running = running,
            canSend = canSend,
            captureEnabled = !state.capturingScreens,
            onAttachCamera = { launchCamera() },
            onAttachFile = { attachPicker.launch(arrayOf("*/*")) },
            onCapture = { confirmCapture = true },
            onSend = {
                val text = promptInput.trim()
                promptInput = ""
                viewModel.sendPrompt(text)
            },
            onStop = viewModel::stopActiveTask,
            modelChipLabel = chatModelChipLabel(state, primaryAgent(state)),
            onModelChip = { modelSheetOpen = true },
            routeChipLabel = stringResource(R.string.route_chip_label, routeModeLabel(state.routing.mode)),
            onRouteChip = { routeSheetOpen = true },
            onMic = {
                if (!voice.listening) voiceBase = promptInput
                voice.toggle()
            },
            listening = voice.listening,
            micLevel = voice.level,
            handsFreeSend = handsFreeSend,
            onToggleHandsFree = { handsFreeSend = !handsFreeSend },
            onGenerateImage = { imageDialogOpen = true },
            workspaceFiles = state.workspaceFiles,
            readyAgents = state.readyAgents,
            connectors = state.connectors,
            onOpenConnectors = { connectorsSheetOpen = true },
            onExecuteSlashCommand = { cmd -> viewModel.sendPrompt(cmd) },
            onSelectAgentTag = {
                if (state.routing.mode == com.nextcraft.forge.model.RouteMode.SINGLE) {
                    viewModel.setRouteMode(com.nextcraft.forge.model.RouteMode.MANUAL)
                }
            },
        )
        if (imageDialogOpen) ImageGenerationDialog(state = state, viewModel = viewModel, onDismiss = { imageDialogOpen = false })
    }
    }

    if (connectorsSheetOpen) ConnectorsSheet(state = state, viewModel = viewModel, onDismiss = { connectorsSheetOpen = false })
    if (usageOpen) ChatUsageSheet(state = state, onDismiss = { usageOpen = false })
    if (scheduleOpen) ScheduleDialog(state = state, viewModel = viewModel, onDismiss = { scheduleOpen = false })
    if (branchSheetOpen) BranchSheet(state = state, viewModel = viewModel, chatId = state.activeChatId, onDismiss = { branchSheetOpen = false })
    if (routeSheetOpen) {
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshReadyAgents() }
        ChatRoutingSheet(state = state, viewModel = viewModel, onDismiss = { routeSheetOpen = false })
    }
    if (modelSheetOpen) {
        androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.refreshAgentProfiles() }
    }
    if (modelSheetOpen) ChatModelSheet(state = state, viewModel = viewModel, onDismiss = { modelSheetOpen = false })
    if (renameOpen) {
        RenameChatDialog(
            initial = state.chats.firstOrNull { it.id == state.activeChatId }?.title.orEmpty(),
            onDismiss = { renameOpen = false },
            onSave = {
                viewModel.renameChat(state.activeChatId, it)
                renameOpen = false
            },
        )
    }
}

@Composable
private fun AntigravityOAuthBanner(
    authState: AntigravityAuthState,
    onStartLogin: () -> Unit,
    onSubmitCode: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    var verificationCode by rememberSaveable { mutableStateOf("") }
    val busy = authState.status == AntigravityAuthStatus.STARTING || authState.status == AntigravityAuthStatus.COMPLETING

    Surface(
        shape = RoundedCornerShape(18.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.agent_google_account), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
                    Text(
                        text = authState.message?.asString() ?: stringResource(R.string.agent_required_for_antigravity),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            authState.authorizationUrl?.let { url ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_open_google_sign_in),
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(url)) },
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.agent_copy_link)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(
                        kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                        value = verificationCode,
                        onValueChange = { verificationCode = it },
                        placeholder = { Text(stringResource(R.string.agent_paste_google_verification_code), fontSize = ForgeType.secondary) },
                        modifier = Modifier.weight(1f),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_verify),
                        enabled = verificationCode.isNotBlank(),
                        onClick = {
                            onSubmitCode(verificationCode)
                            verificationCode = ""
                        },
                    )
                }
            }
            when {
                authState.status == AntigravityAuthStatus.SIGNED_OUT || authState.status == AntigravityAuthStatus.ERROR ->
                    ForgePrimaryButton(
                        text = stringResource(R.string.agent_sign_in_with_google),
                        onClick = onStartLogin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                busy -> OutlinedButton(
                    onClick = onCancel,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.heightIn(min = 44.dp),
                ) { Text(stringResource(R.string.agent_cancel)) }
            }
        }
    }
}

@Composable
internal fun ChatMessageCard(
    message: ChatMessage,
    agentName: String,
    onRunSnippet: (String) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val textColor = scheme.onSurface

    if (message.fromUser) {
        // User bubbles hug their text: no permanent copy button (it forced a 48dp minimum height); a long-press copies.
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
        val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
        val copiedLabel = stringResource(R.string.tool_copy)
        MessageContainer(
            fromUser = true,
            contentColor = textColor,
            onLongClick = {
                haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(message.text))
            },
            longClickLabel = copiedLabel,
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (message.text.isNotBlank()) {
                    MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet, selectable = false)
                }
                if (message.attachments.isNotEmpty()) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        message.attachments.filterNot { it.mimeType.startsWith("image/") }
                            .forEach { att -> AttachmentChip(name = att.displayName, size = att.sizeBytes, onRemove = null) }
                    }
                    message.attachments.filter { it.mimeType.startsWith("image/") }.forEach { att ->
                        ChatImage(alt = att.displayName, src = att.relativePath, maxHeight = 200.dp)
                    }
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (rememberReduceMotion()) Modifier
                else Modifier.animateContentSize(animationSpec = ForgeMotion.springFluid()),
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Each tool call is one collapsed row ("Bash · command ⌄"); tap to read its detail.
        if (message.workItems.size >= STEP_GROUP_MIN) {
            ToolStepGroup(message.workItems)
        } else {
            message.workItems.forEach { item -> ToolCallRow(item) }
        }
        // With several agents in one chat, each reply says who wrote it.
        message.agent?.let { id ->
            AgentKind.fromStoredOrNull(id)?.let { agent ->
                Text(
                    text = agent.displayTitle(),
                    fontSize = ForgeType.secondary,
                    fontWeight = FontWeight.Medium,
                    color = scheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                )
            }
        }
        if (message.workedMillis > 0L) {
            Text(
                text = stringResource(R.string.agent_worked_for, formatWorked(message.workedMillis)),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 2.dp),
            )
        }
        val streaming = message.id == "streaming-assistant"
        if (message.text.isNotBlank()) {
            MessageContainer(fromUser = false, contentColor = textColor) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    // 44dp: the copy button is a 48dp touch box; 32dp let it cover the end of the first lines.
                    Column(modifier = Modifier.padding(end = 44.dp)) {
                        // Selection (long-press, drag handles, Copy) only on a finished message: the streaming one changes
                        // under the selection container, which is what crashed Compose before. Finished text is static.
                        MarkdownText(markdown = message.text, color = textColor, onRunCode = onRunSnippet, selectable = !streaming)
                        if (streaming) {
                            // Blinking caret says "still arriving" without a spinner.
                            val blink = rememberBlink()
                            Box(
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .size(width = 8.dp, height = 16.dp)
                                    .graphicsLayer { alpha = blink }
                                    .background(scheme.primary, RoundedCornerShape(2.dp)),
                            )
                        }
                    }
                    if (!streaming) CopyMessageButton(message.text, Modifier.align(Alignment.TopEnd))
                }
            }
        }
        if (streaming) {
            // Every provider shows the same live line: rotating dots, time since the reply started, and what it is doing.
            val phase = when {
                message.text.isNotBlank() -> R.string.agent_status_writing
                message.workItems.isEmpty() -> R.string.agent_thinking
                else -> R.string.agent_status_working
            }
            // While a tool runs, say which one ("Working… · Bash"), so a long step doesn't look like a hang.
            val running = message.workItems.lastOrNull { !it.isComplete }?.title?.replace(Regex("\\s+"), " ")?.trim()?.take(32)
            val phaseLabel = stringResource(phase)
            WorkingIndicator(
                startedAt = message.createdAt,
                label = if (running.isNullOrBlank() || message.text.isNotBlank()) phaseLabel else "$phaseLabel · $running",
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }
}

/**
 * "(dots) 1m 4s · Thinking…": a ring of dots that turns, the time since the reply started (ticks every second), and the
 * phase. TalkBack hears only the phase (it changes rarely), never the per-second time.
 */
@Composable
internal fun WorkingIndicator(startedAt: java.time.Instant, label: String, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) {
        while (true) {
            now = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000)
        }
    }
    val elapsed = (now - startedAt.toEpochMilli()).coerceAtLeast(0L)
    val reduceMotion = rememberReduceMotion()
    // Eight dots: step the head ~14 times a second instead of animating every frame, which redrew the chat (and the
    // Glass backdrop behind the bar) at 60-120 fps for the whole agent turn.
    var step by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (!reduceMotion) {
        LaunchedEffect(Unit) {
            while (true) {
                kotlinx.coroutines.delay(1_100L / 16)
                step = (step + 1) % 16
            }
        }
    }
    val angle = step * 360f / 16
    val dotColor = scheme.primary
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.clearAndSetSemantics {
            contentDescription = label
            liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
        },
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(18.dp)) {
            val dots = 8
            val ring = size.minDimension / 2f - 2.dp.toPx()
            val head = angle / 360f * dots
            for (i in 0 until dots) {
                val radians = Math.toRadians(i * 360.0 / dots - 90.0)
                // Dots just behind the head are bright, the rest fade: it reads as a turning tail.
                val behind = ((head - i) % dots + dots) % dots
                val alpha = (1f - behind / dots).coerceIn(0.15f, 1f)
                drawCircle(
                    color = dotColor.copy(alpha = alpha),
                    radius = 1.7.dp.toPx() * (0.6f + 0.4f * alpha),
                    center = androidx.compose.ui.geometry.Offset(
                        center.x + (kotlin.math.cos(radians) * ring).toFloat(),
                        center.y + (kotlin.math.sin(radians) * ring).toFloat(),
                    ),
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = formatWorked(elapsed) + " · " + label,
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
    }
}

/**
 * Explicit copy action for a whole message. Long-press selection (MarkdownText) still works for parts of a message,
 * but it is easy to miss and can be lost while the list updates, so copying must not depend on it.
 */
@Composable
private fun CopyMessageButton(text: String, modifier: Modifier = Modifier) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    val label = stringResource(if (copied) R.string.tool_copied else R.string.tool_copy)
    Box(
        modifier = modifier
            .forgeMinTouch()
            .size(36.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClickLabel = label) {
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(text))
                copied = true
                scope.launch {
                    kotlinx.coroutines.delay(1_500)
                    copied = false
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (copied) {
            Icon(Icons.Default.Check, contentDescription = label, tint = tint, modifier = Modifier.size(16.dp))
        } else {
            // Copy glyph (two overlapping rounded sheets); the core icon set has no ContentCopy.
            androidx.compose.foundation.Canvas(modifier = Modifier.size(16.dp).semantics { contentDescription = label }) {
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.6.dp.toPx())
                val sheet = androidx.compose.ui.geometry.Size(size.width * 0.62f, size.height * 0.68f)
                val radius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx())
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.34f, 0f), size = sheet, cornerRadius = radius, style = stroke)
                drawRoundRect(tint, topLeft = androidx.compose.ui.geometry.Offset(0f, size.height * 0.30f), size = sheet, cornerRadius = radius, style = stroke)
            }
        }
    }
}

internal fun formatWorked(millis: Long): String {
    val totalSec = millis / 1000
    val m = totalSec / 60
    val sec = totalSec % 60
    return if (m > 0) "${m}m ${sec}s" else "${sec}s"
}

/** One collapsed tool call: status glyph, "Title · detail" on one line, live rolling tail while running, chevron; expands to the full detail. */
@Composable
internal fun ToolCallRow(item: ActivityItem) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val startedAtMillis = remember(item.title) { System.currentTimeMillis() }
    var elapsedSec by remember(item.title) { androidx.compose.runtime.mutableIntStateOf(0) }
    if (!item.isComplete) {
        LaunchedEffect(item.title) {
            while (true) {
                kotlinx.coroutines.delay(1_000)
                elapsedSec = ((System.currentTimeMillis() - startedAtMillis) / 1_000L).toInt().coerceAtLeast(0)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)) { open = !open }
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        // Kind decides glyph and tint: Think = accent, Write/Edit = warning, Bash/Read = quiet. Status still shows as a dot.
        val kind = toolKind(item)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box {
                ForgeIcon(glyph = kind.glyph, tint = kind.tint(scheme, tokens), size = 18.dp)
                if (!item.isComplete) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(tokens.warningText),
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = if (item.detail.isBlank()) item.title else "${item.title} · ${item.detail.lineSequence().first()}",
                fontSize = ForgeType.control,
                color = if (kind == ToolKind.THINK) scheme.onSurface else scheme.onSurfaceVariant,
                fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!item.isComplete && elapsedSec > 0) {
                val timerText = formatWorked(elapsedSec * 1_000L)
                Text(
                    text = timerText,
                    fontSize = ForgeType.caption,
                    color = tokens.warningText,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            val reduceMotion = rememberReduceMotion()
            val rotation by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
                label = "chevron",
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        val showBody = (open || !item.isComplete) && item.detail.isNotBlank()
        val displayDetail = if (open || item.isComplete) {
            item.detail
        } else {
            item.detail.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString("\n").ifBlank { item.detail }
        }
        AnimatedVisibility(
            visible = showBody,
            enter = expandVertically(tween(ForgeMotion.EXPAND_MS)) + fadeIn(tween(ForgeMotion.FADE_MS)),
            exit = shrinkVertically(tween(ForgeMotion.FADE_MS)) + fadeOut(tween(ForgeMotion.FADE_MS / 2)),
        ) {
            Text(
                text = displayDetail,
                fontSize = ForgeType.secondary,
                lineHeight = 18.sp,
                color = tokens.terminalText,
                fontFamily = if (item.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = if (open) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tokens.terminalBackground)
                    .padding(10.dp),
            )
        }
    }
}

/** User messages sit in a soft rounded surface; agent replies flow on the canvas without a card. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageContainer(
    fromUser: Boolean,
    contentColor: Color,
    onLongClick: (() -> Unit)? = null,
    longClickLabel: String? = null,
    content: @Composable () -> Unit,
) {
    if (fromUser) {
        // Wraps its text and sits at the end: a short message must not get a full-width bubble of empty space.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.TopEnd) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = LocalForgeThemeTokens.current.cardElevatedColor,
                contentColor = contentColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .then(
                        if (onLongClick != null) {
                            Modifier.combinedClickable(onLongClickLabel = longClickLabel, onLongClick = onLongClick, onClick = {})
                        } else Modifier,
                    ),
            ) { content() }
        }
    } else {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) { content() }
    }
}

/** Tool-call kinds, derived from the activity title, each with its own glyph and tint. */
internal enum class ToolKind(val glyph: ForgeGlyph) {
    THINK(ForgeGlyph.SPARK), BASH(ForgeGlyph.TERMINAL), READ(ForgeGlyph.FILE), WRITE(ForgeGlyph.EDIT), OTHER(ForgeGlyph.BUILD);

    fun tint(scheme: androidx.compose.material3.ColorScheme, tokens: ForgeThemeTokens): Color = when (this) {
        THINK -> scheme.primary
        WRITE -> tokens.warningText
        BASH, READ, OTHER -> scheme.onSurfaceVariant
    }
}

internal fun toolKind(item: ActivityItem): ToolKind {
    val t = item.title.lowercase()
    return when {
        "think" in t || "reason" in t -> ToolKind.THINK
        item.isCommand || "bash" in t || "shell" in t || "run" in t || "exec" in t -> ToolKind.BASH
        "write" in t || "edit" in t || "create" in t || "patch" in t -> ToolKind.WRITE
        "read" in t || "view" in t || "search" in t || "grep" in t || "list" in t -> ToolKind.READ
        else -> ToolKind.OTHER
    }
}

/** Small capsule naming an attached file; [onRemove] null makes it read-only (sent messages). */
@Composable
private fun AttachmentChip(name: String, size: Long, onRemove: (() -> Unit)?, showSize: Boolean = true) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(tokens.cardElevatedColor)
            .padding(start = 12.dp, end = if (onRemove == null) 12.dp else 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, fontSize = ForgeType.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        if (showSize) Spacer(modifier = Modifier.width(6.dp))
        if (showSize) Text(
            text = if (size < 1024 * 1024) "${(size / 1024).coerceAtLeast(1)} KB" else String.format(java.util.Locale.getDefault(), "%.1f MB", size / (1024.0 * 1024.0)),
            fontSize = ForgeType.caption,
            color = scheme.onSurfaceVariant,
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove, modifier = Modifier.size(ForgeSpace.minTouch)) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_remove_attachment), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** String lookup for click handlers, which run outside composition and cannot call stringResource. */
private fun stringOf(@androidx.annotation.StringRes id: Int, context: android.content.Context): String = context.getString(id)
