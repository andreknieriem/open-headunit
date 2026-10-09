package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import com.andrerinas.openheadunit.connection.*
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

/** Exercise the actual Nearby transfer into the serialized session owner. */
class NearbySessionPublicationTest {
    @Test fun preparedTunnelPublishesOnlyWhileBothOwnersRemainCurrent() = runBlocking {
        for (fromError in listOf(false, true)) for (outcome in listOf("connected", "replacement", "retired", "new-error", "owned", "transport-owned", "saved-request")) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val initial: CommManager.ConnectionState = if (fromError)
                CommManager.ConnectionState.Error("USB permission not granted for device")
            else CommManager.ConnectionState.Disconnected()
            val flow = MutableStateFlow(initial)
            val queued = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
            val dispatcher = object : CoroutineDispatcher() {
                override fun dispatch(context: CoroutineContext, block: Runnable) { queued.add(block) }
            }
            val scope = CoroutineScope(SupervisorJob() + dispatcher)
            fun set(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            fun get(name: String) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.get(manager)
            set("transportLifecycleLock", Any())
            set("_connectionState", flow)
            set("context", mock(Context::class.java))
            set("settings", mock(Settings::class.java))
            set("_scope", scope)
            // The previous teardown leaves this set; a valid transfer must re-arm the new owner.
            set("disconnectRequested", true)
            val guard = NearbyAttemptGuard()
            val attempt = guard.begin("phone")
            val admission = ConnectionAdmission { action -> guard.run(attempt, action = action) }
            val socket = mock(Socket::class.java)
            val replacement = mock(SocketProjectionConnection::class.java)
            val replacementTransport = mock(com.andrerinas.openheadunit.aap.AapTransport::class.java)
            val method = CommManager::class.java.getDeclaredMethod("connectAdmittedSocket",
                Socket::class.java, ConnectionAdmission::class.java, Pair::class.java,
                CommManager.ConnectionState.Disconnected::class.java, Continuation::class.java)
                .apply { isAccessible = true }
            mockConstruction(SocketProjectionConnection::class.java) { candidate, _ ->
                runBlocking {
                    `when`(candidate.connect()).thenAnswer {
                        if (outcome == "replacement") {
                            set("_connection", replacement)
                            flow.value = CommManager.ConnectionState.TransportStarted
                        } else if (outcome == "retired") guard.retire {}
                        else if (outcome == "new-error") {
                            // A later failure must not inherit the earlier error's admission.
                            flow.value = CommManager.ConnectionState.Error("Another connection failed")
                        } else if (outcome == "owned") set("_connection", replacement)
                        else if (outcome == "transport-owned") set("_transport", replacementTransport)
                        true
                    }
                }
            }.use { candidates ->
                try {
                    var rejected = false
                    try {
                        suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                            method.invoke(manager, socket, admission, null,
                                if (outcome == "saved-request") CommManager.ConnectionState.Disconnected(
                                    reason = CommManager.DisconnectReason.SETTINGS_RESTART) else null, continuation)
                        }
                    } catch (e: java.lang.reflect.InvocationTargetException) {
                        if (e.cause !is ConnectionAdmissionRejectedException) throw e
                        rejected = true
                    } catch (_: ConnectionAdmissionRejectedException) { rejected = true }
                    val candidate = candidates.constructed().single()
                    assertEquals(outcome != "connected", rejected)
                    if (outcome == "connected") {
                        assertSame(candidate, get("_connection"))
                        assertSame(CommManager.ConnectionState.Connected, flow.value)
                        assertNotNull(get("connectionAttempt"))
                        verify(candidate, never()).disconnect()
                        manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                        assertTrue((flow.value as CommManager.ConnectionState.Disconnected).hadPhysicalConnection)
                        assertEquals(1, queued.size)
                    } else {
                        verify(candidate).disconnect()
                        verify(socket).close()
                        assertSame(if (outcome == "replacement" || outcome == "owned") replacement else null, get("_connection"))
                        assertSame(if (outcome == "transport-owned") replacementTransport else null, get("_transport"))
                        verifyNoInteractions(replacement, replacementTransport)
                        assertTrue(queued.isEmpty())
                    }
                } finally { scope.cancel() }
            }
        }
    }
}
