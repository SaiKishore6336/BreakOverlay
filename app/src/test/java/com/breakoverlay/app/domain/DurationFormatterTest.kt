package com.breakoverlay.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class DurationFormatterTest {

    @Test
    fun `compact formats sub-minute minutes and hours`() {
        assertEquals("0m", DurationFormatter.compact(0L))
        assertEquals("<1m", DurationFormatter.compact(1L))
        assertEquals("<1m", DurationFormatter.compact(59_999L))
        assertEquals("1m", DurationFormatter.compact(60_000L))
        assertEquals("59m", DurationFormatter.compact(59 * 60_000L))
        assertEquals("1h 00m", DurationFormatter.compact(60 * 60_000L))
        assertEquals("1h 05m", DurationFormatter.compact(65 * 60_000L))
        assertEquals("24h 00m", DurationFormatter.compact(24 * 60 * 60_000L))
    }

    @Test
    fun `compact clamps negative durations`() {
        assertEquals("0m", DurationFormatter.compact(-1L))
        assertEquals("0m", DurationFormatter.compact(Long.MIN_VALUE))
    }

    @Test
    fun `countdown rounds partial seconds up`() {
        assertEquals("0s", DurationFormatter.countdown(0L))
        assertEquals("1s", DurationFormatter.countdown(1L))
        assertEquals("1s", DurationFormatter.countdown(1_000L))
        assertEquals("2s", DurationFormatter.countdown(1_001L))
        assertEquals("8s", DurationFormatter.countdown(8_000L))
    }

    @Test
    fun `countdown formats minutes and hours with two digit minor units`() {
        assertEquals("1m 00s", DurationFormatter.countdown(60_000L))
        assertEquals("1m 01s", DurationFormatter.countdown(60_001L))
        assertEquals("1h 00m", DurationFormatter.countdown(60 * 60_000L))
        assertEquals("2h 03m", DurationFormatter.countdown(123 * 60_000L))
    }

    @Test
    fun `minutes helper uses singular and compound units`() {
        assertEquals("1 min", DurationFormatter.minutes(1))
        assertEquals("59 min", DurationFormatter.minutes(59))
        assertEquals("1 hr", DurationFormatter.minutes(60))
        assertEquals("2 hr", DurationFormatter.minutes(120))
        assertEquals("2 hr 5 min", DurationFormatter.minutes(125))
    }
}
