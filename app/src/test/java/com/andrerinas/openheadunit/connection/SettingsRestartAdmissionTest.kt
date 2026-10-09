package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.Socket
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class SettingsRestartAdmissionTest {
    private fun set(manager: CommManager, name: String, value: Any?) =
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

    @Test fun `obsolete Save cannot claim or consume a socket after dispatch to IO`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val old = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.TransportStarted)
        set(manager, "_connectionState", flow)
        val connection = mock(ProjectionConnection::class.java)
        val transport = mock(AapTransport::class.java)
        set(manager, "_connection", connection)
        set(manager, "_transport", transport)
        val socket = Socket()
        HeldServerSocket.hold("192.168.1.5:5277", socket)
        try {
            manager.connect("192.168.1.5", 5277, expectedState = old)
            assertTrue(HeldServerSocket.isHeld("192.168.1.5:5277"))
            assertSame(CommManager.ConnectionState.TransportStarted, flow.value)
            verifyNoInteractions(connection, transport)
        } finally {
            HeldServerSocket.abandon(socket)
        }
    }

    @Test fun `Save rechecks after teardown wait before replacing an established session`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val old = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(old)
        val retirement = Job()
        set(manager, "_connectionState", flow)
        set(manager, "transportLifecycleLock", Any())
        set(manager, "_disconnectJob", retirement)
        val method = CommManager::class.java.getDeclaredMethod("connectIp", String::class.java,
            Int::class.javaPrimitiveType, CommManager.ConnectionState.Disconnected::class.java,
            kotlin.coroutines.Continuation::class.java).apply { isAccessible = true }
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Unit> { continuation ->
                method.invoke(manager, "192.168.1.5", 5277, old, continuation)
            }
        }
        val connection = mock(ProjectionConnection::class.java)
        val transport = mock(AapTransport::class.java)
        set(manager, "_connection", connection)
        set(manager, "_transport", transport)
        flow.value = CommManager.ConnectionState.TransportStarted
        retirement.complete()
        retry.join()
        assertSame(CommManager.ConnectionState.TransportStarted, flow.value)
        verifyNoInteractions(connection, transport)
    }

    @Test fun `IP adoption forwards the known server address for the next Save`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val socket = Socket()
        val endpoint = "192.168.1.5" to 5277
        var forwarded: Pair<String, Int>? = null
        doAnswer { invocation ->
            forwarded = invocation.getArgument(2)
            HeldServerSocket.settle(socket)
            Unit
        }.`when`(manager).connect(org.mockito.kotlin.eq(socket), org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull())
        HeldServerSocket.hold("192.168.1.5:5277", socket)
        try {
            manager.connect(endpoint.first, endpoint.second)
            assertEquals(endpoint, forwarded)
            assertFalse(socket.isClosed)
        } finally {
            HeldServerSocket.abandon(socket)
        }
    }
    @Test fun `refused private IP and socket paths leave remote audio classification intact`() = runBlocking {
        for (socketPath in listOf(false, true)) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            val flow = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Connected)
            set(manager, "_connectionState", flow)
            set(manager, "transportLifecycleLock", Any())
            val current = mock(com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection::class.java)
            set(manager, "_connection", current)
            set(manager, "lastAttemptedEndpoint", "192.168.1.8:5277")
            val socket = mock(Socket::class.java)
            `when`(socket.inetAddress).thenReturn(java.net.InetAddress.getByName("127.0.0.1"))
            `when`(socket.port).thenReturn(5277)
            val continuation = kotlin.coroutines.Continuation::class.java
            val method = if (socketPath) CommManager::class.java.getDeclaredMethod("connectSocket",
                Socket::class.java, Pair::class.java, CommManager.ConnectionState.Disconnected::class.java, continuation)
            else CommManager::class.java.getDeclaredMethod("connectIp", String::class.java,
                Int::class.javaPrimitiveType, CommManager.ConnectionState.Disconnected::class.java, continuation)
            method.isAccessible = true
            suspendCoroutineUninterceptedOrReturn<Unit> { completion ->
                if (socketPath) method.invoke(manager, socket, "127.0.0.1" to 5277, saved, completion)
                else method.invoke(manager, "127.0.0.1", 5277, saved, completion)
            }
            assertEquals("192.168.1.8:5277", manager.lastAttemptedEndpoint)
            verifyNoInteractions(current)
            assertTrue(manager.isWirelessSession)
            assertFalse(manager.isLoopbackSession)
            verify(current).isLoopbackPeer
            verifyNoMoreInteractions(current)
            if (socketPath) verify(socket).close()
        }
    }

    @Test fun `new Self request revokes Save before teardown or IO retry without changing AAP state`() = runBlocking {
        for (duringTeardown in listOf(false, true)) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
            val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
            val retirement = Job()
            set(manager, "_connectionState", flow)
            set(manager, "transportLifecycleLock", Any())
            set(manager, "_disconnectJob", retirement)
            val socket = Socket()
            HeldServerSocket.hold("192.168.1.5:5277", socket)
            try {
                // This is the same revocation called when a manual Self request is accepted.
                val retry = if (duringTeardown) launch(start = CoroutineStart.UNDISPATCHED) {
                    retirement.join()
                    manager.connect("192.168.1.5", 5277, expectedState = saved)
                } else null
                manager.cancelPendingSettingsRestart()
                assertSame(saved, flow.value)
                assertFalse(saved.acceptsSettingsRestart(flow.value))
                retirement.complete()
                if (retry != null) retry.join()
                else manager.connect("192.168.1.5", 5277, expectedState = saved)
                assertTrue(HeldServerSocket.isHeld("192.168.1.5:5277"))
                assertSame(saved, flow.value)
            } finally {
                retirement.cancel()
                HeldServerSocket.abandon(socket)
            }
        }
    }


    @Test fun `revoked USB Save cannot claim or touch hardware even when terminal identity is unchanged`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        set(manager, "_connectionState", flow)
        set(manager, "transportLifecycleLock", Any())
        val device = mock(android.hardware.usb.UsbDevice::class.java)
        val context = mock(android.content.Context::class.java)
        set(manager, "context", context)
        manager.cancelPendingSettingsRestart() // same action as the accepted manual Self launch
        manager.connect(device, expectedState = saved)
        assertNull(manager.claimUsb(ConnectionPriorityPolicy.Tier.USB, "obsolete Save", saved))
        assertSame(saved, flow.value)
        verifyNoInteractions(device, context)
    }

    @Test fun `USB Save checks permission again after retirement before publishing Connecting`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        val retirement = Job()
        set(manager, "_connectionState", flow)
        set(manager, "transportLifecycleLock", Any())
        set(manager, "_disconnectJob", retirement)
        val context = mock(android.content.Context::class.java)
        val usb = mock(android.hardware.usb.UsbManager::class.java)
        val device = mock(android.hardware.usb.UsbDevice::class.java)
        `when`(context.getSystemService(android.content.Context.USB_SERVICE)).thenReturn(usb)
        `when`(usb.hasPermission(device)).thenReturn(true)
        set(manager, "context", context)
        val method = CommManager::class.java.getDeclaredMethod("connectUsb", android.hardware.usb.UsbDevice::class.java,
            CommManager.ConnectionState.Disconnected::class.java, kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Unit> { method.invoke(manager, device, saved, it) }
        }
        assertFalse(retry.isCompleted)
        manager.cancelPendingSettingsRestart()
        retirement.complete()
        retry.join()
        assertSame(saved, flow.value)
        verify(usb).hasPermission(device)
        verify(usb, never()).openDevice(device)
        verifyNoInteractions(device)
    }
}
