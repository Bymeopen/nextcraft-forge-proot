@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

internal data class ToolEntry(
    val tab: ForgeTab,
    val glyph: ForgeGlyph,
    @StringRes val title: Int,
    @StringRes val subtitle: Int,
)

internal data class ToolSection(@StringRes val label: Int, val entries: List<ToolEntry>)

internal val toolSections = listOf(
    ToolSection(
        R.string.tools_section_development,
        listOf(
            ToolEntry(ForgeTab.TOOLCHAINS, ForgeGlyph.BUILD, R.string.tools_toolchains, R.string.tools_toolchains_desc),
            ToolEntry(ForgeTab.DIAGNOSTICS, ForgeGlyph.SHIELD, R.string.tools_diagnostics, R.string.tools_diagnostics_desc),
            ToolEntry(ForgeTab.REA, ForgeGlyph.SEARCH, R.string.tools_rea, R.string.tools_rea_desc),
            ToolEntry(ForgeTab.FILES, ForgeGlyph.FOLDER, R.string.tools_files, R.string.tools_files_desc),
            ToolEntry(ForgeTab.FILE_BROWSER, ForgeGlyph.FILE, R.string.tools_file_browser, R.string.tools_file_browser_desc),
            ToolEntry(ForgeTab.CODE_VIEWER, ForgeGlyph.EDIT, R.string.tools_code_viewer, R.string.tools_code_viewer_desc),
            ToolEntry(ForgeTab.PREVIEW, ForgeGlyph.GLOBE, R.string.tools_web_preview, R.string.tools_web_preview_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_build_deploy,
        listOf(
            ToolEntry(ForgeTab.BUILD_MATRIX, ForgeGlyph.SLIDERS, R.string.tools_build_matrix, R.string.tools_build_matrix_desc),
            ToolEntry(ForgeTab.ANDROID, ForgeGlyph.BUILD, R.string.tools_android_builder, R.string.tools_android_builder_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_infrastructure,
        listOf(
            ToolEntry(ForgeTab.SSH, ForgeGlyph.LOCK, R.string.tools_ssh, R.string.tools_ssh_desc),
            ToolEntry(ForgeTab.CICD, ForgeGlyph.REFRESH, R.string.tools_cicd, R.string.tools_cicd_desc),
        ),
    ),
    ToolSection(
        R.string.tools_section_system,
        listOf(
            ToolEntry(ForgeTab.ROM, ForgeGlyph.CODE, R.string.tools_rom, R.string.tools_rom_desc),
            ToolEntry(ForgeTab.CHECKPOINTS, ForgeGlyph.HISTORY, R.string.tools_checkpoints, R.string.tools_checkpoints_desc),
            ToolEntry(ForgeTab.BACKUP, ForgeGlyph.DOWNLOAD, R.string.tools_backup, R.string.tools_backup_desc),
        ),
    ),
)

/** Developer Tools launcher: one shared list row per tool (single accent tile, stroke glyph), grouped by purpose, with search. */
@Composable
fun ToolsScreen(
    onOpen: (ForgeTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val needle = query.trim()

    val scroll = rememberScrollState()
    Column(modifier = modifier.fillMaxSize().forgeFadingVertical(scroll, MaterialTheme.colorScheme.background).verticalScroll(scroll).padding(bottom = 24.dp)) {
        ForgeProjectHeader(
            title = stringResource(R.string.tools_header_title),
            subtitle = stringResource(R.string.tools_header_subtitle),
            actions = {
                ForgeGlyphPillIcon(
                    glyph = ForgeGlyph.SEARCH,
                    description = stringResource(R.string.tools_search),
                    tint = if (searching) scheme.primary else scheme.onSurface,
                    onClick = {
                        searching = !searching
                        if (!searching) query = ""
                    },
                )
            },
        )
        if (searching) {
            ForgeTextField(
                kind = ForgeFieldKind.TEXT, imeAction = ImeAction.Search,
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.tools_search)) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        toolSections.forEach { section ->
            val rows = section.entries.filter { entry ->
                needle.isEmpty() ||
                    stringResource(entry.title).contains(needle, ignoreCase = true) ||
                    stringResource(entry.subtitle).contains(needle, ignoreCase = true)
            }
            if (rows.isNotEmpty()) {
                ForgeSectionLabel(stringResource(section.label))
                rows.forEach { entry ->
                    ForgeListRow(
                        glyph = entry.glyph,
                        title = stringResource(entry.title),
                        subtitle = stringResource(entry.subtitle),
                        onClick = { onOpen(entry.tab) },
                    )
                }
            }
        }
    }
}

