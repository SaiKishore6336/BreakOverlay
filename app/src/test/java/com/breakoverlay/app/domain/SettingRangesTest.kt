package com.breakoverlay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingRangesTest {

    @Test
    fun `break interval boundaries are valid`() {
        assertFalse(SettingRanges.isValidBreakInterval(null))
        assertFalse(SettingRanges.isValidBreakInterval(4))
        assertTrue(SettingRanges.isValidBreakInterval(5))
        assertTrue(SettingRanges.isValidBreakInterval(240))
        assertFalse(SettingRanges.isValidBreakInterval(241))
    }

    @Test
    fun `snooze boundaries are valid`() {
        assertFalse(SettingRanges.isValidSnooze(null))
        assertFalse(SettingRanges.isValidSnooze(0))
        assertTrue(SettingRanges.isValidSnooze(1))
        assertTrue(SettingRanges.isValidSnooze(60))
        assertFalse(SettingRanges.isValidSnooze(61))
    }

    @Test
    fun `daily limit boundaries include one minute and a full day`() {
        assertFalse(SettingRanges.isValidDailyLimit(null))
        assertFalse(SettingRanges.isValidDailyLimit(0))
        assertTrue(SettingRanges.isValidDailyLimit(1))
        assertTrue(SettingRanges.isValidDailyLimit(1_440))
        assertFalse(SettingRanges.isValidDailyLimit(1_441))
    }

    @Test
    fun `coercion keeps values inside supported ranges`() {
        assertEquals(5, SettingRanges.coerceBreakInterval(0))
        assertEquals(240, SettingRanges.coerceBreakInterval(500))
        assertEquals(1, SettingRanges.coerceSnooze(-10))
        assertEquals(60, SettingRanges.coerceSnooze(90))
    }

    @Test
    fun `positive integer parser accepts digits with surrounding whitespace`() {
        assertEquals(20, SettingRanges.parsePositiveInt(" 20 "))
        assertNull(SettingRanges.parsePositiveInt("0"))
        assertNull(SettingRanges.parsePositiveInt("-20"))
        assertNull(SettingRanges.parsePositiveInt("20.5"))
        assertNull(SettingRanges.parsePositiveInt(""))
        assertNull(SettingRanges.parsePositiveInt("abc"))
    }
}
