package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

private data class CommandDestination(
    val destinationTab: ForgeTab,
    val iconGlyph: ForgeGlyph,
    @StringRes val labelResId: Int,
)

private val allCommandDestinations: List<CommandDestination> by lazy {
    buildList {
        add(CommandDestination(ForgeTab.PROJECTS, ForgeGlyph.FOLDER, R.string.nav_projects))
        add(CommandDestination(ForgeTab.AGENT, ForgeGlyph.SLIDERS, R.string.nav_agent))
        add(CommandDestination(ForgeTab.TERMINAL, ForgeGlyph.CODE, R.string.nav_terminal))
        add(CommandDestination(ForgeTab.SETTINGS, ForgeGlyph.SLIDERS, R.string.nav_settings))
        for (group in toolSections) {
            for (item in group.entries) {
                add(CommandDestination(item.tab, item.glyph, item.title))
            }
        }
    }
}

@Composable
internal fun ForgeCommandPalette(
    onDismiss: () -> Unit,
    onOpen: (ForgeTab) -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    var filterInput by rememberSaveable { mutableStateOf("") }
    val trimmedQuery = filterInput.trim()
    val visibleItems = allCommandDestinations
        .map { dest -> dest to stringResource(dest.labelResId) }
        .filter { (_, titleText) -> trimmedQuery.isEmpty() || titleText.contains(trimmedQuery, ignoreCase = true) }

    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.agent_close))
            }
        },
        title = { Text(stringResource(R.string.palette_title)) },
        text = {
            Column {
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT,
                    imeAction = ImeAction.Search,
                    value = filterInput,
                    onValueChange = { filterInput = it },
                    label = { Text(stringResource(R.string.palette_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                ) {
                    for ((dest, titleText) in visibleItems) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) {
                                    onDismiss()
                                    onOpen(dest.destinationTab)
                                }
                                .heightIn(min = ForgeSpace.minTouch)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ForgeIcon(dest.iconGlyph, tint = palette.primary, size = 18.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(text = titleText, fontSize = ForgeType.rowTitle)
                        }
                    }
                    if (visibleItems.isEmpty()) {
                        Text(
                            text = stringResource(R.string.palette_empty),
                            color = palette.onSurfaceVariant,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        },
    )
}
