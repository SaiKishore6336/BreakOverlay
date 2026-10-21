package com.breakoverlay.app.domain

import java.util.Locale
import kotlin.math.ceil
import kotlin.math.max

object DurationFormatter {
    /** Compact duration for dashboard cards: "1h 04m", "12m", "<1m". */
    fun compact(durationMs: Long): String {
        val safe = max(0L, durationMs)
        val totalMinutes = safe / 60_000L
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return when {
            hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
            totalMinutes > 0 -> "${totalMinutes}m"
            safe > 0 -> "<1m"
            else -> "0m"
        }
    }

    /** Countdown label with at most one fractional unit: "8s", "4m 05s", "1h 02m". */
    fun countdown(durationMs: Long): String {
        val safe = max(0L, durationMs)
        val totalSeconds = ceil(safe / 1000.0).toLong()
        return when {
            totalSeconds < 60 -> "${totalSeconds}s"
            totalSeconds < 3600 -> {
                val minutes = totalSeconds / 60
                val seconds = totalSeconds % 60
                String.format(Locale.US, "%dm %02ds", minutes, seconds)
            }
            else -> {
                val hours = totalSeconds / 3600
                val minutes = (totalSeconds % 3600) / 60
                String.format(Locale.US, "%dh %02dm", hours, minutes)
            }
        }
    }

    fun minutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} hr"
        else -> "${minutes / 60} hr ${minutes % 60} min"
    }
}
