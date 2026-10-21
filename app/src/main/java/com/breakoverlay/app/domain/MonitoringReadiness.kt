package com.breakoverlay.app.domain

/**
 * Platform-independent health calculation. The service and dashboard both use this so
 * "running" is never mistaken for "fully functional".
 */
data class MonitoringReadiness(
    val canMonitor: Boolean,
    val fullyFunctional: Boolean,
    val missingCritical: List<CriticalCapability>,
    val optionalWarnings: List<OptionalCapability>,
) {
    enum class CriticalCapability { USAGE_ACCESS, OVERLAY_ACCESS }
    enum class OptionalCapability { NOTIFICATIONS, BATTERY_OPTIMIZATION }

    companion object {
        fun evaluate(
            usageAccessGranted: Boolean,
            overlayGranted: Boolean,
            notificationsGranted: Boolean,
            batteryOptimizationIgnored: Boolean,
            monitoringEnabled: Boolean,
        ): MonitoringReadiness {
            val critical = buildList {
                if (!usageAccessGranted) add(CriticalCapability.USAGE_ACCESS)
                if (!overlayGranted) add(CriticalCapability.OVERLAY_ACCESS)
            }
            val optional = buildList {
                if (!notificationsGranted) add(OptionalCapability.NOTIFICATIONS)
                if (!batteryOptimizationIgnored) add(OptionalCapability.BATTERY_OPTIMIZATION)
            }
            return MonitoringReadiness(
                canMonitor = monitoringEnabled && critical.isEmpty(),
                fullyFunctional = monitoringEnabled && critical.isEmpty() && optional.isEmpty(),
                missingCritical = critical,
                optionalWarnings = optional,
            )
        }
    }
}
