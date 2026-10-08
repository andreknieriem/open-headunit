package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.utils.AppLog
import org.junit.Assert.*
import org.junit.Test

class VideoOutputEventsTest {
    @Test fun `retirement fences callbacks but preserves all queued diagnostics and origins`() {
        val previous = AppLog.LOGGER
        val lines = mutableListOf<String>()
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) { lines.add(msg) }
        }
        try {
            var live = true
            var staleCallbacks = 0
            val events = VideoDecoder.OutputEvents()
            events.info("Throughput over", "VideoDecoder.logThroughput")
            events.callback { live = false }
            events.warn("Decoder stall detected")
            events.callback { staleCallbacks++ }
            events.error("Codec exception in output thread")
            events.dispatch { live }
            events.dispatch { false }
            assertEquals(0, staleCallbacks)
            assertEquals(3, lines.size)
            assertTrue(lines[0].contains("VideoDecoder.logThroughput | Throughput over"))
            assertTrue(lines[1].contains("VideoDecoder.outputThreadLoop | Decoder stall detected"))
            assertTrue(lines[2].contains("VideoDecoder.outputThreadLoop | Codec exception"))
        } finally { AppLog.LOGGER = previous }
    }
}
