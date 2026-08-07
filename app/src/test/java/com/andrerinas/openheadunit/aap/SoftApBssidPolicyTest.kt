package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApBssidPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftApBssidPolicyTest {

    @Test
    fun `the user's own setting wins, and is normalised to upper case`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            SoftApBssidPolicy.choose(
                staticOverride = " aa:bb:cc:dd:ee:ff ",
                shellMac = "11:22:33:44:55:66",
                hardwareAddress = "77:88:99:aa:bb:cc"
            )
        )
    }

    @Test
    fun `a masked rung falls through to the next one`() {
        assertEquals(
            "11:22:33:44:55:66",
            SoftApBssidPolicy.choose(
                staticOverride = "02:00:00:00:00:00",
                shellMac = "11:22:33:44:55:66",
                hardwareAddress = null
            )
        )
        assertEquals(
            "77:88:99:AA:BB:CC",
            SoftApBssidPolicy.choose(
                staticOverride = "",
                shellMac = "00:00:00:00:00:00",
                hardwareAddress = "77:88:99:aa:bb:cc"
            )
        )
    }

    @Test
    fun `all rungs failing yields the empty string, not a placeholder`() {
        // Sending a placeholder is worse than sending nothing: the phone rejects it, and the
        // hotspot route is allowed to omit the field entirely.
        assertEquals("", SoftApBssidPolicy.choose(null, null, null))
        assertEquals("", SoftApBssidPolicy.choose("", "  ", "02:00:00:00:00:00"))
    }

    @Test
    fun `a randomised locally-administered address is a real address`() {
        // Android's own soft AP routinely uses one. Rejecting everything starting with 02: would
        // throw away the BSSID this whole chain exists to find.
        assertTrue(SoftApBssidPolicy.isUsable("02:1A:2B:3C:4D:5E"))
        assertEquals("02:1A:2B:3C:4D:5E", SoftApBssidPolicy.choose("02:1a:2b:3c:4d:5e", null, null))
    }

    @Test
    fun `the settings default of "0" is not an address`() {
        // staticBSSID stores "0" when unset. It is not MAC-shaped, so a placeholder blacklist let
        // it through, and being first in the chain it beat the real interface MAC and went out as
        // BSSID=0 — which the phone rejected on every retry.
        assertFalse(SoftApBssidPolicy.isUsable("0"))
        assertEquals(
            "11:22:33:44:55:66",
            SoftApBssidPolicy.choose(staticOverride = "0", shellMac = "11:22:33:44:55:66", hardwareAddress = null)
        )
    }

    @Test
    fun `anything that is not shaped like an address is refused`() {
        assertFalse(SoftApBssidPolicy.isUsable("auto"))
        assertFalse(SoftApBssidPolicy.isUsable("192.168.1.1"))
        assertFalse(SoftApBssidPolicy.isUsable("aa:bb:cc:dd:ee"))
        assertFalse(SoftApBssidPolicy.isUsable("aa:bb:cc:dd:ee:ff:00"))
        assertFalse(SoftApBssidPolicy.isUsable("gg:bb:cc:dd:ee:ff"))
        assertFalse(SoftApBssidPolicy.isUsable("aabbccddeeff"))
    }

    @Test
    fun `a hand-typed address with dashes is accepted and normalised`() {
        assertTrue(SoftApBssidPolicy.isUsable("aa-bb-cc-dd-ee-ff"))
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            SoftApBssidPolicy.choose(staticOverride = "aa-bb-cc-dd-ee-ff", shellMac = null, hardwareAddress = null)
        )
        // A dash-written placeholder is still a placeholder.
        assertFalse(SoftApBssidPolicy.isUsable("02-00-00-00-00-00"))
    }

    @Test
    fun `the two masking placeholders and blanks are not usable`() {
        assertFalse(SoftApBssidPolicy.isUsable(null))
        assertFalse(SoftApBssidPolicy.isUsable(""))
        assertFalse(SoftApBssidPolicy.isUsable("   "))
        assertFalse(SoftApBssidPolicy.isUsable("00:00:00:00:00:00"))
        assertFalse(SoftApBssidPolicy.isUsable("02:00:00:00:00:00"))
        assertFalse(SoftApBssidPolicy.isUsable("02:00:00:00:00:00 "))
        assertFalse("case must not smuggle a placeholder through", SoftApBssidPolicy.isUsable("02:00:00:00:00:00".uppercase()))
    }
}
