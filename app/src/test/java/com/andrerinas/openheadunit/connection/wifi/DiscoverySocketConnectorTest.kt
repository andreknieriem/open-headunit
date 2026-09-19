package com.andrerinas.openheadunit.connection.wifi

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class DiscoverySocketConnectorTest {
    @Test fun sourceAddressIsBoundBeforeDialAndTheSameSocketIsReturned() {
        val socket = mock<Socket>()
        val result = DiscoverySocketConnector.open("10.42.0.2", 5277, 300, "10.42.0.1", socket)
        assertSame(socket, result)
        val order = inOrder(socket)
        order.verify(socket).bind(InetSocketAddress("10.42.0.1", 0))
        order.verify(socket).connect(InetSocketAddress("10.42.0.2", 5277), 300)
        verify(socket, never()).close()
    }

    @Test fun failedDialClosesSocketAndDoesNotTryAnUnboundFallback() {
        val socket = mock<Socket>()
        doThrow(IOException("network gone")).whenever(socket).connect(any(), any())
        try {
            DiscoverySocketConnector.open("10.42.0.2", 5277, 300, "10.42.0.1", socket)
            fail("Expected failed dial")
        } catch (_: IOException) { }
        verify(socket).bind(InetSocketAddress("10.42.0.1", 0))
        verify(socket, times(1)).connect(any(), any())
        verify(socket).close()
    }

    @Test fun ordinaryDiscoveryKeepsItsUnboundSocketBehavior() {
        val socket = mock<Socket>()
        assertSame(socket, DiscoverySocketConnector.open("192.168.1.10", 5277, 300, socket = socket))
        verify(socket, never()).bind(any())
    }
}
