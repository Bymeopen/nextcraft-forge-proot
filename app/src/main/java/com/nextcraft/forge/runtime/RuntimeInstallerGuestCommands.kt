package com.nextcraft.forge.runtime

import com.nextcraft.forge.runtime.RuntimeInstaller.Companion.ANSI_ESCAPE
import com.nextcraft.forge.runtime.RuntimeInstaller.Companion.MAX_COLLECTED_OUTPUT
import com.nextcraft.forge.runtime.RuntimeInstaller.Companion.MAX_PROCESS_OUTPUT_BYTES
import com.nextcraft.forge.runtime.RuntimeInstaller.Companion.MAX_TERMINAL_LINE
import android.system.Os
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

// Guest command execution and rootfs validation helpers for [RuntimeInstaller].
internal suspend fun RuntimeInstaller.runGuestCommand(
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

internal fun RuntimeInstaller.sanitizeTerminalLine(raw: String): String = raw
    .replace(ANSI_ESCAPE, "")
    .filter { it == '\t' || it.code >= 32 }
    .take(MAX_TERMINAL_LINE)

internal suspend fun RuntimeInstaller.verifyGuest(chrootExec: File, command: String, failureMessage: String) {
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
internal fun RuntimeInstaller.ensureRootfsCompatibilityLinks(targetRootfs: File = rootfs): Boolean {
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
internal fun RuntimeInstaller.ensureAndroidSocketGroupConfiguration(targetRootfs: File = rootfs) {
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

internal fun RuntimeInstaller.isValidCoreRootfs(targetRootfs: File): Boolean =
    ensureRootfsCompatibilityLinks(targetRootfs) &&
        File(targetRootfs, "usr/bin/bash").isFile &&
        isSupportedRootfsVersion(RuntimeMarkers.read(File(targetRootfs, ".nextcraft-rootfs-version"))?.trim())

internal fun RuntimeInstaller.actionableProcessError(output: String, fallback: String, exitCode: Int? = null): String {
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

internal fun RuntimeInstaller.readProcessOutputSafely(file: File): String = runCatching {
    file.readTailText(MAX_PROCESS_OUTPUT_BYTES)
}.getOrElse { error ->
    Log.w("RuntimeInstaller", "Could not decode process output as UTF-8: ${error.message}")
    ""
}
