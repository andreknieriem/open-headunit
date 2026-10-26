package com.andrerinas.openheadunit.decoder.video

/**
 * The rules for turning a stream of AAP video messages back into whole access units.
 *
 * Video arrives either complete in one message (flags 11) or split across a run of them: 9 first,
 * 8 in the middle, 10 last. Reassembly is only correct while that run is intact, and the stream
 * gives us three ways for it not to be - a run that stops before its 10, a fragment that arrives
 * with no run open, and a first fragment whose payload does not begin with a start code at its
 * message type's payload offset. All three end with the codec being handed something that is not a
 * decodable access unit, which is what a smeared or melting picture is.
 *
 * This object holds the ordering rules and nothing else: no buffer, no logging, no clock. That is
 * what makes it testable without a device, and the transition table in its test is the record of
 * what each of the ten reachable (flag, state) pairs is supposed to do. The I/O - the reassembly
 * buffer, the decoder call, the keyframe request and its throttle - stays in [AapVideo].
 */
class VideoFragmentAssembler {

    /** What [AapVideo] should do with the message just handed to [onMessage]. */
    sealed class Action {
        /** Hand the message body to the decoder as-is, starting at [payloadOffset]. */
        data class DecodeWhole(val payloadOffset: Int) : Action()

        /** Start a fresh assembly with the message body from [payloadOffset] onward. */
        data class BeginAssembly(val payloadOffset: Int) : Action()

        /** Append the whole message body to the assembly in progress. */
        object Append : Action()

        /** Append the whole message body, then hand the assembled frame to the decoder. */
        object AppendAndDecode : Action()

        /**
         * Throw this message away.
         *
         * [consumed] tells the caller whether this is video payload rather than channel control
         * traffic. DATA credit is independent: the transport acknowledges type 0 only at LAST,
         * even if decoding discards it. CSD and partial fragments never consume a credit.
         */
        data class Discard(val consumed: Boolean) : Action()
    }

    /**
     * A way the stream broke. Every one of these means the picture is about to drift, so each is
     * counted and each asks for a keyframe - subject to [VideoRecoveryPolicy]'s throttle, applied
     * by the caller.
     */
    enum class Anomaly {
        /** A 9 or 11 arrived while a run was still open: the previous frame lost its tail. */
        TRUNCATED_PREVIOUS,

        /** An 8 or 10 arrived with no run open: this frame lost its head. */
        ORPHANED_FRAGMENT,

        /**
         * A first fragment with no start code at its type's offset (DATA: 10, CSD: 2).
         *
         * This is the one that used to be silent. The run stayed open, so the 8s and 10 behind it
         * were appended and assembled without it, and the codec was fed an access unit missing its
         * own beginning - with nothing logged and no keyframe asked for. Closing the run here turns
         * those followers into [ORPHANED_FRAGMENT]s, which are discarded instead.
         */
        HEADLESS_FIRST_FRAGMENT,

        /** The frame grew past the reassembly buffer's ceiling. Reported by the caller. */
        OVERFLOW,
    }

    /** [Action] plus the anomaly, if any, that the caller should count and log. */
    data class Decision(val action: Action, val anomaly: Anomaly? = null)

    /** Whether a run of fragments is open, i.e. a 9 has been seen and its 10 has not. */
    var isAssembling: Boolean = false
        private set

    /**
     * Whether the frame being assembled is already known to be unusable.
     *
     * Scoped to the current frame only, deliberately. There is no lockout that holds every
     * subsequent P-frame back until a keyframe is positively identified - one existed, and because
     * nothing could reliably tell a keyframe from a P-frame for every codec the app negotiates, it
     * could latch for the rest of the session and freeze the picture with the connection still
     * healthy.
     */
    var isFrameCorrupt: Boolean = false
        private set

    private val counts = IntArray(Anomaly.entries.size)

    /**
     * Applies one video message to the state machine.
     *
     * [type] is the reassembler's message type: DATA (0) has an eight-byte timestamp after the
     * two-byte type, whereas CSD (1) has no timestamp. Validate only that type's payload offset;
     * a second NAL at offset 10 must never hide a CSD NAL at offset 2, nor may timestamp bytes
     * masquerade as CSD. Continuations have no new envelope: the upstream reassembler propagates
     * the FIRST's type and buffers short/split headers before this consumer sees them.
     *
     * The caller checks start codes against the valid message length, not its buffer capacity.
     */
    fun onMessage(flags: Int, type: Int, payloadStartsAt10: Boolean, payloadStartsAt2: Boolean): Decision {
        var anomaly: Anomaly? = null

        when (flags) {
            FLAG_SINGLE, FLAG_FIRST -> {
                if (isAssembling) anomaly = Anomaly.TRUNCATED_PREVIOUS
                isAssembling = false
                isFrameCorrupt = false
            }
            FLAG_MIDDLE, FLAG_LAST -> {
                if (type !in 0..1) return Decision(Action.Discard(consumed = false))
                if (!isAssembling) {
                    anomaly = Anomaly.ORPHANED_FRAGMENT
                    isFrameCorrupt = true
                }
                if (flags == FLAG_LAST) isAssembling = false
            }
            else -> return Decision(Action.Discard(consumed = false))
        }

        anomaly?.let { counts[it.ordinal]++ }

        // A channel control message can replace an unfinished run, but never supplies video bytes.
        if (type !in 0..1) return Decision(Action.Discard(consumed = false), anomaly)
        val payloadOffset = payloadOffset(type, payloadStartsAt10, payloadStartsAt2)

        return when (flags) {
            FLAG_SINGLE -> {
                if (payloadOffset != null) {
                    Decision(Action.DecodeWhole(payloadOffset), anomaly)
                } else {
                    // A complete frame we cannot find the start of is simply dropped. Nothing was
                    // assembled and nothing follows it, so the stream resumes on the next message.
                    Decision(Action.Discard(consumed = false), anomaly)
                }
            }

            FLAG_FIRST -> {
                if (payloadOffset != null) {
                    isAssembling = true
                    Decision(Action.BeginAssembly(payloadOffset), anomaly)
                } else {
                    // Leave the run closed. See Anomaly.HEADLESS_FIRST_FRAGMENT.
                    isFrameCorrupt = true
                    counts[Anomaly.HEADLESS_FIRST_FRAGMENT.ordinal]++
                    Decision(Action.Discard(consumed = false), Anomaly.HEADLESS_FIRST_FRAGMENT)
                }
            }

            FLAG_MIDDLE -> {
                if (isFrameCorrupt) Decision(Action.Discard(consumed = true), anomaly)
                else Decision(Action.Append, anomaly)
            }

            // FLAG_LAST. Anything outside the four payload flags returned above, so the else is
            // only reachable for 10 - it is an else rather than a branch to keep the when
            // exhaustive as an expression.
            else -> {
                if (isFrameCorrupt) Decision(Action.Discard(consumed = true), anomaly)
                else Decision(Action.AppendAndDecode, anomaly)
            }
        }
    }

    /**
     * Records that the frame being assembled outgrew the reassembly buffer, and invalidates it.
     *
     * Separate from [onMessage] because it is only discovered once the caller tries to make room.
     */
    fun onOverflow(): Anomaly {
        isFrameCorrupt = true
        counts[Anomaly.OVERFLOW.ordinal]++
        return Anomaly.OVERFLOW
    }

    /** How many times [anomaly] has been seen since the last [resetCounts]. */
    fun countOf(anomaly: Anomaly): Int = counts[anomaly.ordinal]

    /** Whether anything at all has been counted since the last [resetCounts]. */
    fun hasAnomalies(): Boolean = counts.any { it != 0 }

    /** Zeroes the counters, leaving the assembly state alone. For periodic reporting. */
    fun resetCounts() = counts.fill(0)

    /** Returns the machine to its start state. For a new session. */
    fun reset() {
        isAssembling = false
        isFrameCorrupt = false
        resetCounts()
    }

    companion object {
        /**
         * Whether a video-channel message carries picture at all, answered without touching any state.
         *
         * The transport demux asks this on the read thread so it knows whether to hand the message to
         * the video thread and stop, or to carry on down the dispatch chain with it: a video channel
         * also carries this sink's setup, start, stop and focus messages, and those are answered where
         * they always were. It is the exact complement of the `consumed = false` answers [onMessage]
         * gives, and a test holds the two together.
         */
        fun isPayload(flags: Int, type: Int, payloadStartsAt10: Boolean, payloadStartsAt2: Boolean): Boolean =
            type in 0..1 && when (flags) {
                FLAG_MIDDLE, FLAG_LAST -> true
                FLAG_SINGLE, FLAG_FIRST -> payloadOffset(type, payloadStartsAt10, payloadStartsAt2) != null
                else -> false
            }

        private fun payloadOffset(type: Int, at10: Boolean, at2: Boolean): Int? = when (type) {
            0 -> if (at10) OFFSET_TIMESTAMP_INDICATION else null
            1 -> if (at2) OFFSET_MEDIA_INDICATION else null
            else -> null
        }

        /** A whole frame in one message. */
        const val FLAG_SINGLE = 11

        /** The first fragment of a frame. */
        const val FLAG_FIRST = 9

        /** A fragment that is neither the first nor the last. */
        const val FLAG_MIDDLE = 8

        /** The last fragment of a frame. */
        const val FLAG_LAST = 10

        /** Payload offset for a timestamp indication: 2 bytes of message type, 8 of timestamp. */
        const val OFFSET_TIMESTAMP_INDICATION = 10

        /** Payload offset for a media indication or a config message: 2 bytes of message type. */
        const val OFFSET_MEDIA_INDICATION = 2
    }
}
