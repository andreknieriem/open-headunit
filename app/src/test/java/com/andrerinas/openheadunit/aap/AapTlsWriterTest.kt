package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AapTlsWriterTest {
    @Test fun blockedWriteDoesNotHoldEngineAndControlStaysInOrder() {
        val ssl = mock<AapSsl>()
        val controls = java.util.ArrayDeque<ByteArray>()
        whenever(ssl.drainControlRecords()).thenAnswer {
            controls.toList().also { controls.clear() }
        }
        whenever(ssl.encrypt(any(), any(), any())).thenAnswer {
            val input = it.getArgument<ByteArray>(2)
            ByteArrayWithLimit(input.copyOf(), input.size)
        }
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val engineReleased = CountDownLatch(1)
        val writes = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val writer = AapTlsWriter(ssl) { data, length ->
            writes.add(data[4].toInt())
            if (writes.size == 1) {
                entered.countDown()
                check(resume.await(5, TimeUnit.SECONDS))
            }
            length
        }
        assertTrue(writer.activate())
        val send = thread { writer.send(byteArrayOf(1, 11, 0, 1, 10), 5) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val poll = thread {
            synchronized(ssl) { controls.add(byteArrayOf(20)); engineReleased.countDown() }
        }
        try {
            assertTrue("blocked write held the engine monitor", engineReleased.await(1, TimeUnit.SECONDS))
        } finally {
            resume.countDown()
            send.join(5000)
            poll.join(5000)
        }
        assertEquals(AapTlsWriter.Result.SENT, writer.send(byteArrayOf(1, 11, 0, 1, 30), 5))
        assertEquals(listOf(10, 20, 30), writes)
    }

    @Test fun failedControlWritePreventsFollowingApplicationAndFutureWrites() {
        val ssl = mock<AapSsl>()
        whenever(ssl.drainControlRecords()).thenReturn(listOf(byteArrayOf(1)))
        whenever(ssl.encrypt(any(), any(), any())).thenReturn(ByteArrayWithLimit(ByteArray(5), 5))
        var calls = 0
        val writer = AapTlsWriter(ssl) { _, _ -> calls++; 0 }
        assertTrue(writer.activate())
        assertEquals(AapTlsWriter.Result.FAILED, writer.send(ByteArray(5), 5))
        assertEquals(AapTlsWriter.Result.FAILED, writer.send(ByteArray(5), 5))
        assertFalse(writer.flushControl())
        assertEquals(1, calls)
    }
    @Test fun retirementDoesNotWaitForBlockedIoAndCannotBeReactivated() {
        val ssl = mock<AapSsl>()
        whenever(ssl.drainControlRecords()).thenReturn(emptyList())
        whenever(ssl.encrypt(any(), any(), any())).thenReturn(ByteArrayWithLimit(ByteArray(6), 6))
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val writer = AapTlsWriter(ssl) { _, length ->
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            length
        }
        assertTrue(writer.activate())
        val worker = thread { writer.send(ByteArray(6), 6) }
        val retiring = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            retiring.submit { writer.retire() }.get(1, TimeUnit.SECONDS)
            assertFalse(writer.isReady)
            assertFalse(writer.activate())
        } finally { resume.countDown(); worker.join(5000); retiring.shutdownNow() }
        assertEquals(AapTlsWriter.Result.NOT_READY, writer.send(ByteArray(6), 6))
        verify(ssl).encrypt(any(), any(), any())
    }

}
