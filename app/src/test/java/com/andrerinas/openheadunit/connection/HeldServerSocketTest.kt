package com.andrerinas.openheadunit.connection

import java.net.Socket
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HeldServerSocketTest {
    private val endpoint = "192.168.1.5:5277"
    private val socket = Socket()

    @After fun tearDown() { HeldServerSocket.adopt(socket) }

    @Test fun onlyOneAdopterGetsTheSocket() {
        HeldServerSocket.hold(endpoint, socket)
        assertSame(socket, HeldServerSocket.adopt(endpoint))
        assertNull(HeldServerSocket.adopt(endpoint))
        assertFalse(HeldServerSocket.adopt(socket))
        assertFalse(HeldServerSocket.isHeld(endpoint))
    }

    @Test fun anotherEndpointLeavesItHeld() {
        HeldServerSocket.hold(endpoint, socket)
        assertNull(HeldServerSocket.adopt("192.168.1.6:5277"))
        assertTrue(HeldServerSocket.isHeld(endpoint))
    }

    @Test fun aSocketAdoptedByItsEndpointIsNotAdoptedAgain() {
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.adopt(endpoint)
        assertFalse(HeldServerSocket.adopt(socket))
        assertFalse(socket.isClosed)
    }

    @Test fun aHeldSocketIsAdoptedBySocket() {
        HeldServerSocket.hold(endpoint, socket)
        assertTrue(HeldServerSocket.adopt(socket))
        assertFalse(HeldServerSocket.isHeld(endpoint))
        assertNull(HeldServerSocket.adopt(endpoint))
    }

    @Test fun twoEndpointsAreHeldApart() {
        val other = Socket()
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.hold("192.168.1.6:5277", other)
        assertSame(socket, HeldServerSocket.adopt(endpoint))
        assertSame(other, HeldServerSocket.adopt("192.168.1.6:5277"))
    }

    @Test fun aReplacedHeldSocketIsClosed() {
        val newer = Socket()
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.hold(endpoint, newer)
        assertTrue(socket.isClosed)
        assertSame(newer, HeldServerSocket.adopt(endpoint))
    }

    @Test fun discardAllClosesOnlyWhatIsStillHeld() {
        val adopted = Socket()
        HeldServerSocket.hold("192.168.1.6:5277", adopted)
        HeldServerSocket.adopt("192.168.1.6:5277")
        HeldServerSocket.hold(endpoint, socket)
        HeldServerSocket.discardAll()
        assertTrue(socket.isClosed)
        assertFalse(HeldServerSocket.isHeld(endpoint))
        assertFalse(adopted.isClosed)
    }
}
