package com.nextcraft.forge.ui

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.R

/**
 * Brings the app back after a sign-in finished in the browser. Android usually blocks activity starts from the
 * background, so the direct start is best effort and a heads-up notification ("tap to return") always follows.
 */
object ReturnToApp {
    private const val CHANNEL_ID = "sign_in"
    private const val NOTIFICATION_ID = 4103

    fun afterSignIn(context: Context, text: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        } ?: return
        runCatching { context.startActivity(launch) }.onFailure { com.nextcraft.forge.runtime.ErrorLog.swallowed("ReturnToApp", it) }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        val localized = LocaleHelper.wrapContext(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized.getString(R.string.notif_channel_sign_in), NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(context, 104, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(text)
                .setContentText(localized.getString(R.string.notif_sign_in_return))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    /** Called when the app is visible again: the "tap to return" notification has done its job. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
    }
}
