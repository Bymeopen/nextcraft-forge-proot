@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
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

internal data class FileSearchResult(
    val entries: List<WorkspaceEntry>,
    val matches: Set<String>,
)

internal fun searchWorkspaceEntries(
    entries: List<WorkspaceEntry>,
    query: String,
): FileSearchResult {
    val normalizedQuery = query.trim().lowercase()
    if (normalizedQuery.isEmpty()) return FileSearchResult(entries, emptySet())
    val matchFullPath = '/' in normalizedQuery
    val directMatches = entries.asSequence()
        .filter { item ->
            val candidate = if (matchFullPath) item.path else item.name
            candidate.lowercase().contains(normalizedQuery)
        }
        .map(WorkspaceEntry::path)
        .toSet()

    if (directMatches.isEmpty()) return FileSearchResult(emptyList(), emptySet())

    val retainedPaths = HashSet<String>(directMatches)
    for (matchedPath in directMatches) {
        var ancestor = matchedPath.substringBeforeLast('/', "")
        while (ancestor.isNotEmpty() && retainedPaths.add(ancestor)) {
            ancestor = ancestor.substringBeforeLast('/', "")
        }
    }
    return FileSearchResult(
        entries = entries.filter { it.path in retainedPaths },
        matches = directMatches,
    )
}

@Composable
internal fun FileSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    resultCount: Int?,
    modifier: Modifier = Modifier,
) {
    val themeTokens = LocalForgeThemeTokens.current
    val palette = MaterialTheme.colorScheme
    val inputFocusRequester = remember { FocusRequester() }
    val hostFocusManager = LocalFocusManager.current

    LaunchedEffect(Unit) {
        runCatching { inputFocusRequester.requestFocus() }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = themeTokens.cardContainerColor,
        contentColor = palette.onSurface,
        border = themeTokens.cardBorderStroke,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ForgeIcon(
                glyph = ForgeGlyph.SEARCH,
                tint = palette.onSurfaceVariant,
                size = 18.dp,
            )
            TextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.files_search_hint),
                        color = palette.onSurfaceVariant.copy(alpha = 0.6f),
                        maxLines = 1,
                    )
                },
                keyboardOptions = ForgeFieldKind.TEXT.keyboardOptions(ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { hostFocusManager.clearFocus() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    errorContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                ),
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .focusRequester(inputFocusRequester),
            )
            if (resultCount != null) {
                Text(
                    text = quantityStringResource(R.plurals.files_search_results, resultCount, resultCount),
                    fontSize = ForgeType.caption,
                    color = palette.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .background(palette.onSurface.copy(alpha = 0.07f), RoundedCornerShape(100))
                        .padding(horizontal = 9.dp, vertical = 3.dp),
                )
            }
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.files_search_clear),
                        tint = palette.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
