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
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val operationId = intent.getStringExtra(AndroidAppInstaller.EXTRA_OPERATION_ID).orEmpty()
        val launchAfterInstall = intent.getBooleanExtra(AndroidAppInstaller.EXTRA_LAUNCH_AFTER_INSTALL, true)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val userAction = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (userAction != null) {
                // Prevent untrusted broadcast extras from targeting internal app components
                if (userAction.`package` == context.packageName || userAction.component?.packageName == context.packageName) {
                    return
                }
                userAction.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(userAction) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("AndroidAppInstallReceiver", it) }
            }
            return
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ?.takeIf(String::isNotBlank)
                ?: "APK installation failed."
            AndroidAppInstaller.publish(AndroidInstallEvent(operationId, null, false, false, message))
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            return
        }
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME) ?: return
        intent.getStringExtra(AndroidAppInstaller.EXTRA_APK_FINGERPRINT)?.let { fingerprint ->
            AndroidAppInstaller.rememberInstalled(context, packageName, fingerprint)
        }
        val launched = launchAfterInstall && AndroidAppInstaller.launch(context, packageName)
        AndroidAppInstaller.publish(AndroidInstallEvent(operationId, packageName, true, launched))
        if (launchAfterInstall && !launched) {
            Toast.makeText(context, "Installed $packageName. Open it from the launcher.", Toast.LENGTH_LONG).show()
        }
    }
}
