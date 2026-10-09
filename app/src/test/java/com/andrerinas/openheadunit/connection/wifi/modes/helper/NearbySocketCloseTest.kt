package com.andrerinas.openheadunit.connection.wifi.modes.helper

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class NearbySocketCloseTest {
    @Test fun `close releases both payload waiters and reports IO failure`() {
        val socket = NearbySocket()
        val executor = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(2)
        val waiters = java.util.concurrent.ConcurrentLinkedQueue<Thread>()
        try {
            val read = executor.submit<Throwable?> {
                waiters.add(Thread.currentThread())
                entered.countDown()
                try { socket.getInputStream().read(); null } catch (e: Throwable) { e }
            }
            val write = executor.submit<Throwable?> {
                waiters.add(Thread.currentThread())
                entered.countDown()
                try { socket.getOutputStream().write(1); null } catch (e: Throwable) { e }
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            // Wait until both calls are actually waiting for a payload, rather than testing
            // only the already-closed fast path.
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (System.nanoTime() < deadline &&
                waiters.count { it.state == Thread.State.TIMED_WAITING } < 2) {
                Thread.yield()
            }
            assertEquals(2, waiters.count { it.state == Thread.State.TIMED_WAITING })
            socket.close()
            assertTrue(read.get(1, TimeUnit.SECONDS) is IOException)
            assertTrue(write.get(1, TimeUnit.SECONDS) is IOException)
            assertTrue(socket.isClosed)
            assertFalse(socket.isConnected)
        } finally { socket.close(); executor.shutdownNow() }
    }

    @Test fun `late payloads are closed and cannot resurrect socket`() {
        val socket = NearbySocket()
        socket.close()
        val input = mock(InputStream::class.java)
        val output = mock(OutputStream::class.java)
        socket.inputStreamWrapper = input
        socket.outputStreamWrapper = output
        verify(input).close()
        verify(output).close()
        assertNull(socket.inputStreamWrapper)
        assertNull(socket.outputStreamWrapper)
        assertThrows(IOException::class.java) { socket.getInputStream().available() }
        assertThrows(IOException::class.java) { socket.getOutputStream().flush() }
    }

    @Test fun `closing one failed stream still closes the other exactly once`() {
        val socket = NearbySocket()
        val input = mock(InputStream::class.java)
        val output = mock(OutputStream::class.java)
        doThrow(IOException("already gone")).`when`(input).close()
        socket.inputStreamWrapper = input
        socket.outputStreamWrapper = output
        socket.getInputStream().close()
        socket.close()
        verify(input).close()
        verify(output).close()
    }
}
