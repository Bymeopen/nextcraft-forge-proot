package com.nextcraft.forge.runtime

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class RuntimeExecutionService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var projectNameOverride: String? = null
    private var notificationTitleOverride: String? = null
    /** Resources in the language chosen inside the app; the service's own context follows the system language. */
    private fun t(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        com.nextcraft.forge.ui.LocaleHelper.wrapContext(this).getString(id, *args)

    private fun extraText(intent: Intent?, resKey: String, argsKey: String, vararg rawKeys: String): String? {
        if (intent == null) return null
        if (intent.hasExtra(resKey)) {
            val id = intent.getIntExtra(resKey, 0)
            if (id != 0) {
                val args: Array<String> = intent.getStringArrayExtra(argsKey) ?: emptyArray()
                runCatching { t(id, *args) }.getOrNull()?.let { return it }
            }
        }
        return rawKeys.firstNotNullOfOrNull { intent.getStringExtra(it) }
    }

    private val projectName: String
        get() = projectNameOverride ?: t(com.nextcraft.forge.R.string.notif_default_project)
    private val notificationTitle: String
        get() = notificationTitleOverride ?: t(com.nextcraft.forge.R.string.notif_runtime_active)
    private var canStop: Boolean = true
    /**
     * The agent and the Android build share this service and can run at the same time. Each START counts one
     * task and each COMPLETE/FAILED/CANCELLED ends one; foreground mode and the wake lock stay until the last
     * one ends, so one finishing does not take the protection away from the other.
     */
    private var activeTasks: Int = 0
    private val taskRunning: Boolean
        get() = activeTasks > 0

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START
        val incomingProjectName = intent?.getStringExtra(EXTRA_PROJECT_NAME)
            ?: intent?.getStringExtra(LEGACY_EXTRA_PROJECT_NAME)
        val incomingTitle = extraText(intent, EXTRA_TITLE_RES, EXTRA_DETAIL_ARGS, EXTRA_TITLE, LEGACY_EXTRA_TITLE)
        val incomingDetail = extraText(intent, EXTRA_DETAIL_RES, EXTRA_DETAIL_ARGS, EXTRA_DETAIL, LEGACY_EXTRA_DETAIL)

        incomingProjectName?.takeIf(String::isNotBlank)?.let { projectNameOverride = it }

        // Every start of this service, whatever the action, may have come through startForegroundService(): Android
        // then requires startForeground() promptly or it kills the app (ForegroundServiceDidNotStartInTimeException).
        // Promote first, unconditionally; the action handlers below may demote again right away.
        runCatching {
            startForeground(RUNNING_NOTIFICATION_ID, runningNotification(incomingDetail?.takeIf(String::isNotBlank) ?: "Working in $projectName", includeStop = canStop))
        }

        when (action) {
            ACTION_STOP, LEGACY_ACTION_STOP -> {
                RuntimeTaskController.requestStop()
                if (!taskRunning) {
                    // Service was recreated without an active task; nothing to show or keep alive.
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return START_NOT_STICKY
                }
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(t(com.nextcraft.forge.R.string.notif_stopping), includeStop = false),
                )
            }
            ACTION_PROGRESS, LEGACY_ACTION_PROGRESS -> {
                if (!taskRunning) {
                    // Stray progress for a finished task must not leave an idle service running.
                    stopSelf(startId)
                    return START_NOT_STICKY
                }
                incomingTitle?.takeIf(String::isNotBlank)?.let { notificationTitleOverride = it }
                if (intent?.hasExtra(EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                } else if (intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true) {
                    canStop = intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                }
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: t(com.nextcraft.forge.R.string.notif_working_in, projectName)
                getSystemService(NotificationManager::class.java)?.notify(
                    RUNNING_NOTIFICATION_ID,
                    runningNotification(detail, includeStop = canStop),
                )
            }
            ACTION_COMPLETE, LEGACY_ACTION_COMPLETE -> finishTask(
                title = t(com.nextcraft.forge.R.string.notif_task_completed),
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: t(com.nextcraft.forge.R.string.notif_finished_in, projectName),
                failed = false,
            )
            ACTION_FAILED, LEGACY_ACTION_FAILED -> finishTask(
                title = t(com.nextcraft.forge.R.string.notif_task_attention),
                detail = incomingDetail?.takeIf(String::isNotBlank) ?: t(com.nextcraft.forge.R.string.notif_could_not_finish),
                failed = true,
            )
            ACTION_CANCELLED, LEGACY_ACTION_CANCELLED -> {
                activeTasks = (activeTasks - 1).coerceAtLeast(0)
                if (!taskRunning) {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> {
                // Reset per-task overrides on fresh start so previous non-stoppable or custom-titled
                // tasks never bleed into subsequent coding sessions.
                notificationTitleOverride = incomingTitle?.takeIf(String::isNotBlank)
                canStop = when {
                    intent?.hasExtra(EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(EXTRA_CAN_STOP, true)
                    intent?.hasExtra(LEGACY_EXTRA_CAN_STOP) == true -> intent.getBooleanExtra(LEGACY_EXTRA_CAN_STOP, true)
                    else -> true
                }
                activeTasks++
                val detail = incomingDetail?.takeIf(String::isNotBlank)
                    ?: t(com.nextcraft.forge.R.string.notif_working_in, projectName)
                try {
                    startForeground(
                        RUNNING_NOTIFICATION_ID,
                        runningNotification(detail, includeStop = canStop),
                    )
                } catch (error: RuntimeException) {
                    // Android 12+ refuses foreground promotion once the app has moved to the background
                    // (ForegroundServiceStartNotAllowedException); crashing here would kill the running task.
                    android.util.Log.w("RuntimeExecutionService", "startForeground refused: ${error::class.simpleName}")
                    activeTasks = (activeTasks - 1).coerceAtLeast(0)
                    if (!taskRunning) stopSelf()
                    return START_NOT_STICKY
                }
                acquireWakeLock()
            }
        }
        return START_NOT_STICKY
    }

    private fun runningNotification(detail: String, includeStop: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, RUNNING_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(notificationTitle)
            .setContentText(detail)
            .setContentIntent(openAppIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (includeStop) {
            val stopIntent = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, t(com.nextcraft.forge.R.string.notif_stop_task), stopIntent)
        }
        return builder.build()
    }

    private fun finishTask(title: String, detail: String, failed: Boolean) {
        activeTasks = (activeTasks - 1).coerceAtLeast(0)
        if (!taskRunning) {
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setCategory(if (failed) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
        if (!taskRunning) stopSelf()
    }

    // Ikon status bar harus siluet satu warna, bukan ikon launcher adaptif
    private fun smallIconRes(): Int = com.nextcraft.forge.R.drawable.ic_notification

    private fun openAppIntent(): PendingIntent {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launchIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            1,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:active-coding-task")
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeExecutionService", it) }
        }
        wakeLock = null
    }

    /**
     * Android 15+ caps dataSync foreground services at about six hours per 24 hours and then calls this; the
     * service must stop promptly or the system raises an exception that kills the app. Commands already
     * running in the chroot are separate processes and are not touched.
     */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w("RuntimeExecutionService", "dataSync foreground time limit reached")
        activeTasks = 0
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = RuntimeExecutionServiceContract.ACTION_START
        const val ACTION_STOP = "com.nextcraft.forge.runtime.action.STOP"
        const val ACTION_PROGRESS = RuntimeExecutionServiceContract.ACTION_PROGRESS
        const val ACTION_COMPLETE = RuntimeExecutionServiceContract.ACTION_COMPLETE
        const val ACTION_FAILED = RuntimeExecutionServiceContract.ACTION_FAILED
        const val ACTION_CANCELLED = RuntimeExecutionServiceContract.ACTION_CANCELLED

        const val EXTRA_PROJECT_NAME = RuntimeExecutionServiceContract.EXTRA_PROJECT_NAME
        const val EXTRA_DETAIL = RuntimeExecutionServiceContract.EXTRA_DETAIL
        const val EXTRA_TITLE = "com.nextcraft.forge.runtime.extra.TITLE"
        const val EXTRA_TITLE_RES = "com.nextcraft.forge.runtime.extra.TITLE_RES"
        const val EXTRA_DETAIL_RES = RuntimeExecutionServiceContract.EXTRA_DETAIL_RES
        const val EXTRA_DETAIL_ARGS = RuntimeExecutionServiceContract.EXTRA_DETAIL_ARGS
        const val EXTRA_CAN_STOP = "com.nextcraft.forge.runtime.extra.CAN_STOP"

        private const val LEGACY_ACTION_STOP = "com.nextcraft.forge.STOP_RUNTIME"
        private const val LEGACY_ACTION_PROGRESS = "com.nextcraft.forge.PROGRESS_RUNTIME"
        private const val LEGACY_ACTION_COMPLETE = "com.nextcraft.forge.COMPLETE_RUNTIME"
        private const val LEGACY_ACTION_FAILED = "com.nextcraft.forge.FAIL_RUNTIME"
        private const val LEGACY_ACTION_CANCELLED = "com.nextcraft.forge.CANCEL_RUNTIME"
        private const val LEGACY_EXTRA_PROJECT_NAME = "project_name"
        private const val LEGACY_EXTRA_DETAIL = "detail"
        private const val LEGACY_EXTRA_TITLE = "title"
        private const val LEGACY_EXTRA_CAN_STOP = "can_stop"

        private const val RUNNING_CHANNEL_ID = "runtime"
        private const val RESULT_CHANNEL_ID = "task-results"
        private const val RUNNING_NOTIFICATION_ID = 41
        private const val RESULT_NOTIFICATION_ID = 42
        private const val MAX_WAKE_LOCK_MS = 90 * 60 * 1_000L

        fun ensureNotificationChannels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val localized = com.nextcraft.forge.ui.LocaleHelper.wrapContext(context)
            manager.createNotificationChannel(
                NotificationChannel(
                    RUNNING_CHANNEL_ID,
                    localized.getString(com.nextcraft.forge.R.string.notif_channel_running),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = localized.getString(com.nextcraft.forge.R.string.notif_channel_running_desc)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    localized.getString(com.nextcraft.forge.R.string.notif_channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = localized.getString(com.nextcraft.forge.R.string.notif_channel_results_desc)
                },
            )
        }
    }
}
