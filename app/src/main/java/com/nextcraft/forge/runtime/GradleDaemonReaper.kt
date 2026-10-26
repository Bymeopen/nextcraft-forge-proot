package com.nextcraft.forge.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Stops idle Gradle daemons in the chroot when Android runs short of memory. A warm daemon holds the device-sized heap
 * (3 GB on 10 GB+ phones) for its 10-minute idle timeout; under pressure Android would rather kill this app than a
 * root process, so the daemon goes first. A daemon that used CPU in the last second is building and is left alone.
 */
internal object GradleDaemonReaper {
    /**
     * Kills each GradleDaemon whose user+system CPU time did not move over one second. The bracketed pattern keeps
     * pgrep from matching this script's own command line (which contains the pattern text).
     */
    internal const val STOP_IDLE_SCRIPT =
        "for p in \$(pgrep -f 'org[.]gradle[.]launcher[.]daemon[.]bootstrap[.]GradleDaemon|[/]root/[.]local/bin/agy.*--input-format stream-json' 2>/dev/null); do " +
            "a=\$(cut -d' ' -f14,15 /proc/\$p/stat 2>/dev/null); sleep 1; " +
            "b=\$(cut -d' ' -f14,15 /proc/\$p/stat 2>/dev/null); " +
            "[ -n \"\$a\" ] && [ \"\$a\" = \"\$b\" ] && kill \$p; done; " +
            "sync 2>/dev/null || true; " +
            "echo 1 > /proc/sys/vm/compact_memory 2>/dev/null || true"

    @Volatile private var lastRunAt = 0L

    /** True for the trim levels (and low-memory state) where an idle daemon should give its memory back. */
    @Suppress("DEPRECATION")
    internal fun shouldStop(level: Int, lowMemory: Boolean): Boolean =
        lowMemory || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND

    private fun shellArgv(): List<String> =
        if (RuntimeEngines.isRootEngine()) listOf("su", "-c", STOP_IDLE_SCRIPT) else listOf("/system/bin/sh", "-c", STOP_IDLE_SCRIPT)

    fun stopIdleNow(onComplete: (() -> Unit)? = null) {
        Thread({
            runCatching {
                val proc = ProcessBuilder(shellArgv())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                    .start()
                if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GradleDaemonReaper", it) }
            onComplete?.invoke()
        }, "gradle-daemon-reaper-manual").apply { isDaemon = true }.start()
    }

    fun onTrimMemory(context: Context, level: Int) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        if (!shouldStop(level, info.lowMemory)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRunAt < 30_000) return
        lastRunAt = now
        Thread({
            runCatching {
                val proc = ProcessBuilder(shellArgv())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                    .start()
                if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GradleDaemonReaper", it) }
        }, "gradle-daemon-reaper").apply { isDaemon = true }.start()
    }
}
