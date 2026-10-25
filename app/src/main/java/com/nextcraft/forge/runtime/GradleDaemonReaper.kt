package com.nextcraft.forge.runtime

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import java.util.concurrent.TimeUnit

/**
 * Stops idle Gradle daemons in the PRoot runtime when Android runs short of memory. A warm daemon holds the
 * device-sized heap (3 GB on 10 GB+ phones) for its 10-minute idle timeout; under pressure Android would rather kill
 * this app than let it keep that memory, so the daemon goes first. The daemon process runs as the app's own Android
 * UID (proot has no real root), so it can be found and killed without `su`. A daemon that used CPU in the last
 * second is building and is left alone.
 */
internal object GradleDaemonReaper {
    /**
     * Kills each GradleDaemon whose user+system CPU time did not move over one second. The bracketed pattern keeps
     * pgrep from matching this script's own command line (which contains the pattern text).
     */
    internal const val STOP_IDLE_SCRIPT =
        "for p in \$(pgrep -f 'org[.]gradle[.]launcher[.]daemon[.]bootstrap[.]GradleDaemon' 2>/dev/null); do " +
            "a=\$(cut -d' ' -f14,15 /proc/\$p/stat 2>/dev/null); sleep 1; " +
            "b=\$(cut -d' ' -f14,15 /proc/\$p/stat 2>/dev/null); " +
            "[ -n \"\$a\" ] && [ \"\$a\" = \"\$b\" ] && kill \$p; done"

    @Volatile private var lastRunAt = 0L

    /** True for the trim levels (and low-memory state) where an idle daemon should give its memory back. */
    @Suppress("DEPRECATION")
    internal fun shouldStop(
        level: Int,
        lowMemory: Boolean,
        totalRamMb: Long = 8_192L,
        swapFreeMb: Long = 2_048L,
    ): Boolean {
        if (lowMemory || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            return true
        }
        // On <= 6 GB phones or when SwapRAM/ZRAM is nearly full (< 512 MB free), reap idle daemons earlier on RUNNING_LOW
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && (totalRamMb < 6_800L || swapFreeMb < 512L)) {
            return true
        }
        return false
    }

    fun onTrimMemory(context: Context, level: Int) {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
        val hw = DeviceHardwareOptimizer.inspect(context)
        if (!shouldStop(level, info.lowMemory, hw.totalRamMb, hw.swap.swapFreeMb)) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastRunAt < 30_000) return
        lastRunAt = now
        Thread({
            runCatching {
                val proc = ProcessBuilder("/system/bin/sh", "-c", STOP_IDLE_SCRIPT)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
                    .start()
                if (!proc.waitFor(20, TimeUnit.SECONDS)) proc.destroyForcibly()
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("GradleDaemonReaper", it) }
        }, "gradle-daemon-reaper").apply { isDaemon = true }.start()
    }
}
