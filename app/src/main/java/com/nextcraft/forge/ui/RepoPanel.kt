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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepoSheet(
    state: ForgeUiState,
    viewModel: MainViewModel,
    onReview: (chatId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = MaterialTheme.colorScheme
    val themeTokens = LocalForgeThemeTokens.current
    val repoSnapshot = state.projectRepo
    var commitSubject by rememberSaveable { mutableStateOf("") }
    val isolatedThreads = state.chats.filter { thread -> thread.isolated }
    val hasActiveRuns = state.runningChatIds.isNotEmpty()
    val pipelineState by viewModel.cicd.state.collectAsState()

    LaunchedEffect(state.activeProjectId) {
        viewModel.refreshProjectRepo()
        viewModel.cicd.refresh()
    }

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
                Text(text = stringResource(R.string.repo_title), fontSize = ForgeType.screenTitle, fontWeight = FontWeight.SemiBold)
                if (state.repoBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = themeTokens.cardElevatedColor,
                    border = themeTokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (repoSnapshot == null || !repoSnapshot.isRepo) {
                            Text(text = stringResource(R.string.repo_not_repo), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                        } else {
                            Text(
                                text = stringResource(R.string.repo_branch, repoSnapshot.branch),
                                fontSize = ForgeType.rowTitle,
                                fontWeight = FontWeight.Medium,
                            )
                            val statusText = if (repoSnapshot.uncommitted > 0) {
                                stringResource(R.string.repo_uncommitted, repoSnapshot.uncommitted)
                            } else {
                                stringResource(R.string.repo_clean)
                            }
                            Text(text = statusText, fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ForgeTextField(
                        value = commitSubject,
                        onValueChange = { typed -> commitSubject = typed.take(100) },
                        label = { Text(text = stringResource(R.string.repo_commit_hint)) },
                        singleLine = true,
                        enabled = !state.repoBusy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val canCommit = !state.repoBusy && !hasActiveRuns && commitSubject.isNotBlank() &&
                        (repoSnapshot == null || !repoSnapshot.isRepo || repoSnapshot.uncommitted > 0)
                    ForgePrimaryButton(
                        text = stringResource(R.string.repo_commit),
                        onClick = {
                            viewModel.commitProjectRepo(commitSubject)
                            commitSubject = ""
                        },
                        enabled = canCommit,
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
            if (repoSnapshot != null && repoSnapshot.isRepo) {
                item {
                    Text(text = stringResource(R.string.repo_branches), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
                }
                items(repoSnapshot.branches, key = { branchName -> "branch-$branchName" }) { branchName ->
                    val isHead = branchName == repoSnapshot.branch
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val displayBranch = if (isHead) "$branchName · ${stringResource(R.string.repo_branch_current)}" else branchName
                        Text(
                            text = displayBranch,
                            fontSize = ForgeType.secondary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isHead) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (!isHead) {
                            ForgeTextButton(
                                onClick = { viewModel.switchRepoBranch(branchName) },
                                enabled = !state.repoBusy && !hasActiveRuns,
                            ) {
                                Text(text = stringResource(R.string.repo_branch_switch))
                            }
                            ConfirmedAction(
                                title = stringResource(R.string.repo_branch_delete),
                                message = branchName,
                                confirmLabel = stringResource(R.string.repo_branch_delete),
                                onConfirm = { viewModel.deleteRepoBranch(branchName) },
                            ) { triggerDelete ->
                                ForgeTextButton(
                                    onClick = triggerDelete,
                                    enabled = !state.repoBusy && !hasActiveRuns,
                                ) {
                                    Text(text = stringResource(R.string.repo_branch_delete), color = palette.error)
                                }
                            }
                        }
                    }
                }
                item {
                    var draftBranchName by rememberSaveable { mutableStateOf("") }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ForgeTextField(
                            value = draftBranchName,
                            onValueChange = { typed -> draftBranchName = typed.take(60) },
                            label = { Text(text = stringResource(R.string.repo_branch_new_hint)) },
                            singleLine = true,
                            enabled = !state.repoBusy,
                            kind = ForgeFieldKind.IDENTIFIER,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedButton(
                            onClick = {
                                viewModel.createRepoBranch(draftBranchName)
                                draftBranchName = ""
                            },
                            enabled = !state.repoBusy && !hasActiveRuns && draftBranchName.isNotBlank(),
                            shape = RoundedCornerShape(100),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(text = stringResource(R.string.repo_branch_create))
                        }
                    }
                }
            }
            item {
                Text(text = stringResource(R.string.repo_prs), fontSize = ForgeType.rowTitle, fontWeight = FontWeight.SemiBold)
            }
            val reviewableBranches = isolatedThreads.filter { thread ->
                val branchInfo = state.branchStatus[thread.id]
                branchInfo != null && (branchInfo.commits > 0 || branchInfo.uncommitted > 0 || branchInfo.conflicts.isNotEmpty())
            }
            if (reviewableBranches.isEmpty()) {
                item {
                    Text(text = stringResource(R.string.repo_prs_empty), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                }
            }
            items(reviewableBranches, key = { thread -> thread.id }) { thread ->
                val branchInfo = state.branchStatus[thread.id] ?: return@items
                val isBranchBusy = thread.id in state.branchBusy
                val isThreadRunning = thread.id in state.runningChatIds
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = themeTokens.cardElevatedColor,
                    border = themeTokens.cardBorderStroke,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = thread.displayTitle(),
                            fontSize = ForgeType.rowTitle,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = stringResource(
                                R.string.repo_pr_summary,
                                branchInfo.commits,
                                branchInfo.filesChanged,
                                branchInfo.additions,
                                branchInfo.deletions,
                            ),
                            fontSize = ForgeType.secondary,
                            color = palette.onSurfaceVariant,
                        )
                        if (branchInfo.uncommitted > 0) {
                            Text(
                                text = stringResource(R.string.branch_uncommitted, branchInfo.uncommitted),
                                fontSize = ForgeType.secondary,
                                color = palette.onSurfaceVariant,
                            )
                        }
                        if (branchInfo.behind > 0) {
                            Text(
                                text = stringResource(R.string.repo_pr_behind, branchInfo.behind),
                                fontSize = ForgeType.secondary,
                                color = themeTokens.accentText,
                            )
                        }
                        if (branchInfo.conflicts.isNotEmpty()) {
                            Text(text = stringResource(R.string.repo_pr_conflict), fontSize = ForgeType.secondary, color = palette.error)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { onReview(thread.id) },
                                shape = RoundedCornerShape(100),
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = stringResource(R.string.repo_pr_review))
                            }
                            ForgePrimaryButton(
                                text = stringResource(R.string.repo_pr_merge),
                                onClick = { viewModel.mergeChatBranch(thread.id) },
                                enabled = !isBranchBusy && !isThreadRunning && branchInfo.conflicts.isEmpty(),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
            if (pipelineState.repository != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = themeTokens.cardElevatedColor,
                        border = themeTokens.cardBorderStroke,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "${stringResource(R.string.github_pr_manager_title)} · ${pipelineState.repository}",
                                fontSize = ForgeType.rowTitle,
                                fontWeight = FontWeight.SemiBold,
                            )
                            val headRun = pipelineState.runs.firstOrNull()
                            if (headRun != null) {
                                val runVerdict = headRun.conclusion ?: headRun.status
                                val didFail = headRun.conclusion != null && headRun.conclusion != "success" && headRun.conclusion != "skipped"
                                val runSummaryText = "${headRun.name} (${headRun.branch}): $runVerdict"
                                val runColor = when {
                                    didFail -> palette.error
                                    headRun.conclusion == "success" -> themeTokens.successText
                                    else -> palette.onSurfaceVariant
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = runSummaryText,
                                        fontSize = ForgeType.secondary,
                                        color = runColor,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (didFail) {
                                        ForgeTextButton(
                                            onClick = {
                                                onDismiss()
                                                viewModel.fixCiFailureWithAgent(headRun.name, headRun.branch, headRun.url)
                                            },
                                        ) {
                                            Text(text = stringResource(R.string.github_fix_ci_with_agent), color = palette.error)
                                        }
                                    }
                                }
                            }
                            pipelineState.pullRequests.take(3).forEach { pullReq ->
                                val prBadgeText = "#${pullReq.number} ${pullReq.title}"
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(
                                        text = prBadgeText,
                                        fontSize = ForgeType.secondary,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f),
                                    )
                                    MergePrConfirmed(
                                        title = stringResource(R.string.repo_pr_merge),
                                        message = prBadgeText,
                                        confirmLabel = stringResource(R.string.repo_pr_merge),
                                        onConfirm = { chosenStrategy -> viewModel.cicd.mergePullRequest(pullReq.number, chosenStrategy) },
                                    ) { triggerMerge ->
                                        ForgeTextButton(onClick = triggerMerge, enabled = !pipelineState.loading) {
                                            Text(text = stringResource(R.string.repo_pr_merge))
                                        }
                                    }
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    viewModel.selectTab(ForgeTab.CICD)
                                },
                                shape = RoundedCornerShape(100),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(text = stringResource(R.string.tools_cicd))
                            }
                        }
                    }
                }
            }
            if (repoSnapshot != null && repoSnapshot.recent.isNotEmpty()) {
                item {
                    Text(text = stringResource(R.string.repo_recent), fontSize = ForgeType.secondary, color = palette.onSurfaceVariant)
                }
                items(repoSnapshot.recent, key = { entry -> entry.hash }) { entry ->
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(text = entry.hash, fontSize = ForgeType.secondary, fontFamily = FontFamily.Monospace, color = themeTokens.accentText)
                        Text(text = entry.subject, fontSize = ForgeType.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
