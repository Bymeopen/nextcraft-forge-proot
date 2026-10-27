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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.RouteMode
import com.nextcraft.forge.model.RoutingConfig

@Composable
internal fun routeModeLabel(mode: RouteMode): String {
    val labelRes = when (mode) {
        RouteMode.SINGLE -> R.string.route_mode_single
        RouteMode.MANUAL -> R.string.route_mode_manual
        RouteMode.FAILOVER -> R.string.route_mode_failover
        RouteMode.BALANCED -> R.string.route_mode_balanced
    }
    return stringResource(labelRes)
}

private fun resolveModeSubtitleRes(mode: RouteMode): Int = when (mode) {
    RouteMode.SINGLE -> R.string.route_hint_single
    RouteMode.MANUAL -> R.string.route_hint_manual
    RouteMode.FAILOVER -> R.string.route_hint_failover
    RouteMode.BALANCED -> R.string.route_hint_balanced
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatRoutingSheet(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val cfg = state.routing
    val activeProviders = cfg.agents
    val sortedProviders = activeProviders + AgentKind.entries.filterNot { candidate -> candidate in activeProviders }

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
            Text(text = stringResource(R.string.route_sheet_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
            RouteMode.entries.forEach { candidateMode ->
                RadioRow(
                    title = routeModeLabel(candidateMode),
                    subtitle = stringResource(resolveModeSubtitleRes(candidateMode)),
                    selected = cfg.mode == candidateMode,
                    onClick = { viewModel.setRouteMode(candidateMode) },
                    horizontalPadding = 0.dp,
                )
            }
            if (cfg.mode == RouteMode.SINGLE) {
                Text(text = stringResource(R.string.route_single_pick), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                AgentKind.entries.forEach { provider ->
                    val isConfigured = provider in state.readyAgents || provider == state.agentKind
                    val statusRes = if (isConfigured) R.string.route_agent_ready else R.string.route_agent_not_ready
                    RadioRow(
                        title = provider.displayTitle(),
                        subtitle = stringResource(statusRes),
                        selected = state.agentKind == provider,
                        onClick = { viewModel.selectAgent(provider) },
                        horizontalPadding = 0.dp,
                    )
                }
                if (state.activeChatRunning) {
                    Text(text = stringResource(R.string.route_single_busy), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                }
            } else {
                val sectionHeaderRes = if (cfg.mode == RouteMode.MANUAL) R.string.route_agents_manual else R.string.route_agents_priority
                Text(
                    text = stringResource(sectionHeaderRes),
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                )
                sortedProviders.forEachIndexed { position, provider ->
                    val isEnabled = provider in activeProviders
                    val isConfigured = provider in state.readyAgents
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(text = provider.displayTitle(), fontWeight = FontWeight.Medium)
                                Text(
                                    text = stringResource(if (isConfigured) R.string.route_agent_ready else R.string.route_agent_not_ready),
                                    fontSize = ForgeType.secondary,
                                    color = if (isConfigured) palette.onSurfaceVariant else palette.error,
                                )
                            }
                            Switch(
                                checked = isEnabled,
                                onCheckedChange = { toggledOn ->
                                    val updatedPool = if (toggledOn) activeProviders + provider else activeProviders - provider
                                    if (updatedPool.isNotEmpty()) viewModel.setRouteAgents(updatedPool)
                                },
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.End,
                        ) {
                            if (isEnabled && cfg.mode == RouteMode.BALANCED) {
                                val currentWeight = cfg.weightOf(provider)
                                val eligiblePool = activeProviders.filter { it in state.readyAgents }.ifEmpty { activeProviders }
                                val totalWeight = eligiblePool.sumOf { cfg.weightOf(it) }.coerceAtLeast(1)
                                val decreaseDesc = stringResource(R.string.route_weight_less, provider.displayTitle())
                                IconButton(
                                    onClick = { viewModel.setRouteWeight(provider, currentWeight - 1) },
                                    enabled = currentWeight > 0,
                                ) {
                                    Text(
                                        text = "−",
                                        fontSize = ForgeType.screenTitle,
                                        modifier = Modifier.semantics { contentDescription = decreaseDesc },
                                    )
                                }
                                val sharePct = if (provider in eligiblePool) (currentWeight * 100) / totalWeight else 0
                                Text(
                                    text = stringResource(R.string.route_share_percent, sharePct),
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.widthIn(min = 44.dp),
                                    textAlign = TextAlign.Center,
                                )
                                IconButton(
                                    onClick = { viewModel.setRouteWeight(provider, currentWeight + 1) },
                                    enabled = currentWeight < RoutingConfig.MAX_WEIGHT,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = stringResource(R.string.route_weight_more, provider.displayTitle()),
                                    )
                                }
                            }
                            if (isEnabled && position > 0) {
                                IconButton(
                                    onClick = {
                                        val mutablePool = activeProviders.toMutableList()
                                        val currentPos = mutablePool.indexOf(provider)
                                        if (currentPos > 0) {
                                            mutablePool.removeAt(currentPos)
                                            mutablePool.add(currentPos - 1, provider)
                                            viewModel.setRouteAgents(mutablePool)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.KeyboardArrowUp,
                                        contentDescription = stringResource(R.string.route_move_up, provider.displayTitle()),
                                    )
                                }
                            }
                        }
                    }
                }
                if (cfg.mode == RouteMode.BALANCED) {
                    OutlinedButton(onClick = viewModel::resetRouteWeights, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.route_equal_split))
                    }
                }
                KeywordRoutingRulesEditor(routing = cfg, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun KeywordRoutingRulesEditor(
    routing: RoutingConfig,
    viewModel: MainViewModel,
) {
    val palette = MaterialTheme.colorScheme
    var keywordDraft by rememberSaveable { mutableStateOf("") }
    var targetAgentIdx by rememberSaveable { mutableIntStateOf(0) }
    val providerList = AgentKind.entries
    val safeTargetIdx = targetAgentIdx.coerceIn(0, providerList.lastIndex)

    Text(
        text = stringResource(R.string.route_rules_title),
        fontSize = ForgeType.cardTitle,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 8.dp),
    )
    Text(text = stringResource(R.string.route_rules_hint), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)

    routing.rules.forEachIndexed { ruleIdx, rule ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = rule.keywords.joinToString(", "), fontWeight = FontWeight.Medium)
                Text(
                    text = stringResource(R.string.route_rule_goes_to, rule.agent.displayTitle()),
                    fontSize = ForgeType.secondary,
                    color = palette.primary,
                )
            }
            IconButton(onClick = { viewModel.removeRouteRule(ruleIdx) }) {
                Icon(imageVector = Icons.Default.Close, contentDescription = stringResource(R.string.route_rule_delete))
            }
        }
    }

    ForgeTextField(
        kind = ForgeFieldKind.TEXT,
        imeAction = ImeAction.Done,
        value = keywordDraft,
        onValueChange = { typed -> keywordDraft = typed },
        label = { Text(text = stringResource(R.string.route_rule_keywords)) },
        modifier = Modifier.fillMaxWidth(),
    )
    ForgePillTabs(
        items = providerList.map { provider -> provider.shortTitle() },
        selectedIndex = safeTargetIdx,
        onSelect = { chosen -> targetAgentIdx = chosen },
        equalWidth = true,
    )
    ForgePrimaryButton(
        text = stringResource(R.string.route_rule_add),
        onClick = {
            if (viewModel.addRouteRule(keywordDraft, providerList[safeTargetIdx])) {
                keywordDraft = ""
            }
        },
        enabled = keywordDraft.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}
