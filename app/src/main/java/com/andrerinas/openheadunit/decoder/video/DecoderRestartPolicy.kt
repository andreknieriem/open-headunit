package com.andrerinas.openheadunit.decoder.video

/**
 * Bounds repeated starts that never decoded a frame, without changing the stream's codec.
 * A session that already rendered remains eligible for warm surface/decoder recovery: a slow
 * first frame after reconfiguration is not evidence that its established codec is wrong.
 */
object DecoderRestartPolicy {

    /**
     * True when this restart counts toward the cold-start failure limit. [renderedThisSession]
     * keeps warm reconfiguration outside that limit.
     */
    fun countsTowardFailure(
        renderedSinceLastStart: Boolean,
        renderedThisSession: Boolean
    ): Boolean = !renderedSinceLastStart && !renderedThisSession
}
