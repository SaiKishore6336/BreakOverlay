package com.breakoverlay.app.scheduler

import com.breakoverlay.app.data.PersistedBreakSchedule
import com.breakoverlay.app.domain.SettingRanges
import kotlin.math.max

data class BreakSchedulerTick(
    val shouldTriggerBreak: Boolean,
    val isDue: Boolean,
    val remainingActiveMs: Long,
    val continuousSessionMs: Long,
    val sessionActive: Boolean,
    val isAccruing: Boolean,
    val snoozeExtensionMs: Long,
    val estimatedDueAtWallMs: Long?,
)

/**
 * Timestamp-driven continuous-session break scheduler.
 *
 * Policy:
 *  - Active time accrues only while the screen is on, the device is unlocked, and the
 *    foreground app is within the selected monitoring scope.
 *  - Switching between eligible apps preserves the continuous session.
 *  - Brief ineligible foreground time (up to two minutes), such as a system overlay,
 *    pauses rather than resets the session. Longer gaps end and reset it.
 *  - Screen-off ends the session and resets the accumulated time.
 *  - The periodic break is due after [interval] minutes of active time, plus any
 *    configured snooze extension.
 *  - Snoozing extends the next break by active-time minutes; it never changes Android
 *    usage statistics or daily limits.
 *  - Time does not accrue while the app's own overlay is visible, but the session itself
 *    is not reset by the overlay.
 *
 * The class is framework-independent and is tested with fake timestamps.
 */
class BreakScheduler(
    intervalMinutes: Int = SettingRanges.BREAK_INTERVAL_DEFAULT,
    snoozeMinutes: Int = SettingRanges.SNOOZE_DEFAULT,
    initialAccumulatedActiveMs: Long = 0L,
    initialSnoozeExtensionMs: Long = 0L,
    initialLastDueAtWallMs: Long = 0L,
    initialSessionActive: Boolean = false,
) {
    private var intervalMs: Long = SettingRanges.coerceBreakInterval(intervalMinutes) * 60_000L
    private var snoozeMs: Long = SettingRanges.coerceSnooze(snoozeMinutes) * 60_000L

    private var accumulatedActiveMs: Long = max(0L, initialAccumulatedActiveMs)
    private var snoozeExtensionMs: Long =
        max(0L, initialSnoozeExtensionMs).coerceAtMost(MAX_SNOOZE_EXTENSION_MS)
    private var lastDueAtWallMs: Long = max(0L, initialLastDueAtWallMs)
    private var sessionActive: Boolean = initialSessionActive

    private var overlayShowing: Boolean = false
    private var lastTickElapsedMs: Long? = null
    private var wasAccruingAtLastTick: Boolean = false
    private var ineligibleSinceElapsedMs: Long? = null
    private var sessionStartedAtElapsedMs: Long? = null

    val currentSnoozeExtensionMs: Long get() = snoozeExtensionMs
    val isOverlayShowing: Boolean get() = overlayShowing

    fun configure(intervalMinutes: Int, snoozeMinutes: Int) {
        intervalMs = SettingRanges.coerceBreakInterval(intervalMinutes) * 60_000L
        snoozeMs = SettingRanges.coerceSnooze(snoozeMinutes) * 60_000L
        // No timer object is recreated; the next tick compares the same accumulated
        // active time to the new threshold. This avoids duplicate monitoring loops.
    }

    /**
     * Advances the scheduler to [nowElapsedMs]. Call on every monitor tick.
     * [eligibleForeground] must already include screen, keyguard and monitoring-scope logic.
     */
    fun tick(
        nowElapsedMs: Long,
        nowWallMs: Long,
        screenOn: Boolean,
        eligibleForeground: Boolean,
    ): BreakSchedulerTick {
        val previousElapsed = lastTickElapsedMs
        val delta = if (previousElapsed == null) {
            0L
        } else {
            (nowElapsedMs - previousElapsed).coerceAtLeast(0L)
        }

        var sessionReset = false

        if (!screenOn) {
            sessionReset = resetSession()
            ineligibleSinceElapsedMs = null
        } else if (!eligibleForeground && !overlayShowing) {
            if (sessionActive) {
                val ineligibleStart = ineligibleSinceElapsedMs ?: nowElapsedMs.also {
                    ineligibleSinceElapsedMs = it
                }
                if (nowElapsedMs - ineligibleStart >= INELIGIBLE_GRACE_MS) {
                    sessionReset = resetSession()
                    ineligibleSinceElapsedMs = null
                }
            }
        } else if (eligibleForeground) {
            ineligibleSinceElapsedMs = null
            if (!sessionActive) {
                sessionActive = true
                sessionStartedAtElapsedMs = nowElapsedMs
            } else if (sessionStartedAtElapsedMs == null) {
                sessionStartedAtElapsedMs = nowElapsedMs
            }
        }

        val accruingNow = screenOn && eligibleForeground && !overlayShowing
        // Do not charge a gap larger than the trusted tick window, for example after a
        // process pause or a long Doze interval. The observed time is retained but the
        // unobserved gap is not invented.
        if (
            accruingNow &&
            wasAccruingAtLastTick &&
            !sessionReset &&
            delta in 1..MAX_ACCRUAL_TICK_GAP_MS
        ) {
            accumulatedActiveMs += delta
        }

        wasAccruingAtLastTick = accruingNow
        lastTickElapsedMs = nowElapsedMs

        val threshold = thresholdMs()
        val isDue = sessionActive && accumulatedActiveMs >= threshold
        val remaining = (threshold - accumulatedActiveMs).coerceAtLeast(0L)
        val estimatedDueAtWall = if (accruingNow && !isDue) nowWallMs + remaining else null

        return BreakSchedulerTick(
            shouldTriggerBreak = isDue && !overlayShowing,
            isDue = isDue,
            remainingActiveMs = remaining,
            continuousSessionMs = if (sessionActive) accumulatedActiveMs else 0L,
            sessionActive = sessionActive,
            isAccruing = accruingNow,
            snoozeExtensionMs = snoozeExtensionMs,
            estimatedDueAtWallMs = estimatedDueAtWall,
        )
    }

    /** Marks a real overlay as visible and prevents duplicate triggers. */
    fun markOverlayShown(nowWallMs: Long): Boolean {
        if (overlayShowing) return false
        overlayShowing = true
        lastDueAtWallMs = max(0L, nowWallMs)
        return true
    }

    /**
     * Called only after the mandatory countdown completes and the user activates the
     * snooze button. Adds the configured active-use extension without changing usage data.
     */
    fun onSnooze(nowWallMs: Long) {
        if (!overlayShowing) return
        overlayShowing = false
        if (sessionActive) {
            snoozeExtensionMs = (snoozeExtensionMs + snoozeMs)
                .coerceAtMost(MAX_SNOOZE_EXTENSION_MS)
        } else {
            snoozeExtensionMs = 0L
        }
        lastDueAtWallMs = max(0L, nowWallMs)
        // The first post-overlay tick establishes a new accrual boundary instead of
        // charging time spent looking at the overlay as monitored-app usage.
        wasAccruingAtLastTick = false
        lastTickElapsedMs = null
    }

    /**
     * Drops the next elapsed-time observation without changing accumulated session state.
     * Used while an overlay preview freezes monitoring: the preview duration is not charged
     * as active use, and a long preview is not mistaken for an unobserved gap.
     */
    fun resetAccrualBoundary() {
        lastTickElapsedMs = null
        wasAccruingAtLastTick = false
    }

    /**
     * Handles an overlay that disappears without the user snoozing (for example because
     * overlay permission was revoked). The interrupted session is discarded so it cannot
     * become permanently due without a way to dismiss it.
     */
    fun onOverlayCancelled() {
        overlayShowing = false
        resetSession()
        lastTickElapsedMs = null
        wasAccruingAtLastTick = false
    }

    /**
     * Called when monitoring is disabled or an overlay cannot be removed. The service
     * persists a cleared schedule separately.
     */
    fun resetAll() {
        accumulatedActiveMs = 0L
        snoozeExtensionMs = 0L
        lastDueAtWallMs = 0L
        sessionActive = false
        overlayShowing = false
        lastTickElapsedMs = null
        wasAccruingAtLastTick = false
        ineligibleSinceElapsedMs = null
        sessionStartedAtElapsedMs = null
    }

    fun snapshot(
        bootCount: Int,
        nowElapsedMs: Long,
        nowWallMs: Long,
    ): PersistedBreakSchedule = PersistedBreakSchedule(
        accumulatedActiveMs = accumulatedActiveMs,
        snoozeExtensionMs = snoozeExtensionMs,
        lastDueAtWallMs = lastDueAtWallMs,
        savedAtElapsedMs = nowElapsedMs,
        savedAtWallMs = nowWallMs,
        bootCount = bootCount,
        wasActive = sessionActive && !overlayShowing,
    )

    private fun resetSession(): Boolean {
        val hadState = accumulatedActiveMs > 0L || snoozeExtensionMs > 0L || sessionActive
        accumulatedActiveMs = 0L
        snoozeExtensionMs = 0L
        lastDueAtWallMs = 0L
        sessionActive = false
        sessionStartedAtElapsedMs = null
        ineligibleSinceElapsedMs = null
        return hadState
    }

    private fun thresholdMs(): Long = intervalMs + snoozeExtensionMs

    companion object {
        const val INELIGIBLE_GRACE_MS = 2L * 60_000L
        private const val MAX_ACCRUAL_TICK_GAP_MS = 5L * 60_000L
        private const val MAX_SNOOZE_EXTENSION_MS = 24L * 60L * 60L * 1000L
    }
}
