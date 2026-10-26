package com.nextcraft.forge.ui

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.nextcraft.forge.CrashReporter
import com.nextcraft.forge.R

/**
 * Asks once, after a crash, whether to share the report. Opt-in: nothing is sent by the app itself, the
 * report only leaves the phone through the share sheet the person chooses.
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    var visible by remember { mutableStateOf(CrashReporter.hasUnseen(context)) }
    if (!visible) return
    val dismiss = {
        CrashReporter.markSeen(context)
        visible = false
    }
    ForgeAlertDialog(
        onDismissRequest = dismiss,
        title = { Text(stringResource(R.string.crash_prompt_title)) },
        text = { Text(stringResource(R.string.crash_prompt_message)) },
        confirmButton = {
            TextButton(onClick = {
                CrashReporter.shareIntent(context)?.let { context.startActivity(it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                dismiss()
            }) { Text(stringResource(R.string.crash_prompt_share)) }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text(stringResource(R.string.crash_prompt_later)) } },
    )
}
