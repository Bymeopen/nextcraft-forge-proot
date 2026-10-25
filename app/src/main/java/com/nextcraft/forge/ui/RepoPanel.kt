package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nextcraft.forge.R

/**
 * The project's local Git repository: its branch, a commit box and the latest commits, plus the chats that work in
 * their own branch as local pull requests (review the diff, then merge into the project).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepoSheet(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onReview: (chatId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val tokens = LocalForgeThemeTokens.current
    val repo = state.projectRepo
    var message by rememberSaveable { mutableStateOf("") }
    val branchChats = state.chats.filter { it.isolated }
    val anyRunInProject = state.runningChatIds.isNotEmpty()

    LaunchedEffect(state.activeProjectId) { viewModel.refreshProjectRepo() }

    ForgeModalSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        LazyColumn(
            modifier = Modifier.padding(horizontal = ForgeSpace.card),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(stringResource(R.string.repo_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
                if (state.repoBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = tokens.cardElevatedColor, border = tokens.cardBorderStroke, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (repo == null || !repo.isRepo) {
                            Text(stringResource(R.string.repo_not_repo), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                        } else {
                            Text(stringResource(R.string.repo_branch, repo.branch), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium)
                            Text(
                                if (repo.uncommitted > 0) stringResource(R.string.repo_uncommitted, repo.uncommitted) else stringResource(R.string.repo_clean),
                                fontSize = ForgeType.secondary,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(
                        value = message,
                        onValueChange = { message = it.take(100) },
                        label = { Text(stringResource(R.string.repo_commit_hint)) },
                        singleLine = true,
                        enabled = !state.repoBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    ForgePrimaryButton(
                        text = stringResource(R.string.repo_commit),
                        onClick = { viewModel.commitProjectRepo(message); message = "" },
                        enabled = !state.repoBusy && !anyRunInProject && message.isNotBlank() &&
                            (repo == null || !repo.isRepo || repo.uncommitted > 0),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item {
                ForgeToggleRow(
                    title = stringResource(R.string.repo_auto_branch),
                    subtitle = stringResource(R.string.repo_auto_branch_desc),
                    checked = state.autoBranchChats,
                    onCheckedChange = viewModel::setAutoBranchChats,
                )
            }
            if (repo != null && repo.isRepo) {
                item {
                    Text(stringResource(R.string.repo_branches), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                }
                items(repo.branches, key = { "branch-$it" }) { name ->
                    val current = name == repo.branch
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = if (current) name + " · " + stringResource(R.string.repo_branch_current) else name,
                            fontSize = ForgeType.secondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!current) {
                            ForgeTextButton(onClick = { viewModel.switchRepoBranch(name) }, enabled = !state.repoBusy && !anyRunInProject) {
                                Text(stringResource(R.string.repo_branch_switch))
                            }
                            ForgeTextButton(onClick = { viewModel.deleteRepoBranch(name) }, enabled = !state.repoBusy && !anyRunInProject) {
                                Text(stringResource(R.string.repo_branch_delete), color = scheme.error)
                            }
                        }
                    }
                }
                item {
                    var newBranch by rememberSaveable { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeTextField(
                            value = newBranch,
                            onValueChange = { newBranch = it.take(60) },
                            label = { Text(stringResource(R.string.repo_branch_new_hint)) },
                            singleLine = true,
                            enabled = !state.repoBusy,
                            kind = ForgeFieldKind.IDENTIFIER,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = { viewModel.createRepoBranch(newBranch); newBranch = "" },
                            enabled = !state.repoBusy && !anyRunInProject && newBranch.isNotBlank(),
                            shape = RoundedCornerShape(100),
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(stringResource(R.string.repo_branch_create)) }
                    }
                }
            }
            item {
                Text(stringResource(R.string.repo_prs), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
            }
            val pending = branchChats.filter { chat ->
                val st = state.branchStatus[chat.id]
                st != null && (st.commits > 0 || st.uncommitted > 0 || st.conflicts.isNotEmpty())
            }
            if (pending.isEmpty()) {
                item { Text(stringResource(R.string.repo_prs_empty), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant) }
            }
            items(pending, key = { it.id }) { chat ->
                val st = state.branchStatus.getValue(chat.id)
                val busy = chat.id in state.branchBusy
                val running = chat.id in state.runningChatIds
                Surface(shape = RoundedCornerShape(16.dp), color = tokens.cardElevatedColor, border = tokens.cardBorderStroke, modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(chat.displayTitle(), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            stringResource(R.string.repo_pr_summary, st.commits, st.filesChanged, st.additions, st.deletions),
                            fontSize = ForgeType.secondary,
                            color = scheme.onSurfaceVariant,
                        )
                        if (st.uncommitted > 0) {
                            Text(stringResource(R.string.branch_uncommitted, st.uncommitted), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant)
                        }
                        if (st.behind > 0) {
                            Text(stringResource(R.string.repo_pr_behind, st.behind), fontSize = ForgeType.secondary, color = tokens.accentText)
                        }
                        if (st.conflicts.isNotEmpty()) {
                            Text(stringResource(R.string.repo_pr_conflict), fontSize = ForgeType.secondary, color = scheme.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onReview(chat.id) },
                                shape = RoundedCornerShape(100),
                                modifier = Modifier.weight(1f),
                            ) { Text(stringResource(R.string.repo_pr_review)) }
                            ForgePrimaryButton(
                                text = stringResource(R.string.repo_pr_merge),
                                onClick = { viewModel.mergeChatBranch(chat.id) },
                                enabled = !busy && !running && st.conflicts.isEmpty(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (repo != null && repo.recent.isNotEmpty()) {
                item { Text(stringResource(R.string.repo_recent), fontSize = ForgeType.secondary, color = scheme.onSurfaceVariant) }
                items(repo.recent, key = { it.hash }) { commit ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(commit.hash, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = tokens.accentText)
                        Text(commit.subject, fontSize = ForgeType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
