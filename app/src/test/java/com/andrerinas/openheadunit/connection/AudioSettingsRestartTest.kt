package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.AapAudio
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class AudioSettingsRestartTest {
    private fun field(manager: CommManager, name: String, value: Any?) {
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
    }

    @Test fun `quick audio restart ignores unapplied session settings`() {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val transport = mock(AapTransport::class.java)
        val audio = mock(AapAudio::class.java)
        field(manager, "transportLifecycleLock", Any())
        field(manager, "_transport", transport)
        `when`(transport.aapAudio).thenReturn(audio)
        `when`(audio.needsSessionRestart()).thenReturn(true)
        manager.restartAudio()
        verify(audio).restartAudio()
        verify(audio, never()).needsSessionRestart()
        verify(manager, never()).applyAudioSettings()
    }

}
