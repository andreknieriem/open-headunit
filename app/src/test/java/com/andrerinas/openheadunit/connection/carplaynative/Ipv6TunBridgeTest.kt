package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetIpv6
import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetLink
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bridge over two pipe pairs: a fake NCM link and a fake tunnel. */
class Ipv6TunBridgeTest {
    private val hostMac = byteArrayOf(2, 0, 0, 0, 0, 2)
    private val peer = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x9a.toByte(), 0xbc.toByte())

    private class FakeLink : EthernetLink {
        val inbound = LinkedBlockingQueue<ByteArray>()
        val sent = CopyOnWriteArrayList<ByteArray>()
        val closes = AtomicInteger()
        override fun recv(timeoutMs: Long): ByteArray? = inbound.poll(minOf(timeoutMs, 50), TimeUnit.MILLISECONDS)
        override fun send(frame: ByteArray, timeoutMs: Int): Boolean { sent.add(frame); return true }
        override fun close() { closes.incrementAndGet() }
    }

    private class FakeTun : TunPort {
        val outbound = LinkedBlockingQueue<ByteArray>()
        val written = CopyOnWriteArrayList<ByteArray>()
        val closes = AtomicInteger()
        @Volatile var closed = false
        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                val next = outbound.poll(20, TimeUnit.MILLISECONDS) ?: continue
                if (next.isEmpty()) return -1
                next.copyInto(buffer); return next.size
            }
            return -1
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { written.add(buffer.copyOfRange(offset, offset + length)) }
        override fun close() { closed = true; closes.incrementAndGet() }
    }

    private fun v6(last: Int) = ByteArray(16).also { it[0] = 0xfe.toByte(); it[1] = 0x80.toByte(); it[15] = last.toByte() }

    private fun tcp(dst: ByteArray): ByteArray {
        val p = ByteArray(60)
        p[0] = 0x60; p[5] = 20; p[6] = 6; p[7] = 64
        v6(2).copyInto(p, 8); dst.copyInto(p, 24)
        return p
    }

    private fun naWithoutOption(): ByteArray {
        val p = ByteArray(64)
        p[0] = 0x60; p[5] = 24; p[6] = 58
        v6(2).copyInto(p, 8); v6(1).copyInto(p, 24); p[40] = 136.toByte(); p[44] = 0x60
        return p
    }

    private fun waitFor(condition: () -> Boolean): Boolean {
        val end = System.nanoTime() + 2_000_000_000L
        while (System.nanoTime() < end) { if (condition()) return true; Thread.sleep(5) }
        return condition()
    }

    private class Harness(mac: ByteArray) {
        val link = FakeLink()
        val tun = FakeTun()
        val first = AtomicInteger()
        val errors = CopyOnWriteArrayList<String>()
        val bridge = Ipv6TunBridge(link, tun, mac, { first.incrementAndGet() }, { errors.add(it) })
    }

    @Test fun anOutboundNeighborAdvertisementGainsTheTargetOptionOnTheWire() {
        val h = Harness(hostMac)
        h.bridge.start()
        h.link.inbound.add(EthernetIpv6.build(peer, hostMac, tcp(v6(2))))
        assertTrue(waitFor { h.tun.written.isNotEmpty() })
        h.tun.outbound.add(naWithoutOption())
        assertTrue(waitFor { h.link.sent.isNotEmpty() })
        val ipv6 = h.link.sent[0].copyOfRange(14, h.link.sent[0].size)
        assertEquals(72, ipv6.size)
        assertArrayEquals(hostMac, ipv6.copyOfRange(66, 72))
        h.bridge.close()
    }

    @Test fun noUnicastGoesOutBeforeThePeerIsKnownAndTheWaitLogsOnce() {
        val h = Harness(hostMac)
        h.bridge.start()
        repeat(3) { h.tun.outbound.add(tcp(v6(1))) }
        Thread.sleep(150)
        assertTrue(h.link.sent.isEmpty())
        h.link.inbound.add(EthernetIpv6.build(peer, hostMac, tcp(v6(2))))
        assertTrue(waitFor { h.tun.written.isNotEmpty() })
        h.tun.outbound.add(tcp(v6(1)))
        assertTrue(waitFor { h.link.sent.size == 1 })
        assertArrayEquals(peer, h.link.sent[0].copyOfRange(0, 6))
        h.bridge.close()
    }

    @Test fun multicastGoesOutAtOnce() {
        val h = Harness(hostMac)
        h.bridge.start()
        val dst = ByteArray(16).also { it[0] = 0xff.toByte(); it[1] = 2; it[15] = 1 }
        h.tun.outbound.add(tcp(dst))
        assertTrue(waitFor { h.link.sent.size == 1 })
        assertArrayEquals(byteArrayOf(0x33, 0x33, 0, 0, 0, 1), h.link.sent[0].copyOfRange(0, 6))
        h.bridge.close()
    }

    @Test fun theFirstInboundFrameIsReportedOnceAndItsPacketReachesTheTunnel() {
        val h = Harness(hostMac)
        h.bridge.start()
        val packet = tcp(v6(2))
        repeat(3) { h.link.inbound.add(EthernetIpv6.build(peer, hostMac, packet)) }
        assertTrue(waitFor { h.tun.written.size == 3 })
        assertEquals(1, h.first.get())
        assertArrayEquals(packet, h.tun.written[0])
        h.bridge.close()
    }

    @Test fun aFrameThatIsNotIpv6IsDropped() {
        val h = Harness(hostMac)
        h.bridge.start()
        h.link.inbound.add(ByteArray(60))
        Thread.sleep(150)
        assertTrue(h.tun.written.isEmpty())
        assertEquals(0, h.first.get())
        h.bridge.close()
    }

    @Test fun theTunnelEndingReportsAnError() {
        val h = Harness(hostMac)
        h.bridge.start()
        h.tun.outbound.add(ByteArray(0))
        assertTrue(waitFor { h.errors.isNotEmpty() })
        h.bridge.close()
    }

    @Test fun closeStopsBothThreadsClosesTunnelThenLinkAndIsIdempotent() {
        // Only this test's threads count: other tests' bridges share the names.
        val baseline = Thread.getAllStackTraces().keys.toSet()
        val h = Harness(hostMac)
        h.bridge.start()
        h.bridge.close()
        h.bridge.close()
        assertEquals(1, h.tun.closes.get())
        assertEquals(1, h.link.closes.get())
        assertTrue(h.errors.isEmpty())
        assertTrue(waitFor { Thread.getAllStackTraces().keys.none { it.isAlive && it !in baseline && (it.name == "carplay-ncm-in" || it.name == "carplay-ncm-out") } })
        try { h.bridge.start(); org.junit.Assert.fail() } catch (_: IllegalStateException) { }
    }
}
