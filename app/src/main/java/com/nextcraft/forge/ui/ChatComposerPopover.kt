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
internal fun ComposerAutocompletePopover(
    promptInput: String,
    workspaceFiles: List<com.nextcraft.forge.model.WorkspaceEntry>,
    readyAgents: Set<com.nextcraft.forge.model.AgentKind>,
    connectors: List<com.nextcraft.forge.runtime.McpConnector> = emptyList(),
    onOpenConnectors: (() -> Unit)? = null,
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

    // Resolved outside remember so the catalogue follows the active locale; passed in as a key.
    val res = LocalResources.current
    val suggestions = remember(slashMatch, atMatch, workspaceFiles, readyAgents, connectors, res) {
        when {
            slashMatch != null -> {
                val commands = listOf(
                    ComposerSuggestion("cmd-mcp", "/mcp", res.getString(R.string.composer_cmd_mcp), ForgeGlyph.LINK, res.getString(R.string.composer_badge_mcp), "/mcp", executeImmediately = true),
                    ComposerSuggestion("cmd-build", "/build", res.getString(R.string.composer_cmd_build), ForgeGlyph.BUILD, res.getString(R.string.composer_badge_action), "/build", executeImmediately = true),
                    ComposerSuggestion("cmd-review", "/review", res.getString(R.string.composer_cmd_review), ForgeGlyph.SPARK, res.getString(R.string.composer_badge_swarm), "/review", executeImmediately = true),
                    ComposerSuggestion("cmd-memory", "/memory", res.getString(R.string.composer_cmd_memory), ForgeGlyph.FILE, res.getString(R.string.composer_badge_memory), "/memory", executeImmediately = true),
                    ComposerSuggestion("cmd-compact", "/compact", res.getString(R.string.composer_cmd_compact), ForgeGlyph.SPARK, res.getString(R.string.composer_badge_context), "/compact", executeImmediately = true),
                    ComposerSuggestion("cmd-diff", "/diff", res.getString(R.string.composer_cmd_diff), ForgeGlyph.CODE, res.getString(R.string.composer_badge_pane), "/diff", executeImmediately = true),
                    ComposerSuggestion("cmd-branch", "/branch", res.getString(R.string.composer_cmd_branch), ForgeGlyph.SPARK, res.getString(R.string.composer_badge_git), "/branch", executeImmediately = true),
                    ComposerSuggestion("cmd-loop", "/loop 30m ", res.getString(R.string.composer_cmd_loop), ForgeGlyph.SPARK, res.getString(R.string.composer_badge_timer), "/loop 30m "),
                    ComposerSuggestion("cmd-loop-off", "/loop off", res.getString(R.string.composer_cmd_loop_off), ForgeGlyph.TRASH, res.getString(R.string.composer_badge_timer), "/loop off", executeImmediately = true),
                    ComposerSuggestion("cmd-terminal", "/terminal", res.getString(R.string.composer_cmd_terminal), ForgeGlyph.TERMINAL, res.getString(R.string.composer_badge_pane), "/terminal", executeImmediately = true),
                    ComposerSuggestion("cmd-clear", "/clear", res.getString(R.string.composer_cmd_clear), ForgeGlyph.TRASH, res.getString(R.string.composer_badge_chat), "/clear", executeImmediately = true),
                )
                commands.filter { it.title.removePrefix("/").startsWith(slashMatch) || it.subtitle.lowercase().contains(slashMatch) }
            }
            atMatch != null -> {
                val (atIndex, query) = atMatch
                val list = mutableListOf<ComposerSuggestion>()
                val enabledMcp = connectors.filter { it.enabled }
                for (mcp in enabledMcp) {
                    val tag = "mcp:${mcp.id}"
                    if (query.isEmpty() ||
                        "mcp".startsWith(query) ||
                        tag.startsWith(query) ||
                        mcp.id.startsWith(query) ||
                        mcp.name.lowercase().contains(query)
                    ) {
                        list += ComposerSuggestion(
                            id = "mcp-${mcp.id}",
                            title = "@$tag",
                            subtitle = "Use ${mcp.name} MCP tools (${mcp.key})",
                            glyph = ForgeGlyph.LINK,
                            badge = "MCP",
                            insertText = "Using the ${mcp.name} MCP server (`${mcp.key}`), ",
                        )
                    }
                }
                if (query.isEmpty() || "mcp".startsWith(query) || "connectors".startsWith(query)) {
                    list += ComposerSuggestion(
                        id = "mcp-manage",
                        title = "@mcp:settings",
                        subtitle = "Configure or add MCP servers (GitHub, Figma, Context7, Design…)",
                        glyph = ForgeGlyph.LINK,
                        badge = "Setup",
                        insertText = "",
                    )
                }
                if (atIndex == 0) {
                    val agentEntries = listOf(
                        Triple("claude", com.nextcraft.forge.model.AgentKind.CLAUDE_CODE, res.getString(R.string.composer_agent_claude)),
                        Triple("agy", com.nextcraft.forge.model.AgentKind.ANTIGRAVITY, res.getString(R.string.composer_agent_agy)),
                        Triple("deepseek", com.nextcraft.forge.model.AgentKind.DEEPSEEK_HARNESS, res.getString(R.string.composer_agent_deepseek)),
                    )
                    for ((tag, kind, desc) in agentEntries) {
                        if (tag.startsWith(query) || kind.title.lowercase().contains(query)) {
                            val isReady = readyAgents.isEmpty() || kind in readyAgents
                            list += ComposerSuggestion(
                                id = "agent-$tag",
                                title = "@$tag",
                                subtitle = desc,
                                glyph = ForgeGlyph.SPARK,
                                badge = res.getString(if (isReady) R.string.composer_badge_agent else R.string.composer_badge_setup),
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
                            badge = res.getString(R.string.composer_badge_file),
                            insertText = "${file.path} ",
                        )
                    }
                list.addAll(matchingFiles)
                list.take(8)
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
                                if (item.id == "mcp-manage") {
                                    val (atIdx, _) = atMatch ?: (0 to "")
                                    onReplacePrompt(promptInput.substring(0, atIdx))
                                    onOpenConnectors?.invoke()
                                } else if (item.executeImmediately && onExecuteSlash != null) {
                                    onReplacePrompt("")
                                    if (item.id == "cmd-mcp" && onOpenConnectors != null) {
                                        onOpenConnectors()
                                    } else {
                                        onExecuteSlash(item.insertText)
                                    }
                                } else if (slashMatch != null) {
                                    onReplacePrompt(item.insertText)
                                } else if (atMatch != null) {
                                    val (atIdx, _) = atMatch
                                    val prefix = promptInput.substring(0, atIdx)
                                    val replacement = when {
                                        item.id.startsWith("agent-") -> {
                                            onSelectAgentTag?.invoke()
                                            item.insertText
                                        }
                                        item.id.startsWith("mcp-") -> item.insertText
                                        else -> "@${item.insertText}"
                                    }
                                    onReplacePrompt(prefix + replacement)
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgeIcon(
                            glyph = item.glyph,
                            tint = if (item.id.startsWith("agent-") || item.id.startsWith("cmd-") || item.id.startsWith("mcp-")) scheme.primary else scheme.onSurfaceVariant,
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
