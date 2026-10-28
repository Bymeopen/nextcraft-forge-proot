package com.nextcraft.forge.runtime

import androidx.core.net.toUri
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Long builds and agent sessions run for minutes to hours behind a foreground service, yet many ROMs still stop
 * apps that are "optimized" for battery. Being exempt from that is the one thing the app can ask for itself.
 */
object BatteryOptimization {
    fun isUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    /**
     * The system dialog that asks the user to exempt this app. Some ROMs do not ship it, so when it cannot be
     * resolved the general battery-optimization list is opened instead.
     */
    fun requestIntent(context: Context): Intent {
        val request = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        return if (request.resolveActivity(context.packageManager) != null) {
            request
        } else {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        }
    }

    /** Returns false when no settings screen exists at all (rare); the caller can fall back to a hint. */
    fun launch(context: Context, starter: (Intent) -> Unit): Boolean = try {
        starter(requestIntent(context))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
