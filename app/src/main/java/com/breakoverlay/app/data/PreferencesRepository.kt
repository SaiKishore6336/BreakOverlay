package com.breakoverlay.app.data

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.breakoverlay.app.domain.SettingRanges
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.Locale

private val Context.breakOverlayDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "break_overlay_preferences",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Single source of truth for user settings, selected packages, daily limits and the
 * persisted break-schedule snapshot. Stable package names are used as identifiers.
 */
class PreferencesRepository(context: Context) {
    private val dataStore = context.applicationContext.breakOverlayDataStore

    val preferences: Flow<AppPreferences> = dataStore.data
        .catch { error ->
            Log.e(TAG, "Could not read preferences; falling back to defaults", error)
            emit(emptyPreferences())
        }
        .map(::decodePreferences)
        .distinctUntilChanged()

    val breakSchedule: Flow<PersistedBreakSchedule> = dataStore.data
        .catch { error ->
            Log.e(TAG, "Could not read break schedule", error)
            emit(emptyPreferences())
        }
        .map(::decodeSchedule)
        .distinctUntilChanged()

    suspend fun readPreferences(): AppPreferences = decodePreferences(dataStore.data.first())
    suspend fun readBreakSchedule(): PersistedBreakSchedule =
        decodeSchedule(dataStore.data.first())

    suspend fun setMonitoringEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.MONITORING_ENABLED] = enabled }
    }

    suspend fun setMonitorAllApps(enabled: Boolean) {
        dataStore.edit { it[Keys.MONITOR_ALL_APPS] = enabled }
    }

    suspend fun setBreakIntervalMinutes(minutes: Int) {
        val valid = SettingRanges.coerceBreakInterval(minutes)
        dataStore.edit { it[Keys.BREAK_INTERVAL_MINUTES] = valid }
    }

    suspend fun setSnoozeMinutes(minutes: Int) {
        val valid = SettingRanges.coerceSnooze(minutes)
        dataStore.edit { it[Keys.SNOOZE_MINUTES] = valid }
    }

    suspend fun setSoundEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.SOUND_ENABLED] = enabled }
    }

    suspend fun setSelected(packageName: String, selected: Boolean) {
        dataStore.edit { preferences ->
            val current = preferences[Keys.SELECTED_PACKAGES]?.toMutableSet()
                ?: mutableSetOf()
            if (selected) current += packageName else current -= packageName
            preferences[Keys.SELECTED_PACKAGES] = current
        }
    }

    suspend fun replaceSelectedPackages(packages: Set<String>) {
        dataStore.edit { it[Keys.SELECTED_PACKAGES] = packages }
    }

    suspend fun saveDailyLimit(packageName: String, minutes: Int, enabled: Boolean) {
        require(SettingRanges.isValidDailyLimit(minutes)) { "Invalid daily limit: $minutes" }
        dataStore.edit { preferences ->
            val current = decodeLimits(preferences).toMutableMap()
            current[packageName] = DailyLimit(minutes, enabled)
            preferences[Keys.DAILY_LIMITS] = encodeLimits(current)
            // Saving an individual limit is an explicit signal that the user cares about
            // this app; selecting it is a UI convenience and is persisted separately.
            val selected = preferences[Keys.SELECTED_PACKAGES]?.toMutableSet()
                ?: mutableSetOf()
            selected += packageName
            preferences[Keys.SELECTED_PACKAGES] = selected
        }
    }

    suspend fun removeDailyLimit(packageName: String) {
        dataStore.edit { preferences ->
            val current = decodeLimits(preferences).toMutableMap()
            current.remove(packageName)
            preferences[Keys.DAILY_LIMITS] = encodeLimits(current)
        }
    }

    suspend fun saveBreakSettings(
        intervalMinutes: Int,
        snoozeMinutes: Int,
        monitorAllApps: Boolean,
        soundEnabled: Boolean,
    ) {
        require(SettingRanges.isValidBreakInterval(intervalMinutes))
        require(SettingRanges.isValidSnooze(snoozeMinutes))
        dataStore.edit { preferences ->
            preferences[Keys.BREAK_INTERVAL_MINUTES] = intervalMinutes
            preferences[Keys.SNOOZE_MINUTES] = snoozeMinutes
            preferences[Keys.MONITOR_ALL_APPS] = monitorAllApps
            preferences[Keys.SOUND_ENABLED] = soundEnabled
        }
    }

    /** Persist only the current local day's dedupe keys, pruning older dates. */
    suspend fun markLimitAlerted(localDayKey: String, packageName: String) {
        val key = "$localDayKey|$packageName"
        dataStore.edit { preferences ->
            val current = preferences[Keys.NOTIFIED_LIMITS].orEmpty()
            val kept = current.filterTo(mutableSetOf()) {
                it.substringBefore('|', missingDelimiterValue = "") == localDayKey
            }
            kept += key
            preferences[Keys.NOTIFIED_LIMITS] = kept
        }
    }

    suspend fun saveBreakSchedule(schedule: PersistedBreakSchedule) {
        dataStore.edit { preferences ->
            preferences[Keys.SCHEDULE_ACCUMULATED_MS] = schedule.accumulatedActiveMs
            preferences[Keys.SCHEDULE_SNOOZE_EXTENSION_MS] = schedule.snoozeExtensionMs
            preferences[Keys.SCHEDULE_LAST_DUE_WALL_MS] = schedule.lastDueAtWallMs
            preferences[Keys.SCHEDULE_SAVED_ELAPSED_MS] = schedule.savedAtElapsedMs
            preferences[Keys.SCHEDULE_SAVED_WALL_MS] = schedule.savedAtWallMs
            preferences[Keys.SCHEDULE_BOOT_COUNT] = schedule.bootCount
            preferences[Keys.SCHEDULE_WAS_ACTIVE] = schedule.wasActive
        }
    }

    suspend fun clearBreakSchedule() {
        dataStore.edit { preferences ->
            preferences.remove(Keys.SCHEDULE_ACCUMULATED_MS)
            preferences.remove(Keys.SCHEDULE_SNOOZE_EXTENSION_MS)
            preferences.remove(Keys.SCHEDULE_LAST_DUE_WALL_MS)
            preferences.remove(Keys.SCHEDULE_SAVED_ELAPSED_MS)
            preferences.remove(Keys.SCHEDULE_SAVED_WALL_MS)
            preferences.remove(Keys.SCHEDULE_BOOT_COUNT)
            preferences.remove(Keys.SCHEDULE_WAS_ACTIVE)
        }
    }

    private fun decodePreferences(preferences: Preferences): AppPreferences {
        return AppPreferences(
            monitoringEnabled = preferences[Keys.MONITORING_ENABLED] ?: false,
            selectedPackages = preferences[Keys.SELECTED_PACKAGES].orEmpty(),
            monitorAllApps = preferences[Keys.MONITOR_ALL_APPS] ?: true,
            dailyLimits = decodeLimits(preferences),
            breakIntervalMinutes = SettingRanges.coerceBreakInterval(
                preferences[Keys.BREAK_INTERVAL_MINUTES]
                    ?: SettingRanges.BREAK_INTERVAL_DEFAULT,
            ),
            snoozeMinutes = SettingRanges.coerceSnooze(
                preferences[Keys.SNOOZE_MINUTES] ?: SettingRanges.SNOOZE_DEFAULT,
            ),
            soundEnabled = preferences[Keys.SOUND_ENABLED] ?: true,
            notifiedLimitKeys = preferences[Keys.NOTIFIED_LIMITS].orEmpty(),
        )
    }

    private fun decodeLimits(preferences: Preferences): Map<String, DailyLimit> {
        val result = linkedMapOf<String, DailyLimit>()
        preferences[Keys.DAILY_LIMITS].orEmpty().forEach { encoded ->
            val parts = encoded.split('|')
            if (parts.size != 3) return@forEach
            val packageName = parts[0]
            val minutes = parts[1].toIntOrNull() ?: return@forEach
            if (packageName.isBlank() || !SettingRanges.isValidDailyLimit(minutes)) {
                return@forEach
            }
            result[packageName] = DailyLimit(minutes, enabled = parts[2] == "1")
        }
        return result
    }

    private fun encodeLimits(limits: Map<String, DailyLimit>): Set<String> =
        limits.mapTo(linkedSetOf()) { (packageName, limit) ->
            String.format(
                Locale.US,
                "%s|%d|%d",
                packageName,
                limit.minutes,
                if (limit.enabled) 1 else 0,
            )
        }

    private fun decodeSchedule(preferences: Preferences): PersistedBreakSchedule =
        PersistedBreakSchedule(
            accumulatedActiveMs = preferences[Keys.SCHEDULE_ACCUMULATED_MS]
                ?.coerceAtLeast(0L) ?: 0L,
            snoozeExtensionMs = preferences[Keys.SCHEDULE_SNOOZE_EXTENSION_MS]
                ?.coerceAtLeast(0L) ?: 0L,
            lastDueAtWallMs = preferences[Keys.SCHEDULE_LAST_DUE_WALL_MS]
                ?.coerceAtLeast(0L) ?: 0L,
            savedAtElapsedMs = preferences[Keys.SCHEDULE_SAVED_ELAPSED_MS]
                ?.coerceAtLeast(0L) ?: 0L,
            savedAtWallMs = preferences[Keys.SCHEDULE_SAVED_WALL_MS]
                ?.coerceAtLeast(0L) ?: 0L,
            bootCount = preferences[Keys.SCHEDULE_BOOT_COUNT] ?: -1,
            wasActive = preferences[Keys.SCHEDULE_WAS_ACTIVE] ?: false,
        )

    private object Keys {
        val MONITORING_ENABLED = booleanPreferencesKey("monitoring_enabled")
        val SELECTED_PACKAGES = stringSetPreferencesKey("selected_packages")
        val MONITOR_ALL_APPS = booleanPreferencesKey("monitor_all_apps")
        val DAILY_LIMITS = stringSetPreferencesKey("daily_limits")
        val BREAK_INTERVAL_MINUTES = intPreferencesKey("break_interval_minutes")
        val SNOOZE_MINUTES = intPreferencesKey("snooze_minutes")
        val SOUND_ENABLED = booleanPreferencesKey("sound_enabled")
        val NOTIFIED_LIMITS = stringSetPreferencesKey("notified_limit_keys")

        val SCHEDULE_ACCUMULATED_MS = longPreferencesKey("schedule_accumulated_ms")
        val SCHEDULE_SNOOZE_EXTENSION_MS = longPreferencesKey("schedule_snooze_extension_ms")
        val SCHEDULE_LAST_DUE_WALL_MS = longPreferencesKey("schedule_last_due_wall_ms")
        val SCHEDULE_SAVED_ELAPSED_MS = longPreferencesKey("schedule_saved_elapsed_ms")
        val SCHEDULE_SAVED_WALL_MS = longPreferencesKey("schedule_saved_wall_ms")
        val SCHEDULE_BOOT_COUNT = intPreferencesKey("schedule_boot_count")
        val SCHEDULE_WAS_ACTIVE = booleanPreferencesKey("schedule_was_active")
    }

    companion object {
        private const val TAG = "PreferencesRepository"
    }
}
