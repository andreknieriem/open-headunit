package com.andrerinas.openheadunit.decoder.video

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reason strings here are the ones the real call sites pass. Referencing the constants means a
 * rename cannot silently move a stop from one category to the other.
 */
class DecoderStopPolicyTest {

    @Test
    fun `a surface teardown does not end the session`() {
        assertFalse(DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_SURFACE_DESTROYED))
        assertFalse(DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW))
        assertFalse(
            DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_PROJECTION_VIEW_RECREATE)
        )
        assertFalse(DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_NEW_SURFACE))
        assertFalse(DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_SCREEN_OFF_SLEEP))
        assertFalse(DecoderStopPolicy.endsSession(DecoderStopPolicy.REASON_ACTIVITY_STOPPED))
    }

    @Test
    fun `the decoder's own restarts do not end the session`() {
        assertTrue(DecoderStopPolicy.isDecoderRestart("restart: sync_stall"))
        assertTrue(DecoderStopPolicy.isDecoderRestart("restart: decoder_start_failed: timeout"))
        assertFalse(DecoderStopPolicy.endsSession("restart: sync_stall"))
        assertFalse(DecoderStopPolicy.endsSession("restart: decoder_start_failed: timeout"))
    }

    @Test
    fun `a surface teardown is not mistaken for a decoder restart`() {
        // Only a restart keeps VPS/SPS/PPS and the watchdog counters. A surface teardown must still
        // clear those, it only keeps the pinned codec type.
        assertFalse(DecoderStopPolicy.isDecoderRestart(DecoderStopPolicy.REASON_SURFACE_DESTROYED))
        assertFalse(
            DecoderStopPolicy.isDecoderRestart(DecoderStopPolicy.REASON_DETACHED_FROM_WINDOW)
        )
        assertFalse(
            DecoderStopPolicy.isDecoderRestart(DecoderStopPolicy.REASON_PROJECTION_VIEW_RECREATE)
        )
        assertFalse(DecoderStopPolicy.isDecoderRestart(DecoderStopPolicy.REASON_NEW_SURFACE))
    }

    @Test
    fun `a disconnect ends the session`() {
        // These two clear the codec pin, which is what lets a reconnect renegotiate a new codec.
        assertTrue(DecoderStopPolicy.endsSession("CommManager: doDisconnect"))
        assertTrue(DecoderStopPolicy.endsSession("AapService::onDisconnect"))
    }

    @Test
    fun `an unrecognised reason ends the session`() {
        assertTrue(DecoderStopPolicy.endsSession("unknown"))
        assertTrue(DecoderStopPolicy.endsSession(""))
        assertTrue(DecoderStopPolicy.endsSession("something nobody has written yet"))
    }

    @Test
    fun `matching is exact, not a prefix or a substring`() {
        assertTrue(DecoderStopPolicy.endsSession("surfaceDestroyed unexpectedly"))
        assertTrue(DecoderStopPolicy.endsSession("pre-surfaceDestroyed"))
    }
}
