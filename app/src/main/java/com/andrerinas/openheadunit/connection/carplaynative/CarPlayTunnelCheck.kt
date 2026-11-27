package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.usb.TunProbePacket
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** The CarPlay tunnel as the check and the session see it. `VpnTunnelControl` backs it with `VpnControl`. */
interface TunnelControl {
    fun isDummyVpnUp(): Boolean

    /** True while a CarPlay tunnel start is pending or the tunnel is up, a session's included. */
    fun isCarPlayTunRunning(): Boolean

    /** True when VPN consent is given. The tunnel cannot be established without it. */
    fun isPrepared(): Boolean

    /** Establishes the tunnel. The callbacks may run on any thread. The receiver closes the port. */
    fun start(onReady: (TunPort) -> Unit, onFailed: (String) -> Unit)

    fun stop()
}

/** A TCP listener on `[::]` that the probe SYN targets. */
interface ProbeListener {
    val port: Int
    fun close()
}

fun interface ProbeListenerFactory {
    @Throws(IOException::class)
    fun bind(): ProbeListener
}

/**
 * The user preflight for the tunnel: a real SYN from fe80::1 to fe80::2 into the tunnel and the SYN-ACK back, no iPhone needed.
 * A pass proves the address, the route and the kernel TCP path on this unit, not the NCM half. Blocking: call it off the main thread.
 */
class CarPlayTunnelCheck(
    private val tunnel: TunnelControl,
    private val listeners: ProbeListenerFactory,
    private val clock: () -> Long,
    /** True while an Android Auto session is live or connecting; the tunnel would take its default network. */
    private val aaSessionLive: () -> Boolean,
) {
    sealed class Result(val line: String) {
        object Ok : Result("CarPlayNative: tunnel check ok (SYN-ACK from fe80::2)")
        class Refused(reason: String) : Result("CarPlayNative: refused, $reason")
        class Failed(step: String, reason: String) : Result("CarPlayNative: tunnel check failed ($step: $reason)")
    }

    /** With [holdSeconds] above zero a passing check keeps the tunnel up for that long (at most [MAX_HOLD_SECONDS]) or until [isCancelled]. */
    fun run(holdSeconds: Int = 0, isCancelled: () -> Boolean = { false }): Result {
        val result = check(holdSeconds.coerceIn(0, MAX_HOLD_SECONDS), isCancelled)
        if (result is Result.Ok) AppLog.i(result.line) else AppLog.w(result.line)
        return result
    }

    private fun check(holdSeconds: Int, isCancelled: () -> Boolean): Result {
        if (aaSessionLive()) return Result.Refused("an Android Auto session is live")
        if (tunnel.isDummyVpnUp()) return Result.Refused("the dummy VPN holds the VPN slot")
        if (tunnel.isCarPlayTunRunning()) return Result.Refused("a CarPlay tunnel is already up")
        if (!tunnel.isPrepared()) return Result.Failed("consent", "VPN consent missing")
        val ready = CountDownLatch(1)
        val port = AtomicReference<TunPort?>()
        val failure = AtomicReference<String?>()
        val abandoned = AtomicBoolean(false)
        tunnel.start(
            onReady = { tun -> if (abandoned.get()) tun.close() else { port.set(tun); ready.countDown() } },
            onFailed = { reason -> failure.set(reason); ready.countDown() },
        )
        var listener: ProbeListener? = null
        var reader: Thread? = null
        var tun: TunPort? = null
        try {
            if (!await(ready, ESTABLISH_TIMEOUT_MS)) { abandoned.set(true); return Result.Failed("establish", "no answer in ${ESTABLISH_TIMEOUT_MS / 1000} s") }
            failure.get()?.let { return Result.Failed("establish", it) }
            tun = port.get() ?: return Result.Failed("establish", "no descriptor")
            listener = try { listeners.bind() } catch (e: IOException) { return Result.Failed("listener", e.javaClass.simpleName) }
            val synAck = CountDownLatch(1)
            val bound = listener.port
            val stream = tun
            reader = Thread({ readUntilSynAck(stream, bound, synAck) }, "carplay-tunnel-check").apply { isDaemon = true; start() }
            val syn = TunProbePacket.syn(bound)
            try { tun.write(syn, 0, syn.size) } catch (e: IOException) { return Result.Failed("SYN write", e.javaClass.simpleName) }
            if (!await(synAck, SYN_ACK_TIMEOUT_MS)) return Result.Failed("SYN-ACK", "no answer in ${SYN_ACK_TIMEOUT_MS / 1000} s")
            hold(holdSeconds, isCancelled)
            return Result.Ok
        } finally {
            // The listener goes first, then the descriptor: closing it ends the blocked read.
            listener?.let { runCatching { it.close() } }
            tun?.let { runCatching { it.close() } }
            reader?.let { runCatching { it.join(JOIN_MS) } }
            tunnel.stop()
        }
    }

    private fun readUntilSynAck(tun: TunPort, port: Int, found: CountDownLatch) {
        val buffer = ByteArray(READ_BYTES)
        try {
            while (found.count > 0) {
                val length = tun.read(buffer)
                if (length < 0) return
                if (length > 0 && TunProbePacket.isSynAck(buffer.copyOf(length), port)) { found.countDown(); return }
            }
        } catch (_: IOException) {
            // The tunnel closed under the read: the waiter reports the missing answer.
        } catch (_: RuntimeException) {
        }
    }

    private fun await(latch: CountDownLatch, timeoutMs: Long): Boolean {
        val deadline = clock() + timeoutMs
        while (clock() < deadline) if (latch.await(POLL_MS, TimeUnit.MILLISECONDS)) return true
        return latch.count == 0L
    }

    private fun hold(seconds: Int, isCancelled: () -> Boolean) {
        if (seconds <= 0) return
        AppLog.i("CarPlayNative: tunnel check holds the tunnel for $seconds s")
        val end = clock() + seconds * 1000L
        while (clock() < end && !isCancelled()) Thread.sleep(POLL_MS)
    }

    companion object {
        const val ESTABLISH_TIMEOUT_MS = 5_000L
        const val SYN_ACK_TIMEOUT_MS = 3_000L
        const val MAX_HOLD_SECONDS = 120
        private const val POLL_MS = 25L
        private const val JOIN_MS = 2_000L
        private const val READ_BYTES = 4096
    }
}
