package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.McpTransport
import kotlinx.coroutines.launch

/** Claude Code's MCP connectors and skills: add, switch on or off, remove. Changes apply from the next message. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConnectorsSheet(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    var tab by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(Unit) { viewModel.loadConnectors() }

    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = ForgeSpace.card),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.connectors_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(R.string.connectors_hint),
                    fontSize = ForgeType.secondary,
                    color = scheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                ForgePillTabs(
                    items = listOf(stringResource(R.string.connectors_tab_connectors), stringResource(R.string.connectors_tab_skills)),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                )
            }
            if (tab == 0) connectorItems(state, viewModel) else skillItems(state, viewModel)
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.connectorItems(state: ForgeUiState, viewModel: MainViewModel) {
    if (state.connectors.isEmpty()) {
        item { ForgeEmptyState(glyph = ForgeGlyph.LINK, title = stringResource(R.string.connectors_empty)) }
    }
    items(state.connectors, key = { "connector-${it.id}" }) { connector ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.weight(1f)) {
                Text(connector.name, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    connector.target,
                    fontSize = ForgeType.secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(checked = connector.enabled, onCheckedChange = { viewModel.setConnectorEnabled(connector.id, it) })
            ForgeTextButton(onClick = { viewModel.deleteConnector(connector.id) }) {
                Text(stringResource(R.string.connector_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    item {
        var name by rememberSaveable { mutableStateOf("") }
        var kind by rememberSaveable { mutableStateOf(0) }
        var target by rememberSaveable { mutableStateOf("") }
        var envName by rememberSaveable { mutableStateOf("") }
        // The secret uses plain remember: rememberSaveable would write it into the saved-instance Bundle.
        var secret by remember { mutableStateOf("") }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = { name = "Context7"; kind = 0; target = "https://mcp.context7.com/mcp"; envName = "" },
                shape = RoundedCornerShape(100),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.connector_preset_context7)) }
            ForgeTextField(
                value = name,
                onValueChange = { name = it.take(40) },
                label = { Text(stringResource(R.string.connector_name_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.NAME,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePillTabs(
                items = listOf(stringResource(R.string.connector_kind_remote), stringResource(R.string.connector_kind_command)),
                selectedIndex = kind,
                onSelect = { kind = it },
            )
            ForgeTextField(
                value = target,
                onValueChange = { target = it },
                label = { Text(stringResource(if (kind == 0) R.string.connector_url_hint else R.string.connector_command_hint)) },
                singleLine = true,
                kind = if (kind == 0) ForgeFieldKind.URL else ForgeFieldKind.IDENTIFIER,
                modifier = Modifier.fillMaxWidth(),
            )
            if (kind == 1) {
                ForgeTextField(
                    value = envName,
                    onValueChange = { envName = it.take(60) },
                    label = { Text(stringResource(R.string.connector_env_hint)) },
                    singleLine = true,
                    kind = ForgeFieldKind.IDENTIFIER,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ForgeTextField(
                value = secret,
                onValueChange = { secret = it },
                label = { Text(stringResource(R.string.connector_secret_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.SECRET,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.connector_add),
                onClick = {
                    viewModel.saveConnector(
                        name = name,
                        transport = if (kind == 0) McpTransport.HTTP else McpTransport.COMMAND,
                        target = target,
                        secretName = envName,
                        secret = secret,
                    )
                    secret = ""
                },
                enabled = name.isNotBlank() && target.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.skillItems(state: ForgeUiState, viewModel: MainViewModel) {
    if (state.skills.isEmpty()) {
        item { ForgeEmptyState(glyph = ForgeGlyph.SPARK, title = stringResource(R.string.skills_empty)) }
    }
    item {
        var name by rememberSaveable { mutableStateOf("") }
        var description by rememberSaveable { mutableStateOf("") }
        var body by rememberSaveable { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.skills.forEach { skill ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(skill.name, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (skill.description.isNotBlank()) {
                            Text(
                                skill.description,
                                fontSize = ForgeType.secondary,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    ForgeTextButton(onClick = {
                        name = skill.name
                        description = skill.description
                        scope.launch { body = viewModel.loadSkillBody(skill.name) }
                    }) { Text(stringResource(R.string.skill_edit)) }
                    ForgeTextButton(onClick = { viewModel.deleteSkill(skill.name) }) {
                        Text(stringResource(R.string.connector_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
            ForgeTextField(
                value = name,
                onValueChange = { name = it.take(64) },
                label = { Text(stringResource(R.string.skill_name_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.IDENTIFIER,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgeTextField(
                value = description,
                onValueChange = { description = it.take(300) },
                label = { Text(stringResource(R.string.skill_description_hint)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            ForgeTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text(stringResource(R.string.skill_body_hint)) },
                singleLine = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.skill_save),
                onClick = { viewModel.saveSkill(name, description, body); name = ""; description = ""; body = "" },
                enabled = name.isNotBlank() && body.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
