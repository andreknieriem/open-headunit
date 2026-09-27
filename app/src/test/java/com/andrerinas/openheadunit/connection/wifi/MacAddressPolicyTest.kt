package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MacAddressPolicyTest {

    @Test
    fun `every group address this project has measured is a generated one`() {
        // 21 from the rig's MT50 across every round on the transfer branch, plus one each from two
        // other reporters' units. No counterexample has been seen, on any unit.
        val measured = listOf(
            "26:E2:6D:4E:57:18", "E6:50:68:13:92:11", "62:4A:08:A3:30:49", "12:24:3A:84:CC:C5",
            "C6:E1:6F:A4:EA:39", "52:3A:28:ED:A7:E0", "32:0D:A8:1E:4B:87", "36:F8:B3:B4:DC:39",
            "06:38:49:39:E7:0C", "5A:50:C5:B9:73:DD", "4A:07:A4:F0:AC:CF", "16:AC:69:D7:78:CD",
            "1A:3B:D2:BB:71:B0", "EE:4C:47:05:30:1C",
        )
        for (mac in measured) assertEquals(mac, MacAddressOrigin.GENERATED, MacAddressPolicy.origin(mac))
    }

    @Test
    fun `an interface's own address is not a generated one`() {
        // The rig unit's access point, and a reporter's home router.
        assertEquals(MacAddressOrigin.FACTORY, MacAddressPolicy.origin("00:27:15:43:06:6a"))
        assertEquals(MacAddressOrigin.FACTORY, MacAddressPolicy.origin("f4:52:46:60:8d:4e"))
    }

    @Test
    fun `the bit is read from the first octet, not the string`() {
        // 0x02 set in the first octet and nowhere else is what marks it.
        assertEquals(MacAddressOrigin.GENERATED, MacAddressPolicy.origin("02:11:22:33:44:55"))
        assertEquals(MacAddressOrigin.FACTORY, MacAddressPolicy.origin("00:02:02:02:02:02"))
        assertEquals(MacAddressOrigin.FACTORY, MacAddressPolicy.origin("FC:FD:FD:FD:FD:FD"))
    }

    @Test
    fun `a masking placeholder is not an address, so it has no origin`() {
        // It used to grade 02: as generated and 00: as factory, which let the else branch in
        // WifiLauncherNative decide the soft AP's identity for an address nothing had read.
        assertEquals(MacAddressOrigin.UNREADABLE, MacAddressPolicy.origin("02:00:00:00:00:00"))
        assertEquals(MacAddressOrigin.UNREADABLE, MacAddressPolicy.origin("00:00:00:00:00:00"))
        assertNull(MacAddressPolicy.parse("02:00:00:00:00:00"))
        assertNull(MacAddressPolicy.parse("00:00:00:00:00:00", allowBare = true))
    }

    @Test
    fun `only Bluetooth accepts the separator-less form`() {
        // persist.zj.BTmac reads 008761BF6706, and a separator-only check cost every ZBT unit its
        // hands-free profile. A BSSID is never published that way, so the default stays strict.
        assertEquals("00:87:61:BF:67:06", MacAddressPolicy.parse("008761BF6706", allowBare = true))
        assertNull(MacAddressPolicy.parse("008761BF6706"))
    }

    @Test
    fun `parse returns one canonical spelling whatever it was given`() {
        for (raw in listOf("aa:bb:cc:dd:ee:ff", "AA-BB-CC-DD-EE-FF", "  Aa:bB:cC:dD:eE:fF  ")) {
            assertEquals(raw, "AA:BB:CC:DD:EE:FF", MacAddressPolicy.parse(raw))
        }
    }

    @Test
    fun `firstUsable skips what is not an address`() {
        assertEquals(
            "AA:BB:CC:DD:EE:FF",
            MacAddressPolicy.firstUsable(listOf(null, "", "0", "02:00:00:00:00:00", "aa-bb-cc-dd-ee-ff"))
        )
        assertNull(MacAddressPolicy.firstUsable(listOf(null, "0", "00:00:00:00:00:00")))
    }

    @Test
    fun `case and dash separators read the same`() {
        assertEquals(MacAddressOrigin.GENERATED, MacAddressPolicy.origin("aa:bb:cc:dd:ee:ff"))
        assertEquals(MacAddressOrigin.GENERATED, MacAddressPolicy.origin("AA-BB-CC-DD-EE-FF"))
        assertEquals(MacAddressOrigin.GENERATED, MacAddressPolicy.origin("  aa:bb:cc:dd:ee:ff  "))
    }

    @Test
    fun `anything that is not an address says nothing`() {
        for (junk in listOf(null, "", "0", "not a mac", "aa:bb:cc:dd:ee", "aa:bb:cc:dd:ee:ff:00")) {
            assertEquals(junk.toString(), MacAddressOrigin.UNREADABLE, MacAddressPolicy.origin(junk))
        }
    }

    @Test
    fun `the label is one word a reporter's log can be grepped for`() {
        assertEquals("generated", MacAddressPolicy.label("26:E2:6D:4E:57:18"))
        assertEquals("factory", MacAddressPolicy.label("00:27:15:43:06:6a"))
        assertEquals("unreadable", MacAddressPolicy.label("0"))
    }
}
