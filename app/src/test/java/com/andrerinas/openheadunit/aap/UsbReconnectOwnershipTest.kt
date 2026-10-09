package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class UsbReconnectOwnershipTest {
    private class QueuedMain : CoroutineDispatcher() {
        private val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    @Test fun usbRecheckSurvivesItsConnectingStateButNotAnotherSession() = runBlocking {
        for (observed in listOf(false, true)) for (outcome in listOf(
            "connecting", "error", "wireless-retry", "new-session", "user-exit", "settings-save")) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val ended = ConnectionState.Disconnected()
            val states = MutableStateFlow<ConnectionState>(ended)
            fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            set("transportLifecycleLock", Any())
            set("_connectionState", states)
            set("usbRecoveryOwner", Any())
            set("context", mock(Context::class.java))
            set("settings", mock(Settings::class.java))
            val main = QueuedMain()
            val scope = CoroutineScope(SupervisorJob() + main)
            val deadline = CompletableDeferred<Unit>()
            var checks = 0
            val retry = AutomaticReconnect(scope, { states.value }, { false }, { deadline.await() })
            val owner = manager.usbRecheckOwner(ended)!!
            retry.schedule(ended, 3000, ownsCheck = { manager.ownsUsbRecheck(owner) }) { checks++ }
            val wireless = AutomaticReconnect(scope, { states.value }, { false }, { deadline.await() })
            main.drain()
            val connect = CommManager::class.java.getDeclaredMethod("connectIp",
                String::class.java, Int::class.javaPrimitiveType,
                ConnectionState.Disconnected::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            mockConstruction(SocketProjectionConnection::class.java) { candidate, _ ->
                runBlocking {
                    `when`(candidate.connect()).thenAnswer {
                        // A physical attempt does not fulfill the recovery interval.
                        assertTrue(manager.ownsUsbRecheck(owner))
                        when (outcome) {
                            "error" -> states.value = ConnectionState.Error("USB permission not granted for device")
                            "wireless-retry" -> {
                                states.value = ConnectionState.Disconnected()
                                wireless.schedule(states.value, 2000) {}
                                main.drain()
                            }
                            "new-session" -> {
                                // Model a fulfilled interval whose live emissions were all skipped.
                                // The host harness checks that real handshake completion rotates it.
                                set("usbRecoveryOwner", Any())
                                states.value = ConnectionState.Disconnected()
                            }
                            "user-exit", "settings-save" -> {
                                states.value = ended
                                // Even an already-disconnected stop/Save must revoke old checks.
                                manager.disconnect(isUserExit = outcome == "user-exit",
                                    reason = if (outcome == "settings-save") CommManager.DisconnectReason.SETTINGS_RESTART
                                        else CommManager.DisconnectReason.CONNECTION_ENDED)
                            }
                        }
                        if (observed) retry.onStateChanged()
                        deadline.complete(Unit)
                        main.drain()
                        val allowed = outcome in listOf("connecting", "error", "wireless-retry")
                        assertEquals("$outcome observed=$observed", if (allowed) 1 else 0, checks)
                        // Retire locally without entering unrelated transport cleanup.
                        set("connectionAttempt", Any())
                        false
                    }
                }
            }.use {
                try {
                    suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                        connect.invoke(manager, "127.0.0.1", 5277, null, continuation)
                    }
                } finally { scope.cancel(); main.drain() }
            }
        }
    }

    @Test fun ordinaryDiscoveryTimerStillEndsAtConnecting() = runBlocking {
        val main = QueuedMain()
        val scope = CoroutineScope(SupervisorJob() + main)
        var state: ConnectionState = ConnectionState.Disconnected()
        val deadline = CompletableDeferred<Unit>()
        var checks = 0
        val retry = AutomaticReconnect(scope, { state }, { false }, { deadline.await() })
        try {
            retry.schedule(state, 2000) { checks++ }
            main.drain()
            state = ConnectionState.Connecting
            retry.onStateChanged()
            deadline.complete(Unit)
            main.drain()
            assertEquals(0, checks)
        } finally { scope.cancel(); main.drain() }
    }
    @Test fun oldUserExitFlagDoesNotSuppressANewerWirelessRetry() {
        mockStatic(android.os.SystemClock::class.java).use {
        val main = QueuedMain()
        val scope = CoroutineScope(SupervisorJob() + main)
        val service = mock(AapService::class.java, CALLS_REAL_METHODS)
        val app = mock(com.andrerinas.openheadunit.App::class.java)
        val component = mock(com.andrerinas.openheadunit.AppComponent::class.java)
        val manager = mock(CommManager::class.java)
        val ended = ConnectionState.Disconnected()
        `when`(manager.connectionState).thenReturn(MutableStateFlow<ConnectionState>(ended))
        `when`(component.commManager).thenReturn(manager)
        doReturn(app).`when`(service).applicationContext
        com.andrerinas.openheadunit.App::class.java.getDeclaredField("component\$delegate")
            .apply { isAccessible = true }.set(app, lazyOf(component))
        AapService::class.java.getDeclaredField("serviceScope").apply { isAccessible = true }.set(service, scope)
        service.userExitedAA = true
        val factory = AapService::class.java.getDeclaredMethod("reconnectTimer").apply { isAccessible = true }
        val retry = factory.invoke(service) as AutomaticReconnect
        var calls = 0
        try {
            retry.schedule(ended, 0) { calls++ }
            main.drain()
            assertEquals(1, calls)
        } finally { scope.cancel(); main.drain() }
        }
    }

}
