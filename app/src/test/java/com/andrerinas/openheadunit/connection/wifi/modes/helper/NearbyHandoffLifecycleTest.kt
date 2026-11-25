package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import com.andrerinas.openheadunit.connection.*
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.Socket

class NearbyHandoffLifecycleTest {
    private fun field(target: Any, name: String, value: Any?) = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.set(target, value)
    private fun get(target: Any, name: String): Any? = target.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.get(target)

    @Test fun `Stop invalidates a delayed tunnel without retiring a replacement and fresh attempts stay valid`() = runBlocking {
        for (stop in listOf(false, true)) for (replace in listOf(false, true)) {
            val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val policy = get(comm, "appExitPolicy") as AppExitPolicy
            val nearby = mock<NearbyManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
            val guard = NearbyAttemptGuard()
            val attempt = guard.begin("phone", comm.appConnectionTicket())
            val old = NearbySocket()
            val replacement = NearbySocket()
            val client = mock<ConnectionsClient>()
            field(nearby, "commManager", comm); field(nearby, "attempts", guard)
            field(nearby, "connectionsClient", client)
            field(nearby, "lastQuality", java.util.concurrent.ConcurrentHashMap<String, Int>())
            field(nearby, "activeEndpointId", "phone"); field(nearby, "activeNearbySocket", old)
            var handoffs = 0
            val handoff: suspend (Socket, ConnectionAdmission, Long, CommManager.ConnectionState.Disconnected?) -> Unit = { socket, admission, _, _ ->
                admission.run { check(socket === old || socket === replacement); handoffs++ }
            }
            field(nearby, "onSocketReady", handoff)
            val ticket = comm.appConnectionTicket()
            if (stop) policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
            policy.supersede()
            val current = if (replace) guard.begin("phone", comm.appConnectionTicket()) else attempt
            if (replace) field(nearby, "activeNearbySocket", replacement)
            try {
                nearby.handOverTunnel(attempt, old, ConnectionAdmission { guard.run(attempt, action = it) }, ticket)
                assertEquals(if (!stop && !replace) 1 else 0, handoffs)
                if (stop) {
                    assertTrue(old.isClosed)
                    if (replace) {
                        assertSame(replacement, get(nearby, "activeNearbySocket"))
                        assertTrue(guard.run(current) {})
                        verify(client, never()).disconnectFromEndpoint(any())
                    } else {
                        assertNull(get(nearby, "activeNearbySocket"))
                        verify(client).disconnectFromEndpoint("phone")
                    }
                    val fresh = if (replace) current else guard.begin("phone", comm.appConnectionTicket())
                    field(nearby, "activeNearbySocket", replacement)
                    nearby.handOverTunnel(fresh, replacement,
                        ConnectionAdmission { guard.run(fresh, action = it) }, comm.appConnectionTicket())
                    assertEquals(1, handoffs)
                    assertFalse(replacement.isClosed)
                }
            } finally { old.close(); replacement.close() }
        }
    }

    @Test fun `same-owner claim replacement rejects handoff and retires Nearby without touching new session`() = runBlocking<Unit> {
        val clock = ConnectionArbiter.clock
        ConnectionArbiter.reset(); ConnectionArbiter.clock = { 1000L }; ConnectionArbiter.actions = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val client = mock<ConnectionsClient>()
        val context = mock<Context>()
        val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val state = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
        val retirement = Job()
        field(comm, "context", context); field(comm, "_connectionState", state)
        field(comm, "transportLifecycleLock", Any())
        field(comm, "_disconnectJob", retirement)
        val nearby = mock<NearbyManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS)
        val guard = NearbyAttemptGuard()
        val attempt = guard.begin("phone", comm.appConnectionTicket())
        val old = NearbySocket().apply {
            inputStreamWrapper = ByteArrayInputStream(byteArrayOf())
            outputStreamWrapper = ByteArrayOutputStream()
        }
        field(nearby, "attempts", guard); field(nearby, "connectionsClient", client)
        field(nearby, "lastQuality", java.util.concurrent.ConcurrentHashMap<String, Int>())
        field(nearby, "activeEndpointId", "phone"); field(nearby, "activeNearbySocket", old)
        field(nearby, "context", context); field(nearby, "scope", scope)
        field(nearby, "commManager", comm)
        val handoff: suspend (Socket, ConnectionAdmission, Long, CommManager.ConnectionState.Disconnected?) -> Unit =
            { socket, admission, ticket, save -> comm.connect(socket, admission = admission, ticket = ticket, expectedState = save) }
        field(nearby, "onSocketReady", handoff)
        val claimed = CompletableDeferred<Unit>()
        val admission = ConnectionAdmission { action ->
            guard.run(attempt, action = action).also { claimed.complete(Unit) }
        }
        try {
            val job = async { nearby.handOverTunnel(attempt, old, admission, comm.appConnectionTicket()) }
            claimed.await()
            val newer = requireNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "server B"))
            val replacement = mock<ProjectionConnection>()
            field(comm, "_connection", replacement); state.value = CommManager.ConnectionState.Connected
            retirement.complete(); job.await()
            assertTrue(old.isClosed)
            assertNull(get(nearby, "activeEndpointId")); assertNull(get(nearby, "activeNearbySocket"))
            assertNull(get(nearby, "activePipes"))
            verify(client).disconnectFromEndpoint("phone")
            verify(replacement, never()).disconnect()
            assertSame(replacement, get(comm, "_connection")); assertTrue(ConnectionArbiter.holds(newer))
            val request = mock<Task<Void>>()
            whenever(request.addOnFailureListener(any())).thenReturn(request)
            whenever(client.requestConnection(anyOrNull<String>(), any(), any())).thenReturn(request)
            Mockito.mockConstruction(Settings::class.java).use {
                nearby.connectToEndpoint("phone", comm.appConnectionTicket())
                verify(client).requestConnection(anyOrNull<String>(), eq("phone"), any())
            }
        } finally {
            retirement.complete(); old.close(); scope.cancel()
            ConnectionArbiter.reset(); ConnectionArbiter.clock = clock
        }
    }
}
