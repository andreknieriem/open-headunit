package com.andrerinas.openheadunit.aap.protocol.messages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSinkAnnouncementPolicyTest {

    /**
     * The field failure: enable-audio-sink was on, the session was Native AA over WiFi Direct, and
     * only the system channel was announced because a failed Self Mode launch had left its flag set.
     */
    @Test
    fun `a session that is not Self Mode announces both sinks when the setting is on`() {
        val a = AudioSinkAnnouncementPolicy.announce(
            sinkEnabled = true,
            isSelfModeSession = false,
            mapsAudioOnly = false,
        )
        assertTrue(a.speech)
        assertTrue(a.media)
    }

    /** Self Mode's own session keeps today's behaviour; whether it should is a separate question. */
    @Test
    fun `a Self Mode session still drops both sinks`() {
        val a = AudioSinkAnnouncementPolicy.announce(
            sinkEnabled = true,
            isSelfModeSession = true,
            mapsAudioOnly = false,
        )
        assertFalse(a.speech)
        assertFalse(a.media)
    }

    /** Self Mode cannot be narrowed further; maps-only must not resurrect a sink it dropped. */
    @Test
    fun `maps audio only changes nothing in Self Mode`() {
        val a = AudioSinkAnnouncementPolicy.announce(
            sinkEnabled = true,
            isSelfModeSession = true,
            mapsAudioOnly = true,
        )
        assertFalse(a.speech)
        assertFalse(a.media)
    }

    /** The setting wins over the transport: off means off on every session. */
    @Test
    fun `the sink setting being off drops both sinks whatever the session is`() {
        for (selfMode in listOf(false, true)) {
            for (mapsOnly in listOf(false, true)) {
                val a = AudioSinkAnnouncementPolicy.announce(
                    sinkEnabled = false,
                    isSelfModeSession = selfMode,
                    mapsAudioOnly = mapsOnly,
                )
                assertFalse(a.speech)
                assertFalse(a.media)
            }
        }
    }

    /** The owner's request: the map keeps its voice, the media channel is never offered. */
    @Test
    fun `maps audio only keeps speech and drops media`() {
        val a = AudioSinkAnnouncementPolicy.announce(
            sinkEnabled = true,
            isSelfModeSession = false,
            mapsAudioOnly = true,
        )
        assertTrue(a.speech)
        assertFalse(a.media)
    }
}
