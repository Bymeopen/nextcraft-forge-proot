package com.nextcraft.forge.runtime

import android.content.Context
import android.os.Process as AndroidProcess
import android.system.Os
import android.system.OsConstants
import android.util.Log
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

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
                "export NEXTCRAFT_SIGSYS_PROBE=${shellSingleQuote(RuntimeEngines.sigsysProbe(context).absolutePath)}; " +
                "export NEXTCRAFT_PROOT_TMP=${shellSingleQuote(File(runtimeDir, "proot-tmp").apply { mkdirs() }.absolutePath)}; "
        } else {
            ""
        }
        val pidFile = GuestProcess.pidFileFor(envFile).also { it.parentFile?.mkdirs() }
        val suCommand = ". $quotedEnvFile; rm -f $quotedEnvFile; $engineExports" + GuestProcess.recordPidCommand(pidFile) +
            "exec " + launch.joinToString(" ") { shellSingleQuote(it) }
        return envFile to suCommand
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
            fileName = "nextcraft-core-arm64-2026.10.1.tar.zst",
            sha256 = "fe63d1dececdcd5e861debe0936835d39c2eb6eb787bb7bd71bcab5c3db4d4cf",
            compressedBytes = 74_269_605L,
        )
        internal const val CLAUDE_BUNDLED_VERSION = "2.1.308"
        internal const val CLAUDE_GUEST_PATH = "/usr/local/bin/claude"
        /** Where the npm package lands under PRoot; CLAUDE_GUEST_PATH becomes a symlink into it. */
        internal const val CLAUDE_NPM_GUEST_DIR = "/usr/local/lib/claude-code"
        /** Last @anthropic-ai/claude-code release shipping cli.js (2.1.113+ only download the Bun binary). */
        internal const val CLAUDE_NPM_VERSION = "2.1.112"
        internal val CLAUDE_BUNDLE = RuntimeBundle(
            label = "Claude Code",
            fileName = "nextcraft-claude-arm64-2026.10.1.tar.zst",
            sha256 = "bb993c0a80ff2ea8d9c994e03cf587bf7ef996bdecdab3d95ce51e1e0f04f2bd",
            compressedBytes = 75_422_659L,
        )
        internal val PYTHON_BUNDLE = RuntimeBundle(
            label = "Python",
            fileName = "nextcraft-python-arm64-2026.10.1.tar.zst",
            sha256 = "43a23a3e2d73e46f5c242ac4e3d8f7adef356b987559e1474e4cc9072118a7f3",
            compressedBytes = 15_053_684L,
        )
        /** Built by scripts/runtime-bundles/build-android-bundle.sh; versions in [AndroidToolchain]. */
        internal val ANDROID_BUNDLE = RuntimeBundle(
            label = "Android",
            fileName = "nextcraft-android-arm64-2026.10.1.tar.zst",
            sha256 = "8b4ecf057daa6fd54acd950712f8adb4eb7722c7bc557136f2ea31d7a2bbfd60",
            compressedBytes = 584_522_930L,
        )
        internal val DSH_BUNDLE = RuntimeBundle(
            label = "DeepSeek",
            fileName = "nextcraft-dsh-arm64-2026.10.1.tar.zst",
            sha256 = "d3137b607c32f3714c655f5e0f101d7e7292841a056d3b9bc928e704656c220f",
            compressedBytes = 28_066_921L,
        )
        internal val AGY_BUNDLE = RuntimeBundle(
            label = "Antigravity CLI",
            fileName = "nextcraft-agy-arm64-2026.10.1.tar.zst",
            sha256 = "001cbb373a1110ce32c939c2b27138b46b676b157c9329b50c34e790aa2b7eff",
            compressedBytes = 42_003_928L,
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

