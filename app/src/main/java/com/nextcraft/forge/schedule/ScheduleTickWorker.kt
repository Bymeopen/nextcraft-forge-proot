package com.nextcraft.forge.schedule

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.nextcraft.forge.R
import java.util.concurrent.TimeUnit

/** True while the app's own timer (in the view model) is running; the worker then has nothing to add. */
object ScheduleRuntime {
    @Volatile var tickerActive: Boolean = false
}

/**
 * Safety net every 15 minutes (the WorkManager minimum). The app's own timer runs the tasks while the process lives; when
 * Android has killed the process, this tells the user that tasks are waiting, and opening the app runs them.
 */
class ScheduleTickWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        if (ScheduleRuntime.tickerActive) return Result.success()
        val now = System.currentTimeMillis()
        val due = ScheduleStore(applicationContext).load().count { it.isDue(now) }
        if (due > 0) notifyDue(applicationContext, due)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "schedule-tick"
        private const val CHANNEL = "scheduled_tasks"
        private const val NOTIFICATION_ID = 7312

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScheduleTickWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /**
         * Keeps the 15-minute check only while at least one enabled task exists. It used to be enqueued for everyone at
         * every launch, waking the phone four times an hour to find nothing to do.
         */
        fun sync(context: Context, hasEnabledTasks: Boolean) {
            runCatching { if (hasEnabledTasks) schedule(context) else cancel(context) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ScheduleTickWorker", it) }
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }

        private fun notifyDue(context: Context, count: Int) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.schedule_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)
            }
            val text = context.resources.getQuantityString(R.plurals.schedule_due_notification, count, count)
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.schedule_title))
                .setContentText(text)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            runCatching { manager.notify(NOTIFICATION_ID, notification) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ScheduleTickWorker", it) }
        }
    }
}
