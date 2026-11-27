package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetIpv6
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunProbePacket
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CarPlayTunnelCheckTest {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /** A tunnel that answers the probe SYN as the kernel would, or stays silent. */
    private class FakeTun(private val answer: Boolean) : TunPort {
        val inbound = LinkedBlockingQueue<ByteArray>()
        val closes = AtomicInteger()
        val writes = AtomicInteger()
        @Volatile var closed = false
        override fun read(buffer: ByteArray): Int {
            while (!closed) {
                val next = inbound.poll(10, TimeUnit.MILLISECONDS) ?: continue
                next.copyInto(buffer); return next.size
            }
            return -1
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            writes.incrementAndGet()
            if (answer) inbound.add(synAckFor(buffer.copyOfRange(offset, offset + length)))
        }
        override fun close() { closed = true; closes.incrementAndGet() }
    }

    private class FakeControl(val dummyUp: Boolean = false, val carPlayUp: Boolean = false, val prepared: Boolean = true, val tun: TunPort? = null, val failure: String? = null, val silent: Boolean = false) : TunnelControl {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        override fun isDummyVpnUp() = dummyUp
        override fun isCarPlayTunRunning() = carPlayUp
        override fun isPrepared() = prepared
        override fun start(onReady: (TunPort) -> Unit, onFailed: (String) -> Unit) {
            starts.incrementAndGet()
            if (silent) return
            Thread { if (failure != null) onFailed(failure) else onReady(tun!!) }.start()
        }
        override fun stop() { stops.incrementAndGet() }
    }

    private class FakeListeners(val fail: Boolean = false) : ProbeListenerFactory {
        val closes = AtomicInteger()
        override fun bind(): ProbeListener {
            if (fail) throw IOException("no socket")
            return object : ProbeListener { override val port = 5555; override fun close() { closes.incrementAndGet() } }
        }
    }

    private companion object {
        fun synAckFor(syn: ByteArray): ByteArray {
            val p = syn.copyOf()
            TunProbePacket.TUN_ADDRESS.copyInto(p, EthernetIpv6.IPV6_SOURCE_OFFSET)
            TunProbePacket.PEER_ADDRESS.copyInto(p, EthernetIpv6.IPV6_DESTINATION_OFFSET)
            val tcp = EthernetIpv6.IPV6_HEADER_BYTES
            val srcPort = p.copyOfRange(tcp, tcp + 2); val dstPort = p.copyOfRange(tcp + 2, tcp + 4)
            dstPort.copyInto(p, tcp); srcPort.copyInto(p, tcp + 2)
            val ack = TunProbePacket.INITIAL_SEQUENCE + 1
            for (i in 0..3) p[tcp + 8 + i] = (ack ushr (24 - i * 8)).toByte()
            p[tcp + 13] = 0x12
            p[tcp + 16] = 0; p[tcp + 17] = 0
            val sum = EthernetIpv6.upperLayerChecksum(p, 6, tcp, 20)
            p[tcp + 16] = (sum ushr 8).toByte(); p[tcp + 17] = sum.toByte()
            return p
        }
    }

    @Test fun aTunnelThatAnswersTheSynPasses() {
        val tun = FakeTun(answer = true)
        val control = FakeControl(tun = tun)
        val listeners = FakeListeners()
        val result = CarPlayTunnelCheck(control, listeners, clock, { false }).run()
        assertTrue(result is CarPlayTunnelCheck.Result.Ok)
        assertEquals("CarPlayNative: tunnel check ok (SYN-ACK from fe80::2)", result.line)
        assertEquals(1, tun.closes.get()); assertEquals(1, control.stops.get()); assertEquals(1, listeners.closes.get())
    }

    @Test fun aTunnelThatStaysSilentFailsAfterThreeSecondsAndClosesEverything() {
        val tun = FakeTun(answer = false)
        val control = FakeControl(tun = tun)
        val started = System.nanoTime()
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run()
        assertEquals("CarPlayNative: tunnel check failed (SYN-ACK: no answer in 3 s)", result.line)
        val seconds = (System.nanoTime() - started) / 1_000_000_000.0
        assertTrue("took $seconds s", seconds in 2.9..5.0)
        assertEquals(1, tun.writes.get())
        assertEquals(1, tun.closes.get()); assertEquals(1, control.stops.get())
    }

    @Test fun theDummyVpnRefusesTheCheckWithNoEstablish() {
        val control = FakeControl(dummyUp = true, tun = FakeTun(true))
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run()
        assertEquals("CarPlayNative: refused, the dummy VPN holds the VPN slot", result.line)
        assertEquals(0, control.starts.get())
    }

    @Test fun anAndroidAutoSessionRefusesTheCheckFirstWithItsOwnLine() {
        val control = FakeControl(dummyUp = true, carPlayUp = true, tun = FakeTun(true))
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { true }).run()
        assertEquals("CarPlayNative: refused, an Android Auto session is live", result.line)
        assertEquals(0, control.starts.get())
        assertEquals(0, control.stops.get())
    }

    @Test fun aRunningCarPlayTunnelRefusesTheCheckWithNoStartAndNoStop() {
        val control = FakeControl(carPlayUp = true, tun = FakeTun(true))
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run()
        assertEquals("CarPlayNative: refused, a CarPlay tunnel is already up", result.line)
        assertEquals(0, control.starts.get())
        assertEquals(0, control.stops.get())
    }

    @Test fun missingConsentFailsWithNoEstablish() {
        val control = FakeControl(prepared = false, tun = FakeTun(true))
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run()
        assertEquals("CarPlayNative: tunnel check failed (consent: VPN consent missing)", result.line)
        assertEquals(0, control.starts.get())
    }

    @Test fun anEstablishFailureNamesItsReason() {
        val control = FakeControl(failure = "IllegalStateException: establish returned null")
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run()
        assertEquals("CarPlayNative: tunnel check failed (establish: IllegalStateException: establish returned null)", result.line)
        assertEquals(1, control.stops.get())
    }

    @Test fun aListenerThatCannotBindClosesTheTunnel() {
        val tun = FakeTun(answer = true)
        val control = FakeControl(tun = tun)
        val result = CarPlayTunnelCheck(control, FakeListeners(fail = true), clock, { false }).run()
        assertEquals("CarPlayNative: tunnel check failed (listener: IOException)", result.line)
        assertEquals(1, tun.closes.get()); assertEquals(1, control.stops.get())
    }

    @Test fun aHoldKeepsTheTunnelUpAndStopsOnCancel() {
        val tun = FakeTun(answer = true)
        val control = FakeControl(tun = tun)
        var polls = 0
        val result = CarPlayTunnelCheck(control, FakeListeners(), clock, { false }).run(holdSeconds = 60, isCancelled = { ++polls > 3 })
        assertTrue(result is CarPlayTunnelCheck.Result.Ok)
        assertEquals(1, tun.closes.get())
    }
}
