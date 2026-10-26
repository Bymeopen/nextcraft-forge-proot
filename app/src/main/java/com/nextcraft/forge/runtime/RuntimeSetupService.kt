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
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.stringOrEmpty
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class RuntimeSetupStatus { IDLE, RUNNING, COMPLETE, ERROR, CANCELLED }

data class RuntimeSetupSnapshot(
    val status: RuntimeSetupStatus = RuntimeSetupStatus.IDLE,
    val message: String = "Preparing your private coding workspace",
    val progress: Float = 0f,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val indeterminate: Boolean = false,
    val logs: List<String> = emptyList(),
    val errorMessage: String? = null,
    val offline: Boolean = false,
)

object RuntimeSetupController {
    private val mutableSnapshot = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = mutableSnapshot.asStateFlow()

    @Synchronized
    fun restore(context: Context) {
        val file = stateFile(context)
        if (!file.isFile) return
        // A live run in this process is newer than the file; never overwrite it.
        if (mutableSnapshot.value.status == RuntimeSetupStatus.RUNNING) return
        runCatching {
            val json = JSONObject(file.readText())
            val logsJson = json.optJSONArray("logs") ?: JSONArray()
            val logs = (0 until logsJson.length()).map { logsJson.optString(it) }
            val rawProgress = json.optDouble("progress", 0.0).toFloat()
            val safeProgress = if (rawProgress.isFinite()) rawProgress.coerceIn(0f, 1f) else 0f
            val persisted = runCatching { RuntimeSetupStatus.valueOf(json.optString("status")) }
                .getOrDefault(RuntimeSetupStatus.IDLE)
            // RUNNING read from disk in a fresh process means the app was killed mid-setup: there is
            // no service behind it, so show it as paused instead of spinning forever.
            val interrupted = persisted == RuntimeSetupStatus.RUNNING
            mutableSnapshot.value = RuntimeSetupSnapshot(
                status = if (interrupted) RuntimeSetupStatus.CANCELLED else persisted,
                message = if (interrupted) "Runtime setup was interrupted" else
                    json.stringOrEmpty("message", DEFAULT_PREPARING_MESSAGE).ifBlank { DEFAULT_PREPARING_MESSAGE },
                progress = safeProgress,
                downloadedBytes = json.optLongOrNull("downloadedBytes"),
                totalBytes = json.optLongOrNull("totalBytes"),
                indeterminate = json.optBoolean("indeterminate"),
                logs = logs.takeLast(MAX_LOG_LINES),
                errorMessage = json.stringOrEmpty("errorMessage").takeIf(String::isNotBlank),
                offline = json.optBoolean("offline"),
            )
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeSetupService", it) }
    }

    @Synchronized
    fun begin(context: Context) {
        val previous = mutableSnapshot.value.logs
        val logs = (previous + "— Resuming NextCraft Forge setup —").takeLast(MAX_LOG_LINES)
        set(
            context,
            RuntimeSetupSnapshot(
                status = RuntimeSetupStatus.RUNNING,
                message = DEFAULT_PREPARING_MESSAGE,
                progress = 0.01f,
                logs = logs,
            ),
        )
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val current = mutableSnapshot.value
        val line = event.terminalLine ?: when {
            event.downloadedBytes != null && event.totalBytes != null ->
                "${if (event.unpacking) UNPACK_PREFIX else DOWNLOAD_PREFIX} %.1f / %.1f MB".format(event.downloadedBytes / MB, event.totalBytes / MB)
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }
        val sanitizedLine = line?.takeIf(String::isNotBlank)?.let(::sanitize)
        val isDownloadUpdate = event.downloadedBytes != null && event.totalBytes != null
        val logs = when {
            sanitizedLine == null -> current.logs
            isDownloadUpdate && current.logs.lastOrNull().isProgressLine() ->
                (current.logs.dropLast(1) + sanitizedLine).takeLast(MAX_LOG_LINES)
            else -> (current.logs + sanitizedLine).takeLast(MAX_LOG_LINES)
        }
        if (!isDownloadUpdate || !current.logs.lastOrNull().isProgressLine()) {
            appendLog(context, sanitizedLine)
        }
        val safeFraction = if (event.fraction.isFinite()) event.fraction.coerceIn(0f, 1f) else current.progress
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.RUNNING,
                message = when (event.event) {
                    RuntimeInstallEvent.STAGE, RuntimeInstallEvent.DOWNLOAD -> event.message
                    else -> current.message
                },
                progress = maxOf(current.progress, safeFraction),
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = logs,
                errorMessage = null,
                offline = false,
            ),
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = "Linux chroot runtime is ready",
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (current.logs + "✓ Setup completed successfully").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val causes = generateSequence(error as Throwable?) { it.cause }.toList()
        val offline = causes.any {
            it is UnknownHostException ||
                it.message.orEmpty().contains("unable to resolve host", true) ||
                it.message.orEmpty().contains("no address associated with hostname", true)
        }
        val interruptedDpkg = causes.any {
            it.message.orEmpty().contains("dpkg was interrupted", true) ||
                it.message.orEmpty().contains("dpkg --configure -a", true)
        }
        val friendly = when {
            offline -> "No internet connection. Connect to Wi-Fi or mobile data and retry."
            interruptedDpkg -> "Package installation was interrupted. Retry setup to repair automatically."
            else -> error.message.orEmpty().lineSequence().lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: "Runtime setup could not complete."
        }
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = friendly,
                offline = offline,
                indeterminate = false,
                logs = (current.logs + "✕ $friendly").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val current = mutableSnapshot.value
        set(
            context,
            current.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = "Runtime setup paused",
                indeterminate = false,
                logs = (current.logs + "• Setup paused safely").takeLast(MAX_LOG_LINES),
            ),
        )
    }

    fun fullLog(context: Context): String = logFile(context).readTailText(MAX_LOG_BYTES.toInt())

    private fun set(context: Context, value: RuntimeSetupSnapshot) {
        mutableSnapshot.value = value
        persist(context, value)
    }

    private fun persist(context: Context, value: RuntimeSetupSnapshot) {
        runCatching {
            val safeProgress = if (value.progress.isFinite()) value.progress.coerceIn(0f, 1f) else 0f
            val json = JSONObject()
                .put("status", value.status.name)
                .put("message", value.message)
                .put("progress", safeProgress.toDouble())
                .put("indeterminate", value.indeterminate)
                .put("offline", value.offline)
                .put("logs", JSONArray(value.logs))
            value.downloadedBytes?.let { json.put("downloadedBytes", it) }
            value.totalBytes?.let { json.put("totalBytes", it) }
            value.errorMessage?.let { json.put("errorMessage", it) }
            val target = stateFile(context)
            val staged = File(target.parentFile, "${target.name}.tmp")
            staged.writeText(json.toString())
            if (!staged.renameTo(target)) {
                target.delete()
                if (!staged.renameTo(target)) {
                    android.util.Log.e("SetupController", "Failed to persist setup state — data may be lost")
                }
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeSetupService", it) }
    }

    private fun appendLog(context: Context, line: String?) {
        if (line.isNullOrBlank()) return
        runCatching {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            file.appendText(sanitize(line) + "\n")
            if (file.length() > MAX_LOG_BYTES) {
                val tail = file.readTailText(MAX_LOG_BYTES.toInt())
                file.writeText(tail.substringAfter('\n', tail))
            }
        }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeSetupService", it) }
    }

    private fun sanitize(line: String): String = line.filter { it == '\t' || it.code >= 32 }.take(500)
    private fun stateFile(context: Context) = File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }
    private fun logFile(context: Context) = File(context.filesDir, "setup/runtime-setup.log")
    private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null

    private const val DEFAULT_PREPARING_MESSAGE = "Preparing your private coding workspace"
    private const val MAX_LOG_LINES = 400
    private const val MAX_LOG_BYTES = 1_000_000L
    private const val MB = 1_048_576.0
    private const val DOWNLOAD_PREFIX = "Downloading:"
    private const val UNPACK_PREFIX = "Unpacking:"

    private fun String?.isProgressLine() = this != null && (startsWith(DOWNLOAD_PREFIX) || startsWith(UNPACK_PREFIX))
}

class RuntimeSetupService : Service() {
    private val serviceJob = SupervisorJob()
    private val scope = CoroutineScope(serviceJob + Dispatchers.IO)
    private var installJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockAcquiredAt = 0L
    private var lastWakeLockRenewAt = 0L
    private var lastNotificationAt = 0L

    /** Resources in the language chosen inside the app; the service's own context follows the system language. */
    private fun t(@androidx.annotation.StringRes id: Int, vararg args: Any): String =
        com.nextcraft.forge.ui.LocaleHelper.wrapContext(this).getString(id, *args)

    /** Installer sentences are English; known ones are shown in the app language (same table the install panel uses). */
    private fun localizedRuntimeText(raw: String): String =
        com.nextcraft.forge.ui.RuntimeText.match(raw)?.let { (id, args) -> t(id, *args.toTypedArray()) } ?: raw

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // A stale Pause tap (after the setup finished or failed) must not overwrite that result with "cancelled".
            val running = installJob?.takeIf { it.isActive }
            if (running != null) {
                running.cancel(CancellationException("Stopped by user"))
                RuntimeSetupController.cancelled(this)
            }
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
        } catch (error: RuntimeException) {
            // Android 12+ refuses foreground promotion once the app is in the background; the install can still
            // run (it resumes from its .part files), so do not crash the process.
            android.util.Log.w("RuntimeSetupService", "startForeground refused: ${error::class.simpleName}")
        }
        acquireWakeLock()
        val previousJob = installJob
        // A cancelled job can still be winding down; start the new one after it instead of dropping the request.
        if (previousJob?.isActive != true || previousJob.isCancelled) {
            val stacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty().split(',')
                .mapNotNull { name -> runCatching { DevStack.valueOf(name.trim()) }.getOrNull() }
                .toSet()
            val agent = AgentKind.fromStored(intent?.getStringExtra(EXTRA_AGENT)?.trim())
            val action = intent?.action
            val expectedVersion = intent?.getStringExtra(EXTRA_VERSION).orEmpty()
            RuntimeSetupController.begin(this)
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    previousJob?.join()
                    val installer = RuntimeInstaller(this@RuntimeSetupService)
                    val onProgress: suspend (RuntimeInstallProgress) -> Unit = { progress ->
                        RuntimeSetupController.update(this@RuntimeSetupService, progress)
                        updateNotification(progress.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                        renewWakeLock()
                    }
                    // Manual per-component operations work on top of an existing Core runtime.
                    when (action) {
                        ACTION_INSTALL_AGENT -> installer.ensureAgentInstalled(agent, onProgress)
                        ACTION_UPDATE_AGENT -> installer.updateAgent(agent, expectedVersion, onProgress)
                        ACTION_REMOVE_AGENT -> {
                            onProgress(RuntimeInstallProgress("Removing ${agent.title}…", 0.5f, indeterminate = true))
                            installer.removeAgent(agent)
                            onProgress(RuntimeInstallProgress("Removed ${agent.title}", 1f))
                        }
                        ACTION_INSTALL_STACK -> stacks.forEach { installer.ensureStackInstalled(it, onProgress) }
                        ACTION_REMOVE_STACK -> stacks.forEach { installer.removeStack(it, onProgress) }
                        else -> installer.ensureInstalled(stacks, agent, onProgress)
                    }
                    if (action == ACTION_START) AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    showFinishedNotification(success = true)
                } catch (_: CancellationException) {
                    // Stop followed by an immediate restart: the old job unwinding must not mark the new run as paused.
                    if (installJob === coroutineContext[Job]) RuntimeSetupController.cancelled(this@RuntimeSetupService)
                } catch (error: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, error)
                    showFinishedNotification(success = false)
                } finally {
                    // Only the newest job may tear the service down, or a resumed setup would be killed by the old one.
                    if (installJob === coroutineContext[Job]) {
                        releaseWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            installJob = job
            job.start()
        }
        return START_REDELIVER_INTENT
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotificationAt < NOTIFICATION_THROTTLE_MS) return
        lastNotificationAt = now
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, setupNotification(RuntimeSetupController.snapshot.value))
    }

    private fun setupNotification(state: RuntimeSetupSnapshot): Notification {
        val latest = state.logs.lastOrNull().orEmpty().take(180)
        val bodyText = localizedRuntimeText(latest.ifBlank { state.message })
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(t(com.nextcraft.forge.R.string.notif_setup_title))
            .setContentText(bodyText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bodyText))
            .setContentIntent(openAppIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
        if (state.indeterminate) {
            builder.setProgress(0, 0, true)
        } else {
            builder.setProgress(100, (state.progress * 100).toInt().coerceIn(0, 100), false)
        }
        builder.addAction(
            0,
            t(com.nextcraft.forge.R.string.notif_pause),
            PendingIntent.getService(
                this,
                102,
                Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        return builder.build()
    }

    private fun showFinishedNotification(success: Boolean) {
        val state = RuntimeSetupController.snapshot.value
        val title = if (success) t(com.nextcraft.forge.R.string.rt_msg_chroot_ready) else t(com.nextcraft.forge.R.string.notif_setup_attention)
        val detail = if (success) {
            t(com.nextcraft.forge.R.string.rt_msg_env_ready)
        } else {
            state.errorMessage?.let(::localizedRuntimeText) ?: t(com.nextcraft.forge.R.string.notif_setup_error)
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIconRes())
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(RESULT_NOTIFICATION_ID, notification)
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
            101,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLockAcquiredAt = SystemClock.elapsedRealtime()
        lastWakeLockRenewAt = wakeLockAcquiredAt
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:runtime-setup")
            // Not reference counted, so acquire() can re-arm the timeout without needing a matching release().
            ?.apply { setReferenceCounted(false) }
            ?.apply { acquire(MAX_WAKE_LOCK_MS) }
    }

    /**
     * A full setup (system maintenance up to 35 min, each apt step up to 30 min, plus downloads) can outlast one
     * wake lock timeout; once it expired the CPU could sleep with the screen off and stall the setup. Progress
     * re-arms it, up to [MAX_SETUP_WAKE_MS] in total so a hung setup cannot keep the device awake forever.
     */
    private fun renewWakeLock() {
        val lock = wakeLock ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastWakeLockRenewAt < WAKE_LOCK_RENEW_INTERVAL_MS) return
        if (now - wakeLockAcquiredAt > MAX_SETUP_WAKE_MS) return
        lastWakeLockRenewAt = now
        runCatching { lock.acquire(MAX_WAKE_LOCK_MS) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeSetupService", it) }
    }

    private fun releaseWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("RuntimeSetupService", it) }
        }
        wakeLock = null
    }

    /** Android 15+ dataSync time limit (about six hours per 24 hours): stop promptly; downloads resume from .part files. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        android.util.Log.w("RuntimeSetupService", "dataSync foreground time limit reached")
        installJob?.takeIf { it.isActive }?.cancel(CancellationException("Foreground time limit reached"))
        releaseWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        releaseWakeLock()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "com.nextcraft.forge.START_SETUP"
        const val ACTION_INSTALL_AGENT = "com.nextcraft.forge.INSTALL_AGENT"
        const val ACTION_UPDATE_AGENT = "com.nextcraft.forge.UPDATE_AGENT"
        const val ACTION_REMOVE_AGENT = "com.nextcraft.forge.REMOVE_AGENT"
        const val ACTION_INSTALL_STACK = "com.nextcraft.forge.INSTALL_STACK"
        const val ACTION_REMOVE_STACK = "com.nextcraft.forge.REMOVE_STACK"
        const val EXTRA_VERSION = "target_version"
        const val ACTION_STOP = "com.nextcraft.forge.STOP_SETUP"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"
        private const val CHANNEL_ID = "runtime-setup"
        private const val NOTIFICATION_ID = 51
        private const val RESULT_NOTIFICATION_ID = 52
        private const val NOTIFICATION_THROTTLE_MS = 750L
        private const val MAX_WAKE_LOCK_MS = 45 * 60 * 1_000L
        private const val WAKE_LOCK_RENEW_INTERVAL_MS = 5 * 60 * 1_000L
        private const val MAX_SETUP_WAKE_MS = 4 * 60 * 60 * 1_000L

        fun ensureNotificationChannel(context: Context) {
            val localized = com.nextcraft.forge.ui.LocaleHelper.wrapContext(context)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    localized.getString(com.nextcraft.forge.R.string.notif_channel_setup),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = localized.getString(com.nextcraft.forge.R.string.notif_channel_setup_desc)
                },
            )
        }
    }
}
