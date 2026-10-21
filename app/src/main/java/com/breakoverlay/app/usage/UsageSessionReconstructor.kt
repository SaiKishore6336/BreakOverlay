package com.breakoverlay.app.usage

import kotlin.math.max
import kotlin.math.min

/**
 * Reconstructs foreground usage sessions from Android UsageEvents.
 *
 * Policy:
 *  - Only one app can own the foreground session at a time.
 *  - A session opens on ACTIVITY_RESUMED / MOVE_TO_FOREGROUND and closes on
 *    ACTIVITY_PAUSED / MOVE_TO_BACKGROUND / ACTIVITY_STOPPED for the owning package.
 *  - Screen-off, keyguard and shutdown events close the session. Screen-on / unlock can
 *    reopen the last foreground app, but such inferred sessions are marked estimated.
 *  - The current open session is closed at the query end and marked estimated.
 *  - Sessions are clipped to the requested local-day window; negative or zero-length
 *    sessions are discarded and only one global session is open, preventing overlaps.
 *  - If an app was foreground before the supplied event range, nothing is invented.
 *    Callers should pass a lookback window (the repository uses 24 hours) to capture it.
 *
 * Missing pause/resume transitions can make numbers conservative (under-count) rather
 * than double count. The result exposes whether any package total contains estimates.
 */
class UsageSessionReconstructor {

    fun reconstruct(
        events: List<UsageEventRecord>,
        windowStartMs: Long,
        windowEndMs: Long,
    ): UsageReconstruction {
        require(windowEndMs >= windowStartMs) { "windowEnd must not precede windowStart" }

        var screenOn = true
        var keyguardLocked = false
        var sawScreenEvent = false

        var currentPackage: String? = null
        var currentSessionStart: Long? = null
        var currentSessionEstimated = false
        var lastForegroundPackage: String? = null

        val rawSessions = mutableListOf<UsageSession>()

        fun closeCurrent(atMs: Long, estimatedAtEnd: Boolean = false) {
            val start = currentSessionStart ?: return
            val safeEnd = max(start, atMs)
            if (safeEnd > start) {
                rawSessions += UsageSession(
                    packageName = currentPackage ?: return,
                    startMs = start,
                    endMs = safeEnd,
                    estimated = currentSessionEstimated || estimatedAtEnd,
                )
            }
            currentSessionStart = null
            currentSessionEstimated = false
        }

        fun openSession(packageName: String, atMs: Long, estimated: Boolean) {
            currentPackage = packageName
            lastForegroundPackage = packageName
            currentSessionStart = atMs
            currentSessionEstimated = estimated
        }

        fun closeAndClear(atMs: Long) {
            closeCurrent(atMs)
            currentPackage = null
        }

        val sorted = events.sortedWith(
            compareBy<UsageEventRecord> { it.timestampMs }
                .thenBy { sameTimestampPriority(it.eventType) },
        )

        for (event in sorted) {
            if (event.timestampMs < windowStartMs - INGEST_BEFORE_WINDOW_MS) continue

            when (event.eventType) {
                UsageEventTypes.ACTIVITY_RESUMED,
                UsageEventTypes.MOVE_TO_FOREGROUND,
                -> {
                    val packageName = event.packageName?.takeIf { it.isNotBlank() } ?: continue
                    lastForegroundPackage = packageName
                    if (currentPackage == packageName && currentSessionStart != null) {
                        // Duplicate foreground event while already owning the session.
                        continue
                    }
                    closeCurrent(event.timestampMs)
                    currentPackage = packageName
                    if (screenOn && !keyguardLocked) {
                        openSession(packageName, event.timestampMs, estimated = false)
                    }
                }

                UsageEventTypes.ACTIVITY_PAUSED,
                UsageEventTypes.MOVE_TO_BACKGROUND,
                UsageEventTypes.ACTIVITY_STOPPED,
                -> {
                    if (event.packageName == currentPackage) {
                        closeAndClear(event.timestampMs)
                    }
                }

                UsageEventTypes.SCREEN_NON_INTERACTIVE,
                UsageEventTypes.DEVICE_SHUTDOWN,
                -> {
                    sawScreenEvent = true
                    closeAndClear(event.timestampMs)
                    screenOn = false
                    keyguardLocked = event.eventType == UsageEventTypes.DEVICE_SHUTDOWN ||
                        keyguardLocked
                }

                UsageEventTypes.SCREEN_INTERACTIVE -> {
                    sawScreenEvent = true
                    screenOn = true
                    if (!keyguardLocked) {
                        val previous = lastForegroundPackage
                        if (currentPackage == null && previous != null) {
                            openSession(previous, event.timestampMs, estimated = true)
                        }
                    }
                }

                UsageEventTypes.KEYGUARD_SHOWN -> {
                    sawScreenEvent = true
                    keyguardLocked = true
                    closeAndClear(event.timestampMs)
                }

                UsageEventTypes.KEYGUARD_HIDDEN -> {
                    sawScreenEvent = true
                    keyguardLocked = false
                    val previous = lastForegroundPackage
                    if (screenOn && currentPackage == null && previous != null) {
                        openSession(previous, event.timestampMs, estimated = true)
                    }
                }

                UsageEventTypes.DEVICE_STARTUP -> {
                    sawScreenEvent = true
                    closeAndClear(event.timestampMs)
                    screenOn = false
                    keyguardLocked = true
                    lastForegroundPackage = null
                }
            }
        }

        closeCurrent(windowEndMs, estimatedAtEnd = true)

        val clipped = mutableListOf<UsageSession>()
        for (session in rawSessions) {
            val clippedStart = max(session.startMs, windowStartMs)
            val clippedEnd = min(session.endMs, windowEndMs)
            if (clippedEnd > clippedStart) {
                clipped += session.copy(startMs = clippedStart, endMs = clippedEnd)
            }
        }

        val byPackage = linkedMapOf<String, MutableList<UsageSession>>()
        for (session in clipped) {
            byPackage.getOrPut(session.packageName) { mutableListOf() } += session
        }

        val packageUsage = byPackage.mapValues { (packageName, sessions) ->
            PackageUsage(
                packageName = packageName,
                totalMs = sessions.sumOf { it.endMs - it.startMs },
                estimated = !sawScreenEvent || sessions.any { it.estimated },
                sessions = sessions,
            )
        }.toSortedMap()

        return UsageReconstruction(
            windowStartMs = windowStartMs,
            windowEndMs = windowEndMs,
            byPackage = packageUsage,
            estimated = !sawScreenEvent || packageUsage.values.any { it.estimated },
            screenEventsAvailable = sawScreenEvent,
        )
    }

    private fun sameTimestampPriority(eventType: Int): Int = when (eventType) {
        UsageEventTypes.ACTIVITY_PAUSED,
        UsageEventTypes.MOVE_TO_BACKGROUND,
        UsageEventTypes.ACTIVITY_STOPPED,
        UsageEventTypes.SCREEN_NON_INTERACTIVE,
        UsageEventTypes.KEYGUARD_SHOWN,
        UsageEventTypes.DEVICE_SHUTDOWN,
        -> 0

        UsageEventTypes.SCREEN_INTERACTIVE,
        UsageEventTypes.KEYGUARD_HIDDEN,
        -> 1

        UsageEventTypes.ACTIVITY_RESUMED,
        UsageEventTypes.MOVE_TO_FOREGROUND,
        -> 2

        else -> 3
    }

    private companion object {
        const val INGEST_BEFORE_WINDOW_MS = 26L * 60L * 60L * 1000L
    }
}
