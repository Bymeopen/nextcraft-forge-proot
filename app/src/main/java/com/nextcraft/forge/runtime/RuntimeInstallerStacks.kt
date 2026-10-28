package com.nextcraft.forge.runtime

import android.system.Os
import android.util.Log
import com.nextcraft.forge.model.DevStack
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

// Dev stack and Android toolchain provisioning for [RuntimeInstaller].
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
