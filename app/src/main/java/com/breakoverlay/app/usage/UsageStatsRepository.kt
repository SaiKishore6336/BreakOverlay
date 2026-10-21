package com.breakoverlay.app.usage

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import com.breakoverlay.app.domain.LocalDay
import com.breakoverlay.app.util.PermissionUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.TimeZone

/**
 * Reads Android's UsageStatsManager event stream. The app never fabricates usage: if
 * access is missing or the query fails, the result is marked unavailable.
 *
 * Daily usage is reconstructed for [local midnight, now] from a 24-hour lookback so a
 * session that began before midnight is clipped rather than discarded.
 */
class UsageStatsRepository(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val usageStatsManager: UsageStatsManager? =
        context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager

    private val foregroundLock = Any()
    private var foregroundQueryEndMs = 0L
    private var foregroundPackage: String? = null
    private var lastForegroundPackage: String? = null

    suspend fun loadTodayUsage(): DailyUsageSnapshot = withContext(Dispatchers.IO) {
        val now = clock()
        val dayStart = LocalDay.startOfDay(now)
        val queryStart = LocalDay.startOfDayWithLookback(now, LOOKBACK_MS)
        try {
            if (!PermissionUtils.hasUsageAccess(context)) {
                return@withContext unavailable(dayStart, now, "Usage access has not been granted.")
            }
            val manager = usageStatsManager
                ?: return@withContext unavailable(dayStart, now, "UsageStatsManager is unavailable.")

            val records = readEvents(manager, queryStart, now)
            val reconstruction = UsageSessionReconstructor().reconstruct(
                events = records,
                windowStartMs = dayStart,
                windowEndMs = now,
            )
            DailyUsageSnapshot(dayStart, now, reconstruction)
        } catch (security: SecurityException) {
            Log.w(TAG, "Usage access was revoked while reading usage statistics", security)
            unavailable(dayStart, now, "Usage access is unavailable.")
        } catch (error: RuntimeException) {
            Log.e(TAG, "Could not read usage statistics", error)
            unavailable(dayStart, now, "Usage statistics could not be read.")
        }
    }

    /**
     * Incremental foreground tracker for the monitoring service. UsageEvents is the closest
     * supported real-time signal available without accessibility privileges. A 1-second
     * overlap handles boundary events; duplicate transitions are harmless.
     */
    suspend fun currentForegroundPackage(now: Long = clock()): String? =
        withContext(Dispatchers.IO) {
            synchronized(foregroundLock) {
                if (!PermissionUtils.hasUsageAccess(context)) return@synchronized null
                val manager = usageStatsManager ?: return@synchronized null

                val queryStart: Long
                if (foregroundQueryEndMs == 0L) {
                    queryStart = (now - FIRST_QUERY_LOOKBACK_MS).coerceAtLeast(0L)
                } else if (now < foregroundQueryEndMs) {
                    // Wall clock moved backwards. Reset the incremental state rather than
                    // querying an invalid, reversed time range.
                    queryStart = (now - FIRST_QUERY_LOOKBACK_MS).coerceAtLeast(0L)
                    foregroundPackage = null
                    lastForegroundPackage = null
                } else {
                    queryStart = (foregroundQueryEndMs - QUERY_OVERLAP_MS)
                        .coerceAtLeast(0L)
                }

                try {
                    val records = readEvents(manager, queryStart, now)
                    for (event in records) {
                        when (event.eventType) {
                            UsageEventTypes.ACTIVITY_RESUMED,
                            UsageEventTypes.MOVE_TO_FOREGROUND,
                            -> {
                                event.packageName?.takeIf { it.isNotBlank() }?.let { pkg ->
                                    lastForegroundPackage = pkg
                                    foregroundPackage = pkg
                                }
                            }

                            UsageEventTypes.ACTIVITY_PAUSED,
                            UsageEventTypes.MOVE_TO_BACKGROUND,
                            UsageEventTypes.ACTIVITY_STOPPED,
                            -> {
                                if (event.packageName == foregroundPackage) {
                                    foregroundPackage = null
                                }
                            }

                            UsageEventTypes.SCREEN_NON_INTERACTIVE,
                            UsageEventTypes.KEYGUARD_SHOWN,
                            UsageEventTypes.DEVICE_SHUTDOWN,
                            -> foregroundPackage = null

                            UsageEventTypes.SCREEN_INTERACTIVE,
                            UsageEventTypes.KEYGUARD_HIDDEN,
                            -> {
                                val previous = lastForegroundPackage
                                if (foregroundPackage == null && previous != null) {
                                    foregroundPackage = previous
                                }
                            }

                            UsageEventTypes.DEVICE_STARTUP -> {
                                foregroundPackage = null
                                lastForegroundPackage = null
                            }
                        }
                    }
                    foregroundQueryEndMs = now
                } catch (security: SecurityException) {
                    Log.w(TAG, "Usage access was revoked while sampling the foreground app", security)
                    foregroundPackage = null
                } catch (error: RuntimeException) {
                    Log.e(TAG, "Could not sample foreground usage event", error)
                }
                foregroundPackage
            }
        }

    private fun readEvents(
        manager: UsageStatsManager,
        startMs: Long,
        endMs: Long,
    ): List<UsageEventRecord> {
        val usageEvents: UsageEvents = manager.queryEvents(startMs, endMs) ?: return emptyList()
        val records = ArrayList<UsageEventRecord>()
        val event = UsageEvents.Event()
        while (usageEvents.hasNextEvent()) {
            usageEvents.getNextEvent(event)
            records += UsageEventRecord(
                timestampMs = event.timeStamp,
                packageName = event.packageName,
                eventType = event.eventType,
                className = event.className,
            )
        }
        records.sortBy { it.timestampMs }
        return records
    }

    private fun unavailable(dayStart: Long, now: Long, message: String): DailyUsageSnapshot {
        return DailyUsageSnapshot(
            dayStartMs = dayStart,
            nowMs = now,
            reconstruction = UsageSessionReconstructor().reconstruct(
                events = emptyList(),
                windowStartMs = dayStart,
                windowEndMs = now,
            ),
            error = message,
        )
    }

    companion object {
        private const val TAG = "UsageStatsRepository"
        private const val LOOKBACK_MS = 24L * 60L * 60L * 1000L
        private const val FIRST_QUERY_LOOKBACK_MS = 12L * 60L * 60L * 1000L
        private const val QUERY_OVERLAP_MS = 1_000L
    }
}
