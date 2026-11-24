package com.andrerinas.openheadunit.decoder.video

/**
 * Decides which stream state a [VideoDecoder.stop] call retires.
 *
 * Some state describes the stream rather than the decoder instance. Surface teardown cannot
 * renegotiate the codec, so the pinned codec and parameter sets must survive it. Only a new
 * session can replace the announcement used when the stream has no readable parameter sets.
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

    // These replace the bitstream (and its configuration), but not the projection connection.
    internal const val REASON_GAL_START_REPLACED = "GAL media Start replaced"
    internal const val REASON_GAL_SETUP_REPLACED_PREPARATION = "GAL Setup replaced pre-Start CSD"
    internal const val REASON_GAL_PRE_START_CSD = "GAL pre-Start CSD"

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
     * True when the bitstream ends and its configuration must be reset, including a GAL media
     * replacement on a live connection. Decoder restarts and surface changes keep the bitstream.
     */
    fun endsSession(reason: String): Boolean =
        !isDecoderRestart(reason) && reason !in SURFACE_LIFECYCLE_REASONS

    internal fun replacesGalMediaStream(reason: String): Boolean =
        reason == REASON_GAL_START_REPLACED || reason == REASON_GAL_SETUP_REPLACED_PREPARATION ||
            reason == REASON_GAL_PRE_START_CSD
}
