package com.andrerinas.openheadunit.connection

import android.content.Context
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.CoroutineContext

class UsbRecheckHoldTest {
    private object Never : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private fun manager(state: ConnectionState, owner: Any): Pair<CommManager, MutableStateFlow<ConnectionState>> {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val states = MutableStateFlow(state)
        fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(manager, value)
        set("transportLifecycleLock", Any())
        set("_connectionState", states)
        set("usbRecoveryOwner", owner)
        set("_scope", CoroutineScope(SupervisorJob() + Never))
        set("context", mock(Context::class.java))
        set("settings", mock(Settings::class.java))
        return manager to states
    }

    @Test fun aHeldEndOwesNoUsbRecheck() {
        for (reason in listOf(CommManager.DisconnectReason.PROJECTION_UNRAISED, CommManager.DisconnectReason.CONNECTION_ENDED)) {
            val ended = ConnectionState.Disconnected(reason = reason)
            val owner = Any()
            val (manager, _) = manager(ended, owner)
            if (reason == CommManager.DisconnectReason.PROJECTION_UNRAISED) {
                assertNull(manager.usbRecheckOwner(ended))
            } else {
                assertSame(owner, manager.usbRecheckOwner(ended))
            }
        }
    }

    @Test fun aHeldDisconnectRotatesTheOwner() {
        val owner = Any()
        val (manager, states) = manager(ConnectionState.HandshakeComplete, owner)
        manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false,
            reason = CommManager.DisconnectReason.PROJECTION_UNRAISED)
        val ended = states.value as ConnectionState.Disconnected
        assertTrue(ended.holdsRecovery)
        assertFalse(manager.ownsUsbRecheck(owner))
    }
}
