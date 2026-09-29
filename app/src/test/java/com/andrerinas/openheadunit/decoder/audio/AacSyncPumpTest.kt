package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class AacSyncPumpTest {
    @Test fun `full codec output is released before waiting for an input slot`() {
        var outputHeld = true
        var waitedUs = 0L
        val index = AacSyncPump.awaitInput({ 0L }, { true }, { outputHeld = false }, { timeout ->
            waitedUs += if (outputHeld) timeout else 0
            if (outputHeld) -1 else 3
        })
        assertEquals(3, index)
        assertEquals(0L, waitedUs)
    }

    @Test fun `output that appears during input backpressure is drained without a two hundred millisecond stall`() {
        var now = 0L
        var drains = 0
        var released = false
        val index = AacSyncPump.awaitInput({ now }, { true }, {
            drains++
            if (now >= 20) released = true
        }, { timeout ->
            if (released) 2 else { now += timeout / 1000; -1 }
        })
        assertEquals(2, index)
        assertEquals(20L, now)
        assertTrue(drains > 1)
    }

    @Test fun `dead codec times out while output remains serviced at short intervals`() {
        var now = 0L
        var drains = 0
        val index = AacSyncPump.awaitInput({ now }, { true }, { drains++ }, { timeout ->
            assertTrue(timeout <= 5000)
            now += timeout / 1000
            -1
        })
        assertEquals(-1, index)
        assertEquals(200L, now)
        assertTrue(drains >= 40)
    }

    @Test fun `stop during a drain does not submit to a retired codec`() {
        var running = true
        val index = AacSyncPump.awaitInput({ 0L }, { running }, { running = false }, {
            fail("must not touch retired codec"); 0
        })
        assertEquals(-1, index)
    }
}
