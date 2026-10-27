@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.runtime.LaunchedEffect
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

private const val BROWSE_FILE_CAP = 300
private val EXCLUDED_DIR_SEGMENTS = listOf("node_modules", ".git", "build", "dist", ".gradle", "__pycache__")

internal fun fileAsViewerLines(content: String): List<DiffLine> {
    val rawSplit = content.split('\n')
    val trimmedLines = if (rawSplit.size > 1 && rawSplit.last().isEmpty()) {
        rawSplit.dropLast(1)
    } else {
        rawSplit
    }
    return trimmedLines.mapIndexed { idx, lineText ->
        val lineNo = idx + 1
        DiffLine(
            type = DiffLineType.CONTEXT,
            text = lineText.trimEnd('\r'),
            oldLine = lineNo,
            newLine = lineNo,
        )
    }
}

@Composable
fun CodeViewerScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val palette = MaterialTheme.colorScheme
    val themeTokens = LocalForgeThemeTokens.current
    var viewingDiffMode by rememberSaveable(state.activeProjectId) {
        mutableStateOf(state.changedItems.isNotEmpty())
    }
    var inspectedFilePath by rememberSaveable(state.activeProjectId) {
        mutableStateOf<String?>(null)
    }
    BackHandler(enabled = inspectedFilePath != null) {
        inspectedFilePath = null
    }

    val eligibleWorkspaceFiles = remember(state.workspaceFiles) {
        state.workspaceFiles.filter { item ->
            !item.isDirectory && EXCLUDED_DIR_SEGMENTS.none { seg -> "/$seg/" in "/${item.path}" }
        }
    }
    val cappedFileList = remember(eligibleWorkspaceFiles) {
        eligibleWorkspaceFiles.take(BROWSE_FILE_CAP)
    }

    ToolScaffold(
        title = stringResource(R.string.tools_code_viewer),
        subtitle = state.activeProject?.name ?: stringResource(R.string.tools_code_viewer_desc),
        onBack = {
            if (inspectedFilePath != null) {
                inspectedFilePath = null
            } else {
                viewModel.selectTab(ForgeTab.TOOLS)
            }
        },
    ) {
        if (state.activeProject == null) {
            Text(
                text = stringResource(R.string.code_viewer_no_project),
                color = palette.onSurfaceVariant,
                fontSize = ForgeType.control,
            )
            return@ToolScaffold
        }

        val currentFile = inspectedFilePath
        if (currentFile != null) {
            LaunchedEffect(currentFile) {
                if (state.selectedFilePath != currentFile) {
                    viewModel.openWorkspaceFile(currentFile)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = currentFile,
                    fontFamily = FontFamily.Monospace,
                    fontSize = ForgeType.control,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = { viewModel.openFilesTabForEditing(currentFile) },
                    modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
                ) {
                    Text(stringResource(R.string.code_viewer_edit))
                }
                TextButton(
                    onClick = { viewModel.editInTerminal(currentFile) },
                    modifier = Modifier.heightIn(min = ForgeSpace.minTouch),
                ) {
                    Text(stringResource(R.string.files_edit_in_terminal))
                }
            }
            if (state.selectedFilePath == currentFile) {
                val renderedLines = remember(state.selectedFileContent) {
                    fileAsViewerLines(state.selectedFileContent)
                }
                DiffCodeView(
                    lines = renderedLines,
                    path = currentFile,
                    previewLines = 400,
                )
            }
            return@ToolScaffold
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = viewingDiffMode,
                onClick = { viewingDiffMode = true },
                label = { Text(stringResource(R.string.code_viewer_changes, state.changedItems.size)) },
                modifier = Modifier
                    .heightIn(min = ForgeSpace.minTouch)
                    .forgeSelected(viewingDiffMode),
            )
            FilterChip(
                selected = !viewingDiffMode,
                onClick = { viewingDiffMode = false },
                label = { Text(stringResource(R.string.code_viewer_files, cappedFileList.size)) },
                modifier = Modifier
                    .heightIn(min = ForgeSpace.minTouch)
                    .forgeSelected(!viewingDiffMode),
            )
        }

        if (viewingDiffMode) {
            if (state.changedItems.isEmpty()) {
                ForgeEmptyState(
                    glyph = ForgeGlyph.CODE,
                    title = stringResource(R.string.code_viewer_no_changes_title),
                    message = stringResource(R.string.files_no_unreviewed_checkpoint_changes),
                    actionLabel = stringResource(R.string.code_viewer_see_files, cappedFileList.size),
                    onAction = { viewingDiffMode = false },
                )
            }
            for (diffItem in state.changedItems) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = diffItem.path,
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.control,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .weight(1f)
                                .clickable(role = Role.Button) { inspectedFilePath = diffItem.path },
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.diff_added, diffItem.additions),
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.caption,
                            color = themeTokens.successText,
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.diff_removed, diffItem.deletions),
                            fontFamily = FontFamily.Monospace,
                            fontSize = ForgeType.caption,
                            color = themeTokens.dangerText,
                        )
                    }
                    if (diffItem.binary || diffItem.diffLines.isEmpty()) {
                        val fallbackRes = if (diffItem.binary) R.string.chat_diff_binary else R.string.chat_diff_none
                        Text(
                            text = stringResource(fallbackRes),
                            color = palette.onSurfaceVariant,
                            fontSize = ForgeType.secondary,
                        )
                    } else {
                        DiffCodeView(
                            lines = diffItem.diffLines,
                            path = diffItem.path,
                            previewLines = 60,
                        )
                    }
                }
            }
        } else {
            if (cappedFileList.isEmpty()) {
                Text(
                    text = stringResource(R.string.code_viewer_no_files),
                    color = palette.onSurfaceVariant,
                    fontSize = ForgeType.control,
                )
            }
            if (eligibleWorkspaceFiles.size > cappedFileList.size) {
                Text(
                    text = stringResource(R.string.code_viewer_files_capped, cappedFileList.size, eligibleWorkspaceFiles.size),
                    color = palette.onSurfaceVariant,
                    fontSize = ForgeType.caption,
                )
            }
            for (fileEntry in cappedFileList) {
                val parentFolder = fileEntry.path.substringBeforeLast('/', "")
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { inspectedFilePath = fileEntry.path }
                        .heightIn(min = ForgeSpace.minTouch + 4.dp)
                        .padding(vertical = 4.dp),
                ) {
                    Text(
                        text = fileEntry.name,
                        fontFamily = FontFamily.Monospace,
                        fontSize = ForgeType.control,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (parentFolder.isNotEmpty()) {
                        Text(
                            text = parentFolder,
                            fontSize = ForgeType.caption,
                            color = palette.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
