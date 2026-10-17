package com.andrerinas.openheadunit.connection

import android.content.Context
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbySocket
import com.andrerinas.openheadunit.connection.wifi.scan.FytConnectionStart
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.InetAddress
import java.net.Socket

class ScanControlConnectionTest {
    private fun set(manager: CommManager, name: String, value: Any?) =
        CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)

    private fun manager(socket: Socket): CommManager = mock(CommManager::class.java, CALLS_REAL_METHODS).also {
        set(it, "transportLifecycleLock", Any())
        set(it, "_connection", SocketProjectionConnection(socket, mock(Context::class.java)))
        set(it, "connectionAttempt", Any())
        set(it, "physicalConnectionReached", true)
    }

    private fun socket(host: String) = object : Socket() {
        override fun getInetAddress() = InetAddress.getByName(host)
        override fun getPort() = 5277
    }

    @Test fun `outgoing IP sockets never authorize Native scan control`() {
        socket("192.0.2.1").use { socket ->
            val manager = manager(socket)
            assertNotNull(manager.acceptedWirelessSession)
            set(manager, "outgoingEndpoint", "192.0.2.1" to 5277)
            assertNull(manager.acceptedWirelessSession)
        }
    }

    @Test fun `self nearby unopened and retiring sockets have no scan owner`() {
        socket("127.0.0.1").use { assertNull(manager(it).acceptedWirelessSession) }
        NearbySocket().use { assertNull(manager(it).acceptedWirelessSession) }
        socket("192.0.2.1").use {
            val manager = manager(it)
            set(manager, "physicalConnectionReached", false)
            assertNull(manager.acceptedWirelessSession)
            set(manager, "physicalConnectionReached", true)
            set(manager, "disconnectRequested", true)
            assertNull(manager.acceptedWirelessSession)
            set(manager, "_connection", null)
            assertNull(manager.acceptedWirelessSession)
        }
    }

    @Test fun `a conflated reconnect cancels the old FYT permit without observing disconnected`() {
        socket("192.0.2.1").use {
            val manager = manager(it)
            val gate = FytConnectionStart()
            gate.session(manager.acceptedWirelessSession!!)
            val old = gate.claim(true)!!
            gate.session(manager.acceptedWirelessSession!!)
            assertNull(gate.claim(true))
            set(manager, "connectionAttempt", Any())
            gate.session(manager.acceptedWirelessSession!!)
            assertFalse(old.valid)
            assertNotNull(gate.claim(true))
        }
    }
}
