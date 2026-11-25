package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.AppExitPolicy
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.utils.Settings
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.*
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.*
import org.junit.Test
import org.mockito.Mockito
import org.mockito.kotlin.*

class NearbyDiscoveryFailureTest {
    private lateinit var permissions: org.mockito.MockedStatic<androidx.core.content.ContextCompat>
    private lateinit var clock: org.mockito.MockedStatic<android.os.SystemClock>
    @org.junit.Before fun stubAndroid() {
        permissions = Mockito.mockStatic(androidx.core.content.ContextCompat::class.java)
        clock = Mockito.mockStatic(android.os.SystemClock::class.java)
    }
    @org.junit.After fun restoreAndroid() { clock.close(); permissions.close() }

    @Test fun freshRequestReplacesRevokedAttemptBeforeOldLifecycleCleanup() {
        for (stop in listOf(false, true)) {
            val context = mock<Context>()
            val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val policy = CommManager::class.java.getDeclaredField("appExitPolicy")
                .apply { isAccessible = true }.get(comm) as AppExitPolicy
            val lifecycle = CommManager::class.java.getDeclaredField("transportLifecycleLock")
                .apply { isAccessible = true }.get(comm)
            val client = mock<ConnectionsClient>()
            val task = mock<Task<Void>>()
            whenever(task.addOnFailureListener(any())).thenReturn(task)
            whenever(client.requestConnection(anyOrNull<String>(), any(), any())).thenReturn(task)
            whenever(client.acceptConnection(any(), any())).thenReturn(task)
            val info = mock<ConnectionInfo>()
            whenever(info.endpointName).thenReturn("phone")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            Mockito.mockStatic(Nearby::class.java).use { nearby ->
                nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
                Mockito.mockConstruction(Settings::class.java).use {
                    val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> error("no tunnel yet") }
                    val guard = NearbyManager::class.java.getDeclaredField("attempts")
                        .apply { isAccessible = true }.get(manager) as NearbyAttemptGuard
                    doAnswer {
                        assert(!Thread.holdsLock(lifecycle) && !Thread.holdsLock(guard)) { "SDK request under ownership locks" }
                        task
                    }.whenever(client).requestConnection(anyOrNull<String>(), any(), any())
                    val old = NearbySocket()
                    val replacement = NearbySocket()
                    val socketField = NearbyManager::class.java.getDeclaredField("activeNearbySocket")
                        .apply { isAccessible = true }
                    try {
                        manager.connectToEndpoint("phone", comm.appConnectionTicket())
                        val original = guard.current()
                        socketField.set(manager, old)
                        if (stop) policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
                        policy.supersede()
                        // The old SDK callback has not run and isConnecting is still true.
                        manager.connectToEndpoint("phone", comm.appConnectionTicket())
                        val callbacks = argumentCaptor<ConnectionLifecycleCallback>()
                        verify(client, times(if (stop) 2 else 1))
                            .requestConnection(anyOrNull<String>(), eq("phone"), callbacks.capture())
                        if (stop) {
                            org.junit.Assert.assertTrue(old.isClosed)
                            org.junit.Assert.assertNotSame(original, guard.current())
                            socketField.set(manager, replacement)
                            callbacks.lastValue.onConnectionInitiated("phone", info)
                            callbacks.firstValue.onConnectionInitiated("phone", info)
                            org.junit.Assert.assertSame(replacement, socketField.get(manager))
                            org.junit.Assert.assertFalse(replacement.isClosed)
                            verify(client).disconnectFromEndpoint("phone")
                        } else {
                            org.junit.Assert.assertSame(original, guard.current())
                            org.junit.Assert.assertFalse(old.isClosed)
                            callbacks.firstValue.onConnectionInitiated("phone", info)
                        }
                        verify(client).acceptConnection(eq("phone"), any())
                        manager.connectToEndpoint("phone", comm.appConnectionTicket())
                        verify(client, times(if (stop) 2 else 1))
                            .requestConnection(anyOrNull<String>(), eq("phone"), any())
                    } finally { manager.stop(); old.close(); replacement.close(); scope.cancel() }
                }
            }
        }
    }

    @Test fun delayedLifecycleKeepsRequestTicketAndNewEndpointInputGetsFreshAuthority() {
        for (stop in listOf(false, true)) {
            val context = mock<Context>()
            val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val policy = CommManager::class.java.getDeclaredField("appExitPolicy")
                .apply { isAccessible = true }.get(comm) as AppExitPolicy
            val client = mock<ConnectionsClient>()
            val task = mock<Task<Void>>()
            whenever(task.addOnFailureListener(any())).thenReturn(task)
            whenever(client.requestConnection(anyOrNull<String>(), any(), any())).thenReturn(task)
            whenever(client.acceptConnection(any(), any())).thenReturn(task)
            val info = mock<ConnectionInfo>()
            whenever(info.endpointName).thenReturn("phone")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            Mockito.mockStatic(Nearby::class.java).use { nearby ->
                nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
                Mockito.mockConstruction(Settings::class.java).use {
                    val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> error("no tunnel yet") }
                    try {
                        manager.connectToEndpoint("old", comm.appConnectionTicket())
                        val callbacks = argumentCaptor<ConnectionLifecycleCallback>()
                        verify(client).requestConnection(anyOrNull<String>(), eq("old"), callbacks.capture())
                        if (stop) policy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
                        policy.supersede()
                        callbacks.firstValue.onConnectionInitiated("old", info)
                        verify(client, times(if (stop) 0 else 1)).acceptConnection(eq("old"), any())
                        if (stop) {
                            verify(client).disconnectFromEndpoint("old")
                            manager.connectToEndpoint("new", comm.appConnectionTicket())
                            verify(client).requestConnection(anyOrNull<String>(), eq("new"), callbacks.capture())
                            callbacks.lastValue.onConnectionInitiated("new", info)
                            verify(client).acceptConnection(eq("new"), any())
                        }
                    } finally { manager.stop(); scope.cancel() }
                }
            }
        }
    }

    @Test fun failedRequestKeepsDiscoveryAndAcceptsAnotherEndpoint() {
        val context = mock<Context>()
        val comm = mock<CommManager>(defaultAnswer = Mockito.CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        whenever(comm.acceptsAppConnectionTicket(any())).thenReturn(true)
        val client = mock<ConnectionsClient>()
        val discoveryTask = mock<Task<Void>>()
        val requestTask = mock<Task<Void>>()
        whenever(discoveryTask.addOnSuccessListener(any())).thenReturn(discoveryTask)
        whenever(discoveryTask.addOnFailureListener(any())).thenReturn(discoveryTask)
        whenever(requestTask.addOnFailureListener(any())).thenReturn(requestTask)
        whenever(client.startDiscovery(any(), any(), any())).thenReturn(discoveryTask)
        whenever(client.requestConnection(anyOrNull<String>(), any(), any())).thenReturn(requestTask)
        val info = mock<DiscoveredEndpointInfo>()
        whenever(info.endpointName).thenReturn("phone")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        Mockito.mockStatic(Nearby::class.java).use { nearby ->
            nearby.`when`<ConnectionsClient> { Nearby.getConnectionsClient(context) }.thenReturn(client)
            Mockito.mockConstruction(Settings::class.java) { settings, _ ->
                whenever(settings.autoConnectLastSession).thenReturn(true)
                whenever(settings.lastNearbyDeviceName).thenReturn("phone")
            }.use {
                val manager = NearbyManager(context, scope, comm) { _, _, _, _ -> error("no tunnel yet") }
                try {
                    manager.start(comm.appConnectionTicket())
                    val discoveries = argumentCaptor<EndpointDiscoveryCallback>()
                    verify(client).startDiscovery(any(), discoveries.capture(), any())
                    discoveries.firstValue.onEndpointFound("first", info)
                    val failures = argumentCaptor<OnFailureListener>()
                    verify(requestTask).addOnFailureListener(failures.capture())
                    failures.firstValue.onFailure(Exception("request failed"))
                    verify(client, never()).stopDiscovery()
                    discoveries.firstValue.onEndpointFound("second", info)
                    verify(client).requestConnection(anyOrNull<String>(), eq("second"), any())
                } finally {
                    manager.stop()
                    scope.cancel()
                }
            }
        }
    }
}
