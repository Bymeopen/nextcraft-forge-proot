@file:OptIn(
    ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class,
)

package com.nextcraft.forge.ui

import androidx.compose.ui.platform.LocalResources
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
