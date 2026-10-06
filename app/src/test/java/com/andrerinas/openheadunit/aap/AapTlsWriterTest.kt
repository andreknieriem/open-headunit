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
        assertTrue(writer.send(byteArrayOf(1, 11, 0, 1, 30), 5))
        assertEquals(listOf(10, 20, 30), writes)
    }

    @Test fun failedControlWritePreventsFollowingApplicationAndFutureWrites() {
        val ssl = mock<AapSsl>()
        whenever(ssl.drainControlRecords()).thenReturn(listOf(byteArrayOf(1)))
        whenever(ssl.encrypt(any(), any(), any())).thenReturn(ByteArrayWithLimit(ByteArray(5), 5))
        var calls = 0
        val writer = AapTlsWriter(ssl) { _, _ -> calls++; 0 }
        assertFalse(writer.send(ByteArray(5), 5))
        assertFalse(writer.send(ByteArray(5), 5))
        assertFalse(writer.flushControl())
        assertEquals(1, calls)
    }
}
