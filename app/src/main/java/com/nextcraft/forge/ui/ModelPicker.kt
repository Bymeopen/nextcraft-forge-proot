@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/** One selectable model: [id] is what the agent receives, [label] is what the user reads, [badge] a short tier hint. */
data class ModelOption(val id: String, val label: String, val badge: String = "", @androidx.annotation.StringRes val labelRes: Int? = null)

/**
 * Searchable model list shared by every agent (Claude Code, DeepSeek, Antigravity), shown as a bottom sheet:
 * title + "N available for <provider>", refresh, search, then one card per model. Typing an id that is not in the list
 * offers it as a custom model, so models the lists do not know yet stay usable.
 */
@Composable
fun ModelPickerDialog(
    title: String,
    options: List<ModelOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    loading: Boolean = false,
    onRefresh: (() -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val needle = query.trim()
    val filtered = remember(options, needle) {
        if (needle.isEmpty()) options else options.filter {
            it.id.contains(needle, ignoreCase = true) || it.label.contains(needle, ignoreCase = true)
        }
    }
    val offerCustom = needle.isNotEmpty() && options.none { it.id.equals(needle, ignoreCase = true) }

    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = ForgeSpace.card).padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.model_picker_select), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.model_picker_available, options.size, title),
                        fontSize = ForgeType.secondary,
                        color = scheme.onSurfaceVariant,
                    )
                }
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.model_picker_refresh), tint = scheme.onSurfaceVariant)
                    }
                }
            }
            ForgeTextField(
                kind = ForgeFieldKind.IDENTIFIER, imeAction = ImeAction.Search,
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.model_picker_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.model_picker_loading), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (offerCustom) {
                    item(key = "custom:$needle") {
                        ModelRow(
                            label = stringResource(R.string.model_picker_use_custom, needle),
                            id = "",
                            badge = "",
                            selected = false,
                            onClick = { onSelect(needle) },
                        )
                    }
                }
                items(filtered, key = { it.id }) { option ->
                    ModelRow(
                        label = option.labelRes?.let { stringResource(it) } ?: option.label,
                        id = option.id,
                        badge = when (option.badge) {
                            "latest" -> stringResource(R.string.model_badge_latest)
                            "fast" -> stringResource(R.string.model_badge_fast)
                            else -> option.badge
                        },
                        selected = option.id == selectedId,
                        onClick = { onSelect(option.id) },
                    )
                }
                if (filtered.isEmpty() && !offerCustom) {
                    item(key = "empty") {
                        ForgeEmptyState(glyph = ForgeGlyph.SEARCH, title = stringResource(R.string.model_picker_empty))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelRow(label: String, id: String, badge: String, selected: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) tokens.cardElevatedColor else tokens.cardContainerColor,
        border = tokens.cardBorderStroke,
        modifier = Modifier.fillMaxWidth().clickable(role = Role.RadioButton, onClick = onClick).forgeSelected(selected),
    ) {
        Row(modifier = Modifier.padding(horizontal = ForgeSpace.cardInner, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                    if (badge.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(shape = RoundedCornerShape(8.dp), color = tokens.cardElevatedColor) {
                            Text(
                                badge,
                                fontSize = ForgeType.caption,
                                color = scheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (id.isNotEmpty() && id != label) {
                    Text(id, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = scheme.onSurfaceVariant)
                }
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}
