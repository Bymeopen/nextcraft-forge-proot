package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.network.GeminiImageModels

private val VIDEO_ASPECTS = listOf("16:9", "9:16")
private val VIDEO_RESOLUTIONS = listOf("720p", "1080p")

/** Nano Banana: describe a picture (or, with an image attached, how to change it) and it lands in the chat. */
@Composable
internal fun ImageGenerationDialog(state: ForgeUiState, viewModel: MainViewModel, onDismiss: () -> Unit) {
    val hasKey = remember(state.geminiKeyVersion) { viewModel.hasGeminiImageKey() }
    // The key uses plain remember: rememberSaveable would write it into the saved-instance Bundle.
    var key by remember { mutableStateOf("") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var modelIndex by rememberSaveable { mutableStateOf(0) }
    var mode by rememberSaveable { mutableStateOf(0) }
    var aspectIndex by rememberSaveable { mutableStateOf(0) }
    var resolutionIndex by rememberSaveable { mutableStateOf(0) }
    val editing = state.pendingAttachments.firstOrNull { it.mimeType.startsWith("image/") }

    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasKey) {
                    Text(stringResource(R.string.image_key_desc), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ForgeTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text(stringResource(R.string.image_key_hint)) },
                        singleLine = true,
                        kind = ForgeFieldKind.SECRET,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ForgePillTabs(
                        items = listOf(stringResource(R.string.media_mode_image), stringResource(R.string.media_mode_video)),
                        selectedIndex = mode,
                        onSelect = { mode = it },
                    )
                    ForgeTextField(
                        value = prompt,
                        onValueChange = { prompt = it.take(2000) },
                        label = { Text(stringResource(R.string.image_prompt_hint)) },
                        singleLine = false,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp),
                    )
                    if (editing != null) {
                        Text(
                            stringResource(R.string.image_editing, editing.displayName),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (mode == 0) {
                        ForgePillTabs(
                            items = listOf(stringResource(R.string.image_model_fast), stringResource(R.string.image_model_pro)),
                            selectedIndex = modelIndex,
                            onSelect = { modelIndex = it },
                        )
                    } else {
                        ForgePillTabs(items = VIDEO_ASPECTS, selectedIndex = aspectIndex, onSelect = { aspectIndex = it })
                        ForgePillTabs(items = VIDEO_RESOLUTIONS, selectedIndex = resolutionIndex, onSelect = { resolutionIndex = it })
                        Text(stringResource(R.string.video_note), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        },
        confirmButton = {
            if (!hasKey) {
                TextButton(enabled = key.isNotBlank(), onClick = { viewModel.saveGeminiImageKey(key); key = "" }) {
                    Text(stringResource(R.string.image_key_save))
                }
            } else {
                TextButton(
                    enabled = prompt.isNotBlank() && !state.imageGenerating,
                    onClick = {
                        if (mode == 0) {
                            viewModel.generateImage(prompt, GeminiImageModels.all[modelIndex.coerceIn(0, GeminiImageModels.all.lastIndex)])
                        } else {
                            viewModel.generateVideo(prompt, VIDEO_ASPECTS[aspectIndex.coerceIn(0, 1)], VIDEO_RESOLUTIONS[resolutionIndex.coerceIn(0, 1)])
                        }
                        prompt = ""
                        onDismiss()
                    },
                ) { Text(stringResource(R.string.image_generate)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) } },
    )
}
