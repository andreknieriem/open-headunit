package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import android.content.SharedPreferences
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class NearbySettingsRestartTest {
    @Test fun `fallback preserves a pending endpoint and its bandwidth upgrade`() {
        val operation = ConnectionOperation(7L, null)
        for (connecting in listOf(false, true)) {
            val manager = mock(NearbyManager::class.java, CALLS_REAL_METHODS)
            NearbyManager::class.java.getDeclaredField("attempts").apply { isAccessible = true }.set(manager, NearbyAttemptGuard())
            NearbyManager::class.java.getDeclaredField("isConnecting").apply { isAccessible = true }.set(manager, connecting)
            NearbyManager::class.java.getDeclaredField("activeEndpointId").apply { isAccessible = true }
                .set(manager, if (connecting) null else "joining-phone")
            manager.resumeDiscoveryIfIdle(operation)
            verify(manager, never()).start(org.mockito.kotlin.any())
        }
        val idle = mock(NearbyManager::class.java, CALLS_REAL_METHODS)
        NearbyManager::class.java.getDeclaredField("attempts").apply { isAccessible = true }.set(idle, NearbyAttemptGuard())
        doNothing().`when`(idle).start(org.mockito.kotlin.any())
        idle.resumeDiscoveryIfIdle(operation)
        verify(idle).start(operation)
    }
    @Test fun `explicit save cleans the old tunnel and retries only the rediscovered peer with auto connect off`() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(eq("last-nearby-device-name"), anyString())).thenReturn("Phone")
        val client = mock(ConnectionsClient::class.java)
        @Suppress("UNCHECKED_CAST")
        val task = mock(Task::class.java, RETURNS_SELF) as Task<Void>
        `when`(client.startDiscovery(anyString(), any(), any())).thenReturn(task)
        `when`(client.requestConnection(nullable(String::class.java), anyString(), any())).thenReturn(task)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        `when`(comm.acceptsAppConnectionTicket(anyLong())).thenReturn(true)
        mockStatic(androidx.core.content.ContextCompat::class.java).use {
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(Nearby::class.java).use { nearby ->
            nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
            val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> }
            fun set(name: String, value: Any?) = NearbyManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
            val old = NearbySocket()
            set("activeEndpointId", "old-id")
            set("activeNearbySocket", old)
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = com.andrerinas.openheadunit.connection.SettingsRestartRecovery.WINDOW_MS)
            val states = kotlinx.coroutines.flow.MutableStateFlow<CommManager.ConnectionState>(saved)
            for (name in listOf("_connectionState", "connectionState"))
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, states)
            manager.restartForSettings(saved, comm.appConnectionTicket())
            assertTrue(old.isClosed)
            verify(client).disconnectFromEndpoint("old-id")
            val callback = org.mockito.ArgumentCaptor.forClass(EndpointDiscoveryCallback::class.java)
            verify(client).startDiscovery(anyString(), callback.capture(), any())
            val other = mock(DiscoveredEndpointInfo::class.java)
            `when`(other.endpointName).thenReturn("Other phone")
            callback.value.onEndpointFound("other-id", other)
            verify(client, never()).requestConnection(nullable(String::class.java), anyString(), any())
            val peer = mock(DiscoveredEndpointInfo::class.java)
            `when`(peer.endpointName).thenReturn("Phone")
            callback.value.onEndpointFound("new-id", peer)
            callback.value.onEndpointFound("new-id", peer)
            verify(client, times(1)).requestConnection(nullable(String::class.java), eq("new-id"), any())
            manager.stop()
        }
        }
        }
        scope.cancel()
    }
    @Test fun `expired preference obeys auto connect and lets another saved peer be considered`() {
        val context = mock(Context::class.java)
        val prefs = mock(SharedPreferences::class.java)
        `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
        `when`(prefs.getString(eq("last-nearby-device-name"), anyString())).thenReturn("Phone")
        val client = mock(ConnectionsClient::class.java)
        @Suppress("UNCHECKED_CAST")
        val task = mock(Task::class.java, RETURNS_SELF) as Task<Void>
        `when`(client.startDiscovery(anyString(), any(), any())).thenReturn(task)
        `when`(client.requestConnection(nullable(String::class.java), anyString(), any())).thenReturn(task)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        `when`(comm.acceptsAppConnectionTicket(anyLong())).thenReturn(true)
        mockStatic(androidx.core.content.ContextCompat::class.java).use {
        mockStatic(android.os.SystemClock::class.java).use { clock ->
            var now = 100L
            clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenAnswer { now }
        mockStatic(Nearby::class.java).use { nearby ->
            nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
            val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> }
            fun set(name: String, value: Any?) = NearbyManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = 200L)
            val states = kotlinx.coroutines.flow.MutableStateFlow<CommManager.ConnectionState>(saved)
            for (name in listOf("_connectionState", "connectionState"))
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, states)
            manager.restartForSettings(saved, comm.appConnectionTicket())
            val callback = org.mockito.ArgumentCaptor.forClass(EndpointDiscoveryCallback::class.java)
            verify(client).startDiscovery(anyString(), callback.capture(), any())
            val peer = mock(DiscoveredEndpointInfo::class.java)
            `when`(peer.endpointName).thenReturn("Phone")
            now = 200L
            callback.value.onEndpointFound("expired-peer", peer)
            verify(client, never()).requestConnection(nullable(String::class.java), anyString(), any())
            `when`(prefs.getBoolean(eq("auto-connect-last-session"), anyBoolean())).thenReturn(true)
            `when`(prefs.getString(eq("last-nearby-device-name"), anyString())).thenReturn("Other phone")
            val other = mock(DiscoveredEndpointInfo::class.java)
            `when`(other.endpointName).thenReturn("Other phone")
            callback.value.onEndpointFound("other-id", other)
            verify(client).requestConnection(nullable(String::class.java), eq("other-id"), any())
            manager.stop()
        }
        }
        }
        scope.cancel()
    }

    // Save asks Nearby to retry its peer. Before the peer is found, a user exit that keeps the
    // service cancels that Save (the ticket does not move), or Stop and Open move the ticket. The
    // found peer continues the Save's request, so it carries that Save and its ticket and connects
    // to nothing, even though an ordinary automatic connect is off. The control connects once.
    @Test fun `a Save peer found after the Save was withdrawn does not connect`() {
        for (end in listOf("exit", "stop", "none")) {
            val context = mock(Context::class.java)
            val prefs = mock(SharedPreferences::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(prefs)
            `when`(prefs.getString(eq("last-nearby-device-name"), anyString())).thenReturn("Phone")
            val client = mock(ConnectionsClient::class.java)
            @Suppress("UNCHECKED_CAST")
            val task = mock(Task::class.java, RETURNS_SELF) as Task<Void>
            `when`(client.startDiscovery(anyString(), any(), any())).thenReturn(task)
            `when`(client.requestConnection(nullable(String::class.java), anyString(), any())).thenReturn(task)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val states = kotlinx.coroutines.flow.MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
            for ((name, value) in listOf("_connectionState" to states, "connectionState" to states,
                    "usbRecoveryOwner" to Any(), "settings" to mock(com.andrerinas.openheadunit.utils.Settings::class.java))) {
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, value)
            }
            mockStatic(androidx.core.content.ContextCompat::class.java).use {
            mockStatic(android.os.SystemClock::class.java).use {
            // Stop's retained-group hand-off asserts Main; with Looper mocked both loopers read the same.
            mockStatic(android.os.Looper::class.java).use {
            mockStatic(Nearby::class.java).use { nearby ->
                nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
                val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> }
                // Save's end is the current state, with its ticket read as it is received.
                val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                    settingsRestartUntilMs = Long.MAX_VALUE)
                states.value = saved
                manager.restartForSettings(saved, comm.appConnectionTicket())
                val callback = org.mockito.ArgumentCaptor.forClass(EndpointDiscoveryCallback::class.java)
                verify(client).startDiscovery(anyString(), callback.capture(), any())
                when (end) {
                    // The Home Exit the service survives: already Disconnected, it cancels Save.
                    "exit" -> comm.disconnect(ConnectionOperation(comm.appConnectionTicket(), null), isUserExit = true, honorKillOnDisconnect = false)
                    "stop" -> { comm.requestExplicitAppExit(ticket = comm.appConnectionTicket(), killProcess = false); comm.onAppRelaunched() }
                }
                val peer = mock(DiscoveredEndpointInfo::class.java)
                `when`(peer.endpointName).thenReturn("Phone")
                callback.value.onEndpointFound("peer-id", peer)
                // Recorded after every production frame has returned.
                val requests = mockingDetails(client).invocations.count { it.method.name == "requestConnection" }
                if (end == "none") assertEquals("the Save peer was not connected (requests=$requests)", 1, requests)
                else assertEquals("a Save peer found after the Save was withdrawn still requested a connection (end=$end requests=$requests)", 0, requests)
                manager.stop()
            }
            }
            }
            }
            scope.cancel()
        }
    }

    // An earlier same-endpoint cleanup holds the endpoint's SDK queue, so a Nearby request is
    // accepted and queued behind it. Stop and Open land before the queue drains: the queued
    // request belongs to the old ticket and is not sent. The control, with no Stop, sends it once.
    @Test fun `a queued Nearby request revoked before the queue drains is not sent`() {
        for (stop in listOf(true, false)) {
            val context = mock(Context::class.java)
            `when`(context.getSharedPreferences(anyString(), anyInt())).thenReturn(mock(SharedPreferences::class.java))
            val client = mock(ConnectionsClient::class.java)
            @Suppress("UNCHECKED_CAST")
            val task = mock(Task::class.java, RETURNS_SELF) as Task<Void>
            `when`(client.requestConnection(nullable(String::class.java), anyString(), any())).thenReturn(task)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            val comm = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val states = kotlinx.coroutines.flow.MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
            for ((name, value) in listOf("_connectionState" to states, "connectionState" to states,
                    "usbRecoveryOwner" to Any(), "settings" to mock(com.andrerinas.openheadunit.utils.Settings::class.java))) {
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(comm, value)
            }
            mockStatic(androidx.core.content.ContextCompat::class.java).use {
            mockStatic(android.os.SystemClock::class.java).use {
            mockStatic(android.os.Looper::class.java).use {
            mockStatic(Nearby::class.java).use { nearby ->
                nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
                val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> }
                // The earlier cleanup opened the endpoint's queue and is still running.
                check(NearbyEndpointCalls.reserve("queued-phone") {})
                // A tap: its ticket is read now, and its request queues behind the cleanup.
                manager.connectToEndpoint("queued-phone", ConnectionOperation(comm.appConnectionTicket(), null))
                if (stop) { comm.requestExplicitAppExit(ticket = comm.appConnectionTicket(), killProcess = false); comm.onAppRelaunched() }
                NearbyEndpointCalls.drain("queued-phone")
                // Recorded after every production frame has returned.
                val requests = mockingDetails(client).invocations.count { it.method.name == "requestConnection" }
                if (stop) assertEquals("a queued request revoked by Stop was still sent (requests=$requests)", 0, requests)
                else assertEquals("the queued request was not sent (requests=$requests)", 1, requests)
                manager.stop()
            }
            }
            }
            }
            scope.cancel()
        }
    }
}
