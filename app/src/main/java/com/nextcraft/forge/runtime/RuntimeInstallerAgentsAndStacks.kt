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

fun RuntimeInstaller.isAgentInstalled(agent: AgentKind): Boolean {
    return when (agent) {
        AgentKind.CLAUDE_CODE -> {
            migrateLegacyClaudeMarker()
            isInstalled() && File(rootfs, RuntimeInstaller.CLAUDE_GUEST_PATH.removePrefix("/")).canExecute() &&
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
            "ln -sfn ${RuntimeInstaller.CLAUDE_NPM_GUEST_DIR}/node_modules/.bin/claude ${RuntimeInstaller.CLAUDE_GUEST_PATH}",
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

/**
 * Installs one optional development stack inside Ubuntu Root Chroot.
 */
suspend fun RuntimeInstaller.ensureStackInstalled(
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
suspend fun RuntimeInstaller.removeStack(
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

internal fun RuntimeInstaller.removePythonStack() {
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

internal fun RuntimeInstaller.dpkgOwnedPaths(): Set<String> = runCatching {
    File(rootfs, "var/lib/dpkg/info").listFiles { f -> f.name.endsWith(".list") }.orEmpty()
        .flatMapTo(HashSet()) { it.readLines() }
}.getOrDefault(emptySet())

/** Removes the current overlay layout and every previous one, so an upgrade does not leave an old JDK behind. */
internal fun RuntimeInstaller.removeAndroidStack() {
    AndroidToolchain.REMOVABLE_HOST_PATHS.forEach { removePath(File(rootfs, it)) }
    removeAndroidGradleProperty()
}

internal fun RuntimeInstaller.removeAndroidGradleProperty() {
    val properties = File(rootfs, "root/.gradle/gradle.properties")
    if (!properties.isFile) return
    val remaining = properties.readLines().filterNot(this::isManagedAndroidGradleProperty)
    if (remaining.isEmpty()) {
        properties.delete()
    } else {
        properties.writeText(remaining.joinToString("\n").trimEnd() + "\n")
    }
}

internal fun RuntimeInstaller.removePath(file: File) {
    val isLink = java.nio.file.Files.isSymbolicLink(file.toPath())
    if (!file.exists() && !isLink) return
    val removed = if (file.isDirectory && !isLink) file.deleteRecursivelyNoFollow() else file.delete()
    // Caches and packages created inside the chroot (Gradle, pip, Composer) are root-owned, so the app UID
    // cannot delete them; fall back to a root rm, which first checks that nothing is mounted below the path.
    if (!removed) {
        check(RootfsRepair.safeDeleteRecursively(file)) { "Could not remove ${file.name}" }
    }
}

internal suspend fun RuntimeInstaller.applyStack(
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
internal suspend fun RuntimeInstaller.installComposer(
    chrootExec: File,
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    val composer = File(rootfs, "usr/local/bin/composer")
    if (composer.isFile) return
    onProgress(RuntimeInstallProgress("Downloading Composer…", fraction))
    downloads.mkdirs()
    val staged = File(downloads, "composer.phar")
    val checksum = RuntimeInstaller.COMPOSER_SHA256
    downloadVerified(
        "https://getcomposer.org/download/${RuntimeInstaller.COMPOSER_VERSION}/composer.phar",
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

internal suspend fun RuntimeInstaller.installAndroidToolchain(
    chrootExec: File,
    from: Float,
    to: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    val marker = File(rootfs, "root/.nextcraft-android-tools-version")
    if (marker.readTextOrNull() != RuntimeInstaller.ANDROID_TOOLS_VERSION || !hasAndroidToolchainFiles(rootfs)) {
        // An older layout (different JDK/Gradle directories) would otherwise stay behind next to the new one.
        if (marker.readTextOrNull() != RuntimeInstaller.ANDROID_TOOLS_VERSION && marker.isFile) removeAndroidStack()
        installRuntimeOverlay(
            RuntimeInstaller.ANDROID_BUNDLE,
            "Installing Android SDK ${AndroidToolchain.COMPILE_SDK}, Build Tools ${AndroidToolchain.BUILD_TOOLS_VERSION}, " +
                "Gradle ${AndroidToolchain.GRADLE_VERSION}, and Maven cache…",
            from,
            to,
            onProgress,
        )
        makeAndroidToolsExecutable(rootfs)
        marker.writeMarker(RuntimeInstaller.ANDROID_TOOLS_VERSION)
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

internal fun RuntimeInstaller.hasAndroidToolchainFiles(runtimeRootfs: File): Boolean =
    File(runtimeRootfs, AndroidToolchain.ANDROID_JAR_HOST_PATH).isFile &&
        File(runtimeRootfs, AndroidToolchain.AAPT2_HOST_PATH).isFile &&
        File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH).isFile &&
        File(runtimeRootfs, AndroidToolchain.MAVEN_HOST_DIR).let { it.isDirectory && !it.list().isNullOrEmpty() }

/** Unpack progress: bytes are the compressed archive consumed, so the UI can show MB/s like a download. */
internal fun RuntimeInstaller.unpackProgress(message: String, from: Float, to: Float, done: Long, total: Long) =
    RuntimeInstallProgress(
        message = message,
        fraction = from + (to - from) * (if (total > 0L) done.toFloat() / total else 0f),
        downloadedBytes = done,
        totalBytes = total.takeIf { it > 0L },
        event = RuntimeInstallEvent.DOWNLOAD,
        unpacking = true,
    )

internal suspend fun RuntimeInstaller.installRuntimeOverlay(
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
        stripMacosMetadataArtifacts(rootfs, skipTopLevel = RuntimeInstaller.BIND_MOUNTED_TOP_LEVEL)
    } finally {
        if (archive.parentFile == downloads) archive.delete()
    }
    onProgress(RuntimeInstallProgress("Installed ${bundle.label} runtime bundle", to))
}

internal fun RuntimeInstaller.stripMacosMetadataArtifacts(root: File, skipTopLevel: Set<String> = emptySet()) {
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

internal fun RuntimeInstaller.isAssetAvailable(assetPath: String): Boolean =
    runCatching {
        context.assets.open(assetPath).close()
        true
    }.getOrDefault(false)

/** Fails early with an actionable message instead of an opaque IOException halfway through extraction. */
internal fun RuntimeInstaller.requireFreeSpaceForBundle(bundle: RuntimeBundle, alreadyDownloaded: Boolean) {
    val free = context.filesDir.usableSpace
    if (free <= 0L) return // unknown (some filesystems report 0); let the real write decide
    val factor = if (alreadyDownloaded) RuntimeInstaller.BUNDLE_EXTRACT_SPACE_FACTOR else RuntimeInstaller.BUNDLE_DOWNLOAD_SPACE_FACTOR
    val needed = bundle.compressedBytes * factor
    check(free >= needed) {
        "Not enough free storage for the ${bundle.label} bundle: about ${needed / RuntimeInstaller.MEGABYTE} MB needed, " +
            "${free / RuntimeInstaller.MEGABYTE} MB available. Free some space and retry."
    }
}

internal suspend fun RuntimeInstaller.obtainRuntimeBundle(
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

internal fun RuntimeInstaller.makeAndroidToolsExecutable(runtimeRootfs: File) {
    val buildTools = File(runtimeRootfs, AndroidToolchain.BUILD_TOOLS_HOST_DIR)
    val gradleLauncher = File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH)
    listOf("aapt", "aapt2", "aidl", "apksigner", "d8", "dexdump", "split-select", "zipalign")
        .map { File(buildTools, it) }
        .plus(gradleLauncher)
        .filter(File::isFile)
        .forEach { Os.chmod(it.absolutePath, 0b111101101) }
    patchGradleLauncherStdin(gradleLauncher)
}

internal fun RuntimeInstaller.patchGradleLauncherStdin(gradleLauncher: File) {
    if (!gradleLauncher.isFile) return
    runCatching {
        val original = gradleLauncher.readText()
        if (!original.contains("exec \"\$JAVACMD\" \"\$@\" < /dev/null") && original.contains("exec \"\$JAVACMD\" \"\$@\"")) {
            gradleLauncher.writeText(original.replace("exec \"\$JAVACMD\" \"\$@\"", "exec \"\$JAVACMD\" \"\$@\" < /dev/null"))
            Os.chmod(gradleLauncher.absolutePath, 0b111101101)
        }
    }.onFailure { ErrorLog.swallowed("RuntimeInstaller", it) }
}

internal fun RuntimeInstaller.writeAndroidGradleInitScript(runtimeRootfs: File) {
    val script = File(runtimeRootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/"))
    script.parentFile?.mkdirs()
    script.writeText(RuntimeInstaller.ANDROID_GRADLE_INIT_SCRIPT)
    File(runtimeRootfs, RuntimeMarkers.LEGACY_GRADLE_INIT_SCRIPT).delete()
    patchGradleLauncherStdin(File(runtimeRootfs, AndroidToolchain.GRADLE_LAUNCHER_HOST_PATH))
}

internal fun RuntimeInstaller.deviceTotalMemoryMb(): Long {
    val info = android.app.ActivityManager.MemoryInfo()
    context.getSystemService(android.app.ActivityManager::class.java)?.getMemoryInfo(info)
    return if (info.totalMem > 0) info.totalMem / (1024 * 1024) else 4096L
}

@Synchronized
internal fun RuntimeInstaller.writeAndroidGradleConfiguration(runtimeRootfs: File) {
    val aapt2 = File(runtimeRootfs, RuntimeInstaller.ANDROID_AAPT2_HOST_PATH)
    if (!aapt2.isFile) return

    writeAndroidGradleInitScript(runtimeRootfs)
    val gradleDir = File(runtimeRootfs, "root/.gradle").apply { mkdirs() }
    val properties = File(gradleDir, "gradle.properties")
    val existingLines = properties.readTextOrNull()?.lineSequence()?.toList().orEmpty()
    val hwProfile = DeviceHardwareOptimizer.inspect(context)
    val managedLines = listOf(
        "${RuntimeInstaller.ANDROID_AAPT2_PROPERTY}=${RuntimeInstaller.ANDROID_AAPT2_GUEST_PATH}",
        "${RuntimeInstaller.GRADLE_JVMARGS_PROPERTY}=${RuntimeInstaller.gradleJvmArgs(
            totalMemoryMb = hwProfile.totalRamMb,
            swapTotalMb = hwProfile.swap.swapTotalMb,
            swapFreeMb = hwProfile.swap.swapFreeMb,
            socVendor = hwProfile.socVendor,
            socTier = hwProfile.socTier,
            performanceCores = hwProfile.topology.performanceCores,
        )}",
        "${RuntimeInstaller.KOTLIN_EXECUTION_PROPERTY}=in-process",
    ) + RuntimeInstaller.gradleSpeedProperties(
        totalMemoryMb = hwProfile.totalRamMb,
        swapTotalMb = hwProfile.swap.swapTotalMb,
        swapFreeMb = hwProfile.swap.swapFreeMb,
        socVendor = hwProfile.socVendor,
        socTier = hwProfile.socTier,
        performanceCores = hwProfile.topology.performanceCores,
    ).map { (key, value) -> "$key=$value" }
    val updatedLines = mergeManagedGradleLines(existingLines, managedLines)
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

internal fun RuntimeInstaller.isManagedAndroidGradleProperty(line: String): Boolean =
    isManagedGradleLine(line)

internal fun isManagedGradleLine(line: String): Boolean =
    RuntimeInstaller.MANAGED_ANDROID_GRADLE_PROPERTIES.any { key ->
        Regex("^\\s*${Regex.escape(key)}\\s*[:=].*$").matches(line)
    }

/** Drops previously managed lines and appends the fresh ones, so repeated writes never accumulate duplicates. */
internal fun mergeManagedGradleLines(existingLines: List<String>, managedLines: List<String>): List<String> =
    existingLines.filterNot(::isManagedGradleLine) + managedLines

fun RuntimeInstaller.migrateLegacyToolMarkers() {
    if (!File(rootfs, "usr/bin/bash").isFile) return
    if (languageToolsMarker.readTextOrNull() != RuntimeInstaller.LANGUAGE_TOOLS_VERSION) return
    if (coreToolsMarker.readTextOrNull() != RuntimeInstaller.CORE_TOOLS_VERSION) coreToolsMarker.writeMarker(RuntimeInstaller.CORE_TOOLS_VERSION)
    val state = readDevStackState()
    DevStack.entries.forEach { stack -> if (!state.containsKey(stack.name)) state[stack.name] = true }
    writeDevStackState(state)
}

fun RuntimeInstaller.installedStacks(): Set<DevStack> = readDevStackState()
    .filterValues { it }
    .keys
    .mapNotNull { name -> runCatching { DevStack.valueOf(name) }.getOrNull() }
    .filter(this::isStackInstalled)
    .toSet()

internal fun RuntimeInstaller.guestExecutableOrSymlinkExists(relativePath: String): Boolean {
    val file = File(rootfs, relativePath.removePrefix("/"))
    // lstat first: following a toolchain symlink (gcc, php) from the app is denied by SELinux and logs an AVC.
    return java.nio.file.Files.isSymbolicLink(file.toPath()) || file.exists()
}

fun RuntimeInstaller.isStackInstalled(stack: DevStack): Boolean {
    if (readDevStackState()[stack.name] != true) return false
    return when (stack) {
        DevStack.WEB -> true
        DevStack.PYTHON -> guestExecutableOrSymlinkExists("usr/bin/python3") && guestExecutableOrSymlinkExists("usr/bin/pip3")
        DevStack.CPP -> guestExecutableOrSymlinkExists("usr/bin/gcc") && guestExecutableOrSymlinkExists("usr/bin/g++")
        DevStack.PHP -> guestExecutableOrSymlinkExists("usr/bin/php") && File(rootfs, "usr/local/bin/composer").isFile
        DevStack.ANDROID ->
            RuntimeMarkers.read(File(rootfs, "root/.nextcraft-android-tools-version"))?.trim() == RuntimeInstaller.ANDROID_TOOLS_VERSION &&
                hasAndroidToolchainFiles(rootfs) &&
                File(rootfs, AndroidToolchain.GRADLE_INIT_SCRIPT_GUEST_PATH.removePrefix("/")).isFile
    }
}

internal fun RuntimeInstaller.readDevStackState(): MutableMap<String, Boolean> {
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

internal fun RuntimeInstaller.writeDevStackState(state: Map<String, Boolean>) {
    devStacksFile.parentFile?.mkdirs()
    val obj = JSONObject()
    state.forEach { (name, value) -> obj.put(name, value) }
    // A truncated file reads back as "no stacks installed" and makes the next setup reinstall everything.
    writeRootfsFileAtomically(devStacksFile, obj.toString())
    RuntimeMarkers.legacyOf(devStacksFile)?.let { runCatching { it.writeText(obj.toString()) } }
}

@Suppress("UNUSED_PARAMETER")
internal suspend fun RuntimeInstaller.installNodeIfNeeded(
    chrootExec: File,
    from: Float,
    to: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    if (File(rootfs, "usr/local/bin/node").exists()) return
    val nodeDownloadLabel = "Downloading Node.js ${RuntimeInstaller.NODE_VERSION}…"
    onProgress(RuntimeInstallProgress(nodeDownloadLabel, from))
    downloads.mkdirs()
    val nodeFileName = "node-${RuntimeInstaller.NODE_VERSION}-linux-arm64.tar.gz"
    val nodeBaseUrl = "https://nodejs.org/dist/${RuntimeInstaller.NODE_VERSION}"
    val checksum = RuntimeInstaller.NODE_ARM64_SHA256
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
    onProgress(RuntimeInstallProgress("Installing Node.js ${RuntimeInstaller.NODE_VERSION}…", to))
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

internal suspend fun RuntimeInstaller.aptInstall(
    chrootExec: File,
    packages: List<String>,
    message: String,
    fraction: Float,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    onProgress(RuntimeInstallProgress(message, fraction))
    aptInstallInternal(chrootExec, packages, fraction, onProgress)
}

internal suspend fun RuntimeInstaller.runSystemMaintenance(
    chrootExec: File,
    onProgress: suspend (RuntimeInstallProgress) -> Unit,
) {
    onProgress(
        RuntimeInstallProgress(
            message = "Updating Ubuntu packages inside Root Chroot…",
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
        failureMessage = "Ubuntu system maintenance failed inside Root Chroot",
    )
}

internal suspend fun RuntimeInstaller.aptInstallInternal(
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

internal suspend fun RuntimeInstaller.aptRemove(
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

