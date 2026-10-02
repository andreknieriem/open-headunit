package com.andrerinas.openheadunit.decoder.audio

import com.andrerinas.openheadunit.decoder.audio.OutputWriteWatchdog.Action.*
import org.junit.Assert.*
import org.junit.Test

class OutputWriteWatchdogTest {
    @Test fun `normal output backpressure never reopens the device`() {
        val watchdog = OutputWriteWatchdog()
        for (cycle in 0L..100L) {
            val now = cycle * 10
            assertEquals(WAIT, watchdog.onBlocked(now))
            assertEquals(WAIT, watchdog.onBlocked(now + 9))
            watchdog.onProgress()
        }
    }

    @Test fun `a resumed output that remains full is reopened after half a second`() {
        val watchdog = OutputWriteWatchdog()
        assertEquals(WAIT, watchdog.onBlocked(100))
        assertEquals(WAIT, watchdog.onBlocked(599))
        assertEquals(REOPEN, watchdog.onBlocked(600))
    }

    @Test fun `a route with no progress cannot reopen forever`() {
        val watchdog = OutputWriteWatchdog()
        for (attempt in 0L..2L) {
            assertEquals(WAIT, watchdog.onBlocked(attempt * 600))
            assertEquals(if (attempt < 2) REOPEN else STOP, watchdog.onBlocked(attempt * 600 + 500))
        }
    }

    @Test fun `successful playback resets an earlier stall and recovery budget`() {
        val watchdog = OutputWriteWatchdog()
        assertEquals(WAIT, watchdog.onBlocked(0))
        assertEquals(REOPEN, watchdog.onBlocked(500))
        assertEquals(WAIT, watchdog.onBlocked(600))
        assertEquals(REOPEN, watchdog.onBlocked(1100))
        watchdog.onProgress()
        assertEquals(WAIT, watchdog.onBlocked(10000))
        assertEquals(WAIT, watchdog.onBlocked(10499))
        assertEquals(REOPEN, watchdog.onBlocked(10500))
    }

    @Test fun `recovery retries only the unwritten tail of a partially accepted cycle`() {
        val watchdog = OutputWriteWatchdog()
        var now = 0L
        var firstWrite = true
        var reopened = false
        var tailOffset = -1
        var tailSize = -1
        val written = AudioWriteLoop.writeFully(960, { true }, { offset, remaining ->
            when {
                firstWrite -> { firstWrite = false; 320 }
                !reopened -> 0
                else -> { tailOffset = offset; tailSize = remaining; remaining }
            }
        }, { watchdog.onProgress() }, {
            when (watchdog.onBlocked(now)) {
                WAIT -> now += 100
                REOPEN -> reopened = true
                STOP -> fail("replacement output should make progress")
            }
        })
        assertTrue(reopened)
        assertEquals(500L, now)
        assertEquals(320, tailOffset)
        assertEquals(640, tailSize)
        assertEquals(960, written)
    }
}
