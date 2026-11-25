package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiBandCapabilityTest {

    @After
    fun clear() {
        WifiBandCapability.reportLocalHotspotNarrow(false)
        WifiBandCapability.reportSessionFrequency(0)
    }

    @Test
    fun `a narrow local hotspot caps the band and leaves the frequency at zero`() {
        WifiBandCapability.reportLocalHotspotNarrow(true)
        assertTrue(WifiBandCapability.bandUnreadable(34))
        assertEquals(0, WifiBandCapability.sessionFrequencyMhz())
    }

    @Test
    fun `clearing the report restores the reading`() {
        WifiBandCapability.reportLocalHotspotNarrow(true)
        WifiBandCapability.reportLocalHotspotNarrow(false)
        assertFalse(WifiBandCapability.bandUnreadable(34))
    }

    @Test
    fun `the log header ignores the local hotspot and reads only the API level`() {
        WifiBandCapability.reportLocalHotspotNarrow(true)
        assertFalse(WifiBandCapability.platformCannotAsk(34))
        assertTrue(WifiBandCapability.platformCannotAsk(20))
    }

    @Test
    fun `below API 21 the band is unreadable whatever was reported`() {
        assertTrue(WifiBandCapability.bandUnreadable(20))
    }

    @Test
    fun `the local flag reads back and clears with the report`() {
        assertFalse(WifiBandCapability.isLocalHotspotNarrow())
        WifiBandCapability.reportLocalHotspotNarrow(true)
        assertTrue(WifiBandCapability.isLocalHotspotNarrow())
        WifiBandCapability.reportLocalHotspotNarrow(false)
        assertFalse(WifiBandCapability.isLocalHotspotNarrow())
    }
}
