package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.CommManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SettingsRestartWakeTest {
    @Test fun `settings reason blocks automatic wake before service observes teardown`() {
        val comm = mock(CommManager::class.java)
        val flow = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.TransportStarted)
        `when`(comm.connectionState).thenReturn(flow)
        val native = mock(NativeAaHandshakeManager::class.java, CALLS_REAL_METHODS)
        NativeAaHandshakeManager::class.java.getDeclaredField("commManager").apply { isAccessible = true }.set(native, comm)
        flow.value = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        assertFalse(native.wakesPhone())
        flow.value = CommManager.ConnectionState.Disconnected()
        assertTrue(native.wakesPhone())
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

}
