package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases follow DiPlay `NtpClockPeerTest`: a skewed phone moves the clock, a stranger does not. */
class NtpPacketTest {

    private val epoch = 2_208_988_800L
    private val wallMillis = 1_800_000_000_000L
    private val second = 1_000_000_000L

    private fun sync() = NtpSync(startMonoNs = 5 * second, startWallMillis = wallMillis)

    private fun seconds(ntp: Long) = ntp ushr 32

    /** The phone answers at the same instant with a clock that runs [skew] NTP units ahead. */
    private fun phoneAnswer(request: ByteArray, skew: Long, serviceNs: Long = 0, sync: NtpSync, monoNs: Long): ByteArray {
        val t1 = NtpPacket.requestTransmit(request)!!
        val phoneNow = t1 + skew
        val receive = phoneNow
        val transmit = phoneNow + (serviceNs shl 32) / second
        return NtpPacket.response(request, receive, transmit)!!
    }

    private fun exchange(sync: NtpSync, monoNs: Long, skew: Long, fromPeer: Boolean = true, delayNs: Long = 0): Boolean {
        val request = sync.nextRequest(monoNs)
        val response = phoneAnswer(request, skew, 0, sync, monoNs)
        return sync.onResponse(response, fromPeer, monoNs + delayNs)
    }

    @Test
    fun aRequestIsOneVersionByteATypeAndTheTransmitStamp() {
        val request = NtpPacket.request(0x0102030405060708L)
        assertEquals(32, request.size)
        assertEquals(0x80, request[0].toInt() and 0xff)
        assertEquals(210, request[1].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 7), request.copyOfRange(2, 4))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), request.copyOfRange(24, 32))
        assertEquals(0x0102030405060708L, NtpPacket.requestTransmit(request))
    }

    @Test
    fun aResponseEchoesTheRequestTransmitAsItsOriginate() {
        val request = NtpPacket.request(111)
        val response = NtpPacket.response(request, 222, 333)!!
        assertEquals(211, response[1].toInt() and 0xff)
        val parsed = NtpPacket.parseResponse(response)!!
        assertEquals(Triple(111L, 222L, 333L), Triple(parsed.originate, parsed.receive, parsed.transmit))
    }

    @Test
    fun aShortPacketOrTheWrongTypeIsRefused() {
        assertNull(NtpPacket.type(ByteArray(31)))
        assertNull(NtpPacket.response(ByteArray(31), 1, 2))
        assertNull(NtpPacket.response(NtpPacket.response(NtpPacket.request(1), 2, 3)!!, 1, 2))
        assertNull(NtpPacket.parseResponse(NtpPacket.request(1)))
        assertNull(NtpPacket.requestTransmit(NtpPacket.response(NtpPacket.request(1), 2, 3)!!))
    }

    @Test
    fun theClockStartsOnTheWallClockInNtpSeconds() {
        val sync = sync()
        assertEquals(wallMillis / 1000 + epoch, seconds(sync.now(5 * second)))
        assertEquals(wallMillis / 1000 + epoch + 3, seconds(sync.now(8 * second)))
        assertFalse(sync.synced)
    }

    @Test
    fun aSkewedPhoneStepsTheClockOnTheSecondExchange() {
        val sync = sync()
        val skew = 3600L shl 32
        assertFalse(exchange(sync, 6 * second, skew))
        assertEquals(wallMillis / 1000 + epoch + 1, seconds(sync.now(6 * second)))
        assertTrue(exchange(sync, 7 * second, skew))
        assertTrue(sync.synced)
        assertEquals(wallMillis / 1000 + epoch + 2 + 3600, seconds(sync.now(7 * second)))
    }

    @Test
    fun aStrangersAnswerCannotMoveTheClockAndTheNextPeerAnswerStillCounts() {
        val sync = sync()
        val skew = 3600L shl 32
        repeat(2) { assertFalse(exchange(sync, (6 + it) * second, skew, fromPeer = false)) }
        assertFalse(sync.synced)
        assertEquals(wallMillis / 1000 + epoch + 2, seconds(sync.now(7 * second)))
        val request = sync.nextRequest(8 * second)
        val answer = phoneAnswer(request, skew, 0, sync, 8 * second)
        assertFalse(sync.onResponse(answer, false, 8 * second))
        assertFalse(sync.onResponse(answer, true, 8 * second))
        assertTrue(exchange(sync, 9 * second, skew))
        assertTrue(sync.synced)
    }

    @Test
    fun anAnswerToAnotherRequestChangesNothing() {
        val sync = sync()
        val old = sync.nextRequest(6 * second)
        sync.nextRequest(7 * second)
        val answer = phoneAnswer(old, 3600L shl 32, 0, sync, 7 * second)
        assertFalse(sync.onResponse(answer, true, 7 * second))
        assertFalse(sync.onResponse(answer, true, 7 * second))
    }

    @Test
    fun aNegativeRoundTripIsDropped() {
        val sync = sync()
        val request = sync.nextRequest(6 * second)
        val t1 = NtpPacket.requestTransmit(request)!!
        // The phone claims 5 s of service time inside a round trip of 0.
        val answer = NtpPacket.response(request, t1, t1 + (5L shl 32))!!
        assertFalse(sync.onResponse(answer, true, 6 * second))
        // The pending request is spent, so a second try with a real answer needs a new request.
        assertFalse(sync.onResponse(phoneAnswer(request, 0, 0, sync, 6 * second), true, 6 * second))
    }

    @Test
    fun aSmallOffsetAfterSyncMovesTheClockByAnEighth() {
        val sync = sync()
        val step = 100L shl 32
        exchange(sync, 6 * second, step)
        exchange(sync, 7 * second, step)
        assertTrue(sync.synced)
        val before = sync.now(8 * second)
        val skew = (0.064 * 4294967296.0).toLong()
        exchange(sync, 8 * second, skew)
        assertTrue(exchange(sync, 8 * second + 1, skew))
        val moved = (sync.now(8 * second) - before).toDouble() / 4294967296.0
        assertEquals(0.008, moved, 0.0005)
    }

    @Test
    fun aGapOverTheThresholdStepsAfterSync() {
        val sync = sync()
        exchange(sync, 6 * second, 100L shl 32)
        exchange(sync, 7 * second, 100L shl 32)
        val before = sync.now(8 * second)
        val skew = (0.5 * 4294967296.0).toLong()
        exchange(sync, 8 * second, skew)
        assertTrue(exchange(sync, 8 * second + 1, skew))
        val moved = (sync.now(8 * second) - before).toDouble() / 4294967296.0
        assertEquals(0.5, moved, 0.001)
    }

    @Test
    fun aSampleWithAHigherRoundTripThanTheWindowIsNotUsed() {
        val sync = sync()
        val skew = 10L shl 32
        exchange(sync, 6 * second, skew)
        exchange(sync, 7 * second, skew)
        // The clock now agrees with the phone. A pick with a 10 ms round trip enters the window and moves the clock.
        exchange(sync, 8 * second, 0, delayNs = 10_000_000)
        assertTrue(exchange(sync, 9 * second, 0, delayNs = 10_000_000))
        val before = sync.now(12 * second)
        // A pick with a 200 ms round trip is worse than the window and is ignored.
        exchange(sync, 10 * second, 1L shl 30, delayNs = 200_000_000)
        assertFalse(exchange(sync, 11 * second, 1L shl 30, delayNs = 200_000_000))
        assertEquals(before, sync.now(12 * second))
    }

    @Test
    fun theAnswerToARequestCarriesTheSyncedClock() {
        val sync = sync()
        val request = NtpPacket.request(42)
        val answer = NtpPacket.parseResponse(sync.answer(request, 5 * second)!!)!!
        assertEquals(42L, answer.originate)
        assertEquals(sync.now(5 * second), answer.receive)
        assertEquals(sync.now(5 * second), answer.transmit)
        assertNull(sync.answer(ByteArray(10), 5 * second))
    }
}
