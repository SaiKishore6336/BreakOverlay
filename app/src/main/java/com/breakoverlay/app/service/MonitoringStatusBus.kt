package com.breakoverlay.app.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitoringSnapshot(
    val serviceRunning: Boolean = false,
    val monitoringEnabled: Boolean = false,
    val activePackage: String? = null,
    val sessionActive: Boolean = false,
    val isAccruing: Boolean = false,
    val continuousSessionMs: Long = 0L,
    val remainingActiveMs: Long? = null,
    val estimatedNextBreakWallMs: Long? = null,
    val overlayShowing: Boolean = false,
    val screenOn: Boolean = false,
    val usageAccessGranted: Boolean = false,
    val overlayAccessGranted: Boolean = false,
    val notificationsAvailable: Boolean = false,
    val statusNote: String? = null,
    val lastUpdatedWallMs: Long = 0L,
)

/**
 * In-process status bus. The activity and foreground service normally share one process.
 * The activity still verifies ServiceController.isRunning() on resume so stale UI is not
 * presented as a running service after a process restart.
 */
object MonitoringStatusBus {
    private val mutableState = MutableStateFlow(MonitoringSnapshot())
    val state: StateFlow<MonitoringSnapshot> = mutableState.asStateFlow()

    fun update(transform: (MonitoringSnapshot) -> MonitoringSnapshot) {
        mutableState.update(transform)
    }

    fun markStopped(monitoringEnabled: Boolean) {
        mutableState.update {
            it.copy(
                serviceRunning = false,
                monitoringEnabled = monitoringEnabled,
                activePackage = null,
                sessionActive = false,
                isAccruing = false,
                continuousSessionMs = 0L,
                remainingActiveMs = null,
                estimatedNextBreakWallMs = null,
                overlayShowing = false,
                lastUpdatedWallMs = System.currentTimeMillis(),
            )
        }
    }
}
