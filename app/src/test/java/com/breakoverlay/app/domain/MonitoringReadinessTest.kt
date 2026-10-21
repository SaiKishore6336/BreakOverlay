package com.breakoverlay.app.domain

import com.breakoverlay.app.domain.MonitoringReadiness.CriticalCapability
import com.breakoverlay.app.domain.MonitoringReadiness.OptionalCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonitoringReadinessTest {

    @Test
    fun `fully granted monitoring is ready and fully functional`() {
        val readiness = MonitoringReadiness.evaluate(
            usageAccessGranted = true,
            overlayGranted = true,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            monitoringEnabled = true,
        )

        assertTrue(readiness.canMonitor)
        assertTrue(readiness.fullyFunctional)
        assertTrue(readiness.missingCritical.isEmpty())
        assertTrue(readiness.optionalWarnings.isEmpty())
    }

    @Test
    fun `missing critical permissions prevent monitoring`() {
        val readiness = MonitoringReadiness.evaluate(
            usageAccessGranted = false,
            overlayGranted = false,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            monitoringEnabled = true,
        )

        assertFalse(readiness.canMonitor)
        assertFalse(readiness.fullyFunctional)
        assertEquals(
            listOf(CriticalCapability.USAGE_ACCESS, CriticalCapability.OVERLAY_ACCESS),
            readiness.missingCritical,
        )
    }

    @Test
    fun `optional permission and battery warnings do not block monitoring`() {
        val readiness = MonitoringReadiness.evaluate(
            usageAccessGranted = true,
            overlayGranted = true,
            notificationsGranted = false,
            batteryOptimizationIgnored = false,
            monitoringEnabled = true,
        )

        assertTrue(readiness.canMonitor)
        assertFalse(readiness.fullyFunctional)
        assertEquals(
            listOf(OptionalCapability.NOTIFICATIONS, OptionalCapability.BATTERY_OPTIMIZATION),
            readiness.optionalWarnings,
        )
    }

    @Test
    fun `monitoring disabled is never reported as ready`() {
        val readiness = MonitoringReadiness.evaluate(
            usageAccessGranted = true,
            overlayGranted = true,
            notificationsGranted = true,
            batteryOptimizationIgnored = true,
            monitoringEnabled = false,
        )

        assertFalse(readiness.canMonitor)
        assertFalse(readiness.fullyFunctional)
    }
}
