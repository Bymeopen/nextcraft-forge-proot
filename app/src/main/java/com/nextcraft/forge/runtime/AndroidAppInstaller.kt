package com.nextcraft.forge.runtime

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import androidx.core.content.FileProvider
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AndroidInstallEvent(
    val operationId: String,
    val packageName: String?,
    val success: Boolean,
    val launched: Boolean,
    val message: String? = null,
)

/**
 * Installs a locally-built APK on the device.
 *
 * On rooted devices (NextCraft Forge's primary target), attempts a 1-tap silent
 * `su -c "pm install -r -d -S <size>"` stream install first (excluding self-updates
 * of NextCraft Forge itself), and automatically falls back to Android's standard
 * [PackageInstaller.Session] + [AndroidAppInstallReceiver] flow if root install fails.
 */
object AndroidAppInstaller {
    private val _events = MutableSharedFlow<AndroidInstallEvent>(extraBufferCapacity = 8)
    val events = _events.asSharedFlow()

    fun openIfAlreadyInstalled(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        if (installedFingerprint(context, packageName) != fingerprint(apk)) return false
        return launch(context, packageName)
    }

    fun install(
        context: Context,
        apk: File,
        launchAfterInstall: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        require(
            apk.isFile &&
                !Files.isSymbolicLink(apk.toPath()) &&
                apk.extension.equals("apk", ignoreCase = true) &&
                apk.length() > 0L,
        ) {
            "APK file '${apk.name}' is missing or invalid."
        }
        val appContext = context.applicationContext ?: context
        val pkgName = archivePackageName(appContext, apk)
        val isSelfUpdate = pkgName == appContext.packageName

        Thread({
            val apkHash = runCatching { fingerprint(apk) }.getOrNull()
            // Own update: with root, replace the app without the system dialog (the app restarts itself). A failure leaves
            // the old build in place and falls through to the normal installer below.
            if (isSelfUpdate && com.nextcraft.forge.update.SilentSelfUpdate.install(appContext, apk)) {
                return@Thread
            }
            if (!isSelfUpdate && pkgName != null && apkHash != null && installSilentlyViaRoot(apk)) {
                rememberInstalled(appContext, pkgName, apkHash)
                val launched = launchAfterInstall && launch(appContext, pkgName)
                publish(AndroidInstallEvent(operationId, pkgName, success = true, launched = launched))
                return@Thread
            }
            runCatching {
                installViaSessionOrIntent(appContext, apk, pkgName, apkHash ?: fingerprint(apk), launchAfterInstall, operationId)
            }.onFailure { error ->
                publish(
                    AndroidInstallEvent(
                        operationId = operationId,
                        packageName = pkgName,
                        success = false,
                        launched = false,
                        message = error.message ?: "APK installation failed.",
                    ),
                )
            }
        }, "forge-apk-installer").apply { isDaemon = true }.start()
        return operationId
    }

    /**
     * Streams [apk] over stdin to `su -c "pm install -r -d -S <bytes>"` so `system_server`
     * never needs direct SELinux filesystem access to the app's private data directory.
     */
    internal fun installSilentlyViaRoot(apk: File): Boolean = runCatching {
        if (!RuntimeEngines.isRootEngine()) return@runCatching false
        val sizeBytes = apk.length()
        if (sizeBytes <= 0L) return@runCatching false
        val proc = ProcessBuilder("su", "-c", "pm install -r -d -S $sizeBytes")
            .redirectErrorStream(true)
            .start()
        try {
            apk.inputStream().buffered().use { input ->
                proc.outputStream.use { output ->
                    input.copyTo(output)
                    output.flush()
                }
            }
            if (!proc.waitFor(60L, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                return@runCatching false
            }
            val out = proc.inputStream.bufferedReader().use { it.readText() }
            proc.exitValue() == 0 && out.contains("Success", ignoreCase = true)
        } finally {
            if (proc.isAlive) proc.destroyForcibly()
        }
    }.getOrDefault(false)

    private fun installViaSessionOrIntent(
        context: Context,
        apk: File,
        packageName: String?,
        apkHash: String,
        launchAfterInstall: Boolean,
        operationId: String,
    ) {
        if (isMiuiDevice()) {
            val uri = runCatching {
                FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
            }.getOrElse {
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
            }
            @Suppress("DEPRECATION")
            context.startActivity(
                Intent(Intent.ACTION_INSTALL_PACKAGE, uri).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            publish(
                AndroidInstallEvent(
                    operationId = operationId,
                    packageName = packageName,
                    success = true,
                    launched = false,
                    message = "Opened system package installer.",
                ),
            )
            return
        }
        val installer = context.packageManager.packageInstaller
        val isUpdate = packageName?.let { isInstalled(context, it) } == true
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply {
                setSize(apk.length())
                setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
                setInstallReason(PackageManager.INSTALL_REASON_USER)
                setOriginatingUid(Process.myUid())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    setRequireUserAction(
                        if (isUpdate) PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                        else PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                    )
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
                }
            }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite(apk.name, 0, apk.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val callback = Intent(context, AndroidAppInstallReceiver::class.java)
                    .setAction(ACTION_INSTALL_RESULT)
                    .setPackage(context.packageName)
                    .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    .putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    .putExtra(EXTRA_APK_FINGERPRINT, apkHash)
                    .putExtra(EXTRA_OPERATION_ID, operationId)
                    .putExtra(EXTRA_LAUNCH_AFTER_INSTALL, launchAfterInstall)
                val mutabilityFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT or mutabilityFlag,
                )
                session.commit(pending.intentSender)
            }
        } catch (error: Throwable) {
            runCatching { installer.abandonSession(sessionId) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAppInstaller", it) }
            throw error
        }
    }

    const val ACTION_INSTALL_RESULT = "com.nextcraft.forge.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_FINGERPRINT = "com.nextcraft.forge.extra.APK_FINGERPRINT"
    const val EXTRA_OPERATION_ID = "com.nextcraft.forge.extra.OPERATION_ID"
    const val EXTRA_LAUNCH_AFTER_INSTALL = "com.nextcraft.forge.extra.LAUNCH_AFTER_INSTALL"

    internal fun publish(event: AndroidInstallEvent) {
        _events.tryEmit(event)
    }

    fun packageName(context: Context, apk: File): String? = archivePackageName(context, apk)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedMatches(context: Context, apk: File): Boolean {
        if (!apk.isFile || Files.isSymbolicLink(apk.toPath())) return false
        val packageName = archivePackageName(context, apk) ?: return false
        return installedFingerprint(context, packageName) == fingerprint(apk)
    }

    fun apkFingerprint(apk: File): String = fingerprint(apk)

    internal fun rememberInstalled(context: Context, packageName: String, fingerprint: String) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(packageName, fingerprint)
            .apply()
    }

    internal fun launch(context: Context, packageName: String): Boolean = runCatching {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getLaunchIntentSenderForPackage(packageName).sendIntent(
                context, 0, null, null, null,
            )
        } else {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
        true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun archivePackageName(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun installedFingerprint(context: Context, packageName: String): String? =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(packageName, null)

    private fun fingerprint(apk: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private const val PREFERENCES = "installed_android_apks"

    private fun isMiuiDevice(): Boolean = Build.MANUFACTURER.lowercase(Locale.US) in
        setOf("xiaomi", "redmi", "poco")
}
