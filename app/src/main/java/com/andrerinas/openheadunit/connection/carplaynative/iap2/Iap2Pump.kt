package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException

/**
 * Runs [Iap2Link] and [Iap2Control] over the carkit stream on one thread. It reads, feeds the link and the control
 * reducer with the clock, and writes what they return. It holds no rule. [stop] sends the media remote stop once and
 * ends the loop; the thread also ends when the stream ends. One instance serves one session.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/Iap2CsmChannel.kt`.
 */
class Iap2Pump(
    private val stream: BlockingDuplexByteStream,
    private val link: Iap2Link,
    private val control: Iap2Control,
    private val clock: () -> Long,
    private val events: Events,
) {
    interface Events {
        fun onIdentified()
        fun onAuthenticated()
        fun onStartSent(count: Int)
        fun onFailed(reason: String)
    }

    private val lock = Any()
    private val pending = ArrayList<() -> Unit>()
    private val framer = Iap2Csm.Framer()
    private var thread: Thread? = null
    private var controlStarted = false
    private var failed = false
    @Volatile private var stopped = false

    fun start() {
        check(thread == null) { "Iap2Pump is single use" }
        thread = Thread({ run() }, "carplay-iap2").apply { isDaemon = true; start() }
    }

    /** Sends the 0x4301 again. A no-op once the control session is not running. */
    fun resendStart() {
        synchronized(lock) {
            if (stopped || failed) return
            try { apply(control.resendStart()); flush() } catch (e: IOException) { fail("iAP2 stream write failed (${e.javaClass.simpleName})") }
        }
        fire()
    }

    /** Ends the loop after one best-effort stop message. Safe to call twice. */
    fun stop() {
        synchronized(lock) {
            if (stopped) return
            stopped = true
            pending.clear()
            if (failed || !controlStarted) return
            try { apply(control.stop()); flush() } catch (_: IOException) { /* the stream is gone, the iPhone ends the remote itself */ }
            pending.clear()
        }
    }

    fun join(timeoutMs: Long) {
        val running = thread ?: return
        if (running === Thread.currentThread()) return
        try { running.join(timeoutMs) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun run() {
        try {
            synchronized(lock) { link.start(true, clock()); flush() }
            while (!stopped) {
                val chunk = stream.recv(READ_BYTES, waitMs())
                if (stopped) return
                synchronized(lock) {
                    val now = clock()
                    when {
                        chunk == null -> Unit
                        chunk.isEmpty() -> link.feedEof()
                        else -> link.feed(chunk, now)
                    }
                    link.advanceTime(now)
                    if (!controlStarted && link.state == Iap2Link.State.Normal) {
                        controlStarted = true
                        apply(control.start(now))
                    }
                    if (controlStarted) apply(control.tick(now))
                    drain(now)
                    flush()
                }
                if (chunk != null && chunk.isEmpty()) fail("the carkit stream ended")
                fire()
            }
        } catch (e: IOException) {
            if (!stopped) fail("iAP2 stream failed (${e.javaClass.simpleName})")
        } catch (e: RuntimeException) {
            if (!stopped) fail("iAP2 stream failed (${e.javaClass.simpleName})")
        }
        fire()
    }

    /** Events run outside the lock, so a callback that stops the session cannot wait on the thread that raised it. */
    private fun fire() {
        val ready = synchronized(lock) { pending.toList().also { pending.clear() } }
        ready.forEach { it() }
    }

    /** At most [MAX_WAIT_MS], and no longer than the link's next deadline. */
    private fun waitMs(): Long {
        val next = synchronized(lock) { link.nextDeadline() } ?: return MAX_WAIT_MS
        return (next - clock()).coerceIn(1L, MAX_WAIT_MS)
    }

    private fun drain(now: Long) {
        while (true) {
            val event = link.pollEvent() ?: return
            when (event) {
                is Iap2Link.Event.Control -> for (frame in try { framer.offer(event.bytes) } catch (e: Iap2FormatException) {
                    fail("CarPlayNative: iAP2 framing broke (${e.message})")
                    return
                }) apply(control.onFrame(frame, now))
                is Iap2Link.Event.FileTransfer -> Unit
                is Iap2Link.Event.Dead -> { fail("iAP2 link ended (${event.error?.text ?: "closed"})"); return }
            }
        }
    }

    private fun apply(actions: List<Iap2Control.Action>) {
        for (action in actions) when (action) {
            is Iap2Control.Action.Send -> link.send(Iap2Link.CONTROL_SESSION_ID, action.frame.encode(), clock())
            is Iap2Control.Action.Log -> {
                AppLog.i(action.line)
                if (action.line == Iap2Control.IDENTIFICATION_ACCEPTED_LINE) pending += { events.onIdentified() }
            }
            is Iap2Control.Action.Fail -> fail(action.reason)
            is Iap2Control.Action.StartSent -> pending += { events.onStartSent(action.count) }
            Iap2Control.Action.Ready -> pending += { events.onAuthenticated() }
            is Iap2Control.Action.Incoming -> Unit
        }
    }

    /** Writes what the link has queued. A write error ends the session through [fail]. */
    private fun flush() {
        val bytes = link.takeOutput()
        if (bytes.isNotEmpty()) stream.send(bytes)
    }

    private fun fail(reason: String) = synchronized(lock) {
        if (failed) return@synchronized
        failed = true
        if (!stopped) pending += { events.onFailed(reason) }
    }

    private companion object {
        const val READ_BYTES = 16 * 1024
        const val MAX_WAIT_MS = 250L
    }
}
