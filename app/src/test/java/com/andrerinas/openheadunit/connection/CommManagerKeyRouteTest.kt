package com.andrerinas.openheadunit.connection

import android.view.KeyEvent
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.input.InputAction
import com.andrerinas.openheadunit.input.LocalAction
import com.andrerinas.openheadunit.input.LockedInputPolicy
import com.andrerinas.openheadunit.input.MediaKeyRoutingPolicy
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Drives the real [CommManager.sendKey] with a mocked transport and settings. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class CommManagerKeyRouteTest {
    private val volumeUp = KeyEvent.KEYCODE_VOLUME_UP
    private val volumeMute = KeyEvent.KEYCODE_VOLUME_MUTE

    private class Rig(bindings: Map<Int, InputAction>, volumeToPhone: Boolean = false) {
        val transport: AapTransport = mock(AapTransport::class.java)
        val comm: CommManager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val keyStates = mutableMapOf<Int, KeyDebouncePolicy.KeyState>()

        init {
            val settings = mock(Settings::class.java)
            `when`(settings.keyBindings).thenReturn(bindings)
            `when`(settings.volumeKeysToPhone).thenReturn(volumeToPhone)
            `when`(settings.mediaKeyRouting).thenReturn(MediaKeyRoutingPolicy.Mode.ALWAYS)
            set("settings", settings)
            set("_connectionState", MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.TransportStarted))
            set("_transport", transport)
            set("keyStates", keyStates)
        }

        private fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(comm, value)
    }

    @Test fun unmappedVolumeSendsNothingFromEveryRoute() {
        for (src in listOf("carkey", "key-broadcast", "media-key-intent", "remote-command", "remote-keycode")) {
            val r = Rig(emptyMap())
            r.comm.sendKey(volumeUp, true, null, src)
            verify(r.transport, never()).send(anyInt(), anyBoolean())
        }
    }

    @Test fun boundVolumeGoesOutAsItsAction() {
        val r = Rig(mapOf(volumeUp to InputAction.AaKey(KeyEvent.KEYCODE_DPAD_CENTER)))
        r.comm.sendKey(volumeUp, true, null, "carkey")
        verify(r.transport).send(KeyEvent.KEYCODE_DPAD_CENTER, true)
    }

    @Test fun aLockedUnitDoesNotRunAVolumeKeysLocalAction() {
        val r = Rig(mapOf(volumeUp to InputAction.Local(LocalAction.DIM_SCREEN)))
        val actions = MutableSharedFlow<LocalAction>(replay = 1)
        CommManager::class.java.getDeclaredField("_localActions").apply { isAccessible = true }.set(r.comm, actions)
        LockedInputPolicy.engaged = true
        try {
            r.comm.sendKey(volumeUp, true, null, "carkey")
            r.comm.sendKey(volumeUp, false, null, "carkey")
        } finally {
            LockedInputPolicy.engaged = false
        }
        assertTrue(actions.replayCache.isEmpty())
        r.comm.sendKey(volumeUp, false, null, "carkey")
        assertEquals(listOf(LocalAction.DIM_SCREEN), actions.replayCache)
    }

    @Test fun volumeBoundToNothingStaysLocal() {
        val r = Rig(mapOf(volumeUp to InputAction.None))
        r.comm.sendKey(volumeUp, true, null, "carkey")
        verify(r.transport, never()).send(anyInt(), anyBoolean())
    }

    @Test fun volumeToPhoneSendsFromEveryRoute() {
        for (src in listOf("carkey", "key-broadcast", "remote-keycode", "volume-to-phone")) {
            val r = Rig(emptyMap(), volumeToPhone = true)
            r.comm.sendKey(volumeUp, true, null, src)
            verify(r.transport).send(volumeUp, true)
        }
    }

    @Test fun muteCommandGoesOut() {
        val r = Rig(emptyMap())
        r.comm.sendKey(volumeMute, true, null, "remote-mute")
        verify(r.transport).send(volumeMute, true)
    }

    @Test fun rawMuteKeycodeStaysLocal() {
        val r = Rig(emptyMap())
        r.comm.sendKey(volumeMute, true, null, "remote-keycode")
        verify(r.transport, never()).send(anyInt(), anyBoolean())
    }

    @Test fun aLocalKeyWritesNoKeyState() {
        val r = Rig(emptyMap())
        r.comm.sendKey(volumeUp, true, 10L, "carkey")
        assertTrue(r.keyStates.isEmpty())
    }
}
