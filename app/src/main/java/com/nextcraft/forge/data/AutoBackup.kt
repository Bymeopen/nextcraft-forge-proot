package com.nextcraft.forge.data

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Scheduled backups to one file the user picked once with the system file picker (Google Drive, a folder on the phone,
 * an SD card…). The app keeps write access to that file and replaces its contents on each run, so Drive keeps its own
 * version history. The password of an encrypted auto backup is kept in [ApiKeyVault] (Android Keystore).
 */
class AutoBackupSettings(context: Context) {
    private val prefs = context.getSharedPreferences("nextcraft_auto_backup", Context.MODE_PRIVATE)
    private val vault = ApiKeyVault(context)

    enum class Interval(val days: Long) { OFF(0), DAILY(1), WEEKLY(7) }

    var interval: Interval
        get() = runCatching { Interval.valueOf(prefs.getString("interval", Interval.OFF.name)!!) }.getOrDefault(Interval.OFF)
        set(value) = prefs.edit().putString("interval", value.name).apply()

    /** The destination file (content:// URI with a persisted write permission), or null when none was chosen. */
    var target: Uri?
        get() = prefs.getString("target", null)?.let(Uri::parse)
        set(value) = prefs.edit().putString("target", value?.toString()).apply()

    /** Shown in the screen: the file name as the picker reported it. */
    var targetLabel: String
        get() = prefs.getString("target_label", "") ?: ""
        set(value) = prefs.edit().putString("target_label", value).apply()

    var includeApiKeys: Boolean
        get() = prefs.getBoolean("api_keys", false)
        set(value) = prefs.edit().putBoolean("api_keys", value).apply()

    var lastRunAt: Long
        get() = prefs.getLong("last_run_at", 0L)
        set(value) = prefs.edit().putLong("last_run_at", value).apply()

    /** Empty after a successful run, otherwise the error text. */
    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit().putString("last_error", value).apply()

    var password: String
        get() = vault.get(PASSWORD_ID).orEmpty()
        set(value) = if (value.isEmpty()) vault.remove(PASSWORD_ID) else vault.put(PASSWORD_ID, value)

    private companion object {
        const val PASSWORD_ID = "auto_backup_password"
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AutoBackupSettings(applicationContext)
        val target = settings.target ?: return Result.success()
        val password = settings.password
        return runCatching {
            val out = applicationContext.contentResolver.openOutputStream(target, "wt") ?: error("The backup file is no longer reachable")
            BackupManager(applicationContext).create(
                out,
                BackupManager.Options(apiKeys = settings.includeApiKeys && password.isNotEmpty()),
                password.toCharArray().takeIf { it.isNotEmpty() },
            )
        }.fold(
            onSuccess = {
                settings.lastRunAt = System.currentTimeMillis()
                settings.lastError = ""
                Result.success()
            },
            onFailure = {
                settings.lastRunAt = System.currentTimeMillis()
                settings.lastError = it.message ?: it.javaClass.simpleName
                // A cloud file may be briefly unreachable; WorkManager tries again with backoff.
                if (runAttemptCount < 3) Result.retry() else Result.failure()
            },
        )
    }

    companion object {
        private const val WORK_NAME = "auto-backup"
        private const val NOW_NAME = "auto-backup-now"

        /** Network for cloud destinations (Drive uploads in the background), and no run on a nearly empty battery. */
        private val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        /** Applies the saved interval: schedules the periodic run, or cancels it when off or without a destination. */
        fun sync(context: Context) {
            val settings = AutoBackupSettings(context)
            val manager = WorkManager.getInstance(context)
            if (settings.interval == AutoBackupSettings.Interval.OFF || settings.target == null) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(settings.interval.days, TimeUnit.DAYS)
                .setConstraints(constraints)
                .build()
            manager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun runNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                NOW_NAME,
                androidx.work.ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoBackupWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
