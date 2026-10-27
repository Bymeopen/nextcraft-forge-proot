package com.nextcraft.forge.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

class AndroidAppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AndroidAppInstaller.ACTION_INSTALL_RESULT) return

        val opId = intent.getStringExtra(AndroidAppInstaller.EXTRA_OPERATION_ID).orEmpty()
        val autoLaunch = intent.getBooleanExtra(AndroidAppInstaller.EXTRA_LAUNCH_AFTER_INSTALL, true)
        val statusCode = intent.getIntExtra(
            PackageInstaller.EXTRA_STATUS,
            PackageInstaller.STATUS_FAILURE,
        )

        when (statusCode) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> dispatchConfirmationIntent(context, intent)
            PackageInstaller.STATUS_SUCCESS -> completeSuccessfulInstall(context, intent, opId, autoLaunch)
            else -> reportFailedInstall(context, intent, opId)
        }
    }

    private fun dispatchConfirmationIntent(context: Context, callbackIntent: Intent) {
        val confirmation: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            callbackIntent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            callbackIntent.getParcelableExtra(Intent.EXTRA_INTENT)
        }
        val targetIntent = confirmation ?: return
        val selfPkg = context.packageName
        val targetsSelf = targetIntent.`package` == selfPkg || targetIntent.component?.packageName == selfPkg
        if (targetsSelf) return

        targetIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(targetIntent) }
            .onFailure { ErrorLog.swallowed("AndroidAppInstallReceiver", it) }
    }

    private fun completeSuccessfulInstall(
        context: Context,
        callbackIntent: Intent,
        opId: String,
        autoLaunch: Boolean,
    ) {
        val installedPkg = callbackIntent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        val sha256 = callbackIntent.getStringExtra(AndroidAppInstaller.EXTRA_APK_FINGERPRINT)
        if (!sha256.isNullOrBlank()) {
            AndroidAppInstaller.rememberInstalled(context, installedPkg, sha256)
        }
        val didLaunch = autoLaunch && AndroidAppInstaller.launch(context, installedPkg)
        AndroidAppInstaller.publish(
            AndroidInstallEvent(
                operationId = opId,
                packageName = installedPkg,
                success = true,
                launched = didLaunch,
            ),
        )
        if (autoLaunch && !didLaunch) {
            Toast.makeText(
                context,
                "Installed $installedPkg. Open it from the launcher.",
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private fun reportFailedInstall(context: Context, callbackIntent: Intent, opId: String) {
        val detail = callbackIntent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "APK installation failed."
        AndroidAppInstaller.publish(
            AndroidInstallEvent(
                operationId = opId,
                packageName = null,
                success = false,
                launched = false,
                message = detail,
            ),
        )
        Toast.makeText(context, detail, Toast.LENGTH_LONG).show()
    }
}
