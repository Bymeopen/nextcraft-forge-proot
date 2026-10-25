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
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.schedule.ScheduleRules

/** Timers of the open chat: list (switch off / delete) and a form to add one. Also reachable with `/loop 30m prompt`. */
@Composable
internal fun ScheduleDialog(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tasks = state.scheduledTasks.filter { it.projectId == state.activeProjectId && it.chatId == state.activeChatId }
    var prompt by rememberSaveable { mutableStateOf("") }
    var minutes by rememberSaveable { mutableIntStateOf(30) }
    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.schedule_title)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tasks.isEmpty()) {
                    Text(stringResource(R.string.schedule_empty), color = scheme.onSurfaceVariant)
                }
                tasks.forEach { task ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(task.prompt, maxLines = 3)
                            Text(
                                stringResource(
                                    if (task.byAgent) R.string.schedule_by_agent else R.string.schedule_by_you,
                                    ScheduleRules.label(task.intervalMinutes),
                                    task.runCount,
                                ),
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = task.enabled, onCheckedChange = { viewModel.setScheduledTaskEnabled(task.id, it) })
                        TextButton(onClick = { viewModel.removeScheduledTask(task.id) }) { Text(stringResource(R.string.schedule_delete)) }
                    }
                }
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT, imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                    value = prompt,
                    onValueChange = { prompt = it.take(ScheduleRules.MAX_PROMPT_LENGTH) },
                    label = { Text(stringResource(R.string.schedule_prompt_label)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(15, 30, 60, 180).forEach { m ->
                        FilterChip(selected = minutes == m, onClick = { minutes = m }, label = { Text(ScheduleRules.label(m)) })
                    }
                }
                Text(stringResource(R.string.schedule_hint), color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        },
        confirmButton = {
            TextButton(
                enabled = prompt.isNotBlank(),
                onClick = { viewModel.addScheduledTask(prompt, minutes); prompt = "" },
            ) { Text(stringResource(R.string.schedule_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.schedule_close)) } },
    )
}
