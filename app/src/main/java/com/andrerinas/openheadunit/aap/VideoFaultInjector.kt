package com.andrerinas.openheadunit.aap

/**
 * Deliberately breaks the video fragment stream, so the reassembler's failure paths can be exercised
 * on a head unit that is working correctly.
 *
 * The artifact reports this exists for - a melting or smearing picture - come from units we do not
 * have, and the conditions that cause it are properties of the link rather than of the app: a
 * fragment that never arrives, or a first fragment whose payload does not start where the protocol
 * says it should. A healthy rig produces none of them, which is why three rounds of hardware testing
 * on the decoder measured `dropped=0` and never reproduced the bug at all.
 *
 * This turns that around. Injecting the exact loss pattern reproduces each failure mode on any unit,
 * deterministically, so "the fix works" becomes something that can be measured rather than inferred
 * from a reporter's next drive.
 *
 * Deterministic on purpose: every Nth matching message rather than a random draw, so two builds
 * given the same stream see the same faults and an A/B comparison means something.
 *
 * Off unless explicitly enabled. When it is on, every injected fault is logged, so a log that has
 * been captured with this left on can never be mistaken for a log of a real fault.
 *
 * ### Where a mode is injected decides what can see it
 *
 * [Stage.ASSEMBLER] modes run in [AapVideo.process], which is **downstream of
 * [FragmentedMessageAudit]** - that check runs in the readers, on the header, before decryption
 * ([AapReadSingleMessage] and [AapReadMultipleMessages] both call `auditFragment` there). Every
 * message such a mode pretends never arrived has already been counted by the audit as arriving, so
 * no assembler-stage mode can produce a framing-audit outcome. Hardware measured it: **449 faults
 * injected across the three drop modes, and not one `AapRead:` line attributable to any of them.**
 * Only [VideoFragmentAssembler]'s counters - `headless=`, `orphan=`, `truncated=` - respond to them.
 *
 * [Stage.READER] modes exist because that made one fault untestable. A middle fragment missing from a
 * run is invisible to [VideoFragmentAssembler] by construction, so the audit is the only thing that
 * can see it - and an assembler-stage drop cannot exercise the audit, because the audit has already
 * counted the fragment. A reader-stage mode drops the message before `auditFragment` is called, so
 * the run really is short of the bytes its first fragment declared. A hardware round measured the
 * cost of not having one: 37 and 59 injected middle-fragment faults produced zero keyframe requests
 * and zero escalation activity, because nothing downstream of the reader could tell anything was
 * missing.
 *
 * What that reproduces is a fragment that **never reaches the reassembler**, not one lost in transit.
 * Nothing can be silently lost in transit here: AAP runs TLS over TCP, and a record that goes missing
 * takes the session with it rather than leaving a hole - which a round found out by writing this mode
 * to skip the decrypt as well as the delivery. The distinction matters because the effect on
 * everything downstream is identical, and the cause is not.
 *
 * A mode belongs to exactly one stage and [isActiveAt] is how each site asks. Neither call site
 * re-derives the condition - two that did drifted apart once already, elsewhere in this package.
 */
class VideoFaultInjector(private val mode: Mode, rate: Int, budget: Int = UNLIMITED_BUDGET) {

    /** Where in the receive path a mode is applied, which decides what can observe the fault. */
    enum class Stage {
        /** In [AapVideo.process], after the reader has framed and audited the message. */
        ASSEMBLER,

        /** In the reader, before `auditFragment` counts the fragment - a fragment that never was. */
        READER,
    }

    /** What the injector does to the stream. */
    enum class Mode(val value: Int, val stage: Stage) {
        /** Nothing. The only mode a user should ever be in. */
        OFF(0, Stage.ASSEMBLER),

        /**
         * Swallow first fragments. The 8s and 10 behind each one arrive with no run open, so expect
         * `orphan=` on the reassembly summary. Nothing from the framing audit - see the note above.
         */
        DROP_FIRST_FRAGMENT(1, Stage.ASSEMBLER),

        /**
         * Swallow middle fragments.
         *
         * The interesting one: the run still looks intact to [VideoFragmentAssembler] - a first,
         * some middles, a last, in order - so the frame is assembled with a hole in it and decoded
         * as though it were whole, and the reassembly summary stays at zero.
         *
         * **Nothing reports it, and that is the point of keeping this mode.** [FragmentedMessageAudit]
         * is the check that could, but it sits upstream of this stage and has already counted the
         * fragment this mode discards. So this is a test of the *decoder's* tolerance of a holed
         * access unit and nothing else - and hardware found it has none: at one fault in three the
         * decoder stopped emitting frames entirely, burned its four-restart budget in 33 seconds and
         * never recovered.
         *
         * Use [DROP_MIDDLE_FRAGMENT_IN_READER] to exercise the detection instead. The two are worth
         * keeping apart: this one answers "what does the decoder do with a hole", that one answers
         * "does anything notice the hole", and a round that confuses them measures neither.
         */
        DROP_MIDDLE_FRAGMENT(2, Stage.ASSEMBLER),

        /**
         * Swallow last fragments. The run stays open until the next 9 or 11, so expect `truncated=`
         * on the reassembly summary. Nothing from the framing audit - see the note above.
         */
        DROP_LAST_FRAGMENT(3, Stage.ASSEMBLER),

        /**
         * Present a first fragment as having no start code at either offset, which is the exact
         * shape of the case that used to be assembled headless and silently. The bytes are not
         * touched - only what the reassembler is told about them - because the buffer is shared.
         *
         * Expect `headless=` on the reassembly summary. Nothing from the framing audit - and here
         * the run genuinely is complete, every byte having arrived, so this is the one mode where
         * that silence would be correct even if the injector could reach it.
         */
        HIDE_START_CODE(4, Stage.ASSEMBLER),

        /**
         * Swallow middle fragments **in the reader**, before the framing audit counts them.
         *
         * The same loss as [DROP_MIDDLE_FRAGMENT] injected one step earlier, and the step is the
         * whole difference: here the run really is short of the bytes its first fragment declared,
         * so [FragmentedMessageAudit] sees a delta no fragment count explains and reports
         * `DELTA_CHANGED`. That is the only signal in the app for a middle fragment missing from a
         * run, and this is the only way to produce it on a healthy rig.
         *
         * Everything downstream sees exactly what a holed access unit looks like: the reassembler
         * still finds a first, some middles and a last in order, and still hands the decoder a frame
         * with a hole in it. So this mode exercises the detection *and* the damage together.
         *
         * The message is dropped after it has been decrypted, never before - see
         * `AapRead.Base.shouldDropForFaultInjection` for the session this mode killed by getting
         * that backwards.
         */
        DROP_MIDDLE_FRAGMENT_IN_READER(5, Stage.READER);

        companion object {
            fun fromInt(value: Int): Mode? = entries.firstOrNull { it.value == value }
        }
    }

    /** What the caller should do with the message it just asked about. */
    enum class Effect {
        /** Handle it normally. */
        NONE,

        /** Behave as though it never arrived. */
        DROP,

        /** Handle it, but as a fragment whose payload starts at neither known offset. */
        HIDE_START_CODE,
    }

    /** Faults are applied to one in this many matching messages. */
    val rate: Int = rate.coerceIn(MIN_RATE, MAX_RATE)

    /**
     * How many faults this injector will inject before it stops, or [UNLIMITED_BUDGET] for no limit.
     *
     * Continuous injection measures one thing: that a stream still being broken stays broken. It
     * cannot measure the thing that matters more, which is whether the picture comes back once the
     * loss stops - and every recovery lever in the app is bounded per session, so a run with no end
     * to the faults spends them all and ends wedged whatever the code does. A budget puts both in
     * one capture: the damage, then the repair, with the moment between them in the log.
     */
    val budget: Int = budget.coerceAtLeast(UNLIMITED_BUDGET)

    private var matching = 0L
    private var injected = 0L

    /** How many faults have been injected so far, for the log. */
    val injectedCount: Long get() = injected

    /**
     * Whether the budget is spent and the stream is being left alone from here.
     *
     * The caller says so once, and that line is what a recovery measurement is timed from.
     */
    val budgetSpent: Boolean get() = budget != UNLIMITED_BUDGET && injected >= budget

    /**
     * How many messages the current mode has targeted so far - the denominator [injectedCount] is a
     * share of, and the number that says whether a rate is doing anything.
     */
    val matchingCount: Long get() = matching

    /**
     * One line saying what the injector is set to and what it has actually managed to do.
     *
     * Worth printing periodically rather than only per fault, because the interesting failure is the
     * one where nothing is injected: [rate] counts the messages carrying the flag this mode attacks,
     * and how often a frame fragments at all is a property of what the phone happens to be
     * projecting. A five-minute run at one in twenty produced zero faults on a rig where an earlier
     * run at one in three produced ten in ninety seconds - same code, same setting, different
     * screen. Without the candidate count that reads as "the setting did not take" rather than "the
     * stream did not fragment", and the only way to tell was to read this file.
     */
    fun describe(): String {
        val cap = if (budget == UNLIMITED_BUDGET) "no budget" else "budget $injected/$budget"
        return "$mode 1-in-$rate, $matching candidates seen, $injected injected, $cap"
    }

    /** Whether this injector will ever do anything. */
    val isActive: Boolean get() = mode != Mode.OFF

    /**
     * Whether this injector acts at [stage].
     *
     * Both injection sites ask this rather than comparing modes themselves, so a mode can never be
     * applied twice - once in the reader and again in the assembler - or added to one site and
     * forgotten at the other.
     */
    fun isActiveAt(stage: Stage): Boolean = isActive && mode.stage == stage

    /**
     * Decides what to do with a message carrying [flags].
     *
     * Counts only the messages the current mode targets, so the rate means "one in N of the flag we
     * are attacking" rather than one in N of all video traffic - at three fragments per frame the
     * two differ by a factor of three, and the brief has to be able to say how often a fault lands.
     */
    fun effectFor(flags: Int): Effect {
        val target = targetFlag(mode) ?: return Effect.NONE
        if (flags != target) return Effect.NONE
        // Counted even once the budget is spent, so the summary keeps saying how much of the stream
        // went by untouched - which is what the recovery half of a bounded run is measured against.
        matching++
        if (budgetSpent) return Effect.NONE
        if (matching % rate != 0L) return Effect.NONE
        injected++
        return when (mode) {
            Mode.HIDE_START_CODE -> Effect.HIDE_START_CODE
            Mode.OFF -> Effect.NONE
            else -> Effect.DROP
        }
    }

    companion object {
        /** How often [describe] is worth repeating while a mode is active. */
        const val SUMMARY_INTERVAL_MS = 15_000L

        /** No limit on how many faults are injected: the injector runs for the whole session. */
        const val UNLIMITED_BUDGET = 0

        /** One in one would break every frame and never reach a picture at all. */
        const val MIN_RATE = 2

        const val MAX_RATE = 100000

        /**
         * One in 300, which at the ~50 messages per second a healthy link carries is a fault every
         * few seconds - often enough to measure in a five-minute run, rare enough that the picture
         * in between is a fair sample of normal behaviour.
         */
        const val DEFAULT_RATE = 300

        /** Which fragment flag a mode attacks, or null if it attacks nothing. */
        fun targetFlag(mode: Mode): Int? = when (mode) {
            Mode.OFF -> null
            Mode.DROP_FIRST_FRAGMENT, Mode.HIDE_START_CODE -> VideoFragmentAssembler.FLAG_FIRST
            Mode.DROP_MIDDLE_FRAGMENT,
            Mode.DROP_MIDDLE_FRAGMENT_IN_READER -> VideoFragmentAssembler.FLAG_MIDDLE
            Mode.DROP_LAST_FRAGMENT -> VideoFragmentAssembler.FLAG_LAST
        }
    }
}
