package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IphoneUsbLinkTest {
    /** Each reading is 600 ms after the last, so a -1 read looks like an idle timeout. */
    private fun idleClock(): () -> Long { val t = java.util.concurrent.atomic.AtomicLong(); return { t.addAndGet(600) } }

    /** A clock that never moves, so a -1 read looks like a dead pipe. */
    private val clock: () -> Long = { 0L }

    private class ScriptedPipe(val reads: (Int) -> Int = { -1 }) : IphoneUsbPipe {
        val writes = ArrayList<Int>()
        val controls = ArrayList<Int>()
        val closes = AtomicInteger()
        var control: (Int, ByteArray) -> Int = { _, _ -> -1 }
        var writeResult: (Int) -> Int = { it }
        private val readCount = AtomicInteger()
        override fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int): Int {
            controls.add(request); return control(request, buffer)
        }
        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int { writes.add(length); return writeResult(length) }
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            val n = reads(readCount.getAndIncrement())
            if (n > 0) for (i in 0 until n) buffer[i] = i.toByte()
            if (n == -2) { Thread.sleep(20); return -1 }
            return n
        }
        override fun close() { closes.incrementAndGet() }
    }

    @Test fun anAcceptedModeRequestSendsOnlyRequest0x52() {
        val pipe = ScriptedPipe().apply { control = { _, _ -> 1 } }
        assertTrue(IphoneUsbLink.requestMode(pipe) is IphoneModeAnswer.Accepted)
        assertEquals(listOf(0x52), pipe.controls)
    }

    @Test fun aRefusalOfMinusOneZeroOrThreeBytesReadsTheModeAndNamesIt() {
        for (answer in listOf(-1, 0, 3)) {
            val pipe = ScriptedPipe().apply {
                control = { request, buffer -> if (request == 0x52) answer else { buffer[0] = 5; buffer[1] = 3; buffer[2] = 3; 3 } }
            }
            val refused = IphoneUsbLink.requestMode(pipe) as IphoneModeAnswer.Refused
            assertEquals("CarPlayNative: the iPhone refused wired CarPlay mode (mode 050303)", refused.line)
            assertEquals(listOf(0x52, 0x45), pipe.controls)
        }
    }

    @Test fun aRefusalWithAnUnreadableModeStillSaysSo() {
        val pipe = ScriptedPipe().apply { control = { _, _ -> -1 } }
        val refused = IphoneUsbLink.requestMode(pipe) as IphoneModeAnswer.Refused
        assertEquals("CarPlayNative: the iPhone refused wired CarPlay mode (mode )", refused.line)
    }

    @Test fun writesGoOutInChunksOf16384() {
        val pipe = ScriptedPipe()
        val link = IphoneUsbLink(pipe, clock, {}, {})
        link.write(ByteArray(16384 * 2 + 10))
        assertEquals(listOf(16384, 16384, 10), pipe.writes)
        link.close()
    }

    @Test fun aShortWriteFailsAndIsNotRetriedSilently() {
        val pipe = ScriptedPipe().apply { writeResult = { -1 } }
        val link = IphoneUsbLink(pipe, clock, {}, {})
        try { link.write(ByteArray(5)); org.junit.Assert.fail() } catch (_: IphoneUsbException.DeviceUnavailable) { }
        link.close()
    }

    @Test fun aWriteAfterCloseFails() {
        val link = IphoneUsbLink(ScriptedPipe(), clock, {}, {})
        link.close()
        try { link.write(ByteArray(5)); org.junit.Assert.fail() } catch (_: IphoneUsbException.DeviceUnavailable) { }
    }

    @Test fun aMinusOneReadWithTheLinkOpenIsRetriedAndDataStillArrives() {
        val got = CountDownLatch(1)
        var received: ByteArray? = null
        val pipe = ScriptedPipe(reads = { n -> if (n < 3) -2 else if (n == 3) 4 else -2 })
        val link = IphoneUsbLink(pipe, idleClock(), { received = it; got.countDown() }, {})
        link.start()
        assertTrue(got.await(3, TimeUnit.SECONDS))
        assertArrayEquals(byteArrayOf(0, 1, 2, 3), received)
        link.close()
    }

    @Test fun readsThatFailAtOnceEndTheLinkOnce() {
        val ended = CountDownLatch(1)
        val endings = AtomicInteger()
        val link = IphoneUsbLink(ScriptedPipe(reads = { -1 }), clock, {}, { endings.incrementAndGet(); ended.countDown() })
        link.start()
        assertTrue(ended.await(3, TimeUnit.SECONDS))
        link.close()
        assertEquals(1, endings.get())
    }

    @Test fun closeEndsTheReaderAndIsIdempotent() {
        // Only this test's reader counts: other tests' threads share the name.
        val baseline = Thread.getAllStackTraces().keys.toSet()
        val pipe = ScriptedPipe(reads = { -2 })
        val ended = AtomicInteger()
        val link = IphoneUsbLink(pipe, idleClock(), {}, { ended.incrementAndGet() })
        link.start()
        link.close()
        link.close()
        assertEquals(1, pipe.closes.get())
        assertEquals(0, ended.get())
        assertFalse(Thread.getAllStackTraces().keys.any { it !in baseline && it.name == "carplay-usb-reader" && it.isAlive })
    }

    @Test fun aLinkIsSingleUse() {
        val link = IphoneUsbLink(ScriptedPipe(reads = { -2 }), idleClock(), {}, {})
        link.start()
        try { link.start(); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
        link.close()
    }
}
