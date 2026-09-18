package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.decoder.audio.PlaybackFocusPolicy.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackFocusPolicyTest {

    private fun acquire(
        mode: Mode = Mode.AUTO,
        staticAudioFocus: Boolean = false,
        audioSinkEnabled: Boolean = true,
        isAudioChannel: Boolean = true,
        selfDefeatingLatched: Boolean = false,
        isLoopbackSession: Boolean = false
    ) = PlaybackFocusPolicy.shouldAcquire(
        mode = mode,
        staticAudioFocus = staticAudioFocus,
        audioSinkEnabled = audioSinkEnabled,
        isAudioChannel = isAudioChannel,
        selfDefeatingLatched = selfDefeatingLatched,
        isLoopbackSession = isLoopbackSession
    )

    // --- the pre-existing gates, which no mode may override ---

    @Test
    fun `static focus mode never uses the dynamic path`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, staticAudioFocus = true))
        }
    }

    @Test
    fun `a disabled audio sink never takes focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, audioSinkEnabled = false))
        }
    }

    @Test
    fun `non-audio channels never take focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, isAudioChannel = false))
        }
    }

    // --- AUTO ---

    @Test
    fun `auto takes focus until the harm is observed`() {
        // The car-radio case this feature was written for, and also the head unit that unmutes its
        // amplifier from the focus request: both need us to ask.
        assertTrue(acquire(mode = Mode.AUTO))
    }

    @Test
    fun `auto declines once the latch has tripped`() {
        // Taking focus here makes the A2DP sink AVRCP-pause the phone that is feeding us, and the
        // latch is what saw it happen. This is the only thing that stops AUTO.
        assertFalse(acquire(mode = Mode.AUTO, selfDefeatingLatched = true))
    }

    @Test
    fun `auto and always agree until the latch trips`() {
        // A connected Bluetooth media link used to veto the grab here. Any paired phone has one, so
        // a head unit that routes its amplifier from the focus request played nothing at all, while
        // the harm the veto guessed at is what the latch measures directly.
        assertEquals(acquire(mode = Mode.ALWAYS), acquire(mode = Mode.AUTO))
    }

    // --- ALWAYS / NEVER ---

    @Test
    fun `always keeps taking focus whatever the detection says`() {
        assertTrue(acquire(mode = Mode.ALWAYS))
        assertTrue(acquire(mode = Mode.ALWAYS, selfDefeatingLatched = true))
    }

    @Test
    fun `never declines even when nothing has gone wrong`() {
        assertFalse(acquire(mode = Mode.NEVER, selfDefeatingLatched = false))
    }

    // --- static mode's permanent grab ---

    private fun acquirePermanent(
        mode: Mode = Mode.AUTO,
        staticAudioFocus: Boolean = true,
        audioSinkEnabled: Boolean = true,
        btMediaLinkActive: Boolean = false,
        isLoopbackSession: Boolean = false
    ) = PlaybackFocusPolicy.shouldAcquirePermanent(
        mode = mode,
        staticAudioFocus = staticAudioFocus,
        audioSinkEnabled = audioSinkEnabled,
        btMediaLinkActive = btMediaLinkActive,
        isLoopbackSession = isLoopbackSession
    )

    @Test
    fun `the permanent grab belongs to static mode alone`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquirePermanent(mode = mode, staticAudioFocus = false))
        }
    }

    @Test
    fun `a disabled audio sink never takes permanent focus`() {
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquirePermanent(mode = mode, audioSinkEnabled = false))
        }
    }

    @Test
    fun `auto takes permanent focus when no bluetooth media link is up`() {
        // Static mode's reason for existing — a generic head unit that loses audio routing —
        // is untouched for everyone this bug does not affect.
        assertTrue(acquirePermanent(mode = Mode.AUTO, btMediaLinkActive = false))
    }

    @Test
    fun `auto declines the permanent grab while a bluetooth media link is up`() {
        assertFalse(acquirePermanent(mode = Mode.AUTO, btMediaLinkActive = true))
    }

    @Test
    fun `always and never override the probe on the permanent path too`() {
        assertTrue(acquirePermanent(mode = Mode.ALWAYS, btMediaLinkActive = true))
        assertFalse(acquirePermanent(mode = Mode.NEVER, btMediaLinkActive = false))
    }

    @Test
    fun `the two paths are exact complements and never both take focus`() {
        // Same shape as WifiModePolicy vs UserExitHotspotPolicy: one decision, split across two
        // functions, so the split has to be airtight rather than merely plausible.
        for (mode in Mode.values()) {
            for (static in listOf(false, true)) {
                for (sink in listOf(false, true)) {
                    for (bt in listOf(false, true)) {
                        for (latched in listOf(false, true)) {
                            for (loopback in listOf(false, true)) {
                                val dynamic = PlaybackFocusPolicy.shouldAcquire(
                                    mode = mode,
                                    staticAudioFocus = static,
                                    audioSinkEnabled = sink,
                                    isAudioChannel = true,
                                    selfDefeatingLatched = latched,
                                    isLoopbackSession = loopback
                                )
                                val permanent = acquirePermanent(
                                    mode = mode,
                                    staticAudioFocus = static,
                                    audioSinkEnabled = sink,
                                    btMediaLinkActive = bt,
                                    isLoopbackSession = loopback
                                )
                                assertFalse(
                                    "mode=$mode static=$static sink=$sink bt=$bt " +
                                        "latched=$latched loopback=$loopback",
                                    dynamic && permanent
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Self Mode ---

    @Test
    fun `self mode never takes focus on either path, whatever the mode says`() {
        // Self Mode announces no media or speech sink, so the player we would evict is the one on
        // this device. ALWAYS is included because it speaks for a session to a separate phone.
        for (mode in Mode.values()) {
            assertFalse("mode=$mode", acquire(mode = mode, isLoopbackSession = true))
            assertFalse("mode=$mode", acquirePermanent(mode = mode, isLoopbackSession = true))
        }
    }

    @Test
    fun `self mode outranks the latch, which cannot learn this one`() {
        // countsAsSelfDefeating counts only the media channel, and Self Mode never opens it, so
        // AUTO would otherwise grab focus for the whole session with nothing able to correct it.
        assertFalse(acquire(mode = Mode.AUTO, selfDefeatingLatched = false, isLoopbackSession = true))
    }

    @Test
    fun `a session that is not self mode is unaffected`() {
        // The regression this guard must never cause: the ordinary USB and wireless sessions.
        assertTrue(acquire(mode = Mode.AUTO, isLoopbackSession = false))
        assertTrue(acquirePermanent(mode = Mode.AUTO, isLoopbackSession = false))
    }

    // --- the self-defeating detector ---

    @Test
    fun `a media channel stopping inside the window counts`() {
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 0L))
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 3_400L))
        assertTrue(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 4_070L))
    }

    @Test
    fun `a media channel stopping outside the window does not count`() {
        assertFalse(
            PlaybackFocusPolicy.countsAsSelfDefeating(
                isMediaChannel = true,
                msSinceAcquire = PlaybackFocusPolicy.SELF_DEFEATING_WINDOW_MS
            )
        )
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = 60_000L))
    }

    @Test
    fun `speech and system chimes never count, however short`() {
        // A navigation prompt is legitimately a second long; counting it would latch on one turn.
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = false, msSinceAcquire = 900L))
    }

    @Test
    fun `a negative elapsed time does not count`() {
        // No acquire recorded yet, so there is nothing to attribute the stop to.
        assertFalse(PlaybackFocusPolicy.countsAsSelfDefeating(isMediaChannel = true, msSinceAcquire = -1L))
    }

    // --- mode round-tripping through settings ---

    @Test
    fun `modes round-trip through their stored value and unknown values fall back to auto`() {
        for (mode in Mode.values()) {
            assertEquals(mode, Mode.fromInt(mode.value))
        }
        assertEquals(Mode.AUTO, Mode.fromInt(-1))
        assertEquals(Mode.AUTO, Mode.fromInt(99))
    }

    @Test
    fun `auto is the stored default so an unset preference takes focus until it learns not to`() {
        assertEquals(0, Mode.AUTO.value)
    }
}
