package com.breakoverlay.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.breakoverlay.app.data.PreferencesRepository
import com.breakoverlay.app.service.ServiceController
import com.breakoverlay.app.util.PermissionUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Restarts monitoring after reboot only when the user had enabled it and the critical
 * permissions still exist. Android may still reject a background foreground-service
 * start; that outcome is logged and the dashboard will show monitoring as stopped.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val preferences = PreferencesRepository(appContext).readPreferences()
                if (!preferences.monitoringEnabled) return@launch

                val usageAccess = PermissionUtils.hasUsageAccess(appContext)
                val overlayAccess = PermissionUtils.canDrawOverlays(appContext)
                if (!usageAccess || !overlayAccess) {
                    Log.w(
                        TAG,
                        "Monitoring enabled, but boot restart skipped because permissions are missing " +
                            "(usage=$usageAccess overlay=$overlayAccess)",
                    )
                    return@launch
                }

                val outcome = ServiceController.start(appContext)
                if (!outcome.started) {
                    Log.w(
                        TAG,
                        "Monitoring could not restart after boot: ${outcome.error ?: "unknown error"}",
                    )
                }
            } catch (error: RuntimeException) {
                Log.e(TAG, "Boot restart failed", error)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "BootReceiver"
    }
}
