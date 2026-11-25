package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WppSessionChannelPolicyTest {

    @Test
    fun `a measured group frequency wins over the station's`() {
        val channel = WppSessionChannelPolicy.resolve(
            groupFrequencyMhz = 5180,
            stationFrequencyMhz = 2437,
            associated = true,
        )
        assertEquals(WppSessionChannel.Measured(5180), channel)
    }

    @Test
    fun `an unreadable group frequency falls back to the station the group shares`() {
        val channel = WppSessionChannelPolicy.resolve(
            groupFrequencyMhz = 0,
            stationFrequencyMhz = 2447,
            associated = true,
        )
        assertEquals(WppSessionChannel.Measured(2447), channel)
    }

    @Test
    fun `a station frequency is not used when the unit is not associated`() {
        val channel = WppSessionChannelPolicy.resolve(
            groupFrequencyMhz = 0,
            stationFrequencyMhz = 2447,
            associated = false,
        )
        assertTrue(channel is WppSessionChannel.Unavailable)
    }

    @Test
    fun `nothing readable means no channel rather than a guess`() {
        val channel = WppSessionChannelPolicy.resolve(
            groupFrequencyMhz = 0,
            stationFrequencyMhz = 0,
            associated = false,
        )
        assertTrue(channel is WppSessionChannel.Unavailable)
        assertTrue((channel as WppSessionChannel.Unavailable).reason.contains("cannot name"))
    }

    @Test
    fun `a value that describes no real channel is never resolved`() {
        for (bogus in listOf(-1, 2399, 2501, 5000, 6000)) {
            val group = WppSessionChannelPolicy.resolve(bogus, 0, false)
            assertTrue("group $bogus", group is WppSessionChannel.Unavailable)
            val station = WppSessionChannelPolicy.resolve(0, bogus, true)
            assertTrue("station $bogus", station is WppSessionChannel.Unavailable)
        }
    }

    @Test
    fun `channels are named only for a real measurement`() {
        assertEquals(listOf(2447), WppSessionChannelPolicy.channelsFor(WppSessionChannel.Measured(2447)))
        assertEquals(
            emptyList<Int>(),
            WppSessionChannelPolicy.channelsFor(WppSessionChannel.Unavailable("nothing readable"))
        )
    }

    @Test
    fun `an unavailable channel names no frequency`() {
        assertEquals(0, WppSessionChannelPolicy.mhzOf(WppSessionChannel.Unavailable("nothing")))
        assertEquals(0, WppSessionChannelPolicy.mhzOf(null))
        assertEquals(2447, WppSessionChannelPolicy.mhzOf(WppSessionChannel.Measured(2447)))
    }

    @Test
    fun `a refusal suppresses only the channel that was refused`() {
        val refusedHere = WppSessionChannel.Measured(2447)
        assertTrue(WppSessionChannelPolicy.withheldAfterRefusal(refusedHere, 2447))
        assertFalse(WppSessionChannelPolicy.withheldAfterRefusal(refusedHere, 2437))
        assertFalse(WppSessionChannelPolicy.withheldAfterRefusal(refusedHere, 0))
        assertFalse(
            WppSessionChannelPolicy.withheldAfterRefusal(
                WppSessionChannel.Unavailable("nothing"), 2447
            )
        )
    }

    @Test
    fun `the refusal reason names the channel the phone rejected`() {
        val reason = WppSessionChannelPolicy.refusalReason(WppSessionChannel.Measured(2447))
        assertTrue(reason.contains("NO_SUPPORTED_WIFI_CHANNELS"))
        assertTrue(reason.contains("2447"))
    }
}
