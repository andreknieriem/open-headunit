package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import com.andrerinas.openheadunit.connection.*
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.net.Socket
import kotlin.coroutines.CoroutineContext

/** Exercise the actual Nearby transfer into the serialized session owner. */
class NearbySessionPublicationTest {
    // The arbiter's default clock is Android's SystemClock, which plain JVM tests do not provide.
    private val clock = ConnectionArbiter.clock
    @Before fun prepare() { ConnectionArbiter.reset(); ConnectionArbiter.clock = { 1000L } }
    @After fun restore() { ConnectionArbiter.reset(); ConnectionArbiter.clock = clock }

    @Test fun preparedTunnelPublishesOnlyWhileBothOwnersRemainCurrent() = runBlocking {
        for (fromError in listOf(false, true)) for (outcome in listOf("connected", "replacement", "retired", "new-error", "owned", "transport-owned", "saved-request")) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            // A Save the request was admitted with, revoked while its tunnel is being prepared.
            val saved = if (outcome == "saved-request") CommManager.ConnectionState.Disconnected(
                reason = CommManager.DisconnectReason.SETTINGS_RESTART) else null
            val initial: CommManager.ConnectionState = saved ?: if (fromError)
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
            set("appExitPolicy", AppExitPolicy())
            set("_connectionState", flow)
            set("context", mock(Context::class.java))
            set("settings", mock(Settings::class.java))
            set("_scope", scope)
            // The previous teardown leaves this set; a valid transfer must re-arm the new owner.
            set("disconnectRequested", true)
            val guard = NearbyAttemptGuard()
            val attempt = guard.begin("phone", 0L)
            val admission = ConnectionAdmission { action -> guard.run(attempt, action = action) }
            val socket = mock(Socket::class.java)
            val replacement = mock(SocketProjectionConnection::class.java)
            val replacementTransport = mock(com.andrerinas.openheadunit.aap.AapTransport::class.java)
            ConnectionArbiter.reset()
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
                        else if (outcome == "saved-request") manager.cancelPendingSettingsRestart()
                        true
                    }
                }
            }.use { candidates ->
                try {
                    var rejected = false
                    try {
                        // The actual public restricted request: admission under the Nearby guard, then publication.
                        manager.connect(socket, admission = admission, ticket = manager.appConnectionTicket(), expectedState = saved)
                    } catch (_: ConnectionAdmissionRejectedException) { rejected = true }
                    val candidate = candidates.constructed().single()
                    assertEquals(outcome != "connected", rejected)
                    if (outcome == "connected") {
                        assertSame(candidate, get("_connection"))
                        assertSame(CommManager.ConnectionState.Connected, flow.value)
                        assertNotNull(get("connectionAttempt"))
                        verify(candidate, never()).disconnect()
                        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
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
