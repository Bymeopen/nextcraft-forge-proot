package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/**
 * The file manager of the Tools menu. It lives outside any workspace: the first page lists every workspace as a folder
 * named after it, and opening one browses that workspace with the same explorer the project's own Files pane uses.
 * Back from a workspace's root returns to this list.
 */
@Composable
fun FileManagerScreen(state: ForgeUiState, viewModel: MainViewModel) {
    var openedId by rememberSaveable { mutableStateOf<String?>(null) }
    // selectProject refuses while an agent run or build is active; the workspace opens only once it really is the active one.
    val opened = state.projects.firstOrNull { it.id == openedId && it.id == state.activeProjectId }
    if (opened != null) {
        FilesAndDiffScreen(state = state, viewModel = viewModel, onBackToWorkspaces = { openedId = null })
    } else {
        WorkspaceListPage(
            state = state,
            onOpen = { id ->
                viewModel.openWorkspaceInFileManager(id) { openedId = id }
            },
            onBack = { viewModel.selectTab(ForgeTab.TOOLS) },
        )
    }
}

@Composable
private fun WorkspaceListPage(state: ForgeUiState, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val count = state.projects.size
    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(
            title = stringResource(R.string.fm_title),
            subtitle = pluralStringResource(R.plurals.fm_workspaces_count, count, count),
            onBack = onBack,
        )
        if (state.projects.isEmpty()) {
            ForgeEmptyState(
                glyph = ForgeGlyph.FOLDER,
                title = stringResource(R.string.fm_workspaces_empty_title),
                message = stringResource(R.string.fm_workspaces_empty_message),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.projects, key = { it.id }) { project ->
                    ForgeListRow(
                        glyph = ForgeGlyph.FOLDER,
                        title = project.name,
                        subtitle = project.relativeUpdatedAt(),
                        badge = if (project.id == state.activeProjectId) stringResource(R.string.fm_workspace_active) else null,
                        onClick = { onOpen(project.id) },
                    )
                }
            }
        }
    }
}
