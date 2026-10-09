package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SettingsRestartWakeTest {
    @Test fun `save wake gate expires without a connection and keeps ordinary stand down intact`() {
        mockStatic(android.os.SystemClock::class.java).use { clock ->
            var now = 100L
            clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenAnswer { now }
            val comm = mock(CommManager::class.java)
            val state = CommManager.ConnectionState.Disconnected(
                reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = 200L,
            )
            val flow = MutableStateFlow<CommManager.ConnectionState>(state)
            `when`(comm.connectionState).thenReturn(flow)
            val native = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
            NativeAaHandshakeManager::class.java.getDeclaredField("commManager")
                .apply { isAccessible = true }.set(native, comm)
            assertFalse(native.wakesPhone())
            native.noteSessionEnded(true)
            now = 199L
            assertFalse(native.wakesPhone())
            now = 200L
            assertSame(state, flow.value)
            assertTrue(native.wakesPhone())
            native.noteSessionEnded(false)
            assertFalse(native.wakesPhone())
            flow.value = CommManager.ConnectionState.Disconnected()
            assertFalse(native.wakesPhone())
        }
    }
    @Test fun `Save rearm can wake immediately but user stand-down still wins`() {
        mockStatic(android.os.SystemClock::class.java).use {
            val comm = mock(CommManager::class.java)
            val saved = CommManager.ConnectionState.Disconnected(
                reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = 30_000L,
            )
            `when`(comm.connectionState).thenReturn(MutableStateFlow(saved))
            val native = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
            NativeAaHandshakeManager::class.java.getDeclaredField("commManager")
                .apply { isAccessible = true }.set(native, comm)
            saved.releaseSettingsWake()
            native.noteSessionEnded(true)
            assertTrue(native.wakesPhone())
            native.noteSessionEnded(false)
            saved.cancelSettingsRestart()
            assertFalse(native.wakesPhone())
        }
    }
    @Test fun `settings stand-down leaves job ownership intact while automatic workers use the wake gate`() {
        mockStatic(android.os.SystemClock::class.java).use {
            for (manual in listOf(false, true)) {
                val native = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
                val job = kotlinx.coroutines.Job()
                fun set(name: String, value: Any?) = NativeAaHandshakeManager::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(native, value)
                set("pokeJob", job)
                set("manualPokeInFlight", manual)
                native.noteSessionEnded(false)
                assertFalse(job.isCancelled)
                val owner = NativeAaHandshakeManager::class.java.getDeclaredField("pokeJob").apply { isAccessible = true }
                assertSame(job, owner.get(native))
                job.cancel()
            }
        }
    }

    @Test fun `deadline refresh preserves an in-flight handshake and rereads credentials`() {
        val launcher = mock(com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative::class.java, CALLS_REAL_METHODS)
        val manager = mock(com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager::class.java)
        val shared = mock(com.andrerinas.openheadunit.connection.wifi.WifiLauncherSharedServices::class.java)
        doReturn(manager).`when`(launcher).manager
        `when`(manager.sharedServices).thenReturn(shared)
        doNothing().`when`(launcher).triggerWifiDirectRefresh()
        val native = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        val handshake = kotlinx.coroutines.Job()
        NativeAaHandshakeManager::class.java.getDeclaredField("activeHandshakeJob")
            .apply { isAccessible = true }.set(native, handshake)
        com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative::class.java
            .getDeclaredField("handshakeManager").apply { isAccessible = true }.set(launcher, native)
        launcher.refreshAfterWake()
        assertTrue(handshake.isActive)
        verify(native, never()).rearmForNextSession()
        verify(launcher).triggerWifiDirectRefresh()
        handshake.cancel()
    }

}
