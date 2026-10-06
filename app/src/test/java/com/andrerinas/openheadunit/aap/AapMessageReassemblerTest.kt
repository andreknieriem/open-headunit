package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AapMessageReassemblerTest {
    private fun frame(channel: Int, flags: Int, bytes: ByteArray): AapMessage {
        val first = flags and 1 != 0
        return AapMessage(channel, flags.toByte(), if (first && bytes.size >= 2) ((bytes[0].toInt() and 255) shl 8) or
            (bytes[1].toInt() and 255) else -1, if (first) 2 else 0, bytes.size, bytes)
    }

    @Test fun `audio timestamp and payload survive every split after type`() {
        val data = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 7, 0x12, 0x34, 0x56)
        for (split in 1 until data.size) {
            val r = AapMessageReassembler()
            val first = data.copyOfRange(0, split)
            assertNull(r.accept(frame(Channel.ID_AUD, 9, first), data.size))
            first.fill(0x7f) // The TLS buffer may be reused immediately after accept.
            val done = r.accept(frame(Channel.ID_AUD, 10, data.copyOfRange(split, data.size)), 0)!!
            assertArrayEquals(data, done.data)
            assertEquals(0, done.type)
            assertEquals(2, done.dataOffset)
            assertEquals(11.toByte(), done.flags)
        }
    }

    @Test fun `control fragments interleave with audio without parsing continuation as a type`() {
        val r = AapMessageReassembler()
        assertNull(r.accept(frame(Channel.ID_AUD, 13, byteArrayOf(0, 7)), 4))
        assertTrue(AapMessageFraming.carriesTotalLength(13))
        assertFalse(AapMessageFraming.carriesTotalLength(15))
        val single = frame(0, 11, byteArrayOf(0, 11, 8, 1))
        assertSame(single, r.accept(single, 0))
        val done = r.accept(frame(Channel.ID_AUD, 14, byteArrayOf(8, 1)), 0)!!
        assertArrayEquals(byteArrayOf(0, 7, 8, 1), done.data)
        assertEquals(15.toByte(), done.flags)
        assertNull(r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(0)), 0))
    }

    @Test fun `video retains streamed bytes and inherits type only from its first fragment`() {
        val r = AapMessageReassembler()
        r.accept(frame(Channel.ID_VID, 9, ByteArray(15)), 16)
        val bytes = byteArrayOf(0x7f)
        val last = r.accept(frame(Channel.ID_VID, 10, bytes), 0)!!
        assertSame(bytes, last.data)
        assertEquals(0, last.type)
        assertEquals(0, last.dataOffset)
    }

    @Test fun `video whose timestamp spans fragments is completed before the legacy decoder sees it`() {
        val r = AapMessageReassembler()
        assertNull(r.accept(frame(Channel.ID_VID, 9, byteArrayOf(0, 0)), 15))
        val done = r.accept(frame(Channel.ID_VID, 10, ByteArray(13)), 0)!!
        assertEquals(15, done.size)
        assertEquals(11.toByte(), done.flags)
        assertEquals(2, done.dataOffset)
    }

    @Test fun `empty continuation has no type and high channel ids stay unsigned`() {
        val header = AapMessageIncoming.EncryptedHeader()
        header.buf = byteArrayOf(0xff.toByte(), 10, 0, 0)
        header.decode()
        val message = AapMessageIncoming(header, ByteArrayWithLimit(ByteArray(0), 0))
        assertEquals(255, message.channel)
        assertEquals(-1, message.type)
        assertEquals(0, message.dataOffset)
    }

    @Test fun `DATA is acknowledged once at completion and CSD consumes no permit`() {
        assertFalse(AapMessageFraming.completesMediaData(0, 9))
        assertFalse(AapMessageFraming.completesMediaData(0, 8))
        assertTrue(AapMessageFraming.completesMediaData(0, 10))
        assertTrue(AapMessageFraming.completesMediaData(0, 11))
        assertFalse(AapMessageFraming.completesMediaData(1, 11))
    }

    @Test(expected = IOException::class) fun `rejects incomplete audio`() {
        val r = AapMessageReassembler()
        r.accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 0)), 8)
        r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(1)), 0)
    }

    @Test(expected = IOException::class) fun `rejects oversized declarations before allocation`() {
        AapMessageReassembler().accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 0)), Int.MAX_VALUE)
    }

    @Test fun `a routing change abandons the old run and permits a fresh message`() {
        val r = AapMessageReassembler()
        r.accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 0)), 4)
        assertThrows(IOException::class.java) {
            r.accept(frame(Channel.ID_AUD, 14, byteArrayOf(1, 2)), 0)
        }
        assertNull(r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(1, 2)), 0))
        assertNull(r.accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 0)), 4))
        assertArrayEquals(byteArrayOf(0, 0, 3, 4),
            r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(3, 4)), 0)!!.data)
    }

    @Test fun `an overlong copied message cannot consume the following message`() {
        val r = AapMessageReassembler()
        r.accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 0)), 3)
        assertThrows(IOException::class.java) {
            r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(1, 2)), 0)
        }
        val next = frame(Channel.ID_AUD, 11, byteArrayOf(0, 0, 7))
        assertSame(next, r.accept(next, 0))
    }

    @Test fun `short video prefix owns bytes while interleaved channels reuse TLS storage`() {
        val r = AapMessageReassembler()
        val backing = byteArrayOf(0, 0)
        assertNull(r.accept(frame(Channel.ID_VID, 9, backing), 4))
        backing.fill(0x7f)
        assertNull(r.accept(frame(Channel.ID_AUD, 9, byteArrayOf(0, 1)), 3))
        assertArrayEquals(byteArrayOf(0, 0, 3, 4),
            r.accept(frame(Channel.ID_VID, 10, byteArrayOf(3, 4)), 0)!!.data)
        assertArrayEquals(byteArrayOf(0, 1, 5),
            r.accept(frame(Channel.ID_AUD, 10, byteArrayOf(5)), 0)!!.data)
    }

    @Test fun `large declarations reserve only received bytes across all channels`() {
        val r = AapMessageReassembler()
        for (channel in 0..255) {
            assertNull(r.accept(frame(channel, 9, byteArrayOf(0, 7)), AapMessageReassembler.MAX_MESSAGE_BYTES))
        }
        // Replacing one run must release its actual allocation, not its declared total.
        assertNull(r.accept(frame(Channel.ID_MPB, 9, byteArrayOf(0, 7)), 3))
        assertArrayEquals(byteArrayOf(0, 7, 42),
            r.accept(frame(Channel.ID_MPB, 10, byteArrayOf(42)), 0)!!.data)
    }

    @Test fun `copied buffer growth preserves album art and ignores TLS backing capacity`() {
        val bytes = ByteArray(1024 * 1024 + 17) { (it % 251).toByte() }
        bytes[0] = 0; bytes[1] = 7
        val r = AapMessageReassembler()
        var offset = 0
        while (offset < bytes.size) {
            val length = minOf(16124, bytes.size - offset)
            val flags = if (offset == 0) 9 else if (offset + length == bytes.size) 10 else 8
            val backing = bytes.copyOfRange(offset, offset + length) + ByteArray(100) { 0x7f }
            val fragment = frame(Channel.ID_MPB, flags, backing)
            val done = r.accept(AapMessage(fragment.channel, fragment.flags, fragment.type,
                fragment.dataOffset, length, backing), if (offset == 0) bytes.size else 0)
            if (flags == 10) assertArrayEquals(bytes, done!!.data) else assertNull(done)
            backing.fill(0)
            offset += length
        }
    }

}
