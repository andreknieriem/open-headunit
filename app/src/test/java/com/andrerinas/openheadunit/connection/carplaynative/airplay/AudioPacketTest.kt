package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.airplay.AudioPacketOpener.Result
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.Nonces
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout follows LIVI `livi-audio` `stream.rs` tests: a packet is sealed by the test, header bytes 4 to 12 are the AEAD data. */
class AudioPacketTest {

    private val key = ByteArray(32) { 9 }

    private fun packet(sample: Long, sequence: Int, counter: Long, plain: ByteArray): ByteArray {
        val header = byteArrayOf(
            0x80.toByte(), 0x60, (sequence shr 8).toByte(), sequence.toByte(),
            (sample shr 24).toByte(), (sample shr 16).toByte(), (sample shr 8).toByte(), sample.toByte(),
            0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(),
        )
        val sealed = CarPlayCrypto.chachaSeal(key, Nonces.counter(counter), plain, header.copyOfRange(4, 12))
        val tail = ByteArray(8) { (counter shr (8 * it)).toByte() }
        return header + sealed + tail
    }

    @Test
    fun aPacketArrivesDecryptedWithItsSampleAndSequence() {
        val opened = AudioPacketOpener(key).open(packet(4242, 17, 0, "first".toByteArray())) as Result.Opened
        assertArrayEquals("first".toByteArray(), opened.payload)
        assertEquals(4242L, opened.sample)
        assertEquals(17, opened.sequence)
    }

    @Test
    fun theNonceCounterComesFromTheTailOfEachPacket() {
        val opener = AudioPacketOpener(key)
        assertTrue(opener.open(packet(1, 0, 0x01_0203_0405, "a".toByteArray())) is Result.Opened)
        assertTrue(opener.open(packet(2, 1, 99, "b".toByteArray())) is Result.Opened)
    }

    @Test
    fun aSampleAboveTheSignedRangeStaysUnsigned() {
        val opened = AudioPacketOpener(key).open(packet(0xF000_0001L, 0, 0, ByteArray(4))) as Result.Opened
        assertEquals(0xF000_0001L, opened.sample)
    }

    @Test
    fun anEmptyPayloadStillOpens() {
        val opened = AudioPacketOpener(key).open(packet(7, 0, 0, ByteArray(0))) as Result.Opened
        assertEquals(0, opened.payload.size)
    }

    @Test
    fun aTamperedPacketFailsAndOnlyTheFirstFailureIsFlagged() {
        val opener = AudioPacketOpener(key)
        val bad = packet(1, 0, 0, "tampered".toByteArray()).also { it[AudioPacketOpener.RTP_HEADER_LEN] = (it[AudioPacketOpener.RTP_HEADER_LEN].toInt() xor 0xff).toByte() }
        assertTrue((opener.open(bad) as Result.AuthFailed).first)
        assertFalse((opener.open(bad) as Result.AuthFailed).first)
        assertTrue(opener.open(packet(2, 1, 1, "fine".toByteArray())) is Result.Opened)
    }

    @Test
    fun aChangedTimestampBreaksTheTagBecauseItIsAeadData() {
        val moved = packet(1, 0, 0, "x".toByteArray()).also { it[7] = 9 }
        assertTrue(AudioPacketOpener(key).open(moved) is Result.AuthFailed)
    }

    @Test
    fun aDatagramTooShortForHeaderAndTailIsRefused() {
        val opener = AudioPacketOpener(key)
        assertEquals(Result.TooShort, opener.open(ByteArray(35)))
        assertEquals(Result.TooShort, opener.open(ByteArray(0)))
        assertTrue(opener.open(packet(1, 0, 0, ByteArray(0))) is Result.Opened)
    }

    @Test
    fun theLengthArgumentLimitsTheRead() {
        val data = packet(3, 3, 0, "abc".toByteArray())
        val padded = data + ByteArray(100)
        assertTrue(AudioPacketOpener(key).open(padded, data.size) is Result.Opened)
        assertTrue(AudioPacketOpener(key).open(padded) is Result.AuthFailed)
    }

    @Test
    fun samplesSwapFromBigToLittleEndianAndAnOddByteStays() {
        assertArrayEquals(byteArrayOf(2, 1, 4, 3, 5), AudioPacketOpener.swapS16InPlace(byteArrayOf(1, 2, 3, 4, 5)))
        assertArrayEquals(byteArrayOf(0x34, 0x12), AudioPacketOpener.swapS16InPlace(byteArrayOf(0x12, 0x34)))
        assertEquals(0, AudioPacketOpener.swapS16InPlace(ByteArray(0)).size)
    }
}
