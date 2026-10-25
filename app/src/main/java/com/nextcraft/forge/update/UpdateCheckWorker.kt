package com.nextcraft.forge.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import com.nextcraft.forge.data.AppPreferences
import java.util.concurrent.TimeUnit

/** Checks for a newer build in the background and announces it; errors (no network, no release) just wait for the next round. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val preferences = AppPreferences(applicationContext)
        if (!preferences.updateNotifications && !preferences.autoInstallUpdates) return Result.success()
        val info = runCatching { AppUpdater(applicationContext).check() }.getOrNull() ?: return Result.success()
        if (preferences.autoInstallUpdates && canInstallNow() && installInBackground(info)) return Result.success()
        if (preferences.updateNotifications) UpdateNotifier.notifyIfNew(applicationContext, info)
        return Result.success()
    }

    /** Only while nobody uses the app: no open screen (view model gone), no agent or build running, no mobile data. */
    private fun canInstallNow(): Boolean {
        if (com.nextcraft.forge.schedule.ScheduleRuntime.tickerActive) return false
        if (com.nextcraft.forge.runtime.RuntimeTaskController.hasActiveTask()) return false
        val connectivity = applicationContext.getSystemService(android.net.ConnectivityManager::class.java)
        return connectivity?.isActiveNetworkMetered == false
    }

    /**
     * Downloads (verified by [AppUpdater]) and starts the install. There is no root to install silently under
     * PRoot, so this still shows Android's own install confirmation -- it just happens while nobody is using the
     * app instead of interrupting an active session.
     */
    private fun installInBackground(info: AppUpdateInfo): Boolean = runCatching {
        val apk = AppUpdater(applicationContext).download(info) { _, _ -> }
        com.nextcraft.forge.runtime.AndroidAppInstaller.install(applicationContext, apk, launchAfterInstall = false)
        true
    }.getOrDefault(false)


    companion object {
        private const val WORK_NAME = "app-update-check"

        /** Every 6 hours while the network is up; the same request is kept when scheduled again. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
