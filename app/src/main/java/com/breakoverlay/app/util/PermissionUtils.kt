package com.breakoverlay.app.util

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.breakoverlay.app.notifications.NotificationHelper

object PermissionUtils {

    /**
     * Usage access is an AppOps permission, not a normal runtime permission. Check both
     * AppOps and the manifest permission because OEM implementations vary.
     */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            ?: return false
        val mode = try {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                appOps.checkOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName,
                )
            }
        } catch (_: RuntimeException) {
            return false
        }

        if (mode == AppOpsManager.MODE_ALLOWED) return true

        // On some OEM builds the op remains MODE_DEFAULT while the manifest permission
        // has been explicitly granted. This fallback does not assume usage access.
        return mode == AppOpsManager.MODE_DEFAULT &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.PACKAGE_USAGE_STATS,
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    fun hasPostNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED

    fun areNotificationsEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    /**
     * True if either the app-level notification switch or our gentle alert channel is
     * disabled by the user. Channel settings are owned by the system and are not
     * overridden by the app.
     */
    fun gentleNotificationChannelDisabled(context: Context): Boolean {
        if (!areNotificationsEnabled(context)) return true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return false
        val channel = manager.getNotificationChannel(NotificationHelper.CHANNEL_GENTLE)
            ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    fun effectivelyCanNotify(context: Context): Boolean =
        hasPostNotificationPermission(context) &&
            areNotificationsEnabled(context) &&
            !gentleNotificationChannelDisabled(context)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return try {
            powerManager.isIgnoringBatteryOptimizations(context.packageName)
        } catch (_: RuntimeException) {
            false
        }
    }

    fun isScreenOn(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return powerManager.isInteractive
    }

    fun isDeviceLocked(context: Context): Boolean {
        val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE)
            as? android.app.KeyguardManager ?: return false
        return keyguardManager.isKeyguardLocked
    }
}
