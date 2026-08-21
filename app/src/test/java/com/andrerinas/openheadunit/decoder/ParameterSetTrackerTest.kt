package com.andrerinas.openheadunit.decoder

import com.andrerinas.openheadunit.decoder.ParameterSetTracker.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterSetTrackerTest {

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private fun ParameterSetTracker.offer(kind: Kind, data: ByteArray) =
        offer(kind, data, 0, data.size)

    @Test
    fun `the first of a kind is not a change`() {
        val t = ParameterSetTracker()
        assertFalse(t.offer(Kind.SPS, bytes(1, 2, 3)))
        assertNull(t.takeChange())
        // Stored all the same, which only a differing set can prove: had the first offer been
        // dropped, this one would be a first of a kind too and would report no change.
        assertTrue(t.offer(Kind.SPS, bytes(1, 2, 4)))
    }

    @Test
    fun `the same bytes again are not a change`() {
        // The ordinary case: parameter sets ride along with every keyframe, so this is what the
        // tracker sees a few hundred times a session and it must stay silent for all of them.
        val t = ParameterSetTracker()
        t.offer(Kind.SPS, bytes(1, 2, 3))
        repeat(5) { assertFalse(t.offer(Kind.SPS, bytes(1, 2, 3))) }
        assertNull(t.takeChange())
    }

    @Test
    fun `different bytes are a change, reported once`() {
        val t = ParameterSetTracker()
        t.offer(Kind.SPS, bytes(1, 2, 3))
        assertTrue(t.offer(Kind.SPS, bytes(1, 2, 4)))

        val change = t.takeChange()!!
        assertEquals(1, change.ordinal)
        assertEquals(listOf(Kind.SPS), change.kinds)

        // Latched: the same set arriving again on the next keyframe is not a second change.
        assertFalse(t.offer(Kind.SPS, bytes(1, 2, 4)))
        assertNull(t.takeChange())
    }

    @Test
    fun `a length change is a change`() {
        val t = ParameterSetTracker()
        t.offer(Kind.PPS, bytes(9, 9))
        assertTrue(t.offer(Kind.PPS, bytes(9, 9, 9)))
        assertEquals(listOf(Kind.PPS), t.takeChange()!!.kinds)
    }

    @Test
    fun `several kinds changing in one access unit are one report`() {
        val t = ParameterSetTracker()
        listOf(Kind.VPS, Kind.SPS, Kind.PPS).forEach { t.offer(it, bytes(it.ordinal)) }
        t.offer(Kind.PPS, bytes(7))
        t.offer(Kind.VPS, bytes(8))

        val change = t.takeChange()!!
        assertEquals(1, change.ordinal)
        // Reported in VPS/SPS/PPS order whatever order they arrived in.
        assertEquals(listOf(Kind.VPS, Kind.PPS), change.kinds)
        assertNull(t.takeChange())
    }

    @Test
    fun `changes are counted across the session`() {
        val t = ParameterSetTracker()
        t.offer(Kind.SPS, bytes(1))
        t.offer(Kind.SPS, bytes(2))
        assertEquals(1, t.takeChange()!!.ordinal)
        t.offer(Kind.SPS, bytes(3))
        assertEquals(2, t.takeChange()!!.ordinal)
    }

    @Test
    fun `it reads only the slice it was given`() {
        // The caller hands it a NAL inside a frame buffer that is reused, so a tracker that kept the
        // array instead of a copy would compare the next frame against itself.
        val t = ParameterSetTracker()
        val frame = bytes(0, 0, 1, 0x67, 0x42, 0x00)
        assertFalse(t.offer(Kind.SPS, frame, 3, 3))
        frame[3] = 0x68
        frame[4] = 0x43
        assertTrue(t.offer(Kind.SPS, frame, 3, 3))
    }

    @Test
    fun `a nonsense slice is ignored rather than stored`() {
        val t = ParameterSetTracker()
        assertFalse(t.offer(Kind.SPS, bytes(1, 2), 0, 0))
        assertFalse(t.offer(Kind.SPS, bytes(1, 2), 1, 5))
        assertFalse(t.offer(Kind.SPS, bytes(1, 2), -1, 2))
        // Nothing was stored, so a real set is still the first of its kind. Had any of the three
        // been kept, these bytes would differ from it and this would report a change.
        assertFalse(t.offer(Kind.SPS, bytes(1, 2)))
    }

    @Test
    fun `reset forgets everything`() {
        val t = ParameterSetTracker()
        t.offer(Kind.SPS, bytes(1))
        t.offer(Kind.SPS, bytes(2))
        t.reset()
        assertNull(t.takeChange())
        assertFalse(t.offer(Kind.SPS, bytes(3)))
    }
}
