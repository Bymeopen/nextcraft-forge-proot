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
import com.nextcraft.forge.update.SilentSelfUpdate
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class AndroidInstallEvent(
    val operationId: String,
    val packageName: String?,
    val success: Boolean,
    val launched: Boolean,
    val message: String? = null,
)

object AndroidAppInstaller {
    const val ACTION_INSTALL_RESULT = "com.nextcraft.forge.action.APK_INSTALL_RESULT"
    const val EXTRA_APK_FINGERPRINT = "com.nextcraft.forge.extra.APK_FINGERPRINT"
    const val EXTRA_OPERATION_ID = "com.nextcraft.forge.extra.OPERATION_ID"
    const val EXTRA_LAUNCH_AFTER_INSTALL = "com.nextcraft.forge.extra.LAUNCH_AFTER_INSTALL"

    private const val INSTALLED_LEDGER_PREFS = "installed_android_apks"
    private val XIAOMI_BRANDS = setOf("xiaomi", "redmi", "poco")
    private val HEX_FORMAT = HexFormat.of()

    private val deployExecutor = Executors.newCachedThreadPool { task ->
        Thread(task, "forge-apk-deployer").apply { isDaemon = true }
    }

    private val eventBus = MutableSharedFlow<AndroidInstallEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<AndroidInstallEvent> = eventBus.asSharedFlow()

    fun openIfAlreadyInstalled(context: Context, apk: File): Boolean {
        if (!isValidApkFile(apk)) return false
        val pkg = resolveArchivePackage(context, apk) ?: return false
        val currentHash = readRecordedDigest(context, pkg) ?: return false
        return currentHash == computeSha256(apk) && launch(context, pkg)
    }

    fun install(
        context: Context,
        apk: File,
        launchAfterInstall: Boolean = true,
        operationId: String = UUID.randomUUID().toString(),
    ): String {
        require(isValidApkFile(apk) && apk.length() > 0L) {
            "APK file '${apk.name}' is missing or invalid."
        }
        val appCtx = context.applicationContext ?: context
        val targetPkg = resolveArchivePackage(appCtx, apk)
        val updatingSelf = targetPkg == appCtx.packageName

        deployExecutor.execute {
            executeDeployment(
                appCtx = appCtx,
                apkFile = apk,
                targetPkg = targetPkg,
                updatingSelf = updatingSelf,
                autoLaunch = launchAfterInstall,
                opId = operationId,
            )
        }
        return operationId
    }

    private fun executeDeployment(
        appCtx: Context,
        apkFile: File,
        targetPkg: String?,
        updatingSelf: Boolean,
        autoLaunch: Boolean,
        opId: String,
    ) {
        val digest = runCatching { computeSha256(apkFile) }.getOrNull()
        if (updatingSelf && SilentSelfUpdate.install(appCtx, apkFile)) {
            return
        }
        if (!updatingSelf && targetPkg != null && digest != null && installSilentlyViaRoot(apkFile)) {
            rememberInstalled(appCtx, targetPkg, digest)
            val didLaunch = autoLaunch && launch(appCtx, targetPkg)
            publish(AndroidInstallEvent(opId, targetPkg, success = true, launched = didLaunch))
            return
        }
        runCatching {
            val resolvedDigest = digest ?: computeSha256(apkFile)
            dispatchPackageInstaller(appCtx, apkFile, targetPkg, resolvedDigest, autoLaunch, opId)
        }.onFailure { err ->
            publish(
                AndroidInstallEvent(
                    operationId = opId,
                    packageName = targetPkg,
                    success = false,
                    launched = false,
                    message = err.message ?: "APK installation failed.",
                ),
            )
        }
    }

    internal fun installSilentlyViaRoot(apk: File): Boolean = runCatching {
        if (!RuntimeEngines.isRootEngine()) return false
        val byteLen = apk.length()
        if (byteLen <= 0L) return false
        val child = ProcessBuilder("su", "-c", "pm install -r -d -S $byteLen")
            .redirectErrorStream(true)
            .start()
        try {
            apk.inputStream().buffered().use { src ->
                child.outputStream.use { sink ->
                    src.copyTo(sink)
                    sink.flush()
                }
            }
            if (!child.waitFor(60L, TimeUnit.SECONDS)) {
                child.destroyForcibly()
                return false
            }
            val consoleOutput = child.inputStream.bufferedReader().use { it.readText() }
            child.exitValue() == 0 && consoleOutput.contains("Success", ignoreCase = true)
        } finally {
            if (child.isAlive) child.destroyForcibly()
        }
    }.getOrDefault(false)

    private fun dispatchPackageInstaller(
        context: Context,
        apk: File,
        packageName: String?,
        apkDigest: String,
        launchAfterInstall: Boolean,
        operationId: String,
    ) {
        if (Build.MANUFACTURER.lowercase(Locale.US) in XIAOMI_BRANDS) {
            launchDirectActionInstall(context, apk, packageName, operationId)
            return
        }

        val pkgInstaller = context.packageManager.packageInstaller
        val upgradingExisting = packageName != null && isInstalled(context, packageName)
        val sessionCfg = buildSessionParams(apk.length(), upgradingExisting)
        val sessionId = pkgInstaller.createSession(sessionCfg)

        try {
            pkgInstaller.openSession(sessionId).use { activeSession ->
                apk.inputStream().use { fileIn ->
                    activeSession.openWrite(apk.name, 0L, apk.length()).use { sessionOut ->
                        fileIn.copyTo(sessionOut)
                        activeSession.fsync(sessionOut)
                    }
                }
                val receiverIntent = Intent(context, AndroidAppInstallReceiver::class.java).apply {
                    action = ACTION_INSTALL_RESULT
                    setPackage(context.packageName)
                    addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                    putExtra(PackageInstaller.EXTRA_SESSION_ID, sessionId)
                    putExtra(EXTRA_APK_FINGERPRINT, apkDigest)
                    putExtra(EXTRA_OPERATION_ID, operationId)
                    putExtra(EXTRA_LAUNCH_AFTER_INSTALL, launchAfterInstall)
                }
                val pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val sender = PendingIntent.getBroadcast(context, sessionId, receiverIntent, pendingFlags).intentSender
                activeSession.commit(sender)
            }
        } catch (err: Throwable) {
            runCatching { pkgInstaller.abandonSession(sessionId) }
                .onFailure { ErrorLog.swallowed("AndroidAppInstaller", it) }
            throw err
        }
    }

    private fun buildSessionParams(byteSize: Long, isUpdate: Boolean): PackageInstaller.SessionParams =
        PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setSize(byteSize)
            setInstallLocation(PackageInfo.INSTALL_LOCATION_AUTO)
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            setOriginatingUid(Process.myUid())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val actionMode = if (isUpdate) {
                    PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED
                } else {
                    PackageInstaller.SessionParams.USER_ACTION_REQUIRED
                }
                setRequireUserAction(actionMode)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                setPackageSource(PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE)
            }
        }

    private fun launchDirectActionInstall(
        context: Context,
        apk: File,
        packageName: String?,
        operationId: String,
    ) {
        val contentUri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        }.getOrElse {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        }
        @Suppress("DEPRECATION")
        val viewIntent = Intent(Intent.ACTION_INSTALL_PACKAGE, contentUri).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(viewIntent)
        publish(
            AndroidInstallEvent(
                operationId = operationId,
                packageName = packageName,
                success = true,
                launched = false,
                message = "Opened system package installer.",
            ),
        )
    }

    internal fun publish(event: AndroidInstallEvent) {
        eventBus.tryEmit(event)
    }

    fun packageName(context: Context, apk: File): String? = resolveArchivePackage(context, apk)

    fun isInstalled(context: Context, packageName: String): Boolean = runCatching {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    fun installedMatches(context: Context, apk: File): Boolean {
        if (!isValidApkFile(apk)) return false
        val pkg = resolveArchivePackage(context, apk) ?: return false
        return readRecordedDigest(context, pkg) == computeSha256(apk)
    }

    fun apkFingerprint(apk: File): String = computeSha256(apk)

    internal fun rememberInstalled(context: Context, packageName: String, fingerprint: String) {
        context.getSharedPreferences(INSTALLED_LEDGER_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(packageName, fingerprint)
            .apply()
    }

    internal fun launch(context: Context, packageName: String): Boolean = runCatching {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getLaunchIntentSenderForPackage(packageName).sendIntent(context, 0, null, null, null)
        } else {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launchIntent)
        }
        true
    }.getOrDefault(false)

    private fun isValidApkFile(apk: File): Boolean =
        apk.isFile && !Files.isSymbolicLink(apk.toPath()) && apk.extension.equals("apk", ignoreCase = true)

    @Suppress("DEPRECATION")
    private fun resolveArchivePackage(context: Context, apk: File): String? =
        context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0)?.packageName

    private fun readRecordedDigest(context: Context, packageName: String): String? =
        context.getSharedPreferences(INSTALLED_LEDGER_PREFS, Context.MODE_PRIVATE)
            .getString(packageName, null)

    private fun computeSha256(apk: File): String {
        val hasher = MessageDigest.getInstance("SHA-256")
        val chunk = ByteArray(16 * 1024)
        apk.inputStream().use { stream ->
            while (true) {
                val readBytes = stream.read(chunk)
                if (readBytes <= 0) break
                hasher.update(chunk, 0, readBytes)
            }
        }
        return HEX_FORMAT.formatHex(hasher.digest())
    }
}
