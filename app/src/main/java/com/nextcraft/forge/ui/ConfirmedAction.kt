package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

private val PULL_REQUEST_STRATEGIES = listOf(
    "squash" to "Squash",
    "merge" to "Merge",
    "rebase" to "Rebase",
)

@Composable
internal fun ConfirmedAction(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    trigger: @Composable (onClick: () -> Unit) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    trigger { dialogOpen = true }
    if (!dialogOpen) return
    val themeTokens = LocalForgeThemeTokens.current
    val closeDialog = { dialogOpen = false }
    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = closeDialog,
        shape = RoundedCornerShape(24.dp),
        title = { Text(text = title, fontWeight = FontWeight.SemiBold) },
        text = { Text(text = message) },
        confirmButton = {
            TextButton(onClick = { closeDialog(); onConfirm() }) {
                Text(text = confirmLabel, color = themeTokens.dangerText)
            }
        },
        dismissButton = {
            TextButton(onClick = closeDialog) {
                Text(text = stringResource(R.string.agent_cancel))
            }
        },
    )
}

@Composable
internal fun MergePrConfirmed(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: (mergeMethod: String) -> Unit,
    trigger: @Composable (onClick: () -> Unit) -> Unit,
) {
    var dialogOpen by remember { mutableStateOf(false) }
    var strategyCursor by remember { mutableIntStateOf(0) }
    trigger { dialogOpen = true }
    if (!dialogOpen) return
    val themeTokens = LocalForgeThemeTokens.current
    val closeDialog = { dialogOpen = false }
    ForgeAlertDialog(
        modifier = Modifier.forgePopIn(),
        onDismissRequest = closeDialog,
        shape = RoundedCornerShape(24.dp),
        title = { Text(text = title, fontWeight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Text(text = message)
                ForgePillTabs(
                    items = PULL_REQUEST_STRATEGIES.map { (_, label) -> label },
                    selectedIndex = strategyCursor,
                    onSelect = { chosen -> strategyCursor = chosen },
                    equalWidth = true,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    closeDialog()
                    val safeIdx = strategyCursor.coerceIn(0, PULL_REQUEST_STRATEGIES.lastIndex)
                    onConfirm(PULL_REQUEST_STRATEGIES[safeIdx].first)
                },
            ) {
                Text(text = confirmLabel, color = themeTokens.dangerText)
            }
        },
        dismissButton = {
            TextButton(onClick = closeDialog) {
                Text(text = stringResource(R.string.agent_cancel))
            }
        },
    )
}

@Composable
internal fun UninstallConfirmed(
    name: String,
    onConfirm: () -> Unit,
    trigger: @Composable (onClick: () -> Unit) -> Unit,
) {
    ConfirmedAction(
        title = stringResource(R.string.confirm_uninstall_title, name),
        message = stringResource(R.string.confirm_uninstall_message),
        confirmLabel = stringResource(R.string.settings_agent_remove),
        onConfirm = onConfirm,
        trigger = trigger,
    )
}
