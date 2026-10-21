package com.breakoverlay.app.data

import com.breakoverlay.app.domain.SettingRanges

data class DailyLimit(
    val minutes: Int,
    val enabled: Boolean = true,
)

data class AppPreferences(
    val monitoringEnabled: Boolean = false,
    /**
     * Selection is used by "Selected Apps Only" break monitoring and is kept independent
     * from daily-limit configuration. Saving a daily limit may additionally select an app
     * as a convenience.
     */
    val selectedPackages: Set<String> = emptySet(),
    val monitorAllApps: Boolean = true,
    val dailyLimits: Map<String, DailyLimit> = emptyMap(),
    val breakIntervalMinutes: Int = SettingRanges.BREAK_INTERVAL_DEFAULT,
    val snoozeMinutes: Int = SettingRanges.SNOOZE_DEFAULT,
    val soundEnabled: Boolean = true,
    /** Internal notification-deduplication state, keyed by local day and package. */
    val notifiedLimitKeys: Set<String> = emptySet(),
) {
    fun isMonitored(packageName: String): Boolean =
        monitorAllApps || packageName in selectedPackages

    fun limitFor(packageName: String): DailyLimit? = dailyLimits[packageName]

    fun enabledLimitFor(packageName: String): DailyLimit? =
        dailyLimits[packageName]?.takeIf { it.enabled }
}

data class PersistedBreakSchedule(
    val accumulatedActiveMs: Long = 0L,
    val snoozeExtensionMs: Long = 0L,
    val lastDueAtWallMs: Long = 0L,
    val savedAtElapsedMs: Long = 0L,
    val savedAtWallMs: Long = 0L,
    val bootCount: Int = -1,
    val wasActive: Boolean = false,
)
