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

@Composable
fun FileManagerScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    var selectedWorkspaceId by rememberSaveable { mutableStateOf<String?>(null) }
    val activeSelection = state.projects.firstOrNull { proj ->
        proj.id == selectedWorkspaceId && proj.id == state.activeProjectId
    }
    if (activeSelection != null) {
        FilesAndDiffScreen(
            state = state,
            viewModel = viewModel,
            onBackToWorkspaces = { selectedWorkspaceId = null },
        )
    } else {
        WorkspaceDirectoryIndex(
            state = state,
            onSelectWorkspace = { targetId ->
                viewModel.openWorkspaceInFileManager(targetId) {
                    selectedWorkspaceId = targetId
                }
            },
            onReturnToTools = { viewModel.selectTab(ForgeTab.TOOLS) },
        )
    }
}

@Composable
private fun WorkspaceDirectoryIndex(
    state: ForgeUiState,
    onSelectWorkspace: (String) -> Unit,
    onReturnToTools: () -> Unit,
) {
    val totalWorkspaces = state.projects.size
    Column(modifier = Modifier.fillMaxSize()) {
        ForgeProjectHeader(
            title = stringResource(R.string.fm_title),
            subtitle = pluralStringResource(R.plurals.fm_workspaces_count, totalWorkspaces, totalWorkspaces),
            onBack = onReturnToTools,
        )
        if (state.projects.isEmpty()) {
            ForgeEmptyState(
                glyph = ForgeGlyph.FOLDER,
                title = stringResource(R.string.fm_workspaces_empty_title),
                message = stringResource(R.string.fm_workspaces_empty_message),
            )
            return
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.projects, key = { it.id }) { proj ->
                val activeBadge = if (proj.id == state.activeProjectId) {
                    stringResource(R.string.fm_workspace_active)
                } else {
                    null
                }
                ForgeListRow(
                    glyph = ForgeGlyph.FOLDER,
                    title = proj.name,
                    subtitle = proj.relativeUpdatedAt(),
                    badge = activeBadge,
                    onClick = { onSelectWorkspace(proj.id) },
                )
            }
        }
    }
}
