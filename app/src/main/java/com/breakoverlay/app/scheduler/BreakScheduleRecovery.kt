package com.breakoverlay.app.scheduler

import com.breakoverlay.app.data.PersistedBreakSchedule

/**
 * Pure boot-recovery policy for the persisted break schedule.
 *
 * Elapsed-time snapshots are only meaningful within the same boot. Reusing them after a
 * reboot would either invent or discard active time, so a snapshot from another boot is
 * discarded instead of being interpreted against the new monotonic clock.
 */
data class RestoredBreakState(
    val accumulatedActiveMs: Long,
    val snoozeExtensionMs: Long,
    val lastDueAtWallMs: Long,
    val sessionActive: Boolean,
    val shouldClearPersistence: Boolean,
)

object BreakScheduleRecovery {
    fun restore(
        schedule: PersistedBreakSchedule,
        currentBootCount: Int,
    ): RestoredBreakState {
        val hasSnapshot = schedule.bootCount >= 0
        val sameBoot = hasSnapshot && schedule.bootCount == currentBootCount
        if (!sameBoot) {
            return RestoredBreakState(
                accumulatedActiveMs = 0L,
                snoozeExtensionMs = 0L,
                lastDueAtWallMs = 0L,
                sessionActive = false,
                shouldClearPersistence = hasSnapshot,
            )
        }
        return RestoredBreakState(
            accumulatedActiveMs = schedule.accumulatedActiveMs.coerceAtLeast(0L),
            snoozeExtensionMs = schedule.snoozeExtensionMs.coerceAtLeast(0L),
            lastDueAtWallMs = schedule.lastDueAtWallMs.coerceAtLeast(0L),
            sessionActive = schedule.wasActive,
            shouldClearPersistence = false,
        )
    }
}
