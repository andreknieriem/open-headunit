package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.FragmentedMessageAudit.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The cross-check that catches the one corruption mode nothing else can see.
 *
 * A missing *middle* fragment leaves the run looking intact to [VideoFragmentAssembler] - first,
 * middles, last, in order - so the frame is assembled with a hole in it and decoded as though it were
 * whole. The total size the first fragment declares is the only thing that disagrees.
 *
 * The numbers in the "measured" cases are the real fragment counts and deltas measured on a
 * UNISOC MT50. They are pinned here because the first version of this class
 * compared every run against the establishing run's *whole* difference, so every run with a
 * different fragment count reported - which then exhausted the print budget and left the check dead
 * for the rest of the session.
 */
class FragmentedMessageAuditTest {

    private val vid = 2
    private val aud = 6

    private val first = FragmentedMessageAudit.FLAG_BIT_FIRST
    private val last = FragmentedMessageAudit.FLAG_BIT_LAST
    private val middle = 0
    private val single = first or last

    /** Flags as they appear on the wire, with the encryption bit that every payload message carries. */
    private val encrypted = 0x08

    @Test
    fun `a healthy run reports once and then stays quiet`() {
        val a = FragmentedMessageAudit()
        // Declared total is the sum of the three fragment lengths, whatever the convention turns out
        // to be - the class learns whichever offset the stream uses.
        assertNull(a.onMessage(vid, first or encrypted, encLen = 100, declaredTotal = 300))
        assertNull(a.onMessage(vid, middle or encrypted, encLen = 100, declaredTotal = 0))
        val firstRun = a.onMessage(vid, last or encrypted, encLen = 100, declaredTotal = 0)
        assertNotNull(firstRun)
        assertEquals(Outcome.FIRST_OBSERVATION, firstRun!!.outcome)
        assertEquals(0, firstRun.delta)
        assertEquals(3, firstRun.fragments)

        // Every identical run afterwards is silent.
        repeat(5) {
            assertNull(a.onMessage(vid, first or encrypted, 100, 300))
            assertNull(a.onMessage(vid, middle or encrypted, 100, 0))
            assertNull(a.onMessage(vid, last or encrypted, 100, 0))
        }
    }

    @Test
    fun `the convention is learned, not assumed`() {
        // Same stream shape, but the declared total counts something we do not model - here it is
        // 12 bytes larger than the fragment bodies. The first run establishes that and the rest are
        // silent, so a convention we guessed wrong cannot flood the log.
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 312)
        a.onMessage(vid, middle or encrypted, 100, 0)
        val established = a.onMessage(vid, last or encrypted, 100, 0)!!
        assertEquals(Outcome.FIRST_OBSERVATION, established.outcome)
        assertEquals(12, established.delta)

        assertNull(a.onMessage(vid, first or encrypted, 100, 312))
        assertNull(a.onMessage(vid, middle or encrypted, 100, 0))
        assertNull(a.onMessage(vid, last or encrypted, 100, 0))
    }

    @Test
    fun `a missing middle fragment is reported, and it is the case nothing else sees`() {
        val a = FragmentedMessageAudit()
        // Establish the convention on a good run.
        a.onMessage(vid, first or encrypted, 100, 300)
        a.onMessage(vid, middle or encrypted, 100, 0)
        a.onMessage(vid, last or encrypted, 100, 0)

        // Now the middle never arrives. The assembler sees first-then-last, which is a legal run.
        a.onMessage(vid, first or encrypted, 100, 300)
        val result = a.onMessage(vid, last or encrypted, 100, 0)
        assertNotNull("a 100-byte hole must not pass unreported", result)
        assertEquals(Outcome.DELTA_CHANGED, result!!.outcome)
        assertEquals(100, result.delta)
        assertEquals(0, result.expectedDelta)
        assertEquals(2, result.fragments)
    }

    @Test
    fun `a run that never ends is reported when the next message starts`() {
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 300)
        a.onMessage(vid, middle or encrypted, 100, 0)
        val result = a.onMessage(vid, first or encrypted, 100, 300)
        assertNotNull(result)
        assertEquals(Outcome.TRUNCATED_RUN, result!!.outcome)
        assertEquals(2, result.fragments)
    }

    @Test
    fun `an unfragmented message also ends an open run`() {
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 300)
        val result = a.onMessage(vid, single or encrypted, 500, 0)
        assertEquals(Outcome.TRUNCATED_RUN, result!!.outcome)
        // And leaves nothing open behind it.
        assertEquals(Outcome.ORPHANED_FRAGMENT, a.onMessage(vid, last or encrypted, 100, 0)!!.outcome)
    }

    @Test
    fun `unfragmented messages on their own are never reported`() {
        val a = FragmentedMessageAudit()
        repeat(20) { assertNull(a.onMessage(vid, single or encrypted, 500, 0)) }
    }

    @Test
    fun `a fragment with no run open is an orphan`() {
        val a = FragmentedMessageAudit()
        assertEquals(Outcome.ORPHANED_FRAGMENT, a.onMessage(vid, middle or encrypted, 100, 0)!!.outcome)
        assertEquals(Outcome.ORPHANED_FRAGMENT, a.onMessage(vid, last or encrypted, 100, 0)!!.outcome)
    }

    @Test
    fun `channels are audited independently because their runs interleave`() {
        // Video and audio fragments share one connection, so a run on one must not be closed,
        // extended or blamed by traffic on the other.
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 200)
        a.onMessage(aud, first or encrypted, 50, 150)
        a.onMessage(vid, last or encrypted, 100, 0)
        a.onMessage(aud, middle or encrypted, 50, 0)
        val audioRun = a.onMessage(aud, last or encrypted, 50, 0)
        assertNotNull(audioRun)
        assertEquals(Outcome.FIRST_OBSERVATION, audioRun!!.outcome)
        assertEquals(aud, audioRun.channel)
        assertEquals(3, audioRun.fragments)
        assertEquals(0, audioRun.delta)
    }

    @Test
    fun `an out of range channel is ignored rather than crashing`() {
        val a = FragmentedMessageAudit()
        assertNull(a.onMessage(-1, first or encrypted, 100, 300))
        assertNull(a.onMessage(FragmentedMessageAudit.DEFAULT_CHANNEL_COUNT, first or encrypted, 100, 300))
        assertNull(a.onMessage(9999, last or encrypted, 100, 0))
    }

    @Test
    fun `reset forgets both the open run and the learned convention`() {
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 312)
        a.onMessage(vid, last or encrypted, 100, 0)
        a.onMessage(vid, first or encrypted, 100, 312)
        a.reset()

        assertEquals(
            "a fragment after a reset has no run to belong to",
            Outcome.ORPHANED_FRAGMENT,
            a.onMessage(vid, last or encrypted, 100, 0)!!.outcome
        )
        a.onMessage(vid, first or encrypted, 100, 200)
        assertEquals(
            "and the convention has to be learned again",
            Outcome.FIRST_OBSERVATION,
            a.onMessage(vid, last or encrypted, 100, 0)!!.outcome
        )
    }

    @Test
    fun `the report carries the numbers a reader would need`() {
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 300)
        a.onMessage(vid, middle or encrypted, 100, 0)
        a.onMessage(vid, last or encrypted, 100, 0)
        a.onMessage(vid, first or encrypted, 100, 300)
        val text = a.onMessage(vid, last or encrypted, 100, 0)!!.toString()
        for (field in listOf("channel=2", "fragments=2", "declaredTotal=300", "observed=200", "delta=100", "expectedDelta=0")) {
            org.junit.Assert.assertTrue("report is missing $field: $text", text.contains(field))
        }
    }

    @Test
    fun `the learned convention scales with the fragment count`() {
        // The bug this pins: an establishing run of 3 fragments must not make a run of 7 look wrong.
        val a = FragmentedMessageAudit()
        run(a, fragments = 3, perFragment = -29)
        for (count in listOf(2, 4, 5, 7, 8, 3, 2)) {
            assertNull(
                "a $count-fragment run is not an anomaly just because the first run had 3",
                run(a, fragments = count, perFragment = -29)
            )
        }
    }

    @Test
    fun `the measured fragment counts and deltas from the rig report nothing`() {
        // Every DELTA_CHANGED line the rig printed, replayed. All twenty were false.
        val a = FragmentedMessageAudit()
        val established = run(a, fragments = 3, perFragment = -29)!!
        assertEquals(Outcome.FIRST_OBSERVATION, established.outcome)
        assertEquals(-87, established.delta)
        assertEquals(-29, established.perFragmentDelta)

        for (count in listOf(7, 8, 8, 7, 7, 4, 2, 2, 2, 2, 3, 5, 4, 8, 4, 4, 3, 3, 3, 3)) {
            assertNull(run(a, fragments = count, perFragment = -29))
        }
    }

    @Test
    fun `a hole is still caught once the expectation scales`() {
        // The check has to survive the fix: establish on 3, then lose one fragment out of 5.
        val a = FragmentedMessageAudit()
        run(a, fragments = 3, perFragment = -29)

        // A five-fragment message whose third fragment never arrives: declared covers all five,
        // only four are seen.
        val body = 1000
        val declared = 5 * body
        a.onMessage(vid, first or encrypted, body + 29, declared)
        a.onMessage(vid, middle or encrypted, body + 29, 0)
        a.onMessage(vid, middle or encrypted, body + 29, 0)
        val result = a.onMessage(vid, last or encrypted, body + 29, 0)

        assertNotNull("a 1000-byte hole must not pass unreported", result)
        assertEquals(Outcome.DELTA_CHANGED, result!!.outcome)
        assertEquals(4, result.fragments)
        // Expected -116 for four fragments; the missing fragment's body is the whole difference.
        assertEquals(-116, result.expectedDelta)
        assertEquals(body - 116, result.delta)
    }

    @Test
    fun `a convention that does not divide evenly still scales exactly`() {
        // Nothing requires the per-fragment overhead to divide; the comparison cross-multiplies.
        // Establish 10 over 4 fragments, then a run of 2 must expect exactly 5.
        val a = FragmentedMessageAudit()
        a.onMessage(vid, first or encrypted, 100, 410)
        repeat(2) { a.onMessage(vid, middle or encrypted, 100, 0) }
        val established = a.onMessage(vid, last or encrypted, 100, 0)!!
        assertEquals(10, established.delta)
        assertNull("10 over 4 fragments is not a whole number", established.perFragmentDelta)

        a.onMessage(vid, first or encrypted, 100, 205)
        assertNull("2 fragments should expect exactly half of 10", a.onMessage(vid, last or encrypted, 100, 0))

        a.onMessage(vid, first or encrypted, 100, 206)
        val off = a.onMessage(vid, last or encrypted, 100, 0)!!
        assertEquals(Outcome.DELTA_CHANGED, off.outcome)
        assertEquals(5, off.expectedDelta)
        assertEquals(6, off.delta)
    }

    @Test
    fun `an orphan quotes no expectation, because there is nothing to expect`() {
        val a = FragmentedMessageAudit()
        run(a, fragments = 3, perFragment = -29)
        val orphan = a.onMessage(vid, last or encrypted, 100, 0)!!
        assertEquals(Outcome.ORPHANED_FRAGMENT, orphan.outcome)
        assertNull(orphan.expectedDelta)
        assertNull(orphan.perFragmentDelta)
    }

    @Test
    fun `a truncated run quotes the expectation for what it did see`() {
        val a = FragmentedMessageAudit()
        run(a, fragments = 3, perFragment = -29)
        a.onMessage(vid, first or encrypted, 129, 3000)
        a.onMessage(vid, middle or encrypted, 129, 0)
        val truncated = a.onMessage(vid, first or encrypted, 129, 3000)!!
        assertEquals(Outcome.TRUNCATED_RUN, truncated.outcome)
        assertEquals(2, truncated.fragments)
        assertEquals("scaled to the two fragments that arrived, not to the three of the first run",
            -58, truncated.expectedDelta)
    }

    /**
     * One complete run of [fragments] fragments whose declared total is short by [perFragment] per
     * fragment - the shape the rig measured. Returns whatever the audit made of it.
     */
    private fun run(
        audit: FragmentedMessageAudit,
        fragments: Int,
        perFragment: Int,
        body: Int = 1000,
    ): FragmentedMessageAudit.Result? {
        val declared = fragments * (body + perFragment)
        audit.onMessage(vid, first or encrypted, body, declared)
        repeat(fragments - 2) { audit.onMessage(vid, middle or encrypted, body, 0) }
        return audit.onMessage(vid, last or encrypted, body, 0)
    }
}
