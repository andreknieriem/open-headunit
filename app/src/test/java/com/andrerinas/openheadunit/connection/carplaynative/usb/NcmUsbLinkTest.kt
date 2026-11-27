package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NcmUsbLinkTest {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private class Pipe(val reads: MutableList<ByteArray> = ArrayList()) : NcmPipe {
        val writes = ArrayList<Pair<Int, Int>>()
        val closes = AtomicInteger()
        var accept = true
        // An unplugged pipe: every read fails at once.
        var dead = false
        val maxRead = ArrayList<Int>()
        override fun read(buffer: ByteArray, timeoutMs: Int): Int {
            maxRead.add(buffer.size)
            if (dead) return -1
            // An idle wire answers -1 after the whole timeout.
            if (reads.isEmpty()) { Thread.sleep(timeoutMs.toLong()); return -1 }
            val next = reads.removeAt(0)
            next.copyInto(buffer)
            return next.size
        }
        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int): Int { writes.add(length to timeoutMs); return if (accept) length else -1 }
        override fun close() { closes.incrementAndGet() }
    }

    private fun frame(size: Int) = ByteArray(size) { it.toByte() }

    @Test fun aFrameSplitAcrossReadsComesOutWhole() {
        val block = Ntb16Codec.build(frame(1400), 0)
        val pipe = Pipe(mutableListOf(block.copyOf(500), block.copyOfRange(500, 1000), block.copyOfRange(1000, block.size)))
        val link = NcmUsbLink(pipe, 512, clock)
        assertArrayEquals(frame(1400), link.recv(2000))
    }

    @Test fun twoFramesInOneReadAreReturnedOneAtATime() {
        val pipe = Pipe(mutableListOf(Ntb16Codec.build(frame(40), 0) + Ntb16Codec.build(frame(60), 1)))
        val link = NcmUsbLink(pipe, 512, clock)
        assertEquals(40, link.recv(2000)!!.size)
        assertEquals(60, link.recv(2000)!!.size)
    }

    @Test fun silenceReturnsNullAtTheDeadline() {
        val link = NcmUsbLink(Pipe(), 512, clock)
        val started = System.nanoTime()
        assertNull(link.recv(150))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 100)
    }

    @Test fun anUnpluggedPipeEndsTheLinkAfterFiveFastFailuresAndDoesNotSpin() {
        val pipe = Pipe().apply { dead = true }
        val link = NcmUsbLink(pipe, 512, clock)
        val started = System.nanoTime()
        try { link.recv(1000); org.junit.Assert.fail() } catch (e: IphoneUsbException.DeviceUnavailable) {
            assertEquals("NCM read failed 5 times in a row", e.message)
        }
        assertEquals(UsbReadPolicy.MAX_FAST_FAILURES, pipe.maxRead.size)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 500)
    }

    @Test fun readsAskForNoMoreThan16384Bytes() {
        val pipe = Pipe()
        NcmUsbLink(pipe, 512, clock).recv(50)
        assertTrue(pipe.maxRead.isNotEmpty())
        assertTrue(NcmUsbLink.READ_BYTES == 16384)
    }

    @Test fun sendWrapsTheFrameInOneBlockWithAnIncreasingSequence() {
        val pipe = Pipe()
        val link = NcmUsbLink(pipe, 512, clock)
        assertTrue(link.send(frame(100), 2000))
        assertTrue(link.send(frame(100), 2000))
        assertEquals(listOf(128, 128), pipe.writes.map { it.first })
    }

    @Test fun untilTheFirstBlockIsAcceptedAWriteWaitsOnlyTwentyMilliseconds() {
        val pipe = Pipe().apply { accept = false }
        val link = NcmUsbLink(pipe, 512, clock)
        assertFalse(link.send(frame(100), 2000))
        assertFalse(link.send(frame(100), 2000))
        assertEquals(listOf(20, 20), pipe.writes.map { it.second })
        pipe.accept = true
        assertTrue(link.send(frame(100), 2000))
        assertTrue(link.send(frame(100), 2000))
        assertEquals(2000, pipe.writes.last().second)
    }

    @Test fun closeReleasesThePipeOnceAndStopsTheLink() {
        val pipe = Pipe()
        val link = NcmUsbLink(pipe, 512, clock)
        link.close(); link.close()
        assertEquals(1, pipe.closes.get())
        assertNull(link.recv(50))
        try { link.send(frame(10), 100); org.junit.Assert.fail() } catch (_: IphoneUsbException.DeviceUnavailable) { }
    }
}
