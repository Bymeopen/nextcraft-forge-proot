@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.core.net.toUri
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.SshHost
import com.nextcraft.forge.runtime.TargetStatus
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private fun TargetStatus?.mark(): String = when (this) {
    TargetStatus.RUNNING -> "▶"
    TargetStatus.OK -> "✓"
    TargetStatus.FAILED -> "✕"
    TargetStatus.PENDING -> "…"
    null -> ""
}

/** Build Matrix: choose which build targets of the project to run, run them in order, read the combined log. */
@Composable
fun BuildMatrixScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val matrix by viewModel.buildMatrix.state.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    LaunchedEffect(state.activeProjectId, state.activeChatId) { viewModel.buildMatrix.load() }
    val blocked = state.runningChatIds.isNotEmpty() || state.activeProjectId in state.buildingProjectIds || !state.runtimeInstalled
    ToolScaffold(
        title = stringResource(R.string.tools_build_matrix),
        subtitle = matrix.projectName.ifBlank { stringResource(R.string.tools_build_matrix_desc) },
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        if (matrix.targets.isEmpty()) {
            Text(stringResource(R.string.tool_no_targets), fontSize = ForgeType.control, color = scheme.onSurfaceVariant)
        }
        matrix.targets.forEach { target ->
            ToolCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = target.enabled, onCheckedChange = { viewModel.buildMatrix.toggle(target.id) }, enabled = !matrix.running)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(target.name, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                        Text(target.command, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text(matrix.statuses[target.id].mark(), fontSize = ForgeType.cardTitle, color = LocalForgeThemeTokens.current.accentText, modifier = Modifier.padding(horizontal = 6.dp))
                    if (!target.detected) {
                        ForgeTextButton(onClick = { viewModel.buildMatrix.remove(target.id) }, enabled = !matrix.running) { Text(stringResource(R.string.tool_delete)) }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(shape = RoundedCornerShape(100), onClick = { showAdd = true }, enabled = !matrix.running, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.tool_add_target))
            }
            if (matrix.running) {
                OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.buildMatrix::cancel, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.tool_stop))
                }
            } else {
                ForgePrimaryButton(
                    text = stringResource(R.string.tool_run_selected),
                    onClick = viewModel.buildMatrix::runSelected,
                    enabled = !blocked && matrix.targets.any { it.enabled },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        // Cross-builds (Windows, Linux x86_64, iOS): install the missing compilers, or prepare the macOS CI workflow.
        if (matrix.installCommand != null || matrix.iosProject != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (matrix.installCommand != null) {
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel.buildMatrix::installCrossTools,
                        enabled = !matrix.running && !blocked,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.tool_install_cross_tools)) }
                }
                if (matrix.iosProject != null) {
                    OutlinedButton(
                        shape = RoundedCornerShape(100),
                        onClick = viewModel.buildMatrix::createIosWorkflow,
                        enabled = !matrix.running,
                        modifier = Modifier.weight(1f),
                    ) { Text(stringResource(R.string.tool_create_ios_workflow)) }
                }
            }
            if (matrix.iosProject != null) {
                Text(stringResource(R.string.tool_ios_needs_macos), fontSize = ForgeType.caption, color = scheme.onSurfaceVariant)
            }
        }
        if (matrix.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LogBox(text = matrix.log, placeholder = stringResource(R.string.tool_log_placeholder))
    }
    if (showAdd) {
        var name by remember { mutableStateOf("") }
        var command by remember { mutableStateOf("") }
        ForgeAlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tool_add_target)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(kind = ForgeFieldKind.NAME, imeAction = ImeAction.Next, value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.tool_target_name)) }, modifier = Modifier.fillMaxWidth())
                    ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done, singleLine = false, value = command, onValueChange = { command = it }, label = { Text(stringResource(R.string.tool_target_command)) }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                ForgePrimaryButton(text = stringResource(R.string.projects_rename_save), enabled = name.isNotBlank() && command.isNotBlank(), onClick = {
                    viewModel.buildMatrix.addCustom(name, command)
                    showAdd = false
                })
            },
            dismissButton = { ForgeTextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
}

/** SSH Manager: saved hosts, key pair, connection test, remote command and port-forward tunnels. */
@Composable
fun SshScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ssh by viewModel.ssh.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var showAdd by remember { mutableStateOf(false) }
    var remoteFor by remember { mutableStateOf<SshHost?>(null) }
    LaunchedEffect(Unit) { viewModel.ssh.checkClient() }
    ToolScaffold(
        title = stringResource(R.string.tools_ssh),
        subtitle = stringResource(R.string.tools_ssh_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolCard {
            Text(stringResource(R.string.tool_ssh_client), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
            Text(
                text = when (ssh.clientInstalled) {
                    true -> stringResource(R.string.tool_ssh_client_ready)
                    false -> stringResource(R.string.tool_ssh_client_missing)
                    null -> stringResource(R.string.tool_checking)
                },
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (ssh.clientInstalled == false) {
                    ForgePrimaryButton(text = stringResource(R.string.tool_install), onClick = viewModel.ssh::installClient, enabled = !ssh.busy, modifier = Modifier.weight(1f))
                } else {
                    OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.ssh::loadOrCreateKey, enabled = !ssh.busy && ssh.clientInstalled == true, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.tool_ssh_key))
                    }
                }
            }
            ssh.publicKey?.let { key ->
                Text(key, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                ForgeTextButton(onClick = { clipboard.setText(AnnotatedString(key)) }) { Text(stringResource(R.string.tool_copy)) }
            }
        }
        ToolSectionLabel(stringResource(R.string.tool_ssh_hosts))
        if (ssh.hosts.isEmpty()) Text(stringResource(R.string.tool_ssh_no_hosts), fontSize = ForgeType.control, color = scheme.onSurfaceVariant)
        ssh.hosts.forEach { host ->
            ToolCard {
                Text(host.alias, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                val address = "${host.user}@${host.host}:${host.port}" + if (host.forward.isNotEmpty()) "  ·  -L ${host.forward}" else ""
                Text(address, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.ssh.testConnection(host) }, enabled = !ssh.busy && ssh.clientInstalled == true) { Text(stringResource(R.string.tool_ssh_test)) }
                    OutlinedButton(shape = RoundedCornerShape(100), onClick = { remoteFor = host }, enabled = !ssh.busy && ssh.clientInstalled == true) { Text(stringResource(R.string.tool_ssh_run)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (host.forward.isNotEmpty()) {
                        if (host.id in ssh.tunnels) {
                            OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.ssh.stopTunnel(host.id) }) { Text(stringResource(R.string.tool_ssh_stop_tunnel)) }
                        } else {
                            OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.ssh.startTunnel(host) }, enabled = ssh.clientInstalled == true) { Text(stringResource(R.string.tool_ssh_start_tunnel)) }
                        }
                    }
                    ConfirmedAction(
                        title = stringResource(R.string.tool_delete),
                        message = "${host.alias} (${host.user}@${host.host}:${host.port})",
                        confirmLabel = stringResource(R.string.tool_delete),
                        onConfirm = { viewModel.ssh.removeHost(host.id) },
                    ) { onClick ->
                        ForgeTextButton(onClick = onClick) { Text(stringResource(R.string.tool_delete)) }
                    }
                }
            }
        }
        OutlinedButton(shape = RoundedCornerShape(100), onClick = { showAdd = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.tool_ssh_add_host)) }
        if (ssh.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            ForgeTextButton(onClick = viewModel.ssh::cancel) { Text(stringResource(R.string.tool_stop)) }
        }
        LogBox(text = messageLines(ssh.log), placeholder = stringResource(R.string.tool_log_placeholder))
    }
    if (showAdd) {
        var alias by remember { mutableStateOf("") }
        var user by remember { mutableStateOf("root") }
        var host by remember { mutableStateOf("") }
        var port by remember { mutableStateOf("22") }
        var forward by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        ForgeAlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text(stringResource(R.string.tool_ssh_add_host)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next, value = alias, onValueChange = { alias = it }, label = { Text(stringResource(R.string.tool_ssh_alias)) }, modifier = Modifier.fillMaxWidth())
                    ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next, value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.tool_ssh_user)) }, modifier = Modifier.fillMaxWidth())
                    ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next, value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.tool_ssh_host)) }, modifier = Modifier.fillMaxWidth())
                    ForgeTextField(kind = ForgeFieldKind.NUMBER, imeAction = ImeAction.Next, error = port.takeIf { it.isNotBlank() && !FieldValidation.isPort(it) }?.let { stringResource(R.string.field_error_port) }, value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.tool_ssh_port)) }, modifier = Modifier.fillMaxWidth())
                    ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done, value = forward, onValueChange = { forward = it }, label = { Text(stringResource(R.string.tool_ssh_forward)) }, modifier = Modifier.fillMaxWidth())
                    error?.let { Text(messageText(it), fontSize = ForgeType.secondary, color = scheme.error) }
                }
            },
            confirmButton = {
                ForgePrimaryButton(text = stringResource(R.string.projects_rename_save), onClick = {
                    error = viewModel.ssh.saveHost(alias, user, host, port.toIntOrNull() ?: 0, forward)
                    if (error == null) showAdd = false
                })
            },
            dismissButton = { ForgeTextButton(onClick = { showAdd = false }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
    remoteFor?.let { target ->
        var command by remember { mutableStateOf("") }
        ForgeAlertDialog(
            onDismissRequest = { remoteFor = null },
            title = { Text(stringResource(R.string.tool_ssh_run_on, target.alias)) },
            text = {
                ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done, singleLine = false, value = command, onValueChange = { command = it }, label = { Text(stringResource(R.string.tool_ssh_command)) }, modifier = Modifier.fillMaxWidth())
            },
            confirmButton = {
                ForgePrimaryButton(text = stringResource(R.string.tool_ssh_run), enabled = command.isNotBlank(), onClick = {
                    viewModel.ssh.runRemote(target, command)
                    remoteFor = null
                })
            },
            dismissButton = { ForgeTextButton(onClick = { remoteFor = null }) { Text(stringResource(R.string.projects_cancel)) } },
        )
    }
}

/** CI/CD Pipeline: GitHub Actions workflows, PR manager, and runs of the active project's repository. */
@Composable
fun CiCdScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val ci by viewModel.cicd.state.collectAsStateWithLifecycle()
    val uiState by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activeBranch = uiState.projectRepo?.branch.orEmpty()
    var prTitle by rememberSaveable { mutableStateOf("") }
    var prHead by rememberSaveable(activeBranch) {
        mutableStateOf(if (activeBranch.isNotBlank() && activeBranch != "main") activeBranch else "")
    }
    var prBase by rememberSaveable { mutableStateOf("main") }
    LaunchedEffect(uiState.activeProjectId) { viewModel.cicd.refresh() }
    ToolScaffold(
        title = stringResource(R.string.tools_cicd),
        subtitle = ci.repository ?: stringResource(R.string.tools_cicd_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        if (ci.loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ci.message?.let { Text(it.asString(), fontSize = ForgeType.control, color = scheme.onSurfaceVariant) }
        OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.cicd.refresh() }, enabled = !ci.loading, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.tool_refresh))
        }
        if (ci.repository != null) {
            ToolSectionLabel(stringResource(R.string.github_pr_manager_title))
            ToolCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(
                        kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Next,
                        value = prTitle,
                        onValueChange = { prTitle = it.take(120) },
                        label = { Text(stringResource(R.string.github_pr_title_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        ForgeTextField(
                            kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next,
                            value = prHead,
                            onValueChange = { prHead = it.take(80) },
                            label = { Text(stringResource(R.string.github_pr_head_branch)) },
                            modifier = Modifier.weight(1f),
                        )
                        ForgeTextField(
                            kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                            value = prBase,
                            onValueChange = { prBase = it.take(80) },
                            label = { Text(stringResource(R.string.github_pr_base_branch)) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    ForgePrimaryButton(
                        text = stringResource(R.string.github_pr_create_button),
                        enabled = !ci.loading && prTitle.isNotBlank() && prHead.isNotBlank(),
                        onClick = {
                            viewModel.cicd.createPullRequest(prTitle, prHead, prBase)
                            prTitle = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            ci.pullRequests.forEach { pr ->
                ToolCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val prHeader = "#${pr.number} · ${pr.title}"
                            val prBranches = "${pr.headBranch} → ${pr.baseBranch}"
                            Text(prHeader, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(prBranches, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                        }
                        MergePrConfirmed(
                            title = stringResource(R.string.repo_pr_merge),
                            message = "#${pr.number} · ${pr.title} (${pr.headBranch} → ${pr.baseBranch})",
                            confirmLabel = stringResource(R.string.repo_pr_merge),
                            onConfirm = { method -> viewModel.cicd.mergePullRequest(pr.number, method) },
                        ) { onClick ->
                            OutlinedButton(
                                shape = RoundedCornerShape(100),
                                onClick = onClick,
                                enabled = !ci.loading,
                            ) {
                                Text(stringResource(R.string.repo_pr_merge))
                            }
                        }
                        ForgeTextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, pr.url.toUri())) } }) {
                            Text(stringResource(R.string.tool_open))
                        }
                    }
                }
            }
        }
        if (ci.workflows.isNotEmpty()) {
            ToolSectionLabel(stringResource(R.string.tool_workflows))
            ForgeTextField(
                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,
                value = ci.branch,
                onValueChange = viewModel.cicd::setBranch,
                label = { Text(stringResource(R.string.tool_branch)) },
                modifier = Modifier.fillMaxWidth(),
            )
            ci.workflows.forEach { workflow ->
                ToolCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(workflow.name, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                            Text(workflow.path, fontSize = ForgeType.caption, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.cicd.dispatch(workflow) }, enabled = !ci.loading && workflow.state == "active") {
                            Text(stringResource(R.string.tool_run))
                        }
                    }
                }
            }
        }
        if (ci.runs.isNotEmpty()) {
            ToolSectionLabel(stringResource(R.string.tool_runs))
            ci.runs.forEach { run ->
                val outcome = run.conclusion ?: run.status
                val isFailed = run.conclusion != null && run.conclusion != "success" && run.conclusion != "skipped"
                ToolCard(modifier = Modifier.padding(0.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(run.name, fontSize = ForgeType.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                val runInfo = "${run.branch} · ${run.event} · ${run.createdAt.take(16).replace('T', ' ')}"
                                Text(runInfo, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(
                                text = outcome,
                                fontSize = ForgeType.secondary,
                                fontWeight = FontWeight.Medium,
                                color = if (run.conclusion == "success") scheme.primary else if (run.conclusion == null) scheme.onSurfaceVariant else scheme.error,
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                            ForgeTextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, run.url.toUri())) } }) { Text(stringResource(R.string.tool_open)) }
                        }
                        if (isFailed) {
                            OutlinedButton(
                                shape = RoundedCornerShape(100),
                                onClick = { viewModel.fixCiFailureWithAgent(run.name, run.branch, run.url) },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.github_fix_ci_with_agent), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** ROM Studio: install the repo tool, init and sync an Android ROM source tree in the chroot. */
@Composable
fun RomStudioScreen(viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val rom by viewModel.rom.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.rom.refreshStorage() }
    ToolScaffold(
        title = stringResource(R.string.tools_rom),
        subtitle = stringResource(R.string.tools_rom_desc),
        onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
    ) {
        ToolCard {
            Text(stringResource(R.string.tool_rom_warning, rom.freeStorage), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
        }
        ForgeTextField(kind = ForgeFieldKind.URL, imeAction = ImeAction.Next, error = rom.manifestUrl.takeIf { it.isNotBlank() && !FieldValidation.isHttpUrl(it) }?.let { stringResource(R.string.field_error_url) },  value = rom.manifestUrl, onValueChange = { viewModel.rom.update(manifestUrl = it) }, label = { Text(stringResource(R.string.tool_rom_manifest)) }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Next,  value = rom.branch, onValueChange = { viewModel.rom.update(branch = it) }, label = { Text(stringResource(R.string.tool_branch)) }, modifier = Modifier.weight(1f))
            ForgeTextField(kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Done,  value = rom.directory, onValueChange = { viewModel.rom.update(directory = it) }, label = { Text(stringResource(R.string.tool_rom_folder)) }, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.tool_rom_jobs, rom.jobs), fontSize = ForgeType.control, modifier = Modifier.weight(1f))
            OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.rom.update(jobs = rom.jobs - 1) }) { Text("−") }
            OutlinedButton(shape = RoundedCornerShape(100), onClick = { viewModel.rom.update(jobs = rom.jobs + 1) }, modifier = Modifier.padding(start = 8.dp)) { Text("+") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.rom::installRepoTool, enabled = !rom.running, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tool_rom_install)) }
            OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.rom::init, enabled = !rom.running, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tool_rom_init)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ForgePrimaryButton(text = stringResource(R.string.tool_rom_sync), onClick = viewModel.rom::sync, enabled = !rom.running, modifier = Modifier.weight(1f))
            if (rom.running) {
                OutlinedButton(shape = RoundedCornerShape(100), onClick = viewModel.rom::cancel, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.tool_stop)) }
            }
        }
        if (rom.running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        LogBox(text = rom.log, placeholder = stringResource(R.string.tool_log_placeholder))
    }
}
