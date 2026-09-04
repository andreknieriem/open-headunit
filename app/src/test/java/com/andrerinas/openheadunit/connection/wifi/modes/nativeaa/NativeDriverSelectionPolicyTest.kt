package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDriverSelectionPolicyTest {

    @Test
    fun `disabled mode never shows selector`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 0, connectedCount = 0))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 1, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 2, connectedCount = 2))
    }

    @Test
    fun `single or zero paired devices never shows selector in any mode`() {
        for (mode in Mode.entries) {
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 0, connectedCount = 0))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, connectedCount = 1))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, connectedCount = 0))
        }
    }

    @Test
    fun `always mode shows selector whenever 2 or more devices are paired`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, connectedCount = 1))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 5, connectedCount = 1))
    }

    @Test
    fun `auto mode skips selector if exactly one device is confirmed connected`() {
        // Solo driver entering car with their phone already connected to BT: zero intrusion
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 4, connectedCount = 1))
    }

    @Test
    fun `auto mode shows selector if 2 or more devices are connected`() {
        // Both drivers enter car together: conflict prompt
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, connectedCount = 2))
    }

    @Test
    fun `auto mode shows selector if 0 devices are confirmed connected but multiple paired`() {
        // Connection state unknown (e.g. phones not yet connected to BT stack)
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, connectedCount = 0))
    }

    @Test
    fun `resolveAutoConnectTarget selects single connected device first`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_ALONE"),
            pairedMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_ALONE")
        )
        assertEquals("MAC_ALONE", target)
    }

    @Test
    fun `resolveAutoConnectTarget prioritizes preferred device when multiple connected`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_LAST", "MAC_PREF"),
            pairedMacs = listOf("MAC_PREF", "MAC_LAST", "MAC_OTHER")
        )
        assertEquals("MAC_PREF", target)
    }

    @Test
    fun `resolveAutoConnectTarget prioritizes last used device when no preferred device`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "MAC_LAST",
            connectedMacs = listOf("MAC_OTHER", "MAC_LAST"),
            pairedMacs = listOf("MAC_OTHER", "MAC_LAST")
        )
        assertEquals("MAC_LAST", target)
    }

    @Test
    fun `fresh install with multiple devices and none connected never auto-selects random paired device`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = listOf("MAC_OLD_PHONE", "MAC_SPEAKER")
        )
        assertNull(target)
    }

    @Test
    fun `fresh install with single connected device auto-selects it without history`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = listOf("MAC_CONNECTED"),
            pairedMacs = listOf("MAC_CONNECTED", "MAC_OTHER")
        )
        assertEquals("MAC_CONNECTED", target)
    }

    @Test
    fun `fresh install with single paired device auto-selects it without history`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = listOf("MAC_SOLO")
        )
        assertEquals("MAC_SOLO", target)
    }

    @Test
    fun `first start without history and multiple devices shows selector`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, hasHistory = false))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, connectedCount = 2, hasHistory = false))
    }

    @Test
    fun `single device never shows selector even without history`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, connectedCount = 1, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, connectedCount = 0, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 1, hasHistory = false))
    }

    @Test
    fun `resolveAutoConnectTarget returns null if candidate is empty`() {
        val target = NativeDriverSelectionPolicy.resolveAutoConnectTarget(
            preferredMac = "MAC_PREF",
            lastUsedMac = "",
            connectedMacs = emptyList(),
            pairedMacs = emptyList()
        )
        assertNull(target)
    }

    @Test
    fun `sanitizeTimeout clamps values between 3 and 30 seconds`() {
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-10))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(0))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(3))
        assertEquals(10, NativeDriverSelectionPolicy.sanitizeTimeout(10))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(30))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(31))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(60))
    }

    @Test
    fun `mode id mapping round trip works as expected`() {
        assertEquals(Mode.DISABLED, Mode.fromId(0))
        assertEquals(Mode.AUTO, Mode.fromId(1))
        assertEquals(Mode.ALWAYS, Mode.fromId(2))
        assertEquals(Mode.AUTO, Mode.fromId(-1))
        assertEquals(Mode.AUTO, Mode.fromId(99))
    }
}
