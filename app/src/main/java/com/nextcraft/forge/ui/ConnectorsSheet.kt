@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.runtime.McpConnector
import com.nextcraft.forge.runtime.McpTransport
import kotlinx.coroutines.launch

private val MOBILE_HINT_KEYWORDS = listOf("android", "mobile", "ios", "flutter", "react native", "kotlin")
private val GAME_HINT_KEYWORDS = listOf("game", "unity", "godot", "libgdx", "pygame", "phaser")

@Composable
internal fun ConnectorsSheet(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    var selectedSectionIdx by rememberSaveable { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        viewModel.loadConnectors()
    }

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
                Text(
                    text = stringResource(R.string.connectors_title),
                    fontSize = ForgeType.screenTitle,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.connectors_hint),
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                ForgePillTabs(
                    items = listOf(
                        stringResource(R.string.connectors_tab_connectors),
                        stringResource(R.string.connectors_tab_skills),
                    ),
                    selectedIndex = selectedSectionIdx,
                    onSelect = { selectedSectionIdx = it },
                )
            }
            if (selectedSectionIdx == 0) {
                renderMcpConnectorsSection(state, viewModel)
            } else {
                renderCustomSkillsSection(state, viewModel)
            }
        }
    }
}

private fun LazyListScope.renderMcpConnectorsSection(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    if (state.connectors.isEmpty()) {
        item {
            ForgeEmptyState(
                glyph = ForgeGlyph.LINK,
                title = stringResource(R.string.connectors_empty),
            )
        }
    }
    items(state.connectors, key = { "connector-${it.id}" }) { entry ->
        val palette = MaterialTheme.colorScheme
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.name,
                    fontSize = ForgeType.rowTitle,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.target,
                    fontSize = ForgeType.secondary,
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Switch(
                checked = entry.enabled,
                onCheckedChange = { active -> viewModel.setConnectorEnabled(entry.id, active) },
            )
            ConfirmedAction(
                title = stringResource(R.string.connector_delete),
                message = entry.name,
                confirmLabel = stringResource(R.string.connector_delete),
                onConfirm = { viewModel.deleteConnector(entry.id) },
            ) { triggerDelete ->
                ForgeTextButton(onClick = triggerDelete) {
                    Text(
                        text = stringResource(R.string.connector_delete),
                        color = palette.error,
                    )
                }
            }
        }
    }
    item {
        var draftName by rememberSaveable { mutableStateOf("") }
        var transportTabIdx by rememberSaveable { mutableIntStateOf(0) }
        var draftEndpoint by rememberSaveable { mutableStateOf("") }
        var draftEnvKey by rememberSaveable { mutableStateOf("") }
        var draftSecretVal by remember { mutableStateOf("") }

        val currentProj = state.activeProject
        val combinedHints = "${currentProj?.name.orEmpty()} ${currentProj?.description.orEmpty()} ${currentProj?.language.orEmpty()}".lowercase()
        val mobileProject = currentProj?.type == ProjectType.ANDROID || MOBILE_HINT_KEYWORDS.any { it in combinedHints }
        val gameProject = GAME_HINT_KEYWORDS.any { it in combinedHints }
        val recommendedPresets = remember(mobileProject, gameProject) {
            McpConnector.presetsForProject(isMobileTarget = mobileProject, isGameTarget = gameProject)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in recommendedPresets) {
                    OutlinedButton(
                        onClick = {
                            draftName = preset.name
                            transportTabIdx = if (preset.transport == McpTransport.HTTP) 0 else 1
                            draftEndpoint = preset.target
                            draftEnvKey = preset.secretName
                        },
                        shape = RoundedCornerShape(100),
                    ) {
                        val chipTitle = if (preset.name == "Context7") {
                            stringResource(R.string.connector_preset_context7)
                        } else {
                            preset.name
                        }
                        Text(chipTitle)
                    }
                }
            }
            ForgeTextField(
                value = draftName,
                onValueChange = { draftName = it.take(40) },
                label = { Text(stringResource(R.string.connector_name_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.NAME,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePillTabs(
                items = listOf(
                    stringResource(R.string.connector_kind_remote),
                    stringResource(R.string.connector_kind_command),
                ),
                selectedIndex = transportTabIdx,
                onSelect = { transportTabIdx = it },
            )
            val isHttpMode = transportTabIdx == 0
            ForgeTextField(
                value = draftEndpoint,
                onValueChange = { draftEndpoint = it },
                label = {
                    Text(
                        stringResource(
                            if (isHttpMode) R.string.connector_url_hint else R.string.connector_command_hint,
                        ),
                    )
                },
                singleLine = true,
                kind = if (isHttpMode) ForgeFieldKind.URL else ForgeFieldKind.IDENTIFIER,
                modifier = Modifier.fillMaxWidth(),
            )
            if (!isHttpMode) {
                ForgeTextField(
                    value = draftEnvKey,
                    onValueChange = { draftEnvKey = it.take(60) },
                    label = { Text(stringResource(R.string.connector_env_hint)) },
                    singleLine = true,
                    kind = ForgeFieldKind.IDENTIFIER,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            ForgeTextField(
                value = draftSecretVal,
                onValueChange = { draftSecretVal = it },
                label = { Text(stringResource(R.string.connector_secret_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.SECRET,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.connector_add),
                onClick = {
                    val savedOk = viewModel.saveConnector(
                        name = draftName,
                        transport = if (isHttpMode) McpTransport.HTTP else McpTransport.COMMAND,
                        target = draftEndpoint,
                        secretName = draftEnvKey,
                        secret = draftSecretVal,
                    )
                    if (savedOk) {
                        draftName = ""
                        draftEndpoint = ""
                        draftEnvKey = ""
                        draftSecretVal = ""
                    }
                },
                enabled = draftName.isNotBlank() && draftEndpoint.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private fun LazyListScope.renderCustomSkillsSection(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    if (state.skills.isEmpty()) {
        item {
            ForgeEmptyState(
                glyph = ForgeGlyph.SPARK,
                title = stringResource(R.string.skills_empty),
            )
        }
    }
    item {
        val palette = MaterialTheme.colorScheme
        var skillIdDraft by rememberSaveable { mutableStateOf("") }
        var skillSummaryDraft by rememberSaveable { mutableStateOf("") }
        var skillMarkdownDraft by rememberSaveable { mutableStateOf("") }
        val coroutineScope = rememberCoroutineScope()

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (skillEntry in state.skills) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = skillEntry.name,
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (skillEntry.description.isNotBlank()) {
                            Text(
                                text = skillEntry.description,
                                fontSize = ForgeType.secondary,
                                color = palette.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    ForgeTextButton(
                        onClick = {
                            skillIdDraft = skillEntry.name
                            skillSummaryDraft = skillEntry.description
                            coroutineScope.launch {
                                skillMarkdownDraft = viewModel.loadSkillBody(skillEntry.name)
                            }
                        },
                    ) {
                        Text(stringResource(R.string.skill_edit))
                    }
                    ConfirmedAction(
                        title = stringResource(R.string.connector_delete),
                        message = skillEntry.name,
                        confirmLabel = stringResource(R.string.connector_delete),
                        onConfirm = { viewModel.deleteSkill(skillEntry.name) },
                    ) { triggerDelete ->
                        ForgeTextButton(onClick = triggerDelete) {
                            Text(
                                text = stringResource(R.string.connector_delete),
                                color = palette.error,
                            )
                        }
                    }
                }
            }
            ForgeTextField(
                value = skillIdDraft,
                onValueChange = { skillIdDraft = it.take(64) },
                label = { Text(stringResource(R.string.skill_name_hint)) },
                singleLine = true,
                kind = ForgeFieldKind.IDENTIFIER,
                modifier = Modifier.fillMaxWidth(),
            )
            ForgeTextField(
                value = skillSummaryDraft,
                onValueChange = { skillSummaryDraft = it.take(300) },
                label = { Text(stringResource(R.string.skill_description_hint)) },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
            ForgeTextField(
                value = skillMarkdownDraft,
                onValueChange = { skillMarkdownDraft = it },
                label = { Text(stringResource(R.string.skill_body_hint)) },
                singleLine = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.skill_save),
                onClick = {
                    viewModel.saveSkill(skillIdDraft, skillSummaryDraft, skillMarkdownDraft)
                    skillIdDraft = ""
                    skillSummaryDraft = ""
                    skillMarkdownDraft = ""
                },
                enabled = skillIdDraft.isNotBlank() && skillMarkdownDraft.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
