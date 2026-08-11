package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.modes.native.ApBand
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApBandPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SoftApBandPolicyTest {

    @Test
    fun `5 GHz is tried first, with 2 point 4 kept only as a fallback`() {
        assertEquals(listOf(ApBand.BAND_5GHZ, ApBand.BAND_2GHZ), SoftApBandPolicy.attemptOrder())
    }

    @Test
    fun `opting out of 5 GHz leaves a single attempt, not a reordered pair`() {
        // A second attempt on a band that just failed would only double the time to give up.
        assertEquals(listOf(ApBand.BAND_2GHZ), SoftApBandPolicy.attemptOrder(prefer5Ghz = false))
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
}
