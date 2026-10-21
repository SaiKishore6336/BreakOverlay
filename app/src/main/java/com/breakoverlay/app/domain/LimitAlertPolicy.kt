package com.breakoverlay.app.domain

/**
 * One alert per app per local calendar day.
 *
 * Once an alert has been delivered, corrections that temporarily lower usage below the
 * limit do not re-trigger another alert on the same day. A new date naturally produces a
 * new key and therefore new eligibility. The key set is persisted by PreferencesRepository.
 */
object LimitAlertPolicy {
    fun key(localDayKey: String, packageName: String): String = "$localDayKey|$packageName"

    fun shouldAlert(
        localDayKey: String,
        packageName: String,
        usedMs: Long,
        limitMinutes: Int,
        alertedKeys: Set<String>,
    ): Boolean {
        if (!SettingRanges.isValidDailyLimit(limitMinutes)) return false
        val limitMs = limitMinutes.toLong() * 60_000L
        return usedMs >= limitMs && key(localDayKey, packageName) !in alertedKeys
    }
}
