package com.breakoverlay.app.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BreakSchedulerTest {

    private val minute = 60_000L

    private fun tick(
        scheduler: BreakScheduler,
        elapsedMs: Long,
        eligible: Boolean = true,
        screenOn: Boolean = true,
        wallMs: Long = elapsedMs,
    ): BreakSchedulerTick = scheduler.tick(
        nowElapsedMs = elapsedMs,
        nowWallMs = wallMs,
        screenOn = screenOn,
        eligibleForeground = eligible,
    )

    /** Advances in one-minute observations so tests exercise normal monitor ticks, not gaps. */
    private fun advance(
        scheduler: BreakScheduler,
        fromElapsedMs: Long,
        toElapsedMs: Long,
        eligible: Boolean = true,
        screenOn: Boolean = true,
    ): BreakSchedulerTick {
        var current = fromElapsedMs
        var result = tick(scheduler, current, eligible, screenOn)
        while (current < toElapsedMs) {
            current = minOf(current + minute, toElapsedMs)
            result = tick(scheduler, current, eligible, screenOn)
        }
        return result
    }

    @Test
    fun `break triggers after the configured interval of eligible active time`() {
        val scheduler = BreakScheduler(intervalMinutes = 20, snoozeMinutes = 5)

        val afterFive = advance(scheduler, 0L, 5 * minute)
        assertEquals(5 * minute, afterFive.continuousSessionMs)
        assertTrue(afterFive.sessionActive)
        assertTrue(afterFive.isAccruing)
        assertFalse(afterFive.isDue)
        assertFalse(afterFive.shouldTriggerBreak)

        val atLimit = advance(scheduler, 5 * minute, 20 * minute)
        assertTrue(atLimit.isDue)
        assertTrue(atLimit.shouldTriggerBreak)
        assertEquals(0L, atLimit.remainingActiveMs)
    }

    @Test
    fun `ineligible foreground app pauses the session and resets after the grace period`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        tick(scheduler, 0L)
        val active = tick(scheduler, 4 * minute)
        assertEquals(4 * minute, active.continuousSessionMs)

        val paused = tick(scheduler, 5 * minute, eligible = false)
        assertTrue(paused.sessionActive)
        assertEquals(4 * minute, paused.continuousSessionMs)
        assertFalse(paused.isAccruing)

        val stillPaused = tick(scheduler, 6 * minute, eligible = false)
        assertTrue(stillPaused.sessionActive)

        val reset = tick(scheduler, 7 * minute, eligible = false)
        assertFalse(reset.sessionActive)
        assertEquals(0L, reset.continuousSessionMs)
        assertEquals(20 * minute, reset.remainingActiveMs)
    }

    @Test
    fun `brief ineligible gap preserves the session and does not invent time`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        tick(scheduler, 0L)
        tick(scheduler, 4 * minute)
        tick(scheduler, 5 * minute, eligible = false)

        val resumed = tick(scheduler, 6 * minute, eligible = true)
        assertTrue(resumed.sessionActive)
        assertTrue(resumed.isAccruing)
        assertEquals(4 * minute, resumed.continuousSessionMs)

        val next = tick(scheduler, 7 * minute, eligible = true)
        assertEquals(5 * minute, next.continuousSessionMs)
    }

    @Test
    fun `screen off ends the session and screen on starts a fresh one`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        tick(scheduler, 0L)
        tick(scheduler, 5 * minute)

        val screenOff = tick(scheduler, 6 * minute, screenOn = false)
        assertFalse(screenOff.sessionActive)
        assertEquals(0L, screenOff.continuousSessionMs)
        assertFalse(screenOff.isAccruing)

        val screenOn = tick(scheduler, 7 * minute, screenOn = true)
        assertTrue(screenOn.sessionActive)
        assertEquals(0L, screenOn.continuousSessionMs)
    }

    @Test
    fun `a long unobserved elapsed gap is not charged as active use`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        tick(scheduler, 0L)
        tick(scheduler, 5 * minute)

        val afterLongGap = tick(scheduler, 15 * minute)
        assertEquals(5 * minute, afterLongGap.continuousSessionMs)
        assertTrue(afterLongGap.sessionActive)

        val afterNormalTick = tick(scheduler, 20 * minute)
        assertEquals(10 * minute, afterNormalTick.continuousSessionMs)
    }

    @Test
    fun `overlay pauses accrual and snooze extends the next deadline`() {
        val scheduler = BreakScheduler(intervalMinutes = 20, snoozeMinutes = 5)

        val due = advance(scheduler, 0L, 20 * minute)
        assertTrue(due.isDue)

        assertTrue(scheduler.markOverlayShown(20 * minute))
        assertFalse(scheduler.markOverlayShown(20 * minute))

        val whileOverlay = tick(scheduler, 25 * minute)
        assertEquals(20 * minute, whileOverlay.continuousSessionMs)
        assertFalse(whileOverlay.isAccruing)
        assertTrue(whileOverlay.isDue)
        assertFalse(whileOverlay.shouldTriggerBreak)

        scheduler.onSnooze(25 * minute)
        assertEquals(5 * minute, scheduler.currentSnoozeExtensionMs)
        assertFalse(scheduler.isOverlayShowing)

        val afterSnoozeBoundary = tick(scheduler, 26 * minute)
        assertEquals(20 * minute, afterSnoozeBoundary.continuousSessionMs)
        assertEquals(5 * minute, afterSnoozeBoundary.remainingActiveMs)
        assertFalse(afterSnoozeBoundary.isDue)

        val shortExtension = advance(scheduler, 26 * minute, 30 * minute)
        assertEquals(24 * minute, shortExtension.continuousSessionMs)
        assertFalse(shortExtension.isDue)

        val dueAgain = tick(scheduler, 31 * minute)
        assertTrue(dueAgain.isDue)
        assertTrue(dueAgain.shouldTriggerBreak)
    }

    @Test
    fun `snooze without an active overlay is ignored`() {
        val scheduler = BreakScheduler(intervalMinutes = 20, snoozeMinutes = 5)

        scheduler.onSnooze(10 * minute)

        assertEquals(0L, scheduler.currentSnoozeExtensionMs)
        val state = tick(scheduler, 0L)
        assertEquals(20 * minute, state.remainingActiveMs)
    }

    @Test
    fun `preview boundary reset preserves the real session without charging preview time`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        tick(scheduler, 0L)
        tick(scheduler, 5 * minute)

        scheduler.resetAccrualBoundary()
        val afterPreview = tick(scheduler, 6 * minute)

        assertTrue(afterPreview.sessionActive)
        assertEquals(5 * minute, afterPreview.continuousSessionMs)
        assertTrue(afterPreview.isAccruing)

        val nextRealTick = tick(scheduler, 7 * minute)
        assertEquals(6 * minute, nextRealTick.continuousSessionMs)
    }

    @Test
    fun `cancelled overlay resets the interrupted session`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        advance(scheduler, 0L, 10 * minute)
        scheduler.markOverlayShown(10 * minute)
        assertTrue(scheduler.isOverlayShowing)

        scheduler.onOverlayCancelled()

        assertFalse(scheduler.isOverlayShowing)
        val afterCancellation = tick(scheduler, 11 * minute)
        assertTrue(afterCancellation.sessionActive)
        assertEquals(0L, afterCancellation.continuousSessionMs)
    }

    @Test
    fun `restored snapshot continues accumulated time and snooze extension`() {
        val scheduler = BreakScheduler(
            intervalMinutes = 20,
            snoozeMinutes = 5,
            initialAccumulatedActiveMs = 12 * minute,
            initialSnoozeExtensionMs = 3 * minute,
            initialLastDueAtWallMs = 100L,
            initialSessionActive = true,
        )

        val first = tick(scheduler, 1_000L)
        assertTrue(first.sessionActive)
        assertEquals(12 * minute, first.continuousSessionMs)
        assertEquals(11 * minute, first.remainingActiveMs)

        val due = advance(scheduler, 1_000L, 1_000L + 11 * minute)
        assertTrue(due.isDue)
        assertTrue(due.shouldTriggerBreak)

        val snapshot = scheduler.snapshot(
            bootCount = 7,
            nowElapsedMs = 1_000L + 11 * minute,
            nowWallMs = 5_000L,
        )
        assertEquals(23 * minute, snapshot.accumulatedActiveMs)
        assertEquals(3 * minute, snapshot.snoozeExtensionMs)
        assertEquals(7, snapshot.bootCount)
        assertTrue(snapshot.wasActive)
    }

    @Test
    fun `changing interval applies immediately without losing accumulated time`() {
        val scheduler = BreakScheduler(intervalMinutes = 20)

        val atTenMinutes = advance(scheduler, 0L, 10 * minute)
        assertEquals(10 * minute, atTenMinutes.continuousSessionMs)
        scheduler.configure(intervalMinutes = 5, snoozeMinutes = 2)

        val shortened = tick(scheduler, 10 * minute + 1_000L)
        assertTrue(shortened.isDue)
        assertTrue(shortened.shouldTriggerBreak)
        assertEquals(10 * minute + 1_000L, shortened.continuousSessionMs)
        assertEquals(0L, shortened.remainingActiveMs)
    }

    @Test
    fun `invalid snooze value is coerced to the supported maximum`() {
        val scheduler = BreakScheduler(intervalMinutes = 1, snoozeMinutes = 999)
        tick(scheduler, 0L)

        val due = tick(scheduler, 5 * minute)
        assertTrue(due.isDue)
        scheduler.markOverlayShown(5 * minute)
        scheduler.onSnooze(5 * minute)

        assertEquals(60 * minute, scheduler.currentSnoozeExtensionMs)
    }
}
