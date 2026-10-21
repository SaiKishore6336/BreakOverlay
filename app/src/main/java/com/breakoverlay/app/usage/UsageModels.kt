package com.breakoverlay.app.usage

/**
 * Android-independent representation of android.app.usage.UsageEvents.Event.
 * The numeric values match the public constants on every supported API level.
 */
object UsageEventTypes {
    const val ACTIVITY_RESUMED = 1
    const val MOVE_TO_FOREGROUND = 1
    const val ACTIVITY_PAUSED = 2
    const val MOVE_TO_BACKGROUND = 2
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27
}

data class UsageEventRecord(
    val timestampMs: Long,
    val packageName: String?,
    val eventType: Int,
    val className: String? = null,
)

data class UsageSession(
    val packageName: String,
    val startMs: Long,
    val endMs: Long,
    val estimated: Boolean,
)

data class PackageUsage(
    val packageName: String,
    val totalMs: Long,
    val estimated: Boolean,
    val sessions: List<UsageSession>,
)

data class UsageReconstruction(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val byPackage: Map<String, PackageUsage>,
    val estimated: Boolean,
    val screenEventsAvailable: Boolean,
) {
    val totalMs: Long = byPackage.values.sumOf { it.totalMs }

    fun packageUsageMs(packageName: String): Long =
        byPackage[packageName]?.totalMs ?: 0L

    fun isEstimated(packageName: String): Boolean =
        byPackage[packageName]?.estimated ?: false
}

data class DailyUsageSnapshot(
    val dayStartMs: Long,
    val nowMs: Long,
    val reconstruction: UsageReconstruction,
    val error: String? = null,
) {
    val available: Boolean get() = error == null
}
