package com.andrerinas.openheadunit.connection.wifi.modes.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NearbySocketRetirementTest {
    private fun awaitStreamLatch(thread: Thread) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (thread.stackTrace.any {
                    it.className == CountDownLatch::class.java.name && it.methodName == "await"
                }) return
            Thread.sleep(1)
        }
        throw AssertionError("stream I/O never entered the latch wait")
    }

    @Test fun closeWakesReadsWaitingForThePhoneStream() {
        val socket = NearbySocket()
        val started = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<Boolean> {
                thread.set(Thread.currentThread())
                started.countDown()
                try {
                    socket.getInputStream().read()
                    false
                } catch (_: SocketException) {
                    true
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            awaitStreamLatch(thread.get())
            socket.close()
            assertTrue(read.get(1, TimeUnit.SECONDS))
            assertFalse(socket.isConnected)
        } finally {
            socket.close()
            worker.shutdownNow()
        }
    }

    @Test fun closeWakesWritesWaitingForTheLocalStream() {
        val socket = NearbySocket()
        val started = CountDownLatch(1)
        val thread = AtomicReference<Thread>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val write = worker.submit<Boolean> {
                thread.set(Thread.currentThread())
                started.countDown()
                try {
                    socket.getOutputStream().write(1)
                    false
                } catch (_: SocketException) {
                    true
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            awaitStreamLatch(thread.get())
            socket.close()
            assertTrue(write.get(1, TimeUnit.SECONDS))
        } finally {
            socket.close()
            worker.shutdownNow()
        }
    }
    @Test fun attachmentBeforeAndAfterRetirementClosesBothDirectionsExactlyOnce() {
        for (attachFirst in listOf(true, false)) {
            val socket = NearbySocket()
            var inputsClosed = 0
            var outputsClosed = 0
            val input = object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
                override fun close() { inputsClosed++; throw java.io.IOException("inbound close failed") }
            }
            val output = object : java.io.ByteArrayOutputStream() {
                override fun close() { outputsClosed++ }
            }
            if (!attachFirst) socket.close()
            socket.inputStreamWrapper = input
            socket.outputStreamWrapper = output
            socket.close()
            socket.close()
            assertEquals(1, inputsClosed)
            assertEquals(1, outputsClosed)
            assertNull(socket.inputStreamWrapper)
            assertNull(socket.outputStreamWrapper)
            assertTrue(socket.isClosed)
        }
    }

    @Test fun latePayloadIsRejectedWhileAnEarlierStreamCloseIsBlocked() {
        val socket = NearbySocket()
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val worker = Executors.newFixedThreadPool(2)
        socket.inputStreamWrapper = object : java.io.ByteArrayInputStream(byteArrayOf(1)) {
            override fun close() { entered.countDown(); check(resume.await(5, TimeUnit.SECONDS)) }
        }
        try {
            val closing = worker.submit { socket.close() }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            assertTrue(socket.isClosed)
            assertFalse(socket.isConnected)
            var lateInputClosed = false
            var lateOutputClosed = false
            val attached = worker.submit {
                socket.inputStreamWrapper = object : java.io.ByteArrayInputStream(byteArrayOf(2)) {
                    override fun close() { lateInputClosed = true }
                }
                socket.outputStreamWrapper = object : java.io.ByteArrayOutputStream() {
                    override fun close() { lateOutputClosed = true }
                }
            }
            attached.get(1, TimeUnit.SECONDS) // Must finish before the blocked close is released.
            assertTrue(lateInputClosed)
            assertTrue(lateOutputClosed)
            assertNull(socket.inputStreamWrapper)
            assertNull(socket.outputStreamWrapper)
            resume.countDown()
            closing.get(1, TimeUnit.SECONDS)
        } finally { resume.countDown(); worker.shutdownNow() }
    }

    @Test fun inheritedSocketImplementationIsClosedAlongWithNearbyStreams() {
        val socket = NearbySocket()
        // SocketProjectionConnection initializes SocketImpl through inherited socket options.
        // Bind its otherwise unused descriptor so ownership can be observed without JDK internals.
        socket.soTimeout = 250
        socket.bind(java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0))
        val address = socket.localSocketAddress
        try {
            java.net.ServerSocket().use { competing ->
                try {
                    competing.bind(address)
                    throw AssertionError("test socket did not reserve its address")
                } catch (_: java.net.BindException) { }
            }
            socket.close()
            assertTrue(socket.isClosed)
            java.net.ServerSocket().use { replacement ->
                replacement.bind(address) // Fails if SocketImpl survived a virtual isClosed check.
                assertTrue(replacement.isBound)
            }
            socket.close()
        } finally { socket.close() }
    }

}
