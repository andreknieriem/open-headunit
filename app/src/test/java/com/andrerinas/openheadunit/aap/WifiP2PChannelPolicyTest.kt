package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.direct.WifiP2pChannelPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequencies are the ones measured on a unit where this failed: 2437 and 2462 on the runs where
 * the phone joined, 2467 on the two where it never saw the network.
 */
class WifiP2PChannelPolicyTest {

    @Test
    fun `the channels the phone joined on are not flagged`() {
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(2412))
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(2437))
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(2462))
    }

    @Test
    fun `channels 12 and 13 are flagged`() {
        assertTrue(WifiP2pChannelPolicy.isClientUnfriendly(2467))
        assertTrue(WifiP2pChannelPolicy.isClientUnfriendly(2472))
    }

    @Test
    fun `an unknown frequency is never flagged`() {
        // 0 is what the pre-API-29 reflection returns when no field name matches. Blaming the
        // channel on the strength of a measurement we do not have sends the user after the wrong
        // thing.
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(0))
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(-1))
    }

    @Test
    fun `5GHz is never flagged`() {
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(5180))
        assertFalse(WifiP2pChannelPolicy.isClientUnfriendly(5745))
        assertFalse(WifiP2pChannelPolicy.is24GHz(5180))
    }

    @Test
    fun `channel 14 is 2_4GHz and is flagged`() {
        // Japan only and 802.11b only, so a phone that cannot use channel 12 certainly cannot use
        // this one. No Android group owner is expected to pick it; it is handled because it sits
        // off the 5 MHz grid and would otherwise make channelFor() return nonsense.
        assertTrue(WifiP2pChannelPolicy.is24GHz(2484))
        assertEquals(14, WifiP2pChannelPolicy.channelFor(2484))
        assertTrue(WifiP2pChannelPolicy.isClientUnfriendly(2484))
    }

    @Test
    fun `channelFor maps the 5MHz grid`() {
        assertEquals(1, WifiP2pChannelPolicy.channelFor(2412))
        assertEquals(6, WifiP2pChannelPolicy.channelFor(2437))
        assertEquals(11, WifiP2pChannelPolicy.channelFor(2462))
        assertEquals(12, WifiP2pChannelPolicy.channelFor(2467))
        assertEquals(13, WifiP2pChannelPolicy.channelFor(2472))
    }

    @Test
    fun `channelFor returns 0 for anything off the grid`() {
        assertEquals(0, WifiP2pChannelPolicy.channelFor(0))
        assertEquals(0, WifiP2pChannelPolicy.channelFor(2400))
        assertEquals(0, WifiP2pChannelPolicy.channelFor(2415))
        assertEquals(0, WifiP2pChannelPolicy.channelFor(5180))
    }

    @Test
    fun `describe names the channel or says it is unknown`() {
        assertEquals("channel 12", WifiP2pChannelPolicy.describe(2467))
        assertEquals("channel 6", WifiP2pChannelPolicy.describe(2437))
        assertEquals("unknown channel", WifiP2pChannelPolicy.describe(0))
    }
}
