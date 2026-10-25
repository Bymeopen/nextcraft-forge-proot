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
                ChangedFileRow(change, enabled, onOpenFile, onKeepFile, onRevertFile, onEditInTerminal)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onRevertAll,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                ) { Text(stringResource(R.string.chat_revert_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Button(
                    onClick = onKeepAll,
                    enabled = enabled,
                    shape = RoundedCornerShape(100),
                    modifier = Modifier.weight(1f).heightIn(min = ForgeSpace.minTouch + 4.dp),
                ) { Text(stringResource(R.string.chat_keep_all), maxLines = 1, overflow = TextOverflow.Ellipsis) }
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
) {
    var open by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val name = change.path.substringAfterLast('/')
    val dir = change.path.substringBeforeLast('/', "")
    // Swipe right keeps the file, left reverts it (the revert has its own Undo snackbar). Only while the row is collapsed:
    // an open diff scrolls sideways and would fight the swipe.
    val haptics = LocalHapticFeedback.current
    val swipe = androidx.compose.material3.rememberSwipeToDismissBoxState(
        confirmValueChange = { target ->
            when (target) {
                androidx.compose.material3.SwipeToDismissBoxValue.StartToEnd -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onKeepFile(change.path) }
                androidx.compose.material3.SwipeToDismissBoxValue.EndToStart -> { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onRevertFile(change.path) }
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
                    DiffCodeView(lines = change.diffLines, path = change.path, previewLines = DIFF_PREVIEW_LINES)
                }
                // Two fixed rows instead of one wrapping row, so no button is ever orphaned: ways to look at the file on top,
                // the decision (revert / keep) below with the primary action on the right.
                val actionHeight = Modifier.heightIn(min = ForgeSpace.minTouch)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = { onEditInTerminal(change.path) }, modifier = actionHeight) { Text(stringResource(R.string.files_edit_in_terminal), maxLines = 1) }
                    TextButton(onClick = { onOpenFile(change.path) }, modifier = actionHeight) { Text(stringResource(R.string.chat_open_in_files), maxLines = 1) }
                }
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { onRevertFile(change.path) }, enabled = enabled, modifier = actionHeight) {
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

/** The one-line composer capsule: attach menu, message field, and a round send/stop button. */
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
    /** "Create image" entry of the attach menu (Nano Banana); null hides it. */
    onGenerateImage: (() -> Unit)? = null,
) {
    val tokens = LocalForgeThemeTokens.current
    var attachMenuOpen by remember { mutableStateOf(false) }
    val composerLabel = stringResource(R.string.agent_message_placeholder, agentName)
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
            keyboardOptions = ForgeFieldKind.TEXT.keyboardOptions(ImeAction.Default),
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
            val haptics = LocalHapticFeedback.current
            val reduceMotion = rememberReduceMotion()
            val sendScale by animateFloatAsState(
                targetValue = if (running || canSend) 1f else 0.86f,
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
            // No separate mic button: a long press starts voice input, freeing room for the model/route chips.
            Box(
                modifier = Modifier
                    .forgeMinTouch()
                    .graphicsLayer { scaleX = sendScale; scaleY = sendScale }
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(sendBg)
                    .combinedClickable(
                        enabled = listening || running || canSend,
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
