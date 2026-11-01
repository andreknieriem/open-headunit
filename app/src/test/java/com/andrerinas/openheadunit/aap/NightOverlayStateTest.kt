package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.MediaPlayback
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NightOverlayStateTest {
    private fun metadata(title: String, artist: String = "") = MediaPlayback.MediaMetaData.newBuilder()
        .setSong(title).setArtist(artist).build()

    private fun playback(state: MediaPlayback.MediaPlaybackStatus.State) =
        MediaPlayback.MediaPlaybackStatus.newBuilder().setState(state).build()

    @Test fun metadataAndNavigationAreRetainedForAnOverlayOpenedLater() {
        val state = NightOverlayState()
        val session = AaPresentationSession()
        state.begin(session)
        state.metadata(session, metadata(" Song ", " Artist "))
        state.playback(session, playback(MediaPlayback.MediaPlaybackStatus.State.PLAYING))
        state.navigation(session, NightOverlayState.Navigation("Turn left", 250, null))

        assertEquals(
            NightOverlayState.Content(NightOverlayState.Navigation("Turn left", 250, null), "Song", "Artist", true),
            state.content.value
        )
    }

    @Test fun positionOnlyUpdateDoesNotErasePlaybackStateAndPauseKeepsMetadata() {
        val state = NightOverlayState()
        val session = AaPresentationSession()
        state.begin(session)
        state.metadata(session, metadata("Song", "Artist"))
        state.playback(session, playback(MediaPlayback.MediaPlaybackStatus.State.PLAYING))
        state.playback(session, MediaPlayback.MediaPlaybackStatus.newBuilder().setPlaybackSeconds(50).build())
        assertTrue(state.content.value.isPlaying!!)
        state.playback(session, playback(MediaPlayback.MediaPlaybackStatus.State.PAUSED))
        assertFalse(state.content.value.isPlaying!!)
        assertEquals("Song", state.content.value.title)
        state.playback(session, playback(MediaPlayback.MediaPlaybackStatus.State.PLAYING))
        assertTrue(state.content.value.isPlaying!!)
    }

    @Test fun clearingNavigationDoesNotEraseMusic() {
        val state = NightOverlayState()
        val session = AaPresentationSession()
        state.begin(session)
        state.metadata(session, metadata("Song"))
        state.navigation(session, NightOverlayState.Navigation("Turn", null, null))
        state.navigation(session, null)
        assertNull(state.content.value.navigation)
        assertEquals("Song", state.content.value.title)
    }

    @Test fun newTrackWithoutArtistDoesNotKeepPreviousArtist() {
        val state = NightOverlayState()
        val session = AaPresentationSession()
        state.begin(session)
        state.metadata(session, metadata("First", "Artist"))
        state.metadata(session, metadata("Second"))
        assertEquals("Second", state.content.value.title)
        assertEquals("", state.content.value.artist)
    }

    @Test fun reconnectResetsContentAndLateOldUpdatesCannotReplaceNewContent() {
        val state = NightOverlayState()
        val old = AaPresentationSession()
        state.begin(old)
        state.metadata(old, metadata("Old"))
        state.navigation(old, NightOverlayState.Navigation("Old turn", 500, null))
        val current = AaPresentationSession()
        state.begin(current)
        assertEquals(NightOverlayState.Content(), state.content.value)
        state.metadata(current, metadata("New"))

        state.metadata(old, metadata("Stale"))
        state.navigation(old, NightOverlayState.Navigation("Stale turn", 100, null))
        state.playback(old, playback(MediaPlayback.MediaPlaybackStatus.State.STOPPED))
        state.close(old)
        assertEquals(NightOverlayState.Content(title = "New"), state.content.value)
    }

    @Test fun retiringSessionRejectsPendingUpdatesAndClosingClearsEverything() {
        val state = NightOverlayState()
        val session = AaPresentationSession()
        state.begin(session)
        state.metadata(session, metadata("Song", "Artist"))
        session.retire()
        state.metadata(session, metadata("Stale"))
        assertEquals("Song", state.content.value.title)
        state.close(session)
        assertEquals(NightOverlayState.Content(), state.content.value)
    }
}
