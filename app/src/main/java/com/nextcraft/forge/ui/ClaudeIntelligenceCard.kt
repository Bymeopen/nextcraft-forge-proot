package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.CLAUDE_EFFORT_LEVELS

/**
 * "Active intelligence" of Claude Code, laid out like Antigravity's: the model tile (readable name with its version
 * number, plus the id the CLI receives) that opens the model list, and the reasoning effort as one row of choices.
 */
@Composable
internal fun ClaudeIntelligenceCard(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onOpenModelPicker: () -> Unit,
    /** True inside another card (the Claude account card): no card of its own and no extra padding. */
    embedded: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val model = state.providerProfile.model.ifBlank { "default" }
    val levels = listOf("") + CLAUDE_EFFORT_LEVELS
    val effortLabels = levels.map { claudeEffortText(it) }
    val effortIndex = levels.indexOf(state.claudeEffort).coerceAtLeast(0)

    val body: @Composable ColumnScope.() -> Unit = {
            Text(
                text = stringResource(R.string.agent_intelligence_title),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.agent_intelligence_active, claudeModelLabel(model), effortLabels[effortIndex]),
                fontSize = ForgeType.secondary,
                color = tokens.accentText,
                fontWeight = FontWeight.Medium,
            )
            // Model tile: tap to open the model list.
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = tokens.cardElevatedColor,
                border = tokens.cardBorderStroke,
                modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpenModelPicker),
            ) {
                Row(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(10.dp), color = tokens.cardContainerColor, modifier = Modifier.size(36.dp)) {
                        Box(contentAlignment = Alignment.Center) { ForgeIcon(glyph = ForgeGlyph.SPARK, tint = scheme.primary, size = 20.dp) }
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = claudeModelLabel(model), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                        Text(model, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                    }
                    Chevron(expanded = false)
                }
            }
            // Stacked, not side by side: the long hint used to squeeze the label into two ragged lines.
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(R.string.agent_reasoning_depth),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                )
                Text(text = effortLabels[effortIndex], fontSize = ForgeType.secondary, color = scheme.primary)
            }
            ForgePillTabs(
                items = effortLabels,
                selectedIndex = effortIndex,
                // Saved right away: there is no separate Save button in this layout.
                onSelect = { viewModel.setClaudeEffort(levels[it]) },
            )
            Text(
                text = stringResource(R.string.agent_claude_effort_hint),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
            )
    }
    if (embedded) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
    } else {
        ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(ForgeSpace.cardInner), verticalArrangement = Arrangement.spacedBy(12.dp), content = body)
        }
    }
}

/** Short effort name; "" is the model default. */
@Composable
internal fun claudeEffortText(level: String): String = stringResource(
    when (level) {
        "low" -> R.string.agent_claude_effort_low
        "medium" -> R.string.agent_claude_effort_medium
        "high" -> R.string.agent_claude_effort_high
        "xhigh" -> R.string.agent_claude_effort_xhigh
        "max" -> R.string.agent_claude_effort_max
        else -> R.string.agent_claude_effort_auto
    },
)

/**
 * Readable name of a Claude Code model id, with its version: "claude-opus-5-5" -> "Claude Opus 5.5". Claude Code's own
 * aliases keep their meaning ("sonnet" is the latest Sonnet) and the 1M-context variants say so.
 */
internal fun claudeModelLabel(id: String): String {
    val oneMillion = id.endsWith("[1m]", ignoreCase = true)
    val base = id.removeSuffix("[1m]").removeSuffix("[1M]")
    val name = when (base.lowercase()) {
        "", "default" -> return "Default"
        "sonnet" -> "Claude Sonnet (latest)"
        "opus" -> "Claude Opus (latest)"
        "haiku" -> "Claude Haiku (latest)"
        "opusplan" -> return "Opus Plan (Opus plans, Sonnet builds)"
        else -> antigravityModelLabel(base)
    }
    return if (oneMillion) "$name · 1M" else name
}
