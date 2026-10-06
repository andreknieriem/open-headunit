package com.andrerinas.openheadunit.connection.wifi.modes.helper

import android.content.Context
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

    @Test fun failedRequestKeepsDiscoveryAndAcceptsAnotherEndpoint() {
        val context = mock<Context>()
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
                val manager = NearbyManager(context, scope) { _, _ -> error("no tunnel yet") }
                try {
                    manager.start()
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
