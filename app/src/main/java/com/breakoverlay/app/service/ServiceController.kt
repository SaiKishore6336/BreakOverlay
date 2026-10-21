package com.breakoverlay.app.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat

data class ServiceStartOutcome(
    val started: Boolean,
    val error: String? = null,
)

object ServiceController {
    private const val TAG = "ServiceController"

    const val ACTION_START = "com.breakoverlay.app.action.START_MONITORING"
    const val ACTION_REFRESH = "com.breakoverlay.app.action.REFRESH_MONITORING"

    fun start(context: Context): ServiceStartOutcome {
        val intent = Intent(context, OverlayService::class.java).apply {
            action = ACTION_START
        }
        return try {
            ContextCompat.startForegroundService(context, intent)
            ServiceStartOutcome(started = true)
        } catch (security: SecurityException) {
            Log.e(TAG, "Foreground-service start denied", security)
            ServiceStartOutcome(
                started = false,
                error = "Android denied the foreground service start.",
            )
        } catch (state: IllegalStateException) {
            // This includes ForegroundServiceStartNotAllowedException on Android 12+.
            Log.e(TAG, "Foreground service not allowed at this time", state)
            ServiceStartOutcome(
                started = false,
                error = "Android does not allow starting monitoring right now. Open the app and try again.",
            )
        } catch (error: RuntimeException) {
            Log.e(TAG, "Unexpected foreground-service start failure", error)
            ServiceStartOutcome(
                started = false,
                error = "Monitoring service could not be started.",
            )
        }
    }

    fun refresh(context: Context) {
        if (!isRunning(context)) return
        try {
            val intent = Intent(context, OverlayService::class.java).apply {
                action = ACTION_REFRESH
            }
            context.startService(intent)
        } catch (_: RuntimeException) {
            // Refresh is best effort; the monitor's periodic loop will recover.
        }
    }

    fun stop(context: Context) {
        try {
            context.stopService(Intent(context, OverlayService::class.java))
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not stop monitoring service", error)
        }
    }

    @Suppress("DEPRECATION")
    fun isRunning(context: Context): Boolean {
        return try {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val component = ComponentName(context, OverlayService::class.java)
            activityManager.getRunningServices(Int.MAX_VALUE)
                .any { it.service == component }
        } catch (_: RuntimeException) {
            // Fall back to the in-process status bus.
            MonitoringStatusBus.state.value.serviceRunning
        }
    }
}
