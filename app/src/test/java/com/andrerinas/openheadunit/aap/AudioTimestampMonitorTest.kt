package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class AudioTimestampMonitorTest {
    private val frameUs = 42_667L

    @Test fun `different boot times do not look like audio latency`() {
        val monitor = AudioTimestampMonitor(frameUs)
        assertNull(monitor.onPacket(9_000_000_000, 1_000_000, frameUs))
        val report = monitor.onPacket(9_000_000_000 + frameUs, 1_000_000 + frameUs, frameUs)!!
        assertEquals(1, report.comparablePairs)
        assertEquals(0L, report.maxDeliveryIncreaseUs)
        assertEquals(0L, report.maxSourceExcessUs)
        assertFalse(report.hasGap)
    }

    @Test fun `delayed delivery is distinct from a gap already present in source timestamps`() {
        val monitor = AudioTimestampMonitor(100_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        val report = monitor.onPacket(1_000_000 + frameUs, 5_120_000, frameUs)!!
        assertEquals(77_333L, report.maxDeliveryIncreaseUs)
        assertEquals(0L, report.maxSourceExcessUs)
        assertTrue(report.hasGap)
    }

    @Test fun `source capture gap is not reported as additional delivery delay`() {
        val monitor = AudioTimestampMonitor(100_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        val report = monitor.onPacket(1_120_000, 5_120_000, frameUs)!!
        assertEquals(77_333L, report.maxSourceExcessUs)
        assertEquals(0L, report.maxDeliveryIncreaseUs)
        assertTrue(report.hasGap)
    }

    @Test fun `both upstream and delivery gaps can coexist`() {
        val monitor = AudioTimestampMonitor(100_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        val report = monitor.onPacket(1_120_000, 5_170_000, frameUs)!!
        assertEquals(77_333L, report.maxSourceExcessUs)
        assertEquals(50_000L, report.maxDeliveryIncreaseUs)
    }

    @Test fun `TCP catch-up burst has no negative delay or extra source loss`() {
        val monitor = AudioTimestampMonitor(100_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        monitor.onPacket(1_042_667, 5_090_000, frameUs)
        monitor.onPacket(1_085_334, 5_090_000, frameUs)
        val report = monitor.onPacket(1_128_001, 5_128_001, frameUs)!!
        assertEquals(3, report.comparablePairs)
        assertEquals(47_333L, report.maxDeliveryIncreaseUs)
        assertEquals(0L, report.maxSourceExcessUs)
    }

    @Test fun `unknown repeated and backwards timestamps do not invent a source measurement`() {
        for (nextSource in listOf(0L, -1L, 1_000_000L, 900_000L, Long.MAX_VALUE)) {
            val monitor = AudioTimestampMonitor(100_000)
            monitor.onPacket(1_000_000, 5_000_000, frameUs)
            val report = monitor.onPacket(nextSource, 5_120_000, frameUs)!!
            assertEquals(0, report.comparablePairs)
            assertEquals(0L, report.maxDeliveryIncreaseUs)
            assertEquals(0L, report.maxSourceExcessUs)
            assertTrue(report.hasGap) // Arrival gap remains real, even with unusable PTS.
        }
    }

    @Test fun `explicit resume reset and long unannounced pause rebase clocks`() {
        val monitor = AudioTimestampMonitor(frameUs)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        monitor.reset()
        assertNull(monitor.onPacket(100, 50_000_000, frameUs))
        val report = monitor.onPacket(100 + frameUs, 50_000_000 + frameUs, frameUs)!!
        assertFalse(report.hasGap)
        val pause = monitor.onPacket(60_000_000, 90_000_000, frameUs)!!
        assertEquals(1, pause.discontinuities)
        assertEquals(0, pause.comparablePairs)
        assertFalse(pause.hasGap)
    }

    @Test fun `expected duration follows the previous variable size PCM packet`() {
        val monitor = AudioTimestampMonitor(64_000)
        monitor.onPacket(1_000_000, 5_000_000, 64_000)
        val report = monitor.onPacket(1_064_000, 5_064_000, 20_000)!!
        assertEquals(0L, report.maxSourceExcessUs)
        assertEquals(0L, report.maxArrivalExcessUs)
    }

    @Test fun `reporting clears old spikes but preserves continuity across windows`() {
        val monitor = AudioTimestampMonitor(frameUs)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        assertTrue(monitor.onPacket(1_000_000 + frameUs, 5_100_000, frameUs)!!.hasGap)
        val report = monitor.onPacket(1_000_000 + 2 * frameUs, 5_100_000 + frameUs, frameUs)!!
        assertEquals(1, report.packets)
        assertEquals(1, report.comparablePairs)
        assertFalse(report.hasGap)
    }

    @Test fun `local clock rollback and invalid duration cannot poison the next session`() {
        val monitor = AudioTimestampMonitor(frameUs)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        assertNull(monitor.onPacket(2_000_000, 4_000_000, frameUs))
        assertNull(monitor.onPacket(2_000_001, 4_000_001, 0))
        val report = monitor.onPacket(2_000_000 + frameUs, 4_000_000 + frameUs, frameUs)!!
        assertEquals(2, report.packets)
        assertEquals(1, report.comparablePairs)
        assertFalse(report.hasGap)
    }
}
