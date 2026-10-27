@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.runtime.BranchStatus
import com.nextcraft.forge.runtime.CLAUDE_EFFORT_LEVELS

/*
 * Claude-style chat chrome: a header with a menu button (chat drawer on the left) and actions on the right, a usage
 * sheet, and a model/effort sheet opened from the chip in the composer.
 */

/** Header of the conversation: menu, chat title with project, a spinner while this chat works, changes and a menu. */
@Composable
internal fun ChatTopBar(
    state: ForgeUiState,
    onOpenDrawer: () -> Unit,
    onOpenChanges: () -> Unit,
    onRename: () -> Unit,
    onUsage: () -> Unit,
    onSchedule: () -> Unit,
    onClear: () -> Unit,
    onDelete: () -> Unit,
    onBranch: () -> Unit,
    onEnableBranch: () -> Unit,
    onOpenRea: () -> Unit,
    onOpenMemory: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val activeChat = state.chats.firstOrNull { it.id == state.activeChatId }
    val pending = state.changedItems.count { it.accepted == null }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onOpenDrawer) {
            Icon(Icons.Default.Menu, contentDescription = stringResource(R.string.agent_project_chats), tint = scheme.onSurface)
        }
        Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
            // The spinner sits right after the title while this chat works (it replaces the sliding bar under the header).
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = activeChat?.displayTitle() ?: stringResource(R.string.agent_title),
                    modifier = Modifier.weight(1f, fill = false).forgeHeading(),
                    fontSize = ForgeType.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.activeChatRunning) {
                    CircularProgressIndicator(modifier = Modifier.padding(start = 8.dp).size(14.dp), strokeWidth = 2.dp)
                }
            }
            val activeAgentLabel = primaryAgent(state).displayTitle()
            val modeSuffix = if (state.routing.mode != com.nextcraft.forge.model.RouteMode.SINGLE) {
                routeModeLabel(state.routing.mode)
            } else null
            val branchSuffix = if (activeChat?.isolated == true) stringResource(R.string.branch_title) else null
            Text(
                text = listOfNotNull(state.activeProject?.name, activeAgentLabel, modeSuffix, branchSuffix).joinToString(" · "),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Changes made by the agent: opens the workspace diff.
        IconButton(onClick = onOpenChanges) {
            Box(contentAlignment = Alignment.TopEnd) {
                ForgeIcon(glyph = ForgeGlyph.CODE, tint = scheme.onSurface, size = 22.dp, description = stringResource(R.string.chat_menu_changes))
                if (pending > 0) {
                    Surface(shape = RoundedCornerShape(50), color = scheme.primary, modifier = Modifier.offset(x = 10.dp, y = (-8).dp)) {
                        Text(
                            text = pending.toString(),
                            fontSize = ForgeType.caption,
                            color = scheme.onPrimary,
                            modifier = Modifier.padding(horizontal = 5.dp),
                        )
                    }
                }
            }
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.chat_menu_more), tint = scheme.onSurface)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_rename)) }, onClick = { menuOpen = false; onRename() })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_memory)) }, onClick = { menuOpen = false; onOpenMemory() })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_usage)) }, onClick = { menuOpen = false; onUsage() })
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_schedule)) }, onClick = { menuOpen = false; onSchedule() })
                if (activeChat?.isolated == true) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.branch_menu_open)) }, onClick = { menuOpen = false; onBranch() })
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.branch_menu_enable)) },
                        enabled = !state.activeChatRunning,
                        onClick = { menuOpen = false; onEnableBranch() },
                    )
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.chat_menu_rea)) }, onClick = { menuOpen = false; onOpenRea() })
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_menu_clear)) },
                    enabled = !state.activeChatRunning,
                    onClick = { menuOpen = false; confirmClear = true },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.agent_delete_chat)) },
                    enabled = state.chats.size > 1 && !state.activeChatRunning,
                    onClick = { menuOpen = false; confirmDelete = true },
                )
            }
        }
    }
    if (confirmClear) {
        val tokens = LocalForgeThemeTokens.current
        ForgeAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.chat_menu_clear), fontWeight = FontWeight.SemiBold) },
            text = { Text(activeChat?.displayTitle() ?: stringResource(R.string.agent_title)) },
            confirmButton = {
                ForgeTextButton(onClick = { confirmClear = false; onClear() }) {
                    Text(stringResource(R.string.chat_menu_clear), color = tokens.dangerText)
                }
            },
            dismissButton = {
                ForgeTextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }
    if (confirmDelete) {
        val tokens = LocalForgeThemeTokens.current
        ForgeAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.agent_delete_chat), fontWeight = FontWeight.SemiBold) },
            text = { Text(activeChat?.displayTitle() ?: stringResource(R.string.agent_title)) },
            confirmButton = {
                ForgeTextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.tool_delete), color = tokens.dangerText)
                }
            },
            dismissButton = {
                ForgeTextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }
}

/** Left drawer: the project's chats, newest first, with a spinner on the ones that are working. */
@Composable
internal fun ChatDrawerContent(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onClose: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxHeight().padding(horizontal = 12.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = state.activeProject?.name ?: stringResource(R.string.agent_title),
            fontSize = ForgeType.screenTitle,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // 12 (column) + 8 here = 20dp, the same left edge as the status slot in every row below.
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        ForgePrimaryButton(
            text = stringResource(R.string.agent_new_chat),
            onClick = {
                viewModel.createChat()
                onClose()
            },
            modifier = Modifier.fillMaxWidth(),
        )
        val sortedChats = remember(state.chats) { state.chats.sortedByDescending { it.updatedAtMillis } }
        LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(sortedChats, key = { it.id }) { chat ->
                ChatDrawerRow(
                    chat = chat,
                    current = chat.id == state.activeChatId,
                    running = chat.id in state.runningChatIds,
                    canDelete = state.chats.size > 1 && chat.id !in state.runningChatIds,
                    onOpen = {
                        viewModel.selectChat(chat.id)
                        onClose()
                    },
                    onDelete = { viewModel.deleteChat(chat.id) },
                )
            }
        }
    }
}

@Composable
internal fun ChatDrawerRow(
    chat: ProjectChat,
    current: Boolean,
    running: Boolean,
    canDelete: Boolean,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 8.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Spinner while working, a dot otherwise (accent for the open chat), so the slot is never an unexplained blank.
        Box(modifier = Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (running) CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            else Box(
                modifier = Modifier
                    .size(if (current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(if (current) scheme.primary else scheme.onSurfaceVariant.copy(alpha = 0.45f)),
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chat.displayTitle(),
                fontSize = ForgeType.rowTitle,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Medium,
                color = if (current) scheme.primary else scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = formatRelativeTime(chat.updatedAtMillis), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
        }
        if (canDelete) {
            ConfirmedAction(
                title = stringResource(R.string.agent_delete_chat),
                message = chat.displayTitle(),
                confirmLabel = stringResource(R.string.tool_delete),
                onConfirm = onDelete,
            ) { onClick ->
                IconButton(onClick = onClick) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.agent_delete_chat), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
internal fun RenameChatDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_rename_title), fontWeight = FontWeight.SemiBold) },
        text = {
            ForgeTextField(
                value = text,
                onValueChange = { text = it.take(60) },
                kind = ForgeFieldKind.NAME,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            ForgeTextButton(onClick = { onSave(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.chat_rename_save)) }
        },
        dismissButton = { ForgeTextButton(onClick = onDismiss) { Text(stringResource(R.string.projects_cancel)) } },
    )
}

/** "Usage": how full the model's context window is for this chat. Plan limits live in the Claude account. */
@Composable
internal fun ChatUsageSheet(state: ForgeUiState, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val msgTokens = estimateContextTokens(state.messages)
    // Include estimated system prompt, AGENTS.md rules, and active MCP/skill schema overhead (~4,200 base tokens + 350 per enabled connector/skill)
    val systemOverhead = 4_200 + (state.connectors.count { it.enabled } + state.skills.size) * 350
    val used = msgTokens + systemOverhead
    val activeAgent = primaryAgent(state)
    val activeModel = when (activeAgent) {
        AgentKind.ANTIGRAVITY -> state.antigravityModel
        else -> profileOf(state, activeAgent).model
    }
    val limit = contextWindowTokens(activeModel)
    val fraction = (used.toFloat() / limit).coerceIn(0f, 1f)
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.chat_usage_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            Surface(shape = RoundedCornerShape(16.dp), color = tokens.cardElevatedColor, border = tokens.cardBorderStroke, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.chat_usage_context), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            stringResource(R.string.chat_usage_percent, (fraction * 100).toInt()),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        stringResource(R.string.chat_usage_used, formatTokenCount(used), formatTokenCount(limit)),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
            Text(stringResource(R.string.chat_usage_note), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
    }
}

/** The agent a plain message goes to: the selected one in Single mode, otherwise the first switched-on one. */
internal fun primaryAgent(state: ForgeUiState): AgentKind =
    com.nextcraft.forge.model.AgentRouter.primaryAgent(state.routing, state.agentKind, state.readyAgents)

private fun profileOf(state: ForgeUiState, agent: AgentKind) =
    if (agent == state.agentKind) state.providerProfile else state.agentProfiles[agent] ?: state.providerProfile

/** Short model (and effort) text for the composer chip, e.g. "Fable 5.1 Low". */
@Composable
internal fun chatModelChipLabel(state: ForgeUiState, agent: AgentKind = state.agentKind): String = when (agent) {
    AgentKind.CLAUDE_CODE -> {
        val model = claudeModelLabel(profileOf(state, agent).model.ifBlank { "default" })
            .removePrefix("Claude ").removeSuffix(" (latest)")
        val effort = state.claudeEffort.takeIf { it.isNotBlank() }?.let { claudeEffortText(it) }
        listOfNotNull(model, effort).joinToString(" ")
    }
    AgentKind.ANTIGRAVITY -> state.antigravityModel.takeIf { it.isNotBlank() }?.let(::antigravityModelLabel) ?: "Antigravity"
    AgentKind.DEEPSEEK_HARNESS -> profileOf(state, agent).model.ifBlank { "DeepSeek" }
}

/** Model list and effort row opened from the composer chip. */
@Composable
internal fun ChatModelSheet(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    // With several agents in a chat, the sheet edits the agent picked in the tabs, not only the selected one.
    val multi = state.routing.mode != com.nextcraft.forge.model.RouteMode.SINGLE
    var sheetAgent by remember { mutableStateOf(primaryAgent(state)) }
    val agent = if (multi) sheetAgent else state.agentKind
    val profile = if (agent == state.agentKind) state.providerProfile else state.agentProfiles[agent] ?: state.providerProfile
    val discovered = if (agent == state.agentKind) state.discoveredModels else emptyList()
    val activeAgentLabel = primaryAgent(state).displayTitle()
    val discoveryAgent = if (multi) sheetAgent else state.agentKind
    val discoveryProfile = if (discoveryAgent == state.agentKind) state.providerProfile else state.agentProfiles[discoveryAgent] ?: state.providerProfile
    androidx.compose.runtime.LaunchedEffect(agent) {
        if (agent == AgentKind.ANTIGRAVITY && state.antigravityModels.isEmpty() && !state.antigravityModelsLoading) {
            viewModel.refreshAntigravityModels()
        }
    }
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chat_model_sheet_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                when {
                    discoveryAgent == AgentKind.ANTIGRAVITY -> ForgeTextButton(
                        onClick = viewModel::refreshAntigravityModels,
                        enabled = !state.antigravityModelsLoading,
                    ) { Text(stringResource(R.string.agent_sync)) }
                    discoveryAgent == state.agentKind && discoveryProfile.kind != com.nextcraft.forge.model.ProviderKind.CLAUDE -> ForgeTextButton(
                        onClick = viewModel::discoverProviderModels,
                        enabled = !state.apiBusy,
                    ) { Text(stringResource(R.string.settings_discover_models)) }
                }
            }
            if (state.antigravityModelsLoading || (discoveryAgent == state.agentKind && state.apiBusy)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (multi) {
                // One tab per provider: its own models and, for Claude and Antigravity, its own reasoning effort.
                val agents = AgentKind.entries
                ForgePillTabs(
                    items = agents.map { it.shortTitle() },
                    selectedIndex = agents.indexOf(agent).coerceAtLeast(0),
                    onSelect = { sheetAgent = agents[it] },
                    equalWidth = true, // 3 providers fit side by side; scrolling cut "Antigravity" at the edge
                )
            }
            when (agent) {
                AgentKind.CLAUDE_CODE -> {
                    val levels = listOf("") + CLAUDE_EFFORT_LEVELS
                    Text(stringResource(R.string.agent_reasoning_depth), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    ForgePillTabs(
                        items = levels.map { claudeEffortText(it) },
                        selectedIndex = levels.indexOf(state.claudeEffort).coerceAtLeast(0),
                        onSelect = { viewModel.setClaudeEffort(levels[it]) },
                    )
                    ModelList(
                        options = providerModelOptions(profile.kind, discovered, profile.model),
                        selectedId = profile.model,
                        onSelect = { id -> viewModel.setAgentModel(agent, id) },
                    )
                }
                AgentKind.ANTIGRAVITY -> {
                    val efforts = listOf("low", "medium", "high")
                    Text(stringResource(R.string.agent_reasoning_depth), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    ForgePillTabs(
                        items = listOf(stringResource(R.string.agent_effort_low), stringResource(R.string.agent_effort_medium), stringResource(R.string.agent_effort_high)),
                        selectedIndex = efforts.indexOf(state.antigravityEffort).coerceAtLeast(0),
                        onSelect = { viewModel.setAntigravityConfig(state.antigravityModel, efforts[it]) },
                    )
                    ModelList(
                        options = antigravityModelOptions(state.antigravityModels, state.antigravityModel),
                        selectedId = state.antigravityModel,
                        onSelect = { id -> viewModel.setAgentModel(AgentKind.ANTIGRAVITY, id) },
                    )
                }
                AgentKind.DEEPSEEK_HARNESS -> ModelList(
                    options = providerModelOptions(profile.kind, discovered, profile.model),
                    selectedId = profile.model,
                    onSelect = { id -> viewModel.setAgentModel(agent, id) },
                )
            }
        }
    }
}

@Composable
private fun ModelList(options: List<ModelOption>, selectedId: String, onSelect: (String) -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
        items(options, key = { it.id }) { option ->
            RadioRow(
                title = option.labelRes?.let { stringResource(it) } ?: option.label,
                subtitle = option.id,
                mono = true,
                selected = option.id == selectedId,
                onClick = { onSelect(option.id) },
                horizontalPadding = 0.dp,
            )
        }
    }
}

/** Rough token count of a chat (about four characters per token); the real count is only known to the model. */
internal fun estimateContextTokens(messages: List<ChatMessage>): Int =
    messages.sumOf { message -> message.text.length + message.workItems.sumOf { it.title.length + it.detail.length } } / 4

/** The window assumed for a model id: the `[1m]` and Gemini Pro/Argon variants have one million tokens, everything else 200k. */
internal fun contextWindowTokens(model: String): Int =
    if (model.contains("[1m]", ignoreCase = true) ||
        model.contains("gemini", ignoreCase = true) ||
        model.contains("argon", ignoreCase = true)
    ) 1_000_000 else 200_000

/** "442.8k", "1M", "200k": compact token counts for the usage sheet. */
internal fun formatTokenCount(tokens: Int): String = when {
    tokens >= 1_000_000 -> if (tokens % 1_000_000 == 0) "${tokens / 1_000_000}M" else "%.1fM".format(java.util.Locale.ROOT, tokens / 1_000_000.0)
    tokens >= 1_000 -> if (tokens % 1_000 == 0) "${tokens / 1_000}k" else "%.1fk".format(java.util.Locale.ROOT, tokens / 1_000.0)
    else -> tokens.toString()
}

/** Under the last answer of a chat that has its own branch: what the branch holds, with a way into the branch sheet. */
@Composable
internal fun BranchSummaryCard(status: BranchStatus, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = tokens.cardElevatedColor,
        border = tokens.cardBorderStroke,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen),
    ) {
        Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.branch_card, status.commits, status.filesChanged),
                    fontSize = ForgeType.rowTitle,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = stringResource(R.string.branch_card_lines, status.additions, status.deletions),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                )
            }
            ForgeTextButton(onClick = onOpen) { Text(stringResource(R.string.branch_open)) }
        }
    }
}

/** The chat's branch: what it changed, how far it is from the project, and merge / update / discard. */
@Composable
internal fun BranchSheet(state: ForgeUiState, viewModel: MainViewModel, chatId: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val status = state.branchStatus[chatId] ?: BranchStatus()
    val busy = chatId in state.branchBusy
    val running = chatId in state.runningChatIds
    val diff = state.branchDiff[chatId]
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.padding(horizontal = ForgeSpace.card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.branch_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = tokens.cardElevatedColor, border = tokens.cardBorderStroke, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            stringResource(R.string.branch_summary, status.commits, status.filesChanged, status.additions, status.deletions),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.Medium,
                        )
                        if (status.uncommitted > 0) {
                            Text(stringResource(R.string.branch_uncommitted, status.uncommitted), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                        }
                        if (status.behind > 0) {
                            Text(stringResource(R.string.branch_behind, status.behind), fontSize = ForgeType.secondary, color = tokens.accentText)
                        }
                        if (status.commits == 0 && status.uncommitted == 0) {
                            Text(stringResource(R.string.branch_clean), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (status.conflicts.isNotEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = scheme.error.copy(alpha = 0.10f), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.branch_conflicts_title), fontWeight = FontWeight.SemiBold, color = scheme.error)
                            status.conflicts.forEach { Text(it, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace) }
                            ForgePrimaryButton(
                                text = stringResource(R.string.branch_ask_agent),
                                onClick = {
                                    onDismiss()
                                    viewModel.askAgentToResolveConflicts(chatId)
                                },
                                enabled = !busy && !running,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
            if (status.recent.isNotEmpty()) {
                item { Text(stringResource(R.string.branch_commits_title), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant) }
                items(status.recent, key = { it.hash }) { commit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(commit.hash, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = tokens.accentText)
                        Text(commit.subject, fontSize = ForgeType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            item {
                var commitMessage by rememberSaveable(chatId) { mutableStateOf("") }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(
                        value = commitMessage,
                        onValueChange = { commitMessage = it.take(100) },
                        label = { Text(stringResource(R.string.branch_commit_hint)) },
                        singleLine = true,
                        enabled = !busy && !running && status.conflicts.isEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.commitChatBranch(chatId, commitMessage); commitMessage = "" },
                        enabled = !busy && !running && status.conflicts.isEmpty() && status.uncommitted > 0 && commitMessage.isNotBlank(),
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.branch_commit)) }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.loadBranchDiff(chatId) },
                        enabled = !busy,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.branch_diff)) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = { viewModel.updateChatBranchFromMain(chatId) },
                        enabled = !busy && !running && status.behind > 0 && status.conflicts.isEmpty(),
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.branch_update)) }
                    ForgePrimaryButton(
                        text = stringResource(R.string.branch_merge),
                        onClick = { viewModel.mergeChatBranch(chatId) },
                        enabled = !busy && !running && status.conflicts.isEmpty() && (status.commits > 0 || status.uncommitted > 0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    androidx.compose.material3.OutlinedButton(
                        onClick = { confirmDiscard = true },
                        enabled = !busy && !running,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(stringResource(R.string.branch_discard), color = scheme.error) }
                    ForgeTextButton(
                        onClick = { viewModel.disableChatBranch(chatId); onDismiss() },
                        enabled = !busy && !running,
                    ) { Text(stringResource(R.string.branch_use_project)) }
                }
            }
            if (!diff.isNullOrBlank()) {
                item {
                    Surface(shape = RoundedCornerShape(12.dp), color = tokens.cardElevatedColor, modifier = Modifier.fillMaxWidth()) {
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Text(
                                text = diff,
                                fontFamily = FontFamily.Monospace,
                                fontSize = ForgeType.caption,
                                modifier = Modifier
                                    .padding(12.dp)
                                    .heightIn(max = 360.dp)
                                    .verticalScroll(rememberScrollState()),
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmDiscard) {
        ForgeAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.branch_discard), fontWeight = FontWeight.SemiBold) },
            text = { Text(stringResource(R.string.branch_discard_confirm)) },
            confirmButton = {
                ForgeTextButton(onClick = { confirmDiscard = false; viewModel.discardChatBranch(chatId); onDismiss() }) {
                    Text(stringResource(R.string.branch_discard), color = scheme.error)
                }
            },
            dismissButton = { ForgeTextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
}
