package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApBandPolicyTest {

    @Test
    fun `automatic tries 5 GHz first, with 2 point 4 kept only as a fallback`() {
        assertEquals(
            listOf(ApBand.BAND_5GHZ, ApBand.BAND_2GHZ),
            SoftApBandPolicy.attemptOrder(HotspotBandPreference.AUTO)
        )
    }

    @Test
    fun `a caller that does not pass a preference gets the automatic sweep`() {
        // HotspotManager relies on this default when the preference cannot be read, which happens
        // before the user has unlocked the device.
        assertEquals(
            SoftApBandPolicy.attemptOrder(HotspotBandPreference.AUTO),
            SoftApBandPolicy.attemptOrder()
        )
    }

    @Test
    fun `forcing 5 GHz never falls back to the band that carried no video`() {
        val order = SoftApBandPolicy.attemptOrder(HotspotBandPreference.FORCE_5GHZ)
        assertEquals(listOf(ApBand.BAND_5GHZ), order)
        // Stated separately from the equality above: the whole point of this preference is the
        // absence, and a future reordering should fail here rather than only on list shape.
        assertFalse(ApBand.BAND_2GHZ in order)
    }

    @Test
    fun `forcing 2 point 4 leaves a single attempt, not a reordered pair`() {
        // A second attempt on a band that just failed would only double the time to give up.
        assertEquals(
            listOf(ApBand.BAND_2GHZ),
            SoftApBandPolicy.attemptOrder(HotspotBandPreference.FORCE_2_4GHZ)
        )
    }

    @Test
    fun `every preference asks for at least one band, and never the same one twice`() {
        // An empty sweep would reach HotspotManager as "nothing was attempted", which it reports as
        // every attempt having failed - a log saying the opposite of what happened.
        for (preference in HotspotBandPreference.values()) {
            val order = SoftApBandPolicy.attemptOrder(preference)
            assertTrue("$preference asked for no band at all", order.isNotEmpty())
            assertEquals("$preference repeats a band", order.size, order.distinct().size)
        }
    }

    @Test
    fun `the stored values are the ones the settings screen writes`() {
        // The ordinal contract. A settings backup carries the number, not the name, so this is what
        // a restore onto a newer build can break.
        assertEquals(HotspotBandPreference.AUTO, HotspotBandPreference.fromSetting(0))
        assertEquals(HotspotBandPreference.FORCE_5GHZ, HotspotBandPreference.fromSetting(1))
        assertEquals(HotspotBandPreference.FORCE_2_4GHZ, HotspotBandPreference.fromSetting(2))
    }

    @Test
    fun `an unknown stored value falls back to trying both bands`() {
        assertEquals(HotspotBandPreference.AUTO, HotspotBandPreference.fromSetting(-1))
        assertEquals(HotspotBandPreference.AUTO, HotspotBandPreference.fromSetting(3))
        assertEquals(HotspotBandPreference.AUTO, HotspotBandPreference.fromSetting(99))
    }

    @Test
    fun `band constants match the framework's, which are not the same on both APIs`() {
        // SoftApConfiguration: BAND_2GHZ = 1, BAND_5GHZ = 2.
        assertEquals(1, SoftApBandPolicy.softApConfigurationBand(ApBand.BAND_2GHZ))
        assertEquals(2, SoftApBandPolicy.softApConfigurationBand(ApBand.BAND_5GHZ))
        // WifiConfiguration.apBand: 0 = 2.4 GHz, 1 = 5 GHz. Off by one against the above, which is
        // the whole reason these live behind named accessors.
        assertEquals(0, SoftApBandPolicy.legacyApBand(ApBand.BAND_2GHZ))
        assertEquals(1, SoftApBandPolicy.legacyApBand(ApBand.BAND_5GHZ))
    }

    @Test
    fun `bands describe themselves the way a bug report reads`() {
        assertEquals("5 GHz", SoftApBandPolicy.describe(ApBand.BAND_5GHZ))
        assertEquals("2.4 GHz", SoftApBandPolicy.describe(ApBand.BAND_2GHZ))
    }

    @Test
    fun `a preference describes itself, so a pasted log says why one band was tried`() {
        for (preference in HotspotBandPreference.values()) {
            assertTrue(SoftApBandPolicy.describePreference(preference).isNotEmpty())
        }
        assertTrue(
            SoftApBandPolicy.describePreference(HotspotBandPreference.FORCE_2_4GHZ)
                .contains("2.4 GHz")
        )
    }
}
