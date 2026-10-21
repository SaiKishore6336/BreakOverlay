package com.breakoverlay.app.scheduler

import com.breakoverlay.app.data.PersistedBreakSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakScheduleRecoveryTest {

    private val schedule = PersistedBreakSchedule(
        accumulatedActiveMs = 12 * 60_000L,
        snoozeExtensionMs = 3 * 60_000L,
        lastDueAtWallMs = 99L,
        savedAtElapsedMs = 1_000L,
        savedAtWallMs = 2_000L,
        bootCount = 14,
        wasActive = true,
    )

    @Test
    fun `same boot restores active session and extensions`() {
        val restored = BreakScheduleRecovery.restore(schedule, currentBootCount = 14)

        assertEquals(12 * 60_000L, restored.accumulatedActiveMs)
        assertEquals(3 * 60_000L, restored.snoozeExtensionMs)
        assertEquals(99L, restored.lastDueAtWallMs)
        assertTrue(restored.sessionActive)
        assertFalse(restored.shouldClearPersistence)
    }

    @Test
    fun `snapshot from another boot is discarded and cleared`() {
        val restored = BreakScheduleRecovery.restore(schedule, currentBootCount = 15)

        assertEquals(0L, restored.accumulatedActiveMs)
        assertEquals(0L, restored.snoozeExtensionMs)
        assertEquals(0L, restored.lastDueAtWallMs)
        assertFalse(restored.sessionActive)
        assertTrue(restored.shouldClearPersistence)
    }

    @Test
    fun `missing snapshot does not request unnecessary clearing`() {
        val restored = BreakScheduleRecovery.restore(
            PersistedBreakSchedule(),
            currentBootCount = 9,
        )

        assertEquals(0L, restored.accumulatedActiveMs)
        assertFalse(restored.sessionActive)
        assertFalse(restored.shouldClearPersistence)
    }

    @Test
    fun `snapshot values are clamped to non negative values`() {
        val corrupt = schedule.copy(
            accumulatedActiveMs = -5L,
            snoozeExtensionMs = -10L,
            lastDueAtWallMs = -20L,
        )

        val restored = BreakScheduleRecovery.restore(corrupt, currentBootCount = 14)

        assertEquals(0L, restored.accumulatedActiveMs)
        assertEquals(0L, restored.snoozeExtensionMs)
        assertEquals(0L, restored.lastDueAtWallMs)
        assertTrue(restored.sessionActive)
    }
}
