package com.andrerinas.openheadunit.connection

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class SettingsRestartFailureTest {
    private fun manager(flow: MutableStateFlow<CommManager.ConnectionState>): CommManager =
        mock(CommManager::class.java, CALLS_REAL_METHODS).also { manager ->
            for ((name, value) in listOf("_connectionState" to flow, "connectionState" to flow,
                "transportLifecycleLock" to Any())) {
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
            }
        }

    @Test fun `same Save launch failure preserves its automatic fallback`() = runBlocking {
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        val manager = manager(flow)
        var rearms = 0
        SettingsRestartRecovery.run(0, { saved.acceptsSettingsRestart(flow.value) },
            { manager.reportError("No launch method succeeded", saved) }, { rearms++ })
        assertSame(saved, flow.value)
        assertEquals(1, rearms)
    }

    @Test fun `late Save failures cannot replace a newer connection or revive cancelled recovery`() = runBlocking {
        for (replacement in listOf(CommManager.ConnectionState.Connecting, CommManager.ConnectionState.Connected,
            CommManager.ConnectionState.Disconnected(), CommManager.ConnectionState.Error("new failure"))) {
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            val flow = MutableStateFlow<CommManager.ConnectionState>(replacement)
            manager(flow).reportError("old Save failure", saved)
            assertSame(replacement, flow.value)
            assertFalse(saved.acceptsSettingsRestart(flow.value))
        }
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
            settingsRestartUntilMs = Long.MAX_VALUE)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        val manager = manager(flow)
        manager.disconnect() // Already Disconnected: the real early-return path must still revoke Save.
        manager.reportError("cancelled Save failure", saved)
        assertSame(saved, flow.value)
        assertFalse(saved.acceptsSettingsRestart(flow.value))
        assertFalse(saved.holdsSettingsWake(0))
        SettingsRestartRecovery.run(0, { saved.acceptsSettingsRestart(flow.value) },
            { fail("cancelled retry") }, { fail("cancelled fallback") })
    }
}
