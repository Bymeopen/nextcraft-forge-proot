package com.nextcraft.forge.data

import androidx.core.content.edit
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.io.File
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
        set(value) = prefs.edit {putString("interval", value.name)}

    /** The destination file (content:// URI with a persisted write permission), or null when none was chosen. */
    var target: Uri?
        get() = prefs.getString("target", null)?.let(Uri::parse)
        set(value) = prefs.edit {putString("target", value?.toString())}

    /**
     * Optional destination folder (tree URI, e.g. a Drive folder). When set it wins over [target]: each run adds a
     * time-stamped file and only the newest [KEEP_VERSIONS] are kept.
     */
    var folder: Uri?
        get() = prefs.getString("folder", null)?.let(Uri::parse)
        set(value) = prefs.edit {putString("folder", value?.toString())}

    /** Shown in the screen: the file name as the picker reported it. */
    var targetLabel: String
        get() = prefs.getString("target_label", "") ?: ""
        set(value) = prefs.edit {putString("target_label", value)}

    var includeApiKeys: Boolean
        get() = prefs.getBoolean("api_keys", false)
        set(value) = prefs.edit {putBoolean("api_keys", value)}

    var lastRunAt: Long
        get() = prefs.getLong("last_run_at", 0L)
        set(value) = prefs.edit {putLong("last_run_at", value)}

    /** Empty after a successful run, otherwise the error text. */
    var lastError: String
        get() = prefs.getString("last_error", "") ?: ""
        set(value) = prefs.edit {putString("last_error", value)}

    var password: String
        get() = vault.get(PASSWORD_ID).orEmpty()
        set(value) = if (value.isEmpty()) vault.remove(PASSWORD_ID) else vault.put(PASSWORD_ID, value)

    companion object {
        private const val PASSWORD_ID = "auto_backup_password"
        const val KEEP_VERSIONS = 5
    }
}

class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = AutoBackupSettings(applicationContext)
        val folder = settings.folder
        val target = settings.target
        if (folder == null && target == null) return Result.success()
        val password = settings.password
        return runCatching {
            // Build the archive in a private temp file first so a failure mid-way never truncates the previous backup.
            val temp = File.createTempFile("auto-backup-", ".tmp", applicationContext.cacheDir)
            try {
                BackupManager(applicationContext).create(
                    temp.outputStream(),
                    BackupManager.Options(apiKeys = settings.includeApiKeys && password.isNotEmpty()),
                    password.toCharArray().takeIf { it.isNotEmpty() },
                )
                if (folder != null) {
                    writeVersioned(folder, temp)
                } else {
                    val out = applicationContext.contentResolver.openOutputStream(target!!, "wt") ?: error("The backup file is no longer reachable")
                    out.use { sink -> temp.inputStream().use { it.copyTo(sink) } }
                }
            } finally {
                temp.delete()
            }
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

    /** Adds a time-stamped backup to [tree], then deletes all but the newest [AutoBackupSettings.KEEP_VERSIONS]. */
    private fun writeVersioned(tree: Uri, source: File) {
        val resolver = applicationContext.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val created = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", "$VERSION_PREFIX$stamp$VERSION_SUFFIX")
            ?: error("The backup folder is no longer reachable")
        try {
            val out = resolver.openOutputStream(created, "wt") ?: error("The backup folder is no longer writable")
            out.use { sink -> source.inputStream().use { it.copyTo(sink) } }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            throw e
        }
        // Prune best effort: a failed delete must not fail a backup that was written.
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val old = mutableListOf<Pair<String, Uri>>()
        runCatching {
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    if (name.startsWith(VERSION_PREFIX) && name.endsWith(VERSION_SUFFIX)) {
                        old += name to DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0))
                    }
                }
            }
        }
        // Names embed a sortable timestamp, so name order is age order.
        old.sortedByDescending { it.first }.drop(AutoBackupSettings.KEEP_VERSIONS).forEach { (_, uri) ->
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
    }

    companion object {
        private const val VERSION_PREFIX = "nextcraft-auto-backup-"
        private const val VERSION_SUFFIX = ".ncbak"
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
            if (settings.interval == AutoBackupSettings.Interval.OFF || (settings.target == null && settings.folder == null)) {
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
