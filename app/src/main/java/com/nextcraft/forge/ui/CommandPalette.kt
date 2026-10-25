package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
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

private data class PaletteEntry(val tab: ForgeTab, val glyph: ForgeGlyph, @StringRes val title: Int)

/** Main destinations first, then every tool: one place to jump anywhere by typing a few letters. */
private val paletteEntries: List<PaletteEntry> =
    listOf(
        PaletteEntry(ForgeTab.PROJECTS, ForgeGlyph.FOLDER, R.string.nav_projects),
        PaletteEntry(ForgeTab.AGENT, ForgeGlyph.SLIDERS, R.string.nav_agent),
        PaletteEntry(ForgeTab.TERMINAL, ForgeGlyph.CODE, R.string.nav_terminal),
        PaletteEntry(ForgeTab.SETTINGS, ForgeGlyph.SLIDERS, R.string.nav_settings),
    ) + toolSections.flatMap { section -> section.entries.map { PaletteEntry(it.tab, it.glyph, it.title) } }

/** Opened by long-pressing the bottom bar. Type to filter; tap a row to open it. */
@Composable
internal fun ForgeCommandPalette(onDismiss: () -> Unit, onOpen: (ForgeTab) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()
    val rows = paletteEntries.map { it to stringResource(it.title) }
        .filter { (_, label) -> needle.isEmpty() || label.contains(needle, ignoreCase = true) }
    ForgeAlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_close)) } },
        title = { Text(stringResource(R.string.palette_title)) },
        text = {
            Column {
                ForgeTextField(
                    kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Search,
                    value = query,
                    onValueChange = { query = it },
                    label = { Text(stringResource(R.string.palette_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                    rows.forEach { (entry, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onDismiss(); onOpen(entry.tab) }
                                .heightIn(min = ForgeSpace.minTouch)
                                .padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            ForgeIcon(entry.glyph, tint = scheme.primary, size = 18.dp)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(label, fontSize = ForgeType.rowTitle)
                        }
                    }
                    if (rows.isEmpty()) {
                        Text(stringResource(R.string.palette_empty), color = scheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                    }
                }
            }
        },
    )
}
