@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.providersForAgent
import com.nextcraft.forge.runtime.RuntimeSetupStatus

/**
 * The Agent tab: which coding agent is active and how it reaches its model. Agent pill tabs, an
 * install card when the agent is missing, then Access mode / Endpoint / Model & access (or the
 * Google account card for Antigravity) and Runtime updates. Conversations live under Projects.
 */
@Composable
fun AgentConfigScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val agyAuth by viewModel.antigravityAuth.state.collectAsState()
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme

    var baseUrlInput by rememberSaveable(state.agentKind, state.providerProfile.kind, state.providerProfile.baseUrl) { mutableStateOf(state.providerProfile.baseUrl) }
    var modelInput by rememberSaveable(state.agentKind, state.providerProfile.kind, state.providerProfile.model) { mutableStateOf(state.providerProfile.model) }
    var openRouterOrderInput by rememberSaveable(state.agentKind, state.providerProfile.kind, state.providerProfile.openRouterProviderOrder) { mutableStateOf(state.providerProfile.openRouterProviderOrder) }
    var keyLabelInput by rememberSaveable { mutableStateOf("") }
    // Secrets use plain remember: rememberSaveable would write them into the saved-instance Bundle.
    var keySecretInput by remember { mutableStateOf("") }
    var agyModelInput by rememberSaveable(state.antigravityModel) { mutableStateOf(state.antigravityModel) }
    var agyEffortInput by rememberSaveable(state.antigravityEffort) { mutableStateOf(state.antigravityEffort) }

    var showModelPicker by rememberSaveable { mutableStateOf(false) }
    val isProvisioning = state.setupSnapshot.status == RuntimeSetupStatus.RUNNING
    val runtimeBusy = !state.runtimeInstalled || isProvisioning

    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .forgeFadingVertical(scroll, MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(start = 14.dp, end = 14.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val statusText = state.apiStatusMessage?.asString().orEmpty()
        ForgeLargeTitle(
            title = stringResource(R.string.agent_config_title),
            // Antigravity keeps its own model setting; the provider profile model belongs to the other agents.
            subtitle = listOf(
                state.agentKind.displayTitle(),
                (if (state.agentKind == AgentKind.ANTIGRAVITY) state.antigravityModel else state.providerProfile.model).ifBlank { "default" },
            // Two deliberate lines: " · " in one line wrapped after the dot next to the status chip.
            ).joinToString("\n"),
            horizontalPadding = 6.dp,
            trailing = {
                val installed = state.agentKind in state.installedAgents
                AgentStatusPill(
                    status = when {
                        // A connection test means nothing before the agent is installed.
                        !installed -> AgentStatus.NOT_INSTALLED
                        statusText.startsWith("✓") -> AgentStatus.ONLINE
                        statusText.startsWith("✕") -> AgentStatus.FAILED
                        else -> AgentStatus.NOT_TESTED
                    },
                    busy = installed && state.apiBusy,
                    // One tap on the chip runs the same test as "Test connection" further down.
                    onClick = when {
                        !installed -> null
                        state.agentKind == AgentKind.ANTIGRAVITY -> viewModel::validateAntigravityConnection
                        else -> viewModel::validateProviderConnection
                    },
                )
            },
        )

        val agents = AgentKind.entries
        ForgePillTabs(
            items = agents.map { it.shortTitle() },
            selectedIndex = agents.indexOf(state.agentKind).coerceAtLeast(0),
            onSelect = { viewModel.selectAgent(agents[it]) },
            equalWidth = true, // 3 providers fit side by side; scrolling cut "Antigravity" at the edge
        )
        val agent = state.agentKind
        val agentInstalled = agent in state.installedAgents
        var connectorsOpen by rememberSaveable { mutableStateOf(false) }
        if (agent == AgentKind.CLAUDE_CODE && agentInstalled) {
            ForgeTextButton(onClick = { connectorsOpen = true }) { Text(stringResource(R.string.connectors_open)) }
        }
        if (connectorsOpen) ConnectorsSheet(state = state, viewModel = viewModel, onDismiss = { connectorsOpen = false })

        if (!agentInstalled) {
            // Not installed: one card, one call to action (the reference's "X is not installed" state).
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ForgeStatusIcon(ForgeStatus.TODO, iconSize = 18.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.settings_agent_not_installed, agent.displayTitle()),
                            fontSize = ForgeType.cardTitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = stringResource(R.string.settings_agent_install_hint, agent.downloadNote),
                        fontSize = ForgeType.control,
                        color = scheme.onSurfaceVariant,
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.settings_agent_install_named, agent.displayTitle()),
                        onClick = { viewModel.installAgent(agent) },
                        enabled = !isProvisioning,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (isProvisioning && state.setupScope == SetupScope.RUNTIME) {
                        // A stack/Core install holds the runtime lock; its progress is shown in Settings.
                        Text(
                            text = stringResource(R.string.settings_agent_wait_runtime),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    SetupProgress(state, SetupScope.AGENT)
                }
            }
        } else if (agent == AgentKind.ANTIGRAVITY) {
            AntigravityAccountCard(
                state = state,
                viewModel = viewModel,
                agyAuth = agyAuth,
                agyModelInput = agyModelInput,
                onAgyModelChange = { agyModelInput = it },
                agyEffortInput = agyEffortInput,
                onAgyEffortChange = { agyEffortInput = it },
            )
        } else {
            val availableProviders = providersForAgent(agent)
            val provider = state.providerProfile.kind

            // AI provider: one card; the provider list and the endpoint are collapsible rows inside it.
            var accessOpen by rememberSaveable(agent) { mutableStateOf(false) }
            var endpointOpen by rememberSaveable(agent) { mutableStateOf(false) }
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(R.string.agent_ai_provider),
                    // A card heading, not a second page title: the page's own title ("Atur AI") must stay the biggest text.
                    fontSize = ForgeType.cardTitle,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = ForgeSpace.card, top = ForgeSpace.cardInner, end = ForgeSpace.card, bottom = 4.dp),
                )
                AgentDisclosureRow(
                    icon = ForgeGlyph.LINK,
                    title = provider.displayTitle(),
                    subtitle = provider.displaySubtitle(),
                    expanded = accessOpen,
                    onToggle = { accessOpen = !accessOpen },
                ) {
                    availableProviders.forEach { kind ->
                        RadioRow(
                            title = kind.displayTitle(),
                            subtitle = kind.displaySubtitle(),
                            selected = kind == provider,
                            onClick = { viewModel.updateProviderKind(kind) },
                        )
                    }
                }
                ThinDivider()
                AgentDisclosureRow(
                    icon = ForgeGlyph.INFO,
                    title = stringResource(R.string.settings_endpoint_protocol),
                    subtitle = state.providerProfile.baseUrl.ifBlank { stringResource(R.string.settings_endpoint_default) },
                    expanded = endpointOpen,
                    onToggle = { endpointOpen = !endpointOpen },
                ) {
                    Column(modifier = Modifier.padding(horizontal = ForgeSpace.card, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!provider.fixedBaseUrl && provider != ProviderKind.CLAUDE) {
                            ForgeTextField(
                                kind = ForgeFieldKind.URL, imeAction = ImeAction.Next, error = baseUrlInput.takeIf { it.isNotBlank() && !FieldValidation.isHttpUrl(it) }?.let { stringResource(R.string.field_error_url) },
                                value = baseUrlInput,
                                onValueChange = { baseUrlInput = it },
                                label = { Text(stringResource(R.string.settings_api_base_url)) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ForgeTextField(
                            kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next,
                            value = modelInput,
                            onValueChange = { modelInput = it },
                            label = { Text(stringResource(R.string.settings_model_identifier)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (provider == ProviderKind.LLM_ROUTER) {
                            ForgeTextField(
                                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                                value = openRouterOrderInput,
                                onValueChange = { openRouterOrderInput = it },
                                label = { Text(stringResource(R.string.settings_openrouter_provider_order_comma)) },
                                placeholder = { Text("anthropic,google-vertex,deepseek") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_save_config),
                            onClick = { viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = modelInput, openRouterOrder = openRouterOrderInput) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Credentials (saved API keys): inside the Claude account card for Claude Code, in its own card for the others.
            val credentialsRow: @Composable () -> Unit = {
                val activeKey = state.savedApiKeys.firstOrNull { it.isActive }
                var credentialsOpen by rememberSaveable(agent) { mutableStateOf(false) }
                AgentDisclosureRow(
                    icon = ForgeGlyph.LOCK,
                    title = stringResource(R.string.agent_credentials),
                    subtitle = if (activeKey == null) stringResource(if (provider == ProviderKind.CLAUDE) R.string.agent_key_not_needed else R.string.agent_no_key_saved) else stringResource(R.string.agent_keys_saved, state.savedApiKeys.size, activeKey.name),
                    expanded = credentialsOpen,
                    onToggle = { credentialsOpen = !credentialsOpen },
                ) {
                    Column(modifier = Modifier.padding(horizontal = ForgeSpace.card, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            // Claude sign-in needs no key, so the form is a backup, not a requirement.
                            text = if (provider == ProviderKind.CLAUDE) stringResource(R.string.settings_api_keys_optional)
                            else stringResource(R.string.settings_api_keys_for_auto, provider.displayTitle()),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.SemiBold,
                        )
                        state.savedApiKeys.forEach { keyInfo ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(role = Role.Button) { viewModel.selectActiveApiKey(keyInfo.id) }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (keyInfo.isActive) {
                                    ForgeStatusIcon(ForgeStatus.DONE, iconSize = 18.dp)
                                } else {
                                    ForgeIcon(glyph = ForgeGlyph.LOCK, tint = scheme.onSurfaceVariant, size = 18.dp)
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = if (keyInfo.isActive) stringResource(R.string.settings_key_active, keyInfo.name) else keyInfo.name,
                                    modifier = Modifier.weight(1f),
                                    fontWeight = if (keyInfo.isActive) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                IconButton(onClick = { viewModel.removeApiKey(keyInfo.id) }) {
                                    ForgeIcon(glyph = ForgeGlyph.TRASH, tint = scheme.onSurfaceVariant, size = 18.dp, description = stringResource(R.string.settings_delete_key))
                                }
                            }
                        }
                        ForgeTextField(
                            kind = ForgeFieldKind.NAME, imeAction = ImeAction.Next,
                            value = keyLabelInput,
                            onValueChange = { keyLabelInput = it },
                            label = { Text(stringResource(R.string.settings_label)) },
                            placeholder = { Text(stringResource(R.string.settings_key_label_hint)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ForgeTextField(
                            kind = ForgeFieldKind.SECRET, imeAction = ImeAction.Done,
                            value = keySecretInput,
                            onValueChange = { keySecretInput = it },
                            label = { Text(stringResource(R.string.settings_api_key_token)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        ForgePrimaryButton(
                            text = stringResource(R.string.settings_add_key_to_android),
                            onClick = {
                                if (keySecretInput.isNotBlank()) {
                                    viewModel.addApiKey(keyLabelInput, keySecretInput)
                                    keyLabelInput = ""
                                    keySecretInput = ""
                                }
                            },
                            enabled = keySecretInput.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // Claude subscription: sign in through Claude Code's own login (account, SSO or Console) instead of pasting a
            // token. Shown for every provider of Claude Code so the sign-in is never hidden behind "Access mode".
            if (agent == AgentKind.CLAUDE_CODE) {
                // Model and credentials are part of how Claude is signed in, so they live inside the account card.
                ClaudeAccountCard(viewModel) {
                    ThinDivider()
                    ClaudeIntelligenceCard(state = state, viewModel = viewModel, onOpenModelPicker = { showModelPicker = true }, embedded = true)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.settings_model_access),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextButton(onClick = viewModel::discoverProviderModels, enabled = !state.apiBusy) {
                            Text(stringResource(R.string.settings_discover_models))
                        }
                    }
                    if (state.apiBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    credentialsRow()
                }
            }

            // Model & access for the other agents (Claude Code has both inside its account card above).
            if (agent != AgentKind.CLAUDE_CODE) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 4.dp)) {
                // Section heading with an action: same quiet style as every other section label, not a second page title.
                Text(
                    text = stringResource(R.string.settings_model_access).uppercase(),
                    fontSize = ForgeType.caption,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = ForgeType.sectionTracking,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                ForgeTextButton(onClick = viewModel::discoverProviderModels, enabled = !state.apiBusy) {
                    Text(stringResource(R.string.settings_discover_models))
                }
            }
            if (state.apiBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            var modelOpen by rememberSaveable(agent) { mutableStateOf(false) }
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                // Claude Code picks its model in the "active intelligence" card above; this row is for the other agents.
                if (agent != AgentKind.CLAUDE_CODE) {
                    AgentDisclosureRow(
                        icon = ForgeGlyph.SPARK,
                        title = stringResource(R.string.agent_ai_model),
                        subtitle = state.providerProfile.model.ifBlank { "default" },
                        expanded = modelOpen,
                        onToggle = { modelOpen = !modelOpen },
                    ) {
                        Column(modifier = Modifier.padding(horizontal = ForgeSpace.card, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(stringResource(R.string.settings_current_model), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                                    Text(
                                        text = state.providerProfile.model.ifBlank { "default" },
                                        fontSize = ForgeType.cardTitle,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                                OutlinedButton(shape = RoundedCornerShape(100), onClick = { showModelPicker = true }) {
                                    Text(stringResource(R.string.settings_choose_model))
                                }
                            }
                            if (state.discoveredModels.isNotEmpty()) {
                                Text(
                                    text = quantityStringResource(R.plurals.settings_models_found, state.discoveredModels.size, state.discoveredModels.size),
                                    fontSize = ForgeType.secondary,
                                    fontWeight = FontWeight.Medium,
                                    color = scheme.onSurfaceVariant,
                                )
                                state.discoveredModels.take(30).forEach { m ->
                                    RadioRow(
                                        title = m.displayName,
                                        subtitle = m.id,
                                        mono = true,
                                        selected = m.id == state.providerProfile.model,
                                        onClick = {
                                            modelInput = m.id
                                            viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = m.id, openRouterOrder = openRouterOrderInput)
                                        },
                                        horizontalPadding = 0.dp,
                                    )
                                }
                            }
                        }
                    }
                    ThinDivider()
                }
                credentialsRow()
            }
            }
            // Same connection test as Antigravity: one button under the card, result coloured below it.
            OutlinedButton(
                shape = RoundedCornerShape(100),
                onClick = viewModel::validateProviderConnection,
                enabled = !state.apiBusy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
            ) {
                ForgeIcon(glyph = ForgeGlyph.REFRESH, tint = scheme.primary, size = 18.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.agent_test_connection))
            }
            if (statusText.isNotBlank()) {
                val themeTokens = LocalForgeThemeTokens.current
                Text(
                    text = statusText,
                    fontSize = ForgeType.secondary,
                    color = when {
                        statusText.startsWith("✓") -> themeTokens.successText
                        statusText.startsWith("✕") -> themeTokens.dangerText
                        else -> scheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            if (showModelPicker) {
                ModelPickerDialog(
                    title = provider.displayTitle(),
                    options = providerModelOptions(provider, state.discoveredModels, state.providerProfile.model),
                    selectedId = state.providerProfile.model,
                    loading = state.apiBusy,
                    onRefresh = if (provider == ProviderKind.CLAUDE) null else viewModel::discoverProviderModels,
                    onSelect = { id ->
                        modelInput = id
                        viewModel.updateProviderConfig(baseUrl = baseUrlInput, model = id, openRouterOrder = openRouterOrderInput)
                        showModelPicker = false
                    },
                    onDismiss = { showModelPicker = false },
                )
            }
        }

        // Runtime updates: installed agents, update / remove, check.
        if (state.installedAgents.isNotEmpty()) {
            var updatesOpen by rememberSaveable { mutableStateOf(false) }
            DisclosureCard(
                icon = ForgeGlyph.REFRESH,
                title = stringResource(R.string.settings_runtime_updates),
                subtitle = stringResource(R.string.settings_installed_agents_current),
                expanded = updatesOpen,
                onToggle = { updatesOpen = !updatesOpen },
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.installedAgents.forEach { kind ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 44.dp)) {
                            ForgeStatusIcon(ForgeStatus.DONE, iconSize = 16.dp)
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(kind.displayTitle(), fontSize = ForgeType.body, modifier = Modifier.weight(1f))
                            state.agentUpdates[kind]?.let { info ->
                                ForgeTextButton(onClick = { viewModel.updateAgent(kind) }, enabled = !runtimeBusy) {
                                    Text(stringResource(R.string.settings_agent_update, info.latestVersion))
                                }
                            }
                            UninstallConfirmed(name = kind.displayTitle(), onConfirm = { viewModel.removeAgent(kind) }) { ask ->
                                ForgeTextButton(onClick = ask, enabled = !runtimeBusy) {
                                    Text(stringResource(R.string.settings_agent_remove), color = tokens.dangerText)
                                }
                            }
                        }
                    }
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel::checkAgentUpdates,
                        enabled = !state.checkingAgentUpdates,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                    ) { Text(stringResource(R.string.settings_agent_check_updates)) }
                    // The "not installed" card above already shows this progress; showing it twice looked like two installs.
                    if (agentInstalled) SetupProgress(state, SetupScope.AGENT)
                }
            }
        }

        Text(
            text = stringResource(R.string.agent_footer_security),
            fontSize = ForgeType.secondary,
            color = scheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

private enum class AgentStatus { ONLINE, NOT_TESTED, FAILED, NOT_INSTALLED }

/**
 * Connection state chip at the end of the screen header: Online after a passing test, Failed after a failing one.
 * It is also the shortcut to run that test ([onClick]); while a check runs it says so instead of keeping a stale state.
 */
@Composable
private fun AgentStatusPill(status: AgentStatus, busy: Boolean, onClick: (() -> Unit)?) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val (label, dot) = when {
        busy -> stringResource(R.string.agent_status_checking) to scheme.primary
        status == AgentStatus.ONLINE -> stringResource(R.string.agent_status_online) to tokens.successText
        status == AgentStatus.FAILED -> stringResource(R.string.agent_status_failed) to tokens.dangerText
        status == AgentStatus.NOT_TESTED -> stringResource(R.string.agent_status_not_tested) to scheme.onSurfaceVariant
        else -> stringResource(R.string.agent_status_not_installed) to scheme.onSurfaceVariant
    }
    val tappable = onClick != null && !busy
    Surface(
        shape = RoundedCornerShape(100),
        color = tokens.cardElevatedColor,
        border = tokens.cardBorderStroke,
        modifier = Modifier
            .heightIn(min = ForgeSpace.minTouch)
            .clip(RoundedCornerShape(100))
            .then(if (tappable) Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.agent_test_connection), onClick = onClick!!) else Modifier),
    ) {
        Row(
            modifier = Modifier.heightIn(min = ForgeSpace.minTouch).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = dot)
            } else {
                Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(dot))
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(label, fontSize = ForgeType.secondary, fontWeight = FontWeight.Medium, color = if (busy || status == AgentStatus.NOT_TESTED || status == AgentStatus.NOT_INSTALLED) scheme.onSurfaceVariant else dot)
        }
    }
}

/** A row inside a card that reveals its body: icon tile, title, subtitle and a chevron. */
@Composable
private fun AgentDisclosureRow(
    icon: ForgeGlyph,
    title: String,
    subtitle: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column {
        SettingsRow(icon = icon, title = title, subtitle = subtitle, onClick = onToggle, trailing = { Chevron(expanded) })
        AnimatedVisibility(visible = expanded) {
            Column {
                ThinDivider()
                content()
            }
        }
    }
}
