@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.runtime.Composable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nextcraft.forge.runtime.AndroidBuildIssue
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidLogLevel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.LaunchedEffect
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidIssueSeverity
import androidx.compose.foundation.clickable
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes

private data class StageRow(@StringRes val title: Int, val stages: List<AndroidBuildStage>)

private val stageRows = listOf(
    StageRow(R.string.launch_stage_prepare, listOf(AndroidBuildStage.PREPARING)),
    StageRow(R.string.launch_stage_resolve, listOf(AndroidBuildStage.RESOLVING)),
    StageRow(R.string.launch_stage_compile, listOf(AndroidBuildStage.COMPILING)),
    StageRow(R.string.launch_stage_resources, listOf(AndroidBuildStage.RESOURCES)),
    StageRow(R.string.launch_stage_package, listOf(AndroidBuildStage.PACKAGING, AndroidBuildStage.FINDING_APK)),
    StageRow(R.string.launch_stage_install, listOf(AndroidBuildStage.INSTALLING, AndroidBuildStage.LAUNCHING)),
)

private fun stageRowIndex(stage: AndroidBuildStage): Int = stageRows.indexOfFirst { stage in it.stages }

/** Status of stage row [row] given the last stage the build reached ([lastRow]) and the overall [phase]. */
private fun stageRowStatus(row: Int, lastRow: Int, phase: AndroidBuildPhase): ForgeStatus {
    if (phase == AndroidBuildPhase.IDLE || lastRow < 0) return ForgeStatus.TODO
    return when {
        row < lastRow -> ForgeStatus.DONE
        row == lastRow -> when (phase) {
            AndroidBuildPhase.SUCCEEDED -> ForgeStatus.DONE
            AndroidBuildPhase.FAILED -> ForgeStatus.FAILED
            AndroidBuildPhase.CANCELLED -> ForgeStatus.SKIPPED
            else -> ForgeStatus.RUNNING
        }
        // Rows after the last reached one: skipped on success (e.g. no install), still waiting otherwise.
        else -> if (phase == AndroidBuildPhase.SUCCEEDED) ForgeStatus.SKIPPED else ForgeStatus.TODO
    }
}

@StringRes
private fun AndroidBuildPhase.headline(): Int = when (this) {
    AndroidBuildPhase.IDLE -> R.string.launch_state_idle
    AndroidBuildPhase.PREPARING -> R.string.launch_state_preparing
    AndroidBuildPhase.BUILDING -> R.string.launch_state_building
    AndroidBuildPhase.CANCELLING -> R.string.launch_state_cancelling
    AndroidBuildPhase.SUCCEEDED -> R.string.launch_state_succeeded
    AndroidBuildPhase.FAILED -> R.string.launch_state_failed
    AndroidBuildPhase.CANCELLED -> R.string.launch_state_cancelled
}

private fun AndroidBuildPhase.overallStatus(): ForgeStatus = when (this) {
    AndroidBuildPhase.IDLE -> ForgeStatus.TODO
    AndroidBuildPhase.PREPARING, AndroidBuildPhase.BUILDING, AndroidBuildPhase.CANCELLING -> ForgeStatus.RUNNING
    AndroidBuildPhase.SUCCEEDED -> ForgeStatus.DONE
    AndroidBuildPhase.FAILED -> ForgeStatus.FAILED
    AndroidBuildPhase.CANCELLED -> ForgeStatus.SKIPPED
}

/**
 * Launch screen, Linear style: title + action pill, one status block, then tabs
 * (Stages / Issues / Log / Logcat). A failed build jumps to Issues so the fix is one tap away.
 */
@Composable
fun AndroidBuildScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val build = state.androidBuild
    val busy = build.phase == AndroidBuildPhase.PREPARING ||
        build.phase == AndroidBuildPhase.BUILDING ||
        build.phase == AndroidBuildPhase.CANCELLING
    val canStop = build.phase == AndroidBuildPhase.PREPARING || build.phase == AndroidBuildPhase.BUILDING
    val tokens = LocalForgeThemeTokens.current
    var activeTab by rememberSaveable { mutableStateOf(0) } // 0 stages, 1 issues, 2 log, 3 logcat
    var menuOpen by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val apkSaver = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/vnd.android.package-archive"),
    ) { uri ->
        if (uri != null) viewModel.exportBuiltApk(uri)
    }

    // Stage events carry only the active stage, so remember the last real one to place success/failure.
    var lastRow by remember(state.activeProjectId) { mutableStateOf(-1) }
    LaunchedEffect(state.activeProjectId, build.stage, build.phase) {
        val index = stageRowIndex(build.stage)
        if (build.phase == AndroidBuildPhase.IDLE) lastRow = -1 else if (index >= 0) lastRow = index
    }
    LaunchedEffect(state.activeProjectId, build.phase) {
        if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) activeTab = 1
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(
            title = state.activeProject?.name ?: stringResource(R.string.launch_launch),
            accentSeed = state.activeProject?.name,
            subtitle = stringResource(R.string.launch_launch),
            // Opened from Tools there is no project header above: Back returns to Tools, like the other tool screens.
            onBack = if (state.activeTab != ForgeTab.PROJECTS) ({ viewModel.selectTab(ForgeTab.TOOLS) }) else null,
            actions = {
            if (canStop) {
                ForgePillIcon(
                    icon = Icons.Default.Close,
                    description = stringResource(R.string.launch_cancel_build),
                    onClick = viewModel::stopAndroidBuild,
                    tint = MaterialTheme.colorScheme.error,
                )
            } else {
                ForgePillIcon(
                    icon = Icons.Default.PlayArrow,
                    description = stringResource(R.string.launch_build_and_install_apk),
                    enabled = !busy,
                    onClick = { viewModel.runAndroidBuild("assembleDebug", installAfterBuild = true) },
                )
            }
            Box {
                ForgePillIcon(
                    icon = Icons.Default.MoreVert,
                    description = stringResource(R.string.launch_more_build_actions),
                    onClick = { menuOpen = true },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.launch_assemble_debug_no_install)) },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            viewModel.runAndroidBuild("assembleDebug", installAfterBuild = false)
                        },
                    )
                    if (build.apkPath != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.launch_install_last_apk)) },
                            onClick = {
                                menuOpen = false
                                viewModel.installBuiltApk()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.files_save_copy)) },
                            onClick = {
                                menuOpen = false
                                apkSaver.launch("${state.activeProject?.slug ?: "app"}-debug.apk")
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.verify_now)) },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            viewModel.verifyBuildNow()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.verify_auto_toggle)) },
                        trailingIcon = { androidx.compose.material3.Switch(checked = state.autoVerifyBuild, onCheckedChange = null) },
                        onClick = { viewModel.setAutoVerifyBuild(!state.autoVerifyBuild) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.verify_autofix_toggle)) },
                        enabled = state.autoVerifyBuild,
                        trailingIcon = {
                            androidx.compose.material3.Switch(checked = state.autoFixBuild, onCheckedChange = null, enabled = state.autoVerifyBuild)
                        },
                        onClick = { viewModel.setAutoFixBuild(!state.autoFixBuild) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.settings_auto_commit_push)) },
                        trailingIcon = { androidx.compose.material3.Switch(checked = state.autoSyncGitHub, onCheckedChange = null) },
                        onClick = { viewModel.setAutoSyncGitHub(!state.autoSyncGitHub) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.launch_clean)) },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            viewModel.runAndroidBuild("clean", installAfterBuild = false)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.launch_force_rebuild)) },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            viewModel.runAndroidBuild("clean assembleDebug", installAfterBuild = true)
                        },
                    )
                    if (build.apkPath != null) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.launch_copy_apk_path)) },
                            onClick = {
                                menuOpen = false
                                clipboard.setText(AnnotatedString(build.apkPath))
                            },
                        )
                    }
                }
            }
            },
        )

        LiveResourceMiniBar(onCleanIdleDaemons = viewModel::cleanIdleDaemons)

        // Status block
        val errorCount = build.issues.count { it.severity == AndroidIssueSeverity.ERROR }
        val warningCount = build.issues.count { it.severity == AndroidIssueSeverity.WARNING }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeStatusIcon(build.phase.overallStatus(), iconSize = 22.dp)
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(build.phase.headline()), fontSize = ForgeType.cardTitle, fontWeight = FontWeight.SemiBold)
                Text(
                    text = if (build.phase == AndroidBuildPhase.FAILED && build.issues.isNotEmpty()) {
                        stringResource(
                            R.string.launch_errors_warnings_join,
                            quantityStringResource(R.plurals.launch_errors, errorCount, errorCount),
                            quantityStringResource(R.plurals.launch_warnings, warningCount, warningCount),
                        )
                    } else {
                        build.message?.let { messageText(it) } ?: build.stage.displayLabel()
                    },
                    fontSize = ForgeType.control,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        val reduceMotion = rememberReduceMotion()
        val animatedProgress by androidx.compose.animation.core.animateFloatAsState(
            targetValue = build.stage.progressFraction(),
            animationSpec = if (reduceMotion) androidx.compose.animation.core.snap() else ForgeMotion.springFluid(),
            label = "buildProgress",
        )
        androidx.compose.animation.AnimatedVisibility(
            visible = build.phase == AndroidBuildPhase.BUILDING,
            enter = ForgeMotion.expandEnter(reduceMotion),
            exit = ForgeMotion.shrinkExit(reduceMotion),
        ) {
            LinearProgressIndicator(
                progress = animatedProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .height(3.dp),
            )
        }
        if (build.phase == AndroidBuildPhase.FAILED && (build.issues.isNotEmpty() || build.log.isNotBlank())) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ForgePrimaryButton(
                    text = stringResource(R.string.launch_fix_with_agent),
                    onClick = { viewModel.fixBuildWithAgent(autoRebuild = false) },
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { viewModel.fixBuildWithAgent(autoRebuild = true) },
                    shape = RoundedCornerShape(100),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.launch_fix_auto_rebuild),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        } else if (build.phase == AndroidBuildPhase.SUCCEEDED && build.apkPath != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ForgePrimaryButton(
                    text = stringResource(R.string.launch_install_apk),
                    onClick = viewModel::installBuiltApk,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = { apkSaver.launch("${state.activeProject?.slug ?: "app"}-debug.apk") },
                    shape = RoundedCornerShape(100),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) {
                    Text(
                        text = stringResource(R.string.files_save_copy),
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }

        var lastActiveTab by remember { mutableStateOf(activeTab) }
        val buildTabForward = activeTab >= lastActiveTab
        androidx.compose.runtime.SideEffect { lastActiveTab = activeTab }

        ForgePillTabs(
            items = listOf(
                stringResource(R.string.launch_tab_stages),
                if (build.issues.isEmpty()) {
                    stringResource(R.string.launch_tab_issues)
                } else {
                    stringResource(R.string.launch_tab_issues_count, build.issues.size)
                },
                stringResource(R.string.launch_tab_log),
                stringResource(R.string.launch_tab_logcat),
            ),
            selectedIndex = activeTab,
            onSelect = { activeTab = it },
        )

        androidx.compose.animation.AnimatedContent(
            targetState = activeTab,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { ForgeMotion.tabTransition(forward = buildTabForward, reduceMotion = reduceMotion) },
            label = "androidBuildTabSwitch",
        ) { tabIdx ->
        when (tabIdx) {
            0 -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                stageRows.forEachIndexed { index, row ->
                    val status = stageRowStatus(index, lastRow, build.phase)
                    val muted = status == ForgeStatus.TODO || status == ForgeStatus.SKIPPED
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ForgeStatusIcon(status, iconSize = 20.dp)
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(row.title),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.Medium,
                            color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }

            1 -> BuildIssuesList(
                issues = build.issues,
                onAskAgentToFix = { issue ->
                    viewModel.sendPrompt(issue.formatForAgentPrompt())
                    viewModel.openConversation()
                },
                onOpenFile = { path ->
                    viewModel.openFilesTabForEditing(path)
                },
                modifier = Modifier.fillMaxSize(),
            )

            2 -> {
                val consoleShape = RoundedCornerShape(12.dp)
                val logScrollState = rememberScrollState()
                LaunchedEffect(build.log.length) {
                    if (busy) {
                        logScrollState.scrollTo(logScrollState.maxValue)
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .clip(consoleShape)
                        .background(tokens.terminalBackground)
                        .verticalScroll(logScrollState)
                        .padding(12.dp),
                ) {
                    SelectionContainer {
                        Text(
                            text = build.log.ifBlank { stringResource(R.string.launch_no_build_output) },
                            color = tokens.terminalText,
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.caption,
                            lineHeight = 17.sp,
                        )
                    }
                }
            }

            else -> LogcatPanel(
                state = state,
                onStartLogcat = viewModel::startProjectLogcat,
                onStopLogcat = viewModel::stopProjectLogcat,
                onClearLogcat = viewModel::clearProjectLogcat,
                onSetMinLevel = viewModel::setLogcatMinimumLevel,
                onSetQuery = viewModel::setLogcatQuery,
                onFixCrashWithAgent = viewModel::fixLogcatCrashWithAgent,
                modifier = Modifier.fillMaxSize(),
            )
        }
        }
    }
}

@Composable
private fun BuildIssuesList(
    issues: List<AndroidBuildIssue>,
    onAskAgentToFix: (AndroidBuildIssue) -> Unit,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    if (issues.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.launch_zero_compiler_or_aapt2),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(issues) { issue ->
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = tokens.dangerText,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = messageText(issue.title),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    issue.filePath?.let { path ->
                        Text(
                            text = buildString {
                                append(path)
                                issue.line?.let { append(":").append(it) }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.caption,
                            fontWeight = FontWeight.SemiBold,
                            color = tokens.accentText,
                        )
                    }
                    Text(
                        text = messageText(issue.detail),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                    issue.suggestion?.let { hint ->
                        Text(
                            text = messageText(hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onAskAgentToFix(issue) }) {
                            Text(stringResource(R.string.launch_ask_agent_to_fix))
                        }
                        issue.filePath?.let { path ->
                            OutlinedButton(onClick = { onOpenFile(path) }) {
                                Text(stringResource(R.string.launch_open_file))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogcatPanel(
    state: ForgeUiState,
    onStartLogcat: () -> Unit,
    onStopLogcat: () -> Unit,
    onClearLogcat: () -> Unit,
    onSetMinLevel: (AndroidLogLevel) -> Unit,
    onSetQuery: (String) -> Unit,
    onFixCrashWithAgent: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val logcat = state.androidLogcat
    val tokens = LocalForgeThemeTokens.current
    val hasCrashOrError = remember(logcat.lines) {
        logcat.lines.any { it.level == AndroidLogLevel.ERROR || it.level == AndroidLogLevel.FATAL }
    }
    val filteredLines = remember(logcat.lines, logcat.minimumLevel, logcat.query) {
        val q = logcat.query.trim()
        logcat.lines.filter { line ->
            line.level.ordinal >= logcat.minimumLevel.ordinal &&
                (q.isEmpty() || line.text.contains(q, ignoreCase = true))
        }
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = logcat.message ?: stringResource(R.string.logcat_uid_scoped, logcat.packageName ?: stringResource(R.string.logcat_app_fallback)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (logcat.lines.isNotEmpty()) {
                OutlinedButton(onClick = onClearLogcat) {
                    Text(stringResource(R.string.launch_clear_logcat))
                }
            }
            if (logcat.running) {
                OutlinedButton(onClick = onStopLogcat) {
                    Text(stringResource(R.string.launch_pause))
                }
            } else {
                Button(onClick = onStartLogcat) {
                    Text(stringResource(R.string.launch_stream_logcat))
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (hasCrashOrError) {
                Button(
                    onClick = onFixCrashWithAgent,
                    shape = RoundedCornerShape(100),
                ) {
                    Text(stringResource(R.string.logcat_fix_crash_with_agent), fontWeight = FontWeight.SemiBold)
                }
            }
            AndroidLogLevel.entries.forEach { level ->
                FilterChip(
                    selected = logcat.minimumLevel == level,
                    onClick = { onSetMinLevel(level) },
                    label = { Text(level.name) },
                )
            }
        }

        ForgeTextField(
            kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Search,
            value = logcat.query,
            onValueChange = onSetQuery,
            placeholder = { Text(stringResource(R.string.launch_filter_logcat)) },
            modifier = Modifier.fillMaxWidth(),
        )

        val logcatShape = RoundedCornerShape(10.dp)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(logcatShape)
                .background(tokens.terminalBackground)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, logcatShape)
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(filteredLines, key = { it.id }) { line ->
                val color = when (line.level) {
                    AndroidLogLevel.FATAL, AndroidLogLevel.ERROR -> tokens.dangerText
                    AndroidLogLevel.WARNING -> tokens.warningText
                    AndroidLogLevel.INFO -> tokens.accentText
                    else -> tokens.terminalText
                }
                Text(
                    text = line.text,
                    color = color,
                    fontFamily = FontFamily.Monospace,
                    fontSize = ForgeType.caption,
                    lineHeight = 15.sp,
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPreviewScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var urlInput by rememberSaveable(state.previewUrl) { mutableStateOf(state.previewUrl) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val tokens = LocalForgeThemeTokens.current
    val colors = MaterialTheme.colorScheme
    val webViewBgArgb = tokens.terminalBackground.toArgb()

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            // Detach before destroy(): destroying a WebView that is still attached throws on some WebView builds.
            webViewRef?.let { view ->
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                runCatching { view.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAndPreviewScreen", it) }
            }
            webViewRef = null
        }
    }

    LaunchedEffect(state.previewUrl) {
        val target = state.previewUrl.trim()
        if (target.isNotEmpty() && webViewRef?.url != target) {
            webViewRef?.loadUrl(target)
        }
    }

    fun openUrl(raw: String) {
        val trimmed = raw.trim()
        val normalized = when {
            trimmed.startsWith(":") && trimmed.drop(1).all { it.isDigit() } ->
                "http://127.0.0.1$trimmed"
            trimmed.isNotEmpty() && trimmed.all { it.isDigit() } ->
                "http://127.0.0.1:$trimmed"
            trimmed.startsWith("localhost", ignoreCase = true) || trimmed.startsWith("127.0.0.1") ->
                "http://$trimmed"
            else -> trimmed
        }
        val uriScheme = android.net.Uri.parse(normalized).scheme?.lowercase()
        if (normalized.isNotEmpty() && (uriScheme == "http" || uriScheme == "https")) {
            urlInput = normalized
            viewModel.updatePreviewUrl(normalized)
            webViewRef?.loadUrl(normalized)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (state.activeTab == ForgeTab.PROJECTS) {
            ForgeProjectHeader(
                title = state.activeProject?.name ?: stringResource(R.string.launch_preview),
                accentSeed = state.activeProject?.name,
                subtitle = stringResource(R.string.launch_preview) + " · " + stringResource(R.string.launch_local_dev_server),
                onBack = { viewModel.projectBack() },
            )
        } else {
            // Opened from Tools: same header and Back as every other tool screen.
            ForgeProjectHeader(
                title = stringResource(R.string.launch_preview),
                subtitle = stringResource(R.string.launch_local_dev_server),
                onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
            )
        }

        var previewMode by rememberSaveable { mutableStateOf(0) } // 0 = Web Preview, 1 = UI Inspector
        val reduceMotion = rememberReduceMotion()
        ForgePillTabs(
            items = listOf(
                stringResource(R.string.inspector_tab_web),
                stringResource(R.string.inspector_tab_ui),
            ),
            selectedIndex = previewMode,
            onSelect = { previewMode = it },
        )

        androidx.compose.animation.AnimatedContent(
            targetState = previewMode,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            transitionSpec = { ForgeMotion.tabTransition(forward = targetState >= initialState, reduceMotion = reduceMotion) },
            label = "previewModeSwitch",
        ) { mode ->
        if (mode == 1) {
            UiInspectorPanel(
                state = state,
                viewModel = viewModel,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
        Column(modifier = Modifier.fillMaxSize()) {
        // Address bar: status dot, URL, go arrow; refresh sits outside the bar.
        var opened by rememberSaveable { mutableStateOf(false) }
        val serverUp = state.activeLocalPorts.isNotEmpty() || opened
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = tokens.cardContainerColor,
                contentColor = colors.onSurface,
                border = tokens.cardBorderStroke,
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (serverUp) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.5f)),
                    )
                    TextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        singleLine = true,
                        keyboardOptions = ForgeFieldKind.URL.keyboardOptions(ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = { opened = true; openUrl(urlInput) }),
                        placeholder = { Text(stringResource(R.string.launch_preview_url), color = colors.onSurfaceVariant.copy(alpha = 0.6f)) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            errorContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                    ForgePillIcon(
                        icon = Icons.Default.ArrowForward,
                        description = stringResource(R.string.launch_open),
                        onClick = {
                            opened = true
                            openUrl(urlInput)
                        },
                    )
                }
            }
            ForgePillIcon(
                icon = Icons.Default.Refresh,
                description = stringResource(R.string.launch_scan_ports_and_reload),
                onClick = {
                    viewModel.scanLocalPreviewPorts()
                    webViewRef?.reload()
                },
            )
        }

        if (state.activeLocalPorts.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.activeLocalPorts.forEach { port ->
                    val portUrl = "http://127.0.0.1:$port"
                    AssistChip(
                        onClick = {
                            urlInput = portUrl
                            openUrl(portUrl)
                        },
                        shape = RoundedCornerShape(100),
                        label = { Text(":$port", fontFamily = FontFamily.Monospace) },
                    )
                }
            }
        }

        if (!serverUp) {
            // Nothing is listening yet: say so instead of showing a blank WebView. (if/else, not an early return: a
            // `return@Column` inside this composable lambda is a suspected cause of the Compose Stack.pop crash.)
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                ForgeEmptyState(
                    glyph = ForgeGlyph.GLOBE,
                    title = stringResource(R.string.launch_preview_not_running),
                    message = stringResource(R.string.launch_preview_not_running_hint),
                    actionLabel = stringResource(R.string.preview_open_terminal),
                    onAction = {
                        // Inside a project the terminal is a pane of that project; elsewhere it is the Terminal tab.
                        if (state.activeTab == ForgeTab.PROJECTS) viewModel.selectProjectPane(ProjectPane.TERMINAL) else viewModel.selectTab(ForgeTab.TERMINAL)
                    },
                )
            }
        } else {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(16.dp)),
            factory = { context ->
                // Devices without a usable WebView provider throw here; show an empty view instead of force-closing.
                val created = runCatching { WebView(context) }.getOrNull()
                created?.apply {
                    setBackgroundColor(webViewBgArgb)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: android.webkit.WebResourceRequest,
                        ): Boolean = request.url.scheme?.lowercase() !in setOf("http", "https")

                        // Without this the app process is killed when the renderer dies (e.g. low memory).
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail,
                        ): Boolean {
                            if (webViewRef === view) webViewRef = null
                            (view.parent as? android.view.ViewGroup)?.removeView(view)
                            runCatching { view.destroy() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAndPreviewScreen", it) }
                            return true
                        }
                    }
                    runCatching { loadUrl(state.previewUrl) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAndPreviewScreen", it) }
                    webViewRef = this
                } ?: android.view.View(context)
            },
            update = { view ->
                (view as? WebView)?.let { webView ->
                    webView.setBackgroundColor(webViewBgArgb)
                    webViewRef = webView
                }
            },
            // The view leaves composition when the server stops; destroy it there, not only when the screen closes.
            onRelease = { view ->
                if (webViewRef === view) webViewRef = null
                (view.parent as? android.view.ViewGroup)?.removeView(view)
                (view as? WebView)?.let { runCatching { it.stopLoading(); it.destroy() } }
            },
        )
        }
        }
        }
        }
    }
}

@Composable
private fun UiInspectorPanel(
    state: ForgeUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    var selectedNode by remember(state.uiInspectorNodes) { mutableStateOf<UiInspectorNode?>(null) }
    var instruction by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier.padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.inspector_tab_ui),
                fontSize = ForgeType.cardTitle,
                fontWeight = FontWeight.SemiBold,
            )
            ForgePrimaryButton(
                text = stringResource(R.string.inspector_scan_ui),
                enabled = !state.uiInspectorBusy,
                onClick = viewModel::captureUiInspectorHierarchy,
            )
        }

        selectedNode?.let { node ->
            ForgeThemedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = buildString {
                            append(node.className)
                            if (node.id.isNotBlank()) append(" #").append(node.id)
                            append(" ").append(node.bounds)
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = ForgeType.caption,
                        fontWeight = FontWeight.SemiBold,
                        color = tokens.accentText,
                    )
                    if (node.text.isNotBlank()) {
                        Text(
                            text = node.text,
                            fontSize = ForgeType.secondary,
                            maxLines = 2,
                        )
                    }
                    ForgeTextField(
                        kind = ForgeFieldKind.TEXT,
                        imeAction = ImeAction.Done,
                        value = instruction,
                        onValueChange = { instruction = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.inspector_instruction_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.inspector_edit_with_agent),
                        enabled = instruction.isNotBlank(),
                        onClick = {
                            viewModel.editUiElementWithAgent(node, instruction)
                            instruction = ""
                            selectedNode = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        if (state.uiInspectorNodes.isEmpty()) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.inspector_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = ForgeType.secondary,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.uiInspectorNodes) { node ->
                    val isSelected = selectedNode == node
                    ForgeThemedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(role = Role.Button) { selectedNode = node },
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = buildString {
                                        append(node.className)
                                        if (node.id.isNotBlank()) append(" · #").append(node.id)
                                    },
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = ForgeType.caption,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) tokens.accentText else MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = node.bounds,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = ForgeType.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (node.text.isNotBlank()) {
                                Text(
                                    text = node.text,
                                    fontSize = ForgeType.secondary,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
