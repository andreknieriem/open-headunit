package com.andrerinas.openheadunit.connection.carplaynative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EcdsaSignatureTest {

    private fun der(r: ByteArray, s: ByteArray): ByteArray {
        fun int(v: ByteArray) = byteArrayOf(0x02, v.size.toByte()) + v
        val body = int(r) + int(s)
        return byteArrayOf(0x30, body.size.toByte()) + body
    }

    private val s32 = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `a short r is padded on the left`() {
        val r = byteArrayOf(0x11, 0x22, 0x33)
        val raw = EcdsaSignature.derToRaw(der(r, s32))
        assertEquals(64, raw.size)
        assertArrayEquals(ByteArray(29) + r, raw.copyOfRange(0, 32))
        assertArrayEquals(s32, raw.copyOfRange(32, 64))
    }

    @Test
    fun `a leading zero byte is dropped`() {
        val r = byteArrayOf(0x00, 0x80.toByte()) + ByteArray(31) { 0x55 }
        val raw = EcdsaSignature.derToRaw(der(r, s32))
        assertArrayEquals(r.copyOfRange(1, 33), raw.copyOfRange(0, 32))
    }

    @Test
    fun `a full length value is kept as it is`() {
        val r = ByteArray(32) { 0x7f }
        val raw = EcdsaSignature.derToRaw(der(r, r))
        assertArrayEquals(r + r, raw)
    }

    @Test
    fun `raw converts back to the same DER`() {
        val r = byteArrayOf(0x00, 0x00, 0x7f) + ByteArray(29) { 0x01 }
        val s = byteArrayOf(0x80.toByte()) + ByteArray(31) { 0x02 }
        val raw = r + s
        val der = EcdsaSignature.rawToDer(raw)
        assertArrayEquals(raw, EcdsaSignature.derToRaw(der))
        // r loses its two zero bytes (30 left); s gains a zero byte for its high bit (33).
        assertEquals(2 + (2 + 30) + (2 + 33), der.size)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a value longer than 32 bytes is refused`() {
        EcdsaSignature.derToRaw(der(ByteArray(33) { 0x01 }, s32))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `bytes that are no signature are refused`() {
        EcdsaSignature.derToRaw(byteArrayOf(1, 2, 3))
    }
}
