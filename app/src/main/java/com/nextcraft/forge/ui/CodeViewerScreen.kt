package com.nextcraft.forge.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType

private const val MAX_LISTED_FILES = 300
private val skippedFolders = listOf("node_modules", ".git", "build", "dist", ".gradle", "__pycache__")

/** The text of a file as read-only "context" lines, so the diff viewer's gutter and colouring also serve plain reading. */
internal fun fileAsViewerLines(content: String): List<DiffLine> =
    content.split('\n').let { lines -> if (lines.size > 1 && lines.last().isEmpty()) lines.dropLast(1) else lines }
        .mapIndexed { index, text -> DiffLine(DiffLineType.CONTEXT, text.trimEnd('\r'), oldLine = index + 1, newLine = index + 1) }

/**
 * Code viewer (Tools): read the agent's changes as a diff, or open any project file with line numbers and syntax
 * colours. Read-only; "Edit" hands the file to the editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodeViewerScreen(state: ForgeUiState, viewModel: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    // Open on the files when there is nothing to review, instead of an empty "changes" tab.
    var showChanges by rememberSaveable(state.activeProjectId) { mutableStateOf(state.changedItems.isNotEmpty()) }
    var openPath by rememberSaveable(state.activeProjectId) { mutableStateOf<String?>(null) }
    BackHandler(enabled = openPath != null) { openPath = null }
    // Generated and vendored folders would bury the project's own files and make the list thousands of rows long.
    val allFiles = remember(state.workspaceFiles) {
        state.workspaceFiles.filter { entry -> !entry.isDirectory && skippedFolders.none { "/$it/" in "/${entry.path}" } }
    }
    val files = remember(allFiles) { allFiles.take(MAX_LISTED_FILES) }

    ToolScaffold(
        title = stringResource(R.string.tools_code_viewer),
        subtitle = state.activeProject?.name ?: stringResource(R.string.tools_code_viewer_desc),
        onBack = {
            // Back from an open file returns to the list; from the list it returns to Tools.
            if (openPath != null) openPath = null else viewModel.selectTab(ForgeTab.TOOLS)
        },
    ) {
        if (state.activeProject == null) {
            Text(stringResource(R.string.code_viewer_no_project), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
            return@ToolScaffold
        }
        val path = openPath
        if (path != null) {
            // Same file already loaded in the editor: shown as is; otherwise load it (read-only reading does not change it).
            LaunchedEffectOnce(path) { if (state.selectedFilePath != path) viewModel.openWorkspaceFile(path) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    path,
                    fontFamily = FontFamily.Monospace,
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.openFilesTabForEditing(path) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                    Text(stringResource(R.string.code_viewer_edit))
                }
                TextButton(onClick = { viewModel.editInTerminal(path) }, modifier = Modifier.heightIn(min = ForgeSpace.minTouch)) {
                    Text(stringResource(R.string.files_edit_in_terminal))
                }
            }
            if (state.selectedFilePath == path) {
                DiffCodeView(lines = remember(state.selectedFileContent) { fileAsViewerLines(state.selectedFileContent) }, path = path, previewLines = 400)
            }
            return@ToolScaffold
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = showChanges,
                onClick = { showChanges = true },
                label = { Text(stringResource(R.string.code_viewer_changes, state.changedItems.size)) },
                modifier = Modifier.heightIn(min = ForgeSpace.minTouch).forgeSelected(showChanges),
            )
            FilterChip(
                selected = !showChanges,
                onClick = { showChanges = false },
                label = { Text(stringResource(R.string.code_viewer_files, files.size)) },
                modifier = Modifier.heightIn(min = ForgeSpace.minTouch).forgeSelected(!showChanges),
            )
        }

        if (showChanges) {
            if (state.changedItems.isEmpty()) {
                ForgeEmptyState(
                    glyph = ForgeGlyph.CODE,
                    title = stringResource(R.string.code_viewer_no_changes_title),
                    message = stringResource(R.string.files_no_unreviewed_checkpoint_changes),
                    actionLabel = stringResource(R.string.code_viewer_see_files, files.size),
                    onAction = { showChanges = false },
                )
            }
            state.changedItems.forEach { change ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            change.path,
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.control,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f).clickable(role = Role.Button) { openPath = change.path },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.diff_added, change.additions), fontFamily = FontFamily.Monospace, fontSize = ForgeType.caption, color = LocalForgeThemeTokens.current.successText)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.diff_removed, change.deletions), fontFamily = FontFamily.Monospace, fontSize = ForgeType.caption, color = LocalForgeThemeTokens.current.dangerText)
                    }
                    if (change.binary || change.diffLines.isEmpty()) {
                        Text(
                            stringResource(if (change.binary) R.string.chat_diff_binary else R.string.chat_diff_none),
                            color = scheme.onSurfaceVariant,
                            fontSize = ForgeType.secondary,
                        )
                    } else {
                        DiffCodeView(lines = change.diffLines, path = change.path, previewLines = 60)
                    }
                }
            }
        } else {
            if (files.isEmpty()) {
                Text(stringResource(R.string.code_viewer_no_files), color = scheme.onSurfaceVariant, fontSize = ForgeType.control)
            }
            if (allFiles.size > files.size) {
                Text(stringResource(R.string.code_viewer_files_capped, files.size, allFiles.size), color = scheme.onSurfaceVariant, fontSize = ForgeType.caption)
            }
            files.forEach { entry ->
                val dir = entry.path.substringBeforeLast('/', "")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { openPath = entry.path }
                        .heightIn(min = ForgeSpace.minTouch + 4.dp)
                        .padding(vertical = 4.dp),
                ) {
                    Text(entry.name, fontFamily = FontFamily.Monospace, fontSize = ForgeType.control, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (dir.isNotEmpty()) Text(dir, fontSize = ForgeType.caption, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Runs [block] once per [key] (a LaunchedEffect without the coroutine). */
@Composable
private fun LaunchedEffectOnce(key: Any?, block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(key) { block() }
}
