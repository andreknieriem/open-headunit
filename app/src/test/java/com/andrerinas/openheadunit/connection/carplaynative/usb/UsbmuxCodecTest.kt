package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The VERSION packet and mux header against LIVI `mux.rs`, and the arrays of DiPlay's usbmux regression test. */
class UsbmuxCodecTest {

    // 20 bytes: protocol 0, length 20, version 2, eight zero bytes.
    @Test
    fun `the VERSION packet bytes`() {
        assertEquals("00000000000000140000000200000000" + "00000000", UsbmuxCodec.versionPacket().toHex())
    }

    @Test
    fun `a mux frame carries protocol, length, magic and the two counters`() {
        val frame = UsbmuxCodec.frame(UsbmuxCodec.PROTOCOL_TCP, 0x0102, 0x0304, byteArrayOf(9, 8))
        assertEquals("00000006" + "00000012" + "feedface" + "0102" + "0304" + "0908", frame.toHex())
    }

    @Test
    fun `the SETUP frame carries the single byte 07`() {
        assertEquals("00000002" + "00000011" + "feedface" + "0000" + "0000" + "07", UsbmuxCodec.setupFrame(0, 0).toHex())
    }

    @Test
    fun `a frame the host built is read back by the framer`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(UsbmuxCodec.frame(6, 7, 3, byteArrayOf(1, 2, 3)))
        val frame = buffer.takeFrame()!!
        assertEquals(6, frame.protocol)
        assertEquals(19, frame.length)
        assertEquals(7, frame.sequence)
        assertArrayEquals(byteArrayOf(1, 2, 3), frame.payload)
        assertNull(buffer.takeFrame())
    }

    @Test
    fun `only version 2 of length 20 is a version reply`() {
        fun reply(bytes: ByteArray) = UsbmuxCodec.FrameBuffer().also { it.append(bytes) }.takeFrame()!!
        assertTrue(UsbmuxCodec.isVersionReply(reply(versionReplyWithPadding.copyOf(20))))
        val v1 = versionReplyWithPadding.copyOf(20).also { it[11] = 1 }
        assertEquals(false, UsbmuxCodec.isVersionReply(reply(v1)))
    }

    @Test
    fun `the captured padded VERSION and SYN-ACK replies leave the next frame intact`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(versionReplyWithPadding)
        assertEquals(20, buffer.takeFrame()!!.length)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), remainder(buffer))
        buffer.append(capturedSynAckWithPadding)
        assertEquals(36, buffer.takeFrame()!!.length)
        assertEquals("6d436f6e", remainder(buffer).toHex())
        buffer.append(normalSynAck)
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test
    fun `a padded SYN-ACK split at byte 13 keeps only the observed extra bytes`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(capturedSynAckWithPadding.copyOfRange(0, 13))
        assertNull(buffer.takeFrame())
        buffer.append(capturedSynAckWithPadding.copyOfRange(13, 40))
        assertArrayEquals(normalSynAck.copyOfRange(16, 36), buffer.takeFrame()!!.payload)
        assertEquals("6d436f6e", remainder(buffer).toHex())
    }

    @Test
    fun `an unpadded VERSION and SYN-ACK are read as they are`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(versionReplyWithPadding.copyOf(20))
        assertEquals(20, buffer.takeFrame()!!.length)
        buffer.append(normalSynAck)
        assertEquals(36, buffer.takeFrame()!!.length)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test
    fun `a malformed pad is refused and its bytes stay buffered`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(versionReplyWithPadding.copyOf(20))
        buffer.takeFrame()
        val junk = hex("6d436f6e00000007") + ByteArray(16)
        buffer.append(junk)
        try {
            buffer.takeFrame()
            throw AssertionError("expected a framing error")
        } catch (_: UsbmuxFramingException) {
        }
        assertEquals(junk.size, buffer.bufferedBytes)
    }

    @Test
    fun `padding that follows no eligible reply is refused`() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(capturedSynAckWithPadding.copyOfRange(36, 40) + normalSynAck)
        try {
            buffer.takeFrame()
            throw AssertionError("expected a framing error")
        } catch (_: UsbmuxFramingException) {
        }
    }

    private fun remainder(buffer: UsbmuxCodec.FrameBuffer): ByteArray {
        val bytes = buffer.javaClass.getDeclaredField("bytes").apply { isAccessible = true }
        return bytes.get(buffer) as ByteArray
    }

    private companion object {
        // An iOS VERSION reply declares 20 bytes and sends 24.
        val versionReplyWithPadding = hex("00 00 00 00 00 00 00 14 00 00 00 02 00 00 00 00 49 28 73 00 00 00 00 00")

        // A SYN-ACK declares 36 bytes and sends 40.
        val capturedSynAckWithPadding = hex(
            "00 00 00 06 00 00 00 24 fa ce fa ce 00 00 00 01 " +
                "f2 7e 00 01 00 00 00 00 00 00 00 01 5f 12 02 00 7f 00 00 01 6d 43 6f 6e",
        )
        val normalSynAck = capturedSynAckWithPadding.copyOf(36)
    }
}
