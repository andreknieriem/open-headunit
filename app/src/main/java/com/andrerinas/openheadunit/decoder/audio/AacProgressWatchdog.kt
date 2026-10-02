package com.andrerinas.openheadunit.decoder.audio

/** Progress belongs to the codec's continuous work interval, never to a packet or Start/Stop.
 * Copied output is progress before bank handoff; a closed, already-submitted tail is uncertain,
 * not evidence that a codec is wedged. */
internal class AacProgressWatchdog {
    private var inputBlockedSince = -1L
    private var outputWaitingSince = -1L

    @Synchronized fun inputWaiting(nowMs: Long) {
        if (inputBlockedSince < 0) inputBlockedSince = nowMs
    }
    @Synchronized fun inputAccepted(nowMs: Long) {
        inputBlockedSince = -1
        if (outputWaitingSince < 0) outputWaitingSince = nowMs
    }
    @Synchronized fun outputCopied() { outputWaitingSince = -1 }
    @Synchronized fun idleClosedTail() { outputWaitingSince = -1 }
    @Synchronized fun stalled(nowMs: Long, hasUnsubmitted: Boolean, inputClosed: Boolean): Boolean {
        // A decoder may need another AU to release its uncertain tail. Quiet Stop time is not
        // stall time; Start alone must not destroy that tail before it receives new input.
        if (inputClosed && !hasUnsubmitted) outputWaitingSince = -1
        return (hasUnsubmitted && inputBlockedSince >= 0 && nowMs - inputBlockedSince >= 1000) ||
            ((!inputClosed || hasUnsubmitted) && outputWaitingSince >= 0 && nowMs - outputWaitingSince >= 1000)
    }

    @Synchronized fun reset() { inputBlockedSince = -1; outputWaitingSince = -1 }
}
