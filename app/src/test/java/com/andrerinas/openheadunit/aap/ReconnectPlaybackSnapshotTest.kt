package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test

class ReconnectPlaybackSnapshotTest {
    @Test fun `failed attempts preserve playback and its original expiry time`() {
        val snapshot = ReconnectPlaybackSnapshot()
        snapshot.onDisconnected(true, 100, false)
        snapshot.onDisconnected(null, 200, false)
        snapshot.onDisconnected(null, 300, false)
        assertEquals(ReconnectPlaybackSnapshot.Resume(true, 900), snapshot.consume(1000))
        assertFalse(snapshot.consume(1001).wasPlaying)
    }

    @Test fun `a paused session replaces the previous playing session`() {
        val snapshot = ReconnectPlaybackSnapshot()
        snapshot.onDisconnected(true, 100, false)
        snapshot.onDisconnected(false, 200, false)
        assertEquals(ReconnectPlaybackSnapshot.Resume(false, 100), snapshot.consume(300))
    }

    @Test fun `a deliberate exit cancels resume even without a new playback report`() {
        val snapshot = ReconnectPlaybackSnapshot()
        snapshot.onDisconnected(true, 100, false)
        snapshot.onDisconnected(null, 200, true)
        assertFalse(snapshot.consume(300).wasPlaying)
    }
}
