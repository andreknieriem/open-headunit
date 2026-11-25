package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.*
import java.net.Socket
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class SettingsRestartAdmissionTest {
    // The arbiter's default clock is Android's SystemClock, which plain JVM tests do not provide.
    private val clock = ConnectionArbiter.clock
    @Before fun prepare() { ConnectionArbiter.reset(); ConnectionArbiter.clock = { 1000L } }
    @After fun restore() { ConnectionArbiter.reset(); ConnectionArbiter.clock = clock }

    private fun set(manager: CommManager, name: String, value: Any?) =
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

    @Test fun `obsolete Save cannot claim or consume a socket after dispatch to IO`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
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
            if (HeldServerSocket.adopt(socket)) socket.close()
        }
    }

    @Test fun `Save rechecks after teardown wait before replacing an established session`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val old = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(old)
        val retirement = Job()
        set(manager, "_connectionState", flow)
        set(manager, "transportLifecycleLock", Any())
        set(manager, "_disconnectJob", retirement)
        // Admitted with its Save, the request waits in the teardown join before it can publish.
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            manager.connect("192.168.1.5", 5277, expectedState = old)
        }
        assertFalse(retry.isCompleted)
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

    @Test fun `refused IP and socket requests leave remote audio classification intact`() = runBlocking {
        for (socketPath in listOf(false, true)) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
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
            if (socketPath) manager.connect(socket, restartEndpoint = "127.0.0.1" to 5277, expectedState = saved)
            else manager.connect("127.0.0.1", 5277, expectedState = saved)
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
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
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
                if (HeldServerSocket.adopt(socket)) socket.close()
            }
        }
    }


    @Test fun `revoked USB Save cannot be admitted or touch hardware even when terminal identity is unchanged`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        set(manager, "_connectionState", flow)
        set(manager, "transportLifecycleLock", Any())
        val context = mock(android.content.Context::class.java)
        set(manager, "context", context)
        manager.cancelPendingSettingsRestart() // same action as the accepted manual Self launch
        assertNull(manager.admitUsbRequest(ConnectionPriorityPolicy.Tier.USB, "obsolete Save", saved,
            manager.usbRecheck(manager.appConnectionTicket())))
        assertSame(saved, flow.value)
        verifyNoInteractions(context)
    }

    // A Save's USB request is admitted and waits to publish. The user exits (the service stays):
    // the request leaves the slot and its claim is released at once, not when its producer
    // notices a step later.
    @Test fun `a user exit releases an unpublished USB Save request's claim at once`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val saved = CommManager.ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
            settingsRestartUntilMs = Long.MAX_VALUE)
        val flow = MutableStateFlow<CommManager.ConnectionState>(saved)
        set(manager, "_connectionState", flow)
        set(manager, "connectionState", flow)
        set(manager, "usbRecoveryOwner", Any())
        val request = checkNotNull(manager.admitUsbRequest(ConnectionPriorityPolicy.Tier.USB, "USB Save", saved,
            manager.usbRecheck(manager.appConnectionTicket())))
        assertTrue(ConnectionArbiter.holds(request.claim))
        manager.disconnect(sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
        // Recorded after every production frame has returned.
        val held = ConnectionArbiter.holds(request.claim)
        assertFalse("a user exit left an unpublished USB Save request's claim held (held=$held)", held)
    }

    @Test fun `USB Save checks permission again after retirement before publishing Connecting`() = runBlocking {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
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
            CommManager.ConnectionRequest::class.java, Any::class.java, ConnectionAdmission::class.java,
            kotlin.coroutines.Continuation::class.java)
            .apply { isAccessible = true }
        // The Save request is admitted and holds its claim, so only the revoked Save can refuse its
        // publication.
        val request = checkNotNull(manager.admitUsbRequest(ConnectionPriorityPolicy.Tier.USB, "USB Save", saved,
            manager.usbRecheck(manager.appConnectionTicket())))
        val retry = launch(start = CoroutineStart.UNDISPATCHED) {
            suspendCoroutineUninterceptedOrReturn<Unit> { method.invoke(manager, device, request, null, ConnectionAdmission.UNRESTRICTED, it) }
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
