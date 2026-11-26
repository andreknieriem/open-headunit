package com.andrerinas.openheadunit.aap

import android.support.v4.media.session.PlaybackStateCompat
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback.MediaPlaybackStatus.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionStatePolicyTest {

    @Test
    fun `the phone's paused and stopped states stay apart`() {
        assertTrue(MediaSessionStatePolicy.phonePaused(State.PAUSED, false))
        assertFalse(MediaSessionStatePolicy.phonePaused(State.STOPPED, true))
        assertFalse(MediaSessionStatePolicy.phonePaused(State.PLAYING, true))
    }

    @Test
    fun `a status with no state keeps the last answer`() {
        assertTrue(MediaSessionStatePolicy.phonePaused(null, true))
        assertFalse(MediaSessionStatePolicy.phonePaused(null, false))
    }

    @Test
    fun `a focus loss after the phone paused keeps the session paused`() {
        val paused = MediaSessionStatePolicy.phonePaused(State.PAUSED, false)
        assertEquals(PlaybackStateCompat.STATE_PAUSED, MediaSessionStatePolicy.sessionState(false, paused))
    }

    @Test
    fun `the session reports playing, paused or stopped`() {
        assertEquals(PlaybackStateCompat.STATE_PLAYING, MediaSessionStatePolicy.sessionState(true, false))
        assertEquals(PlaybackStateCompat.STATE_PLAYING, MediaSessionStatePolicy.sessionState(true, true))
        assertEquals(PlaybackStateCompat.STATE_PAUSED, MediaSessionStatePolicy.sessionState(false, true))
        assertEquals(PlaybackStateCompat.STATE_STOPPED, MediaSessionStatePolicy.sessionState(false, false))
    }
}
