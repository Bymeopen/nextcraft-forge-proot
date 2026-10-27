package com.nextcraft.forge

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Installs the crash recorder as early as possible, before any activity or service starts. */
class ForgeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Preference reads, the schedule JSON parse and WorkManager enqueues are not needed for the first frame, and
        // SharedPreferences/WorkManager are thread-safe, so they run off the main thread.
        Thread({
            runCatching {
                com.nextcraft.forge.schedule.ScheduleTickWorker.sync(this, com.nextcraft.forge.schedule.ScheduleStore(this).load().any { it.enabled })
                val updatePrefs = com.nextcraft.forge.data.AppPreferences(this)
                if (updatePrefs.updateNotifications || updatePrefs.autoInstallUpdates) {
                    com.nextcraft.forge.update.UpdateCheckWorker.schedule(this)
                }
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ForgeApplication", it) }
        }, "forge-startup-workers").apply { isDaemon = true }.start()
    }
}

/**
 * Keeps the full stack trace of the last crash on disk so it can be read and copied from the app
 * (Tools → System Diagnostics) even when logcat is not available. Only the newest report is kept.
 */
object CrashReporter {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_CHARS = 60_000

    fun install(context: Context) {
        val file = reportFile(context)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                file.parentFile?.mkdirs()
                val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("Time: $time")
                    appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    append(Log.getStackTraceString(error))
                }
                file.writeText(report.take(MAX_CHARS))
            }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ForgeApplication", it) }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? = reportFile(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        reportFile(context).delete()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("forge_crash", Context.MODE_PRIVATE)

    /** True when a report exists that the person has not yet been asked about. */
    fun hasUnseen(context: Context): Boolean {
        val file = reportFile(context)
        return file.isFile && file.lastModified() != prefs(context).getLong("seen_modified", 0L)
    }

    /** Remembers that this report was shown, so the prompt appears once per crash. */
    fun markSeen(context: Context) {
        prefs(context).edit().putLong("seen_modified", reportFile(context).lastModified()).apply()
    }

    /** Share sheet for the report. Nothing leaves the phone unless the person picks an app and sends it. */
    fun shareIntent(context: Context): android.content.Intent? {
        val report = read(context) ?: return null
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(android.content.Intent.EXTRA_SUBJECT, "NextCraft Forge crash report")
            .putExtra(android.content.Intent.EXTRA_TEXT, report)
        return android.content.Intent.createChooser(send, null)
    }

    private fun reportFile(context: Context) = File(context.filesDir, "crash/$FILE_NAME")
}
