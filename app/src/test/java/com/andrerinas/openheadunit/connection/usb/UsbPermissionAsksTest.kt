package com.andrerinas.openheadunit.connection.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class UsbPermissionAsksTest {

    @Before
    fun reset() = UsbPermissionAsks.resetForTest()

    @Test
    fun `the USB access note shows once per plug-in`() {
        val path = "/dev/bus/usb/001/004"
        assertTrue(UsbPermissionAsks.tryHint(path))
        assertFalse(UsbPermissionAsks.tryHint(path))
        UsbPermissionAsks.noteDetach(path)
        assertTrue(UsbPermissionAsks.tryHint(path))
    }

    @Test
    fun `a denied entry ends at a detach no service saw, so a reused path may ask again`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "a", 1_000))
        assertFalse(UsbPermissionAsks.answered(bus[0], "a", granted = false))
        UsbPermissionAsks.noteDetach(bus[0])
        assertFalse(UsbPermissionAsks.isAsked(bus[0], bus))
    }

    @Test
    fun `a second mark for the same path is refused`() {
        assertTrue(UsbPermissionAsks.tryMark("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/004"), "t"))
        assertFalse(UsbPermissionAsks.tryMark("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/004"), "t"))
        assertTrue(UsbPermissionAsks.isAsked("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/004")))
    }

    @Test
    fun `a completed path can be marked again`() {
        val bus = listOf("/dev/bus/usb/001/004")
        UsbPermissionAsks.tryMark(bus[0], bus, token = "a")
        assertTrue(UsbPermissionAsks.complete(bus[0], "a"))
        assertFalse(UsbPermissionAsks.isAsked(bus[0], bus))
        assertTrue(UsbPermissionAsks.tryMark(bus[0], bus, "t"))
    }

    @Test
    fun `a path that left the bus is pruned at the next mark`() {
        UsbPermissionAsks.tryMark("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/004"), "t")
        // The phone re-enumerated and the first path is gone, so a missed result cannot block it.
        assertTrue(UsbPermissionAsks.tryMark("/dev/bus/usb/001/005", listOf("/dev/bus/usb/001/005"), "t"))
        assertFalse(UsbPermissionAsks.isAsked("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/005")))
        assertTrue(UsbPermissionAsks.tryMark("/dev/bus/usb/001/004", listOf("/dev/bus/usb/001/004"), "t"))
    }

    @Test
    fun `marking one path does not block another`() {
        val bus = listOf("/dev/bus/usb/001/004", "/dev/bus/usb/001/005")
        assertTrue(UsbPermissionAsks.tryMark(bus[0], bus, "t"))
        assertTrue(UsbPermissionAsks.tryMark(bus[1], bus, "t"))
    }

    @Test
    fun `the own-switch age is null before any switch, then grows from the stamp`() {
        assertNull(UsbPermissionAsks.ownSwitchAgeMs(5_000))
        UsbPermissionAsks.noteOwnSwitch(1_000)
        assertEquals(500L, UsbPermissionAsks.ownSwitchAgeMs(1_500))
        assertEquals(4_000L, UsbPermissionAsks.ownSwitchAgeMs(5_000))
    }

    @Test
    fun `a user claim passes an older open entry and an automatic one does not`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "t", 1_000))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "t", 9_000))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.RECOVERY, "t", 9_000))
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 9_000))
    }

    @Test
    fun `a second user press inside the repeat window opens no second dialog`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 1_000))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 1_051))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 1_570))
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 4_000))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "t", 5_000))
    }

    @Test
    fun `an answer to an older request at a reused path leaves the newer entry`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "a", 1_000))
        UsbPermissionAsks.noteDetach(bus[0])
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "b", 2_000))
        assertFalse(UsbPermissionAsks.complete(bus[0], "a"))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "c", 3_000))
        assertTrue(UsbPermissionAsks.complete(bus[0], "b"))
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "c", 4_000))
    }

    @Test
    fun `an open entry holds until its own answer, with no service in between`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "a", 1_000))
        // A recreated service asks again on its first scan; the entry must still refuse it.
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "b", 60_000))
        assertTrue(UsbPermissionAsks.complete(bus[0], "a"))
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "b", 61_000))
    }

    @Test
    fun `a denial keeps the entry and only a grant with the same token ends it`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "a", 1_000))
        // Below Android 12 a rebuilt dialog answers "denied" and stays on screen.
        assertFalse(UsbPermissionAsks.answered(bus[0], "a", granted = false))
        assertFalse(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "b", 2_000))
        assertFalse(UsbPermissionAsks.answered(bus[0], "b", granted = true))
        assertTrue(UsbPermissionAsks.answered(bus[0], "a", granted = true))
        assertFalse(UsbPermissionAsks.isAsked(bus[0], bus))
    }

    @Test
    fun `a user ask still passes an entry a denial kept`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.AUTOMATIC, "a", 1_000))
        UsbPermissionAsks.answered(bus[0], "a", granted = false)
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "b", 5_000))
    }

    @Test
    fun `a user ask that replaced an open dialog is not ended by the first dialog's answer`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "a", 1_000))
        assertTrue(UsbPermissionAsks.claim(bus[0], bus, UsbPermissionAskPolicy.Source.USER, "b", 5_000))
        assertFalse(UsbPermissionAsks.complete(bus[0], "a"))
        assertTrue(UsbPermissionAsks.isAsked(bus[0], bus))
    }

    @Test
    fun `the attach age is per path and a detach drops it with the entry`() {
        val bus = listOf("/dev/bus/usb/001/004")
        assertNull(UsbPermissionAsks.attachAgeMs(bus[0], 5_000))
        UsbPermissionAsks.noteAttach(bus[0], 1_000)
        UsbPermissionAsks.tryMark(bus[0], bus, "t")
        assertEquals(4_000L, UsbPermissionAsks.attachAgeMs(bus[0], 5_000))
        assertNull(UsbPermissionAsks.attachAgeMs("/dev/bus/usb/001/005", 5_000))
        UsbPermissionAsks.noteDetach(bus[0])
        assertNull(UsbPermissionAsks.attachAgeMs(bus[0], 5_000))
        assertFalse(UsbPermissionAsks.isAsked(bus[0], bus))
    }
}
