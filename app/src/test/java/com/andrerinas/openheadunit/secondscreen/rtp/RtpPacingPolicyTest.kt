package com.andrerinas.openheadunit.secondscreen.rtp

import org.junit.Assert.assertEquals
import org.junit.Test

class RtpPacingPolicyTest {

    @Test
    fun `eight packets or fewer are never paced`() {
        assertEquals(0, RtpPacingPolicy.packetsPerPause(8, 100))
        assertEquals(0, RtpPacingPolicy.packetsPerPause(1, 100))
    }

    @Test
    fun `a unit is spread over half the gap and at most 20 ms`() {
        assertEquals(4, RtpPacingPolicy.packetsPerPause(73, 100))
        assertEquals(8, RtpPacingPolicy.packetsPerPause(73, 20))
        assertEquals(73, RtpPacingPolicy.packetsPerPause(73, 2))
    }

    @Test
    fun `the first unit is spread over the full 20 ms and two units at once are not paced`() {
        assertEquals(4, RtpPacingPolicy.packetsPerPause(73, null))
        assertEquals(0, RtpPacingPolicy.packetsPerPause(73, 0))
        assertEquals(0, RtpPacingPolicy.packetsPerPause(73, 1))
    }

    @Test
    fun `the estimate counts full payloads plus one`() {
        assertEquals(1, RtpPacingPolicy.estimatePackets(100))
        assertEquals(73, RtpPacingPolicy.estimatePackets(100_005))
    }
}
