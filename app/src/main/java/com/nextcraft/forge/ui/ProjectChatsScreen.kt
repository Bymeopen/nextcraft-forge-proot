@file:OptIn(ExperimentalFoundationApi::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.ProjectType

@Composable
fun ProjectChatsScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val palette = MaterialTheme.colorScheme
    val currentProject = state.activeProject
    val orderedChats = state.chats.sortedByDescending { it.updatedAtMillis }
    var showRepoModal by rememberSaveable { mutableStateOf(false) }
    var activeBranchReviewChatId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        val projectKindLabel = currentProject?.let { proj ->
            stringResource(if (proj.type == ProjectType.ANDROID) R.string.projects_android else R.string.projects_general)
        }
        val chatCountSummary = quantityStringResource(R.plurals.chats_subtitle, orderedChats.size, orderedChats.size)
        val combinedSubtitle = listOfNotNull(projectKindLabel, chatCountSummary).joinToString(" · ")

        ForgeProjectHeader(
            title = currentProject?.name ?: stringResource(R.string.shell_nextcraft_forge),
            subtitle = combinedSubtitle,
            accentSeed = currentProject?.name,
            onBack = viewModel::projectBack,
            actions = {
                if (currentProject?.type == ProjectType.ANDROID) {
                    ForgePillIcon(
                        icon = Icons.Default.PlayArrow,
                        description = stringResource(R.string.launch_build_and_install_apk),
                        onClick = { viewModel.selectTab(ForgeTab.ANDROID) },
                    )
                }
                ForgeGlyphPillIcon(
                    glyph = ForgeGlyph.BUILD,
                    description = stringResource(R.string.chats_open_project_tools),
                    onClick = { viewModel.selectTab(ForgeTab.TOOLS) },
                )
            },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgePrimaryButton(
                text = "+ ${stringResource(R.string.chats_new_chat)}",
                onClick = viewModel::createChat,
                enabled = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = { showRepoModal = true },
                shape = RoundedCornerShape(100),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                ForgeIcon(glyph = ForgeGlyph.HISTORY, tint = palette.primary, size = 16.dp)
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(R.string.repo_button), fontWeight = FontWeight.SemiBold)
            }
        }

        if (showRepoModal) {
            RepoSheet(
                state = state,
                viewModel = viewModel,
                onReview = { targetId -> activeBranchReviewChatId = targetId },
                onDismiss = { showRepoModal = false },
            )
        }
        activeBranchReviewChatId?.let { targetId ->
            BranchSheet(
                state = state,
                viewModel = viewModel,
                chatId = targetId,
                onDismiss = { activeBranchReviewChatId = null },
            )
        }

        if (orderedChats.isEmpty()) {
            ForgeEmptyState(
                glyph = ForgeGlyph.SPARK,
                title = stringResource(R.string.chats_empty_title),
                message = stringResource(R.string.chats_empty),
            )
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(orderedChats, key = { it.id }) { thread ->
                val isSelectedChat = thread.id == state.activeChatId
                val isCurrentlyRunning = thread.id in state.runningChatIds
                val touchSource = remember { MutableInteractionSource() }
                val rowShape = RoundedCornerShape(16.dp)
                val rowBg = if (isSelectedChat) palette.primary.copy(alpha = 0.12f) else LocalForgeThemeTokens.current.cardContainerColor
                val rowBorder = if (isSelectedChat) palette.primary.copy(alpha = 0.35f) else palette.outlineVariant.copy(alpha = 0.5f)
                Row(
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                        .fillMaxWidth()
                        .forgePressScale(touchSource, pressedScale = 0.985f)
                        .clip(rowShape)
                        .background(rowBg, rowShape)
                        .border(1.dp, rowBorder, rowShape)
                        .clickable(
                            interactionSource = touchSource,
                            indication = ripple(),
                            role = Role.Button,
                        ) { viewModel.openChat(thread.id) }
                        .heightIn(min = 60.dp)
                        .padding(start = 16.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ForgeStatusIcon(
                        status = when {
                            isCurrentlyRunning -> ForgeStatus.RUNNING
                            isSelectedChat -> ForgeStatus.DONE
                            else -> ForgeStatus.TODO
                        },
                        iconSize = 18.dp,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = thread.displayTitle(),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = if (isSelectedChat) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isSelectedChat) palette.primary else palette.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val metaText = if (thread.isolated) {
                            "${formatRelativeTime(thread.updatedAtMillis)} · ${stringResource(R.string.branch_title)}"
                        } else {
                            formatRelativeTime(thread.updatedAtMillis)
                        }
                        Text(
                            text = metaText,
                            fontSize = ForgeType.secondary,
                            color = palette.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (orderedChats.size > 1) {
                        ConfirmedAction(
                            title = stringResource(R.string.agent_delete_chat),
                            message = thread.displayTitle(),
                            confirmLabel = stringResource(R.string.tool_delete),
                            onConfirm = { viewModel.deleteChat(thread.id) },
                        ) { triggerConfirm ->
                            IconButton(
                                onClick = triggerConfirm,
                                enabled = !isCurrentlyRunning,
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.agent_delete_chat),
                                    tint = palette.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowRight,
                        contentDescription = null,
                        tint = palette.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}
