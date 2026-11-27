package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetIpv6
import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetLink
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunBridgeRules
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunOutbound
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** The tunnel descriptor: blocking IP packet reads and writes. Closing it ends a blocked read. */
interface TunPort {
    /** One packet into [buffer]. -1 at the end of the tunnel. */
    fun read(buffer: ByteArray): Int

    fun write(buffer: ByteArray, offset: Int, length: Int)

    fun close()
}

/**
 * Moves IPv6 packets between the tunnel and the NCM link on two threads. [close] stops the tunnel first, then the link,
 * and joins both threads. One instance serves one session.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `network/Ipv6NcmBridge.kt`, with the join order of DiPlay (https://github.com/shihabal3amri/DiPlay).
 */
class Ipv6TunBridge(
    private val link: EthernetLink,
    private val tun: TunPort,
    private val hostMac: ByteArray,
    private val onFirstInbound: () -> Unit,
    private val onError: (String) -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val stopped = AtomicBoolean(false)
    @Volatile private var peerMac: ByteArray? = null
    private var inboundThread: Thread? = null
    private var outboundThread: Thread? = null
    private var inboundSeen = false
    private var waitLogged = false

    init { require(hostMac.size == EthernetIpv6.MAC_BYTES) { "hostMac must be 6 bytes" } }

    fun start() {
        check(!stopped.get() && running.compareAndSet(false, true)) { "Ipv6TunBridge is single use" }
        inboundThread = Thread({ inbound() }, "carplay-ncm-in").apply { isDaemon = true; start() }
        outboundThread = Thread({ outbound() }, "carplay-ncm-out").apply { isDaemon = true; start() }
    }

    fun close() {
        if (!stopped.compareAndSet(false, true)) return
        running.set(false)
        // The tunnel closes before the link: a write into a closing USB link once panicked a unit.
        try { tun.close() } finally {
            outboundThread?.let(::join)
            try { link.close() } finally { inboundThread?.let(::join) }
        }
    }

    private fun inbound() {
        try {
            while (running.get()) {
                val frame = link.recv(RECV_TIMEOUT_MS) ?: continue
                val view = EthernetIpv6.parseIpv6View(frame) ?: continue
                peerMac = view.sourceMac
                if (!inboundSeen) { inboundSeen = true; AppLog.i("CarPlayNative: first NCM frame from the iPhone"); onFirstInbound() }
                tun.write(frame, view.payloadOffset, view.payloadLength)
            }
        } catch (e: IOException) {
            fail("NCM read failed (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            fail("NCM read failed (${e.javaClass.simpleName})")
        }
    }

    private fun outbound() {
        val buffer = ByteArray(TUN_READ_BYTES)
        try {
            while (running.get()) {
                val length = tun.read(buffer)
                if (length < 0) { fail("tunnel closed"); return }
                if (length == 0) { Thread.sleep(1); continue }
                when (val action = TunBridgeRules.outbound(buffer.copyOf(length), hostMac, peerMac)) {
                    TunOutbound.WaitForPeer -> if (!waitLogged) { waitLogged = true; AppLog.i("CarPlayNative: unicast waits for the iPhone MAC") }
                    is TunOutbound.Frame -> link.send(action.frame, SEND_TIMEOUT_MS)
                }
            }
        } catch (_: InterruptedException) {
            // close() interrupts a thread that outlived its join.
        } catch (e: IOException) {
            fail("tunnel write to NCM failed (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            fail("tunnel write to NCM failed (${e.javaClass.simpleName})")
        }
    }

    private fun fail(reason: String) {
        if (running.get()) onError(reason)
    }

    private fun join(thread: Thread) {
        if (thread === Thread.currentThread()) return
        try { thread.join(JOIN_MS) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        if (thread.isAlive) thread.interrupt()
    }

    private companion object {
        const val RECV_TIMEOUT_MS = 1000L
        const val SEND_TIMEOUT_MS = 2000
        const val TUN_READ_BYTES = 4096
        const val JOIN_MS = 2000L
    }
}
