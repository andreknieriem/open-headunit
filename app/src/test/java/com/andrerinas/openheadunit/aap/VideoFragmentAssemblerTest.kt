package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.VideoFragmentAssembler.Action
import com.andrerinas.openheadunit.aap.VideoFragmentAssembler.Anomaly
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transition table for video fragment reassembly.
 *
 * Issue #219 - the melting/smearing picture - is what reaching the codec with something that is not
 * a whole access unit looks like, and the three ways the stream can produce that are all in here.
 * The case worth naming is `a first fragment with no start code`: it used to leave the run open and
 * silently assemble the frame without its own beginning.
 */
class VideoFragmentAssemblerTest {

    private val single = VideoFragmentAssembler.FLAG_SINGLE
    private val first = VideoFragmentAssembler.FLAG_FIRST
    private val middle = VideoFragmentAssembler.FLAG_MIDDLE
    private val last = VideoFragmentAssembler.FLAG_LAST

    private val atTimestamp = VideoFragmentAssembler.OFFSET_TIMESTAMP_INDICATION
    private val atMedia = VideoFragmentAssembler.OFFSET_MEDIA_INDICATION

    /** A message whose payload begins at offset 10. */
    private fun VideoFragmentAssembler.timestampIndication(flags: Int) =
        onMessage(flags, payloadStartsAt10 = true, payloadStartsAt2 = false)

    /** A message whose payload begins at offset 2. */
    private fun VideoFragmentAssembler.mediaIndication(flags: Int) =
        onMessage(flags, payloadStartsAt10 = false, payloadStartsAt2 = true)

    /** A continuation fragment: no start code is expected anywhere in it. */
    private fun VideoFragmentAssembler.continuation(flags: Int) =
        onMessage(flags, payloadStartsAt10 = false, payloadStartsAt2 = false)

    // ---- the happy paths ----

    @Test
    fun `a complete frame decodes from the timestamp offset`() {
        val a = VideoFragmentAssembler()
        val d = a.timestampIndication(single)
        assertEquals(Action.DecodeWhole(atTimestamp), d.action)
        assertNull(d.anomaly)
        assertFalse("a single-message frame opens no run", a.isAssembling)
    }

    @Test
    fun `the timestamp offset wins when both offsets look like a start code`() {
        // Offset 2 can hold a false positive because a media indication and a timestamp indication
        // are the same message with different headers. 10 is checked first, as it always was.
        val d = VideoFragmentAssembler().onMessage(single, payloadStartsAt10 = true, payloadStartsAt2 = true)
        assertEquals(Action.DecodeWhole(atTimestamp), d.action)
    }

    @Test
    fun `a complete frame decodes from the media offset when there is nothing at ten`() {
        assertEquals(Action.DecodeWhole(atMedia), VideoFragmentAssembler().mediaIndication(single).action)
    }

    @Test
    fun `a two-fragment frame assembles and decodes`() {
        val a = VideoFragmentAssembler()
        assertEquals(Action.BeginAssembly(atTimestamp), a.timestampIndication(first).action)
        assertTrue(a.isAssembling)
        assertEquals(Action.AppendAndDecode, a.continuation(last).action)
        assertFalse("the run closes on its last fragment", a.isAssembling)
        assertFalse(a.hasAnomalies())
    }

    @Test
    fun `a long frame appends every middle fragment`() {
        val a = VideoFragmentAssembler()
        a.mediaIndication(first)
        repeat(5) { assertEquals(Action.Append, a.continuation(middle).action) }
        assertEquals(Action.AppendAndDecode, a.continuation(last).action)
        assertFalse(a.hasAnomalies())
    }

    @Test
    fun `back to back frames leave no state behind`() {
        val a = VideoFragmentAssembler()
        repeat(3) {
            a.timestampIndication(first)
            a.continuation(middle)
            assertEquals(Action.AppendAndDecode, a.continuation(last).action)
        }
        assertFalse(a.hasAnomalies())
        assertFalse(a.isFrameCorrupt)
    }

    // ---- truncation: a run that never gets its last fragment ----

    @Test
    fun `a first fragment during an open run reports the previous frame truncated`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        val d = a.timestampIndication(first)
        assertEquals(Anomaly.TRUNCATED_PREVIOUS, d.anomaly)
        assertEquals("the new frame still starts normally", Action.BeginAssembly(atTimestamp), d.action)
        assertTrue("and its run is open", a.isAssembling)
        assertFalse("the new frame is not itself corrupt", a.isFrameCorrupt)
    }

    @Test
    fun `a complete frame during an open run reports the previous frame truncated`() {
        val a = VideoFragmentAssembler()
        a.mediaIndication(first)
        val d = a.timestampIndication(single)
        assertEquals(Anomaly.TRUNCATED_PREVIOUS, d.anomaly)
        assertEquals(Action.DecodeWhole(atTimestamp), d.action)
        assertFalse(a.isAssembling)
    }

    @Test
    fun `a middle fragment does not itself count as truncation`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        assertNull(a.continuation(middle).anomaly)
    }

    // ---- orphans: a fragment with no run open ----

    @Test
    fun `a middle fragment with no run open is an orphan and is discarded`() {
        val a = VideoFragmentAssembler()
        val d = a.continuation(middle)
        assertEquals(Anomaly.ORPHANED_FRAGMENT, d.anomaly)
        assertEquals(Action.Discard(consumed = true), d.action)
        assertTrue(a.isFrameCorrupt)
    }

    @Test
    fun `a last fragment with no run open is an orphan and closes nothing`() {
        val a = VideoFragmentAssembler()
        val d = a.continuation(last)
        assertEquals(Anomaly.ORPHANED_FRAGMENT, d.anomaly)
        assertEquals(Action.Discard(consumed = true), d.action)
        assertFalse(a.isAssembling)
    }

    @Test
    fun `the rest of a corrupt frame is discarded, and the next frame recovers`() {
        val a = VideoFragmentAssembler()
        a.continuation(middle) // orphan, marks the frame corrupt
        assertEquals(Action.Discard(consumed = true), a.continuation(middle).action)
        assertEquals(Action.Discard(consumed = true), a.continuation(last).action)

        val recovered = a.timestampIndication(first)
        assertEquals(Action.BeginAssembly(atTimestamp), recovered.action)
        assertFalse("a new frame clears the mark", a.isFrameCorrupt)
        assertEquals(Action.AppendAndDecode, a.continuation(last).action)
    }

    // ---- the silent hole this class was extracted to close ----

    @Test
    fun `a first fragment with no start code is discarded, not assembled headless`() {
        val a = VideoFragmentAssembler()
        val d = a.continuation(first)

        assertEquals(Anomaly.HEADLESS_FIRST_FRAGMENT, d.anomaly)
        assertEquals(Action.Discard(consumed = false), d.action)
        assertFalse("the run must stay closed, or the followers get assembled without it", a.isAssembling)
        assertTrue(a.isFrameCorrupt)
    }

    @Test
    fun `the followers of a headless first fragment never reach the decoder`() {
        // The whole point. Before this class, these three appended into the reassembly buffer and
        // the 10 handed the codec an access unit missing its own first fragment, with nothing
        // logged. Now each is an orphan and each is discarded.
        val a = VideoFragmentAssembler()
        a.continuation(first)
        for (flags in listOf(middle, middle, last)) {
            val d = a.continuation(flags)
            assertEquals("flag $flags must not be appended", Action.Discard(consumed = true), d.action)
            assertEquals("flag $flags must be reported", Anomaly.ORPHANED_FRAGMENT, d.anomaly)
        }
    }

    @Test
    fun `a headless first fragment does not consume the message`() {
        // Preserved from the original: process() returned false here, which is what decides whether
        // a media ack goes out and whether the other channel handlers get a look.
        val a = VideoFragmentAssembler()
        assertEquals(Action.Discard(consumed = false), a.continuation(first).action)
    }

    @Test
    fun `a complete frame with no start code is dropped without asking for a keyframe`() {
        // Small ones are control traffic on the video channel, not lost picture - len=4 and len=6
        // arrive at session start on real units. No anomaly, so no keyframe request during setup.
        val a = VideoFragmentAssembler()
        val d = a.continuation(single)
        assertEquals(Action.Discard(consumed = false), d.action)
        assertNull(d.anomaly)
        assertFalse(a.hasAnomalies())
    }

    // ---- overflow, and the counters ----

    @Test
    fun `overflow marks the frame corrupt so the rest of it is skipped`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        assertEquals(Anomaly.OVERFLOW, a.onOverflow())
        assertTrue(a.isFrameCorrupt)
        assertEquals(Action.Discard(consumed = true), a.continuation(middle).action)
        assertEquals(Action.Discard(consumed = true), a.continuation(last).action)
    }

    @Test
    fun `every anomaly is counted and the counters reset independently of the state`() {
        val a = VideoFragmentAssembler()
        a.continuation(middle)      // orphan
        a.continuation(first)       // headless
        a.timestampIndication(first)
        a.timestampIndication(first) // truncated
        a.onOverflow()

        assertEquals(1, a.countOf(Anomaly.ORPHANED_FRAGMENT))
        assertEquals(1, a.countOf(Anomaly.HEADLESS_FIRST_FRAGMENT))
        assertEquals(1, a.countOf(Anomaly.TRUNCATED_PREVIOUS))
        assertEquals(1, a.countOf(Anomaly.OVERFLOW))
        assertTrue(a.hasAnomalies())

        a.resetCounts()
        assertFalse(a.hasAnomalies())
        assertTrue("resetCounts must not reopen or close the run", a.isAssembling)
    }

    @Test
    fun `a headless first fragment during an open run counts both anomalies`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        val d = a.continuation(first)
        assertEquals("the caller is told about the one that decides the action", Anomaly.HEADLESS_FIRST_FRAGMENT, d.anomaly)
        assertEquals(1, a.countOf(Anomaly.TRUNCATED_PREVIOUS))
        assertEquals(1, a.countOf(Anomaly.HEADLESS_FIRST_FRAGMENT))
    }

    @Test
    fun `reset returns the machine to its start state`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        a.onOverflow()
        a.reset()
        assertFalse(a.isAssembling)
        assertFalse(a.isFrameCorrupt)
        assertFalse(a.hasAnomalies())
        assertEquals("a 10 after a reset is an orphan, not a completion", Anomaly.ORPHANED_FRAGMENT, a.continuation(last).anomaly)
    }

    // ---- flags that are not video payload ----

    @Test
    fun `an unhandled flag is passed on without touching the run`() {
        val a = VideoFragmentAssembler()
        a.timestampIndication(first)
        val d = a.onMessage(0, payloadStartsAt10 = false, payloadStartsAt2 = false)
        assertEquals(Action.Discard(consumed = false), d.action)
        assertNull(d.anomaly)
        assertTrue("an unrelated flag must not close an open run", a.isAssembling)
        assertFalse(a.hasAnomalies())
    }
}
