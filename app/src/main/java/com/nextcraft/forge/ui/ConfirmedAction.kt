package com.nextcraft.forge.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/**
 * Wraps an action that is hard to undo (uninstalling a runtime component): [trigger] draws the button and gets the
 * click handler that opens a confirmation, and [onConfirm] only runs after the person agrees.
 */
@Composable
internal fun ConfirmedAction(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    trigger: @Composable (onClick: () -> Unit) -> Unit,
) {
    var asking by remember { mutableStateOf(false) }
    trigger { asking = true }
    if (asking) {
        val tokens = LocalForgeThemeTokens.current
        ForgeAlertDialog(
            modifier = Modifier.forgePopIn(),
            onDismissRequest = { asking = false },
            shape = RoundedCornerShape(24.dp),
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { asking = false; onConfirm() }) { Text(confirmLabel, color = tokens.dangerText) }
            },
            dismissButton = {
                TextButton(onClick = { asking = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }
}

/** The standard wording for uninstalling [name]; the caller supplies what runs on confirmation. */
@Composable
internal fun UninstallConfirmed(name: String, onConfirm: () -> Unit, trigger: @Composable (onClick: () -> Unit) -> Unit) {
    ConfirmedAction(
        title = stringResource(R.string.confirm_uninstall_title, name),
        message = stringResource(R.string.confirm_uninstall_message),
        confirmLabel = stringResource(R.string.settings_agent_remove),
        onConfirm = onConfirm,
        trigger = trigger,
    )
}
