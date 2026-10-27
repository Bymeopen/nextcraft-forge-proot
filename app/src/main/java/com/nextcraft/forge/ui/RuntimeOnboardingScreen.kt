@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.runtime.RuntimeSetupStatus

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
    val setupState = state.setupSnapshot
    val setupRunning = setupState.status == RuntimeSetupStatus.RUNNING
    val palette = MaterialTheme.colorScheme
    val themeTokens = LocalForgeThemeTokens.current

    Surface(
        color = palette.background,
        contentColor = palette.onBackground,
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .statusBarsPadding()
                    .padding(vertical = 12.dp),
            ) {
                ForgeLargeTitle(
                    title = stringResource(R.string.onb_title),
                    subtitle = stringResource(R.string.onb_subtitle),
                )

                ForgeSectionLabel(stringResource(R.string.onb_checks))
                EnvironmentCheckItem(
                    titleText = stringResource(R.string.shell_check_arm64),
                    passed = state.arm64Supported,
                    summaryText = stringResource(
                        if (state.arm64Supported) R.string.shell_supported else R.string.shell_requires_arm64,
                    ),
                )
                val executionEngineReady = state.rootAvailable || state.prootAvailable
                val engineSummaryRes = when {
                    state.rootAvailable -> R.string.shell_root_granted
                    state.prootAvailable -> R.string.shell_proot_fallback
                    else -> R.string.shell_root_required
                }
                EnvironmentCheckItem(
                    titleText = stringResource(R.string.shell_check_root),
                    passed = executionEngineReady,
                    summaryText = stringResource(engineSummaryRes),
                )
                if (!executionEngineReady) {
                    Text(
                        text = stringResource(R.string.onb_root_help),
                        fontSize = ForgeType.secondary,
                        color = palette.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = ForgeSpace.screen, vertical = 6.dp),
                    )
                }
                EnvironmentCheckItem(
                    titleText = stringResource(R.string.shell_check_rootfs),
                    passed = state.runtimeInstalled,
                    summaryText = if (state.runtimeInstalled) {
                        stringResource(R.string.shell_installed_ready)
                    } else {
                        runtimeText(setupState.message)
                    },
                    active = !state.runtimeInstalled && setupRunning,
                )

                ForgeSectionLabel(stringResource(R.string.onb_stacks))
                Surface(
                    shape = RoundedCornerShape(18.dp),
                    color = themeTokens.cardContainerColor,
                    contentColor = palette.onSurface,
                    border = themeTokens.cardBorderStroke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp),
                ) {
                    Column(modifier = Modifier.padding(all = 4.dp)) {
                        for (stackOption in DevStack.entries) {
                            ForgeToggleRow(
                                title = stackOption.displayLabel(),
                                subtitle = stackOption.displayDescription(),
                                checked = stackOption in state.selectedDevStacks,
                                enabled = !setupRunning,
                                onCheckedChange = { onToggleStack(stackOption) },
                                modifier = Modifier.padding(horizontal = 8.dp),
                            )
                        }
                    }
                }

                if (setupState.status != RuntimeSetupStatus.IDLE) {
                    val clampedRatio = setupState.progress.coerceIn(0f, 1f)
                    val pctValue = (clampedRatio * 100f).toInt()
                    val badgeStatus = when (setupState.status) {
                        RuntimeSetupStatus.RUNNING -> ForgeStatus.RUNNING
                        RuntimeSetupStatus.CANCELLED -> ForgeStatus.SKIPPED
                        else -> if (setupState.errorMessage != null) ForgeStatus.FAILED else ForgeStatus.DONE
                    }
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = themeTokens.cardContainerColor,
                        contentColor = palette.onSurface,
                        border = themeTokens.cardBorderStroke,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, end = 14.dp, top = 16.dp),
                    ) {
                        Column(modifier = Modifier.padding(all = 16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                ForgeStatusIcon(status = badgeStatus, iconSize = 20.dp)
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = runtimeText(setupState.message),
                                        fontSize = ForgeType.body,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 2,
                                    )
                                    val hasErr = setupState.errorMessage != null
                                    Text(
                                        text = setupState.errorMessage ?: stringResource(badgeStatus.labelRes),
                                        fontSize = ForgeType.secondary,
                                        color = if (hasErr) palette.error else palette.onSurfaceVariant,
                                        modifier = if (hasErr) Modifier.forgeLive() else Modifier,
                                    )
                                }
                                Text(
                                    text = "$pctValue%",
                                    fontSize = ForgeType.body,
                                    fontWeight = FontWeight.SemiBold,
                                    color = palette.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(4.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(themeTokens.cardElevatedColor),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(clampedRatio)
                                        .fillMaxHeight()
                                        .background(palette.primary),
                                )
                            }
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 8.dp),
            ) {
                Divider(color = palette.outlineVariant, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(12.dp))
                if (setupRunning) {
                    OutlinedButton(
                        onClick = onPauseSetup,
                        shape = RoundedCornerShape(100),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .heightIn(min = 48.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.launch_pause),
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                } else {
                    val primaryBtnLabel = stringResource(
                        if (setupState.status == RuntimeSetupStatus.CANCELLED) {
                            R.string.shell_resume_setup
                        } else {
                            R.string.shell_install_runtime
                        },
                    )
                    ForgePrimaryButton(
                        text = primaryBtnLabel,
                        onClick = onStartSetup,
                        enabled = state.arm64Supported,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .heightIn(min = 48.dp),
                    )
                }
                FlowRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalArrangement = Arrangement.Center,
                ) {
                    ForgeTextButton(onClick = onRepairRootfs, enabled = !setupRunning) {
                        Text(stringResource(R.string.onb_repair))
                    }
                    ForgeTextButton(onClick = onRefreshStatus) {
                        Text(stringResource(R.string.onb_recheck))
                    }
                    ForgeTextButton(onClick = onEnterWorkspaceAnyway) {
                        Text(
                            text = stringResource(R.string.onb_continue_anyway),
                            color = palette.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentCheckItem(
    titleText: String,
    passed: Boolean,
    summaryText: String,
    active: Boolean = false,
) {
    val iconState = when {
        passed -> ForgeStatus.DONE
        active -> ForgeStatus.RUNNING
        else -> ForgeStatus.FAILED
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ForgeStatusIcon(status = iconState, iconSize = 20.dp)
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = titleText,
                fontSize = ForgeType.rowTitle,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = summaryText,
                fontSize = ForgeType.secondary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
