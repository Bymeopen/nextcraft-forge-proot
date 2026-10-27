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
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.R
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.ui.LocaleHelper
import com.nextcraft.forge.ui.RuntimeText
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
import java.io.File
import java.net.UnknownHostException

enum class RuntimeSetupStatus {
    IDLE,
    RUNNING,
    COMPLETE,
    ERROR,
    CANCELLED,
}

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
    private val stateFlow = MutableStateFlow(RuntimeSetupSnapshot())
    val snapshot: StateFlow<RuntimeSetupSnapshot> = stateFlow.asStateFlow()

    @Synchronized
    fun restore(context: Context) {
        val snapshotFile = resolveSnapshotFile(context)
        if (!snapshotFile.isFile || stateFlow.value.status == RuntimeSetupStatus.RUNNING) return

        runCatching {
            val root = JSONObject(snapshotFile.readText())
            val logArr = root.optJSONArray("logs") ?: JSONArray()
            val decodedLogs = List(logArr.length()) { idx -> logArr.optString(idx) }
            val parsedFraction = root.optDouble("progress", 0.0).toFloat()
            val clampedFraction = if (parsedFraction.isFinite()) parsedFraction.coerceIn(0f, 1f) else 0f
            val storedStatus = runCatching { RuntimeSetupStatus.valueOf(root.optString("status")) }
                .getOrDefault(RuntimeSetupStatus.IDLE)
            val wasKilledMidRun = storedStatus == RuntimeSetupStatus.RUNNING

            val displayMsg = if (wasKilledMidRun) {
                "Runtime setup was interrupted"
            } else {
                root.stringOrEmpty("message", INIT_MESSAGE).ifBlank { INIT_MESSAGE }
            }

            stateFlow.value = RuntimeSetupSnapshot(
                status = if (wasKilledMidRun) RuntimeSetupStatus.CANCELLED else storedStatus,
                message = displayMsg,
                progress = clampedFraction,
                downloadedBytes = readNullableLong(root, "downloadedBytes"),
                totalBytes = readNullableLong(root, "totalBytes"),
                indeterminate = root.optBoolean("indeterminate"),
                logs = decodedLogs.takeLast(LOG_RING_CAPACITY),
                errorMessage = root.stringOrEmpty("errorMessage").takeIf { it.isNotBlank() },
                offline = root.optBoolean("offline"),
            )
        }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    @Synchronized
    fun begin(context: Context) {
        val updatedLogs = (stateFlow.value.logs + "— Resuming NextCraft Forge setup —").takeLast(LOG_RING_CAPACITY)
        publishAndSave(
            context,
            RuntimeSetupSnapshot(
                status = RuntimeSetupStatus.RUNNING,
                message = INIT_MESSAGE,
                progress = 0.01f,
                logs = updatedLogs,
            ),
        )
    }

    @Synchronized
    fun update(context: Context, event: RuntimeInstallProgress) {
        val prev = stateFlow.value
        val hasTransferMetrics = event.downloadedBytes != null && event.totalBytes != null
        val formattedLine = event.terminalLine ?: when {
            hasTransferMetrics -> {
                val tag = if (event.unpacking) TAG_UNPACKING else TAG_DOWNLOADING
                "$tag %.1f / %.1f MB".format(event.downloadedBytes!! / BYTES_PER_MB, event.totalBytes!! / BYTES_PER_MB)
            }
            event.event == RuntimeInstallEvent.STAGE -> "• ${event.message}"
            else -> null
        }

        val cleanLine = formattedLine?.takeIf { it.isNotBlank() }?.let(::stripControlChars)
        val replaceTail = hasTransferMetrics && isTransferLine(prev.logs.lastOrNull())

        val nextLogs = when {
            cleanLine == null -> prev.logs
            replaceTail -> (prev.logs.dropLast(1) + cleanLine).takeLast(LOG_RING_CAPACITY)
            else -> (prev.logs + cleanLine).takeLast(LOG_RING_CAPACITY)
        }
        if (!replaceTail) {
            writeLogEntryToDisk(context, cleanLine)
        }

        val nextProgress = if (event.fraction.isFinite()) {
            maxOf(prev.progress, event.fraction.coerceIn(0f, 1f))
        } else {
            prev.progress
        }
        val nextMessage = if (event.event == RuntimeInstallEvent.STAGE || event.event == RuntimeInstallEvent.DOWNLOAD) {
            event.message
        } else {
            prev.message
        }

        publishAndSave(
            context,
            prev.copy(
                status = RuntimeSetupStatus.RUNNING,
                message = nextMessage,
                progress = nextProgress,
                downloadedBytes = event.downloadedBytes,
                totalBytes = event.totalBytes,
                indeterminate = event.indeterminate,
                logs = nextLogs,
                errorMessage = null,
                offline = false,
            ),
        )
    }

    @Synchronized
    fun complete(context: Context) {
        val prev = stateFlow.value
        publishAndSave(
            context,
            prev.copy(
                status = RuntimeSetupStatus.COMPLETE,
                message = "Linux chroot runtime is ready",
                progress = 1f,
                indeterminate = false,
                downloadedBytes = null,
                totalBytes = null,
                logs = (prev.logs + "✓ Setup completed successfully").takeLast(LOG_RING_CAPACITY),
            ),
        )
    }

    @Synchronized
    fun fail(context: Context, error: Throwable) {
        val chain = generateSequence(error as Throwable?) { it.cause }.toList()
        val isNetworkDown = chain.any { t ->
            t is UnknownHostException ||
                t.message.orEmpty().contains("unable to resolve host", ignoreCase = true) ||
                t.message.orEmpty().contains("no address associated with hostname", ignoreCase = true)
        }
        val isDpkgLocked = chain.any { t ->
            val msg = t.message.orEmpty()
            msg.contains("dpkg was interrupted", ignoreCase = true) ||
                msg.contains("dpkg --configure -a", ignoreCase = true)
        }

        val summary = when {
            isNetworkDown -> "No internet connection. Connect to Wi-Fi or mobile data and retry."
            isDpkgLocked -> "Package installation was interrupted. Retry setup to repair automatically."
            else -> error.message.orEmpty()
                .lineSequence()
                .lastOrNull { it.isNotBlank() }
                ?.take(220)
                ?: "Runtime setup could not complete."
        }

        val prev = stateFlow.value
        publishAndSave(
            context,
            prev.copy(
                status = RuntimeSetupStatus.ERROR,
                errorMessage = summary,
                offline = isNetworkDown,
                indeterminate = false,
                logs = (prev.logs + "✕ $summary").takeLast(LOG_RING_CAPACITY),
            ),
        )
    }

    @Synchronized
    fun cancelled(context: Context) {
        val prev = stateFlow.value
        publishAndSave(
            context,
            prev.copy(
                status = RuntimeSetupStatus.CANCELLED,
                message = "Runtime setup paused",
                indeterminate = false,
                logs = (prev.logs + "• Setup paused safely").takeLast(LOG_RING_CAPACITY),
            ),
        )
    }

    fun fullLog(context: Context): String =
        resolveLogFile(context).readTailText(LOG_FILE_CAP_BYTES.toInt())

    private fun publishAndSave(context: Context, updated: RuntimeSetupSnapshot) {
        stateFlow.value = updated
        saveSnapshotToDisk(context, updated)
    }

    private fun saveSnapshotToDisk(context: Context, snap: RuntimeSetupSnapshot) {
        runCatching {
            val clamped = if (snap.progress.isFinite()) snap.progress.coerceIn(0f, 1f) else 0f
            val payload = JSONObject()
                .put("status", snap.status.name)
                .put("message", snap.message)
                .put("progress", clamped.toDouble())
                .put("indeterminate", snap.indeterminate)
                .put("offline", snap.offline)
                .put("logs", JSONArray(snap.logs))
            if (snap.downloadedBytes != null) payload.put("downloadedBytes", snap.downloadedBytes)
            if (snap.totalBytes != null) payload.put("totalBytes", snap.totalBytes)
            if (snap.errorMessage != null) payload.put("errorMessage", snap.errorMessage)

            val dest = resolveSnapshotFile(context)
            val tmp = File(dest.parentFile, "${dest.name}.tmp")
            tmp.writeText(payload.toString())
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) {
                    Log.e(LOG_TAG, "Failed to persist setup state")
                }
            }
        }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    private fun writeLogEntryToDisk(context: Context, entry: String?) {
        if (entry.isNullOrBlank()) return
        runCatching {
            val logTarget = resolveLogFile(context)
            logTarget.parentFile?.mkdirs()
            logTarget.appendText(stripControlChars(entry) + "\n")
            if (logTarget.length() > LOG_FILE_CAP_BYTES) {
                val tailChunk = logTarget.readTailText(LOG_FILE_CAP_BYTES.toInt())
                logTarget.writeText(tailChunk.substringAfter('\n', tailChunk))
            }
        }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    private fun stripControlChars(input: String): String =
        input.filter { it == '\t' || it.code >= 32 }.take(500)

    private fun resolveSnapshotFile(context: Context): File =
        File(context.filesDir, "setup/runtime-setup-state.json").apply { parentFile?.mkdirs() }

    private fun resolveLogFile(context: Context): File =
        File(context.filesDir, "setup/runtime-setup.log")

    private fun readNullableLong(node: JSONObject, key: String): Long? =
        if (node.has(key) && !node.isNull(key)) node.optLong(key) else null

    private fun isTransferLine(line: String?): Boolean =
        line != null && (line.startsWith(TAG_DOWNLOADING) || line.startsWith(TAG_UNPACKING))

    private const val LOG_TAG = "RuntimeSetupService"
    private const val INIT_MESSAGE = "Preparing your private coding workspace"
    private const val LOG_RING_CAPACITY = 400
    private const val LOG_FILE_CAP_BYTES = 1_000_000L
    private const val BYTES_PER_MB = 1_048_576.0
    private const val TAG_DOWNLOADING = "Downloading:"
    private const val TAG_UNPACKING = "Unpacking:"
}

class RuntimeSetupService : Service() {
    private val supervisor = SupervisorJob()
    private val workerScope = CoroutineScope(supervisor + Dispatchers.IO)
    private var activeSetupJob: Job? = null
    private var cpuWakeLock: PowerManager.WakeLock? = null
    private var wakeLockStartMs = 0L
    private var wakeLockLastRenewMs = 0L
    private var lastNotifyEmitMs = 0L

    private fun tr(@StringRes resId: Int, vararg formatArgs: Any): String =
        LocaleHelper.wrapContext(this).getString(resId, *formatArgs)

    private fun translateInstallerStatus(englishText: String): String =
        RuntimeText.match(englishText)?.let { (resId, args) -> tr(resId, *args.toTypedArray()) } ?: englishText

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel(this)
        RuntimeSetupController.restore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            val inFlight = activeSetupJob?.takeIf { it.isActive }
            if (inFlight != null) {
                inFlight.cancel(CancellationException("Stopped by user"))
                RuntimeSetupController.cancelled(this)
            }
            dropWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        runCatching {
            startForeground(NOTIF_ID_PROGRESS, buildProgressNotification(RuntimeSetupController.snapshot.value))
        }.onFailure { err ->
            Log.w(LOG_TAG, "startForeground refused: ${err::class.simpleName}")
        }

        holdWakeLock()
        val priorJob = activeSetupJob
        if (priorJob?.isActive != true || priorJob.isCancelled) {
            val chosenStacks = intent?.getStringExtra(EXTRA_STACKS).orEmpty()
                .splitToSequence(',')
                .mapNotNull { token -> runCatching { DevStack.valueOf(token.trim()) }.getOrNull() }
                .toSet()
            val chosenAgent = AgentKind.fromStored(intent?.getStringExtra(EXTRA_AGENT)?.trim())
            val cmdAction = intent?.action
            val targetVer = intent?.getStringExtra(EXTRA_VERSION).orEmpty()

            RuntimeSetupController.begin(this)
            val newJob = workerScope.launch(start = CoroutineStart.LAZY) {
                try {
                    priorJob?.join()
                    val rtInstaller = RuntimeInstaller(this@RuntimeSetupService)
                    val progressCallback: suspend (RuntimeInstallProgress) -> Unit = { prog ->
                        RuntimeSetupController.update(this@RuntimeSetupService, prog)
                        refreshNotification(prog.event == RuntimeInstallEvent.COMMAND_COMPLETED)
                        extendWakeLockIfNeeded()
                    }
                    when (cmdAction) {
                        ACTION_INSTALL_AGENT -> rtInstaller.ensureAgentInstalled(chosenAgent, progressCallback)
                        ACTION_UPDATE_AGENT -> rtInstaller.updateAgent(chosenAgent, targetVer, progressCallback)
                        ACTION_REMOVE_AGENT -> {
                            progressCallback(RuntimeInstallProgress("Removing ${chosenAgent.title}…", 0.5f, indeterminate = true))
                            rtInstaller.removeAgent(chosenAgent)
                            progressCallback(RuntimeInstallProgress("Removed ${chosenAgent.title}", 1f))
                        }
                        ACTION_INSTALL_STACK -> chosenStacks.forEach { rtInstaller.ensureStackInstalled(it, progressCallback) }
                        ACTION_REMOVE_STACK -> chosenStacks.forEach { rtInstaller.removeStack(it, progressCallback) }
                        else -> rtInstaller.ensureInstalled(chosenStacks, chosenAgent, progressCallback)
                    }
                    if (cmdAction == ACTION_START) {
                        AppPreferences(this@RuntimeSetupService).runtimeSetupComplete = true
                    }
                    RuntimeSetupController.complete(this@RuntimeSetupService)
                    postCompletionNotification(succeeded = true)
                } catch (_: CancellationException) {
                    if (activeSetupJob === coroutineContext[Job]) {
                        RuntimeSetupController.cancelled(this@RuntimeSetupService)
                    }
                } catch (err: Throwable) {
                    RuntimeSetupController.fail(this@RuntimeSetupService, err)
                    postCompletionNotification(succeeded = false)
                } finally {
                    if (activeSetupJob === coroutineContext[Job]) {
                        dropWakeLock()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
            activeSetupJob = newJob
            newJob.start()
        }
        return START_REDELIVER_INTENT
    }

    private fun refreshNotification(immediate: Boolean = false) {
        val nowMs = SystemClock.elapsedRealtime()
        if (!immediate && nowMs - lastNotifyEmitMs < NOTIF_MIN_INTERVAL_MS) return
        lastNotifyEmitMs = nowMs
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_ID_PROGRESS, buildProgressNotification(RuntimeSetupController.snapshot.value))
    }

    private fun buildProgressNotification(snap: RuntimeSetupSnapshot): Notification {
        val recentLine = snap.logs.lastOrNull().orEmpty().take(180)
        val localizedContent = translateInstallerStatus(recentLine.ifBlank { snap.message })
        val pauseIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, RuntimeSetupService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, SETUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(tr(R.string.notif_setup_title))
            .setContentText(localizedContent)
            .setStyle(NotificationCompat.BigTextStyle().bigText(localizedContent))
            .setContentIntent(buildLaunchAppPendingIntent())
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .apply {
                if (snap.indeterminate) {
                    setProgress(0, 0, true)
                } else {
                    val pct = (snap.progress * 100f).toInt().coerceIn(0, 100)
                    setProgress(100, pct, false)
                }
            }
            .addAction(0, tr(R.string.notif_pause), pauseIntent)
            .build()
    }

    private fun postCompletionNotification(succeeded: Boolean) {
        val snap = RuntimeSetupController.snapshot.value
        val heading = if (succeeded) tr(R.string.rt_msg_chroot_ready) else tr(R.string.notif_setup_attention)
        val body = if (succeeded) {
            tr(R.string.rt_msg_env_ready)
        } else {
            snap.errorMessage?.let(::translateInstallerStatus) ?: tr(R.string.notif_setup_error)
        }
        val notif = NotificationCompat.Builder(this, SETUP_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(heading)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(buildLaunchAppPendingIntent())
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIF_ID_OUTCOME, notif)
    }

    private fun buildLaunchAppPendingIntent(): PendingIntent {
        val activityIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: runCatching { Intent(this, Class.forName("$packageName.MainActivity")) }.getOrNull()
            ?: runCatching { Intent(this, Class.forName("com.nextcraft.forge.MainActivity")) }.getOrNull()
            ?: Intent().setPackage(packageName)
        activityIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        return PendingIntent.getActivity(
            this,
            101,
            activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun holdWakeLock() {
        if (cpuWakeLock?.isHeld == true) return
        val nowMs = SystemClock.elapsedRealtime()
        wakeLockStartMs = nowMs
        wakeLockLastRenewMs = nowMs
        cpuWakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "com.nextcraft.forge:runtime-setup")
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_SLICE_DURATION_MS)
            }
    }

    private fun extendWakeLockIfNeeded() {
        val lock = cpuWakeLock ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (nowMs - wakeLockLastRenewMs < WAKE_RENEW_STEP_MS) return
        if (nowMs - wakeLockStartMs > WAKE_TOTAL_CAP_MS) return
        wakeLockLastRenewMs = nowMs
        runCatching { lock.acquire(WAKE_SLICE_DURATION_MS) }
            .onFailure { ErrorLog.swallowed(LOG_TAG, it) }
    }

    private fun dropWakeLock() {
        cpuWakeLock?.takeIf { it.isHeld }?.let { lock ->
            runCatching { lock.release() }.onFailure { ErrorLog.swallowed(LOG_TAG, it) }
        }
        cpuWakeLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        Log.w(LOG_TAG, "dataSync foreground time limit reached")
        activeSetupJob?.takeIf { it.isActive }?.cancel(CancellationException("Foreground time limit reached"))
        dropWakeLock()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        dropWakeLock()
        workerScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val LOG_TAG = "RuntimeSetupService"
        const val ACTION_START = "com.nextcraft.forge.START_SETUP"
        const val ACTION_INSTALL_AGENT = "com.nextcraft.forge.INSTALL_AGENT"
        const val ACTION_UPDATE_AGENT = "com.nextcraft.forge.UPDATE_AGENT"
        const val ACTION_REMOVE_AGENT = "com.nextcraft.forge.REMOVE_AGENT"
        const val ACTION_INSTALL_STACK = "com.nextcraft.forge.INSTALL_STACK"
        const val ACTION_REMOVE_STACK = "com.nextcraft.forge.REMOVE_STACK"
        const val ACTION_STOP = "com.nextcraft.forge.STOP_SETUP"
        const val EXTRA_VERSION = "target_version"
        const val EXTRA_STACKS = "selected_stacks"
        const val EXTRA_AGENT = "selected_agent"

        private const val SETUP_CHANNEL_ID = "runtime-setup"
        private const val NOTIF_ID_PROGRESS = 51
        private const val NOTIF_ID_OUTCOME = 52
        private const val NOTIF_MIN_INTERVAL_MS = 750L
        private const val WAKE_SLICE_DURATION_MS = 45L * 60L * 1_000L
        private const val WAKE_RENEW_STEP_MS = 5L * 60L * 1_000L
        private const val WAKE_TOTAL_CAP_MS = 4L * 60L * 60L * 1_000L

        fun ensureNotificationChannel(context: Context) {
            val wrapped = LocaleHelper.wrapContext(context)
            val ch = NotificationChannel(
                SETUP_CHANNEL_ID,
                wrapped.getString(R.string.notif_channel_setup),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = wrapped.getString(R.string.notif_channel_setup_desc)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(ch)
        }
    }
}
