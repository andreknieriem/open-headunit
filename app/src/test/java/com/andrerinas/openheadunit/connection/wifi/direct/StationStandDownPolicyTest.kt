package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationStandDownPolicyTest {

    // --- isAvailable: the guard lives in the device's framework, not in our target SDK ---

    @Test
    fun `below Q the platform honours it with no permission`() {
        assertTrue(StationStandDownPolicy.isAvailable(27, canDrawOverlays = false))
        assertTrue(StationStandDownPolicy.isAvailable(28, canDrawOverlays = false))
    }

    @Test
    fun `from Q to Android 14 the overlay permission is what gets past the guard`() {
        for (sdk in 29..34) {
            assertFalse("api $sdk", StationStandDownPolicy.isAvailable(sdk, canDrawOverlays = false))
            assertTrue("api $sdk", StationStandDownPolicy.isAvailable(sdk, canDrawOverlays = true))
        }
    }

    @Test
    fun `from Android 15 the bypass is gone and nothing works`() {
        assertFalse(StationStandDownPolicy.isAvailable(35, canDrawOverlays = true))
        assertFalse(StationStandDownPolicy.isAvailable(36, canDrawOverlays = true))
    }

    // --- shouldStandDown ---

    private fun decide(
        mode: StationStandDownMode = StationStandDownMode.AUTO,
        sdkInt: Int = 27,
        canDrawOverlays: Boolean = false,
        associated: Boolean = true,
        networkId: Int = 3,
        supports5Ghz: Boolean? = true,
        stationFrequencyMhz: Int = 2437,
        groupBand: P2pBandPreference = P2pBandPreference.AUTO,
    ) = StationStandDownPolicy.shouldStandDown(
        mode, sdkInt, canDrawOverlays, associated, networkId, supports5Ghz, stationFrequencyMhz,
        groupBand
    )

    @Test
    fun `auto stands down a 5 GHz radio whose station sits on 2_4 GHz`() {
        assertTrue(decide(supports5Ghz = true, stationFrequencyMhz = 2437))
        assertTrue(decide(supports5Ghz = true, stationFrequencyMhz = 2462))
    }

    @Test
    fun `auto leaves a 5 GHz station when the group is being asked for on 2_4 GHz`() {
        // Measured on the rig: under AUTO that split never finished joining, under ALWAYS the same
        // setup connected in under a minute and ran stable.
        assertTrue(
            decide(
                supports5Ghz = true, stationFrequencyMhz = 5745,
                groupBand = P2pBandPreference.FORCE_2_4GHZ
            )
        )
        assertFalse(
            decide(
                supports5Ghz = true, stationFrequencyMhz = 5745,
                groupBand = P2pBandPreference.AUTO
            )
        )
        assertFalse(
            decide(
                supports5Ghz = true, stationFrequencyMhz = 5745,
                groupBand = P2pBandPreference.FORCE_5GHZ
            )
        )
    }

    @Test
    fun `auto stands down a known 2_4 GHz-only station on legacy Android`() {
        assertTrue(decide(sdkInt = 25, supports5Ghz = false, stationFrequencyMhz = 2412))
        assertTrue(decide(sdkInt = 24, supports5Ghz = false, stationFrequencyMhz = 2412))
        assertFalse(decide(sdkInt = 26, supports5Ghz = false, stationFrequencyMhz = 2412))
    }

    @Test
    fun `a 2_4 GHz group on a radio with no 5 GHz band still keeps the station, which would only scan`() {
        assertFalse(
            decide(
                supports5Ghz = false, stationFrequencyMhz = 2437,
                groupBand = P2pBandPreference.FORCE_2_4GHZ
            )
        )
    }

    @Test
    fun `auto keeps the station joined on a radio with no 5 GHz band, which only scans if it leaves`() {
        assertFalse(decide(supports5Ghz = false, stationFrequencyMhz = 2437))
        assertFalse(decide(supports5Ghz = false, stationFrequencyMhz = 0))
    }

    @Test
    fun `auto keeps a station that is already on 5 GHz, the state measured to run clean`() {
        assertFalse(decide(supports5Ghz = true, stationFrequencyMhz = 5500))
        assertFalse(decide(supports5Ghz = null, stationFrequencyMhz = 5745))
    }

    @Test
    fun `auto treats an unknown band and an unreadable frequency as a unit that can gain a band`() {
        assertTrue(decide(supports5Ghz = null, stationFrequencyMhz = 0))
        assertTrue(decide(supports5Ghz = true, stationFrequencyMhz = 0))
    }

    @Test
    fun `always stands down whatever the band says`() {
        assertTrue(decide(mode = StationStandDownMode.ALWAYS, supports5Ghz = false, stationFrequencyMhz = 2437))
        assertTrue(decide(mode = StationStandDownMode.ALWAYS, supports5Ghz = true, stationFrequencyMhz = 5500))
    }

    @Test
    fun `never keeps the station joined whatever the band says`() {
        assertFalse(decide(mode = StationStandDownMode.NEVER, supports5Ghz = true, stationFrequencyMhz = 2437))
        assertFalse(decide(mode = StationStandDownMode.NEVER, supports5Ghz = null, stationFrequencyMhz = 0))
    }

    @Test
    fun `never when nothing is joined`() {
        for (mode in StationStandDownMode.values()) {
            assertFalse(mode.name, decide(mode = mode, associated = false, canDrawOverlays = true))
        }
    }

    @Test
    fun `never on a hidden network id, which is what a redacted read looks like`() {
        for (mode in StationStandDownMode.values()) {
            assertFalse(mode.name, decide(mode = mode, networkId = -1, canDrawOverlays = true))
        }
    }

    @Test
    fun `never where the platform would refuse the call`() {
        for (mode in StationStandDownMode.values()) {
            assertFalse(mode.name, decide(mode = mode, sdkInt = 35, canDrawOverlays = true))
        }
    }

    @Test
    fun `the platform gate is the whole of it once a 2_4 GHz network is joined`() {
        for (sdk in 21..36) {
            for (overlay in listOf(false, true)) {
                assertEquals(
                    "api $sdk overlay=$overlay",
                    StationStandDownPolicy.isAvailable(sdk, overlay),
                    decide(sdkInt = sdk, canDrawOverlays = overlay)
                )
            }
        }
    }

    @Test
    fun `the 5 GHz band starts above 4000 MHz`() {
        assertFalse(StationStandDownPolicy.isFiveGhz(2484))
        assertFalse(StationStandDownPolicy.isFiveGhz(0))
        assertTrue(StationStandDownPolicy.isFiveGhz(5170))
        assertTrue(StationStandDownPolicy.isFiveGhz(5825))
    }

    // --- describeSkipped: exactly the complement of the mode's own decision ---

    @Test
    fun `a reason is given exactly when the mode leaves the station joined`() {
        for (sdkInt in listOf(25, 26, 27)) {
            for (mode in StationStandDownMode.values()) {
                for (band in listOf(null, false, true)) {
                    for (freq in listOf(0, 2437, 5500)) {
                        for (group in P2pBandPreference.values()) {
                            val stands = decide(
                                mode = mode, sdkInt = sdkInt, supports5Ghz = band,
                                stationFrequencyMhz = freq, groupBand = group
                            )
                            val why = StationStandDownPolicy.describeSkipped(
                                mode, sdkInt, band, freq, group
                            )
                            assertEquals(
                                "$mode api=$sdkInt band=$band freq=$freq group=$group",
                                stands,
                                why == null
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `the reasons name what decided`() {
        assertTrue(
            StationStandDownPolicy.describeSkipped(
                StationStandDownMode.AUTO, 27, false, 2437, P2pBandPreference.AUTO
            )!!.contains("no 5 GHz band")
        )
        assertTrue(
            StationStandDownPolicy.describeSkipped(
                StationStandDownMode.AUTO, 27, true, 5500, P2pBandPreference.AUTO
            )!!.contains("5500 MHz")
        )
        assertTrue(
            StationStandDownPolicy.describeSkipped(
                StationStandDownMode.NEVER, 27, true, 2437, P2pBandPreference.AUTO
            )!!.contains("setting")
        )
    }

    // --- describeUnavailable: exactly the complement of isAvailable ---

    @Test
    fun `says nothing when it will work`() {
        assertNull(StationStandDownPolicy.describeUnavailable(27, canDrawOverlays = false))
        assertNull(StationStandDownPolicy.describeUnavailable(30, canDrawOverlays = true))
    }

    @Test
    fun `names the overlay permission where that is the missing piece`() {
        val why = StationStandDownPolicy.describeUnavailable(30, canDrawOverlays = false)
        assertNotNull(why)
        assertTrue(why!!.contains("display over other apps"))
    }

    @Test
    fun `says there is no route at all from Android 15`() {
        val why = StationStandDownPolicy.describeUnavailable(35, canDrawOverlays = true)
        assertNotNull(why)
        assertFalse(why!!.contains("display over other apps"))
    }

    @Test
    fun `an explanation is offered for every state that is not available`() {
        for (sdk in 21..36) {
            for (overlay in listOf(false, true)) {
                val available = StationStandDownPolicy.isAvailable(sdk, overlay)
                val why = StationStandDownPolicy.describeUnavailable(sdk, overlay)
                assertEquals("api $sdk overlay=$overlay", available, why == null)
            }
        }
    }

    // --- shouldRestore: a record with no restore is the one harmful outcome ---

    @Test
    fun `restores whenever a record is standing`() {
        assertTrue(StationStandDownPolicy.shouldRestore(0))
        assertTrue(StationStandDownPolicy.shouldRestore(7))
    }

    @Test
    fun `nothing to restore when no record is standing`() {
        assertFalse(StationStandDownPolicy.shouldRestore(-1))
    }
}
