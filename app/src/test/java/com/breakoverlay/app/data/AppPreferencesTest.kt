package com.breakoverlay.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppPreferencesTest {

    @Test
    fun `all apps mode monitors apps independently of selection`() {
        val preferences = AppPreferences(monitorAllApps = true)

        assertTrue(preferences.isMonitored("com.example.any"))
        assertTrue(preferences.isMonitored("com.example.other"))
    }

    @Test
    fun `selected only mode monitors only selected package names`() {
        val preferences = AppPreferences(
            monitorAllApps = false,
            selectedPackages = setOf("com.example.selected"),
        )

        assertTrue(preferences.isMonitored("com.example.selected"))
        assertFalse(preferences.isMonitored("com.example.other"))
    }

    @Test
    fun `limit helpers expose enabled and disabled limits distinctly`() {
        val enabled = DailyLimit(minutes = 30, enabled = true)
        val disabled = DailyLimit(minutes = 45, enabled = false)
        val preferences = AppPreferences(
            dailyLimits = mapOf(
                "com.example.enabled" to enabled,
                "com.example.disabled" to disabled,
            ),
        )

        assertEquals(enabled, preferences.limitFor("com.example.enabled"))
        assertEquals(enabled, preferences.enabledLimitFor("com.example.enabled"))
        assertNull(preferences.enabledLimitFor("com.example.disabled"))
        assertNull(preferences.limitFor("com.example.missing"))
    }

    @Test
    fun `saving a daily limit does not implicitly enable monitoring`() {
        val preferences = AppPreferences(
            monitoringEnabled = false,
            dailyLimits = mapOf("com.example.app" to DailyLimit(20)),
        )

        assertFalse(preferences.monitoringEnabled)
        assertTrue(preferences.isMonitored("com.example.app"))
    }

    @Test
    fun `schedule defaults represent a missing persisted snapshot`() {
        val schedule = PersistedBreakSchedule()

        assertEquals(0L, schedule.accumulatedActiveMs)
        assertEquals(0L, schedule.snoozeExtensionMs)
        assertEquals(-1, schedule.bootCount)
        assertFalse(schedule.wasActive)
    }
}
