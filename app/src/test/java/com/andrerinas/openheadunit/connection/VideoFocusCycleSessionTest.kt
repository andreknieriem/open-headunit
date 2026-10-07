package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class VideoFocusCycleSessionTest {
    private fun set(target: Any, name: String, value: Any?) {
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(target, value)
    }

    @Test fun delayedGainCannotEnterReplacementTransportOrItsHandshake() {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val old = mock(AapTransport::class.java)
        val replacement = mock(AapTransport::class.java)
        val state = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.TransportStarted)
        set(manager, "_connectionState", state)
        set(manager, "_transport", old)
        whenever(old.beginFocusCycle()).thenReturn(true)
        val oldCycle = manager.releaseVideoFocusForKeyframe()
        assertNotNull(oldCycle)
        // A delayed Activity collector can miss the intermediate terminal and Connecting states.
        set(manager, "_transport", replacement)
        state.value = CommManager.ConnectionState.StartingTransport
        assertFalse(manager.retakeVideoFocusForKeyframe(oldCycle!!))
        state.value = CommManager.ConnectionState.TransportStarted
        assertFalse(manager.retakeVideoFocusForKeyframe(oldCycle))
        verify(replacement, never()).send(any<VideoFocusEvent>())
        verify(replacement, never()).endFocusCycle()

        whenever(replacement.beginFocusCycle()).thenReturn(true)
        val replacementCycle = manager.releaseVideoFocusForKeyframe()
        assertNotNull(replacementCycle)
        assertTrue(manager.retakeVideoFocusForKeyframe(replacementCycle!!))
        verify(replacement).send(any<VideoFocusEvent>())
        verify(replacement).endFocusCycle()
    }
}
