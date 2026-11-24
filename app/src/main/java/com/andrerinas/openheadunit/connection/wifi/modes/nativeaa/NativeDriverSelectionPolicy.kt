package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/**
 * Pure decision policy governing multi-driver / device selection in Native AA Wireless mode.
 *
 * All decisions regarding whether to show the selection dialog, which device to prioritize,
 * and how to resolve auto-connect timeouts are encapsulated here for testability.
 */
object NativeDriverSelectionPolicy {

    enum class Mode(val id: Int) {
        DISABLED(0),
        AUTO(1),
        ALWAYS(2);

        companion object {
            fun fromId(id: Int): Mode = entries.firstOrNull { it.id == id } ?: AUTO
        }
    }

    const val DEFAULT_TIMEOUT_SEC = 10
    const val MIN_TIMEOUT_SEC = 3
    const val MAX_TIMEOUT_SEC = 30

    /** How long past the countdown an on-screen prompt may hold the wake poke off. */
    const val PROMPT_GRACE_MS = 15_000L

    /**
     * How long a cancelled prompt keeps refusing incoming connections.
     *
     * One poke cycle, so a poke already on the wire when the user cancelled cannot pull them into
     * a session, and no longer: a phone dialling us afterwards is the driver asking for one.
     */
    const val CANCEL_REFUSAL_MS = 30_000L

    /**
     * How long a chosen driver's phone is the only one accepted once nothing is waking it.
     *
     * One poke cycle. The phone a switch moved away from can be back on the listeners in a second,
     * while the newly chosen one still has to be woken.
     */
    const val CHOSEN_EXCLUSIVE_MS = 30_000L

    /** How many times a chosen driver's phone is woken before the unit gives up on it. */
    const val CHOSEN_WAKE_ROUNDS = 3

    /**
     * Whether a wake attempt spent one of [CHOSEN_WAKE_ROUNDS].
     *
     * A wake the hands-free guard stood down opened nothing and cost the phone nothing, so counting
     * it burns the budget on the air time it never used.
     */
    fun wakeRoundSpent(outcome: BluetoothWakePolicy.WakeOutcome): Boolean =
        outcome != BluetoothWakePolicy.WakeOutcome.STOOD_DOWN &&
            outcome != BluetoothWakePolicy.WakeOutcome.HELD_LINK

    /**
     * Whether a chosen driver's wake loop takes another pass.
     *
     * Three stood-down rounds are over in about 30 s, while the escalation that answers exactly that
     * stand-down needs [HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS] to mature, so a budget
     * counted in refusals put the escalation out of reach on the single-phone default. Refusals are
     * bounded by [CHOSEN_EXCLUSIVE_MAX_MS] instead, which is the ceiling they already sit under.
     * An automatic pick gives way after one unanswered round, so the wake plan reaches the next phone.
     * A pick the selector did not name stops at the floor while its phone holds a hands-free link
     * ([BluetoothWakePolicy.WakeOutcome.HELD_LINK]); any other stand-down keeps it waking.
     * A null [outcome] means no wake round has run yet, so nothing has stood down.
     */
    fun chosenWakeContinues(
        outcome: BluetoothWakePolicy.WakeOutcome?,
        roundsDialled: Int,
        elapsedMs: Long,
        explicit: Boolean,
        fromPlan: Boolean = false,
    ): Boolean =
        outcome != BluetoothWakePolicy.WakeOutcome.NOT_PAIRED &&
            !(!explicit && outcome == BluetoothWakePolicy.WakeOutcome.DIALLED) &&
            !(fromPlan && outcome == BluetoothWakePolicy.WakeOutcome.HELD_LINK && elapsedMs >= CHOSEN_EXCLUSIVE_MS) &&
            roundsDialled < CHOSEN_WAKE_ROUNDS &&
            elapsedMs < CHOSEN_EXCLUSIVE_MAX_MS

    /**
     * Whether an unanswered chosen wake starts the wake plan: an automatic pick after one round,
     * a pick the selector did not name after its whole budget or at the floor when its phone holds
     * a hands-free link, and a phone the driver named never. A wake with no round run (null) never does.
     */
    fun handsOverToWakePlan(outcome: BluetoothWakePolicy.WakeOutcome?, explicit: Boolean, fromPlan: Boolean): Boolean =
        (outcome == BluetoothWakePolicy.WakeOutcome.DIALLED && (!explicit || fromPlan)) ||
            ((outcome == BluetoothWakePolicy.WakeOutcome.STOOD_DOWN ||
                outcome == BluetoothWakePolicy.WakeOutcome.HELD_LINK) && fromPlan)

    /**
     * The hard ceiling on refusing every other phone while the chosen one is woken.
     *
     * Three 20 s holds and two 15 s gaps is a 90 s wake budget, plus 30 s for the phone to join: a
     * cold single-phone connect measured about 26 s on the rig. Bounded so an unreachable choice
     * cannot deafen the unit for good.
     */
    const val CHOSEN_EXCLUSIVE_MAX_MS = 120_000L

    /**
     * How long the phone a switch moved away from is refused while no driver has been chosen yet.
     *
     * Long enough to read a selector and pick, short enough that an abandoned switch heals itself.
     */
    const val SWITCH_AWAY_REFUSAL_MS = 60_000L

    /**
     * How often a refusal that keeps repeating is said again.
     *
     * A refused phone retries RFCOMM every 150 ms or so, so the first line is the useful one and
     * the rest are noise. Restating with a count keeps a long refusal visible without the flood.
     */
    const val GATE_RESTATE_MS = 60_000L

    /** Whether a refusal already spoken for this phone is due to be said again. */
    fun shouldRestateRefusal(msSinceSpoken: Long): Boolean =
        msSinceSpoken >= GATE_RESTATE_MS

    /**
     * How long the wake poke defers to a prompt that is actually on screen.
     *
     * Bounded on purpose. A unit that starts with nobody in front of it must fall back to waking
     * every paired phone, which is what it did before the prompt existed.
     */
    fun promptDeferralMs(timeoutSec: Int): Long = sanitizeTimeout(timeoutSec) * 1000L + PROMPT_GRACE_MS

    /** What the wake poke loop should do about a driver prompt on this pass. */
    enum class PokeHold { GO, HOLD, EXPIRED }

    /**
     * Whether an on-screen driver prompt still holds the wake poke off.
     *
     * Asked on every pass of the poke loop, never once on entry: a deadline read only when
     * something else calls in measures that caller's cadence instead of itself.
     */
    fun pokeHold(
        promptActive: Boolean,
        targetChosen: Boolean,
        promptAgeMs: Long,
        timeoutSec: Int
    ): PokeHold = when {
        !promptActive || targetChosen -> PokeHold.GO
        promptAgeMs >= promptDeferralMs(timeoutSec) -> PokeHold.EXPIRED
        else -> PokeHold.HOLD
    }

    /** Why a phone dialling in during a driver switch is refused, or that it is not. */
    enum class SwitchGate { ACCEPT, WRONG_PHONE, SWITCHED_AWAY }

    /**
     * Whether a phone dialling in is the driver the user actually chose.
     *
     * Reopening the Android Auto listeners for the phone's return lets the one just switched away
     * from win the race. Both windows are bounded: an unfinished switch must not deafen the unit.
     */
    fun switchGate(
        remoteMac: String,
        chosenMac: String?,
        chosenAgeMs: Long,
        switchedAwayFrom: String?,
        switchAgeMs: Long,
        chosenWakeActive: Boolean,
        chosenExplicit: Boolean,
        chosenWakeUnanswered: Boolean,
        handshakePeerMac: String?
    ): SwitchGate = when {
        remoteMac.isEmpty() -> SwitchGate.ACCEPT
        !chosenMac.isNullOrEmpty() &&
            chosenExclusive(
                chosenAgeMs, chosenWakeActive, chosenExplicit, chosenWakeUnanswered,
                chosenHandshaking = handshakePeerMac.equals(chosenMac, ignoreCase = true)
            ) ->
            if (remoteMac.equals(chosenMac, ignoreCase = true)) SwitchGate.ACCEPT
            else SwitchGate.WRONG_PHONE
        !switchedAwayFrom.isNullOrEmpty() && switchAgeMs < SWITCH_AWAY_REFUSAL_MS &&
            remoteMac.equals(switchedAwayFrom, ignoreCase = true) -> SwitchGate.SWITCHED_AWAY
        else -> SwitchGate.ACCEPT
    }

    /** Longest a lower phone waits for a higher listed phone; a silent higher phone must not block the unit for good. */
    const val LIST_HOLD_MS = 20_000L

    /** How recent a dial counts as "here": a refused phone redials at once or within one 5 s retry delay. */
    const val LIST_DIAL_PRESENT_MS = 10_000L

    /** Why a higher listed phone counts as here. */
    enum class HereBy { LINKED, DIALLED, CONNECTING }

    data class ListHold(val above: String, val by: HereBy)

    /** The listed phones before [remoteMac], case ignored; all of them when it is not listed. */
    fun phonesAbove(remoteMac: String, phonesList: List<String>): List<String> {
        val at = phonesList.indexOfFirst { it.equals(remoteMac, ignoreCase = true) }
        return if (at < 0) phonesList else phonesList.subList(0, at)
    }

    /**
     * Whether a phone dialling in waits for a higher phone of the Phones list that is here.
     *
     * Bounded by [LIST_HOLD_MS] so a silent higher phone cannot keep the unit from connecting; a
     * higher phone's own handshake holds past it. A pick the driver made, and the phone a switch
     * left, are not held, nor is a phone in [ruledOut] (not paired). Null means let it in.
     */
    fun listHoldFor(
        remoteMac: String,
        phonesList: List<String>,
        explicitPickMac: String?,
        switchedAwayFrom: String?,
        linked: Set<String>,
        dialAgeMs: Map<String, Long>,
        handshakePeerMac: String?,
        holdAgeMs: Long?,
        ruledOut: Set<String> = emptySet(),
    ): ListHold? {
        if (remoteMac.isEmpty() || phonesList.isEmpty()) return null
        if (explicitPickMac.equals(remoteMac, ignoreCase = true)) return null
        val above = phonesAbove(remoteMac, phonesList).filter { mac ->
            !mac.equals(switchedAwayFrom, ignoreCase = true) && ruledOut.none { it.equals(mac, ignoreCase = true) }
        }
        above.firstOrNull { it.equals(handshakePeerMac, ignoreCase = true) }
            ?.let { return ListHold(it, HereBy.CONNECTING) }
        if (holdAgeMs != null && holdAgeMs >= LIST_HOLD_MS) return null
        for (mac in above) {
            if (linked.any { it.equals(mac, ignoreCase = true) }) return ListHold(mac, HereBy.LINKED)
            val age = dialAgeMs.entries.firstOrNull { it.key.equals(mac, ignoreCase = true) }?.value
            if (age != null && age < LIST_DIAL_PRESENT_MS) return ListHold(mac, HereBy.DIALLED)
        }
        return null
    }

    /** How long a lower phone waits for a higher one nobody has seen yet: first dials came within 4 s, redials come about every 5 s. */
    const val LIST_GRACE_MS = 5_000L

    /**
     * The phone a lower phone's grace waits for: the first listed phone above it that is not ruled
     * out (woken and silent, or not paired). Null means no grace: no list, the explicit pick, the top phone, or
     * nobody left above. A phone the switch left is not waited for, as in [listHoldFor].
     */
    fun graceFor(
        remoteMac: String,
        phonesList: List<String>,
        explicitPickMac: String?,
        switchedAwayFrom: String?,
        ruledOut: Set<String>,
    ): String? {
        if (remoteMac.isEmpty() || phonesList.isEmpty()) return null
        if (explicitPickMac.equals(remoteMac, ignoreCase = true)) return null
        return phonesAbove(remoteMac, phonesList).firstOrNull { mac ->
            !mac.equals(switchedAwayFrom, ignoreCase = true) && ruledOut.none { it.equals(mac, ignoreCase = true) }
        }
    }

    /** A grace under way: when the phone's first dial was refused, and the phone it waits for. */
    data class Grace(val startedAt: Long, val above: String)

    /**
     * One hold of the Phones list: when it began (0 for none), each phone still held with the phone
     * above it and its own first refused dial ([heldSince]), and the phones let in. [grace] and
     * [graceSpent] hold the phones in or past their grace, [told] those kept off a spent hold's handshake.
     */
    data class ListHoldState(
        val startedAt: Long = 0L,
        val waiting: Map<String, String> = emptyMap(),
        val letIn: Set<String> = emptySet(),
        val grace: Map<String, Grace> = emptyMap(),
        val graceSpent: Set<String> = emptySet(),
        val heldSince: Map<String, Long> = emptyMap(),
        val told: Set<String> = emptySet(),
    ) {
        /** Whether [mac] is refused by the hold or its grace, and has had its start line but not its end line. */
        fun waits(mac: String): Boolean =
            mac.uppercase().let { waiting.containsKey(it) || grace.containsKey(it) || it in told }

        /** How long [mac] has been held, for [listHoldFor]; null when it is not held. */
        fun holdAgeMs(mac: String, nowMs: Long): Long? = heldSince[mac.uppercase()]?.let { nowMs - it }
    }

    /** What a dial does to the hold. START, END_* and GRACE_START/GRACE_END say their line once per held phone; END_PICKED says none. */
    enum class HoldStep { START, KEEP, END_CEILING, END_EARLY, END_PICKED, NONE, GRACE_START, GRACE_KEEP, GRACE_END }

    /** [above] is the phone the dialling phone waits for, on START, KEEP, END_* and GRACE_*. */
    data class HoldTurn(val step: HoldStep, val state: ListHoldState, val above: String? = null)

    /**
     * What a dial with [hold] (from [listHoldFor]) does to [state]; a step that changes nothing
     * returns [state] itself. Only a held phone's own dial ends its hold, so the higher phone cannot
     * restart the ceiling. A spent hold stays spent until the manager clears it, but still keeps a
     * lower phone off the higher phone's handshake. With no hold, [graceAbove] (from [graceFor])
     * refuses a phone once for [LIST_GRACE_MS]. Each held phone's ceiling counts from its own first
     * refused dial; [picked] (the dialling phone is the explicit pick) ends its wait with END_PICKED.
     */
    fun listHoldStep(
        state: ListHoldState,
        remoteMac: String,
        hold: ListHold?,
        nowMs: Long,
        graceAbove: String? = null,
        picked: Boolean = false,
    ): HoldTurn {
        val key = remoteMac.uppercase()
        val waitsFor = state.waiting[key]
        val grace = state.grace[key]
        if (hold != null) {
            val used = if (grace == null) state else
                state.copy(grace = state.grace - key, graceSpent = state.graceSpent + key)
            // A grace still open after two graces means the phone stopped dialling: its wait starts now.
            val firstAt = grace?.startedAt?.takeIf { nowMs - it < 2 * LIST_GRACE_MS } ?: nowMs
            return when {
                waitsFor != null -> HoldTurn(HoldStep.KEEP, state, waitsFor)
                state.startedAt == 0L || (state.waiting.isNotEmpty() && key !in state.letIn) -> HoldTurn(
                    HoldStep.START,
                    used.copy(
                        startedAt = if (state.startedAt == 0L) firstAt else state.startedAt,
                        waiting = state.waiting + (key to hold.above),
                        heldSince = state.heldSince + (key to firstAt)
                    ),
                    hold.above
                )
                // A spent hold keeps a phone off the handshake and says so once per phone.
                hold.by == HereBy.CONNECTING ->
                    HoldTurn(HoldStep.KEEP, if (key in used.told) used else used.copy(told = used.told + key), hold.above)
                else -> HoldTurn(HoldStep.NONE, if (key in used.told) used.copy(told = used.told - key) else used, hold.above)
            }
        }
        if (waitsFor == null) {
            // Out of the handshake's way, a told phone is let in as any other.
            val base = if (key in state.told) state.copy(told = state.told - key) else state
            return when {
                grace != null && picked -> HoldTurn(
                    HoldStep.END_PICKED, base.copy(grace = base.grace - key, graceSpent = base.graceSpent + key), grace.above
                )
                grace != null && nowMs - grace.startedAt < LIST_GRACE_MS -> HoldTurn(HoldStep.GRACE_KEEP, base, grace.above)
                grace != null -> HoldTurn(
                    HoldStep.GRACE_END, base.copy(grace = base.grace - key, graceSpent = base.graceSpent + key), grace.above
                )
                graceAbove != null && key !in base.graceSpent && key !in base.letIn -> HoldTurn(
                    HoldStep.GRACE_START, base.copy(grace = base.grace + (key to Grace(nowMs, graceAbove))), graceAbove
                )
                else -> HoldTurn(HoldStep.NONE, base)
            }
        }
        val step = when {
            picked -> HoldStep.END_PICKED
            nowMs - (state.heldSince[key] ?: state.startedAt) >= LIST_HOLD_MS -> HoldStep.END_CEILING
            else -> HoldStep.END_EARLY
        }
        return HoldTurn(
            step, state.copy(waiting = state.waiting - key, letIn = state.letIn + key, heldSince = state.heldSince - key), waitsFor
        )
    }

    /**
     * Whether the chosen driver is still the only phone the accept gate lets in.
     *
     * Only a pick the user made locks it. A pick the phone never answered lapses after the floor,
     * but the chosen phone's own handshake keeps the lock; the ceiling ends every lock.
     */
    fun chosenExclusive(
        chosenAgeMs: Long,
        wakeActive: Boolean,
        explicit: Boolean,
        wakeUnanswered: Boolean,
        chosenHandshaking: Boolean
    ): Boolean =
        explicit && chosenAgeMs < CHOSEN_EXCLUSIVE_MAX_MS &&
            (chosenAgeMs < CHOSEN_EXCLUSIVE_MS || chosenHandshaking || (wakeActive && !wakeUnanswered))

    /**
     * Whether an explicit pick has lapsed after this wake round. A round the phone never answered
     * says it may be off; an answer says it is there, so the lock holds again. A held hands-free link
     * lapses only a pick the selector did not name, whose phone can hold that link and never start
     * Android Auto. Any other stand-down leaves the lapse as it was.
     */
    fun pickLapsedAfterRound(
        outcome: BluetoothWakePolicy.WakeOutcome,
        answeredSincePick: Boolean,
        lapsed: Boolean,
        fromPlan: Boolean = false
    ): Boolean = when (outcome) {
        BluetoothWakePolicy.WakeOutcome.ANSWERED -> false
        BluetoothWakePolicy.WakeOutcome.DIALLED -> lapsed || !answeredSincePick
        BluetoothWakePolicy.WakeOutcome.HELD_LINK -> if (fromPlan) lapsed || !answeredSincePick else lapsed
        else -> lapsed
    }

    /**
     * Determines whether the driver/device selection dialog should be displayed.
     *
     * @param mode User preference mode (DISABLED, AUTO, ALWAYS)
     * @param pairedCount Total number of paired phone candidates
     * @param connectedCount Number of devices currently connected via Bluetooth
     * @param knownCount Bonded phones the wake plan knows (listed, learned or linked); in AUTO with several bonded, none asks and exactly one skips the prompt
     * @param hasHistory Whether a preferred or previously connected device is recorded
     */
    fun shouldShowSelector(
        mode: Mode,
        pairedCount: Int,
        connectedCount: Int,
        knownCount: Int,
        hasHistory: Boolean = true
    ): Boolean {
        if (mode == Mode.DISABLED) return false
        if (pairedCount == 0) return false

        return when (mode) {
            Mode.ALWAYS -> pairedCount > 1
            Mode.AUTO -> when {
                // One connected phone names the driver unambiguously.
                connectedCount == 1 -> false
                // A single bonded phone is the driver even before the wake plan knows it.
                pairedCount <= 1 -> false
                // Several phones, none known and none connected: ask, with no countdown.
                connectedCount == 0 && knownCount == 0 -> true
                // One known phone and none connected: nothing to ask.
                connectedCount == 0 && knownCount == 1 -> false
                else -> true
            }
            Mode.DISABLED -> false
        }
    }

    /**
     * Whether a wake attempt on this phone may claim the full screen straight away.
     *
     * A phone with no live Bluetooth link has to be woken first and may never answer, so the
     * full-screen "Android Auto is starting" would be a claim nothing backs for the whole watchdog.
     */
    fun connectUiIsImmediate(targetMac: String, connectedMacs: Collection<String>): Boolean =
        targetMac.isNotEmpty() && connectedMacs.any { it.equals(targetMac, ignoreCase = true) }

    /** What the selector may say about a phone's Bluetooth link. */
    enum class DriverPresence {
        /** A link is up, so this phone can answer now. */
        CONNECTED,

        /** No link, so this phone has to be woken and may not answer. */
        DISCONNECTED,

        /** The read is unavailable, so the selector says nothing rather than guessing. */
        UNKNOWN
    }

    /**
     * What to tell the driver about [targetMac] before they pick it.
     *
     * [readable] is false where the connection read cannot answer at all, below API 21 among others.
     * Calling every phone disconnected there would be worse than saying nothing, because the whole
     * list would read as unreachable while the phones are sitting connected.
     */
    fun presenceOf(
        targetMac: String,
        connectedMacs: Collection<String>,
        readable: Boolean
    ): DriverPresence = when {
        !readable || targetMac.isEmpty() -> DriverPresence.UNKNOWN
        connectedMacs.any { it.equals(targetMac, ignoreCase = true) } -> DriverPresence.CONNECTED
        else -> DriverPresence.DISCONNECTED
    }

    /**
     * Whether a live hands-free link belongs to some phone other than [targetMac].
     *
     * A switch leaves the outgoing phone's link up, and the wake guard reads that link adapter-wide,
     * so without this it stands down the poke aimed at the incoming phone and strands the switch.
     * Bounded by [CHOSEN_EXCLUSIVE_MAX_MS] so an abandoned switch heals itself.
     */
    fun handsFreeLinkIsAnotherPhones(
        targetMac: String,
        switchedAwayFrom: String?,
        switchAgeMs: Long
    ): Boolean {
        if (switchedAwayFrom.isNullOrEmpty() || targetMac.isEmpty()) return false
        if (switchAgeMs < 0 || switchAgeMs >= CHOSEN_EXCLUSIVE_MAX_MS) return false
        return !targetMac.equals(switchedAwayFrom, ignoreCase = true)
    }

    /**
     * Constrains timeout to [MIN_TIMEOUT_SEC]..[MAX_TIMEOUT_SEC].
     */
    fun sanitizeTimeout(timeoutSec: Int): Int {
        return timeoutSec.coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
    }
}
