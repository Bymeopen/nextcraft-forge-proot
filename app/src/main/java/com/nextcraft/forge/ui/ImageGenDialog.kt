package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.network.GeminiImageModels

private val SUPPORTED_VIDEO_ASPECTS = listOf("16:9", "9:16")
private val SUPPORTED_VIDEO_RESOLUTIONS = listOf("720p", "1080p")

@Composable
internal fun ImageGenerationDialog(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onDismiss: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val isKeyConfigured = remember(state.geminiKeyVersion) { viewModel.hasGeminiImageKey() }
    var apiKeyDraft by remember { mutableStateOf("") }
    var generationPrompt by rememberSaveable { mutableStateOf("") }
    var chosenModelIdx by rememberSaveable { mutableIntStateOf(0) }
    var activeMediaMode by rememberSaveable { mutableIntStateOf(0) }
    var chosenAspectIdx by rememberSaveable { mutableIntStateOf(0) }
    var chosenResIdx by rememberSaveable { mutableIntStateOf(0) }
    val attachedSourceImage = state.pendingAttachments.firstOrNull { it.mimeType.startsWith("image/") }

    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.image_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!isKeyConfigured) {
                    Text(
                        text = stringResource(R.string.image_key_desc),
                        color = palette.onSurfaceVariant,
                    )
                    ForgeTextField(
                        value = apiKeyDraft,
                        onValueChange = { apiKeyDraft = it },
                        label = { Text(stringResource(R.string.image_key_hint)) },
                        singleLine = true,
                        kind = ForgeFieldKind.SECRET,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    ForgePillTabs(
                        items = listOf(
                            stringResource(R.string.media_mode_image),
                            stringResource(R.string.media_mode_video),
                        ),
                        selectedIndex = activeMediaMode,
                        onSelect = { activeMediaMode = it },
                    )
                    ForgeTextField(
                        value = generationPrompt,
                        onValueChange = { input -> generationPrompt = input.take(2000) },
                        label = { Text(stringResource(R.string.image_prompt_hint)) },
                        singleLine = false,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 96.dp),
                    )
                    if (attachedSourceImage != null) {
                        Text(
                            text = stringResource(R.string.image_editing, attachedSourceImage.displayName),
                            color = palette.onSurfaceVariant,
                        )
                    }
                    if (activeMediaMode == 0) {
                        ForgePillTabs(
                            items = listOf(
                                stringResource(R.string.image_model_fast),
                                stringResource(R.string.image_model_pro),
                            ),
                            selectedIndex = chosenModelIdx,
                            onSelect = { chosenModelIdx = it },
                        )
                    } else {
                        ForgePillTabs(
                            items = SUPPORTED_VIDEO_ASPECTS,
                            selectedIndex = chosenAspectIdx,
                            onSelect = { chosenAspectIdx = it },
                        )
                        ForgePillTabs(
                            items = SUPPORTED_VIDEO_RESOLUTIONS,
                            selectedIndex = chosenResIdx,
                            onSelect = { chosenResIdx = it },
                        )
                        Text(
                            text = stringResource(R.string.video_note),
                            color = palette.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isKeyConfigured) {
                TextButton(
                    enabled = apiKeyDraft.isNotBlank(),
                    onClick = {
                        viewModel.saveGeminiImageKey(apiKeyDraft)
                        apiKeyDraft = ""
                    },
                ) {
                    Text(stringResource(R.string.image_key_save))
                }
            } else {
                TextButton(
                    enabled = generationPrompt.isNotBlank() && !state.imageGenerating,
                    onClick = {
                        if (activeMediaMode == 0) {
                            val allModels = GeminiImageModels.all
                            val targetModel = allModels[chosenModelIdx.coerceIn(0, allModels.lastIndex)]
                            viewModel.generateImage(generationPrompt, targetModel)
                        } else {
                            val aspect = SUPPORTED_VIDEO_ASPECTS[chosenAspectIdx.coerceIn(0, 1)]
                            val res = SUPPORTED_VIDEO_RESOLUTIONS[chosenResIdx.coerceIn(0, 1)]
                            viewModel.generateVideo(generationPrompt, aspect, res)
                        }
                        generationPrompt = ""
                        onDismiss()
                    },
                ) {
                    Text(stringResource(R.string.image_generate))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.agent_cancel))
            }
        },
    )
}
