package com.andrerinas.openheadunit.connection.wifi.modes.helper

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
}
