package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import android.content.SharedPreferences
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.ArgumentMatchers.*
import org.mockito.Mockito.*

class NearbySettingsRestartTest {
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
        mockStatic(androidx.core.content.ContextCompat::class.java).use {
        mockStatic(android.os.SystemClock::class.java).use {
        mockStatic(Nearby::class.java).use { nearby ->
            nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
            val manager = NearbyManager(context, scope) {}
            fun set(name: String, value: Any?) = NearbyManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
            val old = NearbySocket()
            set("activeEndpointId", "old-id")
            set("activeNearbySocket", old)
            manager.restartForSettings()
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
        mockStatic(androidx.core.content.ContextCompat::class.java).use {
        mockStatic(android.os.SystemClock::class.java).use { clock ->
            var now = 100L
            clock.`when`<Long> { android.os.SystemClock.elapsedRealtime() }.thenAnswer { now }
        mockStatic(Nearby::class.java).use { nearby ->
            nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
            val manager = NearbyManager(context, scope) {}
            fun set(name: String, value: Any?) = NearbyManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

            manager.restartForSettings(untilMs = 200L)
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
}
