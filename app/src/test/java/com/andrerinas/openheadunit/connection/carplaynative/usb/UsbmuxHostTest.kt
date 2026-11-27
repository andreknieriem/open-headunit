package com.andrerinas.openheadunit.connection.carplaynative.usb

import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class UsbmuxHostTest {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }
    private val phone = FakeIphone()
    private lateinit var link: IphoneUsbLink
    private lateinit var host: UsbmuxHost

    private fun start(split: Int = 0): UsbmuxHost {
        phone.readSplit = split
        host = UsbmuxHost({ link.write(it) }, clock)
        link = IphoneUsbLink(phone, clock, { host.onBytes(it) }, { host.onLinkEnded(it) })
        link.start()
        host.begin(2000)
        return host
    }

    @After fun tearDown() {
        if (::host.isInitialized) host.close()
        if (::link.isInitialized) link.close()
        assertTrue("fake iPhone threads ended", phone.joinThreads())
    }

    private fun echo(port: Int) = phone.listen(port) { s ->
        while (true) { val chunk = s.recv(65536, 2000) ?: break; if (chunk.isEmpty()) break; s.send(chunk) }
    }

    @Test fun handshakeSendsVersionThenSetup() {
        start()
        assertEquals(1, phone.versionRequests.get())
        val frames = phone.written.toList()
        assertEquals(20, frames[0].size)
        assertEquals(UsbmuxCodec.PROTOCOL_SETUP, UsbmuxCodec.readU32(frames[1], 0))
    }

    @Test fun aVersionReplySplitIntoSmallReadsStillCompletesTheHandshake() {
        start(split = 3)
    }

    @Test fun noVersionReplyTimesOut() {
        val silent = FakeIphone()
        val h = UsbmuxHost({ silent.write(it, it.size, 1000) }, clock)
        val l = IphoneUsbLink(silent, clock, { }, { })
        silent.control = { _, _ -> -1 }
        try {
            h.begin(300); fail()
        } catch (_: IphoneUsbException.TimedOut) {
        } finally { h.close(); l.close() }
    }

    @Test fun aStreamEchoesAcrossSplitReads() {
        echo(4000)
        start(split = 7)
        val stream = host.connect(4000, 2000)
        stream.send(ByteArray(40000) { it.toByte() })
        var got = ByteArray(0)
        while (got.size < 40000) got += stream.recv(65536, 2000)!!
        assertArrayEquals(ByteArray(40000) { it.toByte() }, got)
        stream.close()
    }

    @Test fun twoStreamsRunSideBySide() {
        echo(4000); echo(4001)
        start()
        val a = host.connect(4000, 2000)
        val b = host.connect(4001, 2000)
        a.send(byteArrayOf(1)); b.send(byteArrayOf(2))
        assertArrayEquals(byteArrayOf(1), a.recv(10, 2000))
        assertArrayEquals(byteArrayOf(2), b.recv(10, 2000))
        a.close(); b.close()
    }

    @Test fun aSendIsCutIntoSegmentsOf16384() {
        phone.listen(4000) { s -> var total = 0; while (total < 40000) { val c = s.recv(65536, 2000) ?: break; total += c.size } }
        start()
        val stream = host.connect(4000, 2000)
        stream.send(ByteArray(40000))
        stream.close()
        // The link cuts each bulk write at 16384, so the frames are read back from the joined writes.
        val buffer = UsbmuxCodec.FrameBuffer()
        phone.written.toList().drop(1).forEach { buffer.append(it) }
        val sizes = generateSequence { buffer.takeFrame() }
            .filter { it.protocol == UsbmuxCodec.PROTOCOL_TCP && it.payload.size > 20 }.map { it.payload.size - 20 }.toList()
        assertEquals(listOf(16384, 16384, 7232), sizes)
    }

    @Test fun aRecvThatSplitsAReplyKeepsTheRest() {
        echo(4000)
        start()
        val stream = host.connect(4000, 2000)
        stream.send(byteArrayOf(1, 2, 3, 4, 5))
        assertArrayEquals(byteArrayOf(1, 2), stream.recv(2, 2000))
        assertArrayEquals(byteArrayOf(3, 4, 5), stream.recv(10, 2000))
        stream.close()
    }

    @Test fun recvWithNothingToReadReturnsNullAtTheDeadline() {
        echo(4000)
        start()
        val stream = host.connect(4000, 2000)
        assertNull(stream.recv(10, 100))
        stream.close()
    }

    @Test fun aPortNobodyListensOnIsRefused() {
        start()
        try { host.connect(4999, 1000); fail() } catch (_: IphoneUsbException.DeviceUnavailable) { }
    }

    @Test fun aConnectTheIphoneNeverAnswersTimesOut() {
        val h = UsbmuxHost({}, clock)
        try { h.connect(4000, 150); fail() } catch (_: IphoneUsbException.TimedOut) { } finally { h.close() }
    }

    @Test fun theIphoneClosingEndsRecvWithAnEmptyArray() {
        phone.listen(4000) { s -> s.send(byteArrayOf(9)); s.close() }
        start()
        val stream = host.connect(4000, 2000)
        assertArrayEquals(byteArrayOf(9), stream.recv(10, 2000))
        assertEquals(0, stream.recv(10, 2000)!!.size)
        stream.close()
    }

    @Test fun closingTheHostEndsEveryStreamAndAPendingRecv() {
        echo(4000)
        start()
        val stream = host.connect(4000, 2000)
        val result = java.util.concurrent.CompletableFuture<ByteArray?>()
        Thread { result.complete(stream.recv(10, 5000)) }.start()
        Thread.sleep(100)
        host.close()
        assertEquals(0, result.get(2, TimeUnit.SECONDS)!!.size)
        try { stream.send(byteArrayOf(1)); fail() } catch (_: IphoneUsbException) { }
        try { host.connect(4000, 100); fail() } catch (_: IphoneUsbException) { }
    }

    @Test fun aBrokenFrameFailsTheHostAndItsStreams() {
        echo(4000)
        start()
        val stream = host.connect(4000, 2000)
        phone.sendToHost(ByteArray(40) { 0x7f })
        try {
            val deadline = System.currentTimeMillis() + 2000
            while (System.currentTimeMillis() < deadline) { stream.recv(10, 100) }
            fail()
        } catch (_: IphoneUsbException.Protocol) { }
    }

    @Test fun theLinkEndingFailsTheStreams() {
        echo(4000)
        start()
        val stream = host.connect(4000, 2000)
        host.onLinkEnded("USB gone")
        try { stream.recv(10, 500); fail() } catch (e: IphoneUsbException.DeviceUnavailable) { assertEquals("USB gone", e.message) }
        assertNotNull(stream)
    }

    private fun beginEndsWhen(end: (UsbmuxHost) -> Unit) {
        val h = UsbmuxHost({ }, clock)
        val error = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val waiter = Thread { try { h.begin(10_000) } catch (e: Throwable) { error.set(e) } }.apply { start() }
        Thread.sleep(100)
        val started = System.nanoTime()
        end(h)
        waiter.join(2000)
        assertTrue("begin returned at once", !waiter.isAlive && (System.nanoTime() - started) / 1_000_000 < 1000)
        assertTrue(error.get() is IphoneUsbException.DeviceUnavailable)
    }

    @Test fun closeWakesABeginThatWaitsForTheVersionReply() = beginEndsWhen { it.close() }

    @Test fun theLinkEndWakesABeginThatWaitsForTheVersionReply() = beginEndsWhen { it.onLinkEnded("unplugged") }
}
