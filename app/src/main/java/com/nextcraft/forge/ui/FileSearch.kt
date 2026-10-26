@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.WorkspaceEntry

/**
 * Result of a file search: the rows to show (matches plus the folders that lead to them, in the original tree order)
 * and which of those rows actually match, so folders that are only on the way can be drawn quieter.
 */
internal data class FileSearchResult(val entries: List<WorkspaceEntry>, val matches: Set<String>)

/**
 * Filters a flat workspace listing by [query]. A plain word matches file and folder names, case-insensitively; a query
 * that contains "/" matches the whole relative path. A blank query returns the listing unchanged.
 */
internal fun searchWorkspaceEntries(entries: List<WorkspaceEntry>, query: String): FileSearchResult {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return FileSearchResult(entries, emptySet())
    val byPath = '/' in needle
    val matches = entries.filter { e -> (if (byPath) e.path else e.name).lowercase().contains(needle) }.map { it.path }.toSet()
    if (matches.isEmpty()) return FileSearchResult(emptyList(), emptySet())
    val keep = HashSet<String>(matches)
    matches.forEach { path ->
        var parent = path.substringBeforeLast('/', "")
        while (parent.isNotEmpty() && keep.add(parent)) parent = parent.substringBeforeLast('/', "")
    }
    return FileSearchResult(entries.filter { it.path in keep }, matches)
}

/** Capsule search field for the Files explorer: glyph, text, clear button. Takes focus when it appears. */
@Composable
internal fun FileSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int?,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val scheme = MaterialTheme.colorScheme
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = tokens.cardContainerColor,
        contentColor = scheme.onSurface,
        border = tokens.cardBorderStroke,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ForgeIcon(ForgeGlyph.SEARCH, tint = scheme.onSurfaceVariant, size = 18.dp)
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.files_search_hint), color = scheme.onSurfaceVariant.copy(alpha = 0.6f), maxLines = 1) },
                keyboardOptions = ForgeFieldKind.TEXT.keyboardOptions(ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focusManager.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).focusRequester(focusRequester),
            )
            if (resultCount != null) {
                Text(
                    text = quantityStringResource(R.plurals.files_search_results, resultCount, resultCount),
                    fontSize = ForgeType.caption,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .background(scheme.onSurface.copy(alpha = 0.07f), RoundedCornerShape(100))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.files_search_clear), tint = scheme.onSurfaceVariant)
                }
            }
        }
    }
}
