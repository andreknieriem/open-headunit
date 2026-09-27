package com.andrerinas.openheadunit.decoder.video

/**
 * Decides whether a [VideoDecoder.stop] call ends the projection session or only tears the decoder
 * down while the phone keeps streaming.
 *
 * The distinction matters because some of the decoder's state describes the *stream* rather than
 * the decoder instance - the pinned codec type above all - and throwing it away while the stream is
 * still running forces a re-detection on whatever mid-stream packet happens to arrive next. That
 * heuristic cannot tell an ordinary H.264 P-slice header (0x41) from an HEVC VPS, so the re-detect
 * can hand an H.264 stream an HEVC decoder.
 *
 * Anything not listed here ends the session, so an unrecognised reason keeps the old behaviour of
 * resetting everything.
 */
object DecoderStopPolicy {

    /** The projection surface went away; the AAP session is untouched. */
    const val REASON_SURFACE_DESTROYED = "surfaceDestroyed"

    /** The projection view left the window hierarchy; the AAP session is untouched. */
    const val REASON_DETACHED_FROM_WINDOW = "onDetachedFromWindow"

    /** The projection view is being swapped for another backend; the AAP session is untouched. */
    const val REASON_PROJECTION_VIEW_RECREATE = "projectionViewRecreate"

    /** A new surface replaced the old one; the AAP session is untouched. */
    const val REASON_NEW_SURFACE = "New surface"

    /** The head unit went into sleep/standby (e.g. screen off, car turned off temporarily); the AAP session is untouched. */
    const val REASON_SCREEN_OFF_SLEEP = "screen_off_sleep"

    /** The projection activity was stopped (backgrounded); the AAP session is untouched. */
    const val REASON_ACTIVITY_STOPPED = "activity_stopped"

    /** Prefix used by the decoder's own recovery restarts, which have never ended the session. */
    private const val RESTART_PREFIX = "restart"

    private val SURFACE_LIFECYCLE_REASONS = setOf(
        REASON_SURFACE_DESTROYED,
        REASON_DETACHED_FROM_WINDOW,
        REASON_PROJECTION_VIEW_RECREATE,
        REASON_NEW_SURFACE,
        REASON_SCREEN_OFF_SLEEP,
        REASON_ACTIVITY_STOPPED,
    )

    /** True when the decoder is tearing itself down to come straight back up. */
    fun isDecoderRestart(reason: String): Boolean = reason.startsWith(RESTART_PREFIX)

    /**
     * True when [reason] means the phone has gone away and every piece of session state should be
     * reset. False for the decoder's own restarts and for the surface lifecycle, where the stream
     * outlives the decoder.
     */
    fun endsSession(reason: String): Boolean =
        !isDecoderRestart(reason) && reason !in SURFACE_LIFECYCLE_REASONS
}
