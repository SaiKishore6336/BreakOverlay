package com.breakoverlay.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UsageSessionReconstructorTest {

    private val minute = 60_000L
    private val reconstructor = UsageSessionReconstructor()

    private fun event(
        timestampMs: Long,
        eventType: Int,
        packageName: String? = null,
    ) = UsageEventRecord(timestampMs, packageName, eventType)

    private fun screenOn(timestampMs: Long = 0L) =
        event(timestampMs, UsageEventTypes.SCREEN_INTERACTIVE)

    private fun resumed(timestampMs: Long, packageName: String) =
        event(timestampMs, UsageEventTypes.ACTIVITY_RESUMED, packageName)

    private fun paused(timestampMs: Long, packageName: String) =
        event(timestampMs, UsageEventTypes.ACTIVITY_PAUSED, packageName)

    @Test
    fun `session is measured between resume and pause`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                paused(6_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(5_000L, usage.totalMs)
        assertFalse(usage.estimated)
        assertFalse(result.estimated)
        assertTrue(result.screenEventsAvailable)
    }

    @Test
    fun `switching apps closes the previous session without overlap`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                resumed(2_000L, "app.b"),
                paused(5_000L, "app.b"),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )

        assertEquals(1_000L, result.packageUsageMs("app.a"))
        assertEquals(3_000L, result.packageUsageMs("app.b"))
        val allSessions = result.byPackage.values.flatMap { it.sessions }.sortedBy { it.startMs }
        for (index in 1 until allSessions.size) {
            assertTrue(allSessions[index - 1].endMs <= allSessions[index].startMs)
        }
    }

    @Test
    fun `session running across midnight is clipped to the requested local day`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(20 * minute, "app.a"),
                paused(30 * minute, "app.a"),
            ),
            windowStartMs = 25 * minute,
            windowEndMs = 35 * minute,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(5 * minute, usage.totalMs)
        assertEquals(25 * minute, usage.sessions.single().startMs)
        assertEquals(30 * minute, usage.sessions.single().endMs)
    }

    @Test
    fun `currently open session ends at query time and is marked estimated`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(9_000L, usage.totalMs)
        assertTrue(usage.estimated)
        assertTrue(result.estimated)
    }

    @Test
    fun `screen off closes the session and screen on reopens it as an estimate`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                event(5_000L, UsageEventTypes.SCREEN_NON_INTERACTIVE),
                screenOn(10_000L),
                paused(12_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 15_000L,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(6_000L, usage.totalMs)
        assertTrue(usage.estimated)
        assertEquals(2, usage.sessions.size)
    }

    @Test
    fun `keyguard closes the session and unlock reopens it as an estimate`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                event(5_000L, UsageEventTypes.KEYGUARD_SHOWN),
                event(10_000L, UsageEventTypes.KEYGUARD_HIDDEN),
                paused(12_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 15_000L,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(6_000L, usage.totalMs)
        assertTrue(usage.estimated)
    }

    @Test
    fun `shutdown closes usage and does not invent later time`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                event(5_000L, UsageEventTypes.DEVICE_SHUTDOWN),
            ),
            windowStartMs = 0L,
            windowEndMs = 20_000L,
        )

        assertEquals(4_000L, result.packageUsageMs("app.a"))
        assertFalse(result.byPackage.getValue("app.a").estimated)
    }

    @Test
    fun `usage without screen events is marked estimated`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                resumed(1_000L, "app.a"),
                paused(5_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )

        val usage = result.byPackage.getValue("app.a")
        assertEquals(4_000L, usage.totalMs)
        assertTrue(usage.estimated)
        assertFalse(result.screenEventsAvailable)
        assertTrue(result.estimated)
    }

    @Test
    fun `pause is ordered before a resume at the same timestamp`() {
        val result = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(1_000L, "app.a"),
                resumed(5_000L, "app.b"),
                paused(5_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 8_000L,
        )

        assertEquals(4_000L, result.packageUsageMs("app.a"))
        assertEquals(3_000L, result.packageUsageMs("app.b"))
        assertTrue(result.byPackage.getValue("app.b").estimated)
    }

    @Test
    fun `sessions outside the window are discarded and reversed windows are rejected`() {
        val outside = reconstructor.reconstruct(
            events = listOf(
                screenOn(),
                resumed(20_000L, "app.a"),
                paused(25_000L, "app.a"),
            ),
            windowStartMs = 0L,
            windowEndMs = 10_000L,
        )
        assertTrue(outside.byPackage.isEmpty())

        try {
            reconstructor.reconstruct(
                events = emptyList(),
                windowStartMs = 10L,
                windowEndMs = 5L,
            )
            fail("Expected an invalid window to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: callers must not supply a reversed window.
        }
    }
}
