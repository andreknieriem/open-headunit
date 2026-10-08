package com.andrerinas.openheadunit.decoder.video

import android.content.Context
import android.content.SharedPreferences
import android.view.Surface
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/** Exercise the real decoder's ownership operations while the platform Surface is still valid. */
class VideoSurfaceRetirementTest {
    private fun decoder(): VideoDecoder {
        val context = mock(Context::class.java)
        val preferences = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(preferences)
        `when`(preferences.getInt(anyString(), anyInt())).thenAnswer { it.arguments[1] }
        return VideoDecoder(Settings(context))
    }

    private fun surface(): Surface = mock(Surface::class.java).also {
        `when`(it.isValid).thenReturn(true)
    }

    @Test fun `retirement removes a still valid target before the view releases it`() {
        val decoder = decoder()
        val target = surface()
        decoder.setSurface(target)
        decoder.stopIfCurrentSurface(target, DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
        assertTrue(decoder.isCurrentSurface(target))
        assertTrue(decoder.detachSurfaceIfCurrent(target, DecoderStopPolicy.REASON_SURFACE_DESTROYED))
        val stoppedGeneration = decoder.surfaceStopGeneration
        assertTrue(target.isValid)
        assertTrue(decoder.isCurrentSurface(target))
        assertFalse(decoder.stopIfCurrentSurface(target, DecoderStopPolicy.REASON_ACTIVITY_STOPPED))
        assertTrue(decoder.detachSurfaceIfCurrent(target, DecoderStopPolicy.REASON_SURFACE_DESTROYED))
        assertTrue(decoder.isCurrentSurface(target))
        assertEquals(stoppedGeneration, decoder.surfaceStopGeneration)
    }

    @Test fun `late old activity callbacks cannot retire the replacement target`() {
        val decoder = decoder()
        val old = surface()
        val current = surface()
        decoder.setSurface(old)
        decoder.detachSurfaceIfCurrent(old, DecoderStopPolicy.REASON_SURFACE_DESTROYED)
        decoder.setSurface(current)
        assertFalse(decoder.isCurrentSurface(old))
        assertFalse(decoder.stopIfCurrentSurface(old, DecoderStopPolicy.REASON_ACTIVITY_STOPPED))
        assertFalse(decoder.detachSurfaceIfCurrent(old, DecoderStopPolicy.REASON_SURFACE_DESTROYED))
        assertTrue(decoder.isCurrentSurface(current))
    }

    @Test fun `a target can be claimed again only by an explicit surface callback`() {
        val decoder = decoder()
        val target = surface()
        decoder.setSurface(target)
        decoder.detachSurfaceIfCurrent(target, DecoderStopPolicy.REASON_PROJECTION_VIEW_RECREATE)
        decoder.stop(DecoderStopPolicy.REASON_ACTIVITY_STOPPED)
        assertTrue(decoder.isCurrentSurface(target))
        assertFalse(decoder.stopIfCurrentSurface(target, DecoderStopPolicy.REASON_ACTIVITY_STOPPED))
        decoder.setSurface(target)
        assertTrue(decoder.isCurrentSurface(target))
    }
    @Test fun `service sleep re-arms retained surface once without activity stop`() {
        val decoder = decoder()
        val surface = surface()
        val recovery = SurfaceRecoveryState()
        decoder.setSurface(surface)
        recovery.onSurfaceChanged(true, decoder.surfaceStopGeneration)
        recovery.cycleSpent = true
        decoder.stop(DecoderStopPolicy.REASON_SCREEN_OFF_SLEEP)
        assertTrue(decoder.isCurrentSurface(surface))
        // TEXTURE can resume without onSurfaceChanged; the delayed return path consumes the
        // stop generation and the later same-surface callback must not grant a second cycle.
        val stopped = recovery.onSurfaceChanged(false, decoder.surfaceStopGeneration)
        assertTrue(com.andrerinas.openheadunit.aap.ReturnRearmPolicy.shouldRearm(stopped, true, 100L, 200L))
        assertFalse(recovery.cycleSpent)
        recovery.cycleSpent = true
        assertFalse(recovery.onSurfaceChanged(false, decoder.surfaceStopGeneration))
        decoder.stop("restart: sync_stall")
        assertFalse(recovery.onSurfaceChanged(false, decoder.surfaceStopGeneration))
        assertTrue(recovery.cycleSpent)
    }

}
