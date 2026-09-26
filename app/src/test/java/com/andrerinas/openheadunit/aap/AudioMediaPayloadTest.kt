package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class AudioMediaPayloadTest {
    private fun message(type: Int, data: ByteArray, offset: Int = 2, size: Int = data.size) =
        AapMessage(Channel.ID_AUD, 0x0b, type, offset, size, data)

    @Test fun `two byte AAC configuration is not mistaken for a truncated timestamp`() {
        val message = message(1, byteArrayOf(0, 1, 0x11, 0x90.toByte()))
        assertEquals(2, AudioMediaPayload.offset(message))
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()),
            message.data.copyOfRange(AudioMediaPayload.offset(message), message.size))
        assertFalse(AudioMediaPayload.requiresAck(message.type))
    }

    @Test fun `extended codec configuration keeps its first eight bytes`() {
        val bytes = ByteArray(20) { it.toByte() }
        val message = message(1, bytes, size = 12)
        assertArrayEquals(bytes.copyOfRange(2, 12),
            message.data.copyOfRange(AudioMediaPayload.offset(message), message.size))
    }

    @Test fun `ordinary PCM skips exactly its timestamp and preserves the first sample`() {
        val bytes = byteArrayOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 0x44, 0x55, 0x66, 0x77)
        val message = message(0, bytes)
        assertEquals(0x0102030405060708L, AudioMediaPayload.timestampUs(message))
        assertEquals(10, AudioMediaPayload.offset(message))
        assertArrayEquals(byteArrayOf(0x44, 0x55, 0x66, 0x77), bytes.copyOfRange(10, bytes.size))
        assertTrue(AudioMediaPayload.requiresAck(message.type))
    }

    @Test fun `media offsets respect the message view rather than assuming a decrypted header`() {
        val bytes = ByteArray(20)
        bytes[13] = 42
        val message = message(0, bytes, offset = 6, size = 18)
        assertEquals(14, AudioMediaPayload.offset(message))
        assertEquals(42L, AudioMediaPayload.timestampUs(message))
    }

    @Test fun `reused buffer tail cannot make a truncated or empty message valid`() {
        for (size in 0..10) assertEquals(-1, AudioMediaPayload.offset(message(0, ByteArray(8192), size = size)))
        for (size in 0..2) assertEquals(-1, AudioMediaPayload.offset(message(1, ByteArray(8192), size = size)))
    }

    @Test fun `invalid bounds and control messages are not decoded`() {
        val bytes = ByteArray(16)
        for (offset in listOf(-1, 17, Int.MAX_VALUE)) {
            assertEquals(-1, AudioMediaPayload.offset(message(0, bytes, offset)))
        }
        assertEquals(-1, AudioMediaPayload.offset(message(0, bytes, size = 17)))
        assertEquals(-1, AudioMediaPayload.offset(message(0, bytes, size = -1)))
        for (type in listOf(2, 32768, 32769, 32770)) {
            assertFalse(AudioMediaPayload.isMedia(type))
            assertFalse(AudioMediaPayload.requiresAck(type))
            assertEquals(-1, AudioMediaPayload.offset(message(type, bytes)))
        }
    }

    @Test fun `codec configuration does not mint an extra audio flow control permit`() {
        // The phone sends CSD before acquiring permits for its first two access units.
        val types = listOf(1, 0, 0)
        assertEquals(2, types.count(AudioMediaPayload::requiresAck))
    }
}
