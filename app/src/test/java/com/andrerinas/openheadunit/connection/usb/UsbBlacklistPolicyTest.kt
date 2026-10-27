package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identities are from the round 1 and round 3 `UsbHostManager` captures on the transfer branch.
 * The point of the phone cases is that one handset appears under three VID:PIDs and one pair of
 * strings, so a numeric key cannot hold it and a string key can.
 */
class UsbBlacklistPolicyTest {

    private fun poco(vendorId: Int, productId: Int) =
        UsbBlacklistPolicy.key("Xiaomi", "POCO X3 NFC", vendorId, productId)

    @Test
    fun `the same phone keys the same in every USB mode it was captured in`() {
        val fileTransfer = poco(0x18D1, 0x4EE1)
        val debugging = poco(0x05C6, 0x90DB)
        val accessory = poco(0x18D1, 0x2D01)
        assertEquals("name:xiaomi poco x3 nfc", fileTransfer)
        assertEquals(fileTransfer, debugging)
        assertEquals(fileTransfer, accessory)
    }

    @Test
    fun `blacklisting a phone in one mode blocks it in the others, including accessory mode`() {
        // A device that arrives already switched is checked first by the service scan, so a key
        // that misses 18D1:2D01 lets a blacklisted phone connect anyway.
        val list = UsbBlacklistPolicy.add(emptySet(), poco(0x18D1, 0x4EE1))
        assertTrue(UsbBlacklistPolicy.isBlacklisted(list, poco(0x05C6, 0x90DB), 0x05C6, 0x90DB))
        assertTrue(UsbBlacklistPolicy.isBlacklisted(list, poco(0x18D1, 0x2D01), 0x18D1, 0x2D01))
    }

    @Test
    fun `two phones from different makers no longer share a namespace`() {
        val list = UsbBlacklistPolicy.add(emptySet(), poco(0x18D1, 0x4EE1))
        val moto = UsbBlacklistPolicy.key("motorola", "motorola edge 30 neo", 0x18D1, 0x4EE1)
        assertFalse(UsbBlacklistPolicy.isBlacklisted(list, moto, 0x18D1, 0x4EE1))
    }

    @Test
    fun `a device with no readable strings falls back to its VID and PID`() {
        // Below API 21, and on peripherals that ship no string descriptors. They have one stable
        // id and no modes, so the numbers are the right key for them.
        assertEquals("vidpid:090c:1000", UsbBlacklistPolicy.key(null, null, 0x090C, 0x1000))
        assertEquals("vidpid:090c:1000", UsbBlacklistPolicy.key("", "   ", 0x090C, 0x1000))
    }

    @Test
    fun `a manufacturer with no product name still keys on the name`() {
        assertEquals("vidpid:0b95:1790", UsbBlacklistPolicy.key(null, null, 0x0B95, 0x1790))
        assertEquals("name:asix", UsbBlacklistPolicy.key("ASIX", null, 0x0B95, 0x1790))
    }

    @Test
    fun `an entry written before the key had a prefix still matches`() {
        val old = setOf("0b95:1790")
        val key = UsbBlacklistPolicy.key("ASIX", "AX88179B", 0x0B95, 0x1790)
        assertTrue(UsbBlacklistPolicy.isBlacklisted(old, key, 0x0B95, 0x1790))
        assertEquals(emptySet<String>(), UsbBlacklistPolicy.remove(old, key, 0x0B95, 0x1790))
    }

    @Test
    fun `a stored entry in the wrong case or with stray spacing still matches`() {
        // The set survives a settings export, a hand edit and an import, so what comes back is
        // not necessarily what the UI wrote.
        val stored = setOf("Name:Xiaomi POCO X3 NFC", "  vidpid:090C:1000  ")
        assertTrue(UsbBlacklistPolicy.isBlacklisted(stored, poco(0x18D1, 0x4EE1), 0x18D1, 0x4EE1))
        assertTrue(
            UsbBlacklistPolicy.isBlacklisted(
                stored, UsbBlacklistPolicy.key(null, null, 0x090C, 0x1000), 0x090C, 0x1000
            )
        )
    }

    @Test
    fun `a device that is not listed is not blacklisted`() {
        assertFalse(UsbBlacklistPolicy.isBlacklisted(emptySet(), poco(0x18D1, 0x4EE1), 0x18D1, 0x4EE1))
        val list = UsbBlacklistPolicy.add(emptySet(), poco(0x18D1, 0x4EE1))
        val flashDisk = UsbBlacklistPolicy.key(null, null, 0x090C, 0x1000)
        assertFalse(UsbBlacklistPolicy.isBlacklisted(list, flashDisk, 0x090C, 0x1000))
    }

    @Test
    fun `adding rewrites the whole set in canonical form and does not duplicate`() {
        val once = UsbBlacklistPolicy.add(setOf("VIDPID:0B95:1790"), poco(0x18D1, 0x4EE1))
        assertEquals(setOf("vidpid:0b95:1790", "name:xiaomi poco x3 nfc"), once)
        assertEquals(once, UsbBlacklistPolicy.add(once, poco(0x05C6, 0x90DB)))
    }

    @Test
    fun `removing takes out only the device asked for`() {
        val list = UsbBlacklistPolicy.add(
            UsbBlacklistPolicy.add(emptySet(), poco(0x18D1, 0x4EE1)),
            UsbBlacklistPolicy.key(null, null, 0x090C, 0x1000),
        )
        val left = UsbBlacklistPolicy.remove(list, poco(0x18D1, 0x2D01), 0x18D1, 0x2D01)
        assertEquals(setOf("vidpid:090c:1000"), left)
    }

    @Test
    fun `normalising drops blank entries a hand-edited backup can leave behind`() {
        assertEquals(
            setOf("vidpid:0b95:1790"),
            UsbBlacklistPolicy.normalise(setOf("VIDPID:0B95:1790", "", "   ")),
        )
    }

    @Test
    fun `only a key without a name binds one USB mode`() {
        assertTrue(UsbBlacklistPolicy.bindsOneMode(UsbBlacklistPolicy.key(null, null, 0x22b8, 0x2e82)))
        assertFalse(UsbBlacklistPolicy.bindsOneMode(UsbBlacklistPolicy.key("motorola", "moto g", 0x22b8, 0x2e82)))
    }

    @Test
    fun `a device the manifest filter misses gets no note about Android's dialog`() {
        // The Carlinkit is the case that matters: telling its user to tick a default could take it from Autokit.
        val ccpa = UsbDeviceFixtures.carlinkitCcpa
        val key = UsbBlacklistPolicy.key("Magic Communication Tec.", "Auto Box", ccpa.vendorId, ccpa.productId)
        assertEquals(emptyList<UsbBlacklistPolicy.Note>(), UsbBlacklistPolicy.notesOnAdd(key, UsbManifestFilter.matches(ccpa)))
    }

    @Test
    fun `a device the manifest filter matches gets the note about Android's dialog`() {
        val phone = UsbDeviceFixtures.phoneAccessoryPlain
        val key = UsbBlacklistPolicy.key("Google", "Pixel", phone.vendorId, phone.productId)
        assertEquals(
            listOf(UsbBlacklistPolicy.Note.ANDROID_ASKS),
            UsbBlacklistPolicy.notesOnAdd(key, UsbManifestFilter.matches(phone)),
        )
    }

    @Test
    fun `a nameless matched device gets both notes, the USB ID one first`() {
        val key = UsbBlacklistPolicy.key(null, null, 0x1314, 0x1521)
        assertEquals(
            listOf(UsbBlacklistPolicy.Note.ONE_MODE, UsbBlacklistPolicy.Note.ANDROID_ASKS),
            UsbBlacklistPolicy.notesOnAdd(key, filterMatches = true),
        )
    }
}
