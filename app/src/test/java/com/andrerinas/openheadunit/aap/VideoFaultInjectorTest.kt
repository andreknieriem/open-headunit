package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.VideoFaultInjector.Effect
import com.andrerinas.openheadunit.aap.VideoFaultInjector.Mode
import com.andrerinas.openheadunit.aap.VideoFaultInjector.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The test tool that makes the reassembly fixes measurable on a unit that is working correctly.
 *
 * Its own correctness matters as much as the code it exercises: a brief that says "a fault every six
 * seconds" has to be true, or the results cannot be compared between builds.
 */
class VideoFaultInjectorTest {

    private val first = VideoFragmentAssembler.FLAG_FIRST
    private val middleFlag = VideoFragmentAssembler.FLAG_MIDDLE
    private val lastFlag = VideoFragmentAssembler.FLAG_LAST
    private val single = VideoFragmentAssembler.FLAG_SINGLE

    @Test
    fun `off does nothing to anything`() {
        val injector = VideoFaultInjector(Mode.OFF, rate = 2)
        assertFalse(injector.isActive)
        for (flags in listOf(first, middleFlag, lastFlag, single)) {
            repeat(10) { assertEquals(Effect.NONE, injector.effectFor(flags)) }
        }
        assertEquals(0L, injector.injectedCount)
    }

    @Test
    fun `every Nth targeted message is faulted, and nothing else is touched`() {
        val injector = VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = 3)
        assertTrue(injector.isActive)

        val effects = (1..9).map { injector.effectFor(first) }
        assertEquals(
            "one in three, deterministically",
            listOf(Effect.NONE, Effect.NONE, Effect.DROP, Effect.NONE, Effect.NONE, Effect.DROP, Effect.NONE, Effect.NONE, Effect.DROP),
            effects
        )
        assertEquals(3L, injector.injectedCount)
    }

    @Test
    fun `the rate counts only the flag being attacked`() {
        // A frame is often three or four messages, so counting all video traffic would make the rate
        // mean something three times rarer than the brief says.
        val injector = VideoFaultInjector(Mode.DROP_LAST_FRAGMENT, rate = 2)
        // A full frame's worth of messages that are not the target.
        repeat(20) {
            injector.effectFor(first)
            injector.effectFor(middleFlag)
            injector.effectFor(single)
        }
        assertEquals("untargeted traffic must not advance the counter", 0L, injector.injectedCount)
        assertEquals(Effect.NONE, injector.effectFor(lastFlag))
        assertEquals(Effect.DROP, injector.effectFor(lastFlag))
    }

    @Test
    fun `each mode attacks the flag it says it does`() {
        assertEquals(null, VideoFaultInjector.targetFlag(Mode.OFF))
        assertEquals(first, VideoFaultInjector.targetFlag(Mode.DROP_FIRST_FRAGMENT))
        assertEquals(first, VideoFaultInjector.targetFlag(Mode.HIDE_START_CODE))
        assertEquals(middleFlag, VideoFaultInjector.targetFlag(Mode.DROP_MIDDLE_FRAGMENT))
        assertEquals(lastFlag, VideoFaultInjector.targetFlag(Mode.DROP_LAST_FRAGMENT))
    }

    @Test
    fun `hiding the start code is not a drop`() {
        // The distinction matters: the bytes arrive, so the framing audit sees a complete run and
        // only the reassembler should object.
        val injector = VideoFaultInjector(Mode.HIDE_START_CODE, rate = 2)
        assertEquals(Effect.NONE, injector.effectFor(first))
        assertEquals(Effect.HIDE_START_CODE, injector.effectFor(first))
    }

    @Test
    fun `the rate is clamped to something that can still produce a picture`() {
        assertEquals(VideoFaultInjector.MIN_RATE, VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = 1).rate)
        assertEquals(VideoFaultInjector.MIN_RATE, VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = 0).rate)
        assertEquals(VideoFaultInjector.MIN_RATE, VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = -5).rate)
        assertEquals(VideoFaultInjector.MAX_RATE, VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = Int.MAX_VALUE).rate)
        assertEquals(300, VideoFaultInjector(Mode.DROP_FIRST_FRAGMENT, rate = 300).rate)
    }

    @Test
    fun `every mode round-trips through the value stored in settings`() {
        for (mode in Mode.entries) {
            assertEquals(mode, Mode.fromInt(mode.value))
        }
        assertEquals("an unknown stored value must not throw", null, Mode.fromInt(-1))
        assertEquals(null, Mode.fromInt(99))
    }

    @Test
    fun `the default rate is rare enough to leave a fair sample of normal behaviour`() {
        // At the ~50 messages per second a healthy link carries, one in 300 is a fault every few
        // seconds. If this ever changes, the test brief's timings change with it.
        assertTrue(VideoFaultInjector.DEFAULT_RATE >= 100)
        assertTrue(VideoFaultInjector.DEFAULT_RATE in VideoFaultInjector.MIN_RATE..VideoFaultInjector.MAX_RATE)
    }

    @Test
    fun `the candidate count follows the targeted flag and nothing else`() {
        // The denominator the log needs. A run can inject nothing because the rate is high or because
        // the stream never fragmented, and only this number tells those apart.
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 4)
        repeat(20) { injector.effectFor(first) }
        repeat(20) { injector.effectFor(single) }
        assertEquals("untargeted flags are not candidates", 0L, injector.matchingCount)

        repeat(7) { injector.effectFor(middleFlag) }
        assertEquals(7L, injector.matchingCount)
        assertEquals(1L, injector.injectedCount)
    }

    @Test
    fun `a budget stops injection at exactly N faults`() {
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 2, budget = 3)
        assertEquals(3, injector.budget)
        // Six targeted messages at one in two is three faults, which is the whole budget.
        repeat(6) { injector.effectFor(middleFlag) }
        assertEquals(3L, injector.injectedCount)
        assertTrue(injector.budgetSpent)
        // Everything after it goes through untouched, however long the run continues.
        repeat(50) { assertEquals(Effect.NONE, injector.effectFor(middleFlag)) }
        assertEquals(3L, injector.injectedCount)
    }

    @Test
    fun `a spent budget still counts candidates`() {
        // The clean half of a bounded run has to be visible as traffic that went by untouched, or
        // the summary reads like a stream that stopped fragmenting.
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 2, budget = 1)
        repeat(10) { injector.effectFor(middleFlag) }
        assertEquals(10L, injector.matchingCount)
        assertEquals(1L, injector.injectedCount)
    }

    @Test
    fun `no budget means the whole session, and a negative one is not a budget`() {
        val unlimited = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 2)
        repeat(40) { unlimited.effectFor(middleFlag) }
        assertEquals(20L, unlimited.injectedCount)
        assertFalse(unlimited.budgetSpent)

        // Clamped rather than read as "stop immediately", which would silently turn a mistyped
        // setting into a run that injects nothing and looks like a rate that never took.
        val negative = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 2, budget = -4)
        assertEquals(VideoFaultInjector.UNLIMITED_BUDGET, negative.budget)
        repeat(10) { negative.effectFor(middleFlag) }
        assertEquals(5L, negative.injectedCount)
    }

    @Test
    fun `the summary carries the setting and both counts`() {
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 20)
        repeat(8) { injector.effectFor(middleFlag) }
        val text = injector.describe()
        // The exact shape a five-minute run that injected nothing produced, which is the case this
        // line exists for: eight candidates at one in twenty is zero faults and no defect.
        listOf("DROP_MIDDLE_FRAGMENT", "1-in-20", "8 candidates", "0 injected", "no budget").forEach {
            assertTrue("missing '$it' in: $text", text.contains(it))
        }
    }

    @Test
    fun `the summary says how much of a budget is left`() {
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT, rate = 2, budget = 5)
        repeat(4) { injector.effectFor(middleFlag) }
        assertTrue(injector.describe(), injector.describe().contains("budget 2/5"))
    }

    // --- Which stage a mode belongs to ------------------------------------------------------

    @Test
    fun `each mode belongs to exactly one stage, and only one site applies it`() {
        // The two injection sites - AapVideo.process and the readers - both ask isActiveAt rather
        // than comparing modes, so a mode can never be applied twice or land at neither site. This
        // pins that: for every mode, at most one stage claims it, and OFF is claimed by none.
        for (mode in Mode.entries) {
            val injector = VideoFaultInjector(mode, rate = 2)
            val claims = Stage.entries.count { injector.isActiveAt(it) }
            if (mode == Mode.OFF) {
                assertEquals("OFF must be applied nowhere", 0, claims)
            } else {
                assertEquals("$mode must be applied at exactly one stage", 1, claims)
            }
        }
    }

    @Test
    fun `only the reader mode runs in the reader`() {
        // The distinction the whole reader stage exists for. An assembler-stage drop happens after
        // the framing audit has already counted the fragment, so the audit sees a complete run and
        // the one corruption mode nothing else can see stays invisible. A hardware round measured
        // that cost: 37 and 59 injected middle-fragment faults, zero keyframe requests.
        assertTrue(
            VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT_IN_READER, rate = 2).isActiveAt(Stage.READER)
        )
        for (mode in Mode.entries - Mode.DROP_MIDDLE_FRAGMENT_IN_READER) {
            assertFalse(
                "$mode must not be applied in the reader",
                VideoFaultInjector(mode, rate = 2).isActiveAt(Stage.READER)
            )
        }
    }

    @Test
    fun `the reader mode attacks middle fragments, like the assembler-stage mode it mirrors`() {
        // Same fault, injected one step earlier. If these ever target different flags the pair stops
        // being a controlled comparison, which is the only reason to keep both.
        assertEquals(
            VideoFaultInjector.targetFlag(Mode.DROP_MIDDLE_FRAGMENT),
            VideoFaultInjector.targetFlag(Mode.DROP_MIDDLE_FRAGMENT_IN_READER)
        )
        assertEquals(middleFlag, VideoFaultInjector.targetFlag(Mode.DROP_MIDDLE_FRAGMENT_IN_READER))
    }

    @Test
    fun `the reader mode honours the rate and the budget like every other`() {
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT_IN_READER, rate = 3, budget = 2)
        val effects = (1..12).map { injector.effectFor(middleFlag) }
        assertEquals(
            "two faults at one in three, then the budget stops it",
            listOf(Effect.DROP, Effect.DROP),
            effects.filter { it != Effect.NONE }
        )
        assertTrue(injector.budgetSpent)
        // Candidates keep being counted past the budget, so the summary still says how much of the
        // stream went by untouched - what the recovery half of a bounded run is measured against.
        assertEquals(12L, injector.matchingCount)
    }

    @Test
    fun `the reader mode leaves every other flag alone`() {
        val injector = VideoFaultInjector(Mode.DROP_MIDDLE_FRAGMENT_IN_READER, rate = 2)
        for (flags in listOf(first, lastFlag, single)) {
            repeat(10) { assertEquals(Effect.NONE, injector.effectFor(flags)) }
        }
        assertEquals(0L, injector.injectedCount)
    }
}
