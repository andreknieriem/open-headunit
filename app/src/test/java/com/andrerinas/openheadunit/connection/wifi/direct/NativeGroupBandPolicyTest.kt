package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.direct.NativeGroupBandPolicy.Band
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
    fun `a radio with no 5GHz band does not get a 5GHz request under AUTO`() {
        assertEquals(Band.GHZ_2_4, NativeGroupBandPolicy.bandFor(P2pBandPreference.AUTO, supports5Ghz = false))
    }

    @Test
    fun `only a no changes AUTO - a yes and an unknown both keep the 5GHz request`() {
        // is5GHzBandSupported() describes the station side, so a yes is not a promise that a group
        // owner can be hosted there. AUTO keeps its own fallback rather than trusting it.
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(P2pBandPreference.AUTO, supports5Ghz = true))
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(P2pBandPreference.AUTO, supports5Ghz = null))
    }

    @Test
    fun `a user who asked for 5GHz still gets the request made on a radio that reports none`() {
        // The setting exists for a driver that surprises the capability call, so the capability
        // must not be able to overrule it.
        assertEquals(Band.GHZ_5, NativeGroupBandPolicy.bandFor(P2pBandPreference.FORCE_5GHZ, supports5Ghz = false))
    }

    @Test
    fun `AUTO on a radio with no 5GHz band never remakes the group for a band it cannot have`() {
        // The two halves have to agree: bandFor answers GHZ_2_4, and shouldRetryFor5Ghz only fires
        // on GHZ_5, so a group that came up on 2.4 GHz is the one that was asked for.
        val requested = NativeGroupBandPolicy.bandFor(P2pBandPreference.AUTO, supports5Ghz = false)
        assertFalse(NativeGroupBandPolicy.shouldRetryFor5Ghz(requested, 2437, retriesSoFar = 0, maxRetries = 2))
    }

    @Test
    fun `AUTO says which band it is about to try, so the line cannot contradict the request`() {
        assertTrue(
            NativeGroupBandPolicy.describePreference(P2pBandPreference.AUTO, supports5Ghz = false)
                .contains("2.4 GHz")
        )
        assertTrue(
            NativeGroupBandPolicy.describePreference(P2pBandPreference.AUTO, supports5Ghz = true)
                .contains("5 GHz")
        )
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

    @Test
    fun `a chosen channel becomes a frequency, and automatic asks for the band instead`() {
        // The whole point: setGroupOperatingBand is answered by a random pick among eight
        // frequencies, four of them UNII-3, so a request that names MHz is the only fixed one.
        assertEquals(5180, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.GHZ_5, 36))
        assertEquals(5745, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.GHZ_5, 149))
        assertEquals(0, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.GHZ_5, 0))
    }

    @Test
    fun `a 5 GHz channel is never pinned onto a request for another band`() {
        // The setting is shared with the hotspot route, so it is set on units asking for 2.4 GHz.
        assertEquals(0, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.GHZ_2_4, 36))
        assertEquals(0, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.UNSPECIFIED, 36))
    }

    @Test
    fun `a channel we do not offer is not passed to the driver`() {
        assertEquals(0, NativeGroupBandPolicy.requestedFrequencyMhz(NativeGroupBandPolicy.Band.GHZ_5, 52))
    }

    @Test
    fun `failures retry until the budget is spent, whatever was asked for`() {
        for (pinned in listOf(false, true)) {
            for (preference in P2pBandPreference.values()) {
                assertEquals(
                    "pinned=$pinned $preference",
                    NativeGroupBandPolicy.NextStep.RETRY,
                    NativeGroupBandPolicy.nextStepAfterFailure(preference, pinned, retriesSoFar = 3, maxRetries = 4)
                )
            }
        }
    }

    @Test
    fun `a spent budget drops the pinned channel before it drops the band`() {
        // Dropping the channel first is what keeps a wrong choice from being a dead unit: a forced
        // frequency fails the group outright rather than landing somewhere else.
        assertEquals(
            NativeGroupBandPolicy.NextStep.DROP_PINNED_CHANNEL,
            NativeGroupBandPolicy.nextStepAfterFailure(P2pBandPreference.AUTO, true, 4, 4)
        )
        // Including under 5 GHz only, which is about the band and says nothing about the channel.
        assertEquals(
            NativeGroupBandPolicy.NextStep.DROP_PINNED_CHANNEL,
            NativeGroupBandPolicy.nextStepAfterFailure(P2pBandPreference.FORCE_5GHZ, true, 4, 4)
        )
    }

    @Test
    fun `with no channel pinned the ladder is exactly the one that shipped`() {
        assertEquals(
            NativeGroupBandPolicy.NextStep.STANDARD_FALLBACK,
            NativeGroupBandPolicy.nextStepAfterFailure(P2pBandPreference.AUTO, false, 4, 4)
        )
        assertEquals(
            NativeGroupBandPolicy.NextStep.STANDARD_FALLBACK,
            NativeGroupBandPolicy.nextStepAfterFailure(P2pBandPreference.FORCE_2_4GHZ, false, 4, 4)
        )
        assertEquals(
            NativeGroupBandPolicy.NextStep.GIVE_UP,
            NativeGroupBandPolicy.nextStepAfterFailure(P2pBandPreference.FORCE_5GHZ, false, 4, 4)
        )
    }

    @Test
    fun `the step after a dropped channel agrees with fallsBackToPlatformChoice`() {
        // The caller restarts the budget with nothing pinned, so this pair is the whole ladder and
        // the two halves must not drift apart.
        for (preference in P2pBandPreference.values()) {
            val expected = if (NativeGroupBandPolicy.fallsBackToPlatformChoice(preference)) {
                NativeGroupBandPolicy.NextStep.STANDARD_FALLBACK
            } else {
                NativeGroupBandPolicy.NextStep.GIVE_UP
            }
            assertEquals(
                preference.toString(),
                expected,
                NativeGroupBandPolicy.nextStepAfterFailure(preference, false, 4, 4)
            )
        }
    }

    // --- Remembered banded refusal ---

    private val build = "Lenovo/L38043/L38043:10/QP1A/eng.test:userdebug/test-keys"

    @Test
    fun `AUTO skips the banded ladder on the build that refused it`() {
        assertTrue(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.AUTO, channelPinned = false, refusedOnFingerprint = build, currentFingerprint = build))
    }

    @Test
    fun `nothing recorded means the band is asked for as before`() {
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.AUTO, false, null, build))
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.AUTO, false, "", build))
    }

    @Test
    fun `a firmware update asks again`() {
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.AUTO, false, build, build + ".1"))
    }

    @Test
    fun `a forced band keeps asking for that band`() {
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.FORCE_5GHZ, false, build, build))
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.FORCE_2_4GHZ, false, build, build))
    }

    @Test
    fun `a pinned channel is an explicit choice and is still tried`() {
        assertFalse(NativeGroupBandPolicy.skipsBandedRequest(P2pBandPreference.AUTO, channelPinned = true, refusedOnFingerprint = build, currentFingerprint = build))
    }

    @Test
    fun `only an exhausted ladder under AUTO is recorded`() {
        assertTrue(NativeGroupBandPolicy.recordsBandedRefusal(P2pBandPreference.AUTO, ladderExhausted = true))
        assertFalse(NativeGroupBandPolicy.recordsBandedRefusal(P2pBandPreference.AUTO, ladderExhausted = false))
        assertFalse(NativeGroupBandPolicy.recordsBandedRefusal(P2pBandPreference.FORCE_2_4GHZ, ladderExhausted = true))
    }
}
