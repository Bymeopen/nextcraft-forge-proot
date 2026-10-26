package com.nextcraft.forge.update

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Updates this app without the system installer dialog, like a store does: root copies the verified APK to a staging
 * file, then a detached root script runs `pm install -r` and starts the app again. The script is detached because
 * replacing the package kills this process, so nothing here can finish the job; the app only watches the log until it
 * dies (success) or the log reports a failure (the caller then falls back to the normal installer dialog).
 */
object SilentSelfUpdate {
    internal const val STAGING_DIR = "/data/adb/.forge-update"
    internal const val STAGING_APK = "$STAGING_DIR/forge-self-update.apk"
    internal const val LOG_FILE = "$STAGING_DIR/forge-self-update.log"
    private const val WATCH_SECONDS = 150L
    private val SAFE_COMPONENT = Regex("""[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+""")

    /** The shell script that runs detached as root; [component] is `package/Activity` (validated, never free text). */
    internal fun detachedScript(component: String, allowDowngrade: Boolean = false): String? {
        if (!SAFE_COMPONENT.matches(component)) return null
        val flags = if (allowDowngrade) "-r -d" else "-r"
        val inner = "pm install $flags $STAGING_APK > $LOG_FILE 2>&1; rc=\$?; rm -f $STAGING_APK; " +
            "echo rc=\$rc >> $LOG_FILE; " +
            "if [ \$rc -eq 0 ]; then am start --user 0 -n $component >/dev/null 2>&1; fi"
        return "mkdir -p $STAGING_DIR && chmod 700 $STAGING_DIR && rm -f $LOG_FILE; nohup setsid sh -c '$inner' >/dev/null 2>&1 &"
    }

    /** True when the log written by the detached script ends with a failed install (`rc=` other than 0). */
    internal fun logShowsFailure(log: String): Boolean {
        val rc = Regex("""rc=(\d+)""").findAll(log).lastOrNull()?.groupValues?.get(1)?.toIntOrNull() ?: return false
        return rc != 0
    }

    /** Blocking; call off the main thread. Returns false when root is missing or the install failed (nothing was replaced). */
    fun install(context: Context, apk: File): Boolean = runCatching {
        if (!com.nextcraft.forge.runtime.RuntimeEngines.isRootEngine()) return@runCatching false
        if (!apk.isFile || apk.length() <= 0L) return@runCatching false
        val component = context.packageManager.getLaunchIntentForPackage(context.packageName)?.component
            ?.flattenToShortString()?.replace("/.", "/${context.packageName}.")
            ?: return@runCatching false
        val allowDebugDowngrade = com.nextcraft.forge.BuildConfig.DEBUG && hasMatchingSignature(context, apk)
        val script = detachedScript(component, allowDowngrade = allowDebugDowngrade) ?: return@runCatching false
        if (!stage(apk)) return@runCatching false
        if (rootRun(script, 20L) == null) return@runCatching false
        // Success ends this process while the loop waits; only a failure or a silent script gets past it.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCH_SECONDS)
        while (System.nanoTime() < deadline) {
            Thread.sleep(2_000L)
            val log = rootRun("cat $LOG_FILE 2>/dev/null", 10L).orEmpty()
            if (logShowsFailure(log)) return@runCatching false
        }
        false
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun hasMatchingSignature(context: Context, apk: File): Boolean = runCatching {
        val pm = context.packageManager
        val flag = android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
        val installed = pm.getPackageInfo(context.packageName, flag).signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flag)?.signingInfo?.apkContentsSigners?.map { it.toCharsString() }?.toSet()
        !installed.isNullOrEmpty() && installed == archive
    }.getOrDefault(false)

    private fun stage(apk: File): Boolean {
        val cmd = "mkdir -p $STAGING_DIR && chown 0:0 $STAGING_DIR && chmod 700 $STAGING_DIR && " +
            "rm -f $STAGING_APK && cat > $STAGING_APK && chown 0:0 $STAGING_APK && chmod 600 $STAGING_APK"
        val proc = ProcessBuilder("su", "-c", cmd)
            .redirectErrorStream(true)
            .start()
        return try {
            apk.inputStream().buffered().use { input -> proc.outputStream.use { input.copyTo(it) } }
            proc.waitFor(120L, TimeUnit.SECONDS) && proc.exitValue() == 0
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }

    private fun rootRun(command: String, timeoutSeconds: Long): String? {
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
        }, "forge-update-watchdog").apply { isDaemon = true; start() }
        return try {
            val text = proc.inputStream.bufferedReader().use { it.readText() }
            if (!timedOut.get() && proc.waitFor(1, TimeUnit.SECONDS)) text else null
        } finally {
            watchdog.interrupt()
            if (proc.isAlive) proc.destroyForcibly()
        }
    }
}
