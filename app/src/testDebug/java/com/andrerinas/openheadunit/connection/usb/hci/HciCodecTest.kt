package com.andrerinas.openheadunit.connection.usb.hci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HciCodecTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun resetEncodesToTheSpecBytes() {
        assertArrayEquals(bytes(0x03, 0x0C, 0x00), HciCodec.command(HciCodec.OP_RESET))
    }

    @Test
    fun anEventSplitAcrossPacketsIsRejoined() {
        val a = HciCodec.EventAssembler()
        val whole = bytes(0x0E, 0x0C, 0x01, 0x01, 0x10, 0x00, 0x0A, 0x0B, 0x00, 0x0A, 0x5D, 0x00, 0x61, 0x87)
        assertEquals(0, a.feed(whole.copyOfRange(0, 5), 5).size)
        val events = a.feed(whole.copyOfRange(5, whole.size), whole.size - 5)
        assertEquals(1, events.size)
        val (op, ret) = HciCodec.commandComplete(events[0])!!
        assertEquals(HciCodec.OP_READ_LOCAL_VERSION, op)
        val v = HciCodec.localVersion(ret)!!
        assertEquals(0, v.status)
        assertEquals(HciCodec.MANUFACTURER_REALTEK, v.manufacturer)
        assertEquals(0x8761, v.lmpSubversion)
    }

    @Test
    fun twoEventsInOnePacketAreBothReturned() {
        val a = HciCodec.EventAssembler()
        val p = bytes(0x0F, 0x04, 0x00, 0x01, 0x05, 0x04, 0x03, 0x0B, 0x00, 0x2A, 0x00, 1, 2, 3, 4, 5, 6, 0x01, 0x00)
        val events = a.feed(p, p.size)
        assertEquals(2, events.size)
        assertEquals(HciCodec.OP_CREATE_CONNECTION to 0, HciCodec.commandStatus(events[0]))
        assertEquals(0 to 0x2A, HciCodec.connectionComplete(events[1]))
    }

    @Test
    fun bdAddrReadsMostSignificantByteFirst() {
        assertEquals("66:55:44:33:22:11", HciCodec.bdAddr(bytes(0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)))
    }

    @Test
    fun createConnectionPutsTheAddressLittleEndianAndPagesInMode02() {
        val cmd = HciCodec.createConnection(HciCodec.parseAddress("AA:BB:CC:DD:EE:FF")!!)
        assertArrayEquals(bytes(0x05, 0x04, 13, 0xFF, 0xEE, 0xDD, 0xCC, 0xBB, 0xAA), cmd.copyOfRange(0, 9))
        assertArrayEquals(bytes(0x18, 0xCC, 0x02, 0x00, 0x00, 0x00, 0x01), cmd.copyOfRange(9, 16))
        assertEquals(16, cmd.size)
    }

    @Test
    fun otherEventsAreNotReadAsCommandComplete() {
        assertNull(HciCodec.commandComplete(HciCodec.Event(0x0F, bytes(0, 1, 3, 0x0C))))
    }

    @Test
    fun aShortLastFragmentIsPaddedToTheFixedField() {
        val cmd = HciCodec.rtkDownload(0x80 or 119, ByteArray(222) { 0x11 })
        assertEquals(256, cmd.size)
        assertEquals(0xFD, cmd[2].toInt() and 0xFF)
        assertEquals(0xF7, cmd[3].toInt() and 0xFF)
        assertEquals(0x20, cmd[0].toInt() and 0xFF)
        assertEquals(0xFC, cmd[1].toInt() and 0xFF)
        for (i in 4 + 222 until 256) assertEquals(0, cmd[i].toInt())
    }

    @Test
    fun parseAddressAcceptsColonsAndRejectsBadInput() {
        assertArrayEquals(bytes(6, 5, 4, 3, 2, 1), HciCodec.parseAddress("01:02:03:04:05:06"))
        assertNull(HciCodec.parseAddress("01:02:03"))
        assertNull(HciCodec.parseAddress("zz:02:03:04:05:06"))
        assertNull(HciCodec.parseAddress(""))
    }
}
