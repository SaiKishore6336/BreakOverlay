package com.breakoverlay.app.domain

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Calendar-day helpers used for daily screen limits.
 *
 * java.util.Calendar is used instead of java.time so that the app works on minSdk 24
 * without adding core-library desugaring. The default time zone is re-read on every call,
 * so a time-zone change is observed without restarting the process.
 */
object LocalDay {
    fun startOfDay(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return calendar.timeInMillis
    }

    fun startOfNextDay(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): Long {
        val calendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = startOfDay(nowMs, timeZone)
            add(Calendar.DAY_OF_YEAR, 1)
        }
        return calendar.timeInMillis
    }

    /** Stable key for persisted notification deduplication, e.g. "2026-10-21". */
    fun key(nowMs: Long, timeZone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            this.timeZone = timeZone
        }.format(Date(nowMs))

    /**
     * Start of the day that contains the instant, moved back by [extraLookbackMs].
     * Usage events are queried from this point so a session running across midnight
     * can be reconstructed instead of being discarded.
     */
    fun startOfDayWithLookback(
        nowMs: Long,
        extraLookbackMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
    ): Long = startOfDay(nowMs, timeZone) - extraLookbackMs
}
