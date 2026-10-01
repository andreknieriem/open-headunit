package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.MicCaptureFormat

/**
 * What the microphone session actually put on the wire.
 *
 * The capture side already says whether the hardware heard anything
 * ([com.andrerinas.openheadunit.decoder.audio.MicRecorder] logs a peak and a byte count). Nothing said
 * whether those bytes then left the head unit, so a reporter log showed `Initializing AudioRecord`
 * and then silence, and a dead input and a dead uplink read identically. This is the missing half:
 * paired with the capture summary it separates "the microphone is routed nowhere" from "we heard it
 * and never sent it".
 *
 * Reported per microphone session rather than per rolling window, because a session is a few
 * seconds and a fixed window would straddle several or none.
 *
 * **Diagnostic only** - nothing reads the report. Pure and clock-free: the caller passes the time,
 * so a measured session replays in a unit test.
 */
class MicUplinkMonitor {

    private var open = false
    private var startedMs = 0L
    private var frames = 0
    private var bytes = 0L
    private var peak = 0
    private var largest = 0
    private var smallest = 0
    private var acks = 0
    private var discarded = 0

    /** Whether a session is open. A frame arriving with none opens one. */
    val isOpen: Boolean get() = open

    /**
     * Feed one message handed to the send queue.
     *
     * [payloadBytes] is the PCM only, so the percentage compares like with like against the capture
     * rate. Returns true for the first frame of a session, which is the caller's cue to say once
     * that the uplink started - its absence in a log being the finding when the phone asked for the
     * microphone and nothing followed.
     */
    fun onFrame(payloadBytes: Int, framePeak: Int, nowMs: Long): Boolean {
        val first = frames == 0
        if (!open) {
            open = true
            startedMs = nowMs
            smallest = payloadBytes
        }

        if (first) smallest = payloadBytes
        frames++
        bytes += payloadBytes
        if (framePeak > peak) peak = framePeak
        if (payloadBytes > largest) largest = payloadBytes
        if (payloadBytes < smallest) smallest = payloadBytes
        return first
    }

    /** Start before the first send, so a fully cancelled capture still has a report. */
    fun onSessionStart(nowMs: Long) {
        reset()
        open = true
        startedMs = nowMs
    }

    /** One acknowledgement from the phone on the microphone channel. */
    fun onAck() {
        if (open) acks++
    }

    /** Bytes the chunker could not fill a message with. At most one chunk, at the end of a session. */
    fun onDiscarded(bytes: Int) {
        if (open) discarded += bytes
    }

    /**
     * Close the session and say what it produced, or null if no frame was ever sent.
     *
     * A null here after the phone asked for the microphone is itself the answer.
     */
    fun onSessionEnd(nowMs: Long): Report? {
        if (!open) return null
        val report = Report(
            frames = frames,
            bytes = bytes,
            elapsedMs = nowMs - startedMs,
            peak = peak,
            largest = largest,
            smallest = smallest,
            acks = acks,
            discarded = discarded
        )
        reset()
        return report
    }

    /**
     * Forget the previous session. The transport outlives a session and is re-armed for the next
     * one, so every field a session writes has to be restored here.
     */
    fun reset() {
        open = false
        startedMs = 0L
        frames = 0
        bytes = 0L
        peak = 0
        largest = 0
        smallest = 0
        acks = 0
        discarded = 0
    }

    /** One microphone session's worth of uplink. */
    data class Report(
        val frames: Int,
        val bytes: Long,
        val elapsedMs: Long,
        val peak: Int,
        val largest: Int,
        val smallest: Int,
        val acks: Int,
        val discarded: Int
    ) {
        /**
         * Bytes sent as a percentage of what 16 kHz mono 16-bit produces over the same span.
         *
         * Near 100 means the uplink kept up with the microphone. Far below it means messages were
         * dropped or the capture starved, which the capture summary then tells apart.
         */
        val percentOfExpected: Int
            get() {
                val expected = BYTES_PER_SECOND * elapsedMs / 1000L
                return if (expected <= 0L) -1 else (bytes * 100L / expected).toInt()
            }

        override fun toString(): String =
            "mic uplink | $frames frame${if (frames == 1) "" else "s"}, $bytes B in ${elapsedMs}ms " +
                "($percentOfExpected% of expected), peak=$peak/32767, " +
                "largest=${largest}B, smallest=${smallest}B, acks=$acks, discarded=${discarded}B"
    }

    companion object {
        /** What the microphone service announces, so the percentage cannot drift from the wire. */
        // Not a const: the announced format owns the number, and a const initializer cannot call
        // toLong() on it.
        val BYTES_PER_SECOND: Long = MicCaptureFormat.BYTES_PER_SECOND.toLong()
    }
}
