package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.Project
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Where a chat works when it has its own branch: a clone of the project, named after the chat. */
data class ChatBranch(
    /** Pseudo project id the agent bridge uses, so checkpoints and mounts are separate from the project's own. */
    val pseudoProjectId: String,
    val slug: String,
    val cloneDir: File,
)

data class BranchCommit(val hash: String, val subject: String)

/** What a chat branch holds compared with the project (main) it was cut from. */
data class BranchStatus(
    val commits: Int = 0,
    val uncommitted: Int = 0,
    val filesChanged: Int = 0,
    val additions: Int = 0,
    val deletions: Int = 0,
    /** Commits on the project that the branch does not have yet. */
    val behind: Int = 0,
    /** Files with unresolved merge conflicts inside the branch. */
    val conflicts: List<String> = emptyList(),
    val recent: List<BranchCommit> = emptyList(),
)

/** The project's own repository (its main folder), shown in the repository panel. */
data class ProjectRepoInfo(
    val isRepo: Boolean = false,
    val branch: String = "",
    val uncommitted: Int = 0,
    val recent: List<BranchCommit> = emptyList(),
    /** Local branches of the project repository. */
    val branches: List<String> = emptyList(),
)

/** Outcome of a branch operation on the project repository. */
sealed interface RepoOpResult {
    data object Done : RepoOpResult
    data object Dirty : RepoOpResult
    data class Failed(val message: String) : RepoOpResult
}

sealed interface BranchMergeResult {
    data object Done : BranchMergeResult
    data class Conflict(val files: List<String>) : BranchMergeResult
    data class Failed(val message: String) : BranchMergeResult
}

/**
 * One clone of the project per chat ("branch"), so chats that work at the same time cannot overwrite each other's
 * files. A git worktree would be the obvious tool, but it records absolute paths into the main repository and each chroot
 * run only mounts one project folder; a local clone (objects are hard-linked, so it costs almost no space) is
 * self-contained. The clones live inside the project at `.forge/chats/<id>` so that one chroot run sees both and can
 * merge them: commit after every turn, update from main, merge into main, discard.
 */
class ChatBranches(context: Context) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val checkpoints = WorkspaceCheckpoints(appContext.filesDir, appContext)

    /** Paths of the branch of [chatId]; registers the clone directory for the pseudo project. */
    fun branchFor(project: Project, chatId: String): ChatBranch {
        val id = branchId(chatId)
        val main = checkpoints.ensureWorkspace(project.id)
        val dir = File(main, ".forge/chats/$id")
        val pseudo = pseudoProjectId(project.id, id)
        WorkspaceCheckpoints.registerWorkspaceOverride(pseudo, dir)
        return ChatBranch(pseudo, branchSlug(project.slug, id), dir)
    }

    /** Creates the project repository if needed, snapshots uncommitted work, and clones it for the chat. */
    suspend fun ensure(project: Project, chatId: String, onWaitingForLock: (() -> Unit)? = null): ChatBranch = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        val id = branchId(chatId)
        val result = git(project, chatId, ENSURE_SCRIPT, onWaitingForLock = onWaitingForLock)
        check(result.ok) { "Could not prepare the chat branch: ${result.output.trim().takeLast(300)}" }
        branch.also { check(File(it.cloneDir, ".git").exists()) { "The chat branch clone is missing ($id)." } }
    }

    /** Commits what the agent changed in the branch (one commit per turn). */
    suspend fun commitTurn(
        project: Project,
        chatId: String,
        message: String,
        onWaitingForLock: (() -> Unit)? = null,
    ) = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        git(project, chatId, COMMIT_SCRIPT, "MSG" to commitMessage(message), onWaitingForLock = onWaitingForLock)
    }

    /** State and latest commits of the project's own repository (not a chat branch). */
    suspend fun repoInfo(project: Project, onWaitingForLock: (() -> Unit)? = null): ProjectRepoInfo = withContext(Dispatchers.IO) {
        val result = gitProject(project, REPO_INFO_SCRIPT, onWaitingForLock = onWaitingForLock)
        if (result.ok) parseRepoInfo(result.output) else ProjectRepoInfo()
    }

    /** Commits everything in the project folder (creating the repository first when there is none). */
    suspend fun commitProject(
        project: Project,
        message: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): ChrootResult = withContext(Dispatchers.IO) {
        gitProject(project, COMMIT_PROJECT_SCRIPT, "MSG" to commitMessage(message), onWaitingForLock = onWaitingForLock)
    }

    suspend fun createBranch(
        project: Project,
        name: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): RepoOpResult = withContext(Dispatchers.IO) {
        val clean = branchName(name) ?: return@withContext RepoOpResult.Failed("Invalid branch name")
        parseRepoOp(gitProject(project, CREATE_BRANCH_SCRIPT, "NAME" to clean, onWaitingForLock = onWaitingForLock))
    }

    suspend fun switchBranch(
        project: Project,
        name: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): RepoOpResult = withContext(Dispatchers.IO) {
        parseRepoOp(gitProject(project, SWITCH_BRANCH_SCRIPT, "NAME" to name, onWaitingForLock = onWaitingForLock))
    }

    /** Safe delete (`git branch -d`): refuses a branch with commits that are not merged. */
    suspend fun deleteBranch(
        project: Project,
        name: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): RepoOpResult = withContext(Dispatchers.IO) {
        parseRepoOp(gitProject(project, DELETE_BRANCH_SCRIPT, "NAME" to name, onWaitingForLock = onWaitingForLock))
    }

    /** Commits the branch now with the user's own message (the turn commit uses the prompt's first line). */
    suspend fun commitNow(
        project: Project,
        chatId: String,
        message: String,
        onWaitingForLock: (() -> Unit)? = null,
    ) = commitTurn(project, chatId, message, onWaitingForLock)

    suspend fun status(project: Project, chatId: String): BranchStatus? = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        if (!File(branch.cloneDir, ".git").exists()) return@withContext null
        val result = git(project, chatId, STATUS_SCRIPT)
        if (result.ok) parseStatus(result.output) else null
    }

    /** `git diff --stat` and the patch of the branch against the commit it was cut from (long patches are cut). */
    suspend fun diff(project: Project, chatId: String): String = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, DIFF_SCRIPT)
        result.output.take(MAX_DIFF_CHARS).ifBlank { "" }
    }

    suspend fun mergeIntoMain(
        project: Project,
        chatId: String,
        title: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): BranchMergeResult = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, MERGE_SCRIPT, "TITLE" to commitMessage("Merge chat: $title"), onWaitingForLock = onWaitingForLock)
        parseMergeResult(result.output, result.ok)
    }

    /** Brings the project's newer commits into the branch; conflicts are left in the branch for the agent to resolve. */
    suspend fun updateFromMain(
        project: Project,
        chatId: String,
        onWaitingForLock: (() -> Unit)? = null,
    ): BranchMergeResult = withContext(Dispatchers.IO) {
        branchFor(project, chatId)
        val result = git(project, chatId, UPDATE_SCRIPT, onWaitingForLock = onWaitingForLock)
        parseMergeResult(result.output, result.ok)
    }

    suspend fun discard(project: Project, chatId: String, onWaitingForLock: (() -> Unit)? = null) = withContext(Dispatchers.IO) {
        val branch = branchFor(project, chatId)
        git(project, chatId, DISCARD_SCRIPT, onWaitingForLock = onWaitingForLock)
        WorkspaceCheckpoints.clearWorkspaceOverride(branch.pseudoProjectId)
    }

    private suspend fun gitProject(
        project: Project,
        script: String,
        vararg extra: Pair<String, String>,
        onWaitingForLock: (() -> Unit)? = null,
    ): ChrootResult {
        val main = checkpoints.ensureWorkspace(project.id)
        val env = mapOf("FORGE_ID" to "main", "FORGE_SLUG" to project.slug) + extra.toMap()
        var notified = false
        val raw = runner.run(
            workspaceDir = main,
            slug = project.slug,
            command = script,
            environment = env,
            timeoutMillis = GIT_TIMEOUT_MS,
            onChunk = if (onWaitingForLock != null) {
                { chunk ->
                    if (!notified && chunk.contains(WAIT_TOKEN)) {
                        notified = true
                        onWaitingForLock()
                    }
                }
            } else {
                null
            },
        )
        return raw.copy(output = stripWaitTokens(raw.output))
    }

    private suspend fun git(
        project: Project,
        chatId: String,
        script: String,
        vararg extra: Pair<String, String>,
        onWaitingForLock: (() -> Unit)? = null,
    ): ChrootResult {
        val id = branchId(chatId)
        val main = checkpoints.ensureWorkspace(project.id)
        val env = mapOf("FORGE_ID" to id, "FORGE_SLUG" to project.slug) + extra.toMap()
        var notified = false
        val raw = runner.run(
            workspaceDir = main,
            slug = project.slug,
            command = script,
            environment = env,
            timeoutMillis = GIT_TIMEOUT_MS,
            onChunk = if (onWaitingForLock != null) {
                { chunk ->
                    if (!notified && chunk.contains(WAIT_TOKEN)) {
                        notified = true
                        onWaitingForLock()
                    }
                }
            } else {
                null
            },
        )
        return raw.copy(output = stripWaitTokens(raw.output))
    }

    companion object {
        private const val GIT_TIMEOUT_MS = 3 * 60_000L
        const val MAX_DIFF_CHARS = 120_000
        const val WAIT_TOKEN = "[FORGE_GIT_WAITING]"

        fun stripWaitTokens(output: String): String =
            output.lineSequence().filterNot { it.trim() == WAIT_TOKEN }.joinToString("\n")

        /**
         * Shell helper snippet that queues behind any active git operation in the current repository (or `$M` / `$C`),
         * emits [WAIT_TOKEN] once when waiting so the UI can inform the user, and cleans up stale `.git/index.lock`
         * files left behind by crashed processes.
         */
        val GIT_QUEUE_WAIT_SH: String = """
            # pgrep -x misses git-lfs/git-remote-* helpers; the anchored -f pattern catches them without matching this
            # script's own command line (which starts with bash, not git).
            forge_wait_git_lock() {
              local notified=0
              local waited=0
              while [ -f ".git/index.lock" ] || { [ -n "${'$'}{M:-}" ] && [ -f "${'$'}M/.git/index.lock" ]; } || { [ -n "${'$'}{C:-}" ] && [ -f "${'$'}C/.git/index.lock" ]; }; do
                if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                  sleep 0.2
                  if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                    rm -f ".git/index.lock" "${'$'}{M:-.}/.git/index.lock" "${'$'}{C:-.}/.git/index.lock" 2>/dev/null || true
                    break
                  fi
                fi
                if [ "${'$'}notified" -eq 0 ]; then
                  echo "$WAIT_TOKEN"
                  notified=1
                fi
                sleep 0.5
                waited=${'$'}((waited + 1))
                if [ "${'$'}waited" -ge 120 ]; then
                  if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                    rm -f ".git/index.lock" "${'$'}{M:-.}/.git/index.lock" "${'$'}{C:-.}/.git/index.lock" 2>/dev/null || true
                  fi
                  break
                fi
              done
            }
        """.trimIndent()

        /** Eight letters or digits of the chat id: short, safe in paths, refs and branch names. */
        fun branchId(chatId: String): String =
            chatId.filter { it.isLetterOrDigit() }.lowercase().takeLast(8).ifEmpty { "main" }

        fun pseudoProjectId(projectId: String, id: String): String = "$projectId-c$id"

        /** Chroot workspace names are at most 64 characters of [a-z0-9-]. */
        fun branchSlug(projectSlug: String, id: String): String = "${projectSlug.take(48).trimEnd('-')}-c$id"

        fun commitMessage(text: String): String =
            text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim().replace(Regex("[\\u0000-\\u001f]"), " ").take(100)
                .ifEmpty { "Update" }

        /** Reads the `key=value` and `C <hash> <subject>` lines STATUS_SCRIPT prints. */
        fun parseStatus(output: String): BranchStatus {
            val values = HashMap<String, String>()
            val commits = ArrayList<BranchCommit>()
            output.lineSequence().forEach { line ->
                when {
                    line.startsWith("C ") -> line.removePrefix("C ").split(' ', limit = 2).let {
                        if (it.size == 2) commits += BranchCommit(it[0], it[1])
                    }
                    '=' in line -> values[line.substringBefore('=')] = line.substringAfter('=').trim()
                }
            }
            val stat = values["stat"].orEmpty().split(' ').mapNotNull { it.toIntOrNull() }
            return BranchStatus(
                commits = values["ahead"]?.toIntOrNull() ?: 0,
                uncommitted = values["uncommitted"]?.toIntOrNull() ?: 0,
                additions = stat.getOrElse(0) { 0 },
                deletions = stat.getOrElse(1) { 0 },
                filesChanged = stat.getOrElse(2) { 0 },
                behind = values["behind"]?.toIntOrNull() ?: 0,
                conflicts = values["conflicts"].orEmpty().split(',').map(String::trim).filter(String::isNotEmpty),
                recent = commits,
            )
        }

        /** A safe git branch name from free text, or null when nothing usable is left. */
        fun branchName(text: String): String? {
            val clean = text.trim().lowercase()
                .replace(Regex("\\s+"), "-")
                .replace(Regex("[^a-z0-9._/-]"), "")
                .replace(Regex("[/.-]{2,}")) { it.value.first().toString() }
                .trim('-', '/', '.')
                .take(60)
                .trimEnd('-', '/', '.')
            return clean.takeIf { it.isNotEmpty() && !it.endsWith(".lock") && !it.startsWith("chat/") }
        }

        fun parseRepoOp(result: ChrootResult): RepoOpResult {
            val lines = result.output.lineSequence().map(String::trim).toList()
            return when {
                lines.any { it == "RESULT=ok" } -> RepoOpResult.Done
                lines.any { it == "RESULT=dirty" } -> RepoOpResult.Dirty
                else -> RepoOpResult.Failed(
                    lines.firstOrNull { it.startsWith("msg=") }?.removePrefix("msg=")
                        ?: result.output.trim().takeLast(200).ifEmpty { "git failed" },
                )
            }
        }

        /** Reads the `key=value` and `C <hash> <subject>` lines REPO_INFO_SCRIPT prints. */
        fun parseRepoInfo(output: String): ProjectRepoInfo {
            val values = HashMap<String, String>()
            val commits = ArrayList<BranchCommit>()
            val branches = ArrayList<String>()
            output.lineSequence().forEach { line ->
                when {
                    line.startsWith("C ") -> line.removePrefix("C ").split(' ', limit = 2).let {
                        if (it.size == 2) commits += BranchCommit(it[0], it[1])
                    }
                    line.startsWith("B ") -> branches += line.removePrefix("B ").trim()
                    '=' in line -> values[line.substringBefore('=')] = line.substringAfter('=').trim()
                }
            }
            return ProjectRepoInfo(
                isRepo = values["repo"] == "yes",
                branch = values["branch"].orEmpty(),
                uncommitted = values["uncommitted"]?.toIntOrNull() ?: 0,
                recent = commits,
                branches = branches,
            )
        }

        fun parseMergeResult(output: String, ok: Boolean): BranchMergeResult {
            val lines = output.lineSequence().map(String::trim).toList()
            return when {
                lines.any { it == "RESULT=merged" || it == "RESULT=updated" } -> BranchMergeResult.Done
                lines.any { it == "RESULT=conflict" } -> BranchMergeResult.Conflict(
                    lines.firstOrNull { it.startsWith("files=") }.orEmpty().removePrefix("files=")
                        .split(',').map(String::trim).filter(String::isNotEmpty),
                )
                lines.any { it == "RESULT=failed" } -> BranchMergeResult.Failed(
                    lines.firstOrNull { it.startsWith("msg=") }.orEmpty().removePrefix("msg=").ifEmpty { "git failed" },
                )
                else -> BranchMergeResult.Failed(output.trim().takeLast(300).ifEmpty { if (ok) "Unexpected result" else "git failed" })
            }
        }

        // Shell scripts: § stands for $ so that Kotlin does not read them as templates.
        internal fun script(body: String): String = ("""
            set -e
            M="/workspace/§FORGE_SLUG"
            C="§M/.forge/chats/§FORGE_ID"
            # pgrep -x misses git-lfs/git-remote-* helpers; the anchored -f pattern catches them without matching this
            # script's own command line (which starts with bash, not git).
            forge_wait_git_lock() {
              local notified=0
              local waited=0
              while [ -f ".git/index.lock" ] || [ -f "§M/.git/index.lock" ] || [ -f "§C/.git/index.lock" ]; do
                if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                  sleep 0.2
                  if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                    rm -f ".git/index.lock" "§M/.git/index.lock" "§C/.git/index.lock" 2>/dev/null || true
                    break
                  fi
                fi
                if [ "§notified" -eq 0 ]; then
                  echo "[FORGE_GIT_WAITING]"
                  notified=1
                fi
                sleep 0.5
                waited=§((waited + 1))
                if [ "§waited" -ge 120 ]; then
                  if ! pgrep -x git >/dev/null 2>&1 && ! pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; then
                    rm -f ".git/index.lock" "§M/.git/index.lock" "§C/.git/index.lock" 2>/dev/null || true
                  fi
                  break
                fi
              done
            }
            G() {
              local attempt=0
              while true; do
                forge_wait_git_lock
                if git -c user.name="NextCraft Forge" -c user.email="forge@localhost" -c safe.directory='*' -c core.fileMode=false "§@"; then
                  return 0
                fi
                local rc=§?
                if [ "§attempt" -lt 6 ] && { [ -f ".git/index.lock" ] || [ -f "§M/.git/index.lock" ] || [ -f "§C/.git/index.lock" ] || pgrep -x git >/dev/null 2>&1 || pgrep -f '^(\S*/)?git(-[a-z]+)?( |§)' >/dev/null 2>&1; }; then
                  if [ "§attempt" -eq 0 ]; then echo "[FORGE_GIT_WAITING]"; fi
                  attempt=§((attempt + 1))
                  sleep 0.5
                  continue
                fi
                return "§rc"
              done
            }
        """.trimIndent() + "\n" + body.trimIndent()).replace('§', '$')

        /** Makes sure the project is a git repository with everything committed, then clones it for the chat. */
        private val ENSURE_SCRIPT = script("""
            cd "§M"
            [ -d .git ] || G init -q -b main
            mkdir -p .git/info
            grep -qxF '/.forge/' .git/info/exclude 2>/dev/null || echo '/.forge/' >> .git/info/exclude
            G add -A
            if ! G rev-parse -q --verify HEAD >/dev/null; then
              G commit -q --allow-empty -m "Forge: initial commit"
            elif ! G diff --cached --quiet; then
              G commit -q -m "Forge: snapshot before chat branch"
            fi
            if [ ! -d "§C/.git" ]; then
              mkdir -p "§(dirname "§C")"
              G clone -q --local "§M" "§C"
              cd "§C"
              G checkout -q -b "chat/§FORGE_ID"
              G tag -f forge-base HEAD >/dev/null
            fi
        """)

        private val REPO_INFO_SCRIPT = script("""
            cd "§M"
            if [ ! -d .git ]; then echo repo=none; exit 0; fi
            echo repo=yes
            echo "branch=§(G rev-parse --abbrev-ref HEAD 2>/dev/null || echo -)"
            echo "uncommitted=§(G status --porcelain | wc -l)"
            G for-each-ref --format='B %(refname:short)' refs/heads 2>/dev/null || true
            G log --format='C %h %s' -n 15 2>/dev/null || true
        """)

        private val CREATE_BRANCH_SCRIPT = script("""
            cd "§M"
            [ -d .git ] || { echo "msg=Make the first commit before creating a branch"; exit 0; }
            if ! G check-ref-format --branch "§NAME" >/dev/null 2>&1; then echo "msg=Invalid branch name"; exit 0; fi
            if G show-ref -q --verify "refs/heads/§NAME"; then echo "msg=That branch already exists"; exit 0; fi
            G checkout -q -b "§NAME" && echo RESULT=ok
        """)

        /** Switching with uncommitted changes could carry or lose them; the user commits first. */
        private val SWITCH_BRANCH_SCRIPT = script("""
            cd "§M"
            if [ -n "§(G status --porcelain)" ]; then echo RESULT=dirty; exit 0; fi
            G checkout -q "§NAME" && echo RESULT=ok
        """)

        private val DELETE_BRANCH_SCRIPT = script("""
            cd "§M"
            if [ "§(G rev-parse --abbrev-ref HEAD)" = "§NAME" ]; then echo "msg=Switch to another branch first"; exit 0; fi
            if OUT="§(G branch -d "§NAME" 2>&1)"; then echo RESULT=ok; else echo "msg=§(echo "§OUT" | tail -n 1)"; fi
        """)

        private val COMMIT_PROJECT_SCRIPT = script("""
            cd "§M"
            [ -d .git ] || G init -q -b main
            mkdir -p .git/info
            grep -qxF '/.forge/' .git/info/exclude 2>/dev/null || echo '/.forge/' >> .git/info/exclude
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "§MSG"; fi
        """)

        private val COMMIT_SCRIPT = script("""
            cd "§C"
            # `add -A` marks every conflicted file as resolved. After a merge that stopped on conflicts, a file that still
            # holds conflict markers must not be committed: the markers would end up in the user's project.
            for f in §(G diff --name-only --diff-filter=U); do
              if grep -q '^<<<<<<< ' "§f" 2>/dev/null; then echo "unresolved conflict in §f" >&2; exit 3; fi
            done
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "§MSG"; fi
        """)

        private val STATUS_SCRIPT = script("""
            cd "§C"
            echo "ahead=§(G rev-list --count forge-base..HEAD)"
            echo "uncommitted=§(G status --porcelain | wc -l)"
            echo "conflicts=§(G diff --name-only --diff-filter=U | tr '\n' ',')"
            G add -N . 2>/dev/null || true
            echo "stat=§(G diff --numstat forge-base | awk '{a+=§1;d+=§2;f++} END{print a+0,d+0,f+0}')"
            echo "behind=§(G -C "§M" rev-list --count "§(G rev-parse forge-base)"..HEAD)"
            G log --format='C %h %s' -n 8 forge-base..HEAD
        """)

        private val DIFF_SCRIPT = script("""
            cd "§C"
            G add -N . 2>/dev/null || true
            G diff --stat forge-base || true
            echo
            G diff forge-base || true
        """)

        /** Merges the committed branch into the project; a conflict aborts the merge and leaves the project untouched. */
        private val MERGE_SCRIPT = script("""
            if [ -n "§(G -C "§C" diff --name-only --diff-filter=U)" ]; then
              echo RESULT=conflict
              echo "files=§(G -C "§C" diff --name-only --diff-filter=U | tr '\n' ',')"
              exit 0
            fi
            cd "§M"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: snapshot before merging a chat"; fi
            G fetch -q "§C" "+HEAD:refs/forge/chat-§FORGE_ID"
            if G merge --no-ff -m "§TITLE" "refs/forge/chat-§FORGE_ID" >/dev/null 2>&1; then
              echo RESULT=merged
              cd "§C"
              G fetch -q "§M" HEAD
              # Only move the base when the branch really caught up; otherwise status would hide real divergence.
              if G merge -q --ff-only FETCH_HEAD >/dev/null 2>&1; then G tag -f forge-base HEAD >/dev/null; fi
            else
              FILES="§(G diff --name-only --diff-filter=U | tr '\n' ',')"
              G merge --abort >/dev/null 2>&1 || true
              if [ -n "§FILES" ]; then
                echo RESULT=conflict
                echo "files=§FILES"
              else
                echo RESULT=failed
                echo "msg=git merge failed without conflicts (untracked files in the way?)"
              fi
            fi
        """)

        /** Merges the project into the branch; on conflict the merge stays open in the branch for the agent. */
        private val UPDATE_SCRIPT = script("""
            cd "§M"
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: snapshot before updating a chat"; fi
            cd "§C"
            if [ -n "§(G diff --name-only --diff-filter=U)" ]; then
              echo RESULT=conflict
              echo "files=§(G diff --name-only --diff-filter=U | tr '\n' ',')"
              exit 0
            fi
            G add -A
            if ! G diff --cached --quiet; then G commit -q -m "Forge: save work before update"; fi
            G fetch -q "§M" HEAD
            G tag -f forge-base FETCH_HEAD >/dev/null
            if G merge --no-edit FETCH_HEAD >/dev/null 2>&1; then
              echo RESULT=updated
            else
              FILES="§(G diff --name-only --diff-filter=U | tr '\n' ',')"
              if [ -n "§FILES" ]; then
                echo RESULT=conflict
                echo "files=§FILES"
              else
                G merge --abort >/dev/null 2>&1 || true
                echo RESULT=failed
                echo "msg=git merge failed without conflicts (untracked files in the way?)"
              fi
            fi
        """)

        private val DISCARD_SCRIPT = script("""
            rm -rf "§C"
            cd "§M"
            G update-ref -d "refs/forge/chat-§FORGE_ID" >/dev/null 2>&1 || true
        """)
    }
}
