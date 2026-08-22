package com.andrerinas.openheadunit.aap

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

    // What a resolved address is allowed to conclude about the hardware.

    @Test
    fun `an address the chain found is a disproof`() {
        val resolved = SoftApBssidPolicy.choose(null, "11:22:33:44:55:66", null)
        assertTrue(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, null))
        assertTrue(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, ""))
        assertTrue("\"0\" is what the setting holds when unset", SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "0"))
    }

    @Test
    fun `the user's own address is not a disproof, whatever case or separator they typed it in`() {
        // choose() takes the override ahead of every automatic source and WifiDirectManager skips
        // its whole fallback chain when one is set, so behind an override the hardware was never
        // asked. Comparing raw strings would let a hand-typed spelling read as a detected address.
        val resolved = SoftApBssidPolicy.choose(" aa:bb:cc:dd:ee:ff ", "11:22:33:44:55:66", null)
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, " aa:bb:cc:dd:ee:ff "))
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "AA-BB-CC-DD-EE-FF"))
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "aa-bb-cc-dd-ee-ff"))
    }

    @Test
    fun `an override the chain threw away does not protect the record`() {
        // These never win choose(), so the address that got through is the hardware's and the
        // record is genuinely disproved.
        val resolved = SoftApBssidPolicy.choose("not-a-mac", "11:22:33:44:55:66", null)
        assertEquals("11:22:33:44:55:66", resolved)
        assertTrue(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "not-a-mac"))
        assertTrue(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "02:00:00:00:00:00"))
    }

    @Test
    fun `no usable address is never a disproof`() {
        // That state belongs to the abort branch, which raises rather than retires.
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable("", null))
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable(null, null))
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable("02:00:00:00:00:00", null))
    }

    @Test
    fun `a detected address that happens to equal the override is read as the override`() {
        // The deliberate false negative, pinned so it is not mistaken for a bug. Nothing is lost:
        // ConnectionIssueBannerPolicy.remedyApplied hides the banner on any usable override.
        val resolved = SoftApBssidPolicy.choose("11:22:33:44:55:66", "11:22:33:44:55:66", null)
        assertFalse(SoftApBssidPolicy.disprovesBssidUnavailable(resolved, "11:22:33:44:55:66"))
    }
}
