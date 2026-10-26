@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.CrashReporter
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.BatteryOptimization
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.WorkspaceSnapshot
import java.text.DateFormat
import java.util.Date

/** Toolchains Manager: install, update and remove dev stacks and coding agents in one place. */
@Composable
fun ToolchainsScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val running = state.setupSnapshot.status == RuntimeSetupStatus.RUNNING
    val busy = !state.runtimeInstalled || running
    ToolScaffold(
        title = stringResource(R.string.tools_toolchains),
        subtitle = stringResource(R.string.tools_toolchains_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        if (!state.runtimeInstalled) {
            Text(stringResource(R.string.tool_runtime_missing), fontSize = ForgeType.control, color = scheme.error)
        }
        SetupProgress(state)
        ToolSectionLabel(stringResource(R.string.tool_stacks))
        DevStack.entries.forEach { stack ->
            val installed = stack == DevStack.WEB || stack in state.installedDevStacks
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stack.displayLabel(), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                        Text(stack.displayDescription(), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                    }
                    when {
                        stack == DevStack.WEB -> Text(stringResource(R.string.tool_core), fontSize = ForgeType.secondary, color = LocalForgeThemeTokens.current.accentText)
                        installed -> UninstallConfirmed(name = stack.displayLabel(), onConfirm = { viewModel.removeStack(stack) }) { ask ->
                            OutlinedButton(shape = RoundedCornerShape(100), onClick = ask, enabled = !busy) {
                                Text(stringResource(R.string.tool_remove))
                            }
                        }
                        else -> OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.installStack(stack) }, enabled = !running) {
                            Text(stringResource(R.string.tool_install))
                        }
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolSectionLabel(stringResource(R.string.tool_agents))
            Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                ForgeTextButton(onClick = viewModel::checkAgentUpdates, enabled = !state.checkingAgentUpdates && state.runtimeInstalled) {
                    Text(stringResource(R.string.settings_agent_check_updates))
                }
            }
        }
        AgentKind.entries.forEach { agent ->
            val installed = agent in state.installedAgents
            val update = state.agentUpdates[agent]
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(agent.displayTitle(), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = if (installed) stringResource(R.string.settings_agent_installed) else stringResource(R.string.tool_download_size, agent.downloadNote),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    if (!installed) {
                        OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.installAgent(agent) }, enabled = !running) {
                            Text(stringResource(R.string.tool_install))
                        }
                    } else {
                        if (update != null) {
                            OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.updateAgent(agent) }, enabled = !busy) {
                                Text(stringResource(R.string.settings_agent_update, update.latestVersion))
                            }
                        }
                        UninstallConfirmed(name = agent.displayTitle(), onConfirm = { viewModel.removeAgent(agent) }) { ask ->
                            ForgeTextButton(onClick = ask, enabled = !busy) { Text(stringResource(R.string.tool_remove)) }
                        }
                    }
                }
            }
        }
    }
}

/** System Diagnostics: device resources, runtime state and the chroot health check. */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val diag by viewModel.diagnostics.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.diagnostics.refresh() }
    ToolScaffold(
        title = stringResource(R.string.tools_diagnostics),
        subtitle = stringResource(R.string.tools_diagnostics_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolSectionLabel(stringResource(R.string.tool_device))
        ToolCard { diag.device.forEach { (key, value) -> KeyValueRow(key.asString(), value.asString()) } }
        ToolSectionLabel(stringResource(R.string.tool_runtime))
        ToolCard { diag.runtime.forEach { (key, value) -> KeyValueRow(key.asString(), value.asString()) } }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.diagnostics::refresh, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tool_refresh))
            }
            if (diag.healthRunning) {
                OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.diagnostics::cancelHealthCheck, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tool_stop))
                }
            } else {
                ForgePrimaryButton(
                    text = stringResource(R.string.tool_run_health_check),
                    onClick = viewModel.diagnostics::runHealthCheck,
                    enabled = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Many ROMs stop "optimized" apps mid-build; offer the exemption until it is granted.
        val batteryContext = LocalContext.current
        var batteryUnrestricted by remember { mutableStateOf(BatteryOptimization.isUnrestricted(batteryContext)) }
        val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            batteryUnrestricted = BatteryOptimization.isUnrestricted(batteryContext)
            viewModel.diagnostics.refresh()
        }
        if (!batteryUnrestricted) {
            OutlinedButton(
                shape = RoundedCornerShape(100),
                onClick = { BatteryOptimization.launch(batteryContext) { batteryLauncher.launch(it) } },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.tool_battery_unrestrict)) }
        }
        diag.healthSummary?.let { Text(it, fontSize = ForgeType.control, fontWeight = FontWeight.Medium, color = LocalForgeThemeTokens.current.accentText) }
        LogBox(text = diag.healthOutput, placeholder = stringResource(R.string.tool_health_placeholder))

        // Errors the app caught and carried on from: the trail a field problem used to leave nowhere.
        ToolSectionLabel(stringResource(R.string.diag_recent_errors))
        LogBox(text = diag.swallowedErrors, placeholder = stringResource(R.string.diag_no_recent_errors))

        // The full stack trace of the last crash, readable and copyable without adb.
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        var crash by remember { mutableStateOf(CrashReporter.read(context)) }
        ToolSectionLabel(stringResource(R.string.tool_last_crash))
        LogBox(text = crash.orEmpty(), placeholder = stringResource(R.string.tool_no_crash))
        if (crash != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                ForgePrimaryButton(
                    text = stringResource(R.string.tool_copy),
                    onClick = { clipboard.setText(AnnotatedString(crash.orEmpty())) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = { CrashReporter.shareIntent(context)?.let { context.startActivity(it) } },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.crash_prompt_share)) }
                OutlinedButton(
                    shape = RoundedCornerShape(100),
                    onClick = {
                        CrashReporter.clear(context)
                        crash = null
                    },
                    modifier = Modifier.weight(1f),
                ) { Text(stringResource(R.string.tool_delete)) }
            }
        }
    }
}

/** Checkpoint Manager: undo or keep the latest agent changes, and keep restorable snapshots of the project. */
@Composable
fun CheckpointsScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val snaps by viewModel.snapshots.state.collectAsState()
    var label by rememberSaveable { mutableStateOf("") }
    var pendingRestore by remember { mutableStateOf<WorkspaceSnapshot?>(null) }
    LaunchedEffect(state.activeProjectId) { viewModel.snapshots.refresh() }
    val busy = state.sessionRunning || state.terminalRunning || state.buildingProjectIds.isNotEmpty()
    val pendingChangesCount = state.changedItems.count { it.accepted == null }
    ToolScaffold(
        title = stringResource(R.string.tools_checkpoints),
        subtitle = snaps.projectName.ifBlank { stringResource(R.string.tools_checkpoints_desc) },
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolSectionLabel(stringResource(R.string.tool_agent_changes))
        ToolCard {
            Text(
                text = if (pendingChangesCount == 0) stringResource(R.string.tool_no_pending_changes)
                else quantityStringResource(R.plurals.tool_pending_changes, pendingChangesCount, pendingChangesCount),
                fontSize = ForgeType.control,
            )
            if (pendingChangesCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ForgePrimaryButton(text = stringResource(R.string.tool_keep_all), onClick = viewModel::keepAllChanges, enabled = !busy, modifier = Modifier.weight(1f))
                    ConfirmedAction(
                        title = stringResource(R.string.tool_revert_all),
                        message = quantityStringResource(R.plurals.tool_pending_changes, pendingChangesCount, pendingChangesCount),
                        confirmLabel = stringResource(R.string.tool_revert_all),
                        onConfirm = viewModel::revertAllChanges,
                    ) { onClick ->
                        OutlinedButton(shape = RoundedCornerShape(100), onClick = onClick, enabled = !busy, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.tool_revert_all))
                        }
                    }
                }
            }
        }
        ToolSectionLabel(stringResource(R.string.tool_snapshots))
        ToolCard {
            ForgeTextField(
                kind = ForgeFieldKind.NAME, imeAction = ImeAction.Done,
                value = label,
                onValueChange = { label = it.take(40) },
                label = { Text(stringResource(R.string.tool_snapshot_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ForgePrimaryButton(
                text = stringResource(R.string.tool_create_snapshot),
                onClick = {
                    viewModel.snapshots.create(label)
                    label = ""
                },
                enabled = !snaps.busy && state.activeProjectId.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            snaps.message?.let { Text(it.asString(), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant) }
        }
        if (snaps.snapshots.isEmpty()) {
            Text(stringResource(R.string.tool_no_snapshots), fontSize = ForgeType.control, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        }
        snaps.snapshots.forEach { snapshot ->
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(snapshot.label, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val when_ = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(snapshot.createdAtMillis))
                        val detail = "$when_ · ${snapshot.sizeBytes / 1024} KB"
                        Text(
                            text = detail,
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    OutlinedButton(shape = RoundedCornerShape(100), onClick = { pendingRestore = snapshot }, enabled = !snaps.busy && !busy) {
                        Text(stringResource(R.string.tool_restore))
                    }
                    ConfirmedAction(
                        title = stringResource(R.string.tool_delete),
                        message = snapshot.label,
                        confirmLabel = stringResource(R.string.tool_delete),
                        onConfirm = { viewModel.snapshots.delete(snapshot) },
                    ) { onClick ->
                        ForgeTextButton(onClick = onClick, enabled = !snaps.busy) { Text(stringResource(R.string.tool_delete)) }
                    }
                }
            }
        }
    }
    pendingRestore?.let { target ->
        ForgeAlertDialog(
            onDismissRequest = { pendingRestore = null },
            title = { Text(stringResource(R.string.tool_restore_title)) },
            text = { Text(stringResource(R.string.tool_restore_message, target.label)) },
            confirmButton = {
                ForgePrimaryButton(text = stringResource(R.string.tool_restore), onClick = {
                    viewModel.snapshots.restore(target)
                    pendingRestore = null
                })
            },
            dismissButton = { ForgeTextButton(onClick = { pendingRestore = null }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
}
