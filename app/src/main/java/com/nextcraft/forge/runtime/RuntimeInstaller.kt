package com.nextcraft.forge.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream
import org.json.JSONArray
import org.json.JSONObject

data class InstalledRuntime(
    /** The PRoot supervisor script (`proot-exec.sh`); kept under its historical name for the many call sites. */
    val chrootExec: File,
    val rootfs: File,
) {
    val binDir: File get() = chrootExec.parentFile ?: rootfs
}

data class RuntimeInstallProgress(
    val message: String,
    val fraction: Float,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val terminalLine: String? = null,
    val indeterminate: Boolean = false,
    val event: RuntimeInstallEvent = RuntimeInstallEvent.STAGE,
    /** True when [downloadedBytes] counts archive bytes unpacked rather than downloaded. */
    val unpacking: Boolean = false,
)

data class AgentUpdateInfo(
    val installedVersion: String,
    val latestVersion: String,
)

/** The pinned dsh, or a newer one left by a later app build: an older app must not reinstall (downgrade) it. */
private val FS_PROMISES_IMPORT = Regex("""import \{([^}]*)\} from "node:fs/promises";""")

/**
 * Swaps DeepSeek's hard-link publication for COPYFILE_EXCL (`copyFile(src, dst, 1)`): Android's app data filesystem
 * refuses link(2) inside the chroot. Adds `copyFile` to the `node:fs/promises` import whatever else it lists, so new
 * DeepSeek releases that reorder that import still match. Returns the source unchanged when already patched, or null
 * when the expected call or import is missing.
 */
internal fun patchDshSource(source: String, callBefore: String, callAfter: String): String? {
    val import = FS_PROMISES_IMPORT.find(source) ?: return null
    val names = import.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val hasCopyFile = "copyFile" in names
    if (callAfter in source && hasCopyFile) return source
    if (callBefore !in source) return null
    val withImport = if (hasCopyFile) source else source.replaceRange(
        import.range,
        "import { ${(names + "copyFile").sorted().joinToString(", ")} } from \"node:fs/promises\";",
    )
    return withImport.replace(callBefore, callAfter)
}

/** Oldest DeepSeek release the compatibility patch still handles; installs since then keep working without a reinstall. */
private const val MIN_DSH_VERSION = "0.1.2-rc.1"

internal fun isSupportedDshVersion(version: String): Boolean =
    version == MIN_DSH_VERSION || isAgentVersionNewer(version, MIN_DSH_VERSION)

/** True when [candidate] is a newer agent version than [current] (numeric parts, then pre-release tags). */
internal fun isAgentVersionNewer(candidate: String, current: String): Boolean {
    fun numericParts(value: String) = value.substringBefore('-').substringBefore('+')
        .split('.')
        .map { it.toIntOrNull() ?: 0 }
    val left = numericParts(candidate)
    val right = numericParts(current)
    for (index in 0 until maxOf(left.size, right.size)) {
        val comparison = (left.getOrElse(index) { 0 }).compareTo(right.getOrElse(index) { 0 })
        if (comparison != 0) return comparison > 0
    }
    val candidatePreTag = candidate.substringBefore('+').substringAfter('-', "")
    val currentPreTag = current.substringBefore('+').substringAfter('-', "")
    if (candidatePreTag.isEmpty() || currentPreTag.isEmpty()) {
        // A release (no pre-release tag) always outranks a pre-release of the same numeric version.
        return currentPreTag.isNotEmpty() && candidatePreTag.isEmpty()
    }
    // Same numeric version, both pre-release (e.g. "rc.1" vs "rc.2"): compare identifiers in order.
    val candidateIds = candidatePreTag.split('.')
    val currentIds = currentPreTag.split('.')
    for (index in 0 until maxOf(candidateIds.size, currentIds.size)) {
        val candidateId = candidateIds.getOrNull(index)
        val currentId = currentIds.getOrNull(index)
        if (candidateId == null) return false
        if (currentId == null) return true
        val candidateNum = candidateId.toIntOrNull()
        val currentNum = currentId.toIntOrNull()
        val comparison = if (candidateNum != null && currentNum != null) {
            candidateNum.compareTo(currentNum)
        } else {
            candidateId.compareTo(currentId)
        }
        if (comparison != 0) return comparison > 0
    }
    return false
}

enum class RuntimeInstallEvent { STAGE, COMMAND, OUTPUT, DOWNLOAD, COMMAND_COMPLETED, COMPLETED }

private data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    val baseUrl: String = RUNTIME_RELEASE_BASE_URL,
)

/** Release hosting the Ubuntu 24.04 bundles built from scripts/runtime-bundles. */
private const val RUNTIME_RELEASE_BASE_URL = "https://github.com/bymeopen/nextcraft-forge/releases/download/runtime-2026.10.2"


class RuntimeInstaller(private val context: Context) {
    private val runtimeDir = File(context.filesDir, "runtime")
    private val runtimeBinDir = File(runtimeDir, "bin")
    private val prootExecFile = File(runtimeBinDir, "proot-exec.sh")

    /** Historical name of the supervisor script handle; it is the PRoot script in this build. */
    private val chrootExecFile: File get() = prootExecFile
    private val rootfs = File(runtimeDir, "ubuntu")
    private val downloads = File(context.cacheDir, "runtime-downloads")
    private val coreReadyMarker = File(rootfs, ".nextcraft-runtime-ready")
    private val claudeMarker = File(rootfs, ".nextcraft-claude-version")
    /** Official Claude Code release that failed its `--version` check under PRoot on this device. */
    private val claudeUnsupportedMarker = File(rootfs, ".nextcraft-claude-unsupported-version")
    private val bundledClaudeMarker = File(rootfs, ".nextcraft-bundled-claude-version")
    private val rootfsMarker = File(rootfs, ".nextcraft-rootfs-version")
    private val languageToolsMarker = File(rootfs, ".nextcraft-language-tools-version")
    private val coreToolsMarker = File(rootfs, ".nextcraft-core-tools-version")
    private val systemUpgradeMarker = File(rootfs, ".nextcraft-system-upgrade-version")
    private val devStacksFile = File(rootfs, ".nextcraft-dev-stacks.json")
    private val dshMarker = File(rootfs, ".nextcraft-dsh-version")
    private val agyMarker = File(rootfs, ".nextcraft-agy-version")
    private val githubCliMarker = File(rootfs, ".nextcraft-github-cli-version")
    private val dshAndroidCompatibilityMarker = File(rootfs, ".nextcraft-dsh-android-compat-version")
    private val macosMetadataRepairMarker = File(rootfs, ".nextcraft-macos-metadata-repair")

    fun isInstalled(): Boolean {
        RuntimeMarkers.syncAll(rootfs)
        val chrootScriptsReady = runCatching { ensureChrootScripts() }.isSuccess
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = chrootScriptsReady &&
            prootExecFile.isFile &&
            rootfsLayoutReady &&
            File(rootfs, "usr/bin/bash").exists() &&
            isSupportedRootfsVersion(rootfsMarker.readTextOrNull()) &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            coreReadyMarker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    private fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        // Stay out of the directories PRoot binds (/proc, /system, /workspace, ...) so the walk is neither huge nor
        // able to delete "._*" files from a user's project.
        stripMacosMetadataArtifacts(rootfs, skipTopLevel = BIND_MOUNTED_TOP_LEVEL)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeMarker("1")
    }

    /** Returns the already verified PRoot runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { CORE_MISSING_MESSAGE }
        return InstalledRuntime(
            chrootExec = chrootExecFile,
            rootfs = rootfs,
        )
    }

    /** Ensures `proot-exec.sh` is installed in `filesDir/runtime/bin`; stale copies from older builds are removed. */
    @Synchronized
    fun ensureChrootScripts(): File {
        runtimeBinDir.mkdirs()
        listOf("chroot-exec.sh", "unmount-chroot.sh").forEach { File(runtimeBinDir, it).delete() }
        if (!prootExecFile.isFile || prootExecFile.readTextOrNull() != PROOT_EXEC_SCRIPT.trim()) {
            writeScriptAtomically(prootExecFile, PROOT_EXEC_SCRIPT)
        }
        // Never executed directly (app data is noexec for the app UID): it is always run as `/system/bin/sh script`.
        Os.chmod(prootExecFile.absolutePath, 0b110100100)
        return prootExecFile
    }

    /** Active engine for this process; see [RuntimeEngines]. */
    fun runtimeEngine(): RuntimeEngine? = RuntimeEngines.detect(context)

    /** True when the PRoot binary and loader shipped in the APK are usable. */
    fun isRuntimeAccessAvailable(): Boolean = runtimeEngine() != null

    /** Arguments that launch a command line produced by [prepareChrootShellCommand] under the active engine. */
    fun launchArgv(shellCommand: String): List<String> = RuntimeEngines.launchArgv(shellCommand)

    /** Replaces the script via rename so a concurrent launch never executes a half-written file. */
    private fun writeScriptAtomically(target: File, content: String) {
        // Unique per call: several RuntimeInstaller instances can refresh the scripts at the same time.
        val staged = File(target.parentFile, "${target.name}.${AndroidProcess.myPid()}.${System.nanoTime()}.tmp")
        try {
            staged.writeText(content)
            Os.rename(staged.absolutePath, target.absolutePath)
        } catch (e: Throwable) {
            staged.delete()
            throw e
        }
    }

    /** Removes only scaffolding written automatically by earlier alpha builds. */
    fun cleanupLegacyWorkspaceScaffolding() {
        val workspaces = File(context.filesDir, "workspaces")
        workspaces.listFiles { file -> file.isDirectory }.orEmpty().forEach { workspace ->
            File(workspace, "README.md").deleteIfExact(LEGACY_README)
            File(workspace, "index.html").deleteIfExact(LEGACY_INDEX)

            listOf(
                File(workspace, ".claude/settings.json"),
                File(workspace, ".claude.json"),
            ).forEach { settings ->
                if (settings.isFile && settings.readTextOrNull()?.contains("/opt/pocket/permission-hook.sh") == true) {
                    settings.delete()
                }
            }
            File(workspace, ".claude").takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
        }
    }

    private fun File.deleteIfExact(expected: String) {
        if (isFile && runCatching { readText() }.getOrNull() == expected) delete()
    }

    suspend fun ensureInstalled(
        selectedStacks: Set<DevStack> = emptySet(),
        agent: AgentKind = AgentKind.CLAUDE_CODE,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): InstalledRuntime {
        require(
            supportsArm64Runtime(
                android.os.Build.SUPPORTED_ABIS,
                System.getProperty("os.arch"),
            ),
        ) { "NextCraft Forge requires an ARM64 (arm64-v8a / aarch64) Android device." }
        requireNotNull(RuntimeEngines.detect(context, refresh = true)) {
            "The PRoot engine (libproot.so) is missing from this APK; reinstall NextCraft Forge."
        }
        val chrootExec = ensureChrootScripts()
        require(chrootExec.isFile) { "The PRoot supervisor script is unavailable" }

        val staging = File(runtimeDir, "ubuntu.installing")
        val repairBackup = File(runtimeDir, "ubuntu.repair-backup")
        RootfsRepair.reconcileInterruptedSwap(
            rootfs = rootfs,
            staging = staging,
            backup = repairBackup,
            validate = ::isValidCoreRootfs,
        )
        val layoutBroken = rootfs.isDirectory && !ensureRootfsCompatibilityLinks()
        if (layoutBroken ||
            !File(rootfs, "usr/bin/bash").exists() ||
            !isSupportedRootfsVersion(rootfsMarker.readTextOrNull())
        ) {
            onProgress(RuntimeInstallProgress("Preparing Ubuntu PRoot runtime…", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = isAssetAvailable("runtime/${CORE_BUNDLE.fileName}"),
                from = 0.03f,
                to = 0.25f,
                onProgress = onProgress,
            )
            onProgress(RuntimeInstallProgress("Unpacking Core Linux rootfs…", 0.28f))
            staging.deleteRecursively()
            staging.mkdirs()
            extractZstdTar(archive, staging) { done, total ->
                onProgress(unpackProgress("Unpacking Core Linux rootfs…", 0.28f, 0.34f, done, total))
            }
            stripMacosMetadataArtifacts(staging)
            ensureAndroidSocketGroupConfiguration(staging)
            RootfsRepair.activate(
                rootfs = rootfs,
                staging = staging,
                backup = repairBackup,
                validate = ::isValidCoreRootfs,
            )
            writeResolver()
            if (archive.parentFile == downloads) archive.delete()
        }

        check(ensureRootfsCompatibilityLinks()) { "Core runtime has an invalid Linux filesystem layout" }
        ensureAndroidSocketGroupConfiguration(rootfs)

        migrateLegacyClaudeMarker()
        ensureSettingsAndHooks()

        val coreNeeded = !File(rootfs, "usr/bin/git").exists() || !isSupportedCoreToolsVersion()
        // isInstalled() also requires Node; a missing/dangling node must be repaired even when git and the marker are fine.
        val nodeMissing = !File(rootfs, "usr/local/bin/node").exists()
        if (coreNeeded || nodeMissing) {
            installNodeIfNeeded(chrootExec, 0.58f, 0.66f, onProgress)
        }
        if (systemUpgradeMarker.readTextOrNull() != SYSTEM_UPGRADE_VERSION) {
            runSystemMaintenance(chrootExec, onProgress)
            systemUpgradeMarker.writeMarker(SYSTEM_UPGRADE_VERSION)
        }
        if (coreNeeded) {
            aptInstall(
                chrootExec,
                listOf("git", "ca-certificates"),
                "Installing Git and base SSL certificates…",
                0.70f,
                onProgress,
            )
            writeResolver()
            verifyGuest(chrootExec, "git --version", "Failed to verify Git inside the PRoot runtime")
            coreToolsMarker.writeMarker(CORE_TOOLS_VERSION)
        }
        if (!coreReadyMarker.exists()) coreReadyMarker.writeMarker(CORE_TOOLS_VERSION)

        val removedStacks = if (selectedStacks.isNotEmpty()) {
            (installedStacks() - selectedStacks) - DevStack.WEB
        } else {
            emptySet()
        }
        val missingStacks = selectedStacks.filterNot(::isStackInstalled)
        val totalStackOps = maxOf(1, removedStacks.size + missingStacks.size)
        var opIndex = 0
        removedStacks.forEach { stack ->
            val slice = 0.26f / totalStackOps
            val from = 0.72f + opIndex * slice
            removeStack(stack) { p ->
                onProgress(p.copy(fraction = from + p.fraction.coerceIn(0f, 1f) * slice))
            }
            opIndex++
        }
        // One broken stack (e.g. Android tools that cannot run) must not keep the others from installing:
        // install every selected stack, then report all failures together.
        val failedStacks = mutableListOf<Pair<DevStack, Throwable>>()
        missingStacks.forEach { stack ->
            val slice = 0.26f / totalStackOps
            val from = 0.72f + opIndex * slice
            try {
                applyStack(chrootExec, stack, from, from + slice, onProgress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("RuntimeInstaller", "Installing ${stack.label} failed", e)
                failedStacks += stack to e
                onProgress(
                    RuntimeInstallProgress(
                        "${stack.label} failed: ${e.message?.take(160).orEmpty()}",
                        from + slice,
                    ),
                )
            }
            opIndex++
        }

        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(chrootExec, 0.985f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(chrootExec, 0.985f, onProgress)
        }
        check(failedStacks.isEmpty()) {
            failedStacks.joinToString(separator = "\n") { (stack, error) ->
                "${stack.label}: ${error.message?.take(200).orEmpty()}"
            }
        }
        onProgress(RuntimeInstallProgress("NextCraft Forge PRoot setup complete", 1f))
        return InstalledRuntime(chrootExec, rootfs)
    }

    /**
     * Installs one coding agent on demand. Safe to call again: an already-installed
     * agent returns immediately without network access.
     */
    suspend fun ensureAgentInstalled(
        agent: AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> ensureClaudeInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> ensureDshInstalled(runtime.chrootExec, 0.05f, onProgress)
            AgentKind.ANTIGRAVITY -> ensureAgyInstalled(runtime.chrootExec, 0.05f, onProgress)
        }
        onProgress(RuntimeInstallProgress("${agent.title} is ready", 1f))
    }

    /**
     * Removes one coding agent's binaries and version markers. Projects, API keys and the Core runtime are untouched;
     * the agent's own login/config under /root is kept so a reinstall does not force a new sign-in.
     */
    fun removeAgent(agent: AgentKind) {
        installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> {
                removePath(File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/")))
                listOf(claudeMarker, bundledClaudeMarker).forEach { it.deleteMarker() }
            }
            AgentKind.DEEPSEEK_HARNESS -> {
                removePath(File(rootfs, "usr/local/bin/dsh"))
                removePath(File(rootfs, "usr/local/lib/dsh"))
                listOf(dshMarker, dshAndroidCompatibilityMarker).forEach { it.deleteMarker() }
            }
            AgentKind.ANTIGRAVITY -> {
                removePath(File(rootfs, AGY_GUEST_PATH.removePrefix("/")))
                agyMarker.deleteMarker()
            }
        }
        check(!isAgentInstalled(agent)) { "Could not fully remove ${agent.title}" }
    }

    fun isAgentInstalled(agent: AgentKind): Boolean {
        return when (agent) {
            AgentKind.CLAUDE_CODE -> {
                migrateLegacyClaudeMarker()
                // Either the official binary at /usr/local/bin/claude (a regular file) or the npm fallback, where that
                // path is an absolute symlink into the guest that resolves outside the rootfs from the host, so the
                // npm entry point is checked directly instead of following the link.
                isInstalled() && !claudeMarker.readTextOrNull().isNullOrBlank() &&
                    (isOfficialClaudeBinaryInstalled() || File(rootfs, CLAUDE_NPM_CLI_GUEST_PATH.removePrefix("/")).isFile)
            }
            AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
                File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
                isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
            AgentKind.ANTIGRAVITY -> isInstalled() &&
                File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
                !agyMarker.readTextOrNull().isNullOrBlank()
        }
    }

    /** True when /usr/local/bin/claude is the official (Bun) binary rather than the symlink to the npm install. */
    private fun isOfficialClaudeBinaryInstalled(): Boolean {
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        return claude.isFile && !java.nio.file.Files.isSymbolicLink(claude.toPath()) && claude.length() > 1_000_000L
    }

    val dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

    val claudeVersion: String
        get() {
            migrateLegacyClaudeMarker()
            return claudeMarker.readTextOrNull().orEmpty()
        }

    val agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

    val githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

    fun isGitHubCliInstalled(): Boolean = isInstalled() &&
        File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
        githubCliMarker.readTextOrNull() == GITHUB_CLI_VERSION

    /** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
    suspend fun ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
        if (isGitHubCliInstalled()) return
        writeResolver()
        downloads.mkdirs()
        val downloaded = File(downloads, "gh-$GITHUB_CLI_VERSION-linux-arm64.tar.gz")
        onProgress(RuntimeInstallProgress("Downloading GitHub CLI…", 0.05f))
        val githubDownloadLabel = "Downloading GitHub CLI $GITHUB_CLI_VERSION…"
        downloadVerified(GITHUB_CLI_RELEASE_URL, downloaded, GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    message = githubDownloadLabel,
                    fraction = 0.05f + ratio * 0.75f,
                    downloadedBytes = bytes,
                    totalBytes = total.takeIf { it > 0L },
                    event = RuntimeInstallEvent.DOWNLOAD,
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing GitHub CLI $GITHUB_CLI_VERSION…", 0.85f, indeterminate = true))
        val destination = File(rootfs, GITHUB_CLI_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        var found = false
        try {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                        val staged = File(destination.parentFile, ".gh-$GITHUB_CLI_VERSION.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Official GitHub CLI archive did not contain the expected binary" }
        } finally {
            downloaded.delete()
        }
        verifyGuest(
            chrootExec = installedRuntime().chrootExec,
            command = "$GITHUB_CLI_GUEST_PATH --version",
            failureMessage = "Failed to verify GitHub CLI",
        )
        githubCliMarker.writeMarker(GITHUB_CLI_VERSION)
        check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
        onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
    }

    /** Provisions Git credential store and GitHub CLI hosts.yml inside the guest rootfs with 0600 permissions. */
    fun configureGitHubAccess(token: String, login: String) {
        val cleanToken = token.trim().replace("\r", "").replace("\n", "")
        if (cleanToken.isEmpty()) return
        val cleanLogin = login.trim().replace("\r", "").replace("\n", "").ifEmpty { "x-access-token" }
        val rootHome = File(rootfs, "root").apply { mkdirs() }
        val gitCredentials = File(rootHome, ".git-credentials")
        writeRootfsFileAtomically(gitCredentials, "https://$cleanLogin:$cleanToken@github.com\n")
        runCatching { Os.chmod(gitCredentials.absolutePath, 384) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0600

        // Merge instead of replacing: the user's own name, email, aliases and editor settings must survive connecting
        // GitHub. Only the credential helper is forced; identity and default branch are filled in when missing.
        val gitConfig = File(rootHome, ".gitconfig")
        val existingConfig = if (gitConfig.isFile) {
            // An unreadable existing file must not be replaced by a template that drops the user's settings.
            checkNotNull(runCatching { gitConfig.readText() }.getOrNull()) { "Could not read ~/.gitconfig" }
        } else {
            ""
        }
        writeRootfsFileAtomically(
            gitConfig,
            mergeGitConfig(
                existingConfig,
                listOf(
                    GitConfigEntry("credential", "helper", "store", replace = true),
                    GitConfigEntry("user", "name", cleanLogin),
                    GitConfigEntry("user", "email", "$cleanLogin@users.noreply.github.com"),
                    GitConfigEntry("init", "defaultBranch", "main"),
                    // Workspaces belong to the app UID while git runs as root, so git needs this to trust them.
                    GitConfigEntry("safe", "directory", "*", multiValue = true),
                ),
            ),
        )
        runCatching { Os.chmod(gitConfig.absolutePath, 384) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0600

        val ghConfigDir = File(rootHome, ".config/gh").apply { mkdirs() }
        val ghHosts = File(ghConfigDir, "hosts.yml")
        writeRootfsFileAtomically(
            ghHosts,
            buildString {
                appendLine("github.com:")
                appendLine("    oauth_token: $cleanToken")
                appendLine("    user: $cleanLogin")
                appendLine("    git_protocol: https")
            },
        )
        runCatching { Os.chmod(ghHosts.absolutePath, 384) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0600
    }

    /** Removes stored Git and GitHub CLI credentials from the guest rootfs. */
    fun clearGitHubAccess() {
        val rootHome = File(rootfs, "root")
        listOf(File(rootHome, ".git-credentials"), File(rootHome, ".config/gh/hosts.yml")).forEach { file ->
            if (!file.delete() && file.exists()) Log.w("RuntimeInstaller", "Could not remove ${file.name}")
        }
    }

    /**
     * True when `claude auth login` left a usable login (access + refresh token) in the guest. Under PRoot the file
     * belongs to the app UID, so it can be read directly.
     */
    fun hasClaudeLoginCredential(): Boolean {
        val file = File(rootfs, "root/.claude/.credentials.json")
        return runCatching { file.isFile && file.readText().contains("\"refreshToken\"") }.getOrDefault(false)
    }

    /** Versions recorded after each real agent binary has been installed and verified. */
    fun installedAgentVersions(): Map<AgentKind, String> = buildMap {
        migrateLegacyClaudeMarker()
        claudeMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.CLAUDE_CODE) && it.matches(CLAUDE_VERSION_PATTERN) }
            ?.let { put(AgentKind.CLAUDE_CODE, it) }

        dshMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { isAgentInstalled(AgentKind.DEEPSEEK_HARNESS) }
            ?.let { put(AgentKind.DEEPSEEK_HARNESS, it) }

        agyMarker.readTextOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() && File(rootfs, AGY_GUEST_PATH.removePrefix("/")).canExecute() }
            ?.let { put(AgentKind.ANTIGRAVITY, it) }
    }

    /** Checks each installed agent against its own authoritative release source. */
    suspend fun checkAgentUpdates(): Map<AgentKind, AgentUpdateInfo> {
        val installed = installedAgentVersions()
        return buildMap {
            // The official release is offered unless this device already proved that exact version cannot run under
            // PRoot (updateClaude records it); the pinned npm release remains the floor.
            installed[AgentKind.CLAUDE_CODE]?.let { current ->
                val latest = runCatching {
                    JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
                }.getOrNull()?.takeIf { CLAUDE_VERSION_PATTERN.matches(it) }
                val unsupported = claudeUnsupportedMarker.readTextOrNull()?.trim()
                when {
                    latest != null && isVersionNewer(latest, current) && latest != unsupported ->
                        put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
                    isVersionNewer(CLAUDE_NPM_VERSION, current) ->
                        put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, CLAUDE_NPM_VERSION))
                    else -> Unit
                }
            }
            installed[AgentKind.DEEPSEEK_HARNESS]?.let { current ->
                DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
                    put(AgentKind.DEEPSEEK_HARNESS, AgentUpdateInfo(current, compatible))
                }
            }
            installed[AgentKind.ANTIGRAVITY]?.let { current ->
                runCatching { fetchAgyManifest().getString("version") }.getOrNull()
                    ?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                        put(AgentKind.ANTIGRAVITY, AgentUpdateInfo(current, latest))
                    }
            }
        }
    }

    suspend fun updateAgent(
        agent: AgentKind,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        when (agent) {
            AgentKind.CLAUDE_CODE -> updateClaude(runtime, expectedVersion, onProgress)
            AgentKind.DEEPSEEK_HARNESS -> updateDsh(runtime, expectedVersion, onProgress)
            AgentKind.ANTIGRAVITY -> updateAgy(runtime, expectedVersion, onProgress)
        }
        onProgress(
            RuntimeInstallProgress(
                "${agent.title} $expectedVersion is ready",
                1f,
                event = RuntimeInstallEvent.COMPLETED,
            ),
        )
    }

    /**
     * Swaps [target] for a new binary and keeps the old one until [verify] passes. A binary that downloads and
     * checksums fine but cannot run (e.g. a glibc mismatch) is rolled back instead of leaving a broken agent that
     * still counts as installed.
     */
    private suspend fun replaceBinaryWithRollback(
        target: File,
        install: () -> Unit,
        verify: suspend () -> Unit,
    ) {
        val previous = File(target.parentFile, ".${target.name}.previous")
        // An earlier update killed between the rename below and its cleanup leaves the only good binary in .previous.
        if (previous.exists() && !target.exists()) runCatching { Os.rename(previous.absolutePath, target.absolutePath) }
        previous.delete()
        if (target.exists()) Os.rename(target.absolutePath, previous.absolutePath)
        try {
            install()
            verify()
            previous.delete()
        } catch (e: Throwable) {
            target.delete()
            if (previous.exists()) runCatching { Os.rename(previous.absolutePath, target.absolutePath) }
            throw e
        }
    }

    private suspend fun updateClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (expectedVersion == CLAUDE_NPM_VERSION) {
            installClaudeViaNpm(runtime.chrootExec, 0.05f, onProgress)
            return
        }
        installOfficialClaude(runtime, expectedVersion, onProgress)
    }

    /**
     * Installs the official Claude Code binary (a Bun build) from Anthropic's release server, exactly as the chroot
     * engine does. Under PRoot the binary may still die on a syscall Android's app policy traps; the `--version`
     * check catches that, the npm install is restored, and the version is recorded so it is not offered again.
     */
    private suspend fun installOfficialClaude(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(CLAUDE_VERSION_PATTERN.matches(expectedVersion)) { "Invalid Claude Code version" }
        val latest = JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
        check(latest == expectedVersion) { "A newer Claude Code release is available. Refresh updates and try again." }
        val base = "https://downloads.claude.ai/claude-code-releases/$latest"
        val manifest = JSONObject(fetchText("$base/manifest.json"))
        val checksum = manifest.getJSONObject("platforms").getJSONObject("linux-arm64").getString("checksum")
        val downloaded = File(downloads, "claude-$latest")
        val claudeDownloadLabel = "Downloading Claude Code $latest…"
        downloadVerified("$base/linux-arm64/claude", downloaded, checksum) { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(claudeDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        claude.parentFile?.mkdirs()
        claude.parentFile?.let { if (!it.canWrite()) RootfsRepair.reclaimOwnership(it, AndroidProcess.myUid(), recursive = false) }
        val staged = File(claude.parentFile, ".claude-$latest.installing")
        val previousVersion = claudeMarker.readTextOrNull()
        try {
            replaceBinaryWithRollback(
                target = claude,
                install = {
                    try {
                        downloaded.copyTo(staged, overwrite = true)
                        Os.chmod(staged.absolutePath, 0b111101101)
                        Os.rename(staged.absolutePath, claude.absolutePath)
                    } catch (e: Throwable) {
                        staged.delete()
                        throw e
                    }
                },
                verify = { verifyGuest(runtime.chrootExec, "$CLAUDE_GUEST_PATH --version", "Claude Code $latest cannot run under PRoot on this device") },
            )
        } catch (e: Throwable) {
            // The previous binary or npm symlink is back in place; remember that this release is a no-go here.
            runCatching { claudeUnsupportedMarker.writeText(latest) }
            if (previousVersion != null) runCatching { claudeMarker.writeText(previousVersion) }
            throw IllegalStateException(
                "Claude Code $latest cannot run under PRoot on this device (${e.message}). " +
                    "The installed version was kept; the npm release $CLAUDE_NPM_VERSION remains available.",
                e,
            )
        } finally {
            downloaded.delete()
        }
        claudeUnsupportedMarker.delete()
        claudeMarker.writeMarker(latest)
        onProgress(RuntimeInstallProgress("Installed Claude Code $latest (official binary)", 0.995f))
    }

    private suspend fun updateAgy(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val manifest = fetchAgyManifest()
        val latest = manifest.getString("version")
        check(latest == expectedVersion) { "A newer Antigravity CLI release is available. Refresh updates and try again." }
        val downloaded = File(downloads, "antigravity-$latest-linux-arm64.tar.gz")
        val agyDownloadLabel = "Downloading Antigravity CLI $latest…"
        downloadVerified(manifest.getString("url"), downloaded, manifest.getString("sha512"), algorithm = "SHA-512") { bytes, total ->
            val ratio = if (total > 0L) bytes.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(agyDownloadLabel, ratio * 0.9f, bytes, total.takeIf { it > 0L }, event = RuntimeInstallEvent.DOWNLOAD))
        }
        val destination = File(rootfs, AGY_GUEST_PATH.removePrefix("/"))
        destination.parentFile?.mkdirs()
        destination.parentFile?.let { if (!it.canWrite()) RootfsRepair.reclaimOwnership(it, AndroidProcess.myUid(), recursive = false) }
        var found = false
        try {
          replaceBinaryWithRollback(
            target = destination,
            verify = { verifyGuest(runtime.chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify updated Antigravity CLI") },
            install = {
            @Suppress("DEPRECATION")
            TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
                var entry = archive.nextTarEntry
                while (entry != null) {
                    if (entry.isFile && entry.name.removePrefix("./") == "antigravity") {
                        val staged = File(destination.parentFile, ".agy-$latest.installing")
                        try {
                            FileOutputStream(staged).use { archive.copyTo(it) }
                            Os.chmod(staged.absolutePath, 0b111101101)
                            Os.rename(staged.absolutePath, destination.absolutePath)
                        } catch (e: Throwable) {
                            staged.delete()
                            throw e
                        }
                        found = true
                        break
                    }
                    entry = archive.nextTarEntry
                }
            }
            check(found) { "Antigravity update archive is incomplete" }
            },
          )
        } finally {
            downloaded.delete()
        }
        agyMarker.writeMarker(latest)
    }

    private suspend fun updateDsh(
        runtime: InstalledRuntime,
        expectedVersion: String,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        check(isSupportedDshVersion(expectedVersion)) {
            "DeepSeek $expectedVersion is not compatible with this NextCraft Forge build"
        }
        val quotedVersion = expectedVersion.replace(Regex("[^0-9A-Za-z.+-]"), "")
        check(quotedVersion == expectedVersion) { "Invalid DeepSeek version" }
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; next=/usr/local/lib/dsh.updating; " +
                "rm -rf \"${'$'}next\"; mkdir -p \"${'$'}next\"; " +
                // Two separate, empty config files: when the user and global npm config both resolve to /dev/null,
                // npm stops with "double-loading config /dev/null as global, previously loaded as user". Guest-writable
                // npm config (~/.npmrc, /etc/npmrc) must still not redirect the registry for a package that becomes the
                // agent runtime, hence the explicit --registry.
                "export npm_config_userconfig=\"${'$'}next/.npmrc-user\" npm_config_globalconfig=\"${'$'}next/.npmrc-global\"; " +
                "cd \"${'$'}next\"; : > .npmrc-user; : > .npmrc-global; npm init -y >/dev/null; " +
                "npm install --omit=dev --no-audit --no-fund --registry=https://registry.npmjs.org @deepseek-ai/dsh@$quotedVersion; " +
                "rm -f .npmrc-user .npmrc-global; " +
                // npm runs as root; the app patches the tree next (applyDshAndroidCompatibility) as its own UID.
                "chown -R -h ${AndroidProcess.myUid()}:${AndroidProcess.myUid()} \"${'$'}next\"",
            displayCommand = "npm install @deepseek-ai/dsh@$quotedVersion",
            fraction = 0.55f,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Failed to update DeepSeek",
        )
        val staged = File(rootfs, "usr/local/lib/dsh.updating")
        applyDshAndroidCompatibility(staged, expectedVersion)
        runGuestCommand(
            chrootExec = runtime.chrootExec,
            command = "set -e; old=/usr/local/lib/dsh.previous; rm -rf \"${'$'}old\"; " +
                "mv /usr/local/lib/dsh \"${'$'}old\"; " +
                "if mv /usr/local/lib/dsh.updating /usr/local/lib/dsh; then :; " +
                "else mv \"${'$'}old\" /usr/local/lib/dsh; exit 1; fi",
            displayCommand = "Activate DeepSeek $quotedVersion",
            fraction = 0.9f,
            timeoutMs = 60_000L,
            onProgress = onProgress,
            failureMessage = "Failed to activate DeepSeek update",
        )
        val verified = runCatching {
            verifyGuest(runtime.chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify updated DeepSeek")
        }
        if (verified.isFailure) {
            runCatching {
                runGuestCommand(
                    chrootExec = runtime.chrootExec,
                    command = "set -e; rm -rf /usr/local/lib/dsh; mv /usr/local/lib/dsh.previous /usr/local/lib/dsh",
                    displayCommand = "Restore previous DeepSeek",
                    fraction = 0.95f,
                    timeoutMs = 60_000L,
                    onProgress = onProgress,
                    failureMessage = "Failed to restore previous DeepSeek",
                )
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
            verified.getOrThrow()
        }
        val previous = File(rootfs, "usr/local/lib/dsh.previous")
        if (!previous.deleteRecursively() && !RootfsRepair.safeDeleteRecursively(previous)) {
            Log.w("RuntimeInstaller", "Could not remove the previous DeepSeek copy")
        }
        dshMarker.writeMarker(expectedVersion)
        dshAndroidCompatibilityMarker.writeMarker(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun fetchAgyManifest(): JSONObject = JSONObject(
        fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
    )

    private fun isVersionNewer(candidate: String, current: String): Boolean = isAgentVersionNewer(candidate, current)

    private fun migrateLegacyClaudeMarker() {
        if (claudeMarker.isFile) return
        val claude = File(rootfs, CLAUDE_GUEST_PATH.removePrefix("/"))
        if (!claude.isFile) return
        val legacyVersion = sequenceOf(
            coreReadyMarker.readTextOrNull(),
            bundledClaudeMarker.readTextOrNull(),
        ).mapNotNull { it?.trim() }.firstOrNull { it.matches(CLAUDE_VERSION_PATTERN) } ?: return
        claudeMarker.writeMarker(legacyVersion)
    }

    private fun isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
        CORE_TOOLS_VERSION,
        LEGACY_CORE_TOOLS_VERSION,
        PREVIOUS_CORE_TOOLS_VERSION,
        NEXTCRAFT_CORE_TOOLS_VERSION,
        NEXTCRAFT_CORE_TOOLS_VERSION_2,
    )

    /** The Python 3.12 overlay needs glibc 2.39; an Ubuntu 20.04 runtime has to be reinstalled on 24.04 first. */
    private fun pythonBundle(): RuntimeBundle {
        check(rootfsMarker.readTextOrNull() != LEGACY_ROOTFS_VERSION) { LEGACY_ROOTFS_PYTHON_MESSAGE }
        return PYTHON_BUNDLE
    }

    private fun isSupportedRootfsVersion(version: String?): Boolean = version in setOf(
        ROOTFS_VERSION,
        LEGACY_ROOTFS_VERSION,
    )

    private suspend fun ensureClaudeInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        migrateLegacyClaudeMarker()
        if (isAgentInstalled(AgentKind.CLAUDE_CODE)) return
        // The official Claude bundle is a Bun binary that Android's app sandbox kills (SIGSYS); PRoot always uses npm.
        installClaudeViaNpm(chrootExec, fraction, onProgress)
    }

    /**
     * Non-root devices (PRoot): Android's seccomp policy for apps kills the Bun-built `claude` binary with SIGSYS
     * (Bun registers `rseq` itself, which the policy forbids; glibc programs survive via GLIBC_TUNABLES). The last
     * npm release that still ships cli.js for plain Node is installed instead, at the same guest path and marker.
     */
    private suspend fun installClaudeViaNpm(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val version = CLAUDE_NPM_VERSION
        check(CLAUDE_VERSION_PATTERN.matches(version)) { "Invalid Claude Code version" }
        runGuestCommand(
            chrootExec = chrootExec,
            command = "set -e; dir=$CLAUDE_NPM_GUEST_DIR; rm -rf \"${'$'}dir\"; mkdir -p \"${'$'}dir\"; cd \"${'$'}dir\"; " +
                "npm init -y >/dev/null; npm install --omit=dev --no-audit --no-fund @anthropic-ai/claude-code@$version; " +
                "ln -sfn $CLAUDE_NPM_GUEST_DIR/node_modules/.bin/claude $CLAUDE_GUEST_PATH",
            displayCommand = "npm install @anthropic-ai/claude-code@$version",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Failed to install Claude Code from npm",
        )
        verifyGuest(chrootExec, "$CLAUDE_GUEST_PATH --version", "Failed to verify Claude Code")
        claudeMarker.writeText(version)
        onProgress(RuntimeInstallProgress("Installed Claude Code $version (npm, PRoot)", 0.995f))
        // A newer official release may exist; upgrading is best-effort and never fails the npm install itself.
        upgradeAfterBundledInstall(AgentKind.CLAUDE_CODE, onProgress)
    }

    private suspend fun ensureAgyInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.ANTIGRAVITY)) return
        installRuntimeOverlay(
            bundle = AGY_BUNDLE,
            message = "Installing Antigravity CLI $AGY_VERSION…",
            from = fraction,
            to = 0.995f,
            onProgress = onProgress,
            forceEmbedded = isAssetAvailable("runtime/${AGY_BUNDLE.fileName}"),
        )
        verifyGuest(chrootExec, "$AGY_GUEST_PATH --version", "Failed to verify Antigravity CLI")
        agyMarker.writeMarker(AGY_VERSION)
        require(isAgentInstalled(AgentKind.ANTIGRAVITY)) {
            "Antigravity CLI installation is incomplete"
        }
        upgradeAfterBundledInstall(AgentKind.ANTIGRAVITY, onProgress)
    }

    /**
     * The APK bundles a fixed agent version, which can be older than what the user already ran: after a rootfs
     * rebuild or a reinstall of the app, installing the bundle would silently downgrade the agent. Move it straight
     * to the newest release instead. Best effort: offline or a failed download keeps the bundled version.
     */
    private suspend fun upgradeAfterBundledInstall(
        agent: AgentKind,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        runCatching {
            val update = checkAgentUpdates()[agent] ?: return
            val runtime = installedRuntime()
            when (agent) {
                AgentKind.CLAUDE_CODE -> updateClaude(runtime, update.latestVersion, onProgress)
                AgentKind.ANTIGRAVITY -> updateAgy(runtime, update.latestVersion, onProgress)
                AgentKind.DEEPSEEK_HARNESS -> Unit
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w("RuntimeInstaller", "Kept the bundled ${agent.title}; updating to the latest release failed", error)
        }
    }

    private suspend fun ensureDshInstalled(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
            ensureDshAndroidCompatibility()
            return
        }
        val dshDir = File(rootfs, "usr/local/lib/dsh")
        val dshBackup = File(rootfs, "usr/local/lib/dsh.bak")
        val dshMarkerBackup = File(dshMarker.parentFile, "${dshMarker.name}.bak")
        val compatMarkerBackup = File(dshAndroidCompatibilityMarker.parentFile, "${dshAndroidCompatibilityMarker.name}.bak")
        File(rootfs, "usr/local/lib/dsh.updating").deleteRecursively()
        File(rootfs, "usr/local/lib/dsh.previous").deleteRecursively()
        dshBackup.deleteRecursively()
        dshMarkerBackup.delete()
        compatMarkerBackup.delete()
        if (dshDir.exists()) dshDir.renameTo(dshBackup)
        if (dshMarker.exists()) dshMarker.renameTo(dshMarkerBackup)
        if (dshAndroidCompatibilityMarker.exists()) dshAndroidCompatibilityMarker.renameTo(compatMarkerBackup)
        try {
            installRuntimeOverlay(
                bundle = DSH_BUNDLE,
                message = "Installing DeepSeek $DSH_VERSION…",
                from = fraction,
                to = 0.995f,
                onProgress = onProgress,
            )
            ensureDshAndroidCompatibility()
            verifyGuest(chrootExec, "/usr/local/bin/dsh --profile headless --help", "Failed to verify DeepSeek")
            require(isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) {
                "The DeepSeek runtime bundle is incomplete"
            }
            dshBackup.deleteRecursively()
            dshMarkerBackup.delete()
            compatMarkerBackup.delete()
        } catch (e: Throwable) {
            dshDir.deleteRecursively()
            if (dshBackup.exists()) dshBackup.renameTo(dshDir)
            if (dshMarkerBackup.exists()) dshMarkerBackup.renameTo(dshMarker)
            if (compatMarkerBackup.exists()) compatMarkerBackup.renameTo(dshAndroidCompatibilityMarker)
            throw e
        }
    }

    /**
     * Ensures DSH uses COPYFILE_EXCL (`copyFile(tmp, finalPath, 1)`) for atomic no-clobber publication.
     */
    fun ensureDshAndroidCompatibility() {
        if (!isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) return
        val dshRoot = File(rootfs, "usr/local/lib/dsh")
        try {
            applyDshAndroidCompatibility(dshRoot, dshMarker.readTextOrNull().orEmpty())
        } catch (e: java.io.IOException) {
            throw e
        }
        dshAndroidCompatibilityMarker.writeMarker(DSH_ANDROID_COMPATIBILITY_VERSION)
    }

    private fun applyDshAndroidCompatibility(dshRoot: File, version: String) {
        check(isSupportedDshVersion(version)) {
            "DeepSeek $version is not compatible with this NextCraft Forge build"
        }
        val persistence = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-session-persistence-jsonl/lib/index.js",
        )
        val localFs = File(
            dshRoot,
            "node_modules/@deepseek-ai/dsh-fs-local/lib/index.js",
        )
        patchDshHardLinkPublication(persistence, callBefore = "await link(tmp, finalPath);", callAfter = "await copyFile(tmp, finalPath, 1);")
        patchDshHardLinkPublication(
            localFs,
            callBefore = "await linkFile(tempPath, absolutePath);",
            callAfter = "await copyFile(tempPath, absolutePath, 1);",
        )
    }

    private fun patchDshHardLinkPublication(file: File, callBefore: String, callAfter: String) {
        check(file.isFile) { "DeepSeek compatibility file is missing: ${file.name}" }
        val original = file.readText()
        val source = patchDshSource(original, callBefore, callAfter)
            ?: error("Installed DeepSeek source is not compatible with this NextCraft build")
        if (source == original) return
        val staging = File(file.parentFile, "${file.name}.tmp")
        staging.writeText(source)
        if (!staging.renameTo(file)) {
            staging.copyTo(file, overwrite = true)
            staging.delete()
        }
    }

    /**
     * Installs one optional development stack inside the Ubuntu PRoot runtime.
     */
    suspend fun ensureStackInstalled(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val runtime = installedRuntime()
        if (isStackInstalled(stack) && stack != DevStack.ANDROID) return
        applyStack(runtime.chrootExec, stack, 0.05f, 0.95f, onProgress)
        onProgress(RuntimeInstallProgress("${stack.label} stack is ready", 1f))
    }

    /**
     * Removes an optional development stack without touching projects or the core
     * Node.js/Git runtime.
     */
    suspend fun removeStack(
        stack: DevStack,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(stack != DevStack.WEB) { "Web (Node.js / npm) is part of the Core runtime and cannot be removed." }
        val runtime = installedRuntime()
        if (!isStackInstalled(stack)) return

        onProgress(RuntimeInstallProgress("Removing ${stack.label}…", 0.1f, indeterminate = true))
        when (stack) {
            DevStack.WEB -> Unit
            DevStack.PYTHON -> removePythonStack()
            DevStack.ANDROID -> removeAndroidStack()
            DevStack.CPP -> aptRemove(
                runtime.chrootExec,
                listOf("build-essential", "gcc", "g++", "make", "cmake", "gdb"),
                0.45f,
                onProgress,
            )
            DevStack.PHP -> {
                aptRemove(
                    runtime.chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip"),
                    0.45f,
                    onProgress,
                )
                removePath(File(rootfs, "usr/local/bin/composer"))
                removePath(File(rootfs, "root/.cache/composer"))
                removePath(File(rootfs, "root/.composer"))
            }
        }

        writeDevStackState(readDevStackState().apply { put(stack.name, false) })
        onProgress(RuntimeInstallProgress("Removed ${stack.label}", 1f))
    }

    private fun removePythonStack() {
        val dpkgOwned = dpkgOwnedPaths()
        // The overlay is unmanaged by dpkg, so it is removed by path. Covers the current Ubuntu 24.04 bundle
        // (Python 3.12) and the legacy 20.04 bundle (Python 3.8). Shared libraries the overlay also ships
        // (libsqlite3, libreadline, libhistory) and tzdata files are left in place: other tools may use them.
        listOf(
            ".pocket-python-tools-version",
            ".nextcraft-python-tools-version",
            "usr/bin/python3",
            "usr/bin/python3.12",
            "usr/bin/python3.8",
            "usr/bin/pip",
            "usr/bin/pip3",
            "usr/bin/pip3.12",
            "usr/bin/pdb3",
            "usr/bin/pdb3.12",
            "usr/bin/pydoc3",
            "usr/bin/pydoc3.12",
            "usr/bin/pygettext3",
            "usr/bin/pygettext3.12",
            "usr/bin/py3clean",
            "usr/bin/py3compile",
            "usr/bin/py3versions",
            "usr/lib/aarch64-linux-gnu/libpython3.12.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.12.so.1.0",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1",
            "usr/lib/aarch64-linux-gnu/libpython3.8.so.1.0",
            "usr/lib/python3",
            "usr/lib/python3.12",
            "usr/lib/python3.8",
            "usr/lib/valgrind/python3.supp",
            "usr/local/lib/python3.12",
            "usr/local/lib/python3.8",
            "etc/python3",
            "etc/python3.12",
            "usr/share/python3",
            "usr/share/python-wheels",
            "root/.cache/pip",
        )
            // gdb (C/C++ stack) pulls in dpkg's libpython3.12 at these same paths; deleting them breaks gdb and
            // leaves dpkg believing the packages are installed. Only remove what dpkg does not own.
            .filterNot { "/$it" in dpkgOwned }
            .forEach { removePath(File(rootfs, it)) }
    }

    private fun dpkgOwnedPaths(): Set<String> = runCatching {
        File(rootfs, "var/lib/dpkg/info").listFiles { f -> f.name.endsWith(".list") }.orEmpty()
            .flatMapTo(HashSet()) { it.readLines() }
    }.getOrDefault(emptySet())

    /** Removes the current overlay layout and every previous one, so an upgrade does not leave an old JDK behind. */
    private fun removeAndroidStack() {
        AndroidToolchain.REMOVABLE_HOST_PATHS.forEach { removePath(File(rootfs, it)) }
        removeAndroidGradleProperty()
    }

    private fun removeAndroidGradleProperty() {
        val properties = File(rootfs, "root/.gradle/gradle.properties")
        if (!properties.isFile) return
        val remaining = properties.readLines().filterNot(::isManagedAndroidGradleProperty)
        if (remaining.isEmpty()) {
            properties.delete()
        } else {
            properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
        }
    }

    private fun removePath(file: File) {
        val isLink = java.nio.file.Files.isSymbolicLink(file.toPath())
        if (!file.exists() && !isLink) return
        val removed = if (file.isDirectory && !isLink) file.deleteRecursivelyNoFollow() else file.delete()
        if (!removed) {
            check(RootfsRepair.safeDeleteRecursively(file)) { "Could not remove ${file.name}" }
        }
    }

    private suspend fun applyStack(
        chrootExec: File,
        stack: DevStack,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        when (stack) {
            DevStack.WEB -> {
                onProgress(RuntimeInstallProgress("Checking Node.js and npm…", from))
                verifyGuest(chrootExec, "node --version && npm --version", "Failed to verify Node.js")
            }
            DevStack.PYTHON -> {
                installRuntimeOverlay(pythonBundle(), "Installing Python toolchain…", from, to, onProgress)
                // A failed check must fail the stack: returning normally reported "Python stack is ready" and left it
                // out of the failures that ensureInstalled reports.
                runCatching {
                    verifyGuest(chrootExec, "python3 --version && pip3 --version", "Failed to verify Python")
                }.onFailure { error ->
                    onProgress(
                        RuntimeInstallProgress(
                            "Python verification failed: ${error.message?.take(120).orEmpty()}",
                            to,
                        ),
                    )
                    throw error
                }
            }
            DevStack.ANDROID -> {
                installAndroidToolchain(chrootExec, from, to, onProgress)
            }
            DevStack.CPP -> {
                aptInstall(
                    chrootExec,
                    listOf("build-essential", "cmake", "gdb"),
                    "Installing C/C++ build toolchain…",
                    from,
                    onProgress,
                )
                verifyGuest(
                    chrootExec,
                    "gcc --version && g++ --version && make --version && cmake --version",
                    "Failed to verify C/C++ toolchain",
                )
            }
            DevStack.PHP -> {
                aptInstall(
                    chrootExec,
                    listOf("php-cli", "php-mbstring", "php-xml", "php-curl", "php-zip", "unzip"),
                    "Installing PHP and extensions…",
                    from,
                    onProgress,
                )
                installComposer(chrootExec, from, onProgress)
                verifyGuest(chrootExec, "php --version && composer --version", "Failed to verify PHP and Composer")
            }
        }
        writeDevStackState(readDevStackState().apply { put(stack.name, true) })
        onProgress(RuntimeInstallProgress("Installed ${stack.label}", to))
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun installComposer(
        chrootExec: File,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val composer = File(rootfs, "usr/local/bin/composer")
        if (composer.isFile) return
        onProgress(RuntimeInstallProgress("Downloading Composer…", fraction))
        downloads.mkdirs()
        val staged = File(downloads, "composer.phar")
        val checksum = COMPOSER_SHA256
        downloadVerified(
            "https://getcomposer.org/download/$COMPOSER_VERSION/composer.phar",
            staged,
            checksum,
        ) { _, _ -> }
        composer.parentFile?.mkdirs()
        val staging = File(composer.parentFile, ".composer.installing")
        try {
            staged.inputStream().use { input -> FileOutputStream(staging).use { input.copyTo(it) } }
            Os.chmod(staging.absolutePath, 0b111101101)
            Os.rename(staging.absolutePath, composer.absolutePath)
        } catch (e: Throwable) {
            staging.delete()
            throw e
        } finally {
            staged.delete()
        }
        onProgress(RuntimeInstallProgress("Installing Composer…", fraction))
    }

    private suspend fun installAndroidToolchain(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        val marker = File(rootfs, "root/.nextcraft-android-tools-version")
        if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION || !hasAndroidToolchainFiles(rootfs)) {
            // An older layout (different JDK/Gradle directories) would otherwise stay behind next to the new one.
            if (marker.readTextOrNull() != ANDROID_TOOLS_VERSION && marker.isFile) removeAndroidStack()
            installRuntimeOverlay(
                ANDROID_BUNDLE,
                "Installing Android SDK ${AndroidToolchain.COMPILE_SDK}, Build Tools ${AndroidToolchain.BUILD_TOOLS_VERSION}, " +
                    "Gradle ${AndroidToolchain.GRADLE_VERSION}, and Maven cache…",
                from,
                to,
                onProgress,
            )
            makeAndroidToolsExecutable(rootfs)
            marker.writeMarker(ANDROID_TOOLS_VERSION)
        }
        writeAndroidGradleConfiguration(rootfs)

        val jdk = AndroidToolchain.JDK_MAJOR
        verifyGuest(
            chrootExec,
            "java -version 2>&1 | grep -E '\"$jdk\\.|version $jdk' && " +
                "gradle --version && aapt2 version && test -f ${AndroidToolchain.ANDROID_JAR_GUEST_PATH}",
            "Failed to verify Android SDK, Gradle, and Java $jdk",
        )
    }

    private fun hasAndroidToolchainFiles(runtimeRootfs: File): Boolean =
        File(runtimeRootfs, AndroidToolchain.ANDROID_JAR_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.AAPT2_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH).isFile &&
            File(runtimeRootfs, AndroidToolchain.MAVEN_HOST_DIR).let { it.isDirectory && !it.list().isNullOrEmpty() }

    /** Unpack progress: bytes are the compressed archive consumed, so the UI can show MB/s like a download. */
    private fun unpackProgress(message: String, from: Float, to: Float, done: Long, total: Long) =
        RuntimeInstallProgress(
            message = message,
            fraction = from + (to - from) * (if (total > 0L) done.toFloat() / total else 0f),
            downloadedBytes = done,
            totalBytes = total.takeIf { it > 0L },
            event = RuntimeInstallEvent.DOWNLOAD,
            unpacking = true,
        )

    private suspend fun installRuntimeOverlay(
        bundle: RuntimeBundle,
        message: String,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        forceEmbedded: Boolean = false,
    ) {
        val preferEmbedded = forceEmbedded || isAssetAvailable("runtime/${bundle.fileName}")
        val archive = obtainRuntimeBundle(bundle, preferEmbedded = preferEmbedded, from = from, to = to * 0.8f + from * 0.2f, onProgress = onProgress)
        onProgress(RuntimeInstallProgress(message, to * 0.8f + from * 0.2f, indeterminate = true))
        try {
            extractZstdTar(archive, rootfs) { done, total ->
                onProgress(unpackProgress(message, to * 0.8f + from * 0.2f, to, done, total))
            }
            // The rootfs may be mounted by a running session: never walk into /proc, /dev or a project bind.
            stripMacosMetadataArtifacts(rootfs, skipTopLevel = BIND_MOUNTED_TOP_LEVEL)
        } finally {
            if (archive.parentFile == downloads) archive.delete()
        }
        onProgress(RuntimeInstallProgress("Installed ${bundle.label} runtime bundle", to))
    }

    private fun stripMacosMetadataArtifacts(root: File, skipTopLevel: Set<String> = emptySet()) {
        if (!root.isDirectory) return
        val queue = ArrayDeque<File>()
        queue.add(root)
        var removed = 0
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (current == root && child.name in skipTopLevel) continue
                if (child.name.startsWith("._")) {
                    if (child.isDirectory) child.deleteRecursively() else child.delete()
                    removed++
                } else if (child.isDirectory && !java.nio.file.Files.isSymbolicLink(child.toPath())) {
                    queue.add(child)
                }
            }
        }
        if (removed > 0) {
            Log.i("RuntimeInstaller", "Stripped $removed macOS metadata artifacts")
        }
    }

    private fun isAssetAvailable(assetPath: String): Boolean =
        runCatching {
            context.assets.open(assetPath).close()
            true
        }.getOrDefault(false)

    /** Fails early with an actionable message instead of an opaque IOException halfway through extraction. */
    private fun requireFreeSpaceForBundle(bundle: RuntimeBundle, alreadyDownloaded: Boolean) {
        val free = context.filesDir.usableSpace
        if (free <= 0L) return // unknown (some filesystems report 0); let the real write decide
        val factor = if (alreadyDownloaded) BUNDLE_EXTRACT_SPACE_FACTOR else BUNDLE_DOWNLOAD_SPACE_FACTOR
        val needed = bundle.compressedBytes * factor
        check(free >= needed) {
            "Not enough free storage for the ${bundle.label} bundle: about ${needed / MEGABYTE} MB needed, " +
                "${free / MEGABYTE} MB available. Free some space and retry."
        }
    }

    private suspend fun obtainRuntimeBundle(
        bundle: RuntimeBundle,
        preferEmbedded: Boolean,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ): File {
        downloads.mkdirs()
        val destination = File(downloads, bundle.fileName)
        requireFreeSpaceForBundle(bundle, alreadyDownloaded = destination.isFile)
        if (preferEmbedded && isAssetAvailable("runtime/${bundle.fileName}")) {
            val loadingLabel = "Loading ${bundle.label} bundle…"
            onProgress(RuntimeInstallProgress(loadingLabel, from, 0, bundle.compressedBytes))
            val temporary = File(downloads, "${bundle.fileName}.part")
            context.assets.open("runtime/${bundle.fileName}").use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var copied = 0L
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        val ratio = (copied.toFloat() / bundle.compressedBytes).coerceIn(0f, 1f)
                        onProgress(RuntimeInstallProgress(loadingLabel, from + ratio * (to - from), copied, bundle.compressedBytes))
                    }
                }
            }
            require(digest(temporary, "SHA-256").equals(bundle.sha256, ignoreCase = true)) {
                "${bundle.label} bundle checksum mismatch"
            }
            if (destination.exists()) destination.delete()
            check(temporary.renameTo(destination)) { "Could not stage the ${bundle.label} bundle" }
            return destination
        }

        val url = "${bundle.baseUrl}/${bundle.fileName}"
        val downloadingLabel = "Downloading ${bundle.label} bundle…"
        downloadVerified(url, destination, bundle.sha256) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(RuntimeInstallProgress(downloadingLabel, from + ratio * (to - from), downloaded, total.takeIf { it > 0 }))
        }
        return destination
    }

    private fun makeAndroidToolsExecutable(runtimeRootfs: File) {
        val buildTools = File(runtimeRootfs, AndroidToolchain.BUILD_TOOLS_HOST_DIR)
        listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
            .map { File(buildTools, it) }
            .plus(File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH))
            .filter(File::isFile)
            .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    }

    private fun writeAndroidGradleInitScript(runtimeRootfs: File) {
        val script = File(runtimeRootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/"))
        script.parentFile?.mkdirs()
        script.writeText(ANDROID_GRADLE_INIT_SCRIPT)
        File(runtimeRootfs, RuntimeMarkers.LEGACY_GRADLE_INIT_SCRIPT).delete()
    }

    private fun deviceTotalMemoryMb(): Long {
        val info = android.app.ActivityManager.MemoryInfo()
        context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
        return if (info.totalMem > 0) info.totalMem / (1024 * 1024) else 4096L
    }

    @Synchronized
    private fun writeAndroidGradleConfiguration(runtimeRootfs: File) {
        val aapt2 = File(runtimeRootfs, ANDROID_AAPT2_HOST_PATH)
        if (!aapt2.isFile) return

        writeAndroidGradleInitScript(runtimeRootfs)
        val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
        val properties = File(gradleDir, "gradle.properties")
        val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
        val hwProfile = DeviceHardwareOptimizer.inspect(context)
        val managedLines = listOf(
            "$ANDROID_AAPT2_PROPERTY=$ANDROID_AAPT2_GUEST_PATH",
            "$GRADLE_JVMARGS_PROPERTY=${gradleJvmArgs(hwProfile)}",
            "$KOTLIN_EXECUTION_PROPERTY=in-process",
        ) + gradleSpeedProperties(hwProfile).map { (key, value) -> "$key=$value" }
        val updatedLines = existingLines.filterNot(::isManagedAndroidGradleProperty) + managedLines
        if (existingLines == updatedLines) return

        val temporary = File(gradleDir, "gradle.properties.nextcraft.tmp")
        try {
            temporary.writeText(updatedLines.joinToString("\n").trimEnd() + "\n")
            Os.rename(temporary.absolutePath, properties.absolutePath)
        } catch (e: Throwable) {
            temporary.delete()
            throw e
        }
    }

    private fun isManagedAndroidGradleProperty(line: String): Boolean =
        MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
            Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
        }

    fun migrateLegacyToolMarkers() {
        if (!File(rootfs, "usr/bin/bash").isFile) return
        if (languageToolsMarker.readTextOrNull() != LANGUAGE_TOOLS_VERSION) return
        if (coreToolsMarker.readTextOrNull() != CORE_TOOLS_VERSION) coreToolsMarker.writeMarker(CORE_TOOLS_VERSION)
        val state = readDevStackState()
        DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
        writeDevStackState(state)
    }

    fun installedStacks(): Set<DevStack> = readDevStackState()
        .filterValues { it }
        .keys
        .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
        .filter(::isStackInstalled)
        .toSet()

    private fun guestExecutableOrSymlinkExists(relativePath: String): Boolean {
        val file = File(rootfs, relativePath.removePrefix("/"))
        // lstat first: following a toolchain symlink (gcc, php) from the app is denied by SELinux and logs an AVC.
        return java.nio.file.Files.isSymbolicLink(file.toPath()) || file.exists()
    }

    fun isStackInstalled(stack: DevStack): Boolean {
        if (readDevStackState()[stack.name] != true) return false
        return when (stack) {
            DevStack.WEB -> true
            DevStack.PYTHON -> guestExecutableOrSymlinkExists("usr/bin/python3") && guestExecutableOrSymlinkExists("usr/bin/pip3")
            DevStack.CPP -> guestExecutableOrSymlinkExists("usr/bin/gcc") && guestExecutableOrSymlinkExists("usr/bin/g++")
            DevStack.PHP -> guestExecutableOrSymlinkExists("usr/bin/php") && File(rootfs, "usr/local/bin/composer").isFile
            DevStack.ANDROID ->
                RuntimeMarkers.read(File(rootfs, "root/.nextcraft-android-tools-version"))?.trim() == ANDROID_TOOLS_VERSION &&
                    hasAndroidToolchainFiles(rootfs) &&
                    File(rootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/")).isFile
        }
    }

    private fun readDevStackState(): MutableMap<String, Boolean> {
        if (!devStacksFile.isFile) return mutableMapOf()
        return runCatching {
            val obj = JSONObject(devStacksFile.readText())
            mutableMapOf<String, Boolean>().apply {
                DevStack.entries.forEach { stack ->
                    if (obj.has(stack.name)) put(stack.name, obj.optBoolean(stack.name))
                }
            }
        }.getOrDefault(mutableMapOf())
    }

    private fun writeDevStackState(state: Map<String, Boolean>) {
        devStacksFile.parentFile?.mkdirs()
        val obj = JSONObject()
        state.forEach { (name, value) -> obj.put(name, value) }
        // A truncated file reads back as "no stacks installed" and makes the next setup reinstall everything.
        writeRootfsFileAtomically(devStacksFile, obj.toString())
        RuntimeMarkers.legacyOf(devStacksFile)?.let { runCatching { it.writeText(obj.toString()) } }
    }

    @Suppress("UNUSED_PARAMETER")
    private suspend fun installNodeIfNeeded(
        chrootExec: File,
        from: Float,
        to: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        if (File(rootfs, "usr/local/bin/node").exists()) return
        val nodeDownloadLabel = "Downloading Node.js $NODE_VERSION…"
        onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
        downloads.mkdirs()
        val nodeFileName = "node-$NODE_VERSION-linux-arm64.tar.gz"
        val nodeBaseUrl = "https://nodejs.org/dist/$NODE_VERSION"
        val checksum = NODE_ARM64_SHA256
        val nodeArchive = File(downloads, nodeFileName)
        downloadVerified("$nodeBaseUrl/$nodeFileName", nodeArchive, checksum) { downloaded, total ->
            val ratio = if (total > 0) downloaded.toFloat() / total else 0f
            onProgress(
                RuntimeInstallProgress(
                    nodeDownloadLabel,
                    from + ratio * (to - from),
                    downloaded,
                    total.takeIf { it > 0 },
                ),
            )
        }
        onProgress(RuntimeInstallProgress("Installing Node.js $NODE_VERSION…", to))
        val nodeStaging = File(runtimeDir, "node.installing")
        try {
            nodeStaging.deleteRecursively()
            nodeStaging.mkdirs()
            extractNodeArchive(nodeArchive, nodeStaging)
            val nodeHome = File(rootfs, "usr/local/lib/nodejs")
            nodeHome.deleteRecursively()
            nodeHome.parentFile?.mkdirs()
            check(nodeStaging.renameTo(nodeHome)) { "Could not activate Node.js" }
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            listOf("node", "npm", "npx", "corepack").forEach { command ->
                val link = File(localBin, command)
                if (link.exists() || java.nio.file.Files.isSymbolicLink(link.toPath())) link.delete()
                Os.symlink("../lib/nodejs/bin/$command", link.absolutePath)
            }
        } catch (e: Throwable) {
            nodeStaging.deleteRecursively()
            throw e
        } finally {
            nodeArchive.delete()
        }
    }

    private suspend fun aptInstall(
        chrootExec: File,
        packages: List<String>,
        message: String,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(RuntimeInstallProgress(message, fraction))
        aptInstallInternal(chrootExec, packages, fraction, onProgress)
    }

    private suspend fun runSystemMaintenance(
        chrootExec: File,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = "Updating Ubuntu packages inside the PRoot runtime…",
                fraction = 0.69f,
                indeterminate = true,
            ),
        )
        writeResolver()
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 upgrade -y"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get update && apt-get upgrade -y",
            fraction = 0.69f,
            timeoutMs = 35 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Ubuntu system maintenance failed inside the PRoot runtime",
        )
    }

    private suspend fun aptInstallInternal(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        writeResolver()
        val packageNames = packages.joinToString(" ")
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "dpkg --configure -a && " +
            "apt-get -o DPkg::Lock::Timeout=120 -f install -y && " +
            "apt-get -o DPkg::Lock::Timeout=120 update && " +
            "apt-get -o DPkg::Lock::Timeout=120 install -y --no-install-recommends $packageNames && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "dpkg --configure -a && apt-get -f install -y && apt-get install -y $packageNames",
            fraction = fraction,
            timeoutMs = 30 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not install packages: $packageNames",
        )
    }

    private suspend fun aptRemove(
        chrootExec: File,
        packages: List<String>,
        fraction: Float,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
    ) {
        require(packages.isNotEmpty()) { "No packages selected" }
        val packageNames = packages.joinToString(" ")
        // The stack packages (php-cli, build-essential, gcc, ...) are metapackages: purging them alone leaves the real
        // binaries (php8.3-cli, gcc-13, ...) installed, so autoremove the dependencies that were pulled in with them.
        val command = "export DEBIAN_FRONTEND=noninteractive; " +
            "apt-get -o DPkg::Lock::Timeout=120 purge -y $packageNames && " +
            "apt-get -o DPkg::Lock::Timeout=120 autoremove --purge -y && " +
            "apt-get clean && rm -rf /var/lib/apt/lists/*"
        runGuestCommand(
            chrootExec = chrootExec,
            command = command,
            displayCommand = "apt-get purge -y $packageNames && apt-get autoremove --purge -y",
            fraction = fraction,
            timeoutMs = 20 * 60 * 1_000L,
            onProgress = onProgress,
            failureMessage = "Could not remove packages: $packageNames",
        )
    }

    private suspend fun runGuestCommand(
        chrootExec: File,
        command: String,
        displayCommand: String,
        fraction: Float,
        timeoutMs: Long,
        onProgress: suspend (RuntimeInstallProgress) -> Unit,
        failureMessage: String,
    ) {
        onProgress(
            RuntimeInstallProgress(
                message = displayCommand,
                fraction = fraction,
                terminalLine = "root@forge:~# $displayCommand",
                indeterminate = true,
                event = RuntimeInstallEvent.COMMAND,
            ),
        )
        val running = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { running.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        val native = running as? NativeSpawnProcess
        val collected = StringBuilder()
        try {
            withTimeout(timeoutMs) {
                var offset = 0L
                var pending = ""
                while (running.isAlive || (native?.outputFile?.length() ?: 0L) > offset) {
                    coroutineContext.ensureActive()
                    val file = native?.outputFile
                    if (file != null && file.length() > offset) {
                        RandomAccessFile(file, "r").use { input ->
                            val length = input.length()
                            if (length > offset) {
                                input.seek(offset)
                                val available = (length - offset).coerceAtMost(256 * 1024).toInt()
                                val bytes = ByteArray(available)
                                val read = input.read(bytes)
                                if (read > 0) {
                                    val usable = completeUtf8PrefixLength(bytes, read).let {
                                        if (it > 0 || !running.isAlive) (if (it > 0) it else read) else 0
                                    }
                                    if (usable > 0) {
                                        offset += usable
                                        pending += String(bytes, 0, usable, Charsets.UTF_8).replace('\r', '\n')
                                    }
                                }
                            }
                        }
                        val parts = pending.split('\n')
                        pending = parts.last()
                        for (raw in parts.dropLast(1)) {
                            val line = sanitizeTerminalLine(raw)
                            if (line.isNotBlank()) {
                                collected.appendLine(line)
                                if (collected.length > MAX_COLLECTED_OUTPUT) {
                                    collected.delete(0, collected.length - MAX_COLLECTED_OUTPUT)
                                }
                                onProgress(
                                    RuntimeInstallProgress(
                                        message = line,
                                        fraction = fraction,
                                        terminalLine = line,
                                        indeterminate = true,
                                        event = RuntimeInstallEvent.OUTPUT,
                                    ),
                                )
                            }
                        }
                    } else {
                        delay(80)
                    }
                }
                sanitizeTerminalLine(pending).takeIf(String::isNotBlank)?.let { line ->
                    collected.appendLine(line)
                    onProgress(RuntimeInstallProgress(line, fraction, terminalLine = line, indeterminate = true, event = RuntimeInstallEvent.OUTPUT))
                }
            }
        } catch (e: TimeoutCancellationException) {
            // TimeoutCancellationException is a CancellationException: left as-is the setup service would
            // report "paused" instead of a failure.
            throw IllegalStateException("$failureMessage: timed out after ${timeoutMs / 60_000L} min", e)
        } finally {
            if (running.isAlive) {
                running.destroy()
                repeat(10) {
                    if (!running.isAlive) return@repeat
                    runCatching { Thread.sleep(50) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                }
                if (running.isAlive) {
                    running.destroyForcibly()
                }
            }
            native?.outputFile?.delete()
        }
        val exit = running.waitFor()
        onProgress(
            RuntimeInstallProgress(
                message = if (exit == 0) "Command completed" else "Command failed (exit $exit)",
                fraction = fraction,
                terminalLine = "[exit $exit] $displayCommand",
                event = RuntimeInstallEvent.COMMAND_COMPLETED,
            ),
        )
        check(exit == 0) { actionableProcessError(collected.toString(), failureMessage) }
    }

    private fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    private suspend fun verifyGuest(chrootExec: File, command: String, failureMessage: String) {
        val verify = process(
            chrootExec = chrootExec,
            rootfs = rootfs,
            workspace = File(rootfs, "root"),
            environment = emptyMap(),
            guestCommand = listOf("/usr/bin/env", "bash", "-lc", command),
            guestWorkspacePath = "/root",
        )
        runCatching { verify.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        try {
            withTimeout(60_000L) {
                while (verify.isAlive) delay(50)
            }
            val exit = verify.waitFor()
            val output = (verify as? NativeSpawnProcess)?.outputFile
                ?.let(::readProcessOutputSafely)
                .orEmpty()
                .trim()
            check(exit == 0) { actionableProcessError(output, failureMessage, exit) }
        } catch (e: TimeoutCancellationException) {
            throw IllegalStateException("$failureMessage: timed out", e)
        } finally {
            if (verify.isAlive) {
                verify.destroy()
                verify.destroyForcibly()
            }
            runCatching { verify.waitFor() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
            (verify as? NativeSpawnProcess)?.outputFile?.delete()
        }
    }

    /**
     * Restores merged-/usr compatibility links (`bin -> usr/bin`, `lib -> usr/lib`, `sbin -> usr/sbin`)
     * inside the Ubuntu rootfs without replacing real directories.
     */
    private fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
        if (!targetRootfs.isDirectory) return false
        val links = mapOf(
            "bin" to "usr/bin",
            "lib" to "usr/lib",
            "sbin" to "usr/sbin",
        )
        return runCatching {
            links.forEach { (name, destination) ->
                val link = File(targetRootfs, name)
                val path = link.toPath()
                if (java.nio.file.Files.isSymbolicLink(path)) {
                    if (java.nio.file.Files.readSymbolicLink(path).toString() != destination) {
                        java.nio.file.Files.delete(path)
                        Os.symlink(destination, link.absolutePath)
                    }
                } else if (link.exists()) {
                    check(link.isDirectory) { "Linux /$name is not a directory or symbolic link" }
                } else {
                    Os.symlink(destination, link.absolutePath)
                }
            }
            File(targetRootfs, "usr/bin/env").canExecute() &&
                File(targetRootfs, "usr/bin/bash").canExecute() &&
                File(targetRootfs, "lib/ld-linux-aarch64.so.1").exists()
        }.onFailure {
            Log.e("RuntimeInstaller", "Could not repair Linux compatibility links", it)
        }.getOrDefault(false)
    }

    /**
     * Ensures Android paranoid-network socket groups (`aid_inet` 3003, `aid_net_raw` 3004)
     * and APT sandbox root configuration are present inside the chroot rootfs.
     */
    private fun ensureAndroidSocketGroupConfiguration(targetRootfs: File = rootfs) {
        runCatching {
            val groupFile = File(targetRootfs, "etc/group")
            if (groupFile.isFile) {
                var groupText = groupFile.readText()
                var changed = false
                if (!groupText.lineSequence().any { it.startsWith("aid_inet:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_inet:x:3003:root,_apt\n"
                    changed = true
                }
                if (!groupText.lineSequence().any { it.startsWith("aid_net_raw:") }) {
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "aid_net_raw:x:3004:root,_apt\n"
                    changed = true
                }
                // Under PRoot every guest process keeps the app's supplementary GIDs (everybody, the app's cache and
                // "all" groups, ...). Name them so `groups`, `id` and login shells stop printing
                // "cannot find name for group ID".
                val knownGids = groupText.lineSequence().mapNotNull { it.split(':').getOrNull(2)?.toIntOrNull() }.toSet()
                for ((gid, name) in androidSupplementaryGroups()) {
                    if (gid in knownGids) continue
                    if (!groupText.endsWith("\n")) groupText += "\n"
                    groupText += "$name:x:$gid:root\n"
                    changed = true
                }
                if (changed) groupFile.writeText(groupText)
            }
            val aptConf = File(targetRootfs, "etc/apt/apt.conf.d/99android-chroot")
            aptConf.parentFile?.mkdirs()
            // No apt seccomp sandbox: the app process already runs under Android's filter, and apt's own filter
            // cannot be installed on top of it from inside proot.
            val expectedAptConf = "APT::Sandbox::User \"root\";\nAPT::Sandbox::Seccomp \"false\";\nAcquire::Retries \"3\";\n"
            if (aptConf.readTextOrNull() != expectedAptConf.trim()) {
                aptConf.writeText(expectedAptConf)
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }

    /** The process's supplementary GIDs from /proc/self/status, with the names Android gives them. */
    private fun androidSupplementaryGroups(): List<Pair<Int, String>> {
        val groups = runCatching {
            File("/proc/self/status").readLines().firstOrNull { it.startsWith("Groups:") }
                ?.removePrefix("Groups:")?.trim()?.split(Regex("\\s+"))?.mapNotNull { it.toIntOrNull() }
        }.getOrNull().orEmpty()
        // Android: cache GID = 20000 + (appId - 10000), "all" GID = 50000 + (appId - 10000).
        val appIndex = AndroidProcess.myUid() % 100_000 - 10_000
        return groups.map { gid ->
            gid to when {
                gid == 3003 -> "aid_inet"
                gid == 3004 -> "aid_net_raw"
                gid == 9997 -> "aid_everybody"
                gid == 20_000 + appIndex -> "aid_cache"
                gid == 50_000 + appIndex -> "aid_all"
                gid >= 99_900_000 -> "aid_shared"
                else -> "aid_$gid"
            }
        }
    }

    private fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            isSupportedRootfsVersion(RuntimeMarkers.read(File(targetRootfs, ".nextcraft-rootfs-version"))?.trim())

    private fun actionableProcessError(output: String, fallback: String, exitCode: Int? = null): String {
        val lines = output.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        val chrootError = lines.firstOrNull {
            it.startsWith("chroot:") || it.startsWith("mount:") || it.contains("Permission denied", ignoreCase = true)
        }
        // A silent failure (binary killed by a signal, proot refusing to start) leaves no output: the exit code is
        // then the only clue. Shells report a signal as 128+N.
        val exitHint = exitCode?.takeIf { it != 0 }?.let { code ->
            val signal = if (code > 128) " (signal ${code - 128})" else ""
            " [exit $code$signal, engine ${RuntimeEngines.current().name.lowercase()}]"
        }.orEmpty()
        return chrootError ?: output.trim().takeLast(1_000).ifBlank { fallback } + exitHint
    }

    private fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    /** Idempotent per-start maintenance of an installed rootfs (scripts, group names, apt conf). */
    fun refreshGuestConfiguration() {
        if (!File(rootfs, "etc/group").isFile) return
        ensureChrootScripts()
        ensureAndroidSocketGroupConfiguration(rootfs)
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking PRoot runtime files…", 0.15f))
        ensureChrootScripts()
        ensureAndroidSocketGroupConfiguration(rootfs)
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing runtime IPC bridge…", 0.42f))
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("PRoot runtime ready", 1f))
        return installed
    }

    /** Spawns a command inside the Ubuntu 24.04 rootfs via the static `proot` from the APK and `proot-exec.sh`. */
    @Suppress("UNUSED_PARAMETER")
    fun process(
        chrootExec: File,
        rootfs: File,
        workspace: File,
        environment: Map<String, String>,
        guestCommand: List<String>,
        guestWorkspacePath: String = "/workspace",
        emulateHardLinks: Boolean = true,
        outputFile: File = File(context.cacheDir, "runtime-output-${System.nanoTime()}.log"),
        pseudoTerminal: Boolean = false,
        ptyRows: Int = 40,
        ptyColumns: Int = 120,
        trustClaudeWorkspace: Boolean = true,
        claudeMcpServers: org.json.JSONObject? = null,
    ): Process {
        val (envFile, suCommand) = prepareChrootShellCommand(
            workspace = workspace,
            guestWorkspacePath = guestWorkspacePath,
            environment = environment,
            guestCommand = guestCommand,
            chrootExec = chrootExec,
            targetRootfs = rootfs,
            trustClaudeWorkspace = trustClaudeWorkspace,
            claudeMcpServers = claudeMcpServers,
        )

        val spawned = try {
            NativeSpawnProcess.start(
                argv = launchArgv(suCommand),
                environment = emptyMap(),
                cwd = context.filesDir.absolutePath,
                outputFile = outputFile,
                pseudoTerminal = pseudoTerminal,
                ptyRows = ptyRows,
                ptyColumns = ptyColumns,
            )
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
        val pidFile = GuestProcess.pidFileFor(envFile)
        spawned.guestPidFile = pidFile
        // The `rm -f` inside suCommand never runs when su is denied or exec fails, which would leave API keys
        // on disk until the next launch's 10-minute purge. Remove the file as soon as the process is gone.
        Thread({
            while (spawned.isAlive) Thread.sleep(ENV_CLEANUP_POLL_MS)
            envFile.delete()
            pidFile.delete()
        }, "forge-env-cleanup").apply { isDaemon = true; start() }
        return spawned
    }

    /**
     * Prepares a one-time mode-0600 environment script and returns `(envFile, suCommand)` for
     * executing [guestCommand] inside the PRoot runtime with all SDK, PATH, and provider variables
     * exported and immediately unlinked before `exec`.
     */
    fun prepareChrootShellCommand(
        workspace: File,
        guestWorkspacePath: String = "/workspace",
        environment: Map<String, String> = emptyMap(),
        guestCommand: List<String>,
        chrootExec: File = chrootExecFile,
        targetRootfs: File = rootfs,
        trustClaudeWorkspace: Boolean = true,
        claudeMcpServers: org.json.JSONObject? = null,
    ): Pair<File, String> {
        check(ensureRootfsCompatibilityLinks(targetRootfs)) { "Core runtime has an invalid Linux filesystem layout" }
        require(
            guestWorkspacePath == "/root" ||
                guestWorkspacePath == "/workspace" ||
                Regex("^/workspace/[a-z0-9](?:[a-z0-9-]{0,62}[a-z0-9])?$").matches(guestWorkspacePath),
        ) { "Invalid project workspace path" }
        ensureChrootScripts()
        writeResolver()
        ensureShellPrompt()
        workspace.mkdirs()
        File(targetRootfs, guestWorkspacePath.removePrefix("/")).mkdirs()
        writeAndroidGradleConfiguration(targetRootfs)
        AntigravityCustomizations.apply(targetRootfs, guestWorkspacePath) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
        // ~/.claude.json belongs to Claude Code; other agents never read it, so they skip the parse + fsync per launch.
        if (trustClaudeWorkspace && guestWorkspacePath != "/root") {
            ensureWorkspaceTrust(guestWorkspacePath, environment, claudeMcpServers)
        }
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        File(context.filesDir, "runtime-bridge-responses").mkdirs()
        val appUid = AndroidProcess.myUid().toString()

        val guestEnv = buildMap {
            val hwProfile = DeviceHardwareOptimizer.inspect(context)
            val prootOpt = ProotAndChrootOptimizer.buildConfig(hwProfile, hasRootAccess = false)
            put("HOME", "/root")
            put("USER", "root")
            put("LOGNAME", "root")
            val androidReady = RuntimeMarkers.read(File(targetRootfs, "root/.nextcraft-android-tools-version"))?.trim() == ANDROID_TOOLS_VERSION
            val basePath = "/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if (androidReady) {
                put("ANDROID_HOME", AndroidToolchain.SDK_GUEST_DIR)
                put("ANDROID_SDK_ROOT", AndroidToolchain.SDK_GUEST_DIR)
                put("GRADLE_HOME", AndroidToolchain.GRADLE_HOME_GUEST_DIR)
                put("GRADLE_USER_HOME", "/root/.gradle")
                put("ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride", AndroidToolchain.AAPT2_GUEST_PATH)
                put("PATH", "${AndroidToolchain.GUEST_PATH_PREFIX}:$basePath")
            } else {
                put("PATH", basePath)
            }
            put("LANG", "C.UTF-8")
            // Same two settings as proot-exec.sh, so they also reach commands that re-exec with a fresh environment.
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            put("UV_USE_IO_URING", "0")
            // Go programs under ptrace: async preemption signals interrupt network syscalls (see proot-exec.sh).
            put("GODEBUG", "asyncpreemptoff=1")
            put("TERM", "xterm-256color")
            putAll(prootOpt.environmentVars)
            putAll(environment)
        }

        // Write environment variables (including sensitive API keys/OAuth tokens) to a
        // one-time mode-0600 file inside filesDir/runtime/env and immediately unlink it
        // in the launching shell before `exec`, so secrets NEVER appear in `ps aux` or
        // `/proc/<pid>/cmdline`.
        val runtimeEnvDir = File(runtimeDir, "env").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111000000) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0700
        }
        // A launch that never reached `rm -f` (su denied, process killed) leaves API keys on
        // disk. Purge anything old enough that it cannot belong to a launch in progress.
        runtimeEnvDir.listFiles { f -> f.name.startsWith(".env-") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 10 * 60 * 1_000L }
            ?.forEach { it.delete() }
        // Pid files of launches whose owner never cleaned up (terminal sessions, crashes): a day is far past any run.
        File(runtimeDir, "pids").listFiles()
            ?.filter { System.currentTimeMillis() - it.lastModified() > 24 * 60 * 60 * 1_000L }
            ?.forEach { it.delete() }
        val envFile = File(runtimeEnvDir, ".env-${java.util.UUID.randomUUID()}.sh")
        val envScript = buildString {
            for ((key, value) in guestEnv) {
                if (VALID_ENV_NAME.matches(key)) {
                    append("export ").append(key).append('=').append(shellSingleQuote(value)).append('\n')
                }
            }
        }
        val envBytes = envScript.toByteArray(Charsets.UTF_8)
        runCatching {
            val fd = Os.open(
                envFile.absolutePath,
                OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_TRUNC or OsConstants.O_EXCL,
                0b110000000,
            )
            FileOutputStream(fd).use { it.write(envBytes) }
        }.onFailure {
            envFile.writeText(envScript)
            runCatching { Os.chmod(envFile.absolutePath, 0b110000000) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0600
        }

        val quotedEnvFile = shellSingleQuote(envFile.absolutePath)
        val supervisorArgs = listOf(
            targetRootfs.absolutePath,
            workspace.absolutePath,
            guestWorkspacePath,
            bridge.absolutePath,
            appUid,
        ) + guestCommand
        // The app UID cannot exec the script itself, so it is always run through /system/bin/sh.
        val launch = listOf("/system/bin/sh", chrootExec.absolutePath) + supervisorArgs
        val engineExports =
            "export NEXTCRAFT_PROOT=${shellSingleQuote(RuntimeEngines.prootBinary(context).absolutePath)}; " +
                "export NEXTCRAFT_PROOT_LOADER=${shellSingleQuote(RuntimeEngines.prootLoader(context).absolutePath)}; " +
                "export NEXTCRAFT_PROOT_TMP=${shellSingleQuote(File(runtimeDir, "proot-tmp").apply { mkdirs() }.absolutePath)}; "
        // Absolute path: the env file exports the guest PATH, under which /system/bin/sh cannot find `rm`.
        val suCommand = ". $quotedEnvFile; /system/bin/rm -f $quotedEnvFile; ${engineExports}exec " +
            launch.joinToString(" ") { shellSingleQuote(it) }
        return envFile to suCommand
    }

    /** One hook entry (Bash/Edit/Write/NotebookEdit → permission-hook.sh); registered for PreToolUse and PermissionRequest. */
    private fun permissionHookEntry(): JSONArray = JSONArray().put(
        JSONObject()
            .put("matcher", "Bash|Edit|Write|NotebookEdit")
            .put(
                "hooks",
                JSONArray().put(
                    JSONObject().put("type", "command").put("command", "/opt/nextcraft/permission-hook.sh"),
                ),
            ),
    )

    fun ensureSettingsAndHooks() {
        val hook = File(rootfs, "opt/nextcraft/permission-hook.sh")
        hook.parentFile?.mkdirs()
        // Hands every permission request to the app through the shared bridge directory and
        // waits for the user's/guardrail decision. The app classifies risk, auto-answers
        // SAFE/REVIEW and prompts for HIGH. No answer within the timeout means deny.
        // Written atomically: a Claude session may be executing this script, and truncating it in place makes the hook
        // answer nothing, which Claude Code treats as a refusal.
        writeRootfsFileAtomically(
            hook,
            """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/nextcraft-bridge}"
RESP="${'$'}{NEXTCRAFT_RESPONSE_DIR:-/nextcraft-bridge-responses}"
# Parallel chats share this directory: the session id (set by the app) tags the request so only its owner answers it.
SID="${'$'}{NEXTCRAFT_SESSION_ID:-}"
ID="${'$'}{SID:+${'$'}{SID}_}${'$'}(cat /proc/sys/kernel/random/uuid 2>/dev/null || echo "${'$'}${'$'}-${'$'}(date +%s)")"
DECISION=deny
EVENT=PermissionRequest
# Without a live app watcher (Claude started by hand from the Terminal tab) answer nothing, so Claude Code keeps its
# own interactive prompt instead of waiting for a decision nobody will give. Parallel chats each keep their own
# .watcher-<id> marker, so one session ending never removes another's; any marker existing means some watcher runs.
HAS_WATCHER=0
for w in "${'$'}BRIDGE"/.watcher-*; do
    [ -e "${'$'}w" ] && HAS_WATCHER=1 && break
done
if [ "${'$'}HAS_WATCHER" = "0" ]; then
    cat > /dev/null
    exit 0
fi
if [ -d "${'$'}BRIDGE" ] && cat > "${'$'}BRIDGE/${'$'}ID.request.tmp"; then
    grep -q '"hook_event_name" *: *"PreToolUse"' "${'$'}BRIDGE/${'$'}ID.request.tmp" && EVENT=PreToolUse
    mv "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.request"
    i=0
    while [ "${'$'}i" -lt 600 ]; do
        if [ -f "${'$'}RESP/${'$'}ID.response" ]; then
            [ "${'$'}(cat "${'$'}RESP/${'$'}ID.response")" = "allow" ] && DECISION=allow
            break
        fi
        sleep 0.1
        i=${'$'}((i + 1))
    done
    rm -f "${'$'}BRIDGE/${'$'}ID.request" "${'$'}BRIDGE/${'$'}ID.request.tmp"
else
    cat > /dev/null
fi
# The same script serves both hook events. PreToolUse is the one every Claude Code version runs in headless (-p)
# mode; PermissionRequest only runs there in newer releases, and 2.1.112 (the npm release PRoot is pinned to) turns an
# unanswered "ask" into "This command requires approval" without consulting it.
if [ "${'$'}EVENT" = "PreToolUse" ]; then
    printf '{"hookSpecificOutput":{"hookEventName":"PreToolUse","permissionDecision":"%s","permissionDecisionReason":"NextCraft Forge"}}\n' "${'$'}DECISION"
else
    printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}DECISION"
fi
""",
            mode = 0b111101101,
        )

        // BROWSER handler for `claude auth login`: there is no browser in the chroot, so the sign-in URL is handed to
        // the app through the bridge directory and opened in the Android browser. The browser then redirects to
        // http://localhost:<port>/callback, which the CLI (same network namespace) is listening on.
        val openUrl = File(rootfs, "opt/nextcraft/open-url.sh")
        writeRootfsFileAtomically(
            openUrl,
            """#!/bin/sh
BRIDGE="${'$'}{NEXTCRAFT_BRIDGE_DIR:-/nextcraft-bridge}"
[ -n "${'$'}1" ] && [ -d "${'$'}BRIDGE" ] || exit 0
printf '%s\n' "${'$'}1" > "${'$'}BRIDGE/claude-login-url.tmp" && mv "${'$'}BRIDGE/claude-login-url.tmp" "${'$'}BRIDGE/claude-login-url"
""",
            mode = 0b111101101,
        )

        val settingsContent = JSONObject()
            .put("disableAllHooks", false)
            .put(
                "permissions",
                JSONObject()
                    .put("allow", claudeWorkspaceToolRules())
                    .put("defaultMode", "acceptEdits"),
            )
            .put(
                "hooks",
                JSONObject()
                    .put("PreToolUse", permissionHookEntry())
                    .put("PermissionRequest", permissionHookEntry()),
            )
            .toString()

        val settingsPaths = listOf(
            File(rootfs, "root/.claude/nextcraft-settings.json"),
            File(rootfs, "root/.claude/settings.json"),
            File(rootfs, "etc/claude/settings.json"),
        )
        for (target in settingsPaths) {
            writeRootfsFileAtomically(target, settingsContent)
        }
        // Sessions started by an older build (and its settings files) still call /opt/pocket/*: keep identical copies there.
        val legacyScripts = File(rootfs, "opt/pocket")
        listOf(hook, openUrl).forEach { script ->
            runCatching { writeRootfsFileAtomically(File(legacyScripts, script.name), script.readText(), mode = 0b111101101) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }
        File(rootfs, "root/.claude/pocket-settings.json").delete()
        // Agents' background jobs get a hangup when the turn ends (the CLI tears down its shells). This starts a command
        // in its own session with hangups ignored, so a dev server or a long build outlives the turn.
        writeRootfsFileAtomically(
            File(rootfs, "opt/nextcraft/bin/keep-running"),
            """#!/bin/sh
# Keeps a command running after the agent's turn ends (dev servers, long builds).
#   keep-running <logfile> <command> [args...]      then read <logfile>; stop it with: kill <pid>
[ ${'$'}# -ge 2 ] || { echo "usage: keep-running <logfile> <command> [args...]" >&2; exit 2; }
log="${'$'}1"; shift
setsid nohup "${'$'}@" > "${'$'}log" 2>&1 < /dev/null &
echo "started pid ${'$'}! (log: ${'$'}log)"
""",
            mode = 0b111101101,
        )
        ensureSystemGitTrust()
        ProotAndChrootOptimizer.applyGuestWrappers(rootfs) { file, body, mode -> writeRootfsFileAtomically(file, body, mode = mode) }
        AntigravityCustomizations.apply(rootfs) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
        ensureWorkspaceTrust("/workspace")
    }

    /**
     * PRoot's `-0` flag makes the guest see every process as UID 0, while the files themselves are still owned by
     * the app's own Android UID; git compares the two and refuses every project with "dubious ownership" (exit 128)
     * without this. The system config is used so ~/.gitconfig stays the user's.
     */
    private fun ensureSystemGitTrust() {
        val config = File(rootfs, "etc/gitconfig")
        val existing = if (config.isFile) runCatching { config.readText() }.getOrNull() ?: return else ""
        val merged = mergeGitConfig(existing, listOf(GitConfigEntry("safe", "directory", "*", multiValue = true)))
        if (merged != existing) writeRootfsFileAtomically(config, merged)
    }

    private fun ensureWorkspaceTrust(
        workspacePath: String,
        environment: Map<String, String> = emptyMap(),
        mcpServers: org.json.JSONObject? = null,
    ) = synchronized(CLAUDE_STATE_LOCK) {
        val stateFile = File(rootfs, "root/.claude.json")
        val state = if (stateFile.exists()) {
            // Claude may be writing the file right now (another session); never replace a file that does not parse,
            // or the account, API-key approvals and project history in it would be lost.
            readClaudeState(stateFile) ?: run {
                Log.w("RuntimeInstaller", "Leaving ~/.claude.json untouched: it could not be parsed")
                return@synchronized
            }
        } else {
            JSONObject()
        }
        var changed = false
        listOf("disableAllHooks", "permissions", "hooks", "allowedTools", "autoApprove")
            .forEach { if (state.has(it)) { state.remove(it); changed = true } }
        if (!state.optBoolean("hasCompletedOnboarding", false)) {
            state.put("hasCompletedOnboarding", true)
            changed = true
        }
        val projects = state.optJSONObject("projects") ?: JSONObject().also { changed = true }
        val workspace = projects.optJSONObject(workspacePath) ?: JSONObject().also { changed = true }
        if (!workspace.optBoolean("hasTrustDialogAccepted", false)) {
            workspace.put("hasTrustDialogAccepted", true)
            changed = true
        }
        projects.put(workspacePath, workspace)
        state.put("projects", projects)
        val apiKeySuffix = environment["ANTHROPIC_API_KEY"]?.trim()?.takeIf { it.isNotEmpty() }?.takeLast(20)
        if (!apiKeySuffix.isNullOrBlank()) {
            val responses = state.optJSONObject("customApiKeyResponses") ?: JSONObject().also { changed = true }
            val approved = responses.optJSONArray("approved") ?: JSONArray().also { changed = true }
            val existing = (0 until approved.length()).mapNotNull { approved.optString(it).takeIf(String::isNotBlank) }.toSet()
            if (apiKeySuffix !in existing) {
                approved.put(apiKeySuffix)
                changed = true
            }
            responses.put("approved", approved)
            if (responses.optJSONArray("rejected") == null) {
                responses.put("rejected", JSONArray())
                changed = true
            }
            state.put("customApiKeyResponses", responses)
        }
        // Connectors the user added: the entries this app manages (prefix "forge-") follow the saved list, anything
        // else in mcpServers (added with `claude mcp add`) is left alone.
        if (mcpServers != null) {
            val existing = state.optJSONObject("mcpServers") ?: JSONObject()
            fun managed(source: JSONObject): String = source.keys().asSequence()
                .filter { it.startsWith(McpConnector.MANAGED_PREFIX) }.sorted()
                .joinToString("|") { key -> key + "=" + source.get(key) }
            if (managed(existing) != managed(mcpServers)) {
                existing.keys().asSequence().toList()
                    .filter { it.startsWith(McpConnector.MANAGED_PREFIX) }
                    .forEach { existing.remove(it) }
                mcpServers.keys().forEach { key -> existing.put(key, mcpServers.get(key)) }
                state.put("mcpServers", existing)
                changed = true
            }
        }
        // Every launch used to rewrite (and fsync) the file even when nothing changed; that is slow flash I/O and
        // can overwrite what a running Claude session wrote in the meantime.
        if (!changed && stateFile.exists()) return@synchronized
        writeRootfsFileAtomically(stateFile, state.toString())
    }

    /** Parses ~/.claude.json, retrying briefly in case Claude is in the middle of rewriting it. */
    private fun readClaudeState(stateFile: File): JSONObject? {
        repeat(3) { attempt ->
            val parsed = runCatching { JSONObject(stateFile.readText()) }.getOrNull()
            if (parsed != null) return parsed
            if (attempt < 2) Thread.sleep(150)
        }
        return null
    }

    /**
     * Writes [target] through a temp file in the same directory and an atomic rename, so a crash or a concurrent
     * reader never sees a truncated file.
     */
    private fun writeRootfsFileAtomically(target: File, content: String, mode: Int? = null) {
        val parent = checkNotNull(target.parentFile) { "No parent directory for ${target.name}" }
        parent.mkdirs()
        val temp = File(parent, ".${target.name}.${AndroidProcess.myPid()}.${System.nanoTime()}.tmp")
        try {
            FileOutputStream(temp).use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            // Set the mode before the rename so the file is never visible without its execute bit.
            if (mode != null) Os.chmod(temp.absolutePath, mode)
            Os.rename(temp.absolutePath, target.absolutePath)
        } catch (error: java.io.IOException) {
            // EACCES: the folder or file belongs to root (or carries a label the app may not write). Let root write it.
            val written = RootfsRepair.writeAsRoot(
                target, content.toByteArray(Charsets.UTF_8), File(downloads, ".root-write.tmp"), AndroidProcess.myUid(),
                mode ?: 0b110100100,
            )
            if (!written) throw error
        } finally {
            temp.delete()
        }
    }

    // Only auto-allow read-only tools. Bash/Edit/Write/NotebookEdit go through the
    // PermissionRequest hook so the app can classify and gate each command.
    private fun claudeWorkspaceToolRules() = JSONArray().apply {
        put("Read")
        put("Glob")
        put("Grep")
        // Tools outside the permission hook (web, sub-agents, todos, skills) would otherwise be refused outright in a
        // headless session, since nobody can answer Claude's own prompt. With auto-approve on, nothing may be refused.
        if (runCatching { com.nextcraft.forge.data.AppPreferences(context).autoApproveAllTools }.getOrDefault(true)) {
            listOf("WebFetch", "WebSearch", "Task", "Agent", "TodoWrite", "Skill").forEach(::put)
        }
    }

    /**
     * dpkg (any `apt install` inside the chroot) re-applies base-files' mode 0700 root:root to /root, and files that
     * root creates there stay root's. The app UID is then locked out of /root and below: the version markers cannot be
     * read (REA shows "not installed"), `.claude` cannot be written, `agy` cannot be replaced. Folders are handed back
     * to the app UID (not recursively: root still reaches everything, and the per-file fallbacks cover the rest).
     */
    internal fun ensureAppAccess() {
        val uid = AndroidProcess.myUid()
        for (relative in listOf("root", "root/.claude", "root/.gemini", "root/.gemini/config", "root/.gemini/antigravity-cli", "root/.local", "root/.local/bin", "etc")) {
            val dir = File(rootfs, relative)
            if (dir.isDirectory && (!dir.canWrite() || !dir.canExecute())) {
                RootfsRepair.reclaimOwnership(dir, uid, recursive = false)
            }
        }
    }

    /** Keeps the coloured prompt block in /root/.bashrc ([ShellPrompt]); an unreadable file is left alone. */
    private fun ensureShellPrompt() {
        ensureX86Wrapper()
        val bashrc = File(rootfs, "root/.bashrc")
        val current = runCatching { if (bashrc.isFile) bashrc.readText() else "" }.getOrNull() ?: return
        val updated = ShellPrompt.apply(current)
        if (updated != current) {
            runCatching { writeRootfsFileAtomically(bashrc, updated, mode = 0b110100100) }
                .onFailure { Log.w("RuntimeInstaller", "Could not write the shell prompt", it) }
        }
    }

    /** Installs `run-x86` (see [X86Wrapper]) in the guest; skipped while it is up to date. */
    private fun ensureX86Wrapper() {
        val target = File(rootfs, X86Wrapper.GUEST_PATH)
        if (runCatching { target.readText() }.getOrNull() == X86Wrapper.SCRIPT) return
        runCatching {
            target.parentFile?.mkdirs()
            writeRootfsFileAtomically(target, X86Wrapper.SCRIPT, mode = 0b111101101)
        }.onFailure { Log.w("RuntimeInstaller", "Could not write run-x86", it) }
    }

    private fun writeResolver() {
        ensureAppAccess()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager?.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val deviceServers = dns.mapNotNull { it.hostAddress }
        // Many carriers and most phones default Private DNS to a provider whose IP only answers DNS-over-TLS on port
        // 853; the guest's plain resolver only ever queries port 53, so every lookup there times out even though the
        // device's own apps and browser resolve fine. Public plain-DNS fallbacks are always appended (not only when
        // the device list is empty) so the resolver falls through to them when the device entries are DoT-only.
        // glibc only reads the first MAXNS=3 "nameserver" lines, so the device's own servers are capped to 1 here --
        // otherwise two DoT-only device entries would push both public fallbacks off the end of the list.
        val fallbacks = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "8.8.4.4", "1.0.0.1")
        val servers = (deviceServers.take(1) + fallbacks).distinct().take(3)
        val resolvFile = File(rootfs, "etc/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) {
            resolvFile.delete()
        }
        // Shorter timeout/attempts than glibc's 5s/2 default so a dead or filtered server is skipped quickly instead
        // of burning the whole login flow's patience on one unreachable nameserver before falling through.
        val resolver = servers.joinToString("\n") { "nameserver $it" } + "\noptions timeout:2 attempts:2 rotate\n"
        try {
            // A link that survived the delete above (not ours to unlink) would send the write into /run, which is root's.
            if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) throw java.io.IOException("resolv.conf is still a symlink")
            resolvFile.writeText(resolver)
        } catch (error: java.io.IOException) {
            // /etc/resolv.conf is root-owned after the chroot supervisor (running as root) created it.
            if (!RootfsRepair.writeAsRoot(resolvFile, resolver.toByteArray(Charsets.UTF_8), File(downloads, ".root-write.tmp"), AndroidProcess.myUid())) {
                throw error
            }
        }
        ensureLocalhostInHosts()
    }

    /**
     * The Core rootfs ships an empty /etc/hosts, so `localhost` was resolved through DNS and failed. Anything that binds
     * or connects to localhost then breaks: the OAuth callback listener of Antigravity ("listen tcp: lookup localhost ...
     * no such host") and Claude Code logins, dev servers, Gradle and preview. Keeps whatever else the file holds.
     */
    private fun ensureLocalhostInHosts() {
        val hosts = File(rootfs, "etc/hosts")
        val current = runCatching { hosts.takeIf { it.isFile }?.readText().orEmpty() }.getOrDefault("")
        val hasLocalhost = current.lineSequence().any { line ->
            val fields = line.substringBefore('#').trim().split(Regex("\\s+"))
            fields.size >= 2 && fields[0] == "127.0.0.1" && "localhost" in fields.drop(1)
        }
        if (hasLocalhost) return
        val entries = "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n"
        runCatching { writeRootfsFileAtomically(hosts, entries + current.trimStart('\n')) }
            .onFailure { Log.w("RuntimeInstaller", "Could not write /etc/hosts", it) }
    }

    private fun openZstdInputStream(
        archive: File,
        consumed: java.util.concurrent.atomic.AtomicLong? = null,
    ): java.io.InputStream {
        val fileStream = archive.inputStream()
        // Counts compressed bytes read so unpacking can report MB/s; the external-zstd fallback is not counted.
        val counted = if (consumed == null) fileStream else object : java.io.FilterInputStream(fileStream) {
            override fun read(): Int = super.read().also { if (it >= 0) consumed.incrementAndGet() }
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                super.read(b, off, len).also { if (it > 0) consumed.addAndGet(it.toLong()) }
        }
        val rawStream = BufferedInputStream(counted)
        return try {
            ZstdCompressorInputStream(rawStream)
        } catch (_: Throwable) {
            runCatching { rawStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
            val process = ProcessBuilder("zstd", "-d", "-c", archive.absolutePath).start()
            object : java.io.FilterInputStream(BufferedInputStream(process.inputStream)) {
                private var reachedEof = false

                override fun read(): Int = super.read().also { if (it < 0) reachedEof = true }

                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    super.read(b, off, len).also { if (it < 0) reachedEof = true }

                override fun close() {
                    try {
                        super.close()
                    } finally {
                        runCatching { process.errorStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                        runCatching { process.outputStream.close() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                        val finished = runCatching {
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        }.getOrDefault(false)
                        if (!finished) {
                            process.destroyForcibly()
                        } else if (reachedEof && process.exitValue() != 0) {
                            throw java.io.IOException("zstd decompression failed with exit code ${process.exitValue()}")
                        }
                    }
                }
            }
        }
    }

    /**
     * Opens [target] for writing during an unpack. An earlier unpack may have left a read-only file (mode 0555) or root
     * rewrote it, which made the app UID's open fail with EACCES (Antigravity install stopped at 80%). A leftover is
     * replaced; if the folder itself is root's it is reclaimed first.
     */
    private fun openForWrite(target: File): FileOutputStream {
        try {
            return FileOutputStream(target)
        } catch (first: java.io.IOException) {
            val parent = target.parentFile
            if (parent != null && !parent.canWrite()) RootfsRepair.reclaimOwnership(parent, AndroidProcess.myUid(), recursive = false)
            if (target.exists() && !target.canWrite() && !target.delete()) {
                RootfsRepair.reclaimOwnership(target, AndroidProcess.myUid(), recursive = false)
                target.delete()
            }
            return FileOutputStream(target)
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun extractZstdTar(
        archive: File,
        destination: File,
        /** Receives (compressed bytes consumed, archive size) at most every ~400 ms. */
        report: (suspend (Long, Long) -> Unit)? = null,
    ) {
        val consumed = if (report != null) java.util.concurrent.atomic.AtomicLong() else null
        val archiveSize = archive.length()
        var lastReport = 0L
        val rootDir = destination.canonicalFile
        val rootPath = rootDir.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(openZstdInputStream(archive, consumed)).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                // Pause/stop from the notification must end a long unpack instead of waiting for it to finish.
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (report != null && consumed != null) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastReport >= 400L) {
                        lastReport = now
                        report(consumed.get().coerceAtMost(archiveSize), archiveSize)
                    }
                }
                val currentEntry = entry
                val cleanName = currentEntry.name.removePrefix("./").trimEnd('/')
                if (cleanName.isNotBlank()) {
                    val target = safeChild(rootDir, cleanName)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                val resolvedDir = runCatching {
                                    resolveSafeSymlinkTarget(
                                        rootPath = rootPath,
                                        parentDir = target.toPath().parent ?: rootPath,
                                        linkName = java.nio.file.Files.readSymbolicLink(target.toPath()).toString(),
                                        errorMessage = "Archive path escapes runtime via symlink",
                                    ).toFile()
                                }.getOrNull()
                                if (resolvedDir != null) {
                                    resolvedDir.mkdirs()
                                } else {
                                    target.delete()
                                    target.mkdirs()
                                }
                            } else {
                                target.mkdirs()
                            }
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            resolveSafeSymlinkTarget(
                                rootPath = rootPath,
                                parentDir = target.toPath().parent ?: rootPath,
                                linkName = currentEntry.linkName,
                                errorMessage = "Archive symlink escapes runtime",
                            )
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                if (target.isDirectory && !java.nio.file.Files.isSymbolicLink(target.toPath())) {
                                    target.deleteRecursively()
                                } else {
                                    target.delete()
                                }
                            }
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            target.parentFile?.mkdirs()
                            val linkTarget = safeChild(rootDir, currentEntry.linkName.removePrefix("./").trimEnd('/'))
                            if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            openForWrite(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
                runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
            }
        }
        // Published bundles still carry the .pocket-* marker names.
        RuntimeMarkers.syncAll(destination)
    }

    @Suppress("DEPRECATION")
    private fun extractNodeArchive(archive: File, destination: File) {
        val rootDir = destination.canonicalFile
        val rootPath = rootDir.toPath()
        val deferredLinks = mutableListOf<Pair<File, File>>()
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            var entry: TarArchiveEntry? = tar.nextTarEntry
            while (entry != null) {
                val currentEntry = entry
                val relative = currentEntry.name.removePrefix("./").substringAfter('/', "").trimEnd('/')
                if (relative.isNotBlank()) {
                    val target = safeChild(rootDir, relative)
                    when {
                        currentEntry.isDirectory -> {
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            target.mkdirs()
                        }
                        currentEntry.isSymbolicLink -> {
                            target.parentFile?.mkdirs()
                            resolveSafeSymlinkTarget(
                                rootPath = rootPath,
                                parentDir = target.toPath().parent ?: rootPath,
                                linkName = currentEntry.linkName,
                                errorMessage = "Archive symlink escapes runtime",
                            )
                            if (target.exists() || java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            Os.symlink(currentEntry.linkName, target.absolutePath)
                        }
                        currentEntry.isLink -> {
                            val relativeLink = currentEntry.linkName.removePrefix("./").substringAfter('/', "").trimEnd('/')
                            val linkTarget = safeChild(rootDir, relativeLink)
                            target.parentFile?.mkdirs()
                            if (linkTarget.exists() || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                                linkOrCopyFile(linkTarget, target)
                            } else {
                                deferredLinks += target to linkTarget
                            }
                        }
                        currentEntry.isFile -> {
                            target.parentFile?.mkdirs()
                            if (java.nio.file.Files.isSymbolicLink(target.toPath())) target.delete()
                            FileOutputStream(target).use { output -> tar.copyTo(output) }
                            runCatching { Os.chmod(target.absolutePath, currentEntry.mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
                        }
                    }
                }
                entry = tar.nextTarEntry
            }
        }
        deferredLinks.forEach { (target, linkTarget) ->
            require(linkTarget.isFile || java.nio.file.Files.isSymbolicLink(linkTarget.toPath())) {
                "Node.js archive hard-link target is missing"
            }
            target.parentFile?.mkdirs()
            linkOrCopyFile(linkTarget, target)
            if (!java.nio.file.Files.isSymbolicLink(target.toPath())) {
                runCatching { Os.chmod(target.absolutePath, Os.stat(linkTarget.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
            }
        }
    }

    private fun linkOrCopyFile(source: File, destination: File) {
        if (destination.exists() || java.nio.file.Files.isSymbolicLink(destination.toPath())) destination.delete()
        if (java.nio.file.Files.isSymbolicLink(source.toPath())) {
            val symTarget = java.nio.file.Files.readSymbolicLink(source.toPath()).toString()
            Os.symlink(symTarget, destination.absolutePath)
            return
        }
        val linked = runCatching {
            Os.link(source.absolutePath, destination.absolutePath)
            true
        }.getOrDefault(false)
        if (!linked) {
            source.inputStream().use { input -> FileOutputStream(destination).use { input.copyTo(it) } }
            runCatching { Os.chmod(destination.absolutePath, Os.stat(source.absolutePath).st_mode and 0b111111111) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }
    }

    private fun resolveSafeSymlinkTarget(
        rootPath: java.nio.file.Path,
        parentDir: java.nio.file.Path,
        linkName: String,
        errorMessage: String = "Archive symlink escapes runtime",
    ): java.nio.file.Path {
        require(linkName.isNotBlank() && !linkName.contains('\u0000')) { errorMessage }
        val normalizedLink = linkName.replace('\\', '/')
        val resolved = if (normalizedLink.startsWith('/')) {
            val relFromRoot = normalizedLink.trimStart('/')
            if (relFromRoot.isEmpty()) rootPath else rootPath.resolve(relFromRoot).normalize()
        } else {
            parentDir.resolve(normalizedLink).normalize()
        }
        require(resolved.startsWith(rootPath)) { errorMessage }
        return resolved
    }

    /**
     * Walks [segments] from [start] one directory at a time. A symlink is resolved lexically against the root and
     * the result is then walked again segment by segment, so a link whose target itself crosses another link
     * (the guest owns every byte of the rootfs and can plant `tmp/esc -> ../../..`) cannot lead outside the root.
     */
    private fun walkDirectoryWithinRoot(
        rootPath: java.nio.file.Path,
        start: java.nio.file.Path,
        segments: List<String>,
        hops: IntArray,
    ): java.nio.file.Path {
        var currentDir = start
        for (segment in segments) {
            val candidate = currentDir.resolve(segment).normalize()
            require(candidate.startsWith(rootPath) && candidate != rootPath) { "Archive path escapes runtime" }
            currentDir = if (java.nio.file.Files.isSymbolicLink(candidate)) {
                check(++hops[0] <= 16) { "Archive symlink loop detected" }
                val resolved = resolveSafeSymlinkTarget(
                    rootPath = rootPath,
                    parentDir = candidate.parent ?: rootPath,
                    linkName = java.nio.file.Files.readSymbolicLink(candidate).toString(),
                    errorMessage = "Archive path escapes runtime via symlink",
                )
                val relative = rootPath.relativize(resolved)
                val innerSegments = (0 until relative.nameCount).map { relative.getName(it).toString() }
                    .filter { it.isNotEmpty() && it != "." }
                walkDirectoryWithinRoot(rootPath, rootPath, innerSegments, hops)
            } else {
                candidate
            }
        }
        return currentDir
    }

    private fun safeChild(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/').trimEnd('/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe archive path"
        }
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        val hops = intArrayOf(0)
        val currentDir = walkDirectoryWithinRoot(rootPath, rootPath, segments.dropLast(1), hops)
        val finalPath = currentDir.resolve(segments.last()).normalize()
        require(finalPath.startsWith(rootPath) && finalPath != rootPath) {
            "Archive path escapes runtime"
        }
        return finalPath.toFile()
    }

    private suspend fun downloadVerified(
        url: String,
        destination: File,
        expectedChecksum: String,
        algorithm: String = "SHA-256",
        onBytes: suspend (downloaded: Long, total: Long) -> Unit,
    ) {
        destination.parentFile?.mkdirs()
        if (destination.isFile && digest(destination, algorithm).equals(expectedChecksum, ignoreCase = true)) {
            onBytes(destination.length(), destination.length())
            return
        }
        val temporary = File(destination.parentFile, "${destination.name}.part")
        var existing = temporary.takeIf(File::isFile)?.length() ?: 0L
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 20_000
            connection.readTimeout = 120_000
            connection.instanceFollowRedirects = true
            if (existing > 0L) connection.setRequestProperty("Range", "bytes=$existing-")
            val code = connection.responseCode
            if (code == 416 && existing > 0L) {
                // Stale/complete .part cannot be resumed; discard it and restart now (existing is 0 on the retry).
                temporary.delete()
                return downloadVerified(url, destination, expectedChecksum, algorithm, onBytes)
            }
            check(code in 200..299) { "Download failed with HTTP $code" }
            val resumed = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!resumed) {
                temporary.delete()
                existing = 0L
            }
            val total = connection.contentLengthLong.takeIf { it >= 0L }?.plus(existing) ?: -1L
            connection.inputStream.use { input ->
                FileOutputStream(temporary, resumed).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var downloaded = existing
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        onBytes(downloaded, total)
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
        val actual = digest(temporary, algorithm)
        if (!actual.equals(expectedChecksum, ignoreCase = true)) {
            temporary.delete()
            error("Downloaded file checksum did not match")
        }
        destination.delete()
        check(temporary.renameTo(destination)) { "Could not finish download" }
    }

    private fun fetchText(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.setRequestProperty("Accept", "application/json")
            val code = connection.responseCode
            check(code in 200..299) { "Request failed with HTTP $code" }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun digest(file: File, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm)
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        /**
         * Gradle speed settings, sized like [gradleJvmArgs]. Measured on a 10.9 GB phone, an up-to-date JetNews build
         * still took 4 min with a fresh JVM per build and 2 workers: almost all of it start-up and configuration.
         *  - daemon: keeps the warmed-up JVM between builds; it exits after 10 idle minutes so its heap is returned.
         *    Off below 7 GB, where an idle 1-2 GB JVM would push the app into the low-memory killer;
         *  - configuration cache (problems only warn, so incompatible builds still run) and the build cache;
         *  - workers: 4 / 3 / 2 and parallel project execution only on 10 GB+ (each worker adds memory).
         */
        internal fun gradleSpeedProperties(totalMemoryMb: Long): List<Pair<String, String>> =
            gradleSpeedProperties(DeviceHardwareOptimizer.computeGradleTuning(totalMemoryMb))

        internal fun gradleSpeedProperties(profile: DeviceHardwareOptimizer.HardwareProfile): List<Pair<String, String>> =
            gradleSpeedProperties(
                DeviceHardwareOptimizer.computeGradleTuning(
                    totalMemoryMb = profile.totalRamMb,
                    swapTotalMb = profile.swap.swapTotalMb,
                    swapFreeMb = profile.swap.swapFreeMb,
                    socVendor = profile.socVendor,
                    socTier = profile.socTier,
                    performanceCores = profile.topology.performanceCores,
                ),
            )

        internal fun gradleSpeedProperties(tuning: DeviceHardwareOptimizer.GradleTuning): List<Pair<String, String>> = listOf(
            "org.gradle.daemon" to tuning.daemonEnabled.toString(),
            "org.gradle.daemon.idletimeout" to tuning.daemonIdleTimeoutMs.toString(),
            GRADLE_WORKERS_PROPERTY to tuning.maxWorkers.toString(),
            GRADLE_PARALLEL_PROPERTY to tuning.parallelExecution.toString(),
            "org.gradle.caching" to "true",
            "org.gradle.configuration-cache" to "true",
            "org.gradle.configuration-cache.problems" to "warn",
        )

        internal fun gradleJvmArgs(totalMemoryMb: Long): String =
            gradleJvmArgs(DeviceHardwareOptimizer.computeGradleTuning(totalMemoryMb))

        internal fun gradleJvmArgs(profile: DeviceHardwareOptimizer.HardwareProfile): String =
            gradleJvmArgs(
                DeviceHardwareOptimizer.computeGradleTuning(
                    totalMemoryMb = profile.totalRamMb,
                    swapTotalMb = profile.swap.swapTotalMb,
                    swapFreeMb = profile.swap.swapFreeMb,
                    socVendor = profile.socVendor,
                    socTier = profile.socTier,
                    performanceCores = profile.topology.performanceCores,
                ),
            )

        internal fun gradleJvmArgs(tuning: DeviceHardwareOptimizer.GradleTuning): String =
            "-Xmx${tuning.heapMb}m -XX:MaxMetaspaceSize=${tuning.metaspaceMb}m -Dfile.encoding=UTF-8"

        /** Top-level rootfs directories that proot-exec.sh binds from Android or the app on every launch. */
        private val BIND_MOUNTED_TOP_LEVEL = setOf("proc", "sys", "dev", "system", "apex", "vendor", "product", "system_ext", "odm", "workspace", "data", "storage", "mnt", "sdcard", "tmp", "root", "nextcraft-bridge", "pocketdev-bridge", "pocket-bridge", "pocketdev-bridge-responses", "nextcraft-bridge-responses")
        /** RuntimeInstaller is created per call site; the lock must be shared by every instance. */
        private val CLAUDE_STATE_LOCK = Any()

        fun rootfsDir(context: Context): File = File(context.filesDir, "runtime/ubuntu")
        fun bridgeRootDir(context: Context): File = File(context.filesDir, "runtime-bridge")

        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        private const val AGY_VERSION = "1.4.5"
        const val CORE_MISSING_MESSAGE =
            "The base Ubuntu runtime (Core) is not installed or is incomplete. Run the runtime setup first, then retry."
        private const val GITHUB_CLI_VERSION = "2.100.0"
        private const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        private const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"

        init {
            check(
                GITHUB_CLI_RELEASE_SHA256.length == 64 &&
                    GITHUB_CLI_RELEASE_SHA256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
            ) {
                "Invalid GITHUB_CLI_RELEASE_SHA256 length"
            }
        }
        private const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        private const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        private const val ROOTFS_VERSION = "ubuntu-24.04.5-arm64"
        private const val LEGACY_ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        private const val LEGACY_ROOTFS_PYTHON_MESSAGE =
            "Python needs the Ubuntu 24.04 runtime. This runtime is still on Ubuntu 20.04: reinstall the runtime " +
                "(Settings → Runtime), then install Python again."
        private const val NODE_VERSION = "v24.22.1"
        private const val NODE_ARM64_SHA256 = "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f"
        private const val COMPOSER_VERSION = "2.10.3"
        private const val COMPOSER_SHA256 = "7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6"
        private const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        private const val CORE_TOOLS_VERSION = "core-bundle-2026.10.1"
        private const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        private const val PREVIOUS_CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        private const val NEXTCRAFT_CORE_TOOLS_VERSION = "nextcraft-core-2026.10.1"
        /** Core rebuilt from scratch by build-core-on-linux.sh (runtime-bundles.yml). */
        private const val NEXTCRAFT_CORE_TOOLS_VERSION_2 = "nextcraft-core-2026.10.2"
        private const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        private const val ANDROID_TOOLS_VERSION = AndroidToolchain.TOOLS_VERSION
        private const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        private const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        private const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        private const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        private const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        private val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
            ANDROID_AAPT2_PROPERTY,
            GRADLE_JVMARGS_PROPERTY,
            GRADLE_WORKERS_PROPERTY,
            GRADLE_PARALLEL_PROPERTY,
            KOTLIN_EXECUTION_PROPERTY,
            "org.gradle.daemon",
            "org.gradle.daemon.idletimeout",
            "org.gradle.caching",
            "org.gradle.configuration-cache",
            "org.gradle.configuration-cache.problems",
        )
        private const val ANDROID_AAPT2_GUEST_PATH = AndroidToolchain.AAPT2_GUEST_PATH
        private const val ANDROID_AAPT2_HOST_PATH = AndroidToolchain.AAPT2_HOST_PATH

        /**
         * Gradle init script that makes the offline Maven repository and the ARM64 aapt2 visible to every build.
         * `build-android-bundle.sh` ships a byte-identical copy; keep both in sync.
         */
        internal val ANDROID_GRADLE_INIT_SCRIPT = """
            def nextcraftMaven = uri('${AndroidToolchain.MAVEN_GUEST_DIR}')
            // The bundled cache holds some POMs without their jars, and Gradle does not try another repository once it
            // found the metadata. It goes first for offline builds and for NextCraft's own project templates (which it was
            // built from); any other project resolves from the real repositories and uses it only as a last resort.
            def nextcraftCacheFirst = { File root ->
                gradle.startParameter.offline || new File(root, '.nextcraft/android-project.json').isFile()
            }
            // sqlite-jdbc (Room's KSP/kapt schema checks) sees Android's /system in the chroot and loads its Android build,
            // which cannot run on Ubuntu ("invalid ELF header"). Point it at the Linux aarch64 library from the same jar. On a
            // project's first build the jar is not cached yet, so a Room project gets a pinned release from Maven Central instead.
            def nextcraftSqliteJdbc = { File projectDir ->
                if (System.getProperty('org.sqlite.lib.path')) return
                def sqliteCache = new File(gradle.gradleUserHomeDir, 'caches/modules-2/files-2.1/org.xerial/sqlite-jdbc')
                def sqliteJars = []
                (sqliteCache.listFiles() ?: []).each { v -> (v.listFiles() ?: []).each { h ->
                    (h.listFiles() ?: []).each { f -> if (f.name.startsWith('sqlite-jdbc-') && f.name.endsWith('.jar')) sqliteJars << f }
                } }
                def sqliteJar = sqliteJars.max { it.lastModified() }
                if (sqliteJar == null && !gradle.startParameter.offline) {
                    def buildFiles = ['gradle/libs.versions.toml', 'build.gradle', 'build.gradle.kts', 'app/build.gradle', 'app/build.gradle.kts']
                    def usesRoom = buildFiles.any { name ->
                        def f = new File(projectDir, name)
                        f.isFile() && f.text.contains('androidx.room')
                    }
                    def pinned = new File(gradle.gradleUserHomeDir, 'nextcraft/sqlite-jdbc-3.50.3.0.jar')
                    if (usesRoom && !pinned.isFile()) {
                        try {
                            def connection = new URL('https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.50.3.0/sqlite-jdbc-3.50.3.0.jar').openConnection()
                            connection.connectTimeout = 15000
                            connection.readTimeout = 60000
                            pinned.parentFile.mkdirs()
                            def partial = new File(pinned.path + '.part')
                            connection.inputStream.withStream { input -> partial.withOutputStream { it << input } }
                            partial.renameTo(pinned)
                        } catch (Exception ignored) {
                            // Offline or blocked: the build still runs and the next one uses the jar Gradle downloaded.
                        }
                    }
                    if (pinned.isFile()) sqliteJar = pinned
                }
                if (sqliteJar == null) return
                def sqliteDir = new File(gradle.gradleUserHomeDir, 'nextcraft/' + sqliteJar.name.replace('.jar', ''))
                def sqliteLib = new File(sqliteDir, 'libsqlitejdbc.so')
                if (!sqliteLib.isFile()) {
                    def zip = new java.util.zip.ZipFile(sqliteJar)
                    try {
                        def entry = zip.getEntry('org/sqlite/native/Linux/aarch64/libsqlitejdbc.so')
                        if (entry != null) {
                            sqliteDir.mkdirs()
                            zip.getInputStream(entry).withStream { input -> sqliteLib.withOutputStream { it << input } }
                        }
                    } finally {
                        zip.close()
                    }
                }
                if (sqliteLib.isFile()) {
                    System.setProperty('org.sqlite.lib.path', sqliteDir.path)
                    System.setProperty('org.sqlite.lib.name', 'libsqlitejdbc.so')
                }
            }
            beforeSettings { settings ->
                nextcraftSqliteJdbc(settings.settingsDir)
                settings.pluginManagement.repositories {
                    if (nextcraftCacheFirst(settings.settingsDir)) maven { url = nextcraftMaven }
                    google()
                    mavenCentral()
                    gradlePluginPortal()
                }
            }
            settingsEvaluated { settings ->
                settings.dependencyResolutionManagement.repositories {
                    maven { url = nextcraftMaven }
                }
            }
            gradle.beforeProject { project ->
                project.extensions.extraProperties.set(
                    '$ANDROID_AAPT2_PROPERTY',
                    '$ANDROID_AAPT2_GUEST_PATH'
                )
                if (nextcraftCacheFirst(project.rootDir)) {
                    project.buildscript.repositories {
                        maven { url = nextcraftMaven }
                    }
                }
            }
        """.trimIndent() + "\n"
        private val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val DSH_VERSION = "0.2.1-rc.2"
        private const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v2"
        private val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "nextcraft-core-arm64-2026.10.2.tar.zst",
            sha256 = "c2dab14270aae62b21243109a5a6e75af7d0c6821fda6264b91f973e18f3ddfb",
            compressedBytes = 74_457_771L,
        )
        private const val CLAUDE_BUNDLED_VERSION = "2.1.308"
        private const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        /** Where the npm package lands under PRoot; CLAUDE_GUEST_PATH becomes a symlink into it. */
        private const val CLAUDE_NPM_GUEST_DIR = "/usr/local/lib/claude-code"
        private const val CLAUDE_NPM_CLI_GUEST_PATH = "$CLAUDE_NPM_GUEST_DIR/node_modules/@anthropic-ai/claude-code/cli.js"
        /** Last @anthropic-ai/claude-code release shipping cli.js (2.1.113+ only download the Bun binary). */
        private const val CLAUDE_NPM_VERSION = "2.1.112"
        private val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "nextcraft-claude-arm64-2026.10.2.tar.zst",
            sha256 = "cc479f683e0e581e055d6fa2c033897c1aa6e306b6c065c9cff9e65e55c8e7a4",
            compressedBytes = 102_137_744L,
        )
        private val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "nextcraft-python-arm64-2026.10.2.tar.zst",
            sha256 = "6f4a847ffe7d36cea16c4fb3e1dc1ad2d7619ef9dff31560cfaa9b5e47a833d8",
            compressedBytes = 15_018_991L,
        )
        /** Built by scripts/runtime-bundles/build-android-bundle.sh; versions in [AndroidToolchain]. */
        private val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "nextcraft-android-arm64-2026.10.2.tar.zst",
            sha256 = "84ca85c6578fe210ca7ada5ad7dd7117e769254c1faf597609f50fe3e5ed165d",
            compressedBytes = 584_339_354L,
        )
        private val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek",
            fileName = "nextcraft-dsh-arm64-2026.10.2.tar.zst",
            sha256 = "0036adeef3f30a7b535821e7f4594c3a296ff16f7dfbbcaec6b93bc6ef44ff25",
            compressedBytes = 90_508_623L,
        )
        private val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "nextcraft-agy-arm64-2026.10.2.tar.zst",
            sha256 = "0a3e344817ada663f5cb79b0b86707fd76581cef794194d31dc360ec5b5d889b",
            compressedBytes = 48_493_274L,
        )
        private const val ENV_CLEANUP_POLL_MS = 1_000L
        /** Compressed size multiples needed to download (1x) and extract (about 3x for zstd rootfs content). */
        private const val BUNDLE_DOWNLOAD_SPACE_FACTOR = 4
        private const val BUNDLE_EXTRACT_SPACE_FACTOR = 3
        private const val MEGABYTE = 1024L * 1024L
        private const val MAX_TERMINAL_LINE = 500
        private const val MAX_COLLECTED_OUTPUT = 24_000
        private const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        private val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        private val VALID_ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

        /** Non-root supervisor (ADR-007); byte-identical copy in scripts/runtime-bundles/proot-exec.sh. */
        private val PROOT_EXEC_SCRIPT = """
            #!/system/bin/sh
            # PRoot supervisor (ADR-008): same arguments and guest layout as the chroot app's chroot-exec.sh, but the rootfs is
            # entered with the proot shipped in the APK (NEXTCRAFT_PROOT, termux/proot built with the NDK), no kernel mounts. Always run as
            # `/system/bin/sh proot-exec.sh …`: the app UID may not exec files from its data directory.
            set -e
            # The env file sourced before this script replaces PATH with the guest PATH; the host-side commands below
            # (mkdir, …) live in /system/bin. The guest PATH is set again further down, before proot starts.
            export PATH="/system/bin:/system/xbin${'$'}{PATH:+:${'$'}PATH}"

            if [ "${'$'}#" -lt 6 ]; then
                echo "Usage: proot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            ROOTFS="${'$'}1"
            HOST_WORKSPACE="${'$'}2"
            GUEST_WORKSPACE="${'$'}3"
            HOST_BRIDGE="${'$'}4"
            APP_UID="${'$'}5"
            shift 5

            if [ -z "${'$'}ROOTFS" ] || [ -z "${'$'}HOST_WORKSPACE" ] || [ -z "${'$'}GUEST_WORKSPACE" ] || [ -z "${'$'}HOST_BRIDGE" ] || [ -z "${'$'}APP_UID" ]; then
                echo "Usage: proot-exec.sh <rootfs> <host_workspace> <guest_workspace> <host_bridge> <app_uid> <cmd...>" >&2
                exit 2
            fi

            case "${'$'}GUEST_WORKSPACE" in
                *..*|/workspace/.|/workspace/*/*|*[!a-zA-Z0-9._/-]*)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
                /root|/workspace|/workspace/[a-zA-Z0-9._-]*) ;;
                *)
                    echo "Invalid guest workspace path: ${'$'}GUEST_WORKSPACE" >&2
                    exit 65
                    ;;
            esac

            case "${'$'}APP_UID" in
                ''|*[!0-9]*)
                    echo "Invalid app uid: ${'$'}APP_UID" >&2
                    exit 65
                    ;;
            esac

            PROOT="${'$'}{NEXTCRAFT_PROOT:-}"
            if [ -z "${'$'}PROOT" ] || [ ! -x "${'$'}PROOT" ]; then
                echo "PRoot binary not available: ${'$'}{PROOT:-NEXTCRAFT_PROOT unset}" >&2
                exit 127
            fi
            # proot keeps its own state (translated /proc entries, temporary files) here; it must be app-writable.
            export PROOT_TMP_DIR="${'$'}{NEXTCRAFT_PROOT_TMP:-${'$'}ROOTFS/tmp/.proot-tmp}"
            export PROOT_L2S_DIR="${'$'}{PROOT_L2S_DIR:-${'$'}ROOTFS/tmp/.proot-l2s}"
            # Enable seccomp-BPF syscall filter on Linux 4.19+ unless explicitly disabled; legacy <4.19 kernels fallback to ptrace-only.
            if [ -z "${'$'}{PROOT_NO_SECCOMP:-}" ]; then
                KVER=${'$'}(uname -r 2>/dev/null || echo "5.10.0")
                KMAJ=${'$'}{KVER%%.*}
                KREST=${'$'}{KVER#*.}
                KMIN=${'$'}{KREST%%.*}
                if [ "${'$'}{KMAJ:-5}" -lt 4 ] || { [ "${'$'}{KMAJ:-5}" -eq 4 ] && [ "${'$'}{KMIN:-19}" -lt 19 ]; }; then
                    export PROOT_NO_SECCOMP=1
                fi
            fi
            # Every guest execve goes through proot's ELF loader. By default proot extracts it into PROOT_TMP_DIR, which Android
            # (SELinux, targetSdk >= 29) refuses to exec from app data, so nothing in the guest would start. The loader ships in
            # the APK next to proot itself (libproot_loader.so) and is named here.
            if [ -n "${'$'}{NEXTCRAFT_PROOT_LOADER:-}" ]; then
                export PROOT_LOADER="${'$'}NEXTCRAFT_PROOT_LOADER"
            fi
            HOST_RESP="${'$'}HOST_BRIDGE-responses"
            SHM_DIR="${'$'}ROOTFS/tmp/.nextcraft-shm"

            mkdir -p "${'$'}ROOTFS${'$'}GUEST_WORKSPACE" "${'$'}HOST_WORKSPACE" "${'$'}HOST_BRIDGE" "${'$'}HOST_RESP" "${'$'}PROOT_TMP_DIR" "${'$'}PROOT_L2S_DIR" "${'$'}SHM_DIR" \
                "${'$'}ROOTFS/nextcraft-bridge" "${'$'}ROOTFS/pocketdev-bridge" "${'$'}ROOTFS/pocket-bridge" "${'$'}ROOTFS/pocketdev-bridge-responses" "${'$'}ROOTFS/nextcraft-bridge-responses" \
                "${'$'}ROOTFS/tmp" "${'$'}ROOTFS/dev" "${'$'}ROOTFS/proc" "${'$'}ROOTFS/sys"

            if [ ! -s "${'$'}ROOTFS/etc/resolv.conf" ]; then
                mkdir -p "${'$'}ROOTFS/etc"
                printf "nameserver 8.8.8.8\nnameserver 1.1.1.1\n" > "${'$'}ROOTFS/etc/resolv.conf"
            fi

            unset ANDROID_ROOT ANDROID_DATA ANDROID_ART_ROOT ANDROID_I18N_ROOT ANDROID_TZDATA_ROOT
            unset ANDROID_ASSETS ANDROID_BOOTLOGO ANDROID_RUNTIME_ROOT ANDROID_STORAGE
            unset ASEC_MOUNTPOINT BOOTCLASSPATH DEX2OATBOOTCLASSPATH EXTERNAL_STORAGE SYSTEMSERVERCLASSPATH
            unset LD_PRELOAD LD_LIBRARY_PATH

            export HOME="/root"
            export USER="root"
            export LOGNAME="root"
            export TERM="${'$'}{TERM:-xterm-256color}"
            export LANG="${'$'}{LANG:-C.UTF-8}"
            export TMPDIR="/tmp"
            export GLIBC_TUNABLES="glibc.pthread.rseq=0"
            # Android's app seccomp policy kills a process on io_uring_setup (SIGSYS, exit 159). libuv (Node 20+) opens
            # an io_uring by default, which took down every npm/node run; this makes it use epoll instead.
            export UV_USE_IO_URING=0
            export UV_THREADPOOL_SIZE="${'$'}{UV_THREADPOOL_SIZE:-6}"
            # Go binaries (Antigravity's agy, dsh) preempt goroutines with SIGURG many times a second. Under ptrace every
            # signal is a tracer round trip and can interrupt an in-flight connect/read, which surfaces as random
            # "dial tcp"/"i/o timeout" failures that never happen in a kernel chroot. Cooperative preemption only.
            export GODEBUG="${'$'}{GODEBUG:-asyncpreemptoff=1}"
            export NEXTCRAFT_BRIDGE_DIR="/nextcraft-bridge"
            export POCKETDEV_BRIDGE_DIR="/nextcraft-bridge"
            export NEXTCRAFT_RESPONSE_DIR="/nextcraft-bridge-responses"
            export PATH="${'$'}{PATH:-/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin}"
            case ":${'$'}PATH:" in
                *:/root/.local/bin:*) ;;
                *) export PATH="/opt/nextcraft/bin:/root/.local/bin:${'$'}PATH" ;;
            esac

            BASE_PATH="/opt/nextcraft/bin:/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
            if [ -d "${'$'}ROOTFS/root/android-sdk" ]; then
                # Keep in sync with AndroidToolchain.kt.
                export ANDROID_HOME="${'$'}{ANDROID_HOME:-/root/android-sdk}"
                export ANDROID_SDK_ROOT="${'$'}{ANDROID_SDK_ROOT:-/root/android-sdk}"
                export GRADLE_HOME="${'$'}{GRADLE_HOME:-/opt/gradle/gradle-9.8.1}"
                export GRADLE_USER_HOME="${'$'}{GRADLE_USER_HOME:-/root/.gradle}"
                export ORG_GRADLE_PROJECT_android_aapt2FromMavenOverride="/root/android-sdk/build-tools/36.0.0/aapt2"
                export PATH="/opt/gradle/gradle-9.8.1/bin:/root/android-sdk/build-tools/36.0.0:/root/android-sdk/cmdline-tools/latest/bin:${'$'}BASE_PATH"
            else
                export PATH="${'$'}BASE_PATH"
            fi

            # Bindings follow `proot-distro login` (Termux), which is the proven way to run an Ubuntu rootfs on a non-root
            # phone, plus the NextCraft guest paths of chroot-exec.sh. Later bindings take precedence.
            BINDS="-b /dev -b /dev/pts -b /proc -b /sys -b ${'$'}SHM_DIR:/dev/shm -b /dev/urandom:/dev/random"
            [ -e /dev/fd ] || BINDS="${'$'}BINDS -b /proc/self/fd:/dev/fd"
            [ -e /dev/stdin ] || BINDS="${'$'}BINDS -b /proc/self/fd/0:/dev/stdin"
            [ -e /dev/stdout ] || BINDS="${'$'}BINDS -b /proc/self/fd/1:/dev/stdout"
            [ -e /dev/stderr ] || BINDS="${'$'}BINDS -b /proc/self/fd/2:/dev/stderr"
            # Android hides several /proc and /sys entries from apps; programs that read them (nproc, Java, Node, apt)
            # get a plausible static copy instead of EACCES. Only unreadable entries are replaced.
            SYSDATA="${'$'}ROOTFS/.nextcraft-sysdata"
            mkdir -p "${'$'}SYSDATA/sys_empty"
            fake() {
                # ${'$'}1 host path, ${'$'}2 file name under SYSDATA, ${'$'}3 content
                if [ ! -r "${'$'}1" ]; then
                    [ -s "${'$'}SYSDATA/${'$'}2" ] || printf '%s\n' "${'$'}3" > "${'$'}SYSDATA/${'$'}2"
                    BINDS="${'$'}BINDS -b ${'$'}SYSDATA/${'$'}2:${'$'}1"
                fi
            }
            fake /proc/loadavg loadavg "0.12 0.07 0.02 2/165 765"
            fake /proc/stat stat "cpu  1000 0 1000 100000 0 0 0 0 0 0
            cpu0 1000 0 1000 100000 0 0 0 0 0 0
            intr 0
            ctxt 0
            btime 1700000000
            processes 1000
            procs_running 1
            procs_blocked 0"
            fake /proc/uptime uptime "1000.00 1000.00"
            fake /proc/version version "Linux version 6.6.0-android (proot@nextcraft) (gcc (GCC) 13.3.0, GNU ld (GNU Binutils) 2.42) #1 SMP PREEMPT"
            fake /proc/vmstat vmstat "nr_free_pages 100000
            nr_zone_inactive_anon 0
            nr_zone_active_anon 0
            pgpgin 0
            pgpgout 0
            pswpin 0
            pswpout 0"
            fake /proc/sys/kernel/cap_last_cap cap_last_cap "40"
            fake /proc/sys/fs/inotify/max_user_watches max_user_watches "4096"
            fake /proc/sys/kernel/overflowuid overflowuid "65534"
            fake /proc/sys/kernel/overflowgid overflowgid "65534"
            [ -r /sys/fs/selinux ] && [ ! -r /sys/fs/selinux/enforce ] && BINDS="${'$'}BINDS -b ${'$'}SYSDATA/sys_empty:/sys/fs/selinux"
            for bionic_dir in /system /apex /vendor /product /system_ext /odm; do
                [ -d "${'$'}bionic_dir" ] && BINDS="${'$'}BINDS -b ${'$'}bionic_dir"
            done
            # Shared storage as proot-distro binds it; readable only when Android granted the app storage access.
            for storage_dir in /storage /sdcard /mnt/sdcard; do
                [ -d "${'$'}storage_dir" ] && [ -r "${'$'}storage_dir" ] && BINDS="${'$'}BINDS -b ${'$'}storage_dir"
            done
            # /sdcard is a symlink chain the app may not traverse; the real directory is readable with All files access.
            if [ ! -r /sdcard ] && [ -d /storage/emulated/0 ] && [ -r /storage/emulated/0 ]; then
                BINDS="${'$'}BINDS -b /storage/emulated/0:/sdcard"
            fi
            # The bind list is word-split on purpose (one -b per path); a path with whitespace would be read as the command.
case "${'$'}ROOTFS${'$'}HOST_WORKSPACE${'$'}GUEST_WORKSPACE${'$'}HOST_BRIDGE" in
    *[[:space:]]*) echo "proot-exec: paths must not contain whitespace" >&2; exit 65 ;;
esac
if [ "${'$'}HOST_WORKSPACE" != "${'$'}ROOTFS${'$'}GUEST_WORKSPACE" ]; then
                BINDS="${'$'}BINDS -b ${'$'}HOST_WORKSPACE:${'$'}GUEST_WORKSPACE"
            fi
            BINDS="${'$'}BINDS -b ${'$'}HOST_BRIDGE:/nextcraft-bridge -b ${'$'}HOST_BRIDGE:/pocketdev-bridge -b ${'$'}HOST_BRIDGE:/pocket-bridge -b ${'$'}HOST_RESP:/pocketdev-bridge-responses -b ${'$'}HOST_RESP:/nextcraft-bridge-responses"

            KERNEL_OPT=""
            if [ -n "${'$'}{NEXTCRAFT_KERNEL_RELEASE:-}" ]; then
                KERNEL_OPT="-k ${'$'}NEXTCRAFT_KERNEL_RELEASE"
            fi

            # Same extension set as proot-distro: --kill-on-exit ends every guest process with proot (a cancelled build
            # never lingers), --link2symlink emulates hard links, --sysvipc emulates SysV IPC that Android forbids,
            # -L fixes lstat sizes for dpkg. -0 makes the guest see uid 0 (apt, npm -g).
            # shellcheck disable=SC2086
            exec "${'$'}PROOT" --kill-on-exit --link2symlink --sysvipc -L -0 ${'$'}KERNEL_OPT -r "${'$'}ROOTFS" \
                ${'$'}BINDS \
                -w "${'$'}GUEST_WORKSPACE" \
                /usr/bin/env GUEST_WORKSPACE="${'$'}GUEST_WORKSPACE" /bin/sh -c 'cd "${'$'}GUEST_WORKSPACE" && exec "${'$'}@"' -- "${'$'}@"
        """.trimIndent() + "\n"
    }
}

internal data class GitConfigEntry(
    val section: String,
    val key: String,
    val value: String,
    val replace: Boolean = false,
    /** For multi-valued keys (safe.directory): add the value unless that exact value is already there. */
    val multiValue: Boolean = false,
)

/**
 * Adds [entries] to a git config text. An entry whose key already exists in its section is kept as the user set it,
 * unless [GitConfigEntry.replace]. Sections are matched by exact `[name]` header (no subsections are needed here).
 */
internal fun mergeGitConfig(existing: String, entries: List<GitConfigEntry>): String {
    val lines = existing.lines().dropLastWhile { it.isBlank() }.toMutableList()
    fun sectionOf(line: String) = Regex("^\\s*\\[\\s*([A-Za-z0-9.-]+)\\s*]\\s*$").find(line)?.groupValues?.get(1)?.lowercase()
    fun isHeader(line: String) = Regex("^\\s*\\[[^\\]]*]").containsMatchIn(line)
    fun keyOf(line: String) = Regex("^\\s*([A-Za-z][A-Za-z0-9-]*)\\s*(=|$)").find(line)?.groupValues?.get(1)?.lowercase()
    for (entry in entries) {
        val wanted = entry.section.lowercase()
        val start = lines.indexOfFirst { sectionOf(it) == wanted }
        val formatted = "    ${entry.key} = ${entry.value}"
        if (start < 0) {
            lines += "[${entry.section}]"
            lines += formatted
            continue
        }
        var end = start + 1
        // Any header ends the section, including subsections such as [credential "https://github.com"], so an entry is
        // never appended inside (or matched against) another section's keys.
        while (end < lines.size && !isHeader(lines[end])) end++
        val sameKey = (start + 1 until end).filter { keyOf(lines[it]) == entry.key.lowercase() }
        if (entry.multiValue) {
            val present = sameKey.any { lines[it].substringAfter('=', "").trim() == entry.value }
            if (!present) lines.add(end, formatted)
            continue
        }
        val existingIndex = sameKey.firstOrNull()
        when {
            existingIndex == null -> lines.add(end, formatted)
            entry.replace -> lines[existingIndex] = formatted
        }
    }
    return lines.joinToString("\n") + "\n"
}
