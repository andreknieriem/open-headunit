package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AudioWriteLoopTest {
    @Test fun `partial and zero writes preserve every sample in order`() {
        val source = (0 until 32).toList()
        val received = mutableListOf<Int>()
        val results = listOf(4, 0, 8, 0, 20).iterator()
        var waits = 0
        var accepted = 0
        val result = AudioWriteLoop.writeFully(32, { true }, { offset, remaining ->
            val count = results.next()
            assertTrue(count <= remaining)
            received.addAll(source.subList(offset, offset + count))
            count
        }, { accepted += it }, { waits++ })
        assertEquals(source, received)
        assertEquals(32, result)
        assertEquals(32, accepted)
        assertEquals(2, waits)
    }

    @Test fun `shutdown terminates a stalled output without spinning`() {
        var running = true
        var attempts = 0
        val result = AudioWriteLoop.writeFully(32, { running }, { _, _ -> attempts++; 0 }, {}, { running = false })
        assertEquals(0, result)
        assertEquals(1, attempts)
    }

    @Test fun `a device error stops retries and preserves accounting`() {
        var accepted = 0
        val result = AudioWriteLoop.writeFully(32, { true }, { offset, _ ->
            if (offset == 0) 8 else -6
        }, { accepted += it }, { fail("must not retry a device error") })
        assertEquals(-6, result)
        assertEquals(8, accepted)
    }
}
