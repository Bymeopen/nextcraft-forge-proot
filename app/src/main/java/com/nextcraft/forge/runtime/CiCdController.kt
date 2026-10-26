package com.nextcraft.forge.runtime

import com.nextcraft.forge.ui.UiText
import com.nextcraft.forge.ui.UiTextException
import com.nextcraft.forge.ui.toUiText
import com.nextcraft.forge.R
import android.content.Context
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubPullRequest
import com.nextcraft.forge.network.GitHubWorkflow
import com.nextcraft.forge.network.GitHubWorkflowRun
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CiCdState(
    val repository: String? = null,
    val workflows: List<GitHubWorkflow> = emptyList(),
    val runs: List<GitHubWorkflowRun> = emptyList(),
    val pullRequests: List<GitHubPullRequest> = emptyList(),
    val branch: String = "main",
    val loading: Boolean = false,
    val message: UiText? = null,
)

/** Owner and repo of the `origin` remote in a workspace's .git/config, for github.com remotes only. */
internal fun githubRepositoryOf(workspace: File): Pair<String, String>? {
    val config = File(workspace, ".git/config").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() } ?: return null
    val origin = Regex("""\[remote "origin"][^\[]*?url\s*=\s*(\S+)""").find(config)?.groupValues?.get(1) ?: return null
    val match = Regex("""github\.com[:/]([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+?)(?:\.git)?/?$""").find(origin) ?: return null
    return match.groupValues[1] to match.groupValues[2]
}

/** CI/CD pipeline: GitHub Actions runs and manual workflow dispatch for the active project's repository. */
class CiCdController(
    context: Context,
    private val activeProject: () -> Project?,
    private val workspaceFor: (String) -> File,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    private val client = GitHubClient(appContext)
    private val vault = ApiKeyVault(appContext)
    private val mutable = MutableStateFlow(CiCdState())
    val state: StateFlow<CiCdState> = mutable.asStateFlow()
    @Volatile
    private var lastProjectId: String? = null

    fun refresh(preserveMessage: Boolean = false) {
        val project = activeProject()
        if (project == null) {
            lastProjectId = null
            mutable.update { CiCdState(message = UiText.res(R.string.rt_open_project_first)) }
            return
        }
        val switchedProject = lastProjectId != project.id
        lastProjectId = project.id
        mutable.update {
            if (switchedProject) {
                CiCdState(loading = true, message = if (preserveMessage) it.message else null)
            } else {
                it.copy(loading = true, message = if (preserveMessage) it.message else null)
            }
        }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    if (token.isBlank()) throw UiTextException(UiText.res(R.string.rt_cicd_connect_github))
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id))
                        ?: error("This project has no GitHub remote. Push it to GitHub or clone a GitHub repository.")
                    val prs = runCatching { client.pullRequests(token, owner, repo) }.getOrDefault(emptyList())
                    val wfs = runCatching { client.workflows(token, owner, repo) }.getOrDefault(emptyList())
                    val rns = runCatching { client.workflowRuns(token, owner, repo) }.getOrDefault(emptyList())
                    listOf("$owner/$repo", wfs, rns, prs)
                }
            }
            if (lastProjectId != project.id) return@launch
            @Suppress("UNCHECKED_CAST")
            mutable.update { current ->
                result.fold(
                    onSuccess = { tuple ->
                        current.copy(
                            repository = tuple[0] as String,
                            workflows = tuple[1] as List<GitHubWorkflow>,
                            runs = tuple[2] as List<GitHubWorkflowRun>,
                            pullRequests = tuple[3] as List<GitHubPullRequest>,
                            loading = false,
                        )
                    },
                    onFailure = {
                        current.copy(
                            repository = null,
                            workflows = emptyList(),
                            runs = emptyList(),
                            pullRequests = emptyList(),
                            loading = false,
                            message = it.toUiText(R.string.rt_cicd_load_failed),
                        )
                    },
                )
            }
        }
    }

    fun createPullRequest(title: String, head: String, base: String = "main", body: String = "") {
        val project = activeProject() ?: return
        if (title.isBlank() || head.isBlank()) return
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    if (token.isBlank()) throw UiTextException(UiText.res(R.string.rt_cicd_connect_github))
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id)) ?: error("No GitHub remote.")
                    client.createPullRequest(token, owner, repo, title, head, base, body)
                }
            }
            mutable.update {
                it.copy(
                    loading = false,
                    message = result.fold(
                        { pr -> UiText.Raw("PR #${pr.number} created: ${pr.title}") },
                        { e -> e.toUiText(R.string.rt_cicd_start_failed) },
                    ),
                )
            }
            if (result.isSuccess) refresh(preserveMessage = true)
        }
    }

    fun mergePullRequest(number: Int, mergeMethod: String = "squash") {
        val project = activeProject() ?: return
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    if (token.isBlank()) throw UiTextException(UiText.res(R.string.rt_cicd_connect_github))
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id)) ?: error("No GitHub remote.")
                    client.mergePullRequest(token, owner, repo, number, mergeMethod)
                }
            }
            mutable.update {
                it.copy(
                    loading = false,
                    message = result.fold(
                        { UiText.Raw("PR #$number merged ($mergeMethod)") },
                        { e -> e.toUiText(R.string.rt_cicd_start_failed) },
                    ),
                )
            }
            if (result.isSuccess) refresh(preserveMessage = true)
        }
    }

    fun setBranch(branch: String) = mutable.update { it.copy(branch = branch.trim()) }

    fun dispatch(workflow: GitHubWorkflow) {
        val project = activeProject() ?: return
        val branch = mutable.value.branch.ifBlank { "main" }
        mutable.update { it.copy(loading = true, message = null) }
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val token = vault.get("GITHUB_TOKEN").orEmpty()
                    if (token.isBlank()) throw UiTextException(UiText.res(R.string.rt_cicd_connect_github))
                    val (owner, repo) = githubRepositoryOf(workspaceFor(project.id)) ?: error("No GitHub remote.")
                    client.dispatchWorkflow(token, owner, repo, workflow.id, branch)
                }
            }
            mutable.update {
                it.copy(
                    loading = false,
                    message = result.fold({ UiText.res(R.string.rt_cicd_started, workflow.name, branch) }, { e -> e.toUiText(R.string.rt_cicd_start_failed) }),
                )
            }
        }
    }
}
