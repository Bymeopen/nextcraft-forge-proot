@file:OptIn(ExperimentalFoundationApi::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.ExperimentalFoundationApi

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind

/**
 * Inside a project: its chats, each with its own title, newest first. One primary New chat action;
 * the header offers Build (Android) and Project tools. Tapping a chat opens the conversation.
 */
@Composable
fun ProjectChatsScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val scheme = MaterialTheme.colorScheme
    val project = state.activeProject
    val chats = state.chats.sortedByDescending { it.updatedAtMillis }
    var repoOpen by rememberSaveable { mutableStateOf(false) }
    var reviewChatId by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(
            title = project?.name ?: stringResource(R.string.shell_nextcraft_forge),
            subtitle = listOfNotNull(
                project?.let { stringResource(if (it.type == com.nextcraft.forge.model.ProjectType.ANDROID) R.string.projects_android else R.string.projects_general) },
                quantityStringResource(R.plurals.chats_subtitle, chats.size, chats.size),
            ).joinToString(" · "),
            onBack = { viewModel.projectBack() },
            actions = {
                // Build & install only means something for Android projects.
                if (project?.type == com.nextcraft.forge.model.ProjectType.ANDROID) {
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

        ForgePrimaryButton(
            text = "+ " + stringResource(R.string.chats_new_chat),
            onClick = { viewModel.createChat() },
            enabled = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 6.dp),
        )

        ForgeTextButton(
            onClick = { repoOpen = true },
            modifier = Modifier.padding(start = 12.dp),
        ) { Text(stringResource(R.string.repo_button)) }
        if (repoOpen) {
            RepoSheet(
                state = state,
                viewModel = viewModel,
                onReview = { chatId -> reviewChatId = chatId },
                onDismiss = { repoOpen = false },
            )
        }
        reviewChatId?.let { id ->
            BranchSheet(state = state, viewModel = viewModel, chatId = id, onDismiss = { reviewChatId = null })
        }

        if (chats.isEmpty()) {
            ForgeEmptyState(
                glyph = ForgeGlyph.SPARK,
                title = stringResource(R.string.chats_empty_title),
                message = stringResource(R.string.chats_empty),
            )
            return
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(chats, key = { it.id }) { chat ->
                val active = chat.id == state.activeChatId
                Row(
                    modifier = Modifier
                        .animateItem(fadeInSpec = null, fadeOutSpec = null)
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { viewModel.openChat(chat.id) }
                        .heightIn(min = 60.dp)
                        .padding(start = 20.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ForgeStatusIcon(
                        status = if (chat.id in state.runningChatIds) ForgeStatus.RUNNING else ForgeStatus.TODO,
                        iconSize = 16.dp,
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = chat.displayTitle(),
                            fontSize = ForgeType.rowTitle,
                            // The open chat is marked by weight and colour, not by a "done" check.
                            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (active) scheme.primary else scheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = formatRelativeTime(chat.updatedAtMillis),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (chats.size > 1) {
                        ConfirmedAction(
                            title = stringResource(R.string.agent_delete_chat),
                            message = chat.displayTitle(),
                            confirmLabel = stringResource(R.string.tool_delete),
                            onConfirm = { viewModel.deleteChat(chat.id) },
                        ) { onClick ->
                            IconButton(onClick = onClick, enabled = chat.id !in state.runningChatIds) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_delete_chat), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                    Icon(Icons.Default.KeyboardArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}
