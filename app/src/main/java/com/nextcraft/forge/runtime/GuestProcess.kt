package com.nextcraft.forge.runtime

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Reaches proot-exec's shell for a launch. The launch writes proot-exec's pid to a file; Stop signals that pid, and
 * proot-exec forwards the signal to the command's process group and reaps it (TERM, then KILL after a short grace
 * period). The shell runs as the app's own Android UID (proot has no real root), so a plain `kill` reaches it.
 */
internal object GuestProcess {
    /** The pid file that belongs to the launch using [envFile] (same random id). */
    fun pidFileFor(envFile: File): File =
        File(File(envFile.parentFile?.parentFile, "pids"), envFile.name.removePrefix(".env-").removeSuffix(".sh") + ".pid")

    /** Shell fragment that records the shell's pid; `exec chroot-exec` then keeps that pid. */
    fun recordPidCommand(pidFile: File): String = "echo \$\$ > ${shellSingleQuote(pidFile.absolutePath)}; "

    /**
     * Sends [signal] (2 = INT, 15 = TERM) to proot-exec. With [forceAfterSeconds], a KILL follows if it is still alive
     * then (proot-exec has already reaped the group by that time when it could).
     */
    fun signal(pidFile: File?, signal: Int, forceAfterSeconds: Int? = null) {
        val pid = pidFile?.takeIf { it.isFile }?.let { runCatching { it.readText().trim().toInt() }.getOrNull() } ?: return
        if (pid <= 1) return
        val force = forceAfterSeconds?.let { "; sleep $it; kill -0 $pid 2>/dev/null && kill -s KILL $pid" }.orEmpty()
        Thread({
            runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", "kill -s $signal $pid 2>/dev/null$force; true")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null")))
                    .start()
                if (!proc.waitFor((forceAfterSeconds ?: 0) + 10L, TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GuestProcess", it) }
        }, "guest-signal").apply { isDaemon = true }.start()
    }
}
