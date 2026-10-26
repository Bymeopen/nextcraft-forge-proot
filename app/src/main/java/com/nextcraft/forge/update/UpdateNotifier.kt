package com.nextcraft.forge.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.nextcraft.forge.R
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.ui.LocaleHelper

/** Tells the user that a newer build is available, once per version, also when the app is closed. */
object UpdateNotifier {
    private const val CHANNEL_ID = "app_updates"
    private const val NOTIFICATION_ID = 4101

    /** A version is announced once: only a build newer than the last announced one gets a notification. */
    internal fun shouldNotify(availableCode: Long, lastNotifiedCode: Long): Boolean = availableCode > lastNotifiedCode

    /** Posts the notification unless this version was already announced or notifications are off. Returns true if posted. */
    fun notifyIfNew(context: Context, info: AppUpdateInfo): Boolean {
        val preferences = AppPreferences(context)
        if (!preferences.updateNotifications) return false
        if (!shouldNotify(info.versionCode, preferences.lastNotifiedUpdateCode)) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        // Without permission (Android 13+) nothing is shown; the version stays unannounced so a later grant still works.
        if (!manager.areNotificationsEnabled()) return false
        val localized = LocaleHelper.wrapContext(context)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, localized.getString(R.string.upd_notif_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP }
            ?: return false
        val open = PendingIntent.getActivity(context, 102, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(localized.getString(R.string.upd_notif_title))
            .setContentText(localized.getString(R.string.upd_notif_text, info.versionName))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        preferences.lastNotifiedUpdateCode = info.versionCode
        return true
    }
}
