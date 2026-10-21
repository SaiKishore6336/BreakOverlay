package com.breakoverlay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LimitAlertPolicyTest {

    private val day = "2026-10-21"
    private val nextDay = "2026-10-22"
    private val packageName = "com.example.app"

    @Test
    fun `alert becomes eligible at the exact limit`() {
        val limitMs = 10 * 60_000L

        assertFalse(
            LimitAlertPolicy.shouldAlert(
                localDayKey = day,
                packageName = packageName,
                usedMs = limitMs - 1,
                limitMinutes = 10,
                alertedKeys = emptySet(),
            ),
        )
        assertTrue(
            LimitAlertPolicy.shouldAlert(
                localDayKey = day,
                packageName = packageName,
                usedMs = limitMs,
                limitMinutes = 10,
                alertedKeys = emptySet(),
            ),
        )
    }

    @Test
    fun `an already alerted app does not alert again on the same day`() {
        val key = LimitAlertPolicy.key(day, packageName)

        assertFalse(
            LimitAlertPolicy.shouldAlert(
                localDayKey = day,
                packageName = packageName,
                usedMs = 20 * 60_000L,
                limitMinutes = 10,
                alertedKeys = setOf(key),
            ),
        )
    }

    @Test
    fun `a new local day creates new alert eligibility`() {
        val previousKey = LimitAlertPolicy.key(day, packageName)

        assertTrue(
            LimitAlertPolicy.shouldAlert(
                localDayKey = nextDay,
                packageName = packageName,
                usedMs = 20 * 60_000L,
                limitMinutes = 10,
                alertedKeys = setOf(previousKey),
            ),
        )
    }

    @Test
    fun `invalid or disabled limit values are never announced by this policy`() {
        assertFalse(
            LimitAlertPolicy.shouldAlert(
                localDayKey = day,
                packageName = packageName,
                usedMs = Long.MAX_VALUE,
                limitMinutes = 0,
                alertedKeys = emptySet(),
            ),
        )
        assertFalse(
            LimitAlertPolicy.shouldAlert(
                localDayKey = day,
                packageName = packageName,
                usedMs = Long.MAX_VALUE,
                limitMinutes = 24 * 60 + 1,
                alertedKeys = emptySet(),
            ),
        )
    }

    @Test
    fun `alert key contains both day and package`() {
        assertEquals("$day|$packageName", LimitAlertPolicy.key(day, packageName))
    }
}
