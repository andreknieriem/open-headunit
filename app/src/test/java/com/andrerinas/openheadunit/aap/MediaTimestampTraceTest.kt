package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler.Companion.FLAG_FIRST
import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler.Companion.FLAG_LAST
import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler.Companion.FLAG_MIDDLE
import com.andrerinas.openheadunit.decoder.video.VideoFragmentAssembler.Companion.FLAG_SINGLE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaTimestampTraceTest {

    private val trace = MediaTimestampTrace()

    private fun media(ts: Long): ByteArray {
        val b = ByteArray(14)
        for (i in 0 until 8) b[2 + i] = (ts shr (56 - 8 * i)).toByte()
        return b
    }

    private fun MediaTimestampTrace.media(ts: Long, now: Long, flags: Int = FLAG_SINGLE): String? {
        val b = media(ts)
        return onMessage(0, flags, b, b.size, now)
    }

    private fun MediaTimestampTrace.config(now: Long = 0, flags: Int = FLAG_SINGLE) =
        onMessage(1, flags, ByteArray(8), 8, now)

    @Test
    fun `the first media unit after the first config logs first`() {
        assertNull(trace.config())
        assertEquals("AapVideo: forwarded stream media timestamp first=5000000us", trace.media(5_000_000, 100))
    }

    @Test
    fun `a restart logs the previous and next timestamp, the step and the arrival gap`() {
        trace.config()
        trace.media(5_000_000, 1000)
        trace.config(1500)
        val line = trace.media(5_600_000, 1700)!!
        assertTrue(line, line.endsWith("5000000us -> 5600000us (step 600000us), 700ms since its last unit"))
    }

    @Test
    fun `a clock that resets shows a negative step`() {
        trace.config()
        trace.media(5_000_000, 1000)
        trace.config(1100)
        assertTrue(trace.media(200, 1200)!!.contains("(step -4999800us)"))
    }

    @Test
    fun `media units with no config between them log nothing`() {
        trace.config()
        trace.media(1, 0)
        assertNull(trace.media(2, 33))
        assertNull(trace.media(3, 66))
    }

    @Test
    fun `middle and last fragments are ignored`() {
        trace.config()
        assertNull(trace.media(1, 0, FLAG_MIDDLE))
        assertNull(trace.media(1, 0, FLAG_LAST))
        assertTrue(trace.media(1, 0, FLAG_FIRST)!!.contains("first=1us"))
    }

    @Test
    fun `a type-0 message shorter than 10 bytes is ignored`() {
        trace.config()
        assertNull(trace.onMessage(0, FLAG_SINGLE, ByteArray(9), 9, 0))
        assertTrue(trace.media(7, 1)!!.contains("first=7us"))
    }

    @Test
    fun `the timestamp is read big-endian as 64 bits`() {
        trace.config()
        assertEquals("AapVideo: forwarded stream media timestamp first=5000000000us", trace.media(5_000_000_000L, 0))
    }
}
