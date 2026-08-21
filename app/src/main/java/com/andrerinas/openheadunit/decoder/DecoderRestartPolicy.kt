package com.andrerinas.openheadunit.decoder

/**
 * Decides whether a decoder restart counts toward the codec-type flip and the permanent-failure
 * latch.
 *
 * The escalation ladder exists for one case: a hardware component that cannot decode this codec
 * type at all, which would otherwise restart forever. The evidence for that case is a session in
 * which *nothing has ever rendered*. The counter used to test only "no frame since the last
 * start" - but every surface swap stops the decoder and zeroes that timestamp, so a stream that
 * had been rendering for an hour looked identical to one that never worked, and a few slow
 * warm-ups after a relaunch walked it through the flip (to a codec type guaranteed wrong for the
 * running stream) and into the latch. Measured on hardware: first frame after a mid-session
 * reconfigure takes up to ~8s on some SoCs against a 2s stall window, so the ladder climbed on
 * healthy sessions.
 */
object DecoderRestartPolicy {

    /**
     * True when this restart is evidence against the pinned codec type. [renderedThisSession]
     * being true proves the codec type decodes this stream, so no number of warm-up restarts
     * after that is reason to flip or to give up.
     */
    fun countsTowardFailure(
        codecTypePinned: Boolean,
        renderedSinceLastStart: Boolean,
        renderedThisSession: Boolean
    ): Boolean = codecTypePinned && !renderedSinceLastStart && !renderedThisSession
}
