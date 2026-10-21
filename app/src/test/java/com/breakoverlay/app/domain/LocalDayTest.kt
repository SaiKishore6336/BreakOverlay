package com.breakoverlay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class LocalDayTest {

    private val utc: TimeZone = TimeZone.getTimeZone("UTC")

    private fun instant(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int = 0,
    ): Long = Calendar.getInstance(utc).apply {
        clear()
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun `start of day is local midnight`() {
        val now = instant(2026, Calendar.OCTOBER, 21, 15, 42)
        val start = LocalDay.startOfDay(now, utc)

        assertEquals(instant(2026, Calendar.OCTOBER, 21, 0), start)
        assertEquals("2026-10-21", LocalDay.key(now, utc))
    }

    @Test
    fun `start of next day moves one calendar day forward`() {
        val now = instant(2026, Calendar.DECEMBER, 31, 23, 59)
        assertEquals(
            instant(2027, Calendar.JANUARY, 1, 0),
            LocalDay.startOfNextDay(now, utc),
        )
    }

    @Test
    fun `lookback subtracts from local midnight`() {
        val now = instant(2026, Calendar.OCTOBER, 21, 9)
        val lookback = 24L * 60L * 60L * 1000L

        assertEquals(
            instant(2026, Calendar.OCTOBER, 20, 0),
            LocalDay.startOfDayWithLookback(now, lookback, utc),
        )
    }

    @Test
    fun `day key reflects the supplied time zone`() {
        val now = instant(2026, Calendar.OCTOBER, 21, 23, 30)
        val plusTwo = TimeZone.getTimeZone("GMT+02:00")
        val minusFive = TimeZone.getTimeZone("GMT-05:00")

        assertEquals("2026-10-22", LocalDay.key(now, plusTwo))
        assertEquals("2026-10-21", LocalDay.key(now, minusFive))
    }
}
