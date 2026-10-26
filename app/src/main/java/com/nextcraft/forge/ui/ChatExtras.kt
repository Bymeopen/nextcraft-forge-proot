@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.nextcraft.forge.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ripple
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.ImeAction
import com.nextcraft.forge.R
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLineType
import com.nextcraft.forge.model.RiskLevel
import com.nextcraft.forge.model.ToolRequest

/*
 * Batch B (chat): grouped tool steps, inline diff review, approval sheet and quick-action chips.
 * Everything here is stateless apart from expand/collapse, so it renders in the phone-size screenshot harness.
 */

/** Three or more tool calls in one reply fold into a single "N steps" row; the live step shows while it runs. */
internal const val STEP_GROUP_MIN = 3

@Composable
internal fun ToolStepGroup(items: List<ActivityItem>, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val live = items.lastOrNull()?.isComplete == false
    val kinds = items.map { toolKind(it) }.distinct().take(3)
    val expandLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(role = Role.Button, onClickLabel = expandLabel) { open = !open }
                .heightIn(min = ForgeSpace.minTouch)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Kind glyphs overlap like an avatar stack so the row says what happened before it is opened.
            Box(modifier = Modifier.width(22.dp + 14.dp * (kinds.size - 1).coerceAtLeast(0))) {
                kinds.forEachIndexed { index, kind ->
                    Box(
                        modifier = Modifier
                            .padding(start = 14.dp * index)
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(tokens.cardElevatedColor),
                        contentAlignment = Alignment.Center,
                    ) { ForgeIcon(kind.glyph, tint = kind.tint(scheme, tokens), size = 14.dp) }
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            val last = items.last()
            Text(
                text = if (live) {
                    if (last.detail.isBlank()) last.title else "${last.title} · ${last.detail.lineSequence().first()}"
                } else {
                    quantityStringResource(R.plurals.chat_steps, items.size, items.size)
                },
                fontSize = ForgeType.control,
                color = if (live) scheme.onSurface else scheme.onSurfaceVariant,
                fontFamily = if (live && last.isCommand) FontFamily.Monospace else FontFamily.Default,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (live) {
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(tokens.warningText))
                Spacer(modifier = Modifier.width(8.dp))
            }
            Chevron(open)
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(androidx.compose.animation.core.tween(ForgeMotion.EXPAND_MS)) + fadeIn(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS)),
            exit = shrinkVertically(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS)) + fadeOut(androidx.compose.animation.core.tween(ForgeMotion.FADE_MS / 2)),
        ) {
            Column(modifier = Modifier.padding(start = 12.dp)) {
                items.forEach { ToolCallRow(it) }
            }
        }
    }
}

/** Files the agent changed in this turn, with an inline diff per file and keep / revert right in the chat. */
@Composable
internal fun ChangedFilesCard(
    changes: List<ChangeItem>,
    enabled: Boolean,
    onOpenFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onKeepAll: () -> Unit,
    onRevertAll: () -> Unit,
    modifier: Modifier = Modifier,
    onEditInTerminal: (String) -> Unit = {},
    onAcceptAndCommit: (() -> Unit)? = null,
    onRevertHunk: ((String, Int) -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val adds = changes.sumOf { it.additions }
    val dels = changes.sumOf { it.deletions }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(vertical = 6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(ForgeGlyph.FILE, tint = scheme.primary, size = 18.dp)
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = quantityStringResource(R.plurals.chat_files_changed, changes.size, changes.size),
                    fontSize = ForgeType.rowTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                DiffCounts(adds, dels)
            }
            changes.forEach { change ->
                ChangedFileRow(change, enabled, onOpenFile, onKeepFile, onRevertFile, onEditInTerminal, onRevertHunk)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ConfirmedAction(
                    title = stringResource(R.string.chat_revert_all),
                    message = quantityStringResource(R.plurals.chat_files_changed, changes.size, changes.size),
                    confirmLabel = stringResource(R.string.chat_revert_all),
                    onConfirm = onRevertAll,
                ) { onClick ->
                    OutlinedButton(
                        onClick = onClick,
                        enabled = enabled,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                    ) { Text(stringResource(R.string.chat_revert_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                Button(
                    onClick = onKeepAll,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                ) { Text(stringResource(R.string.chat_keep_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            if (onAcceptAndCommit != null) {
                OutlinedButton(
                    onClick = onAcceptAndCommit,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 8.dp, end = 8.dp, top = 6.dp)
                        .heightIn(min = ForgeSpace.minTouch),
                ) {
                    Text(
                        stringResource(R.string.diff_accept_and_commit),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiffCounts(additions: Int, deletions: Int) {
    val tokens = LocalForgeThemeTokens.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.diff_added, additions), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tokens.successText)
        Text(stringResource(R.string.diff_removed, deletions), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = tokens.dangerText)
    }
}

private const val DIFF_PREVIEW_LINES = 24

@Composable
private fun ChangedFileRow(
    change: ChangeItem,
    enabled: Boolean,
    onOpenFile: (String) -> Unit,
    onKeepFile: (String) -> Unit,
    onRevertFile: (String) -> Unit,
    onEditInTerminal: (String) -> Unit,
    onRevertHunk: ((String, Int) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    var confirmRevert by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val name = change.path.substringAfterLast('/')
    val dir = change.path.substringBeforeLast('/', "")
    // Swipe right keeps the file, left asks before reverting it so diagonal scroll gestures never lose changes.
    // Only while the row is collapsed: an open diff scrolls sideways and would fight the swipe.
    val haptics = LocalHapticFeedback.current
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onKeepFile(change.path) }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); confirmRevert = true }
                else -> Unit
            }
            false // the list drops the row itself once the change is resolved; if it is not, the row springs back
        },
    )
    androidx.compose.material3.SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = enabled && !open,
        enableDismissFromEndToStart = enabled && !open,
        backgroundContent = {
            val keeping = swipe.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd
            val reverting = swipe.dismissDirection == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart
            if (keeping || reverting) {
                Box(
                    modifier = Modifier.fillMaxSize().background((if (keeping) tokens.successText else tokens.dangerText).copy(alpha = 0.16f)).padding(horizontal = 18.dp),
                    contentAlignment = if (keeping) Alignment.CenterStart else Alignment.CenterEnd,
                ) {
                    Text(
                        stringResource(if (keeping) R.string.chat_keep else R.string.chat_revert),
                        fontWeight = FontWeight.SemiBold,
                        color = if (keeping) tokens.successText else tokens.dangerText,
                    )
                }
            }
        },
    ) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClickLabel = stringResource(if (open) R.string.a11y_collapse else R.string.a11y_expand)) { open = !open }
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(name, fontSize = ForgeType.control, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (dir.isNotEmpty()) {
                    Text(dir, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            DiffCounts(change.additions, change.deletions)
            Spacer(modifier = Modifier.width(2.dp))
            Chevron(open)
        }
        AnimatedVisibility(visible = open) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) {
                if (change.binary || change.diffLines.isEmpty()) {
                    Text(
                        stringResource(if (change.binary) R.string.chat_diff_binary else R.string.chat_diff_none),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                } else {
                    DiffCodeView(
                        lines = change.diffLines,
                        path = change.path,
                        previewLines = DIFF_PREVIEW_LINES,
                        onRevertHunk = if (enabled && onRevertHunk != null) ({ idx -> onRevertHunk(change.path, idx) }) else null,
                    )
                }
                // Two fixed rows instead of one wrapping row, so no button is ever orphaned: ways to look at the file on top,
                // the decision (revert / keep) below with the primary action on the right.
                val actionHeight = Modifier.heightIn(min = ForgeSpace.minTouch)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onEditInTerminal(change.path) }, modifier = actionHeight) { Text(stringResource(R.string.files_edit_in_terminal), maxLines = 1) }
                    TextButton(onClick = { onOpenFile(change.path) }, modifier = actionHeight) { Text(stringResource(R.string.chat_open_in_files), maxLines = 1) }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { confirmRevert = true }, enabled = enabled, modifier = actionHeight) {
                        Text(stringResource(R.string.chat_revert), color = tokens.dangerText, maxLines = 1)
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = { onKeepFile(change.path) }, enabled = enabled, modifier = actionHeight) {
                        Text(stringResource(R.string.chat_keep), fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }
    }
    if (confirmRevert) {
        ForgeAlertDialog(
            onDismissRequest = { confirmRevert = false },
            title = { Text(stringResource(R.string.files_revert_file), fontWeight = FontWeight.SemiBold) },
            text = { Text(change.path) },
            confirmButton = {
                ForgeTextButton(onClick = { confirmRevert = false; onRevertFile(change.path) }) {
                    Text(stringResource(R.string.chat_revert), color = tokens.dangerText)
                }
            },
            dismissButton = {
                ForgeTextButton(onClick = { confirmRevert = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }
}

internal data class QuickChip(val label: String, val glyph: ForgeGlyph, val onClick: () -> Unit)

/** One row of pill shortcuts above the composer: what to do next, based on what just happened. */
@Composable
internal fun QuickActionRow(chips: List<QuickChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val scroll = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .forgeFadingEdges(scroll, scheme.background)
            .horizontalScroll(scroll)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        chips.forEach { chip ->
            val interaction = remember { MutableInteractionSource() }
            Row(
                modifier = Modifier
                    .forgePressScale(interaction, pressedScale = 0.95f)
                    .clip(RoundedCornerShape(100))
                    .background(tokens.cardElevatedColor)
                    .clickable(interactionSource = interaction, indication = ripple(), role = Role.Button, onClick = chip.onClick)
                    .heightIn(min = ForgeSpace.minTouch)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ForgeIcon(chip.glyph, tint = scheme.primary, size = 16.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(chip.label, fontSize = ForgeType.control, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/**
 * Tool approval as a bottom sheet. It cannot be dismissed by swiping, tapping outside or Back: the agent is waiting,
 * so the answer must be an explicit Deny or Approve.
 */
@Composable
internal fun ToolApprovalSheet(request: ToolRequest, onApprove: () -> Unit, onReject: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { it != SheetValue.Hidden },
    )
    ForgeModalSheet(
        onDismissRequest = {},
        sheetState = sheetState,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        ToolApprovalContent(request, onApprove, onReject)
    }
}

@Composable
internal fun ToolApprovalContent(request: ToolRequest, onApprove: () -> Unit, onReject: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val (riskLabel, riskTint) = when (request.risk) {
        RiskLevel.HIGH -> R.string.approval_risk_high to scheme.error
        RiskLevel.REVIEW -> R.string.approval_risk_review to tokens.warningText
        RiskLevel.SAFE -> R.string.approval_risk_safe to scheme.onSurfaceVariant
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ForgeIcon(ForgeGlyph.WARNING, tint = riskTint, size = 20.dp)
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_approval_title), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                Text(
                    text = stringResource(riskLabel) + " · " + request.toolName,
                    fontSize = ForgeType.secondary,
                    fontWeight = FontWeight.Medium,
                    color = riskTint,
                )
            }
        }
        Text(request.explanation, fontSize = ForgeType.body, color = scheme.onSurfaceVariant)
        if (request.affectedPaths.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.approval_paths).uppercase(), fontSize = ForgeType.caption, letterSpacing = ForgeType.sectionTracking, color = scheme.onSurfaceVariant)
                request.affectedPaths.take(5).forEach { path ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ForgeIcon(ForgeGlyph.FILE, tint = scheme.onSurfaceVariant, size = 14.dp)
                        Spacer(modifier = Modifier.width(8.dp))
                        // File name first: a long path would otherwise ellipsize away the one part that matters.
                        Column {
                            Text(path.substringAfterLast('/'), fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            val dir = path.substringBeforeLast('/', "")
                            if (dir.isNotEmpty()) Text(dir, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                val extra = request.affectedPaths.size - 5
                if (extra > 0) Text(stringResource(R.string.chat_diff_more, extra), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            }
        }
        request.commandPreview?.takeIf { it.isNotBlank() }?.let { cmd ->
            Text(
                text = cmd,
                style = ForgeCodeTextStyle,
                color = tokens.terminalText,
                maxLines = 8,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.terminalBackground)
                    .padding(12.dp),
            )
        }
        Text(stringResource(R.string.approval_waiting_hint), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = onReject,
                shape = RoundedCornerShape(100),
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.agent_deny), fontWeight = FontWeight.Medium) }
            Button(
                onClick = onApprove,
                shape = RoundedCornerShape(100),
                colors = if (request.risk == RiskLevel.HIGH) {
                    ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.weight(1f).heightIn(min = 52.dp),
            ) { Text(stringResource(R.string.agent_approve_execution), fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The one-line composer capsule: attach menu, message field, and a round send/stop button, plus inline @/slash autocomplete. */
@Composable
internal fun ChatComposer(
    promptInput: String,
    onPromptChange: (String) -> Unit,
    agentName: String,
    running: Boolean,
    canSend: Boolean,
    captureEnabled: Boolean,
    onAttachCamera: () -> Unit,
    onAttachFile: () -> Unit,
    onCapture: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    /** Model (and effort) shown as a chip next to the attach button; tapping it opens the model sheet. */
    modelChipLabel: String = "",
    onModelChip: (() -> Unit)? = null,
    /** Routing mode shown as a second chip (single agent, manual, failover, balanced); tapping it opens the routing sheet. */
    routeChipLabel: String = "",
    onRouteChip: (() -> Unit)? = null,
    /** Microphone button before the send button; null hides it. [listening] shows the recording state. */
    onMic: (() -> Unit)? = null,
    listening: Boolean = false,
    /** 0..1 loudness while [listening]; the mic pulses with it. */
    micLevel: Float = 0f,
    /** When true, voice dictation automatically sends the prompt as soon as the user finishes speaking. */
    handsFreeSend: Boolean = false,
    onToggleHandsFree: (() -> Unit)? = null,
    /** "Create image" entry of the attach menu (Nano Banana); null hides it. */
    onGenerateImage: (() -> Unit)? = null,
    /** Workspace files and ready agents for inline `@file`, `@agent`, and `/slash` autocomplete popover. */
    workspaceFiles: List<com.nextcraft.forge.model.WorkspaceEntry> = emptyList(),
    readyAgents: Set<com.nextcraft.forge.model.AgentKind> = emptySet(),
    onExecuteSlashCommand: ((String) -> Unit)? = null,
    onSelectAgentTag: (() -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    var attachMenuOpen by remember { mutableStateOf(false) }
    val composerLabel = stringResource(R.string.agent_message_placeholder, agentName)
    Column(modifier = Modifier.fillMaxWidth()) {
    AnimatedVisibility(visible = listening) {
        Surface(
            shape = RoundedCornerShape(100),
            color = tokens.cardElevatedColor,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 2.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                )
                Text(
                    text = stringResource(R.string.voice_handsfree_hint),
                    fontSize = ForgeType.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (onToggleHandsFree != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (handsFreeSend) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(role = Role.Button, onClick = onToggleHandsFree),
                    ) {
                        Text(
                            text = stringResource(R.string.voice_auto_send_toggle),
                            fontSize = ForgeType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = if (handsFreeSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }
    }
    ComposerAutocompletePopover(
        promptInput = promptInput,
        workspaceFiles = workspaceFiles,
        readyAgents = readyAgents,
        onReplacePrompt = onPromptChange,
        onExecuteSlash = onExecuteSlashCommand,
        onSelectAgentTag = onSelectAgentTag,
    )
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = tokens.cardContainerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Column(modifier = Modifier.padding(start = 4.dp, end = 6.dp, top = 4.dp, bottom = 4.dp)) {
        // Message field on its own line, like Claude; the attach button, model chip and send button sit below it.
        TextField(
            value = promptInput,
            onValueChange = onPromptChange,
            placeholder = {
                Text(
                    stringResource(R.string.chat_composer_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            // The short hint fits a 360dp phone; TalkBack still hears which agent the message goes to.
            modifier = Modifier.fillMaxWidth().semantics { contentDescription = composerLabel },
            maxLines = 5,
            // Enter stays a newline (messages are multi-line); send is the round button.
            // Disable IME autocorrect when typing a slash command (`/`) so Gboard/IME never duplicates `/compact/compact`.
            keyboardOptions = if (promptInput.startsWith("/")) {
                ForgeFieldKind.IDENTIFIER.keyboardOptions(ImeAction.Default)
            } else {
                ForgeFieldKind.TEXT.keyboardOptions(ImeAction.Default)
            },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Attach and the chips scroll on their own: long model/route labels must never push the mic and send
            // buttons past the edge of a narrow phone, which left them off-screen and unreachable.
            Row(
                modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Attach: photos or any file; it lands in attachments/ inside the workspace.
                Box {
                    IconButton(onClick = { attachMenuOpen = true }) {
                        ForgeIcon(glyph = ForgeGlyph.PLUS, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp, description = stringResource(R.string.agent_attach))
                    }
                    DropdownMenu(expanded = attachMenuOpen, onDismissRequest = { attachMenuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_attach_camera)) },
                            onClick = { attachMenuOpen = false; onAttachCamera() },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.agent_attach_file)) },
                            onClick = { attachMenuOpen = false; onAttachFile() },
                        )
                        if (onGenerateImage != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.image_menu)) },
                                onClick = { attachMenuOpen = false; onGenerateImage() },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.capture_menu)) },
                            enabled = captureEnabled,
                            onClick = { attachMenuOpen = false; onCapture() },
                        )
                    }
                }
                if (onModelChip != null && modelChipLabel.isNotBlank()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onModelChip),
                    ) {
                        Text(
                            text = modelChipLabel,
                            fontSize = ForgeType.secondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 150.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
                if (onRouteChip != null && routeChipLabel.isNotBlank()) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onRouteChip),
                    ) {
                        Text(
                            text = routeChipLabel,
                            fontSize = ForgeType.secondary,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 110.dp).padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                }
            }
            if (onMic != null) {
                val reduceMotionMic = rememberReduceMotion()
                val pulseColor = MaterialTheme.colorScheme.error
                val pulse by animateFloatAsState(
                    targetValue = if (listening) micLevel else 0f,
                    animationSpec = if (reduceMotionMic) snap() else tween(90),
                    label = "micPulse",
                )
                IconButton(
                    onClick = onMic,
                    modifier = Modifier.drawBehind {
                        // A soft ring that grows with the voice: the person sees the phone is hearing them.
                        if (pulse > 0.02f) {
                            drawCircle(pulseColor.copy(alpha = 0.10f + 0.18f * pulse), radius = size.minDimension / 2f * (0.8f + 0.5f * pulse))
                        }
                    },
                ) {
                    ForgeIcon(
                        glyph = ForgeGlyph.MIC,
                        tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 22.dp,
                        description = stringResource(if (listening) R.string.voice_stop else R.string.voice_start),
                    )
                }
            }
            val haptics = LocalHapticFeedback.current
            val reduceMotion = rememberReduceMotion()
            val sendScale by animateFloatAsState(
                targetValue = if (listening || running || canSend) 1f else 0.86f,
                animationSpec = if (reduceMotion) snap() else ForgeMotion.springSnappy(),
                label = "sendScale",
            )
            val sendBg by animateColorAsState(
                targetValue = when {
                    listening -> MaterialTheme.colorScheme.error
                    canSend -> MaterialTheme.colorScheme.primary
                    running -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
                },
                animationSpec = if (reduceMotion) snap() else tween(ForgeMotion.FADE_MS),
                label = "sendBg",
            )
            Box(
                modifier = Modifier
                    .forgeMinTouch()
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .combinedClickable(
                        enabled = listening || running || canSend || onMic != null,
                        role = Role.Button,
                        onClickLabel = stringResource(
                            when {
                                listening -> R.string.voice_stop
                                running && !canSend -> R.string.agent_stop
                                else -> R.string.agent_send
                            },
                        ),
                        onLongClick = onMic?.takeIf { !listening && !running }?.let { mic ->
                            {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                mic()
                            }
                        },
                        onClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            // While the agent works, sending queues the message; stop is available when nothing is typed.
                            when {
                                listening -> onMic?.invoke()
                                canSend -> onSend()
                                running -> onStop()
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = when {
                        listening -> Icons.Default.Close
                        running && !canSend -> Icons.Default.Close
                        else -> Icons.Default.KeyboardArrowUp
                    },
                    contentDescription = stringResource(
                        when {
                            listening -> R.string.voice_stop
                            running && !canSend -> R.string.agent_stop
                            else -> R.string.agent_send
                        },
                    ),
                    tint = when {
                        listening -> MaterialTheme.colorScheme.onError
                        canSend -> MaterialTheme.colorScheme.onPrimary
                        running -> MaterialTheme.colorScheme.onError
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        }
    }
    }
}

private data class ComposerSuggestion(
    val id: String,
    val title: String,
    val subtitle: String,
    val glyph: ForgeGlyph,
    val badge: String? = null,
    val insertText: String,
    val executeImmediately: Boolean = false,
)

/**
 * Floating glass popover right above the composer when the user types `@` (agents + workspace files) or `/` (slash commands).
 */
@Composable
private fun ComposerAutocompletePopover(
    promptInput: String,
    workspaceFiles: List<com.nextcraft.forge.model.WorkspaceEntry>,
    readyAgents: Set<com.nextcraft.forge.model.AgentKind>,
    onReplacePrompt: (String) -> Unit,
    onExecuteSlash: ((String) -> Unit)?,
    onSelectAgentTag: (() -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    val slashMatch = remember(promptInput) {
        if (promptInput.startsWith("/") && !promptInput.contains('\n') && promptInput.indexOf(' ') < 0) {
            promptInput.substring(1).lowercase()
        } else null
    }

    val atMatch = remember(promptInput) {
        if (slashMatch != null) null
        else {
            val lastAt = promptInput.lastIndexOf('@')
            if (lastAt >= 0 && (lastAt == 0 || promptInput[lastAt - 1].isWhitespace())) {
                val after = promptInput.substring(lastAt + 1)
                if (after.none { it.isWhitespace() } && after.length <= 48) {
                    lastAt to after.lowercase()
                } else null
            } else null
        }
    }

    val suggestions = remember(slashMatch, atMatch, workspaceFiles, readyAgents) {
        when {
            slashMatch != null -> {
                val commands = listOf(
                    ComposerSuggestion("cmd-build", "/build", "Build & install debug APK", ForgeGlyph.BUILD, "Action", "/build", executeImmediately = true),
                    ComposerSuggestion("cmd-review", "/review", "Audit uncommitted changes with 2nd agent", ForgeGlyph.SPARK, "Swarm", "/review", executeImmediately = true),
                    ComposerSuggestion("cmd-memory", "/memory", "Open persistent project memory (.forge/memory.md)", ForgeGlyph.FILE, "Memory", "/memory", executeImmediately = true),
                    ComposerSuggestion("cmd-compact", "/compact", "Compact conversation history into a concise summary", ForgeGlyph.SPARK, "Context", "/compact", executeImmediately = true),
                    ComposerSuggestion("cmd-diff", "/diff", "Open workspace diff & changed files", ForgeGlyph.CODE, "Pane", "/diff", executeImmediately = true),
                    ComposerSuggestion("cmd-branch", "/branch", "Isolate this chat in its own Git branch", ForgeGlyph.SPARK, "Git", "/branch", executeImmediately = true),
                    ComposerSuggestion("cmd-loop", "/loop 30m ", "Repeat a prompt on a schedule (5m–7d)", ForgeGlyph.SPARK, "Timer", "/loop 30m "),
                    ComposerSuggestion("cmd-loop-off", "/loop off", "Clear all scheduled timers in this chat", ForgeGlyph.TRASH, "Timer", "/loop off", executeImmediately = true),
                    ComposerSuggestion("cmd-terminal", "/terminal", "Switch to project Ubuntu Root Terminal", ForgeGlyph.TERMINAL, "Pane", "/terminal", executeImmediately = true),
                    ComposerSuggestion("cmd-clear", "/clear", "Clear conversation history in this chat", ForgeGlyph.TRASH, "Chat", "/clear", executeImmediately = true),
                )
                commands.filter { it.title.removePrefix("/").startsWith(slashMatch) || it.subtitle.lowercase().contains(slashMatch) }
            }
            atMatch != null -> {
                val (atIndex, query) = atMatch
                val list = mutableListOf<ComposerSuggestion>()
                if (atIndex == 0) {
                    val agentEntries = listOf(
                        Triple("claude", com.nextcraft.forge.model.AgentKind.CLAUDE_CODE, "Claude Code CLI in Root Chroot"),
                        Triple("agy", com.nextcraft.forge.model.AgentKind.ANTIGRAVITY, "Google Antigravity CLI (agy)"),
                        Triple("deepseek", com.nextcraft.forge.model.AgentKind.DEEPSEEK_HARNESS, "DeepSeek Harness JSON-RPC"),
                    )
                    for ((tag, kind, desc) in agentEntries) {
                        if (tag.startsWith(query) || kind.title.lowercase().contains(query)) {
                            val isReady = readyAgents.isEmpty() || kind in readyAgents
                            list += ComposerSuggestion(
                                id = "agent-$tag",
                                title = "@$tag",
                                subtitle = desc,
                                glyph = ForgeGlyph.SPARK,
                                badge = if (isReady) "Agent" else "Setup",
                                insertText = "@$tag ",
                            )
                        }
                    }
                }
                val matchingFiles = workspaceFiles
                    .asSequence()
                    .filter { !it.isDirectory }
                    .filter { query.isEmpty() || it.name.lowercase().contains(query) || it.path.lowercase().contains(query) }
                    .take(6)
                    .map { file ->
                        ComposerSuggestion(
                            id = "file-${file.path}",
                            title = file.name,
                            subtitle = file.path,
                            glyph = ForgeGlyph.FILE,
                            badge = "File",
                            insertText = "${file.path} ",
                        )
                    }
                list.addAll(matchingFiles)
                list.take(7)
            }
            else -> emptyList()
        }
    }

    AnimatedVisibility(
        visible = suggestions.isNotEmpty(),
        enter = expandVertically(tween(ForgeMotion.EXPAND_MS)) + fadeIn(tween(ForgeMotion.FADE_MS)),
        exit = shrinkVertically(tween(ForgeMotion.FADE_MS)) + fadeOut(tween(ForgeMotion.FADE_MS / 2)),
    ) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = tokens.cardElevatedColor,
            contentColor = scheme.onSurface,
            border = tokens.cardBorderStroke,
            shadowElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 2.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                suggestions.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) {
                                if (item.executeImmediately && onExecuteSlash != null) {
                                    onReplacePrompt("")
                                    onExecuteSlash(item.insertText)
                                } else if (slashMatch != null) {
                                    onReplacePrompt(item.insertText)
                                } else if (atMatch != null) {
                                    val (atIdx, _) = atMatch
                                    val prefix = promptInput.substring(0, atIdx)
                                    val replacement = if (item.id.startsWith("agent-")) {
                                        onSelectAgentTag?.invoke()
                                        item.insertText
                                    } else "@${item.insertText}"
                                    onReplacePrompt(prefix + replacement)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgeIcon(
                            glyph = item.glyph,
                            tint = if (item.id.startsWith("agent-") || item.id.startsWith("cmd-")) scheme.primary else scheme.onSurfaceVariant,
                            size = 18.dp,
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = item.title,
                                fontSize = ForgeType.control,
                                fontWeight = FontWeight.SemiBold,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = item.subtitle,
                                fontSize = ForgeType.caption,
                                color = scheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (item.badge != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = scheme.primary.copy(alpha = 0.12f),
                            ) {
                                Text(
                                    text = item.badge,
                                    fontSize = ForgeType.caption,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.primary,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dynamic Island-style Multi-Agent & Multi-Build Live Activity HUD.
 * Shows live counts of running agents across all projects and active APK builds; tapping opens a quick switcher sheet.
 */
@Composable
internal fun LiveActivityHud(
    state: ForgeUiState,
    onSelectRun: (projectId: String, chatId: String) -> Unit,
    onSelectBuild: (projectId: String) -> Unit,
    onStopRun: (projectId: String, chatId: String) -> Unit,
    onStopBuild: (projectId: String) -> Unit = {},
) {
    val inActiveChatScreen = state.activeTab == ForgeTab.PROJECTS &&
        state.projectView == ProjectView.CONVERSATION &&
        state.projectPane == ProjectPane.CHAT

    // Show the HUD whenever any background agent or APK build is running outside the currently visible chat,
    // or when multiple chats/builds are running concurrently.
    val backgroundRuns = if (inActiveChatScreen) {
        state.liveAgentRuns.filterNot { it.projectId == state.activeProjectId && it.chatId == state.activeChatId }
    } else {
        state.liveAgentRuns
    }
    val activeBuilds = state.buildingProjectIds
    val showHud = backgroundRuns.isNotEmpty() || activeBuilds.isNotEmpty() || state.liveAgentRuns.size > 1

    var sheetOpen by remember { mutableStateOf(false) }
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    AnimatedVisibility(
        visible = showHud,
        enter = expandVertically(tween(ForgeMotion.EXPAND_MS)) + fadeIn(tween(ForgeMotion.FADE_MS)),
        exit = shrinkVertically(tween(ForgeMotion.FADE_MS)) + fadeOut(tween(ForgeMotion.FADE_MS / 2)),
    ) {
        val totalAgents = state.liveAgentRuns.size
        val totalBuilds = activeBuilds.size
        val anyWaitingApproval = state.liveAgentRuns.any { it.waitingApproval }
        val leadRun = backgroundRuns.firstOrNull() ?: state.liveAgentRuns.firstOrNull()

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(100),
                color = tokens.cardElevatedColor,
                contentColor = scheme.onSurface,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (anyWaitingApproval) tokens.warningText.copy(alpha = 0.65f) else scheme.primary.copy(alpha = 0.45f),
                ),
                shadowElevation = 4.dp,
                modifier = Modifier
                    .clip(RoundedCornerShape(100))
                    .clickable(role = Role.Button) { sheetOpen = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (anyWaitingApproval) tokens.warningText else scheme.primary),
                    )
                    val summaryParts = buildList {
                        if (totalAgents > 0) {
                            add(if (totalAgents == 1) "1 Agent live" else "$totalAgents Agents live")
                        }
                        if (totalBuilds > 0) {
                            add(if (totalBuilds == 1) "1 APK build" else "$totalBuilds APK builds")
                        }
                    }
                    Text(
                        text = summaryParts.joinToString(" · "),
                        fontSize = ForgeType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = if (anyWaitingApproval) tokens.warningText else scheme.primary,
                    )
                    if (leadRun != null) {
                        val leadLabel = listOf(leadRun.projectName, leadRun.chatTitle).joinToString(" · ")
                        Text(
                            text = leadLabel,
                            fontSize = ForgeType.caption,
                            color = scheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 180.dp),
                        )
                    }
                }
            }
        }
    }

    if (sheetOpen) {
        ForgeModalSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ForgeSpace.card)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.hud_live_title),
                    fontSize = ForgeType.screenTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                if (state.liveAgentRuns.isEmpty() && activeBuilds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.hud_live_empty),
                        fontSize = ForgeType.body,
                        color = scheme.onSurfaceVariant,
                    )
                }
                state.liveAgentRuns.forEach { run ->
                    val isCurrent = run.projectId == state.activeProjectId &&
                        run.chatId == state.activeChatId &&
                        inActiveChatScreen
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.cardElevatedColor,
                        border = if (isCurrent) {
                            androidx.compose.foundation.BorderStroke(1.dp, scheme.primary.copy(alpha = 0.6f))
                        } else tokens.cardBorderStroke,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) {
                                sheetOpen = false
                                onSelectRun(run.projectId, run.chatId)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ForgeIcon(
                                glyph = ForgeGlyph.SPARK,
                                tint = if (run.waitingApproval) tokens.warningText else scheme.primary,
                                size = 20.dp,
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val runTitle = listOf(run.projectName, run.chatTitle).joinToString(" · ")
                                Text(
                                    text = runTitle,
                                    fontSize = ForgeType.rowTitle,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = listOfNotNull(
                                        run.agent.displayTitle(),
                                        run.statusLine?.asString(),
                                    ).joinToString(" · "),
                                    fontSize = ForgeType.caption,
                                    color = if (run.waitingApproval) tokens.warningText else scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { onStopRun(run.projectId, run.chatId) },
                            ) {
                                Text(stringResource(R.string.agent_stop), color = scheme.error, fontSize = ForgeType.caption)
                            }
                        }
                    }
                }
                activeBuilds.forEach { projectId ->
                    val proj = state.projects.firstOrNull { it.id == projectId }
                    val record = state.projectBuilds[projectId]
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = tokens.cardElevatedColor,
                        border = tokens.cardBorderStroke,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button) {
                                sheetOpen = false
                                onSelectBuild(projectId)
                            },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ForgeIcon(glyph = ForgeGlyph.BUILD, tint = tokens.warningText, size = 20.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val buildTitle = listOfNotNull(proj?.name, stringResource(R.string.notif_building_apk)).joinToString(" · ")
                                Text(
                                    text = buildTitle,
                                    fontSize = ForgeType.rowTitle,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = record?.message ?: stringResource(R.string.notif_building_apk),
                                    fontSize = ForgeType.caption,
                                    color = scheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            TextButton(
                                onClick = { onStopBuild(projectId) },
                            ) {
                                Text(stringResource(R.string.agent_stop), color = scheme.error, fontSize = ForgeType.caption)
                            }
                        }
                    }
                }
            }
        }
    }
}
