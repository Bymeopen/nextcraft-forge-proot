package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.schedule.ScheduleRules

private val PRESET_INTERVALS_MINUTES = intArrayOf(15, 30, 60, 180)

@Composable
internal fun ScheduleDialog(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val chatSchedules = state.scheduledTasks.filter { entry ->
        entry.projectId == state.activeProjectId && entry.chatId == state.activeChatId
    }
    var draftPrompt by rememberSaveable { mutableStateOf("") }
    var chosenMinutes by rememberSaveable { mutableIntStateOf(30) }

    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (chatSchedules.isEmpty()) {
                    Text(
                        text = stringResource(R.string.schedule_empty),
                        color = palette.onSurfaceVariant,
                    )
                }
                for (item in chatSchedules) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = item.prompt, maxLines = 3)
                            val originRes = if (item.byAgent) R.string.schedule_by_agent else R.string.schedule_by_you
                            Text(
                                text = stringResource(originRes, ScheduleRules.label(item.intervalMinutes), item.runCount),
                                color = palette.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = item.enabled,
                            onCheckedChange = { active -> viewModel.setScheduledTaskEnabled(item.id, active) },
                        )
                        ForgeTextButton(onClick = { viewModel.removeScheduledTask(item.id) }) {
                            Text(stringResource(R.string.schedule_delete), color = LocalForgeThemeTokens.current.dangerText)
                        }
                    }
                }
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT,
                    imeAction = ImeAction.Done,
                    value = draftPrompt,
                    onValueChange = { input -> draftPrompt = input.take(ScheduleRules.MAX_PROMPT_LENGTH) },
                    label = { Text(stringResource(R.string.schedule_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (preset in PRESET_INTERVALS_MINUTES) {
                        FilterChip(
                            selected = chosenMinutes == preset,
                            onClick = { chosenMinutes = preset },
                            label = { Text(ScheduleRules.label(preset)) },
                        )
                    }
                }
                Text(
                    text = stringResource(R.string.schedule_hint),
                    color = palette.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        },
        confirmButton = {
            ForgePrimaryButton(
                text = stringResource(R.string.schedule_add),
                enabled = draftPrompt.isNotBlank(),
                onClick = {
                    viewModel.addScheduledTask(draftPrompt, chosenMinutes)
                    draftPrompt = ""
                },
            )
        },
        dismissButton = {
            ForgeTextButton(onClick = onDismiss) {
                Text(stringResource(R.string.schedule_close))
            }
        },
    )
}
