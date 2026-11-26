package com.andrerinas.openheadunit.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogRingBufferTest {

    @Test
    fun `empty buffer reports zero lines`() {
        val buf = AppLogRingBuffer(capacity = 4)
        assertEquals(0, buf.size())
        assertEquals(0L, buf.droppedCount())
        assertEquals("=== In-app OPENHU ring buffer (0 lines) ===", buf.snapshot())
    }

    @Test
    fun `preserves insertion order before wrapping`() {
        val buf = AppLogRingBuffer(capacity = 5)
        buf.record("I", "OPENHU", "first", timestampMs = 1000L)
        buf.record("W", "OPENHU", "second", timestampMs = 2000L)
        val out = buf.snapshot()
        assertEquals(2, buf.size())
        assertEquals(0L, buf.droppedCount())
        assertTrue(out.contains("(2 lines)"))
        assertTrue(out.indexOf("I/OPENHU: first") < out.indexOf("W/OPENHU: second"))
    }

    @Test
    fun `overwrites oldest entries when capacity is exceeded`() {
        val buf = AppLogRingBuffer(capacity = 3)
        for (i in 1..5) {
            buf.record("D", "OPENHU", "msg-$i", timestampMs = i * 1000L)
        }
        val out = buf.snapshot()
        assertEquals(3, buf.size())
        assertEquals(2L, buf.droppedCount())
        assertTrue(out.contains("(3 lines, 2 older dropped)"))
        assertFalse(out.contains("msg-1"))
        assertFalse(out.contains("msg-2"))
        assertTrue(out.indexOf("msg-3") < out.indexOf("msg-4"))
        assertTrue(out.indexOf("msg-4") < out.indexOf("msg-5"))
    }

    @Test
    fun `clear resets size and dropped count`() {
        val buf = AppLogRingBuffer(capacity = 2)
        buf.record("I", "OPENHU", "a", 1000L)
        buf.record("I", "OPENHU", "b", 2000L)
        buf.record("I", "OPENHU", "c", 3000L)
        buf.clear()
        assertEquals(0, buf.size())
        assertEquals(0L, buf.droppedCount())
        assertFalse(buf.snapshot().contains("c"))
    }
}
