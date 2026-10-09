package com.andrerinas.openheadunit.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class InternalFailureRecoveryTest {
    private class Fixture : AutoCloseable {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val context = mock(Context::class.java)
        val settings = mock(Settings::class.java)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        private val queued = ArrayDeque<Runnable>()
        private val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        })
        val owner = Any()
        init {
            field("transportLifecycleLock", Any())
            field("_connectionState", states)
            field("_scope", scope)
            field("usbRecoveryOwner", owner)
            field("context", context)
            field("settings", settings)
        }
        fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(manager, value)
        fun assertRecoverable() {
            val ended = states.value as ConnectionState.Disconnected
            assertFalse("Internal failures must not latch user exit", ended.isUserExit)
            assertTrue("USB recovery must remain owned", manager.ownsUsbRecheck(owner))
            assertEquals("Exactly one cleanup is queued", 1, queued.size)
            verify(context, never()).sendBroadcast(any(android.content.Intent::class.java))
        }
        override fun close() { scope.cancel() }
    }

    @Test fun thrownSocketOpenFailuresHaveTheSamePolicyAsFalseReturns() = runBlocking {
        for (accepted in listOf(false, true)) for (throws in listOf(false, true)) {
            Fixture().use { f ->
                // Even this option must not close the service for an attempt that never opened.
                `when`(f.settings.killOnDisconnect).thenReturn(true)
                mockConstruction(SocketProjectionConnection::class.java) { connection, _ ->
                    runBlocking { `when`(connection.connect()).thenAnswer {
                        if (throws) throw IOException("open failed") else false
                    } }
                }.use {
                    if (accepted) invoke(f.manager, "connectSocket",
                        arrayOf(Socket::class.java, Pair::class.java, ConnectionState.Disconnected::class.java),
                        mock(Socket::class.java), null, null)
                    else invoke(f.manager, "connectIp",
                        arrayOf(String::class.java, Int::class.javaPrimitiveType!!, ConnectionState.Disconnected::class.java),
                        "127.0.0.1", 5277, null)
                    f.assertRecoverable()
                }
            }
        }
    }

    @Test fun thrownUsbOpenFailuresPreserveRecoveryForBothDrivers() = runBlocking {
        for (libusb in listOf(false, true)) {
            Fixture().use { f ->
                val device = mock(UsbDevice::class.java)
                val usb = mock(UsbManager::class.java)
                `when`(f.context.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
                `when`(usb.hasPermission(device)).thenReturn(true)
                `when`(f.settings.useLibusb).thenReturn(libusb)
                `when`(f.settings.killOnDisconnect).thenReturn(true)
                val driver = if (libusb) LibusbProjectionConnection::class.java else StandardUsbProjectionConnection::class.java
                mockConstruction(driver) { connection, _ ->
                    runBlocking { `when`(connection.connect()).thenAnswer { throw IOException("USB open failed") } }
                }.use {
                    invoke(f.manager, "connectUsb", arrayOf(UsbDevice::class.java, ConnectionState.Disconnected::class.java), device, null)
                    f.assertRecoverable()
                }
            }
        }
    }

    @Test fun readingStartupFailureDoesNotBecomeUserExit() = runBlocking {
        Fixture().use { f ->
            val transport = mock(AapTransport::class.java)
            f.field("_transport", transport)
            f.states.value = ConnectionState.HandshakeComplete
            // A failure while acquiring startup audio resources reaches the same protected catch
            // as startReading(). It must not mark this transport as a deliberate user exit.
            `when`(transport.aapAudio).thenAnswer { throw IllegalStateException("audio startup failed") }
            f.manager.startReading()
            f.assertRecoverable()
            verify(transport, never()).wasUserExit = true
        }
    }

    @Test fun reportedFailureRetainsRecoveryButExplicitExitRevokesIt() = runBlocking {
        Fixture().use { f ->
            f.manager.emitError("launch timeout")
            f.assertRecoverable()
            f.manager.disconnect(honorKillOnDisconnect = false)
            assertFalse("An explicit stop still cancels a queued recheck", f.manager.ownsUsbRecheck(f.owner))
        }
    }

    @Test fun timeoutAfterRetirementDoesNotRevokeThePendingUsbCheck() = runBlocking {
        Fixture().use { f ->
            f.field("disconnectRequested", true)
            f.manager.emitError("launch timeout after retirement")
            assertTrue(f.states.value is ConnectionState.Error)
            assertTrue(f.manager.ownsUsbRecheck(f.owner))
            f.manager.disconnect(honorKillOnDisconnect = false)
            assertFalse(f.manager.ownsUsbRecheck(f.owner))
        }
    }

    private suspend fun invoke(manager: CommManager, name: String, types: Array<Class<*>>, vararg args: Any?) {
        val method = CommManager::class.java.getDeclaredMethod(name, *types, Continuation::class.java)
            .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> method.invoke(manager, *args, continuation) }
    }
}
