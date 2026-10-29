package com.nextcraft.forge.runtime

import android.os.Process as AndroidProcess
import android.system.Os
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.json.JSONObject

/**
 * Installs one coding agent on demand. Safe to call again: an already-installed
 * agent returns immediately without network access.
 */
suspend fun RuntimeInstaller.ensureAgentInstalled(
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
fun RuntimeInstaller.removeAgent(agent: AgentKind) {
    installedRuntime()
    when (agent) {
        AgentKind.CLAUDE_CODE -> {
            removePath(File(rootfs, RuntimeInstaller.CLAUDE_GUEST_PATH.removePrefix("/")))
            listOf(claudeMarker, bundledClaudeMarker).forEach { it.deleteMarker() }
        }
        AgentKind.DEEPSEEK_HARNESS -> {
            removePath(File(rootfs, "usr/local/bin/dsh"))
            removePath(File(rootfs, "usr/local/lib/dsh"))
            listOf(dshMarker, dshAndroidCompatibilityMarker).forEach { it.deleteMarker() }
        }
        AgentKind.ANTIGRAVITY -> {
            removePath(File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/")))
            agyMarker.deleteMarker()
        }
    }
    check(!isAgentInstalled(agent)) { "Could not fully remove ${agent.title}" }
}

/**
 * Whether the guest file at [guestPath] is executable. `File.canExecute()` follows symlinks on the host, where an
 * absolute guest target (the PRoot npm install links `/usr/local/bin/claude` to `/usr/local/lib/claude-code/...`) does
 * not exist, so an installed agent looked missing. Each hop is re-rooted inside [rootfs] instead, and a link that
 * leaves the rootfs, loops or dangles counts as not executable.
 */
internal fun isGuestExecutable(rootfs: File, guestPath: String): Boolean {
    val rootPath = rootfs.canonicalFile.toPath()
    var current = rootPath.resolve(guestPath.removePrefix("/")).normalize()
    if (!current.startsWith(rootPath)) return false
    var hops = 0
    while (java.nio.file.Files.isSymbolicLink(current)) {
        if (++hops > 16) return false
        val parent = current.parent ?: return false
        current = try {
            RuntimeInstaller.resolveSafeSymlinkTarget(
                rootPath = rootPath,
                parentDir = parent,
                linkName = java.nio.file.Files.readSymbolicLink(current).toString(),
                errorMessage = "Guest symlink escapes runtime",
            )
        } catch (_: IllegalArgumentException) {
            return false
        }
    }
    return current.toFile().canExecute()
}

fun RuntimeInstaller.isAgentInstalled(agent: AgentKind): Boolean {
    return when (agent) {
        AgentKind.CLAUDE_CODE -> {
            migrateLegacyClaudeMarker()
            isInstalled() && isGuestExecutable(rootfs, RuntimeInstaller.CLAUDE_GUEST_PATH) &&
                !claudeMarker.readTextOrNull().isNullOrBlank()
        }
        AgentKind.DEEPSEEK_HARNESS -> isInstalled() &&
            File(rootfs, "usr/local/lib/dsh/node_modules/.bin/dsh").isFile &&
            isSupportedDshVersion(dshMarker.readTextOrNull().orEmpty())
        AgentKind.ANTIGRAVITY -> isInstalled() &&
            File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/")).canExecute() &&
            !agyMarker.readTextOrNull().isNullOrBlank()
    }
}

val RuntimeInstaller.dshVersion: String get() = dshMarker.readTextOrNull().orEmpty()

val RuntimeInstaller.claudeVersion: String
    get() {
        migrateLegacyClaudeMarker()
        return claudeMarker.readTextOrNull().orEmpty()
    }

val RuntimeInstaller.agyVersion: String get() = agyMarker.readTextOrNull().orEmpty()

val RuntimeInstaller.githubCliVersion: String get() = githubCliMarker.readTextOrNull().orEmpty()

fun RuntimeInstaller.isGitHubCliInstalled(): Boolean = isInstalled() &&
    File(rootfs, RuntimeInstaller.GITHUB_CLI_GUEST_PATH.removePrefix("/")).canExecute() &&
    githubCliMarker.readTextOrNull() == RuntimeInstaller.GITHUB_CLI_VERSION

/** Installs GitHub's official ARM64 CLI on demand; it is not bundled in the APK. */
suspend fun RuntimeInstaller.ensureGitHubCliInstalled(onProgress: suspend (RuntimeInstallProgress) -> Unit) {
    if (isGitHubCliInstalled()) return
    writeResolver()
    downloads.mkdirs()
    val downloaded = File(downloads, "gh-${RuntimeInstaller.GITHUB_CLI_VERSION}-linux-arm64.tar.gz")
    onProgress(RuntimeInstallProgress("Downloading GitHub CLI…", 0.05f))
    val githubDownloadLabel = "Downloading GitHub CLI ${RuntimeInstaller.GITHUB_CLI_VERSION}…"
    downloadVerified(RuntimeInstaller.GITHUB_CLI_RELEASE_URL, downloaded, RuntimeInstaller.GITHUB_CLI_RELEASE_SHA256) { bytes, total ->
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
    onProgress(RuntimeInstallProgress("Installing GitHub CLI ${RuntimeInstaller.GITHUB_CLI_VERSION}…", 0.85f, indeterminate = true))
    val destination = File(rootfs, RuntimeInstaller.GITHUB_CLI_GUEST_PATH.removePrefix("/"))
    destination.parentFile?.mkdirs()
    var found = false
    try {
        @Suppress("DEPRECATION")
        TarArchiveInputStream(GzipCompressorInputStream(BufferedInputStream(downloaded.inputStream()))).use { archive ->
            var entry = archive.nextTarEntry
            while (entry != null) {
                if (entry.isFile && entry.name.removePrefix("./").endsWith("/bin/gh")) {
                    val staged = File(destination.parentFile, ".gh-${RuntimeInstaller.GITHUB_CLI_VERSION}.installing")
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
        command = "${RuntimeInstaller.GITHUB_CLI_GUEST_PATH} --version",
        failureMessage = "Failed to verify GitHub CLI",
    )
    githubCliMarker.writeMarker(RuntimeInstaller.GITHUB_CLI_VERSION)
    check(isGitHubCliInstalled()) { "GitHub CLI installation is incomplete" }
    onProgress(RuntimeInstallProgress("GitHub CLI is ready", 1f))
}

/**
 * Provisions Git identity (`~/.gitconfig`) and root-only (`0600`) Git/GitHub CLI credentials
 * (`~/.git-credentials` and `~/.config/gh/hosts.yml`) inside the guest rootfs when [persistCredentials] is true.
 */
fun RuntimeInstaller.configureGitHubAccess(token: String, login: String, persistCredentials: Boolean = true) {
    val cleanToken = token.trim().replace("\r", "").replace("\n", "")
    if (cleanToken.isEmpty()) return
    val cleanLogin = login.trim().replace("\r", "").replace("\n", "").ifEmpty { "x-access-token" }
    val rootHome = File(rootfs, "root").apply { mkdirs() }
    if (persistCredentials) {
        val gitCredentials = File(rootHome, ".git-credentials")
        // git/gh rewrite these as root inside the chroot; reclaim ownership instead of failing with EACCES.
        writeRootfsFileAtomically(gitCredentials, "https://$cleanLogin:$cleanToken@github.com\n")
        runCatching { Os.chmod(gitCredentials.absolutePath, 384) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0600
    } else {
        clearGitHubAccess()
    }

    // Merge instead of replacing: the user's own name, email, aliases and editor settings must survive connecting
    // GitHub. Only the credential helper is forced; identity and default branch are filled in when missing.
    val gitConfig = File(rootHome, ".gitconfig")
    if (gitConfig.exists() && !gitConfig.canRead()) RootfsRepair.reclaimOwnership(gitConfig, AndroidProcess.myUid())
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

    if (persistCredentials) {
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
}

/** Removes stored Git and GitHub CLI credentials from the guest rootfs. */
fun RuntimeInstaller.clearGitHubAccess() {
    val rootHome = File(rootfs, "root")
    listOf(File(rootHome, ".git-credentials"), File(rootHome, ".config/gh/hosts.yml")).forEach { file ->
        if (!file.exists() || file.delete()) return@forEach
        // Root-owned after git/gh rewrote it: take it back, otherwise "Disconnect" would leave the token on disk.
        RootfsRepair.reclaimOwnership(file, AndroidProcess.myUid())
        if (!file.delete() && file.exists()) Log.w("RuntimeInstaller", "Could not remove ${file.name}")
    }
}

/**
 * True when `claude auth login` left a usable login (access + refresh token) in the guest. The file is written by
 * root inside the chroot, so the app UID usually cannot read it; ask root.
 */
fun RuntimeInstaller.hasClaudeLoginCredential(): Boolean {
    val file = File(rootfs, "root/.claude/.credentials.json")
    // PRoot (no root): the guest runs as the app UID, so the file is ours and can be read directly. Without this the
    // `su` call below fails on such phones and the app reports "signed out" although the login is there.
    val direct = runCatching { if (file.isFile && file.canRead()) file.readText().contains("\"refreshToken\"") else null }.getOrNull()
    if (direct != null) return direct
    val quoted = shellSingleQuote(file.absolutePath)
    return runCatching {
        val process = ProcessBuilder("su", "-c", "grep -q '\"refreshToken\"' $quoted").start()
        if (!process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            false
        } else {
            process.exitValue() == 0
        }
    }.getOrDefault(false)
}

/** Versions recorded after each real agent binary has been installed and verified. */
fun RuntimeInstaller.installedAgentVersions(): Map<AgentKind, String> = buildMap {
    migrateLegacyClaudeMarker()
    claudeMarker.readTextOrNull()
        ?.trim()
        ?.takeIf { isAgentInstalled(AgentKind.CLAUDE_CODE) && it.matches(RuntimeInstaller.CLAUDE_VERSION_PATTERN) }
        ?.let { put(AgentKind.CLAUDE_CODE, it) }

    dshMarker.readTextOrNull()
        ?.trim()
        ?.takeIf { isAgentInstalled(AgentKind.DEEPSEEK_HARNESS) }
        ?.let { put(AgentKind.DEEPSEEK_HARNESS, it) }

    agyMarker.readTextOrNull()
        ?.trim()
        ?.takeIf { it.isNotEmpty() && File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/")).canExecute() }
        ?.let { put(AgentKind.ANTIGRAVITY, it) }
}

/** Checks each installed agent against its own authoritative release source. */
suspend fun RuntimeInstaller.checkAgentUpdates(): Map<AgentKind, AgentUpdateInfo> {
    val installed = installedAgentVersions()
    return buildMap {
        installed[AgentKind.CLAUDE_CODE]?.let { current ->
            runCatching {
                JSONObject(fetchText("https://registry.npmjs.org/@anthropic-ai/claude-code/latest")).getString("version")
            }.getOrNull()?.takeIf { isVersionNewer(it, current) }?.let { latest ->
                put(AgentKind.CLAUDE_CODE, AgentUpdateInfo(current, latest))
            }
        }
        installed[AgentKind.DEEPSEEK_HARNESS]?.let { current ->
            RuntimeInstaller.DSH_VERSION.takeIf { isVersionNewer(it, current) }?.let { compatible ->
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

suspend fun RuntimeInstaller.updateAgent(
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
internal suspend fun RuntimeInstaller.replaceBinaryWithRollback(
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

internal suspend fun RuntimeInstaller.updateClaude(
    runtime: InstalledRuntime,
    expectedVersion: String,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    check(RuntimeEngines.current() != RuntimeEngine.PROOT) {
        "Claude Code stays at ${RuntimeInstaller.CLAUDE_NPM_VERSION} on the PRoot engine: newer releases are Bun binaries that Android's app sandbox cannot run without root."
    }
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
    val claude = File(rootfs, RuntimeInstaller.CLAUDE_GUEST_PATH.removePrefix("/"))
    claude.parentFile?.mkdirs()
    claude.parentFile?.let { if (!it.canWrite()) RootfsRepair.reclaimOwnership(it, AndroidProcess.myUid(), recursive = false) }
    val staged = File(claude.parentFile, ".claude-$latest.installing")
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
            verify = { verifyGuest(runtime.chrootExec, "${RuntimeInstaller.CLAUDE_GUEST_PATH} --version", "Failed to verify updated Claude Code") },
        )
    } finally {
        downloaded.delete()
    }
    claudeMarker.writeMarker(latest)
}

internal suspend fun RuntimeInstaller.updateAgy(
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
    val destination = File(rootfs, RuntimeInstaller.AGY_GUEST_PATH.removePrefix("/"))
    destination.parentFile?.mkdirs()
    destination.parentFile?.let { if (!it.canWrite()) RootfsRepair.reclaimOwnership(it, AndroidProcess.myUid(), recursive = false) }
    var found = false
    try {
      replaceBinaryWithRollback(
        target = destination,
        verify = { verifyGuest(runtime.chrootExec, "${RuntimeInstaller.AGY_GUEST_PATH} --version", "Failed to verify updated Antigravity CLI") },
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

internal suspend fun RuntimeInstaller.updateDsh(
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
            // npm stops with "double-loading config /dev/null as global, previously loaded as user".
            "export npm_config_userconfig=\"${'$'}next/.npmrc-user\" npm_config_globalconfig=\"${'$'}next/.npmrc-global\"; " +
            "cd \"${'$'}next\"; : > .npmrc-user; : > .npmrc-global; npm init -y >/dev/null; " +
            "npm install --omit=dev --no-audit --no-fund @deepseek-ai/dsh@$quotedVersion; " +
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
    dshAndroidCompatibilityMarker.writeMarker(RuntimeInstaller.DSH_ANDROID_COMPATIBILITY_VERSION)
}

internal fun RuntimeInstaller.fetchAgyManifest(): JSONObject = JSONObject(
    fetchText("https://antigravity-cli-auto-updater-974169037036.us-central1.run.app/manifests/linux_arm64.json"),
)

internal fun RuntimeInstaller.isVersionNewer(candidate: String, current: String): Boolean = isAgentVersionNewer(candidate, current)

internal fun RuntimeInstaller.migrateLegacyClaudeMarker() {
    if (claudeMarker.isFile) return
    val claude = File(rootfs, RuntimeInstaller.CLAUDE_GUEST_PATH.removePrefix("/"))
    if (!claude.isFile) return
    val legacyVersion = sequenceOf(
        coreReadyMarker.readTextOrNull(),
        bundledClaudeMarker.readTextOrNull(),
    ).mapNotNull { it?.trim() }.firstOrNull { it.matches(RuntimeInstaller.CLAUDE_VERSION_PATTERN) } ?: return
    claudeMarker.writeMarker(legacyVersion)
}

internal fun RuntimeInstaller.isSupportedCoreToolsVersion(): Boolean = coreToolsMarker.readTextOrNull() in setOf(
    RuntimeInstaller.CORE_TOOLS_VERSION,
    RuntimeInstaller.LEGACY_CORE_TOOLS_VERSION,
    RuntimeInstaller.PREVIOUS_CORE_TOOLS_VERSION,
    RuntimeInstaller.NEXTCRAFT_CORE_TOOLS_VERSION,
    RuntimeInstaller.NEXTCRAFT_CORE_TOOLS_VERSION_2,
)

/** The Python 3.12 overlay needs glibc 2.39; an Ubuntu 20.04 runtime has to be reinstalled on 24.04 first. */
internal fun RuntimeInstaller.pythonBundle(): RuntimeBundle {
    check(rootfsMarker.readTextOrNull() != RuntimeInstaller.LEGACY_ROOTFS_VERSION) { RuntimeInstaller.LEGACY_ROOTFS_PYTHON_MESSAGE }
    return RuntimeInstaller.PYTHON_BUNDLE
}

internal fun RuntimeInstaller.isSupportedRootfsVersion(version: String?): Boolean = version in setOf(
    RuntimeInstaller.ROOTFS_VERSION,
    RuntimeInstaller.LEGACY_ROOTFS_VERSION,
)

internal suspend fun RuntimeInstaller.ensureClaudeInstalled(
    chrootExec: File,
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    migrateLegacyClaudeMarker()
    if (isAgentInstalled(AgentKind.CLAUDE_CODE)) return
    if (RuntimeEngines.current() == RuntimeEngine.PROOT) {
        installClaudeViaNpm(chrootExec, fraction, onProgress)
        return
    }
    installRuntimeOverlay(
        bundle = RuntimeInstaller.CLAUDE_BUNDLE,
        message = "Installing Claude Code ${RuntimeInstaller.CLAUDE_BUNDLED_VERSION}…",
        from = fraction,
        to = 0.995f,
        onProgress = onProgress,
    )
    verifyGuest(chrootExec, "${RuntimeInstaller.CLAUDE_GUEST_PATH} --version", "Failed to verify Claude Code")
    migrateLegacyClaudeMarker()
    require(claudeMarker.readTextOrNull() == RuntimeInstaller.CLAUDE_BUNDLED_VERSION) {
        "The Claude Code runtime bundle is incomplete"
    }
    upgradeAfterBundledInstall(AgentKind.CLAUDE_CODE, onProgress)
}

/**
 * Non-root devices (PRoot): Android's seccomp policy for apps kills the Bun-built `claude` binary with SIGSYS
 * (Bun registers `rseq` itself, which the policy forbids; glibc programs survive via GLIBC_TUNABLES). The last
 * npm release that still ships cli.js for plain Node is installed instead, at the same guest path and marker.
 */
internal suspend fun RuntimeInstaller.installClaudeViaNpm(
    chrootExec: File,
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    val version = RuntimeInstaller.CLAUDE_NPM_VERSION
    check(RuntimeInstaller.CLAUDE_VERSION_PATTERN.matches(version)) { "Invalid Claude Code version" }
    runGuestCommand(
        chrootExec = chrootExec,
        command = "set -e; dir=${RuntimeInstaller.CLAUDE_NPM_GUEST_DIR}; rm -rf \"${'$'}dir\"; mkdir -p \"${'$'}dir\"; cd \"${'$'}dir\"; " +
            "npm init -y >/dev/null; npm install --omit=dev --no-audit --no-fund @anthropic-ai/claude-code@$version; " +
            // Relative on purpose: /usr/local/bin -> ../lib/claude-code resolves on the host too (see isGuestExecutable).
            "ln -sfn ../lib/claude-code/node_modules/.bin/claude ${RuntimeInstaller.CLAUDE_GUEST_PATH}",
        displayCommand = "npm install @anthropic-ai/claude-code@$version",
        fraction = fraction,
        timeoutMs = 20 * 60 * 1_000L,
        onProgress = onProgress,
        failureMessage = "Failed to install Claude Code from npm",
    )
    verifyGuest(chrootExec, "${RuntimeInstaller.CLAUDE_GUEST_PATH} --version", "Failed to verify Claude Code")
    claudeMarker.writeText(version)
    onProgress(RuntimeInstallProgress("Installed Claude Code $version (npm, PRoot)", 0.995f))
}

internal suspend fun RuntimeInstaller.ensureAgyInstalled(
    chrootExec: File,
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    if (isAgentInstalled(AgentKind.ANTIGRAVITY)) return
    installRuntimeOverlay(
        bundle = RuntimeInstaller.AGY_BUNDLE,
        message = "Installing Antigravity CLI ${RuntimeInstaller.AGY_VERSION}…",
        from = fraction,
        to = 0.995f,
        onProgress = onProgress,
        forceEmbedded = isAssetAvailable("runtime/${RuntimeInstaller.AGY_BUNDLE.fileName}"),
    )
    verifyGuest(chrootExec, "${RuntimeInstaller.AGY_GUEST_PATH} --version", "Failed to verify Antigravity CLI")
    agyMarker.writeMarker(RuntimeInstaller.AGY_VERSION)
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
internal suspend fun RuntimeInstaller.upgradeAfterBundledInstall(
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

internal suspend fun RuntimeInstaller.ensureDshInstalled(
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
            bundle = RuntimeInstaller.DSH_BUNDLE,
            message = "Installing DeepSeek ${RuntimeInstaller.DSH_VERSION}…",
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
fun RuntimeInstaller.ensureDshAndroidCompatibility() {
    if (!isAgentInstalled(AgentKind.DEEPSEEK_HARNESS)) return
    val dshRoot = File(rootfs, "usr/local/lib/dsh")
    try {
        applyDshAndroidCompatibility(dshRoot, dshMarker.readTextOrNull().orEmpty())
    } catch (e: java.io.IOException) {
        // A tree installed by an earlier root npm is root-owned; take it back and retry once.
        if (!RootfsRepair.reclaimOwnership(dshRoot, AndroidProcess.myUid())) throw e
        applyDshAndroidCompatibility(dshRoot, dshMarker.readTextOrNull().orEmpty())
    }
    dshAndroidCompatibilityMarker.writeMarker(RuntimeInstaller.DSH_ANDROID_COMPATIBILITY_VERSION)
}

internal fun RuntimeInstaller.applyDshAndroidCompatibility(dshRoot: File, version: String) {
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

internal fun RuntimeInstaller.patchDshHardLinkPublication(file: File, callBefore: String, callAfter: String) {
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

