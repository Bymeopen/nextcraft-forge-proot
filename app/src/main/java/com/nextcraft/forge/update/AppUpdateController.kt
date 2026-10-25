package com.nextcraft.forge.update

import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ErrorLog
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The calls the update flow makes on the outside world; the real implementation wraps [AppUpdater] and the installer. */
interface AppUpdateSource {
    /** The newer build, or null when up to date. Throws [NoPublishedReleaseException] or an IOException on failure. */
    fun check(manifestUrlOverride: String): AppUpdateInfo?
    fun download(info: AppUpdateInfo, progress: (Long, Long) -> Unit): File
    fun install(apk: File)
}

/** The two switches the person controls, kept in preferences. */
interface UpdatePrefs {
    var notifications: Boolean
    var autoInstall: Boolean
}

/** What the update row, the switches and the Projects banner show. */
data class AppUpdateState(
    val notifications: Boolean = true,
    val autoInstall: Boolean = true,
    val info: AppUpdateInfo? = null,
    val statusMessage: String? = null,
    /** 0..1 while an update downloads, null otherwise. */
    val progress: Float? = null,
    val checking: Boolean = false,
)

/**
 * Finding, downloading and installing a newer build of the app, plus the two switches that decide whether the phone
 * checks in the background. Moved out of MainViewModel with its behaviour unchanged; it is the only writer of these
 * six pieces of state, and MainViewModel mirrors them for the screens.
 */
class AppUpdateController(
    private val scope: CoroutineScope,
    private val source: AppUpdateSource,
    private val prefs: UpdatePrefs,
    private val scheduleBackgroundCheck: () -> Unit,
    private val cancelBackgroundCheck: () -> Unit,
    /** Announces a newer build once with a notification. */
    private val announce: (AppUpdateInfo) -> Unit,
    private val text: (Int, Array<out Any>) -> String,
    private val localize: (String) -> String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutable = MutableStateFlow(AppUpdateState(notifications = prefs.notifications, autoInstall = prefs.autoInstall))
    val state: StateFlow<AppUpdateState> = mutable.asStateFlow()
    private var lastSilentCheck = 0L

    private fun str(id: Int, vararg args: Any) = text(id, args)

    fun setNotifications(enabled: Boolean) {
        prefs.notifications = enabled
        mutable.update { it.copy(notifications = enabled) }
        if (enabled) {
            scheduleBackgroundCheck()
            silentCheck(force = true)
        } else if (!prefs.autoInstall) {
            cancelBackgroundCheck()
        }
    }

    /** Background download and silent install while the app is idle (needs root; without it only the notification shows). */
    fun setAutoInstall(enabled: Boolean) {
        prefs.autoInstall = enabled
        mutable.update { it.copy(autoInstall = enabled) }
        if (enabled) {
            scheduleBackgroundCheck()
            if (mutable.value.info != null) {
                downloadAndInstall()
            }
        } else if (!prefs.notifications) {
            cancelBackgroundCheck()
        }
    }

    /**
     * Looks for a newer build without the "checking" UI: when the app opens, and when notifications are switched on.
     * A newer build is kept in [AppUpdateState.info] (the Projects banner and the update row read it) and announced
     * once with a notification. Errors are ignored here; the manual check still reports them.
     */
    fun silentCheck(force: Boolean = false) {
        if (!force && !prefs.notifications) return
        val time = now()
        if (!force && time - lastSilentCheck < SILENT_CHECK_INTERVAL_MS) return
        lastSilentCheck = time
        scope.launch {
            val info = withContext(io) { runCatching { source.check("") }.getOrNull() } ?: return@launch
            if (mutable.value.info == null) {
                mutable.update { it.copy(info = info, statusMessage = str(R.string.upd_available, info.versionName, info.versionCode)) }
            }
            withContext(io) { announce(info) }
            if (prefs.autoInstall && mutable.value.progress == null) {
                downloadAndInstall()
            }
        }
    }

    fun checkNow(manifestUrlOverride: String = "") {
        if (mutable.value.checking) return
        scope.launch {
            mutable.update { it.copy(checking = true, info = null, statusMessage = str(R.string.upd_checking)) }
            val result = withContext(io) {
                runCatching { source.check(manifestUrlOverride.trim()) }.onFailure { ErrorLog.swallowed("AppUpdate", it) }
            }
            val info = result.getOrNull()
            val error = result.exceptionOrNull()
            mutable.update {
                it.copy(
                    checking = false,
                    info = info,
                    statusMessage = when {
                        error is NoPublishedReleaseException -> str(R.string.upd_no_release)
                        error is java.net.UnknownHostException || error is java.net.SocketTimeoutException -> str(R.string.upd_no_internet)
                        error != null -> str(R.string.upd_check_failed, error.message?.let(localize) ?: str(R.string.upd_network_error))
                        info != null -> str(R.string.upd_available, info.versionName, info.versionCode)
                        else -> str(R.string.upd_up_to_date)
                    },
                )
            }
        }
    }

    fun downloadAndInstall() {
        val info = mutable.value.info ?: return
        if (mutable.value.progress != null) return
        scope.launch {
            mutable.update { it.copy(progress = 0f, statusMessage = str(R.string.upd_downloading, info.versionName)) }
            val result = withContext(io) {
                runCatching {
                    var lastReported = -1
                    val apk = source.download(info) { downloaded, total ->
                        val fraction = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
                        // Report whole-percent steps only; per-chunk state updates would recompose the screen constantly.
                        val step = fraction?.let { (it * 100).toInt() } ?: (downloaded / (512 * 1024)).toInt()
                        if (step != lastReported) {
                            lastReported = step
                            val mb = "%.1f".format(downloaded / 1_048_576.0)
                            val msg = if (fraction != null) {
                                str(R.string.upd_progress_total, info.versionName, mb, "%.1f".format(total / 1_048_576.0))
                            } else {
                                str(R.string.upd_progress, info.versionName, mb)
                            }
                            mutable.update { it.copy(progress = fraction ?: 0f, statusMessage = msg) }
                        }
                    }
                    source.install(apk)
                }.onFailure { ErrorLog.swallowed("AppUpdate", it) }
            }
            result.onSuccess {
                mutable.update { it.copy(progress = null, statusMessage = str(R.string.upd_installing, info.versionName)) }
            }.onFailure { err ->
                mutable.update { it.copy(progress = null, statusMessage = str(R.string.upd_install_failed, err.message?.let(localize).orEmpty())) }
            }
        }
    }

    private companion object {
        const val SILENT_CHECK_INTERVAL_MS = 60 * 60 * 1000L
    }
}
