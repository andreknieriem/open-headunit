package com.andrerinas.openheadunit.aap

import android.support.v4.media.session.PlaybackStateCompat
import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback

/** The media session state for the phone's playback status. A pause stays a pause, not a stop. */
object MediaSessionStatePolicy {

    /** Whether the phone paused. A status with no state keeps the last answer. */
    fun phonePaused(state: MediaPlayback.MediaPlaybackStatus.State?, lastPaused: Boolean): Boolean =
        if (state == null) lastPaused else state == MediaPlayback.MediaPlaybackStatus.State.PAUSED

    fun sessionState(isPlaying: Boolean, paused: Boolean): Int = when {
        isPlaying -> PlaybackStateCompat.STATE_PLAYING
        paused -> PlaybackStateCompat.STATE_PAUSED
        else -> PlaybackStateCompat.STATE_STOPPED
    }
}
