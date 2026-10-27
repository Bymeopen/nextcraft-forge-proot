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
internal val FS_PROMISES_IMPORT = Regex("""import \{([^}]*)\} from "node:fs/promises";""")

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
internal const val MIN_DSH_VERSION = "0.1.2-rc.1"

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

internal data class RuntimeBundle(
    val label: String,
    val fileName: String,
    val sha256: String,
    val compressedBytes: Long,
    val baseUrl: String = RUNTIME_RELEASE_BASE_URL,
)

/** Release hosting the Ubuntu 24.04 bundles built from scripts/runtime-bundles. */
internal const val RUNTIME_RELEASE_BASE_URL = "https://github.com/bymeopen/nextcraft-forge/releases/download/runtime-2026.10.2"


class RuntimeInstaller(internal val context: Context) {
    internal val runtimeDir = File(context.filesDir, "runtime")
    internal val runtimeBinDir = File(runtimeDir, "bin")
    internal val chrootExecFile = File(runtimeBinDir, "chroot-exec.sh")
    internal val prootExecFile = File(runtimeBinDir, "proot-exec.sh")
    internal val unmountChrootFile = File(runtimeBinDir, "unmount-chroot.sh")
    internal val rootfs = File(runtimeDir, "ubuntu")
    internal val downloads = File(context.cacheDir, "runtime-downloads")
    internal val coreReadyMarker = File(rootfs, ".nextcraft-runtime-ready")
    internal val claudeMarker = File(rootfs, ".nextcraft-claude-version")
    internal val bundledClaudeMarker = File(rootfs, ".nextcraft-bundled-claude-version")
    internal val rootfsMarker = File(rootfs, ".nextcraft-rootfs-version")
    internal val languageToolsMarker = File(rootfs, ".nextcraft-language-tools-version")
    internal val coreToolsMarker = File(rootfs, ".nextcraft-core-tools-version")
    internal val systemUpgradeMarker = File(rootfs, ".nextcraft-system-upgrade-version")
    internal val devStacksFile = File(rootfs, ".nextcraft-dev-stacks.json")
    internal val dshMarker = File(rootfs, ".nextcraft-dsh-version")
    internal val agyMarker = File(rootfs, ".nextcraft-agy-version")
    internal val githubCliMarker = File(rootfs, ".nextcraft-github-cli-version")
    internal val dshAndroidCompatibilityMarker = File(rootfs, ".nextcraft-dsh-android-compat-version")
    internal val macosMetadataRepairMarker = File(rootfs, ".nextcraft-macos-metadata-repair")

    fun isInstalled(): Boolean {
        RuntimeMarkers.syncAll(rootfs)
        val chrootScriptsReady = runCatching { ensureChrootScripts() }.isSuccess
        val rootfsLayoutReady = ensureRootfsCompatibilityLinks()
        val legacyLanguageTools = languageToolsMarker.readTextOrNull() == LANGUAGE_TOOLS_VERSION
        val coreToolsReady = File(rootfs, "usr/bin/git").exists() && isSupportedCoreToolsVersion()
        val ready = chrootScriptsReady &&
            chrootExecFile.canExecute() &&
            rootfsLayoutReady &&
            File(rootfs, "usr/bin/bash").exists() &&
            isSupportedRootfsVersion(rootfsMarker.readTextOrNull()) &&
            File(rootfs, "usr/local/bin/node").exists() &&
            (legacyLanguageTools || coreToolsReady) &&
            coreReadyMarker.exists()
        if (ready) repairLegacyMacosMetadata()
        return ready
    }

    internal fun repairLegacyMacosMetadata() {
        if (macosMetadataRepairMarker.isFile) return
        if (!rootfs.isDirectory) return
        // isInstalled() can run while a chroot session is mounted: stay out of the bind mounts (/proc, /system,
        // /workspace, ...) so the walk is neither huge nor able to delete "._*" files from a user's project.
        stripMacosMetadataArtifacts(rootfs, skipTopLevel = BIND_MOUNTED_TOP_LEVEL)
        macosMetadataRepairMarker.parentFile?.mkdirs()
        macosMetadataRepairMarker.writeMarker("1")
    }

    /** Returns the already verified Root Chroot runtime without performing network or update checks. */
    fun installedRuntime(): InstalledRuntime {
        check(isInstalled()) { CORE_MISSING_MESSAGE }
        return InstalledRuntime(
            chrootExec = chrootExecFile,
            rootfs = rootfs,
        )
    }

    /**
     * Ensures `chroot-exec.sh` and `unmount-chroot.sh` are installed in `filesDir/runtime/bin`
     * with executable permissions (`0755`).
     */
    @Synchronized
    fun ensureChrootScripts(): File {
        runtimeBinDir.mkdirs()
        if (!chrootExecFile.isFile || chrootExecFile.readTextOrNull() != CHROOT_EXEC_SCRIPT.trim()) {
            writeScriptAtomically(chrootExecFile, CHROOT_EXEC_SCRIPT)
        }
        Os.chmod(chrootExecFile.absolutePath, 0b111101101)

        if (!unmountChrootFile.isFile || unmountChrootFile.readTextOrNull() != UNMOUNT_CHROOT_SCRIPT.trim()) {
            writeScriptAtomically(unmountChrootFile, UNMOUNT_CHROOT_SCRIPT)
        }
        Os.chmod(unmountChrootFile.absolutePath, 0b111101101)
        if (!prootExecFile.isFile || prootExecFile.readTextOrNull() != PROOT_EXEC_SCRIPT.trim()) {
            writeScriptAtomically(prootExecFile, PROOT_EXEC_SCRIPT)
        }
        // Never executed directly (app data is noexec for the app UID): it is always run as `/system/bin/sh script`.
        Os.chmod(prootExecFile.absolutePath, 0b110100100)
        return chrootExecFile
    }

    /** Active engine for this process; see [RuntimeEngines]. */
    fun runtimeEngine(): RuntimeEngine? = RuntimeEngines.detect(context)

    /** True when commands can run at all: root chroot, or the PRoot fallback. */
    fun isRuntimeAccessAvailable(): Boolean = runtimeEngine() != null

    /** Arguments that launch a command line produced by [prepareChrootShellCommand] under the active engine. */
    /** chroot: `su -mm -c` where the su supports it (see [SuCompat]); PRoot: the app's own shell. */
    fun launchArgv(shellCommand: String): List<String> =
        if (RuntimeEngines.current() == RuntimeEngine.CHROOT) SuCompat.argv(shellCommand) else RuntimeEngines.launchArgv(shellCommand)

    /** Replaces the script via rename so a concurrent launch never executes a half-written file. */
    internal fun writeScriptAtomically(target: File, content: String) {
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

    /** Unmounts all kernel mounts under [targetRootfs] before deleting or swapping rootfs directories. */
    fun unmountChrootMounts(targetRootfs: File = rootfs): Boolean {
        ensureChrootScripts()
        return RootfsRepair.ensureUnmounted(targetRootfs)
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

    internal fun File.deleteIfExact(expected: String) {
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
        require(DeviceHardwareOptimizer.meetsMinimumAndroidVersion()) {
            "NextCraft Forge requires Android 12 (API 31) or newer."
        }
        val hwProfile = DeviceHardwareOptimizer.inspect(context)
        require(DeviceHardwareOptimizer.meetsMinimumRam(hwProfile.totalRamMb)) {
            "NextCraft Forge requires at least 6 GB of RAM (detected ${hwProfile.ramTier.label})."
        }
        // The first su call shows the Magisk/KernelSU/APatch grant dialog; the 8 s default kills su before the user can tap Allow.
        // Without root the bundled PRoot engine (ADR-007) takes over; without either there is nothing to run on.
        requireNotNull(RuntimeEngines.detect(context, ROOT_GRANT_TIMEOUT_SECONDS, refresh = true)) {
            "Root access (su -mm) is required by NextCraft Forge's Ubuntu 24.04 LTS Chroot engine, and this build has no PRoot fallback. Grant root permission in Magisk, KernelSU, or APatch and try again."
        }
        val chrootExec = ensureChrootScripts()
        require(chrootExec.canExecute()) { "The Root Chroot supervisor script is unavailable" }

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
            onProgress(RuntimeInstallProgress("Preparing Ubuntu Root Chroot runtime…", 0.03f))
            val archive = obtainRuntimeBundle(
                CORE_BUNDLE,
                preferEmbedded = isAssetAvailable("runtime/${CORE_BUNDLE.fileName}"),
                from = 0.03f,
                to = 0.25f,
                onProgress = onProgress,
            )
            onProgress(RuntimeInstallProgress("Unpacking Core Linux rootfs…", 0.28f))
            check(RootfsRepair.ensureUnmounted(staging)) { "Staging rootfs still has active mounts" }
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
            verifyGuest(chrootExec, "git --version", "Failed to verify Git inside Root Chroot")
            coreToolsMarker.writeMarker(CORE_TOOLS_VERSION)
        }
        if (!coreReadyMarker.exists()) coreReadyMarker.writeMarker(CORE_TOOLS_VERSION)

        val removedStacks = if (selectedStacks.isNotEmpty()) {
            (installedStacks() - selectedStacks) - DevStack.WEB
        } else {
            emptySet()
        }
        val missingStacks = selectedStacks.filterNot(this::isStackInstalled)
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
        onProgress(RuntimeInstallProgress("NextCraft Forge Root Chroot setup complete", 1f))
        return InstalledRuntime(chrootExec, rootfs)
    }


    internal suspend fun runGuestCommand(
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

    internal fun sanitizeTerminalLine(raw: String): String = raw
        .replace(ANSI_ESCAPE, "")
        .filter { it == '\t' || it.code >= 32 }
        .take(MAX_TERMINAL_LINE)

    internal suspend fun verifyGuest(chrootExec: File, command: String, failureMessage: String) {
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
    internal fun ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
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
    internal fun ensureAndroidSocketGroupConfiguration(targetRootfs: File = rootfs) {
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
                if (changed) groupFile.writeText(groupText)
            }
            val aptConf = File(targetRootfs, "etc/apt/apt.conf.d/99android-chroot")
            aptConf.parentFile?.mkdirs()
            val expectedAptConf = "APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n"
            if (aptConf.readTextOrNull() != expectedAptConf.trim()) {
                aptConf.writeText(expectedAptConf)
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }

    internal fun isValidCoreRootfs(targetRootfs: File): Boolean =
        ensureRootfsCompatibilityLinks(targetRootfs) &&
            File(targetRootfs, "usr/bin/bash").isFile &&
            isSupportedRootfsVersion(RuntimeMarkers.read(File(targetRootfs, ".nextcraft-rootfs-version"))?.trim())

    internal fun actionableProcessError(output: String, fallback: String, exitCode: Int? = null): String {
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

    internal fun readProcessOutputSafely(file: File): String = runCatching {
        file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
    }.getOrElse { error ->
        Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
        ""
    }

    suspend fun initializeExisting(onProgress: suspend (RuntimeInstallProgress) -> Unit): InstalledRuntime {
        val installed = installedRuntime()
        onProgress(RuntimeInstallProgress("Checking Root Chroot runtime files…", 0.15f))
        ensureChrootScripts()
        ensureAndroidSocketGroupConfiguration(rootfs)
        writeResolver()
        ensureSettingsAndHooks()
        File(context.filesDir, "runtime-bridge").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        onProgress(RuntimeInstallProgress("Preparing runtime IPC bridge…", 0.42f))
        disablePhantomProcessKillerViaRoot()
        check(File(rootfs, "usr/local/bin/node").canExecute()) { "Core runtime is missing Node.js" }
        check(File(rootfs, "usr/bin/git").canExecute()) { "Core runtime is missing Git" }
        onProgress(RuntimeInstallProgress("Root Chroot runtime ready", 1f))
        return installed
    }

    /**
     * Spawns a command inside the Ubuntu 24.04 Root Chroot via `su -mm` and `chroot-exec.sh`.
     * No PRoot binary or ptrace translation is used.
     */
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
     * executing [guestCommand] inside the Root Chroot with all SDK, PATH, and provider variables
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
        val effectiveMcpServers = claudeMcpServers ?: runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull()
        AntigravityCustomizations.apply(targetRootfs, guestWorkspacePath, effectiveMcpServers) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
        AntigravityCustomizations.applyWorkspace(workspace, guestWorkspacePath)
        // ~/.claude.json belongs to Claude Code; other agents never read it, so they skip the parse + fsync per launch.
        if (trustClaudeWorkspace && guestWorkspacePath != "/root") {
            ensureWorkspaceTrust(guestWorkspacePath, environment, effectiveMcpServers)
        }
        val bridge = File(context.filesDir, "runtime-bridge").apply { mkdirs() }
        File(context.filesDir, "runtime-bridge-responses").mkdirs()
        val appUid = AndroidProcess.myUid().toString()

        val hwProfile = DeviceHardwareOptimizer.inspect(context)
        val prootConfig = ProotAndChrootOptimizer.buildConfig(
            hwProfile = hwProfile,
            emulateHardLinks = true,
        )
        val guestEnv = buildMap {
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
            put("TERM", "xterm-256color")
            put("GLIBC_TUNABLES", "glibc.pthread.rseq=0")
            // PROOT_TMP_DIR/PROOT_L2S_DIR here are bare "/tmp/..." -- real top-level host paths the app has no
            // permission to write to. chroot-exec.sh/proot-exec.sh compute their own rootfs versions of both.
            putAll(prootConfig.environmentVars.filterKeys { it != "PROOT_TMP_DIR" && it != "PROOT_L2S_DIR" })
            putAll(environment)
        }

        // Write environment variables (including sensitive API keys/OAuth tokens) to a
        // one-time mode-0600 file inside filesDir/runtime/env and immediately unlink it
        // inside the root shell before `exec`, so secrets NEVER appear in `ps aux` or
        // `/proc/<pid>/cmdline` while surviving Magisk/KernelSU/APatch su environ sanitization.
        val runtimeEnvDir = File(runtimeDir, "env").apply {
            mkdirs()
            runCatching { Os.chmod(absolutePath, 0b111000000) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) } // 0700
        }
        // A launch that never reached `rm -f` (su denied, process killed) leaves API keys on
        // disk. Purge anything old enough that it cannot belong to a launch in progress (su prompt + exec < 2 min).
        runtimeEnvDir.listFiles { f -> f.name.startsWith(".env-") }
            ?.filter { System.currentTimeMillis() - it.lastModified() > 2 * 60 * 1_000L }
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
        val launch = when (RuntimeEngines.current()) {
            RuntimeEngine.CHROOT -> listOf(chrootExec.absolutePath) + supervisorArgs
            // Same supervisor contract, but driven by proot from the APK; the app UID cannot exec the script itself.
            RuntimeEngine.PROOT -> listOf("/system/bin/sh", prootExecFile.absolutePath) + supervisorArgs
        }
        val engineExports = if (RuntimeEngines.current() == RuntimeEngine.PROOT) {
            "export NEXTCRAFT_PROOT=${shellSingleQuote(RuntimeEngines.prootBinary(context).absolutePath)}; " +
                "export NEXTCRAFT_PROOT_LOADER=${shellSingleQuote(RuntimeEngines.prootLoader(context).absolutePath)}; " +
                "export NEXTCRAFT_PROOT_TMP=${shellSingleQuote(File(runtimeDir, "proot-tmp").apply { mkdirs() }.absolutePath)}; "
        } else {
            ""
        }
        val pidFile = GuestProcess.pidFileFor(envFile).also { it.parentFile?.mkdirs() }
        val suCommand = ". $quotedEnvFile; rm -f $quotedEnvFile; $engineExports" + GuestProcess.recordPidCommand(pidFile) +
            "exec " + launch.joinToString(" ") { shellSingleQuote(it) }
        return envFile to suCommand
    }

    /**
     * Workspaces belong to the app UID while git runs as root, so without this git refuses every project with
     * "dubious ownership" (exit 128). The system config is used so ~/.gitconfig stays the user's.
     */
    internal fun ensureSystemGitTrust() {
        val config = File(rootfs, "etc/gitconfig")
        val existing = if (config.isFile) runCatching { config.readText() }.getOrNull() ?: return else ""
        val merged = mergeGitConfig(existing, listOf(GitConfigEntry("safe", "directory", "*", multiValue = true)))
        if (merged != existing) writeRootfsFileAtomically(config, merged)
    }

    /** Installs or removes android-shell and friends to match Settings → Android system access. */
    fun applyAndroidHostTools() {
        if (!rootfs.isDirectory) return
        val enabled = com.nextcraft.forge.data.AppPreferences(context).androidHostTools
        AndroidHostTools.apply(rootfs, enabled) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b111101101) }
    }

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
if [ -d "${'$'}BRIDGE" ] && cat > "${'$'}BRIDGE/${'$'}ID.request.tmp" && mv "${'$'}BRIDGE/${'$'}ID.request.tmp" "${'$'}BRIDGE/${'$'}ID.request"; then
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
printf '{"hookSpecificOutput":{"hookEventName":"PermissionRequest","decision":{"behavior":"%s"}}}\n' "${'$'}DECISION"
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
        writeRootfsFileAtomically(
            File(rootfs, "etc/profile.d/nextcraft-browser.sh"),
            "export BROWSER=\"${'$'}{BROWSER:-/opt/nextcraft/open-url.sh}\"\n",
            mode = 0b110100100,
        )
        listOf("usr/bin/xdg-open", "usr/local/bin/xdg-open", "usr/bin/www-browser", "usr/bin/sensible-browser").forEach { path ->
            val link = File(rootfs, path)
            runCatching {
                link.parentFile?.mkdirs()
                if (java.nio.file.Files.isSymbolicLink(link.toPath()) || link.exists()) link.delete()
                Os.symlink("/opt/nextcraft/open-url.sh", link.absolutePath)
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }

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
                JSONObject().put(
                    "PermissionRequest",
                    JSONArray().put(
                        JSONObject()
                            .put("matcher", "Bash|Edit|Write|NotebookEdit")
                            .put(
                                "hooks",
                                JSONArray().put(
                                    JSONObject().put("type", "command").put("command", "/opt/nextcraft/permission-hook.sh"),
                                ),
                            ),
                    ),
                ),
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
        applyAndroidHostTools()
        ForgeMcpServer.apply(rootfs) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b111101101) }
        ProotAndChrootOptimizer.applyGuestWrappers(rootfs) { file, body, mode -> writeRootfsFileAtomically(file, body, mode = mode) }
        val managedMcp = runCatching { ConnectorStore(context).mcpServersJson() }.getOrNull()
        AntigravityCustomizations.apply(rootfs, mcpServers = managedMcp) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
        runCatching {
            val dshPatch = File(rootfs, "root/.dsh/cordis.patch.yml")
            writeRootfsFileAtomically(dshPatch, ForgeMcpServer.toDshCordisPatchYaml(managedMcp), mode = 0b110000000)
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        ensureWorkspaceTrust("/workspace", mcpServers = managedMcp)
        ensureSharedTmpPermissions()
    }

    internal fun ensureSharedTmpPermissions() {
        val sharedSticky = listOf("tmp", "var/tmp").map { File(rootfs, it) }
        sharedSticky.forEach { dir ->
            runCatching {
                dir.mkdirs()
                Os.chmod(dir.absolutePath, 0b1_111_111_111) // 01777: sticky + rwxrwxrwx
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
        }
        runCatching {
            val run = File(rootfs, "run").apply { mkdirs() }
            Os.chmod(run.absolutePath, 0b111_101_101) // 0755
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }

    /** Synchronizes enabled MCP connectors into Antigravity (`mcp_config.json`), DeepSeek Harness (`cordis.patch.yml`), and Claude Code (`~/.claude.json`). */
    fun syncMcpConfigs() {
        runCatching {
            val managedMcp = ConnectorStore(context).mcpServersJson()
            AntigravityCustomizations.apply(rootfs, mcpServers = managedMcp) { file, body -> writeRootfsFileAtomically(file, body, mode = 0b110100100) }
            val dshPatch = File(rootfs, "root/.dsh/cordis.patch.yml")
            writeRootfsFileAtomically(dshPatch, ForgeMcpServer.toDshCordisPatchYaml(managedMcp), mode = 0b110000000)
            ensureWorkspaceTrust("/workspace", mcpServers = managedMcp)
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeInstaller", it) }
    }

    internal fun ensureWorkspaceTrust(
        workspacePath: String,
        environment: Map<String, String> = emptyMap(),
        mcpServers: org.json.JSONObject? = null,
    ) = synchronized(CLAUDE_STATE_LOCK) {
        val stateFile = File(rootfs, "root/.claude.json")
        if (stateFile.exists() && !(stateFile.canRead() && stateFile.canWrite())) {
            // Claude runs as root inside the chroot and may have rewritten the file as root-owned 0600.
            RootfsRepair.reclaimOwnership(stateFile, AndroidProcess.myUid())
        }
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
    internal fun readClaudeState(stateFile: File): JSONObject? {
        repeat(3) { attempt ->
            val parsed = runCatching { JSONObject(stateFile.readText()) }.getOrNull()
            if (parsed != null) return parsed
            if (attempt < 2) Thread.sleep(150)
        }
        return null
    }

    /**
     * Writes [target] through a temp file in the same directory and an atomic rename, so a crash or a concurrent
     * reader never sees a truncated file. A root-owned target (rewritten by a root process in the chroot) is
     * reclaimed first.
     */
    internal fun writeRootfsFileAtomically(target: File, content: String, mode: Int? = null) {
        val parent = checkNotNull(target.parentFile) { "No parent directory for ${target.name}" }
        parent.mkdirs()
        if (!parent.canWrite() || (target.exists() && !target.canWrite())) {
            RootfsRepair.reclaimOwnership(if (parent.canWrite()) target else parent, AndroidProcess.myUid())
        }
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
    internal fun claudeWorkspaceToolRules() = JSONArray().apply {
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
    internal fun ensureShellPrompt() {
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
    internal fun ensureX86Wrapper() {
        val target = File(rootfs, X86Wrapper.GUEST_PATH)
        if (runCatching { target.readText() }.getOrNull() == X86Wrapper.SCRIPT) return
        runCatching {
            target.parentFile?.mkdirs()
            writeRootfsFileAtomically(target, X86Wrapper.SCRIPT, mode = 0b111101101)
        }.onFailure { Log.w("RuntimeInstaller", "Could not write run-x86", it) }
    }

    internal fun writeResolver() {
        ensureAppAccess()
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val dns = manager?.getLinkProperties(manager.activeNetwork)?.dnsServers.orEmpty()
        val deviceServers = dns.mapNotNull { it.hostAddress }
        // glibc only reads the first MAXNS=3 "nameserver" lines, so the device's own servers are capped to 1 here --
        // otherwise two DoT-only device entries would push both public fallbacks off the end of the list.
        val fallbacks = listOf("1.1.1.1", "8.8.8.8", "9.9.9.9", "8.8.4.4", "1.0.0.1")
        val servers = (deviceServers.take(1) + fallbacks).distinct().take(3)
        val resolvFile = File(rootfs, "etc/resolv.conf")
        resolvFile.parentFile?.mkdirs()
        if (java.nio.file.Files.isSymbolicLink(resolvFile.toPath())) {
            resolvFile.delete()
        }
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
    internal fun ensureLocalhostInHosts() {
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

    internal fun openZstdInputStream(
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
            val process = runCatching {
                ProcessBuilder("zstd", "-d", "-c", archive.absolutePath).start()
            }.getOrElse {
                val escapedPath = archive.absolutePath.replace("'", "'\"'\"'")
                val pathExport = "export PATH=/system/bin:/system/xbin:/sbin:/vendor/bin:/data/adb/magisk:/data/adb/ksu/bin:/data/adb/ap/bin:${'$'}PATH; "
                ProcessBuilder("su", "-c", "${pathExport}zstd -d -c '$escapedPath'").start()
            }
            object : java.io.FilterInputStream(BufferedInputStream(process.inputStream)) {
                internal var reachedEof = false

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
    internal fun openForWrite(target: File): FileOutputStream {
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
    internal suspend fun extractZstdTar(
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
    internal fun extractNodeArchive(archive: File, destination: File) {
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

    internal fun linkOrCopyFile(source: File, destination: File) {
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

    internal suspend fun downloadVerified(
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

    internal fun fetchText(url: String): String {
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

    internal fun digest(file: File, algorithm: String): String {
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

    internal fun File.readTextOrNull(): String? = runCatching { readText().trim() }.getOrNull()

    companion object {
        // Pure path helpers (no instance state) so that unit tests can exercise them.
        internal fun resolveSafeSymlinkTarget(
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

        internal fun safeChild(root: File, relative: String): File {
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
            var currentDir = rootPath
            for (index in 0 until segments.lastIndex) {
                val segment = segments[index]
                var candidate = currentDir.resolve(segment).normalize()
                require(candidate.startsWith(rootPath) && candidate != rootPath) { "Archive path escapes runtime" }
                var hops = 0
                while (java.nio.file.Files.isSymbolicLink(candidate)) {
                    check(++hops <= 16) { "Archive symlink loop detected" }
                    val linkTarget = java.nio.file.Files.readSymbolicLink(candidate).toString()
                    val parent = candidate.parent ?: rootPath
                    candidate = resolveSafeSymlinkTarget(
                        rootPath = rootPath,
                        parentDir = parent,
                        linkName = linkTarget,
                        errorMessage = "Archive path escapes runtime via symlink",
                    )
                }
                currentDir = candidate
            }
            val finalPath = currentDir.resolve(segments.last()).normalize()
            require(finalPath.startsWith(rootPath) && finalPath != rootPath) {
                "Archive path escapes runtime"
            }
            return finalPath.toFile()
        }

        /**
         * Gradle speed settings, sized like [gradleJvmArgs]. Measured on a 10.9 GB phone, an up-to-date JetNews build
         * still took 4 min with a fresh JVM per build and 2 workers: almost all of it start-up and configuration.
         *  - daemon: keeps the warmed-up JVM between builds; it exits after 10 idle minutes so its heap is returned.
         *    Off below 7 GB, where an idle 1-2 GB JVM would push the app into the low-memory killer;
         *  - configuration cache (problems only warn, so incompatible builds still run) and the build cache;
         *  - workers: 4 / 3 / 2 and parallel project execution only on 10 GB+ (each worker adds memory).
         */
        internal fun gradleSpeedProperties(
            totalMemoryMb: Long,
            swapTotalMb: Long = 0L,
            swapFreeMb: Long = swapTotalMb,
            socVendor: DeviceHardwareOptimizer.SocVendor = DeviceHardwareOptimizer.SocVendor.GENERIC_ARM64,
            socTier: DeviceHardwareOptimizer.SocTier = DeviceHardwareOptimizer.SocTier.BALANCED_MID,
            performanceCores: Int = 4,
        ): List<Pair<String, String>> {
            val tuning = DeviceHardwareOptimizer.computeGradleTuning(
                totalMemoryMb = totalMemoryMb,
                swapTotalMb = swapTotalMb,
                swapFreeMb = swapFreeMb,
                socVendor = socVendor,
                socTier = socTier,
                performanceCores = performanceCores,
            )
            return listOf(
                "org.gradle.daemon" to tuning.daemonEnabled.toString(),
                "org.gradle.daemon.idletimeout" to tuning.daemonIdleTimeoutMs.toString(),
                GRADLE_WORKERS_PROPERTY to tuning.maxWorkers.toString(),
                GRADLE_PARALLEL_PROPERTY to tuning.parallelExecution.toString(),
                "org.gradle.caching" to "true",
                "org.gradle.configuration-cache" to "true",
                "org.gradle.configuration-cache.problems" to "warn",
            )
        }

        /**
         * Gradle JVM options for builds inside the chroot, sized to the device's RAM tier (4/6/8/10/12/16 GB+),
         * built-in phone SwapRAM / ZRAM, and SoC core topology so small phones are not pushed into the
         * low-memory killer (exit 137) and large ones can build Room/KSP projects at full speed.
         */
        internal fun gradleJvmArgs(
            totalMemoryMb: Long,
            swapTotalMb: Long = 0L,
            swapFreeMb: Long = swapTotalMb,
            socVendor: DeviceHardwareOptimizer.SocVendor = DeviceHardwareOptimizer.SocVendor.GENERIC_ARM64,
            socTier: DeviceHardwareOptimizer.SocTier = DeviceHardwareOptimizer.SocTier.BALANCED_MID,
            performanceCores: Int = 4,
        ): String {
            val tuning = DeviceHardwareOptimizer.computeGradleTuning(
                totalMemoryMb = totalMemoryMb,
                swapTotalMb = swapTotalMb,
                swapFreeMb = swapFreeMb,
                socVendor = socVendor,
                socTier = socTier,
                performanceCores = performanceCores,
            )
            return "-Xmx${tuning.heapMb}m -XX:MaxMetaspaceSize=${tuning.metaspaceMb}m " +
                "-XX:+UseG1GC -XX:+UseStringDeduplication -XX:G1PeriodicGCInterval=15000 -XX:+G1PeriodicGCInvokesConcurrent " +
                "-XX:G1HeapWastePercent=5 -XX:MaxHeapFreeRatio=40 -XX:MinHeapFreeRatio=15 " +
                "-Dfile.encoding=UTF-8"
        }

        /** Top-level rootfs directories that chroot-exec.sh bind-mounts from Android or the app on every launch. */
        internal val BIND_MOUNTED_TOP_LEVEL = setOf("proc", "sys", "dev", "system", "apex", "vendor", "product", "system_ext", "odm", "android", "workspace", "data", "storage", "mnt", "sdcard", "tmp", "root", "nextcraft-bridge", "pocketdev-bridge", "pocket-bridge", "pocketdev-bridge-responses", "nextcraft-bridge-responses")
        /** RuntimeInstaller is created per call site; the lock must be shared by every instance. */
        internal val CLAUDE_STATE_LOCK = Any()

        fun rootfsDir(context: Context): File = File(context.filesDir, "runtime/ubuntu")
        fun bridgeRootDir(context: Context): File = File(context.filesDir, "runtime-bridge")

        const val AGY_GUEST_PATH = "/root/.local/bin/agy"
        const val GITHUB_CLI_GUEST_PATH = "/root/.local/bin/gh"
        internal const val AGY_VERSION = "1.4.5"
        const val CORE_MISSING_MESSAGE =
            "The base Ubuntu runtime (Core) is not installed or is incomplete. Run the runtime setup first, then retry."
        internal const val GITHUB_CLI_VERSION = "2.100.0"
        internal const val GITHUB_CLI_RELEASE_URL = "https://github.com/cli/cli/releases/download/v2.100.0/gh_2.100.0_linux_arm64.tar.gz"
        internal const val GITHUB_CLI_RELEASE_SHA256 = "ea4e7a581a32ccad6cc7923cb1576ac5859ba4b9a16ab22eb8f8a96e78e2e961"

        init {
            check(
                GITHUB_CLI_RELEASE_SHA256.length == 64 &&
                    GITHUB_CLI_RELEASE_SHA256.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' },
            ) {
                "Invalid GITHUB_CLI_RELEASE_SHA256 length"
            }
        }
        internal const val LEGACY_README = "# Pocket Dev project\n\nThis project is managed locally on Android.\n"
        internal const val LEGACY_INDEX = "<!doctype html><title>Pocket Dev</title><h1>Hello from Android</h1>\n"
        internal const val ROOTFS_VERSION = "ubuntu-24.04.5-arm64"
        internal const val LEGACY_ROOTFS_VERSION = "ubuntu-20.04.5-arm64"
        internal const val LEGACY_ROOTFS_PYTHON_MESSAGE =
            "Python needs the Ubuntu 24.04 runtime. This runtime is still on Ubuntu 20.04: reinstall the runtime " +
                "(Settings → Runtime), then install Python again."
        internal const val NODE_VERSION = "v24.22.1"
        internal const val NODE_ARM64_SHA256 = "d28c8a5bf0a808f0ed434a1dce8c54ae98f0371c0bd86ac58abc613f73e6643f"
        internal const val COMPOSER_VERSION = "2.10.3"
        internal const val COMPOSER_SHA256 = "7a2d379d5b8ffdaa028580ef26494c36d2feef4b178d3dd1473a4dbc5e17c8d6"
        internal const val LANGUAGE_TOOLS_VERSION = "node-v24.19.0-python3-v1"
        internal const val CORE_TOOLS_VERSION = "core-bundle-2026.10.1"
        internal const val LEGACY_CORE_TOOLS_VERSION = "core-bundle-2026.09.4"
        internal const val PREVIOUS_CORE_TOOLS_VERSION = "core-bundle-2026.09.5"
        internal const val NEXTCRAFT_CORE_TOOLS_VERSION = "nextcraft-core-2026.10.1"
        /** Core rebuilt from scratch by build-core-on-linux.sh (runtime-bundles.yml). */
        internal const val NEXTCRAFT_CORE_TOOLS_VERSION_2 = "nextcraft-core-2026.10.2"
        internal const val SYSTEM_UPGRADE_VERSION = "ubuntu-maintenance-v1"
        internal const val ANDROID_TOOLS_VERSION = AndroidToolchain.TOOLS_VERSION
        internal const val ANDROID_AAPT2_PROPERTY = "android.aapt2FromMavenOverride"
        internal const val GRADLE_JVMARGS_PROPERTY = "org.gradle.jvmargs"
        internal const val GRADLE_WORKERS_PROPERTY = "org.gradle.workers.max"
        internal const val GRADLE_PARALLEL_PROPERTY = "org.gradle.parallel"
        internal const val KOTLIN_EXECUTION_PROPERTY = "kotlin.compiler.execution.strategy"
        internal val MANAGED_ANDROID_GRADLE_PROPERTIES = setOf(
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
        internal const val ANDROID_AAPT2_GUEST_PATH = AndroidToolchain.AAPT2_GUEST_PATH
        internal const val ANDROID_AAPT2_HOST_PATH = AndroidToolchain.AAPT2_HOST_PATH

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
        internal val CLAUDE_VERSION_PATTERN = Regex("[0-9]+\\.[0-9]+\\.[0-9]+")
        const val DSH_VERSION = "0.2.1-rc.2"
        internal const val DSH_ANDROID_COMPATIBILITY_VERSION = "copyfile-excl-v2"
        internal val CORE_BUNDLE = RuntimeBundle(
            label = "Core",
            fileName = "nextcraft-core-arm64-2026.10.2.tar.zst",
            sha256 = "c2dab14270aae62b21243109a5a6e75af7d0c6821fda6264b91f973e18f3ddfb",
            compressedBytes = 74_457_771L,
        )
        internal const val CLAUDE_BUNDLED_VERSION = "2.1.308"
        internal const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        /** Where the npm package lands under PRoot; CLAUDE_GUEST_PATH becomes a symlink into it. */
        internal const val CLAUDE_NPM_GUEST_DIR = "/usr/local/lib/claude-code"
        /** Last @anthropic-ai/claude-code release shipping cli.js (2.1.113+ only download the Bun binary). */
        internal const val CLAUDE_NPM_VERSION = "2.1.112"
        internal val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "nextcraft-claude-arm64-2026.10.2.tar.zst",
            sha256 = "cc479f683e0e581e055d6fa2c033897c1aa6e306b6c065c9cff9e65e55c8e7a4",
            compressedBytes = 102_137_744L,
        )
        internal val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "nextcraft-python-arm64-2026.10.2.tar.zst",
            sha256 = "6f4a847ffe7d36cea16c4fb3e1dc1ad2d7619ef9dff31560cfaa9b5e47a833d8",
            compressedBytes = 15_018_991L,
        )
        /** Built by scripts/runtime-bundles/build-android-bundle.sh; versions in [AndroidToolchain]. */
        internal val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "nextcraft-android-arm64-2026.10.2.tar.zst",
            sha256 = "84ca85c6578fe210ca7ada5ad7dd7117e769254c1faf597609f50fe3e5ed165d",
            compressedBytes = 584_339_354L,
        )
        internal val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek",
            fileName = "nextcraft-dsh-arm64-2026.10.2.tar.zst",
            sha256 = "0036adeef3f30a7b535821e7f4594c3a296ff16f7dfbbcaec6b93bc6ef44ff25",
            compressedBytes = 90_508_623L,
        )
        internal val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "nextcraft-agy-arm64-2026.10.2.tar.zst",
            sha256 = "0a3e344817ada663f5cb79b0b86707fd76581cef794194d31dc360ec5b5d889b",
            compressedBytes = 48_493_274L,
        )
        internal const val ROOT_GRANT_TIMEOUT_SECONDS = 45L
        internal const val ENV_CLEANUP_POLL_MS = 1_000L
        /** Compressed size multiples needed to download (1x) and extract (about 3x for zstd rootfs content). */
        internal const val BUNDLE_DOWNLOAD_SPACE_FACTOR = 4
        internal const val BUNDLE_EXTRACT_SPACE_FACTOR = 3
        internal const val MEGABYTE = 1024L * 1024L
        internal const val MAX_TERMINAL_LINE = 500
        internal const val MAX_COLLECTED_OUTPUT = 24_000
        internal const val MAX_PROCESS_OUTPUT_BYTES = 256 * 1024
        internal val ANSI_ESCAPE = Regex("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))")
        internal val VALID_ENV_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

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
