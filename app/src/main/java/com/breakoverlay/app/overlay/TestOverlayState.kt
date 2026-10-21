package com.breakoverlay.app.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared in-process flag for the dashboard's "Test overlay now" preview.
 *
 * While a preview is visible, the monitoring service pauses its scheduler without ending the
 * real continuous session. This prevents the preview from adding active time, resetting the
 * session through the ineligible-app grace period, or triggering a second real overlay.
 * Daily usage and daily-limit state are never modified by a preview.
 *
 * A StateFlow is used so the service resets its accrual boundary immediately on both the
 * start and end of a preview, including previews so short that a polling tick would miss them.
 */
object TestOverlayState {
    private val mutableActive = MutableStateFlow(false)
    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    fun setActive(active: Boolean) {
        mutableActive.value = active
    }
}
