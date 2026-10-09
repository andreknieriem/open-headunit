package com.andrerinas.openheadunit.connection.projection

import android.content.Context
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.modes.helper.NearbySocket
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.Socket

class LoopbackSessionOwnershipTest {
    private val context = mock(Context::class.java)

    private fun manager(connection: SocketProjectionConnection, attempted: String?): CommManager =
        mock(CommManager::class.java, CALLS_REAL_METHODS).also { manager ->
            for ((name, value) in listOf("_connection" to connection, "lastAttemptedEndpoint" to attempted)) {
                CommManager::class.java.getDeclaredField(name).apply { isAccessible = true }.set(manager, value)
            }
        }

    @Test fun `physical peer classifies loopback without mutable attempted endpoint metadata`() {
        for (host in listOf("127.0.0.1", "127.0.0.2", "::1", "localhost", "192.0.2.1")) {
            val connection = SocketProjectionConnection(host, 5277, context)
            val expected = host != "192.0.2.1"
            assertEquals(expected, connection.isLoopbackPeer)
            assertEquals(expected, manager(connection, null).isLoopbackSession)
            assertEquals(expected, manager(connection, "127.0.0.1:5277").isLoopbackSession)
            assertEquals(expected, manager(connection, "192.0.2.99:5277").isLoopbackSession)
        }
    }

    @Test fun `ordinary accepted loopback and remote sockets retain their actual peer`() {
        for (host in listOf("127.0.0.1", "::1", "192.0.2.1")) {
            val socket = object : Socket() {
                override fun getInetAddress() = InetAddress.getByName(host)
                override fun getPort() = 5288
            }
            try {
                val connection = SocketProjectionConnection(socket, context)
                assertEquals(host != "192.0.2.1", manager(connection, null).isLoopbackSession)
            } finally { socket.close() }
        }
    }

    @Test fun `Nearby synthetic loopback never claims local Self ownership`() {
        val socket = NearbySocket().apply {
            inputStreamWrapper = ByteArrayInputStream(byteArrayOf())
            outputStreamWrapper = ByteArrayOutputStream()
        }
        try {
            assertTrue(socket.inetAddress.isLoopbackAddress)
            val connection = SocketProjectionConnection(socket, context)
            assertFalse(connection.isLoopbackPeer)
            assertFalse(manager(connection, "127.0.0.1:0").isLoopbackSession)
        } finally { socket.close() }
    }
}
