package com.breakoverlay.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.breakoverlay.app.MainActivity
import com.breakoverlay.app.R
import com.breakoverlay.app.domain.DurationFormatter
import com.breakoverlay.app.util.PermissionUtils
import kotlin.math.ceil

/**
 * Owns notification channels and all notifications. Channel importance, sound and vibration
 * are only set at creation time; after that the user's system settings are authoritative.
 */
class NotificationHelper(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return

        val background = NotificationChannel(
            CHANNEL_BACKGROUND,
            context.getString(R.string.channel_background_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_background_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }

        val defaultSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val gentle = NotificationChannel(
            CHANNEL_GENTLE,
            context.getString(R.string.channel_gentle_name),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_gentle_description)
            enableVibration(true)
            if (defaultSound != null) {
                val attributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                setSound(defaultSound, attributes)
            }
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }

        notificationManager.createNotificationChannels(listOf(background, gentle))
    }

    fun buildServiceNotification(title: String, text: String): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(dashboardPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setSilent(true)
            .setColor(context.getColor(R.color.brand_primary))
            .addAction(
                0,
                context.getString(R.string.notification_action_dashboard),
                dashboardPendingIntent(),
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(
                NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
            )
        }
        return builder.build()
    }

    /** Returns true only when the notification was actually accepted for posting. */
    fun showScreenLimitReached(
        packageName: String,
        appLabel: String,
        usedMs: Long,
        limitMinutes: Int,
        estimated: Boolean,
    ): Boolean {
        if (!PermissionUtils.effectivelyCanNotify(context)) return false
        val usedMinutes = ceil(usedMs.coerceAtLeast(0L) / 60_000.0).toInt()
        val body = context.getString(
            R.string.notification_limit_text,
            usedMinutes,
            limitMinutes,
        )
        val finalBody = if (estimated) {
            body + " " + context.getString(R.string.notification_estimated_suffix)
        } else {
            body
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_GENTLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(
                context.getString(R.string.notification_limit_title, appLabel),
            )
            .setContentText(finalBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(finalBody))
            .setContentIntent(dashboardPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(context.getColor(R.color.brand_primary))
            .build()

        return try {
            NotificationManagerCompat.from(context).notify(
                screenLimitNotificationId(packageName),
                notification,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun showOverlayPermissionNeeded(): Boolean {
        if (!PermissionUtils.effectivelyCanNotify(context)) return false
        val notification = NotificationCompat.Builder(context, CHANNEL_GENTLE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_overlay_title))
            .setContentText(context.getString(R.string.notification_overlay_text))
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(context.getString(R.string.notification_overlay_text)),
            )
            .setContentIntent(dashboardPendingIntent())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(context.getColor(R.color.brand_primary))
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(
                OVERLAY_PERMISSION_NOTIFICATION_ID,
                notification,
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun updateServiceNotification(title: String, text: String) {
        try {
            notificationManager.notify(
                SERVICE_NOTIFICATION_ID,
                buildServiceNotification(title, text),
            )
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS can be denied on API 33+. startForeground still owns the
            // service; the dashboard explains that alerts may be hidden.
        }
    }

    fun cancelServiceNotification() {
        notificationManager.cancel(SERVICE_NOTIFICATION_ID)
    }

    fun monitoringStatusText(
        monitoring: Boolean,
        permissionIssue: Boolean,
        isAccruing: Boolean,
        remainingMs: Long?,
    ): String = when {
        !monitoring -> context.getString(R.string.service_notification_stopped)
        permissionIssue -> context.getString(R.string.service_notification_permission)
        remainingMs != null && isAccruing ->
            context.getString(
                R.string.service_notification_next_break,
                DurationFormatter.countdown(remainingMs),
            )
        else -> context.getString(R.string.service_notification_paused)
    }

    private fun dashboardPendingIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            REQUEST_DASHBOARD,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun screenLimitNotificationId(packageName: String): Int =
        10_000 + (packageName.hashCode() and 0x0fffffff)

    companion object {
        const val CHANNEL_BACKGROUND = "break_overlay_channel"
        const val CHANNEL_GENTLE = "break_overlay_gentle_channel"

        const val SERVICE_NOTIFICATION_ID = 1001
        const val OVERLAY_PERMISSION_NOTIFICATION_ID = 2001
        private const val REQUEST_DASHBOARD = 3001
    }
}
