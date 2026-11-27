package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The first two vectors come from LIVI `ts_vectors.json` (`cipher`, `cipher2`). */
class ControlCipherTest {

    private fun key(value: Int) = ByteArray(32) { value.toByte() }

    private fun pair() = ControlCipher(key(1), key(2)) to ControlCipher(key(2), key(1))

    @Test
    fun theLiviCipherVectorsMatch() {
        val cipher = ControlCipher(key(1), key(2))
        val first = cipher.encrypt(ByteArray(16384) { 0x41 } + "tail".toByteArray())
        assertEquals(16424, first.size)
        assertEquals("d9a80f2f38ddd9c2af5f3ac536cd481e4dcfa9dd38c87b6d400289ccfc8cabd2", CarPlayCrypto.sha256(first).toHex())
        assertEquals("0600f46717c443a865be9990d158e4679a8fd58f23a1a863", cipher.encrypt("second".toByteArray()).toHex())
    }

    @Test
    fun recordsRoundTripAcrossPartialReads() {
        val (ours, theirs) = pair()
        val a = theirs.encrypt("GET /info RTSP/1.0\r\n\r\n".toByteArray())
        val b = theirs.encrypt("OPTIONS * RTSP/1.0\r\n\r\n".toByteArray())
        val stream = a + b
        val first = ours.decrypt(stream.copyOf(a.size + 5))!!
        assertEquals("GET /info RTSP/1.0\r\n\r\n", String(first.data))
        assertEquals(5, first.rest.size)
        val second = ours.decrypt(first.rest + stream.copyOfRange(a.size + 5, stream.size))!!
        assertEquals("OPTIONS * RTSP/1.0\r\n\r\n", String(second.data))
        assertEquals(0, second.rest.size)
    }

    @Test
    fun aHeaderAloneWaitsForTheRest() {
        val (ours, theirs) = pair()
        val record = theirs.encrypt("hello".toByteArray())
        val partial = ours.decrypt(record.copyOf(1))!!
        assertEquals(0, partial.data.size)
        assertArrayEquals(record.copyOf(1), partial.rest)
    }

    @Test
    fun largeMessagesSplitAt16KiB() {
        val (ours, theirs) = pair()
        val big = ByteArray(0x4000 + 10) { 0x5a }
        val sealed = theirs.encrypt(big)
        assertEquals(2 + 0x4000 + 16 + 2 + 10 + 16, sealed.size)
        assertArrayEquals(big, ours.decrypt(sealed)!!.data)
        assertEquals(2 + 16, theirs.encrypt(ByteArray(0)).size)
    }

    @Test
    fun aTamperedRecordFailsAndLeavesTheCounter() {
        val (ours, theirs) = pair()
        val sealed = theirs.encrypt("hello".toByteArray())
        val bad = sealed.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(ours.decrypt(bad))
        assertEquals("hello", String(ours.decrypt(sealed)!!.data))
    }

    @Test
    fun aRecordSentTwiceFailsTheSecondTime() {
        val (ours, theirs) = pair()
        val sealed = theirs.encrypt("hello".toByteArray())
        ours.decrypt(sealed)!!
        assertNull(ours.decrypt(sealed))
    }
}
