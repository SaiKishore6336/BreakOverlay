package com.breakoverlay.app.domain

/**
 * User-facing limits and the ranges enforced across the UI, persistence and scheduler.
 * Keeping them in one place prevents the dashboard and the background service from
 * disagreeing about what constitutes a valid setting.
 */
object SettingRanges {
    const val BREAK_INTERVAL_MIN = 5
    const val BREAK_INTERVAL_MAX = 240
    const val BREAK_INTERVAL_DEFAULT = 20

    const val SNOOZE_MIN = 1
    const val SNOOZE_MAX = 60
    const val SNOOZE_DEFAULT = 5

    const val DAILY_LIMIT_MIN_MINUTES = 1
    const val DAILY_LIMIT_MAX_MINUTES = 24 * 60

    fun coerceBreakInterval(minutes: Int): Int =
        minutes.coerceIn(BREAK_INTERVAL_MIN, BREAK_INTERVAL_MAX)

    fun coerceSnooze(minutes: Int): Int =
        minutes.coerceIn(SNOOZE_MIN, SNOOZE_MAX)

    fun isValidBreakInterval(minutes: Int?): Boolean =
        minutes != null && minutes in BREAK_INTERVAL_MIN..BREAK_INTERVAL_MAX

    fun isValidSnooze(minutes: Int?): Boolean =
        minutes != null && minutes in SNOOZE_MIN..SNOOZE_MAX

    fun isValidDailyLimit(minutes: Int?): Boolean =
        minutes != null && minutes in DAILY_LIMIT_MIN_MINUTES..DAILY_LIMIT_MAX_MINUTES

    /** Extracts the first positive integer from a text field, or null for invalid input. */
    fun parsePositiveInt(raw: String): Int? =
        raw.trim().toIntOrNull()?.takeIf { it > 0 }
}
