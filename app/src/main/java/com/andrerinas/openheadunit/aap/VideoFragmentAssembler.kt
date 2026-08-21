package com.andrerinas.openheadunit.aap

/**
 * The rules for turning a stream of AAP video messages back into whole access units.
 *
 * Video arrives either complete in one message (flags 11) or split across a run of them: 9 first,
 * 8 in the middle, 10 last. Reassembly is only correct while that run is intact, and the stream
 * gives us three ways for it not to be - a run that stops before its 10, a fragment that arrives
 * with no run open, and a first fragment whose payload does not begin with a start code at either
 * offset the protocol uses. All three end with the codec being handed something that is not a
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
         * [consumed] mirrors what `AapVideo.process` has always returned for the case, because the
         * caller uses it to decide whether to send a media ack and whether to try the other channel
         * handlers. Getting it wrong changes protocol behaviour, so it is carried explicitly rather
         * than inferred.
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
         * A first fragment whose payload begins with no start code at offset 10 or offset 2.
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
     * [payloadStartsAt10] and [payloadStartsAt2] are the two offsets the protocol puts a frame at -
     * 10 for a timestamp indication, 2 for a media indication - and each is true only when a start
     * code is present there *and* there is payload behind it. The caller resolves them because it
     * owns the bytes; which one wins, and what happens when neither does, is decided here.
     */
    fun onMessage(flags: Int, payloadStartsAt10: Boolean, payloadStartsAt2: Boolean): Decision {
        var anomaly: Anomaly? = null

        when (flags) {
            FLAG_SINGLE, FLAG_FIRST -> {
                if (isAssembling) anomaly = Anomaly.TRUNCATED_PREVIOUS
                isAssembling = false
                isFrameCorrupt = false
            }
            FLAG_MIDDLE, FLAG_LAST -> {
                if (!isAssembling) {
                    anomaly = Anomaly.ORPHANED_FRAGMENT
                    isFrameCorrupt = true
                }
                if (flags == FLAG_LAST) isAssembling = false
            }
            else -> return Decision(Action.Discard(consumed = false))
        }

        anomaly?.let { counts[it.ordinal]++ }

        val payloadOffset = when {
            payloadStartsAt10 -> OFFSET_TIMESTAMP_INDICATION
            payloadStartsAt2 -> OFFSET_MEDIA_INDICATION
            else -> null
        }

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
