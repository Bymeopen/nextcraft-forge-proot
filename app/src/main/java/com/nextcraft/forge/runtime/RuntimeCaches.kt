package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Download caches inside the rootfs that only cost space: dependencies come back on the next build or install.
 * Files there belong to root (written by the chroot), so sizing and deleting go through `su`.
 */
internal object RuntimeCaches {
    enum class Kind(val label: String, val paths: List<String>) {
        GRADLE("Gradle", listOf("root/.gradle/caches", "root/.gradle/daemon", "root/.gradle/native", "root/.gradle/nextcraft")),
        NPM("npm / Yarn", listOf("root/.npm/_cacache", "root/.cache/yarn")),
        PIP("pip", listOf("root/.cache/pip")),
        APT("apt", listOf("var/cache/apt/archives")),
    }

    private fun quoted(rootfs: File, kind: Kind) =
        kind.paths.joinToString(" ") { shellSingleQuote(File(rootfs, it).absolutePath) }

    /** Size of each cache in bytes (0 when absent). */
    fun sizes(rootfs: File): Map<Kind, Long> = Kind.entries.associateWith { kind ->
        val out = runRoot("du -sk ${quoted(rootfs, kind)} 2>/dev/null | awk '{s+=\$1} END {print s+0}'", 60) ?: "0"
        (out.trim().toLongOrNull() ?: 0L) * 1024L
    }

    /** Deletes the caches; idle Gradle daemons are stopped first so none keeps a cache file open. */
    fun clear(rootfs: File, kinds: Set<Kind>) {
        if (kinds.isEmpty()) return
        if (Kind.GRADLE in kinds) runRoot(GradleDaemonReaper.STOP_IDLE_SCRIPT, 30)
        val targets = kinds.joinToString(" ") { quoted(rootfs, it) }
        // The apt entry is a directory apt expects to exist: empty it instead of removing it.
        runRoot("rm -rf $targets; mkdir -p ${shellSingleQuote(File(rootfs, "var/cache/apt/archives/partial").absolutePath)}", 300)
    }

    private fun runRoot(command: String, timeoutSeconds: Long): String? = runCatching {
        val proc = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
        val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
        val watchdog = Thread({
            try {
                if (!proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                    timedOut.set(true)
                    proc.destroyForcibly()
                }
            } catch (_: InterruptedException) {
            }
        }, "forge-caches-watchdog").apply { isDaemon = true; start() }
        val output = try {
            proc.inputStream.bufferedReader().use { it.readText() }
        } finally {
            watchdog.interrupt()
            if (proc.isAlive) proc.destroyForcibly()
        }
        if (!timedOut.get() && proc.waitFor(1, TimeUnit.SECONDS)) output else null
    }.getOrNull()
}
