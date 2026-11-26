package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxTcp.Companion.ACK
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxTcp.Companion.FIN
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxTcp.Companion.RST
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxTcp.Companion.SYN
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases of LIVI `mux.rs` tests, with the state reached through a SYN-ACK that carries sequence 99. */
class UsbmuxTcpTest {

    private fun up(): UsbmuxTcp = UsbmuxTcp(1, 2).also {
        it.syn()
        it.onSegment(SYN or ACK, 99, ByteArray(0))
    }

    private fun ackOf(segment: ByteArray) = UsbmuxTcp.parse(segment).let { ((segment[8].toInt() and 0xff) shl 24) or ((segment[9].toInt() and 0xff) shl 16) or ((segment[10].toInt() and 0xff) shl 8) or (segment[11].toInt() and 0xff) }

    @Test
    fun `the SYN has no payload, sequence 0 and the SYN flag`() {
        val segment = UsbmuxTcp.parse(UsbmuxTcp(1, 62078).syn())
        assertEquals(1, segment.sourcePort)
        assertEquals(62078, segment.destinationPort)
        assertEquals(0, segment.sequence)
        assertEquals(SYN, segment.flags)
        assertEquals(0, segment.payload.size)
    }

    @Test
    fun `a SYN-ACK connects, moves the sequence by one and acks the peer sequence plus one`() {
        val tcp = UsbmuxTcp(1, 2)
        tcp.syn()
        val reaction = tcp.onSegment(SYN or ACK, 99, ByteArray(0))
        assertTrue(reaction.connected)
        assertTrue(tcp.connected)
        val ack = reaction.replies.single()
        assertEquals(ACK, UsbmuxTcp.parse(ack).flags)
        assertEquals(1, UsbmuxTcp.parse(ack).sequence)
        assertEquals(100, ackOf(ack))
    }

    @Test
    fun `data with a FIN hands the payload over and acks both`() {
        val tcp = up()
        val reaction = tcp.onSegment(ACK or FIN, 100, byteArrayOf(1, 2, 3))
        assertArrayEquals(byteArrayOf(1, 2, 3), reaction.data)
        assertEquals(listOf(103, 104), reaction.replies.map(::ackOf))
        assertTrue(reaction.closed)
        assertNull(tcp.fin())
    }

    @Test
    fun `written chunks advance the sequence and the FIN comes once`() {
        val tcp = up()
        val first = tcp.data(ByteArray(5)).single()
        val second = tcp.data(ByteArray(2)).single()
        assertEquals(listOf(1, 6), listOf(first, second).map { UsbmuxTcp.parse(it).sequence })
        assertNotNull(tcp.fin())
        assertNull(tcp.fin())
    }

    @Test
    fun `a write above 16 KiB splits into segments of at most 16 KiB`() {
        val segments = up().data(ByteArray(16384 * 2 + 10))
        assertEquals(listOf(16384, 16384, 10), segments.map { UsbmuxTcp.parse(it).payload.size })
        assertEquals(listOf(1, 16385, 32769), segments.map { UsbmuxTcp.parse(it).sequence })
    }

    @Test
    fun `a RST ends the connection and answers nothing`() {
        val tcp = up()
        val reaction = tcp.onSegment(RST, 5, ByteArray(0))
        assertTrue(reaction.reset)
        assertTrue(reaction.replies.isEmpty())
        assertTrue(tcp.closed)
        assertFalse(tcp.connected && !tcp.closed)
    }

    @Test
    fun `a bare ACK gets no reply and no data`() {
        val reaction = up().onSegment(ACK, 100, ByteArray(0))
        assertNull(reaction.data)
        assertTrue(reaction.replies.isEmpty())
    }

    @Test
    fun `payload after the close is not handed over`() {
        val tcp = up()
        tcp.onSegment(FIN or ACK, 100, ByteArray(0))
        assertNull(tcp.onSegment(ACK, 101, byteArrayOf(1)).data)
    }

    @Test
    fun `parse reads the payload from the data offset, options included`() {
        val raw = ByteArray(24 + 3).also { it[12] = 0x60; it[13] = ACK.toByte(); it[24] = 7; it[25] = 8; it[26] = 9 }
        assertArrayEquals(byteArrayOf(7, 8, 9), UsbmuxTcp.parse(raw).payload)
    }

    @Test
    fun `a segment shorter than its header or with a bad offset is refused`() {
        listOf(ByteArray(19), ByteArray(20).also { it[12] = 0x40 }, ByteArray(20).also { it[12] = 0xf0.toByte() }).forEach {
            try {
                UsbmuxTcp.parse(it)
                throw AssertionError("expected a framing error")
            } catch (_: UsbmuxFramingException) {
            }
        }
    }
}
