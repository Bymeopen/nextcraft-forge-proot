@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.StarterTemplate

@Composable
internal fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, description: String, isAndroid: Boolean, template: AndroidTemplate, starter: StarterTemplate) -> Unit,
    gameTemplatesEnabled: Boolean = false,
) {
    val availableStarters = StarterTemplate.entries.filter {
        gameTemplatesEnabled || it != StarterTemplate.GAME_LIBGDX
    }
    var projectTitle by rememberSaveable { mutableStateOf("") }
    var projectSummary by rememberSaveable { mutableStateOf("") }
    var useAndroidStack by rememberSaveable { mutableStateOf(false) }
    var selectedAndroidKind by rememberSaveable { mutableStateOf(AndroidTemplate.COMPOSE) }
    var selectedStarterKind by rememberSaveable { mutableStateOf(StarterTemplate.EMPTY) }
    val effectiveStarter = if (selectedStarterKind in availableStarters) selectedStarterKind else StarterTemplate.EMPTY

    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = stringResource(R.string.np_title),
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val missingNameMsg = if (projectTitle.isBlank()) stringResource(R.string.field_error_required) else null
                ForgeTextField(
                    kind = ForgeFieldKind.NAME,
                    imeAction = ImeAction.Next,
                    error = missingNameMsg,
                    value = projectTitle,
                    onValueChange = { projectTitle = it },
                    label = { Text(stringResource(R.string.shell_project_name)) },
                    placeholder = { Text(stringResource(R.string.shell_my_awesome_app)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT,
                    imeAction = ImeAction.Done,
                    value = projectSummary,
                    onValueChange = { projectSummary = it },
                    label = { Text(stringResource(R.string.shell_description)) },
                    placeholder = { Text(stringResource(R.string.shell_optional_project_summary)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                ForgeToggleRow(
                    title = stringResource(R.string.np_android_template),
                    subtitle = stringResource(R.string.np_android_template_sub),
                    checked = useAndroidStack,
                    onCheckedChange = { useAndroidStack = it },
                )
                if (useAndroidStack) {
                    val androidOptions = AndroidTemplate.entries
                    ForgePillTabs(
                        items = androidOptions.map(AndroidTemplate::label),
                        selectedIndex = androidOptions.indexOf(selectedAndroidKind).coerceAtLeast(0),
                        onSelect = { idx -> selectedAndroidKind = androidOptions[idx] },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                } else {
                    ForgePillTabs(
                        items = availableStarters.map(StarterTemplate::label),
                        selectedIndex = availableStarters.indexOf(effectiveStarter).coerceAtLeast(0),
                        onSelect = { idx -> selectedStarterKind = availableStarters[idx] },
                        modifier = Modifier.padding(horizontal = 0.dp),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(projectTitle, projectSummary, useAndroidStack, selectedAndroidKind, effectiveStarter) },
                enabled = projectTitle.isNotBlank(),
                shape = RoundedCornerShape(100),
            ) {
                Text(
                    text = stringResource(R.string.files_create),
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            ForgeTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.agent_cancel))
            }
        },
    )
}
