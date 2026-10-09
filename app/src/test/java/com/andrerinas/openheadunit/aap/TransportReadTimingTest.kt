package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.decoder.audio.AudioDiagnostics
import org.mockito.kotlin.mock
import org.mockito.Mockito.CALLS_REAL_METHODS

class TransportReadTimingTest {
    @Test fun `idle header waiting cannot consume the slow processing diagnostic budget`() {
        assertFalse(TransportReadTiming.isProcessingSlow(0, 2))
        assertTrue(TransportReadTiming.isProcessingSlow(60, 2))
        assertTrue(TransportReadTiming.isProcessingSlow(0, 75))
    }

    @Test fun `video read cannot use the audio history slot or throttle a following audio report`() {
        val transport = mock<AapTransport>(defaultAnswer = CALLS_REAL_METHODS)
        val active = AapTransport::class.java.getDeclaredField("audioTimingActive").apply { isAccessible = true }
        active.setBoolean(transport, true)
        val before = AudioDiagnostics.snapshot(1000)
        val timing = TransportReadTiming(Channel.ID_VID, 9876, 0, 100, 0, 0)
        transport.recordSlowAudioRead(timing, 1000)
        assertEquals(before, AudioDiagnostics.snapshot(1000))
        transport.recordSlowAudioRead(timing.copy(channel = Channel.ID_AUD), 1000)
        val after = AudioDiagnostics.snapshot(1000)
        assertTrue(after.contains("Audio transport read channel=6 readerGap=9876ms"))
        active.setBoolean(transport, false)
        transport.recordSlowAudioRead(timing.copy(channel = Channel.ID_AUD), 2000)
        assertEquals(after, AudioDiagnostics.snapshot(1000))
    }
}
