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
            assertNull(report.arrivalSpike!!.sourceGapUs)
            assertFalse(report.arrivalSpike.comparable)
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

    @Test fun `capture start and completion timestamps tolerate changing PCM packet durations`() {
        for ((previous, current) in listOf(20_000L to 64_000L, 64_000L to 20_000L)) {
            // 13.5 stamps capture completion, 17.6 subtracts the block duration. Neither
            // convention implies a source or delivery gap when the packet size changes.
            for (sourceDelta in listOf(previous, current)) {
                val monitor = AudioTimestampMonitor(1)
                monitor.onPacket(1_000_000, 5_000_000, previous)
                val report = monitor.onPacket(1_000_000 + sourceDelta, 5_000_000 + current, current)!!
                assertEquals(1, report.durationChanges)
                assertEquals(0, report.comparablePairs)
                assertEquals(sourceDelta, report.maxSourceGapUs)
                assertEquals(0L, report.maxSourceExcessUs)
                assertEquals(0L, report.maxDeliveryIncreaseUs)
                assertEquals(0L, report.maxArrivalExcessUs)
                assertFalse(report.hasGap)
            }
        }
    }

    @Test fun `size changes still report arrival stalls and rebase the next equal size pair`() {
        val monitor = AudioTimestampMonitor(1)
        monitor.onPacket(1_000_000, 5_000_000, 20_000)
        val changed = monitor.onPacket(1_064_000, 5_164_000, 64_000)!!
        assertEquals(100_000L, changed.maxArrivalExcessUs)
        assertTrue(changed.hasGap)
        assertEquals(64_000L, changed.arrivalSpike!!.sourceGapUs)
        assertEquals(20_000L, changed.arrivalSpike.previousDurationUs)
        assertEquals(64_000L, changed.arrivalSpike.durationUs)
        assertFalse(changed.arrivalSpike.comparable)
        val steady = monitor.onPacket(1_128_000, 5_228_000, 64_000)!!
        assertEquals(1, steady.comparablePairs)
        assertEquals(0, steady.durationChanges)
        assertFalse(steady.hasGap)
        assertNull(steady.arrivalSpike)
    }

    @Test fun `millisecond capture completion timestamps tolerate ordinary rounding`() {
        val monitor = AudioTimestampMonitor(85_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        monitor.onPacket(1_043_000, 5_042_667, frameUs)
        val report = monitor.onPacket(1_085_000, 5_085_334, frameUs)!!
        assertEquals(2, report.comparablePairs)
        assertFalse(report.hasGap)
        assertTrue(report.maxDeliveryIncreaseUs < 1000)
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

    @Test fun `largest delivery spike keeps its own source pair and event time`() {
        val monitor = AudioTimestampMonitor(1_000_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        // The largest source gap belongs to the first pair, while the largest
        // arrival gap belongs to the second. Mixing maxima would misdiagnose it.
        monitor.onPacket(1_700_000, 5_200_000, frameUs)
        monitor.onPacket(1_742_667, 5_900_000, frameUs)
        val report = monitor.onPacket(1_785_334, 6_000_000, frameUs)!!
        assertEquals(700_000L, report.maxSourceGapUs)
        assertEquals(700_000L, report.maxArrivalGapUs)
        val spike = report.arrivalSpike!!
        assertEquals(5_900_000L, spike.arrivalUs)
        assertEquals(700_000L, spike.arrivalGapUs)
        assertEquals(frameUs, spike.sourceGapUs)
        assertTrue(spike.comparable)
        assertTrue(report.toString().contains("arrivalSpikeElapsedMs=5900"))
    }

    @Test fun `spike snapshots survive later windows without keeping old events`() {
        val monitor = AudioTimestampMonitor(100_000)
        monitor.onPacket(1_000_000, 5_000_000, frameUs)
        val first = monitor.onPacket(1_042_667, 5_120_000, frameUs)!!
        val second = monitor.onPacket(1_162_667, 5_240_000, frameUs)!!
        assertEquals(5_120_000L, first.arrivalSpike!!.arrivalUs)
        assertEquals(frameUs, first.arrivalSpike.sourceGapUs)
        assertEquals(5_240_000L, second.arrivalSpike!!.arrivalUs)
        assertEquals(120_000L, second.arrivalSpike.sourceGapUs)
        monitor.reset()
        monitor.onPacket(2_000_000, 6_000_000, frameUs)
        monitor.onPacket(2_042_667, 6_042_667, frameUs)
        monitor.onPacket(2_085_334, 6_085_334, frameUs)
        assertNull(monitor.onPacket(2_128_001, 6_128_001, frameUs)!!.arrivalSpike)
    }
}
