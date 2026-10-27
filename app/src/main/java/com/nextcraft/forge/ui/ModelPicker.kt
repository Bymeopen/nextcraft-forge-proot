@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.annotation.StringRes
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

data class ModelOption(
    val id: String,
    val label: String,
    val badge: String = "",
    @StringRes val labelRes: Int? = null,
)

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
    val palette = MaterialTheme.colorScheme
    var searchFilter by remember { mutableStateOf("") }
    val trimmedFilter = searchFilter.trim()
    val localizedMap = options.associate { item ->
        item.id to (item.labelRes?.let { stringResource(it) } ?: item.label)
    }
    val matchingModels = remember(options, trimmedFilter, localizedMap) {
        if (trimmedFilter.isEmpty()) {
            options
        } else {
            options.filter { item ->
                item.id.contains(trimmedFilter, ignoreCase = true) ||
                    item.label.contains(trimmedFilter, ignoreCase = true) ||
                    localizedMap[item.id].orEmpty().contains(trimmedFilter, ignoreCase = true)
            }
        }
    }
    val canOfferCustomId = trimmedFilter.isNotEmpty() && options.none { it.id.equals(trimmedFilter, ignoreCase = true) }

    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = ForgeSpace.card)
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.model_picker_select),
                        fontSize = ForgeType.screenTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.model_picker_available, options.size, title),
                        fontSize = ForgeType.secondary,
                        color = palette.onSurfaceVariant,
                    )
                }
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh, enabled = !loading) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.model_picker_refresh),
                            tint = palette.onSurfaceVariant,
                        )
                    }
                }
            }
            ForgeTextField(
                kind = ForgeFieldKind.IDENTIFIER,
                imeAction = ImeAction.Search,
                value = searchFilter,
                onValueChange = { searchFilter = it },
                placeholder = { Text(stringResource(R.string.model_picker_search)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (loading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        text = stringResource(R.string.model_picker_loading),
                        fontSize = ForgeType.secondary,
                        color = palette.onSurfaceVariant,
                    )
                }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (canOfferCustomId) {
                    item(key = "custom:$trimmedFilter") {
                        SelectableModelCard(
                            displayName = stringResource(R.string.model_picker_use_custom, trimmedFilter),
                            modelIdentifier = "",
                            tierBadge = "",
                            isSelected = false,
                            onChoose = { onSelect(trimmedFilter) },
                        )
                    }
                }
                items(matchingModels, key = { it.id }) { item ->
                    val resolvedBadge = when (item.badge) {
                        "latest" -> stringResource(R.string.model_badge_latest)
                        "fast" -> stringResource(R.string.model_badge_fast)
                        else -> item.badge
                    }
                    SelectableModelCard(
                        displayName = item.labelRes?.let { stringResource(it) } ?: item.label,
                        modelIdentifier = item.id,
                        tierBadge = resolvedBadge,
                        isSelected = item.id == selectedId,
                        onChoose = { onSelect(item.id) },
                    )
                }
                if (matchingModels.isEmpty() && !canOfferCustomId) {
                    item(key = "empty") {
                        ForgeEmptyState(
                            glyph = ForgeGlyph.SEARCH,
                            title = stringResource(R.string.model_picker_empty),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableModelCard(
    displayName: String,
    modelIdentifier: String,
    tierBadge: String,
    isSelected: Boolean,
    onChoose: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val themeTokens = LocalForgeThemeTokens.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (isSelected) themeTokens.cardElevatedColor else themeTokens.cardContainerColor,
        border = themeTokens.cardBorderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onChoose)
            .forgeSelected(isSelected),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ForgeSpace.cardInner, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = displayName,
                        fontSize = ForgeType.rowTitle,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (tierBadge.isNotEmpty()) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = themeTokens.cardElevatedColor,
                        ) {
                            Text(
                                text = tierBadge,
                                fontSize = ForgeType.caption,
                                color = palette.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                if (modelIdentifier.isNotEmpty() && modelIdentifier != displayName) {
                    Text(
                        text = modelIdentifier,
                        fontSize = ForgeType.secondary,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onSurfaceVariant,
                    )
                }
            }
            RadioButton(selected = isSelected, onClick = null)
        }
    }
}
