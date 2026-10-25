@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.R
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.imePadding

@Composable
internal fun RuntimeOnboardingScreen(
    state: ForgeUiState,
    onToggleStack: (DevStack) -> Unit,
    onStartSetup: () -> Unit,
    onPauseSetup: () -> Unit,
    onRepairRootfs: () -> Unit,
    onRefreshStatus: () -> Unit,
    onEnterWorkspaceAnyway: () -> Unit,
) {
    val snap = state.setupSnapshot
    val isInstalling = snap.status == RuntimeSetupStatus.RUNNING
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current

    // The gate renders before the main Scaffold, so it must set its own background and content
    // colour: without this Surface, LocalContentColor falls back to black on the dark themes.
    Surface(
        color = scheme.background,
        contentColor = scheme.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
    Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
    Column(
        modifier = Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        ForgeLargeTitle(
            title = stringResource(R.string.onb_title),
            subtitle = stringResource(R.string.onb_subtitle),
        )

        ForgeSectionLabel(stringResource(R.string.onb_checks))
        StatusCheckRow(
            label = stringResource(R.string.shell_check_arm64),
            ok = state.arm64Supported,
            detail = stringResource(if (state.arm64Supported) R.string.shell_supported else R.string.shell_requires_arm64),
        )
        StatusCheckRow(
            label = stringResource(R.string.shell_check_root),
            ok = state.prootAvailable,
            detail = stringResource(if (state.prootAvailable) R.string.shell_proot_fallback else R.string.shell_root_required),
        )
        if (!state.prootAvailable) {
            Text(
                text = stringResource(R.string.onb_root_help),
                fontSize = ForgeType.secondary,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = ForgeSpace.screen, vertical = 6.dp),
            )
        }
        StatusCheckRow(
            label = stringResource(R.string.shell_check_rootfs),
            ok = state.runtimeInstalled,
            detail = if (state.runtimeInstalled) stringResource(R.string.shell_installed_ready) else runtimeText(snap.message),
            inProgress = !state.runtimeInstalled && snap.status == RuntimeSetupStatus.RUNNING,
        )

        ForgeSectionLabel(stringResource(R.string.onb_stacks))
        // One card groups the stack toggles; the description alone is enough (installsSummary repeated it).
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = tokens.cardContainerColor,
            contentColor = scheme.onSurface,
            border = tokens.cardBorderStroke,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                DevStack.entries.forEach { stack ->
                    ForgeToggleRow(
                        title = stack.displayLabel(),
                        subtitle = stack.displayDescription(),
                        checked = stack in state.selectedDevStacks,
                        enabled = !isInstalling,
                        onCheckedChange = { onToggleStack(stack) },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }

        if (snap.status != RuntimeSetupStatus.IDLE) {
            // Progress card: status glyph, message, percent on the right, a soft bar underneath.
            val pct = (snap.progress.coerceIn(0f, 1f) * 100f).toInt()
            val progressStatus = when (snap.status) {
                RuntimeSetupStatus.RUNNING -> ForgeStatus.RUNNING
                RuntimeSetupStatus.CANCELLED -> ForgeStatus.SKIPPED
                else -> if (snap.errorMessage != null) ForgeStatus.FAILED else ForgeStatus.DONE
            }
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = tokens.cardContainerColor,
                contentColor = scheme.onSurface,
                border = tokens.cardBorderStroke,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 16.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ForgeStatusIcon(status = progressStatus, iconSize = 20.dp)
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(runtimeText(snap.message), fontSize = ForgeType.body, fontWeight = FontWeight.Medium, maxLines = 2)
                            Text(
                                text = snap.errorMessage ?: stringResource(progressStatus.labelRes),
                                fontSize = ForgeType.secondary,
                                color = if (snap.errorMessage != null) scheme.error else scheme.onSurfaceVariant,
                                modifier = if (snap.errorMessage != null) Modifier.forgeLive() else Modifier,
                            )
                        }
                        Text(
                            text = "$pct%",
                            fontSize = ForgeType.body,
                            fontWeight = FontWeight.SemiBold,
                            color = scheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(tokens.cardElevatedColor),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(snap.progress.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .background(scheme.primary),
                        )
                    }
                }
            }
        }

    }
    // The actions stay pinned: on 360dp phones the install button used to sit below the fold of a long checklist.
    Column(modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 8.dp)) {
        Divider(color = scheme.outlineVariant, thickness = 0.5.dp)
        Spacer(modifier = Modifier.height(12.dp))
        // One primary capsule (install / resume / pause), then quiet text actions, then the escape link.
        if (isInstalling) {
            OutlinedButton(
                onClick = onPauseSetup,
                shape = RoundedCornerShape(100),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.launch_pause), fontWeight = FontWeight.SemiBold) }
        } else {
            ForgePrimaryButton(
                text = stringResource(if (snap.status == RuntimeSetupStatus.CANCELLED) R.string.shell_resume_setup else R.string.shell_install_runtime),
                onClick = onStartSetup,
                enabled = state.arm64Supported,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .heightIn(min = 48.dp),
            )
        }
        // The three quiet actions share one wrapping row: a separate row for each cost ~100dp of the checklist's height.
        @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalArrangement = Arrangement.Center,
        ) {
            ForgeTextButton(onClick = onRepairRootfs, enabled = !isInstalling) { Text(stringResource(R.string.onb_repair)) }
            ForgeTextButton(onClick = onRefreshStatus) { Text(stringResource(R.string.onb_recheck)) }
            ForgeTextButton(onClick = onEnterWorkspaceAnyway) {
                Text(stringResource(R.string.onb_continue_anyway), color = scheme.onSurfaceVariant)
            }
        }
    }
    }
    }
}

@Composable
private fun StatusCheckRow(
    label: String,
    ok: Boolean,
    detail: String,
    // True while the step is still being installed: shown as running instead of a red failure.
    inProgress: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeStatusIcon(if (ok) ForgeStatus.DONE else if (inProgress) ForgeStatus.RUNNING else ForgeStatus.FAILED, iconSize = 20.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
            Text(text = detail, fontSize = ForgeType.secondary, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
