package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.RoutingConfig

/** Short label of a routing mode, shown on the composer chip. */
@Composable
internal fun routeModeLabel(mode: RouteMode): String = stringResource(
    when (mode) {
        RouteMode.SINGLE -> R.string.route_mode_single
        RouteMode.MANUAL -> R.string.route_mode_manual
        RouteMode.FAILOVER -> R.string.route_mode_failover
        RouteMode.BALANCED -> R.string.route_mode_balanced
    },
)

private fun routeModeHint(mode: RouteMode): Int = when (mode) {
    RouteMode.SINGLE -> R.string.route_hint_single
    RouteMode.MANUAL -> R.string.route_hint_manual
    RouteMode.FAILOVER -> R.string.route_hint_failover
    RouteMode.BALANCED -> R.string.route_hint_balanced
}

/**
 * Chat settings for using several agents in one conversation: the mode (single, manual with @agent tags, failover,
 * balanced) and which agents take part, in priority order. Opened from the composer chip next to the model chip.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRoutingSheet(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val routing = state.routing
    // Ordered list: enabled agents in priority order first, then the ones that are off.
    val enabled = routing.agents
    val ordered = enabled + AgentKind.entries.filterNot { it in enabled }
    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ForgeSpace.card)
                .padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.route_sheet_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            RouteMode.entries.forEach { mode ->
                RadioRow(
                    title = routeModeLabel(mode),
                    subtitle = stringResource(routeModeHint(mode)),
                    selected = routing.mode == mode,
                    onClick = { viewModel.setRouteMode(mode) },
                    horizontalPadding = 0.dp,
                )
            }
            if (routing.mode == RouteMode.SINGLE) {
                // Single: exactly one provider, chosen here (the same choice as in the model sheet).
                Text(stringResource(R.string.route_single_pick), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                AgentKind.entries.forEach { agent ->
                    val ready = agent in state.readyAgents || agent == state.agentKind
                    RadioRow(
                        title = agent.displayTitle(),
                        subtitle = stringResource(if (ready) R.string.route_agent_ready else R.string.route_agent_not_ready),
                        selected = state.agentKind == agent,
                        onClick = { viewModel.selectAgent(agent) },
                        horizontalPadding = 0.dp,
                    )
                }
                if (state.activeChatRunning) {
                    // selectAgent refuses while the open chat's turn runs; other chats and projects can keep running.
                    Text(stringResource(R.string.route_single_busy), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                }
            }
            if (routing.mode != RouteMode.SINGLE) {
                Text(
                    stringResource(if (routing.mode == RouteMode.MANUAL) R.string.route_agents_manual else R.string.route_agents_priority),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                )
                ordered.forEachIndexed { index, agent ->
                    val on = agent in enabled
                    val ready = agent in state.readyAgents
                    // Name and switch on one line, the weight/priority controls below, so nothing is squeezed.
                    Column(modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(agent.displayTitle(), fontWeight = FontWeight.Medium)
                            Text(
                                text = stringResource(if (ready) R.string.route_agent_ready else R.string.route_agent_not_ready),
                                fontSize = ForgeType.secondary,
                                color = if (ready) scheme.onSurfaceVariant else scheme.error,
                            )
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { checked ->
                                // At least one agent stays on, otherwise Failover and Balanced would have nobody to use.
                                val next = if (checked) enabled + agent else enabled - agent
                                if (next.isNotEmpty()) viewModel.setRouteAgents(next)
                            },
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.End) {
                        if (on && routing.mode == RouteMode.BALANCED) {
                            val weight = routing.weightOf(agent)
                            // The share is among the agents that can actually answer; the others get nothing until they are set up.
                            val pool = enabled.filter { it in state.readyAgents }.ifEmpty { enabled }
                            val total = pool.sumOf { routing.weightOf(it) }.takeIf { it > 0 } ?: 1
                            val lessLabel = stringResource(R.string.route_weight_less, agent.displayTitle())
                            IconButton(onClick = { viewModel.setRouteWeight(agent, weight - 1) }, enabled = weight > 0) {
                                Text("−", fontSize = ForgeType.screenTitle, modifier = Modifier.semantics { contentDescription = lessLabel })
                            }
                            Text(
                                text = stringResource(R.string.route_share_percent, if (agent in pool) weight * 100 / total else 0),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.widthIn(min = 44.dp),
                                textAlign = TextAlign.Center,
                            )
                            IconButton(onClick = { viewModel.setRouteWeight(agent, weight + 1) }, enabled = weight < RoutingConfig.MAX_WEIGHT) {
                                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.route_weight_more, agent.displayTitle()))
                            }
                        }
                        if (on && index > 0) {
                            IconButton(onClick = {
                                val moved = enabled.toMutableList()
                                val at = moved.indexOf(agent)
                                if (at > 0) {
                                    moved.removeAt(at)
                                    moved.add(at - 1, agent)
                                    viewModel.setRouteAgents(moved)
                                }
                            }) {
                                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.route_move_up, agent.displayTitle()))
                            }
                        }
                    }
                    }
                }
                if (routing.mode == RouteMode.BALANCED) {
                    OutlinedButton(onClick = viewModel::resetRouteWeights, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.route_equal_split))
                    }
                }
                RouteRulesSection(routing = routing, viewModel = viewModel)
            }
        }
    }
}

/** Rules on the content of the prompt: "these words go to this agent". Checked after an @agent tag, before the mode. */
@Composable
private fun RouteRulesSection(routing: RoutingConfig, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    var words by rememberSaveable { mutableStateOf("") }
    var agentIndex by rememberSaveable { mutableIntStateOf(0) }
    val agents = AgentKind.entries
    Text(stringResource(R.string.route_rules_title), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
    Text(stringResource(R.string.route_rules_hint), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
    routing.rules.forEachIndexed { index, rule ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(rule.keywords.joinToString(", "), fontWeight = FontWeight.Medium)
                Text(stringResource(R.string.route_rule_goes_to, rule.agent.displayTitle()), fontSize = ForgeType.secondary, color = scheme.primary)
            }
            IconButton(onClick = { viewModel.removeRouteRule(index) }) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.route_rule_delete))
            }
        }
    }
    ForgeTextField(
        kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Done,
        value = words,
        onValueChange = { words = it },
        label = { Text(stringResource(R.string.route_rule_keywords)) },
        modifier = Modifier.fillMaxWidth(),
    )
    ForgePillTabs(
        items = agents.map { it.shortTitle() },
        selectedIndex = agentIndex,
        onSelect = { agentIndex = it },
        equalWidth = true, // 3 providers fit side by side; scrolling cut "Antigravity" at the edge
    )
    ForgePrimaryButton(
        text = stringResource(R.string.route_rule_add),
        onClick = { if (viewModel.addRouteRule(words, agents[agentIndex])) words = "" },
        enabled = words.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}
