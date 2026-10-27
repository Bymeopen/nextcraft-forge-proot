package com.nextcraft.forge.runtime

import android.content.Context
import android.os.StatFs
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RomState(
    val manifestUrl: String = "",
    val branch: String = "",
    val directory: String = "rom",
    val jobs: Int = 4,
    val log: String = "",
    val running: Boolean = false,
    val freeStorage: String = "",
)

internal val ROM_MANIFEST_URL = Regex("https://[A-Za-z0-9._~:/?#@!$&()*+,;=%-]{4,300}")
internal val ROM_BRANCH = Regex("[A-Za-z0-9._/-]{1,100}")
// No leading "-" or "."; "mkdir -p -x" / "cd .." would be taken as an option or escape the ROM workspace.
internal val ROM_DIRECTORY = Regex("[A-Za-z0-9_][A-Za-z0-9._-]{0,59}")

/**
 * ROM Studio: orchestrates Google's `repo` tool (install, init, sync) in the chroot so a custom ROM source tree can be
 * fetched on the device. Syncing is network- and storage-heavy; the log shows what each step prints.
 */
class RomStudioController(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val appContext = context.applicationContext
    private val runner = ChrootRunner(appContext)
    private val prefs = appContext.getSharedPreferences("forge_tools", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(
        RomState(
            manifestUrl = prefs.getString("rom_manifest", "") ?: "",
            branch = prefs.getString("rom_branch", "") ?: "",
            directory = prefs.getString("rom_dir", "rom") ?: "rom",
            jobs = prefs.getInt("rom_jobs", 4).coerceIn(1, 16),
        ),
    )
    val state: StateFlow<RomState> = mutable.asStateFlow()
    private var job: Job? = null

    fun update(manifestUrl: String? = null, branch: String? = null, directory: String? = null, jobs: Int? = null) {
        mutable.update {
            it.copy(
                manifestUrl = manifestUrl?.trim() ?: it.manifestUrl,
                branch = branch?.trim() ?: it.branch,
                directory = directory?.trim() ?: it.directory,
                jobs = (jobs ?: it.jobs).coerceIn(1, 16),
            )
        }
        val s = mutable.value
        prefs.edit().putString("rom_manifest", s.manifestUrl).putString("rom_branch", s.branch).putString("rom_dir", s.directory).putInt("rom_jobs", s.jobs).apply()
    }

    fun refreshStorage() {
        val free = runCatching { StatFs(appContext.filesDir.absolutePath).availableBytes }.getOrDefault(0L)
        mutable.update { it.copy(freeStorage = "%.1f GB free".format(free / (1L shl 30).toDouble())) }
    }

    fun installRepoTool() = launchStep(
        "Install the repo tool",
        "command -v git >/dev/null && command -v python3 >/dev/null || { echo 'git and python3 are required: install the Web and Python stacks in Toolchains first.'; exit 1; }; " +
            "curl -fsSL https://storage.googleapis.com/git-repo-downloads/repo -o /tmp/repo.tmp && " +
            repoLauncherCheck() +
            "install -m 0755 /tmp/repo.tmp /usr/local/bin/repo && rm -f /tmp/repo.tmp && /usr/local/bin/repo --version",
    )

    /**
     * Shell fragment that verifies the downloaded launcher. With [REPO_LAUNCHER_SHA256] pinned the file must match the
     * digest; an empty pin falls back to the shebang check and logs a warning so maintainers notice the missing pin.
     */
    private fun repoLauncherCheck(): String {
        if (REPO_LAUNCHER_SHA256.isNotBlank()) {
            return "echo '${REPO_LAUNCHER_SHA256}  /tmp/repo.tmp' | sha256sum -c --status - || { echo 'repo launcher checksum mismatch'; rm -f /tmp/repo.tmp; exit 1; }; "
        }
        Log.w(TAG, "REPO_LAUNCHER_SHA256 is not pinned; falling back to the shebang check for the repo launcher")
        return "head -n 1 /tmp/repo.tmp | grep -q '^#!/usr/bin/env python' && "
    }

    fun init() {
        val s = mutable.value
        if (!ROM_MANIFEST_URL.matches(s.manifestUrl)) return fail("Manifest URL must be an https:// link.")
        if (!ROM_BRANCH.matches(s.branch)) return fail("Enter the branch to sync, e.g. lineage-21.0.")
        if (!ROM_DIRECTORY.matches(s.directory)) return fail("Folder name may only contain letters, digits, . _ -")
        val dir = ChrootRunner.quote(s.directory)
        launchStep(
            "repo init",
            "mkdir -p $dir && cd $dir && git config --global user.name >/dev/null 2>&1 || git config --global user.name forge; " +
                "git config --global user.email >/dev/null 2>&1 || git config --global user.email forge@android.local; " +
                "repo init --depth=1 --no-clone-bundle -u ${ChrootRunner.quote(s.manifestUrl)} -b ${ChrootRunner.quote(s.branch)} </dev/null",
        )
    }

    fun sync() {
        val s = mutable.value
        if (!ROM_DIRECTORY.matches(s.directory)) return fail("Folder name may only contain letters, digits, . _ -")
        launchStep(
            "repo sync",
            "cd ${ChrootRunner.quote(s.directory)} && repo sync -c -j${s.jobs} --no-tags --no-clone-bundle --optimized-fetch --prune --fail-fast </dev/null",
            timeoutMillis = 12 * 60 * 60_000L,
        )
    }

    fun cancel() {
        job?.cancel()
    }

    private fun fail(message: String) = mutable.update { it.copy(log = (it.log + "✕ $message\n").takeLast(MAX_LOG_CHARS)) }

    private fun launchStep(title: String, command: String, timeoutMillis: Long = 30 * 60_000L) {
        if (mutable.value.running) return
        mutable.update { it.copy(running = true, log = (it.log + "\n▶ $title\n").takeLast(MAX_LOG_CHARS)) }
        job = scope.launch {
            try {
                val result = runner.run(runner.toolWorkspace(SLUG), SLUG, command, timeoutMillis = timeoutMillis) { chunk ->
                    mutable.update { it.copy(log = (it.log + chunk).takeLast(MAX_LOG_CHARS)) }
                }
                mutable.update { it.copy(log = (it.log + if (result.ok) "✓ $title\n" else "✕ $title (exit ${result.exitCode}${if (result.timedOut) ", timed out" else ""})\n").takeLast(MAX_LOG_CHARS)) }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(log = (it.log + "■ stopped\n").takeLast(MAX_LOG_CHARS)) }
                throw cancelled
            } catch (error: Throwable) {
                mutable.update { it.copy(log = (it.log + "✕ ${error.message}\n").takeLast(MAX_LOG_CHARS)) }
            } finally {
                mutable.update { it.copy(running = false) }
                refreshStorage()
            }
        }
    }

    private companion object {
        const val TAG = "RomStudio"
        const val SLUG = "rom"
        const val MAX_LOG_CHARS = 80_000

        /**
         * SHA-256 (lowercase hex) of the `repo` launcher at https://storage.googleapis.com/git-repo-downloads/repo.
         * Maintainers must fill this with the digest of the release they ship against (`sha256sum repo`) and bump it
         * when the launcher is updated; while empty only the shebang is checked.
         */
        const val REPO_LAUNCHER_SHA256 = ""
    }
}
