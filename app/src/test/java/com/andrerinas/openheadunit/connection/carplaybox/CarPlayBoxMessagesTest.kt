package com.andrerinas.openheadunit.connection.carplaybox

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayBoxMessagesTest {

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test
    fun `open is 28 bytes with the fields in order and work mode 2`() {
        val frame = CarPlayBoxMessages.open(1280, 720, 30, 5, 49152, 2)
        assertEquals(16 + 28, frame.size)
        val b = ByteBuffer.wrap(frame, 16, 28).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(1280, 720, 30, 5, 49152, 2, 2), List(7) { b.getInt() })
        assertEquals(28, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
    }

    @Test
    fun `heartbeat is the known 16 bytes`() {
        assertArrayEquals(
            hex(0xaa, 0x55, 0xaa, 0x55, 0, 0, 0, 0, 0xaa, 0, 0, 0, 0x55, 0xff, 0xff, 0xff),
            CarPlayBoxMessages.heartbeat(),
        )
    }

    @Test
    fun `command carries its number`() {
        val frame = CarPlayBoxMessages.command(12)
        assertEquals(20, frame.size)
        assertEquals(12, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(16))
    }

    @Test
    fun `send file holds the path with its zero then the content`() {
        val frame = CarPlayBoxMessages.sendFileInt("/a", 240)
        val b = ByteBuffer.wrap(frame, 16, frame.size - 16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(3, b.getInt())
        assertEquals('/'.code.toByte(), b.get())
        assertEquals('a'.code.toByte(), b.get())
        assertEquals(0.toByte(), b.get())
        assertEquals(4, b.getInt())
        assertEquals(240, b.getInt())
    }

    @Test
    fun `touch is 16 bytes with the flags word`() {
        val frame = CarPlayBoxMessages.touch(14, 5000, 6000, 0x10002)
        assertEquals(32, frame.size)
        val b = ByteBuffer.wrap(frame, 16, 16).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(listOf(14, 5000, 6000, 0x10002), List(4) { b.getInt() })
    }

    @Test
    fun `video header flags give the touch flags word`() {
        fun header(flags: Int) = le(24).putInt(1280).putInt(720).putInt(flags).array()
        assertEquals(2, CarPlayBoxMessages.parseVideo(header(0b0000))!!.touchFlags)
        assertEquals(1, CarPlayBoxMessages.parseVideo(header(0b0100))!!.touchFlags)
        assertEquals(4, CarPlayBoxMessages.parseVideo(header(0b1000))!!.touchFlags)
        assertEquals(2 or (1 shl 16), CarPlayBoxMessages.parseVideo(header(0b0001))!!.touchFlags)
        val parsed = CarPlayBoxMessages.parseVideo(header(0))!!
        assertEquals(1280, parsed.width)
        assertEquals(720, parsed.height)
        assertNull(CarPlayBoxMessages.parseVideo(ByteArray(19)))
    }

    @Test
    fun `audio header parses and the 13 byte form is a command`() {
        val pcm = le(16).putInt(4).putFloat(0.5f).putInt(1).putInt(0x7F).array()
        val parsed = CarPlayBoxMessages.parseAudio(pcm)!!
        assertEquals(4, parsed.decodeType)
        assertEquals(0.5f, parsed.volume, 0f)
        assertEquals(1, parsed.audioType)
        assertNull(parsed.command)
        val cmd = le(13).putInt(2).putFloat(0f).putInt(1).put(6).array()
        assertEquals(6, CarPlayBoxMessages.parseAudio(cmd)!!.command)
        assertNull(CarPlayBoxMessages.parseAudio(ByteArray(11)))
    }

    @Test
    fun `mic audio is type 7 decode 5 audio type 3`() {
        val frame = CarPlayBoxMessages.micAudio(byteArrayOf(1, 2, 3, 4))
        val b = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7, b.getInt(8))
        assertEquals(5, b.getInt(16))
        assertEquals(0f, b.getFloat(20), 0f)
        assertEquals(3, b.getInt(24))
        assertEquals(16 + 12 + 4, frame.size)
    }

    @Test
    fun `plugged and version parse`() {
        assertEquals(3, CarPlayBoxMessages.parsePluggedPhoneType(le(8).putInt(3).putInt(1).array()))
        assertNull(CarPlayBoxMessages.parsePluggedPhoneType(ByteArray(2)))
        assertEquals("2025.02", CarPlayBoxMessages.parseSoftwareVersion("2025.02\u0000".toByteArray()))
    }

    @Test
    fun `open size rounds an odd display down to even`() {
        assertEquals(Pair(1024, 600), CarPlayBoxMessages.openSize(1025, 601))
        assertEquals(Pair(1024, 600), CarPlayBoxMessages.openSize(1024, 600))
    }
}
