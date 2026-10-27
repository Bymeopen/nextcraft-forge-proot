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
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.R
import com.nextcraft.forge.ui.LocaleHelper

class RuntimeExecutionService : Service() {
    private var cpuLock: PowerManager.WakeLock? = null
    private var customProjectLabel: String? = null
    private var customHeadingLabel: String? = null
    private var allowUserStop: Boolean = true
    private var runningTaskCount: Int = 0

    private val hasRunningTasks: Boolean
        get() = runningTaskCount > 0

    private val activeProjectLabel: String
        get() = customProjectLabel ?: tr(R.string.notif_default_project)

    private val activeHeadingLabel: String
        get() = customHeadingLabel ?: tr(R.string.notif_runtime_active)

    private fun tr(@StringRes resId: Int, vararg formatArgs: Any): String =
        LocaleHelper.wrapContext(this).getString(resId, *formatArgs)

    private fun resolveIntentString(
        intent: Intent?,
        resExtraKey: String,
        argsExtraKey: String,
        vararg fallbackKeys: String,
    ): String? {
        if (intent == null) return null
        if (intent.hasExtra(resExtraKey)) {
            val resId = intent.getIntExtra(resExtraKey, 0)
            if (resId != 0) {
                val fmtArgs = intent.getStringArrayExtra(argsExtraKey) ?: emptyArray()
                val formatted = runCatching { tr(resId, *fmtArgs) }.getOrNull()
                if (formatted != null) return formatted
            }
        }
        return fallbackKeys.firstNotNullOfOrNull { key -> intent.getStringExtra(key) }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmd = intent?.action ?: ACTION_START
        val projArg = intent?.getStringExtra(EXTRA_PROJECT_NAME) ?: intent?.getStringExtra(OLD_EXTRA_PROJECT)
        val titleArg = resolveIntentString(intent, EXTRA_TITLE_RES, EXTRA_DETAIL_ARGS, EXTRA_TITLE, OLD_EXTRA_TITLE)
        val detailArg = resolveIntentString(intent, EXTRA_DETAIL_RES, EXTRA_DETAIL_ARGS, EXTRA_DETAIL, OLD_EXTRA_DETAIL)

        if (!projArg.isNullOrBlank()) {
            customProjectLabel = projArg
        }

        when (cmd) {
            ACTION_STOP, OLD_ACTION_STOP -> handleStopCommand()
            ACTION_PROGRESS, OLD_ACTION_PROGRESS -> handleProgressCommand(intent, startId, titleArg, detailArg)
            ACTION_COMPLETE, OLD_ACTION_COMPLETE -> completeOneTask(
                heading = tr(R.string.notif_task_completed),
                body = detailArg?.takeIf { it.isNotBlank() } ?: tr(R.string.notif_finished_in, activeProjectLabel),
                isError = false,
            )
            ACTION_FAILED, OLD_ACTION_FAILED -> completeOneTask(
                heading = tr(R.string.notif_task_attention),
                body = detailArg?.takeIf { it.isNotBlank() } ?: tr(R.string.notif_could_not_finish),
                isError = true,
            )
            ACTION_CANCELLED, OLD_ACTION_CANCELLED -> {
                runningTaskCount = (runningTaskCount - 1).coerceAtLeast(0)
                if (!hasRunningTasks) {
                    releaseCpuLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            else -> handleStartCommand(intent, titleArg, detailArg)
        }
        return START_NOT_STICKY
    }

    private fun handleStopCommand() {
        RuntimeTaskController.requestStop()
        if (!hasRunningTasks) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIF_ID_ACTIVE,
            buildActiveNotification(tr(R.string.notif_stopping), showStopAction = false),
        )
    }

    private fun handleProgressCommand(intent: Intent?, startId: Int, titleArg: String?, detailArg: String?) {
        if (!hasRunningTasks) {
            stopSelf(startId)
            return
        }
        if (!titleArg.isNullOrBlank()) {
            customHeadingLabel = titleArg
        }
        readCanStopExtra(intent)?.let { allowUserStop = it }
        val bodyText = detailArg?.takeIf { it.isNotBlank() } ?: tr(R.string.notif_working_in, activeProjectLabel)
        getSystemService(NotificationManager::class.java)?.notify(
            NOTIF_ID_ACTIVE,
            buildActiveNotification(bodyText, showStopAction = allowUserStop),
        )
    }

    private fun handleStartCommand(intent: Intent?, titleArg: String?, detailArg: String?) {
        customHeadingLabel = titleArg?.takeIf { it.isNotBlank() }
        allowUserStop = readCanStopExtra(intent) ?: true
        runningTaskCount++

        val bodyText = detailArg?.takeIf { it.isNotBlank() } ?: tr(R.string.notif_working_in, activeProjectLabel)
        try {
            startForeground(NOTIF_ID_ACTIVE, buildActiveNotification(bodyText, showStopAction = allowUserStop))
        } catch (err: RuntimeException) {
            Log.w(LOG_TAG, "startForeground refused: ${err::class.simpleName}")
            runningTaskCount = (runningTaskCount - 1).coerceAtLeast(0)
            if (!hasRunningTasks) stopSelf()
            return
        }
        acquireCpuLock()
    }

    private fun readCanStopExtra(intent: Intent?): Boolean? = when {
        intent == null -> null
        intent.hasExtra(EXTRA_CAN_STOP) -> intent.getBooleanExtra(EXTRA_CAN_STOP, true)
        intent.hasExtra(OLD_EXTRA_CAN_STOP) -> intent.getBooleanExtra(OLD_EXTRA_CAN_STOP, true)
        else -> null
    }

    private fun buildActiveNotification(bodyText: String, showStopAction: Boolean): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ACTIVE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(activeHeadingLabel)
            .setContentText(bodyText)
            .setContentIntent(buildOpenAppPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (showStopAction) {
            val stopPending = PendingIntent.getService(
                this,
                2,
                Intent(this, RuntimeExecutionService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, tr(R.string.notif_stop_task), stopPending)
        }
        return builder.build()
    }

    private fun completeOneTask(heading: String, body: String, isError: Boolean) {
        runningTaskCount = (runningTaskCount - 1).coerceAtLeast(0)
        if (!hasRunningTasks) {
            releaseCpuLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
        val resultNotif = NotificationCompat.Builder(this, CHANNEL_OUTCOME)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(buildOpenAppPendingIntent())
            .setAutoCancel(true)
            .setCategory(if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID_OUTCOME, resultNotif)
        if (!hasRunningTasks) stopSelf()
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        launch.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            1,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireCpuLock() {
        if (cpuLock?.isHeld == true) return
        cpuLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:active-coding-task")
            ?.apply { acquire(WAKE_HOLD_TIMEOUT_MS) }
    }

    private fun releaseCpuLock() {
        cpuLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        }
        cpuLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "dataSync foreground time limit reached")
        runningTaskCount = 0
        releaseCpuLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseCpuLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val LOG_TAG = "RuntimeExecutionService"

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

        private const val OLD_ACTION_STOP = "com.nextcraft.forge.STOP_RUNTIME"
        private const val OLD_ACTION_PROGRESS = "com.nextcraft.forge.PROGRESS_RUNTIME"
        private const val OLD_ACTION_COMPLETE = "com.nextcraft.forge.COMPLETE_RUNTIME"
        private const val OLD_ACTION_FAILED = "com.nextcraft.forge.FAIL_RUNTIME"
        private const val OLD_ACTION_CANCELLED = "com.nextcraft.forge.CANCEL_RUNTIME"
        private const val OLD_EXTRA_PROJECT = "project_name"
        private const val OLD_EXTRA_DETAIL = "detail"
        private const val OLD_EXTRA_TITLE = "title"
        private const val OLD_EXTRA_CAN_STOP = "can_stop"

        private const val CHANNEL_ACTIVE = "runtime"
        private const val CHANNEL_OUTCOME = "task-results"
        private const val NOTIF_ID_ACTIVE = 41
        private const val NOTIF_ID_OUTCOME = 42
        private const val WAKE_HOLD_TIMEOUT_MS = 35L * 60L * 1_000L

        fun ensureNotificationChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            val wrapped = LocaleHelper.wrapContext(context)
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ACTIVE,
                    wrapped.getString(R.string.notif_channel_running),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = wrapped.getString(R.string.notif_channel_running_desc)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_OUTCOME,
                    wrapped.getString(R.string.notif_channel_results),
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = wrapped.getString(R.string.notif_channel_results_desc)
                },
            )
        }
    }
}
