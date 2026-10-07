package com.andrerinas.openheadunit.connection.wifi.modes.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NearbySocketRetirementTest {
    @Test fun closeWakesReadsWaitingForThePhoneStream() {
        val socket = NearbySocket()
        val started = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val read = worker.submit<Boolean> {
                started.countDown()
                try {
                    socket.getInputStream().read()
                    false
                } catch (_: SocketException) {
                    true
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
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
        val worker = Executors.newSingleThreadExecutor()
        try {
            val write = worker.submit<Boolean> {
                started.countDown()
                try {
                    socket.getOutputStream().write(1)
                    false
                } catch (_: SocketException) {
                    true
                }
            }
            assertTrue(started.await(1, TimeUnit.SECONDS))
            socket.close()
            assertTrue(write.get(1, TimeUnit.SECONDS))
        } finally {
            socket.close()
            worker.shutdownNow()
        }
    }
}
