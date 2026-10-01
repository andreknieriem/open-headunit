package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.decoder.audio.MicChunkAccumulator

/**
 * Poll-side retirement is immediate; recorder start/stop and replies run in lifecycle FIFO order.
 * The enqueue callbacks must only enqueue, never execute inline or wait for native I/O.
 * Each capture callback, queued send and diagnostic report retains its original run.
 */
internal class MicSessionController(
    private val lifecycle: (() -> Unit) -> Unit,
    private val sending: (() -> Unit) -> Unit,
    private val startCapture: (CaptureCallbacks) -> Int,
    private val stopCapture: () -> Unit,
    private val response: (Int, Boolean) -> Unit,
    private val data: (ByteArray, Long) -> Unit,
    private val clockMs: () -> Long,
    private val timestampUs: () -> Long,
    private val report: (MicUplinkMonitor.Report) -> Unit
) {
    class CaptureCallbacks(
        val data: (ByteArray, Int, Int) -> Unit,
        val failed: () -> Unit,
        val isCurrent: () -> Boolean
    )

    private val lock = Any()
    private var sequence = 0
    private var current: Run? = null
    private var stopped = false
    private class Packet(val bytes: ByteArray, val timestamp: Long, val peak: Int)
    private inner class Run(val id: Int, window: Int) {
        var open = true
        val chunks = MicChunkAccumulator()
        val monitor = MicUplinkMonitor().apply { onSessionStart(clockMs()) }
        val flow = MicFlowControl<Packet> { token, packet ->
            sending {
                val claimed = synchronized(lock) {
                    if (!open || !flowClaim(this, token, packet)) false else {
                        monitor.onFrame(packet.bytes.size, packet.peak, clockMs())
                        true
                    }
                }
                // A claimed send may finish after retirement, before the close reply in this FIFO.
                if (claimed) data(packet.bytes, packet.timestamp)
            }
        }
        val token = flow.begin(id, window)
    }
    private fun flowClaim(run: Run, token: Long, packet: Packet): Boolean = run.flow.claim(token, packet)

    fun open(window: Int) = synchronized(lock) {
        if (stopped) return@synchronized
        if (window !in 1..4096) { rejectLocked(); return@synchronized }
        val existing = current
        if (existing != null) {
            lifecycle { response(existing.id, synchronized(lock) { existing.open }) }
            return@synchronized
        }
        val run = Run(++sequence, window)
        current = run
        lifecycle {
            val requested = synchronized(lock) { run.open }
            val result = if (requested) try {
                startCapture(CaptureCallbacks(
                    data = { bytes, length, peak -> captured(run, bytes, length, peak) },
                    failed = { captureFailed(run) },
                    isCurrent = { synchronized(lock) { run.open && current === run } }
                ))
            } catch (_: Exception) { -1 } else -1
            if (result != 0) {
                val summary = synchronized(lock) { if (run.open) retireLocked(run, 0) else null }
                summary?.let(report)
                // Cleanup before any subsequent Open can acquire the recorder/foreground claim.
                stopCapture()
            }
            synchronized(lock) {
                response(run.id, result == 0)
                if (run.open && current === run && result == 0) run.flow.activate(run.token)
            }
        }
    }

    fun reject() = synchronized(lock) { if (!stopped) rejectLocked() }
    private fun rejectLocked() {
        val run = current
        val id = run?.id ?: sequence
        val summary = if (run != null) retireLocked(run, 0) else null
        lifecycle {
            summary?.let(report)
            if (run != null) stopCapture()
            response(id, false)
        }
    }

    fun close(reply: Boolean = false, shutdown: Boolean = false) = synchronized(lock) {
        if (stopped) return@synchronized
        if (shutdown) stopped = true
        val run = current
        val id = run?.id ?: sequence
        val summary = if (run != null) retireLocked(run, 0) else null
        lifecycle {
            summary?.let(report)
            stopCapture()
            if (reply) response(id, true)
        }
    }

    fun acknowledge(id: Int, count: Int): Boolean = synchronized(lock) {
        val run = current ?: return@synchronized false
        if (!run.flow.acknowledge(id, count)) return@synchronized false
        repeat(count) { run.monitor.onAck() }
        true
    }

    private fun captured(run: Run, bytes: ByteArray, length: Int, peak: Int) = synchronized(lock) {
        if (!run.open || current !== run || length <= 0) return@synchronized
        val discarded = run.chunks.offerWhile(bytes, length, timestampUs(), peak) { chunk, size, stamp, level ->
            run.flow.offer(Packet(chunk.copyOf(size), stamp, level)) == MicFlowControl.Offer.ACCEPTED
        }
        if (discarded != 0) {
            val summary = retireLocked(run, discarded)
            // Queue while holding only the short state lock so a new Open cannot overtake abort.
            lifecycle {
                summary?.let(report)
                stopCapture()
                response(run.id, false)
            }
        }
    }

    private fun captureFailed(run: Run) = synchronized(lock) {
        if (!run.open || current !== run) return@synchronized
        val summary = retireLocked(run, 0)
        lifecycle {
            summary?.let(report)
            stopCapture()
            response(run.id, false)
        }
    }

    private fun retireLocked(run: Run, rejectedBytes: Int): MicUplinkMonitor.Report? {
        run.open = false
        if (current === run) current = null
        val cancelled = run.flow.close().sumOf { it.bytes.size }
        run.monitor.onDiscarded(cancelled + run.chunks.reset() + rejectedBytes)
        // All claims and accounting precede retirement under this lock. Publish the immutable
        // report on the lifecycle worker, which survives send-queue shutdown during disconnect.
        return run.monitor.onSessionEnd(clockMs())
    }
}
