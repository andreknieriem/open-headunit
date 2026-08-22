package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.NativeGroupBandPolicy.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frequencies are real ones: 2437 and 2462 are the 2.4 GHz channels this rig's groups have come
 * up on, 5180 and 5745 the 5 GHz ones.
 */
class NativeGroupBandPolicyTest {

    @Test
    fun `the default is unchanged - 5GHz is what Native AA asks for`() {
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(P2pBandPreference.AUTO))
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(P2pBandPreference.FORCE_5GHZ))
    }

    @Test
    fun `only the 2_4GHz position asks for 2_4GHz`() {
        assertEquals(Band.GHZ_2_4, NativeGroupBandPolicy.bandFor(P2pBandPreference.FORCE_2_4GHZ))
    }

    @Test
    fun `the stored values are the ones the settings screen writes`() {
        // A settings backup carries the number, not the name, so these three are a contract.
        assertEquals(P2pBandPreference.AUTO, P2pBandPreference.fromSetting(0))
        assertEquals(P2pBandPreference.FORCE_5GHZ, P2pBandPreference.fromSetting(1))
        assertEquals(P2pBandPreference.FORCE_2_4GHZ, P2pBandPreference.fromSetting(2))
    }

    @Test
    fun `an unknown stored value asks for 5GHz and keeps its fallback`() {
        for (value in listOf(-1, 3, 99)) {
            assertEquals("$value", P2pBandPreference.AUTO, P2pBandPreference.fromSetting(value))
        }
    }

    @Test
    fun `only 5GHz only refuses the platform's own choice`() {
        // The whole difference between Auto and 5 GHz only on a modern device. A group on 2.4 GHz
        // can connect, look healthy and show nothing, and that position says not to be given one.
        assertTrue(NativeGroupBandPolicy.fallsBackToPlatformChoice(P2pBandPreference.AUTO))
        assertFalse(NativeGroupBandPolicy.fallsBackToPlatformChoice(P2pBandPreference.FORCE_5GHZ))
        assertTrue(NativeGroupBandPolicy.fallsBackToPlatformChoice(P2pBandPreference.FORCE_2_4GHZ))
    }

    @Test
    fun `choosing 2_4GHz disarms the mismatch retry, or it would remake every group it made`() {
        // The coupling this object exists to hold. It falls out of the request rather than being a
        // second flag: bandFor() answers GHZ_2_4, and shouldRetryFor5Ghz only fires on GHZ_5.
        val requested = NativeGroupBandPolicy.bandFor(P2pBandPreference.FORCE_2_4GHZ)
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(requested, 2437, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `a preference describes itself, so a pasted log says why one band was tried`() {
        for (preference in P2pBandPreference.values()) {
            assertTrue("$preference", NativeGroupBandPolicy.describePreference(preference).isNotEmpty())
        }
        assertTrue(NativeGroupBandPolicy.describePreference(P2pBandPreference.FORCE_2_4GHZ).contains("2.4 GHz"))
    }

    @Test
    fun `a 5GHz request that came up on 2_4GHz is remade`() {
        assertTrue(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2437, retriesSoFar = 0, maxRetries = 2))
        assertTrue(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2462, retriesSoFar = 1, maxRetries = 2))
    }

    @Test
    fun `a group deliberately put on 2_4GHz is never remade - the round would measure nothing otherwise`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_2_4, 2437, retriesSoFar = 0, maxRetries = 2))
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_2_4, 2462, retriesSoFar = 1, maxRetries = 2))
    }

    @Test
    fun `a 5GHz request that came up on 5GHz is left alone`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 5180, retriesSoFar = 0, maxRetries = 2))
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 5745, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `the retry budget still bounds it`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 2437, retriesSoFar = 2, maxRetries = 2))
    }

    @Test
    fun `an unknown frequency is not a mismatch`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.GHZ_5, 0, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `a standard-fallback group is never remade - nobody asked for a band`() {
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(Band.UNSPECIFIED, 2437, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `the labels are the ones the log and the briefs grep for`() {
        assertEquals("2.4GHz", NativeGroupBandPolicy.label(Band.GHZ_2_4))
        assertEquals("5GHz", NativeGroupBandPolicy.label(Band.GHZ_5))
    }
}
