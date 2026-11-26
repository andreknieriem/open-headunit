package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.DriverPresence
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.HereBy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.HoldStep
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.HoldTurn
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.ListHold
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.ListHoldState
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.Mode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.PokeHold
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeDriverSelectionPolicy.SwitchGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeDriverSelectionPolicyTest {

    @Test
    fun `disabled mode never shows selector`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 0, knownCount = 0, connectedCount = 0))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 1, knownCount = 1, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.DISABLED, pairedCount = 2, knownCount = 2, connectedCount = 2))
    }

    @Test
    fun `one phone in scope, or none paired, never shows the selector in any mode`() {
        for (mode in Mode.entries) {
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 0, knownCount = 0, connectedCount = 0))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, knownCount = 1, connectedCount = 1))
            assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(mode, pairedCount = 1, knownCount = 1, connectedCount = 0))
        }
    }

    @Test
    fun `AUTO with one bonded phone that nothing knows connects without asking`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, connectedCount = 0, knownCount = 0))
    }

    @Test
    fun `ALWAYS with one phone and an empty scope stays silent`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 1, connectedCount = 0, knownCount = 0))
    }

    @Test
    fun `no paired phone never asks, whatever the scope`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 0, connectedCount = 0, knownCount = 0))
    }

    @Test
    fun `always mode shows selector whenever 2 or more devices are paired`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, knownCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, knownCount = 2, connectedCount = 1))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, knownCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 5, knownCount = 5, connectedCount = 1))
    }

    @Test
    fun `auto mode skips selector if exactly one device is confirmed connected`() {
        // Solo driver entering car with their phone already connected to BT: zero intrusion
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 1))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 4, knownCount = 4, connectedCount = 1))
    }

    @Test
    fun `auto mode shows selector if 2 or more devices are connected`() {
        // Both drivers enter car together: conflict prompt
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 2))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, knownCount = 3, connectedCount = 2))
    }

    @Test
    fun `auto mode shows selector if 0 devices are confirmed connected but multiple paired`() {
        // Connection state unknown (e.g. phones not yet connected to BT stack)
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 0))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, knownCount = 3, connectedCount = 0))
    }

    @Test
    fun `first start without history and multiple devices shows selector`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 0, hasHistory = false))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 3, knownCount = 3, connectedCount = 2, hasHistory = false))
    }

    @Test
    fun `single device never shows selector even without history`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, knownCount = 1, connectedCount = 1, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 1, knownCount = 1, connectedCount = 0, hasHistory = false))
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 1, hasHistory = false))
    }

    @Test
    fun `sanitizeTimeout clamps values between 3 and 30 seconds`() {
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-10))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(-1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(0))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(1))
        assertEquals(3, NativeDriverSelectionPolicy.sanitizeTimeout(3))
        assertEquals(10, NativeDriverSelectionPolicy.sanitizeTimeout(10))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(30))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(31))
        assertEquals(30, NativeDriverSelectionPolicy.sanitizeTimeout(60))
    }

    @Test
    fun `prompt deferral is the countdown plus a fixed grace`() {
        assertEquals(
            10 * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS,
            NativeDriverSelectionPolicy.promptDeferralMs(10)
        )
        assertEquals(
            30 * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS,
            NativeDriverSelectionPolicy.promptDeferralMs(30)
        )
    }

    @Test
    fun `prompt deferral clamps its timeout like sanitizeTimeout does`() {
        // A stored timeout of 0 must not mean "defer for the grace alone", nor 3600 mean an hour:
        // this bounds how long a unit with nobody in front of it can go without a wake poke.
        assertEquals(
            NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MIN_TIMEOUT_SEC),
            NativeDriverSelectionPolicy.promptDeferralMs(0)
        )
        assertEquals(
            NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC),
            NativeDriverSelectionPolicy.promptDeferralMs(3600)
        )
    }

    @Test
    fun `prompt deferral is always bounded and never zero`() {
        for (timeout in -5..40) {
            val deferral = NativeDriverSelectionPolicy.promptDeferralMs(timeout)
            assertTrue(deferral >= NativeDriverSelectionPolicy.MIN_TIMEOUT_SEC * 1000L)
            assertTrue(deferral <= NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC * 1000L + NativeDriverSelectionPolicy.PROMPT_GRACE_MS)
        }
    }

    @Test
    fun `a cancelled prompt refuses for less than a whole prompt window`() {
        // The refusal only has to outlast a poke that was already on the wire when the user
        // cancelled. Longer than the prompt itself and a cancel starts to look permanent again.
        assertTrue(NativeDriverSelectionPolicy.CANCEL_REFUSAL_MS > 0L)
        assertTrue(
            NativeDriverSelectionPolicy.CANCEL_REFUSAL_MS <
                NativeDriverSelectionPolicy.promptDeferralMs(NativeDriverSelectionPolicy.MAX_TIMEOUT_SEC)
        )
    }

    @Test
    fun `no prompt on screen never holds the wake poke`() {
        assertEquals(
            PokeHold.GO,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = false, targetChosen = false, promptAgeMs = 0L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `a chosen driver ends the hold whatever the clock says`() {
        assertEquals(
            PokeHold.GO,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = true, targetChosen = true, promptAgeMs = 0L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `a fresh prompt holds the wake poke`() {
        assertEquals(
            PokeHold.HOLD,
            NativeDriverSelectionPolicy.pokeHold(
                promptActive = true, targetChosen = false, promptAgeMs = 1_000L, timeoutSec = 10
            )
        )
    }

    @Test
    fun `an unanswered prompt expires exactly on its own deadline`() {
        val deadline = NativeDriverSelectionPolicy.promptDeferralMs(10)
        assertEquals(
            PokeHold.HOLD,
            NativeDriverSelectionPolicy.pokeHold(true, false, deadline - 1L, 10)
        )
        assertEquals(
            PokeHold.EXPIRED,
            NativeDriverSelectionPolicy.pokeHold(true, false, deadline, 10)
        )
    }

    @Test
    fun `the hold deadline moves with the timeout setting`() {
        // The whole point of the setting. A hold that expires at the same moment for 10 s and 30 s
        // is reading a cadence somewhere else, not this value.
        val age = NativeDriverSelectionPolicy.promptDeferralMs(10)
        assertEquals(PokeHold.EXPIRED, NativeDriverSelectionPolicy.pokeHold(true, false, age, 10))
        assertEquals(PokeHold.HOLD, NativeDriverSelectionPolicy.pokeHold(true, false, age, 30))
    }

    @Test
    fun `a chosen driver is the only phone accepted while the window stands`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", "AA:BB", 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
        assertEquals(
            SwitchGate.WRONG_PHONE,
            NativeDriverSelectionPolicy.switchGate("cc:dd", "AA:BB", 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an unfinished choice stops refusing once its window passes`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the phone a switch moved away from waits while nobody is chosen`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            NativeDriverSelectionPolicy.switchGate("aa:bb", null, 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("cc:dd", null, 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an abandoned switch heals itself`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", null, 0L, "AA:BB", NativeDriverSelectionPolicy.SWITCH_AWAY_REFUSAL_MS, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `a choice outranks the phone the switch moved away from`() {
        // Picking the same phone again is allowed: the switch is what the choice answers.
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", "AA:BB", 0L, "AA:BB", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `an unreadable remote address is never refused on a guess`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("", "AA:BB", 0L, "CC:DD", 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `no switch and no choice accepts everything`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate("aa:bb", null, 0L, null, 0L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `a chosen driver stays exclusive while its wake is still running`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
        assertTrue(NativeDriverSelectionPolicy.chosenExclusive(90_000L, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false))
    }

    @Test
    fun `a chosen driver stops being exclusive when nothing is waking it`() {
        assertTrue(NativeDriverSelectionPolicy.chosenExclusive(29_999L, wakeActive = false, explicit = true, wakeUnanswered = false, chosenHandshaking = false))
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MS, wakeActive = false, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
    }

    @Test
    fun `an unreachable choice cannot hold the gate for good`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false)
        )
    }

    @Test
    fun `the wrong phone waits while the explicit pick is woken and has not missed a round`() {
        assertEquals(
            SwitchGate.WRONG_PHONE,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", 60_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the chosen phone is accepted late in its own wake`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "AA:BB", 90_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the wrong phone is let in once the wake budget runs out`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "cc:dd", "AA:BB", NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, null, 0L,
                chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the phone a switch left keeps waiting after the choice stops being exclusive`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "CC:DD", 40_000L, "AA:BB", 40_000L, chosenWakeActive = false, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `picking back the phone the switch left accepts it`() {
        assertEquals(
            SwitchGate.ACCEPT,
            NativeDriverSelectionPolicy.switchGate(
                "aa:bb", "AA:BB", 1_000L, "AA:BB", 5_000L, chosenWakeActive = true, chosenExplicit = true, chosenWakeUnanswered = false, handshakePeerMac = null)
        )
    }

    @Test
    fun `the wake budget is three rounds`() {
        assertEquals(3, NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS)
    }

    @Test
    fun `a stood-down wake opened nothing, so it spends no round`() {
        assertFalse(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN))
        assertFalse(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.HELD_LINK))
        assertTrue(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.ANSWERED))
        assertTrue(NativeDriverSelectionPolicy.wakeRoundSpent(BluetoothWakePolicy.WakeOutcome.DIALLED))
    }

    @Test
    fun `refusals keep the loop alive long enough for the escalation to mature`() {
        // The whole point: three refusals are over in about 30 s, and the escalation needs 90 s.
        var elapsed = 0L
        var passes = 0
        while (NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, 0, elapsed, explicit = true)) {
            passes++
            elapsed += 15_000L
        }
        assertTrue("refusals must outlast ${HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS}ms",
            elapsed > HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS)
        assertTrue(passes > NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS)
    }

    @Test
    fun `dialled rounds still spend the budget, and the ceiling still ends an endless refusal`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.DIALLED, NativeDriverSelectionPolicy.CHOSEN_WAKE_ROUNDS, 0L, explicit = true
            )
        )
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, 0,
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, explicit = true
            )
        )
    }

    @Test
    fun `an unpaired phone ends the loop rather than being waited out`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, 0, 0L, explicit = true
            )
        )
    }

    @Test
    fun `a target with a live bluetooth link may take the full screen`() {
        assertTrue(
            NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", listOf("CC:DD", "AA:BB"))
        )
    }

    @Test
    fun `the live link is matched case insensitively`() {
        assertTrue(
            NativeDriverSelectionPolicy.connectUiIsImmediate("aa:bb", listOf("AA:BB"))
        )
    }

    @Test
    fun `a target that is not connected defers the full screen`() {
        assertFalse(
            NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", listOf("CC:DD"))
        )
    }

    @Test
    fun `nothing connected defers the full screen`() {
        assertFalse(NativeDriverSelectionPolicy.connectUiIsImmediate("AA:BB", emptyList()))
    }

    @Test
    fun `an empty target defers the full screen`() {
        assertFalse(NativeDriverSelectionPolicy.connectUiIsImmediate("", listOf("AA:BB")))
    }

    @Test
    fun `mode id mapping round trip works as expected`() {
        assertEquals(Mode.DISABLED, Mode.fromId(0))
        assertEquals(Mode.AUTO, Mode.fromId(1))
        assertEquals(Mode.ALWAYS, Mode.fromId(2))
        assertEquals(Mode.AUTO, Mode.fromId(-1))
        assertEquals(Mode.AUTO, Mode.fromId(99))
    }

    // --- whose hands-free link is it ---

    private val OUTGOING = "AA:BB:CC:DD:EE:FF"
    private val INCOMING = "11:22:33:44:55:66"

    /** The round-5 failure: the outgoing phone's link stood the incoming phone's wake down. */
    @Test
    fun `during a switch the link belongs to the phone being left`() {
        assertTrue(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, 5_000L))
    }

    @Test
    fun `the phone being left is not another phone`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(OUTGOING, OUTGOING, 5_000L))
    }

    @Test
    fun `a stored address is matched whatever its case`() {
        assertFalse(
            NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(
                OUTGOING.lowercase(), OUTGOING.uppercase(), 5_000L
            )
        )
    }

    @Test
    fun `with no switch underway the link is nobody else's`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, null, 5_000L))
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, "", 5_000L))
    }

    /** An abandoned switch must not lift the guard for good. */
    @Test
    fun `the override expires with the chosen driver's own ceiling`() {
        val ceiling = NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS
        assertTrue(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, ceiling - 1))
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, ceiling))
    }

    /** The stamp is zero until a switch happens, so an unset clock must not read as a fresh switch. */
    @Test
    fun `a nonsense age never lifts the guard`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(INCOMING, OUTGOING, -1L))
    }

    @Test
    fun `an empty target is never another phone`() {
        assertFalse(NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones("", OUTGOING, 5_000L))
    }

    // --- what the selector tells the driver about a phone ---

    @Test
    fun `a phone with a live link reads as connected`() {
        assertEquals(
            DriverPresence.CONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(OUTGOING, INCOMING), true)
        )
    }

    @Test
    fun `a phone that is not in the connected set reads as disconnected`() {
        assertEquals(
            DriverPresence.DISCONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(OUTGOING), true)
        )
    }

    @Test
    fun `presence matches a stored address whatever its case`() {
        assertEquals(
            DriverPresence.CONNECTED,
            NativeDriverSelectionPolicy.presenceOf(INCOMING.lowercase(), listOf(INCOMING.uppercase()), true)
        )
    }

    /**
     * The read is unavailable below API 21 and on adapters that refuse it, and there every phone
     * would otherwise be labelled absent while sitting connected. Say nothing instead.
     */
    @Test
    fun `an unreadable stack reports nothing rather than absence`() {
        assertEquals(
            DriverPresence.UNKNOWN,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, emptyList(), false)
        )
        assertEquals(
            DriverPresence.UNKNOWN,
            NativeDriverSelectionPolicy.presenceOf(INCOMING, listOf(INCOMING), false)
        )
    }

    @Test
    fun `an empty address is never claimed to be present or absent`() {
        assertEquals(DriverPresence.UNKNOWN, NativeDriverSelectionPolicy.presenceOf("", listOf(INCOMING), true))
    }

    /** The row label and the connect UI must never disagree about the same phone. */
    @Test
    fun `presence agrees with the connect UI decision`() {
        for (macs in listOf(emptyList<String>(), listOf(OUTGOING), listOf(INCOMING))) {
            val immediate = NativeDriverSelectionPolicy.connectUiIsImmediate(INCOMING, macs)
            val connected = NativeDriverSelectionPolicy.presenceOf(INCOMING, macs, true) == DriverPresence.CONNECTED
            assertEquals(immediate, connected)
        }
    }

    // --- how often a repeated refusal is said again ---

    @Test
    fun `a refusal just spoken is not repeated`() {
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(0L))
    }

    @Test
    fun `a refusal is not repeated while it is still fresh`() {
        assertFalse(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS - 1)
        )
    }

    @Test
    fun `a refusal is repeated once the gap has passed`() {
        assertTrue(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS)
        )
        assertTrue(
            NativeDriverSelectionPolicy.shouldRestateRefusal(NativeDriverSelectionPolicy.GATE_RESTATE_MS * 10)
        )
    }

    /** A clock that runs backwards must not turn the restate into a second flood. */
    @Test
    fun `a negative age never restates`() {
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(-1L))
        assertFalse(NativeDriverSelectionPolicy.shouldRestateRefusal(Long.MIN_VALUE))
    }

    // A watch that is connected must be scoped out before the counts reach the policy: the
    // caller hands over phones only, and these pin what that buys.

    @Test
    fun `a connected watch scoped out keeps the one connected phone unambiguous`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 1))
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, knownCount = 2, connectedCount = 2))
    }

    private val poco = "MAC_POCO"
    private val moto = "MAC_MOTO"

    @Test
    fun `AUTO skips the selector when one phone is in scope and none is connected`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, knownCount = 1))
    }

    @Test
    fun `AUTO shows the selector with no countdown target when nothing is in scope`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, knownCount = 0))
    }

    @Test
    fun `ALWAYS counts every offered phone whatever the scope`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.ALWAYS, pairedCount = 2, connectedCount = 0, knownCount = 1))
    }

    private fun gate(
        remote: String = "cc:dd",
        chosen: String? = "AA:BB",
        ageMs: Long,
        switchedAway: String? = null,
        switchAgeMs: Long = 0L,
        wakeActive: Boolean = true,
        explicit: Boolean,
        unanswered: Boolean,
        handshakePeer: String? = null
    ) = NativeDriverSelectionPolicy.switchGate(
        remote, chosen, ageMs, switchedAway, switchAgeMs, wakeActive, explicit, unanswered, handshakePeer
    )

    @Test
    fun `an automatic pick is never exclusive`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 0L, explicit = false, unanswered = false))
    }

    @Test
    fun `an automatic pick still refuses the phone a switch moved away from`() {
        assertEquals(
            SwitchGate.SWITCHED_AWAY,
            gate(remote = "aa:bb", chosen = "CC:DD", ageMs = 10_000L, switchedAway = "AA:BB",
                switchAgeMs = 10_000L, explicit = false, unanswered = false)
        )
    }

    @Test
    fun `an explicit pick that has not missed a round keeps the lock while its wake runs`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 90_000L, explicit = true, unanswered = false))
    }

    @Test
    fun `an explicit pick that missed a round keeps the floor`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 20_000L, explicit = true, unanswered = true))
    }

    @Test
    fun `an explicit pick that missed a round lets others in after the floor`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 31_000L, explicit = true, unanswered = true))
    }

    @Test
    fun `an unanswered explicit pick is not exclusive past the floor`() {
        assertFalse(NativeDriverSelectionPolicy.chosenExclusive(31_000L, wakeActive = true, explicit = true, wakeUnanswered = true, chosenHandshaking = false))
    }

    @Test
    fun `the ceiling ends an explicit lock whose wake is active and answered`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenExclusive(
                NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, wakeActive = true, explicit = true, wakeUnanswered = false, chosenHandshaking = false
            )
        )
    }

    @Test
    fun `pickLapsedAfterRound truth table`() {
        val p = NativeDriverSelectionPolicy
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = false, lapsed = false))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = true, lapsed = false))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, answeredSincePick = false, lapsed = false))
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = false))
        assertTrue(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, answeredSincePick = false, lapsed = true))
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.NOT_PAIRED, answeredSincePick = false, lapsed = false))
    }

    @Test
    fun `a lapsed pick that answers a later round holds the gate again`() {
        val p = NativeDriverSelectionPolicy
        val lapsed = p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, false, p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, false, false))
        assertEquals(
            SwitchGate.WRONG_PHONE,
            p.switchGate("cc:dd", "AA:BB", 60_000L, null, 0L, chosenWakeActive = true, chosenExplicit = true,
                chosenWakeUnanswered = lapsed, handshakePeerMac = null)
        )
    }

    @Test
    fun `an answered pick does not lapse again on a later dialled round`() {
        val p = NativeDriverSelectionPolicy
        val afterAnswer = p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.ANSWERED, false, false)
        assertFalse(p.pickLapsedAfterRound(BluetoothWakePolicy.WakeOutcome.DIALLED, answeredSincePick = true, lapsed = afterAnswer))
    }

    @Test
    fun `a lapsed pick still holds the gate while its own handshake runs`() {
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = "aa:bb"))
    }

    @Test
    fun `another phone's handshake does not hold a lapsed pick`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = "ee:ff"))
    }

    @Test
    fun `a lapsed pick with no handshake lets others in after the floor`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 60_000L, explicit = true, unanswered = true, handshakePeer = null))
    }

    @Test
    fun `the chosen phone's handshake does not outlast the ceiling`() {
        assertEquals(
            SwitchGate.ACCEPT,
            gate(ageMs = NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, explicit = true, unanswered = true, handshakePeer = "AA:BB")
        )
    }

    @Test
    fun `an automatic pick gets no lock from its handshake`() {
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 10_000L, explicit = false, unanswered = false, handshakePeer = "AA:BB"))
    }

    @Test
    fun `chosenExclusive holds for the chosen phone's handshake`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenExclusive(
                60_000L, wakeActive = false, explicit = true, wakeUnanswered = true, chosenHandshaking = true)
        )
    }

    @Test
    fun `a countdown pick gives way after one unanswered round`() {
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.DIALLED, 1, 30_000L, explicit = false
            )
        )
    }

    @Test
    fun `a countdown pick keeps waking through stand-downs up to the ceiling`() {
        val stoodDown = BluetoothWakePolicy.WakeOutcome.STOOD_DOWN
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(stoodDown, 0, 60_000L, explicit = false))
        assertFalse(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                stoodDown, 0, NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, explicit = false
            )
        )
    }

    @Test
    fun `a countdown pick that answered keeps its rounds`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.ANSWERED, 1, 30_000L, explicit = false
            )
        )
    }

    @Test
    fun `an explicit pick keeps its three rounds after an unanswered one`() {
        assertTrue(
            NativeDriverSelectionPolicy.chosenWakeContinues(
                BluetoothWakePolicy.WakeOutcome.DIALLED, 1, 30_000L, explicit = true
            )
        )
    }

    @Test
    fun `in AUTO one known phone with another bonded and none linked skips the prompt`() {
        assertFalse(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, knownCount = 1))
    }

    @Test
    fun `in AUTO two known phones with none linked ask`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, knownCount = 2))
    }

    @Test
    fun `in AUTO no known phone with two bonded asks`() {
        assertTrue(NativeDriverSelectionPolicy.shouldShowSelector(Mode.AUTO, pairedCount = 2, connectedCount = 0, knownCount = 0))
    }

    @Test
    fun `an automatic pick hands over to the wake plan after an unanswered round`() {
        assertTrue(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.DIALLED, explicit = false, fromPlan = false))
    }

    @Test
    fun `a button press hands over to the wake plan once its budget ends unanswered`() {
        assertTrue(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.DIALLED, explicit = true, fromPlan = true))
    }

    @Test
    fun `a phone the driver named never hands over`() {
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.DIALLED, explicit = true, fromPlan = false))
    }

    @Test
    fun `an answered or unpaired wake never hands over`() {
        val outcomes = listOf(
            BluetoothWakePolicy.WakeOutcome.ANSWERED,
            BluetoothWakePolicy.WakeOutcome.NOT_PAIRED,
        )
        for (outcome in outcomes) {
            assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(outcome, explicit = false, fromPlan = false))
            assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(outcome, explicit = true, fromPlan = true))
            assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(outcome, explicit = true, fromPlan = false))
        }
    }

    @Test
    fun `a held link lapses a pick the selector did not name`() {
        assertTrue(NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, answeredSincePick = false, lapsed = false, fromPlan = true))
    }

    @Test
    fun `a held link keeps the lock on a phone the driver named`() {
        assertFalse(NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, answeredSincePick = false, lapsed = false, fromPlan = false))
    }

    @Test
    fun `a held link does not lapse a pick that answered`() {
        assertFalse(NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, answeredSincePick = true, lapsed = false, fromPlan = true))
    }

    @Test
    fun `the button's held-link pick lets another phone in after the floor, not before`() {
        val lapsed = NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, answeredSincePick = false, lapsed = false, fromPlan = true)
        assertEquals(SwitchGate.ACCEPT, gate(ageMs = 31_000L, explicit = true, unanswered = lapsed))
        assertEquals(SwitchGate.WRONG_PHONE, gate(ageMs = 10_000L, explicit = true, unanswered = lapsed))
    }

    @Test
    fun `a held link on a pick the selector did not name ends the wake at the floor`() {
        val stoodDown = BluetoothWakePolicy.WakeOutcome.HELD_LINK
        assertFalse(NativeDriverSelectionPolicy.chosenWakeContinues(stoodDown, 0, 30_000L, explicit = true, fromPlan = true))
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(stoodDown, 0, 29_000L, explicit = true, fromPlan = true))
    }

    @Test
    fun `a held link on a phone the driver named runs to the ceiling`() {
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, 0, 60_000L, explicit = true, fromPlan = false))
    }

    @Test
    fun `a stood-down pick the selector did not name hands over to the wake plan`() {
        assertTrue(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, explicit = true, fromPlan = true))
        assertTrue(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, explicit = true, fromPlan = true))
    }

    @Test
    fun `a dead-link cycle round after the floor keeps a button pick waking`() {
        // The radio cycle returns a plain stand-down; the round after it must still run.
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, 1, 45_000L, explicit = true, fromPlan = true))
    }

    @Test
    fun `a plain stand-down leaves the lapse of a button pick as it was`() {
        assertFalse(NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = false, fromPlan = true))
        assertTrue(NativeDriverSelectionPolicy.pickLapsedAfterRound(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, answeredSincePick = false, lapsed = true, fromPlan = true))
    }

    @Test
    fun `a held link after the floor still ends a button pick`() {
        assertFalse(NativeDriverSelectionPolicy.chosenWakeContinues(
            BluetoothWakePolicy.WakeOutcome.HELD_LINK, 1, 45_000L, explicit = true, fromPlan = true))
    }

    @Test
    fun `a stood-down phone the driver named does not hand over`() {
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, explicit = true, fromPlan = false))
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(
            BluetoothWakePolicy.WakeOutcome.STOOD_DOWN, explicit = false, fromPlan = false))
    }

    @Test
    fun `a pick the selector did not name keeps waking past the floor until a real round ends`() {
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(null, 0, 30_000L, explicit = true, fromPlan = true))
        assertTrue(NativeDriverSelectionPolicy.chosenWakeContinues(null, 0, 60_000L, explicit = true, fromPlan = true))
        assertFalse(NativeDriverSelectionPolicy.chosenWakeContinues(
            null, 0, NativeDriverSelectionPolicy.CHOSEN_EXCLUSIVE_MAX_MS, explicit = true, fromPlan = true))
    }

    @Test
    fun `a wake that ended before any real round does not hand over`() {
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(null, explicit = true, fromPlan = true))
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(null, explicit = false, fromPlan = true))
        assertFalse(NativeDriverSelectionPolicy.handsOverToWakePlan(null, explicit = false, fromPlan = false))
    }

    private fun hold(
        remote: String,
        list: List<String> = listOf("a", "b"),
        explicitPick: String? = null,
        switchedAway: String? = null,
        linked: Set<String> = emptySet(),
        dialAge: Map<String, Long> = emptyMap(),
        peer: String? = null,
        holdAge: Long? = null,
        ruledOut: Set<String> = emptySet(),
    ): ListHold? = NativeDriverSelectionPolicy.listHoldFor(
        remote, list, explicitPick, switchedAway, linked, dialAge, peer, holdAge, ruledOut
    )

    @Test
    fun `one phone dialling with nobody above it here is let in at once`() {
        assertNull(hold("b"))
    }

    @Test
    fun `a lower phone waits while a higher listed phone is linked`() {
        assertEquals(ListHold("a", HereBy.LINKED), hold("b", linked = setOf("a")))
    }

    @Test
    fun `a lower phone waits while a higher phone dialled in the last 10s`() {
        assertEquals(ListHold("a", HereBy.DIALLED), hold("b", dialAge = mapOf("a" to 9_999L)))
        assertNull(hold("b", dialAge = mapOf("a" to 10_000L)))
    }

    @Test
    fun `the hold ends at its ceiling`() {
        assertEquals(ListHold("a", HereBy.LINKED), hold("b", linked = setOf("a"), holdAge = 19_999L))
        assertNull(hold("b", linked = setOf("a"), holdAge = 20_000L))
    }

    @Test
    fun `a higher phone's handshake keeps a lower phone out past the ceiling`() {
        assertEquals(ListHold("a", HereBy.CONNECTING), hold("b", peer = "a", holdAge = 60_000L))
    }

    @Test
    fun `an unlisted phone waits for any listed phone that is here`() {
        assertEquals(ListHold("a", HereBy.LINKED), hold("c", list = listOf("a"), linked = setOf("a")))
    }

    @Test
    fun `the first phone in the list never waits`() {
        assertNull(hold("a", linked = setOf("b"), peer = "b"))
    }

    @Test
    fun `an explicit pick is let in whatever the list says`() {
        assertNull(hold("b", explicitPick = "b", linked = setOf("a")))
    }

    @Test
    fun `the phone a switch moved away from is not a higher phone`() {
        assertNull(hold("b", switchedAway = "a", linked = setOf("a")))
    }

    @Test
    fun `an empty Phones list holds nobody`() {
        assertNull(hold("b", list = emptyList(), linked = setOf("a")))
    }

    @Test
    fun `addresses match whatever their case`() {
        assertEquals(
            ListHold("aa:bb", HereBy.LINKED),
            hold("CC:DD", list = listOf("aa:bb", "cc:dd"), linked = setOf("AA:BB"))
        )
    }

    @Test
    fun `phonesAbove lists every listed phone for an unlisted one and none for the first`() {
        assertEquals(listOf("a", "b"), NativeDriverSelectionPolicy.phonesAbove("c", listOf("a", "b")))
        assertEquals(emptyList<String>(), NativeDriverSelectionPolicy.phonesAbove("a", listOf("a", "b")))
        assertEquals(listOf("a"), NativeDriverSelectionPolicy.phonesAbove("b", listOf("a", "b")))
    }

    @Test
    fun `the highest phone that is here is named`() {
        assertEquals(
            ListHold("a", HereBy.DIALLED),
            hold("c", list = listOf("a", "b", "c"), linked = setOf("b"), dialAge = mapOf("a" to 1_000L))
        )
    }

    /** Runs dials through listHoldFor and listHoldStep as the manager does. List [a, b], a is linked unless told. */
    private class Gate(
        var linked: Set<String> = setOf("a"),
        val list: List<String> = listOf("a", "b"),
        val grace: Boolean = false,
        var explicit: String? = null,
        val ruledOut: Set<String> = emptySet(),
        val unpaired: Set<String> = emptySet(),
    ) {
        var state = ListHoldState()
        val dials = HashMap<String, Long>()

        fun dial(remote: String, now: Long, peer: String? = null): HoldTurn {
            if (remote in list) dials[remote] = now
            val hold = NativeDriverSelectionPolicy.listHoldFor(
                remote, list, explicit, null, linked,
                dials.mapValues { now - it.value }, peer,
                state.holdAgeMs(remote, now), unpaired
            )
            val above = if (grace) NativeDriverSelectionPolicy.graceFor(remote, list, explicit, null, ruledOut + unpaired) else null
            val picked = explicit.equals(remote, ignoreCase = true)
            return NativeDriverSelectionPolicy.listHoldStep(state, remote, hold, now, above, picked).also { state = it.state }
        }
    }

    @Test
    fun `the higher phone's own dial neither ends nor restarts the hold`() {
        val gate = Gate()
        assertEquals(HoldStep.START, gate.dial("b", 1_000L).step)
        assertEquals(HoldStep.NONE, gate.dial("a", 6_000L).step)
        assertEquals(ListHoldState(1_000L, mapOf("B" to "a"), heldSince = mapOf("B" to 1_000L)), gate.state)
        assertEquals(HoldStep.KEEP, gate.dial("b", 6_100L).step)
    }

    @Test
    fun `a higher phone that dials and fails twice still lets the lower phone in 20s after the first held dial`() {
        val gate = Gate()
        gate.dial("b", 1_000L)
        gate.dial("a", 5_000L)
        assertEquals(HoldStep.KEEP, gate.dial("b", 5_100L).step)
        gate.dial("a", 12_000L)
        assertEquals(HoldStep.KEEP, gate.dial("b", 20_999L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("b", 21_000L).step)
    }

    @Test
    fun `the end line names the held pair whichever phone dials`() {
        val gate = Gate()
        gate.dial("b", 1_000L)
        assertEquals(HoldStep.NONE, gate.dial("a", 21_500L).step)
        val end = gate.dial("b", 21_600L)
        assertEquals(HoldStep.END_CEILING, end.step)
        assertEquals("a", end.above)
        assertEquals(HoldStep.NONE, gate.dial("b", 21_700L).step)
    }

    @Test
    fun `after an early end a return of the higher phone starts no new hold`() {
        val gate = Gate()
        gate.dial("b", 1_000L)
        gate.linked = emptySet()
        assertEquals(HoldStep.END_EARLY, gate.dial("b", 12_000L).step)
        gate.linked = setOf("a")
        assertEquals(HoldStep.NONE, gate.dial("b", 13_000L).step)
        assertEquals(HoldStep.NONE, gate.dial("b", 40_000L).step)
    }

    @Test
    fun `a spent hold still keeps a lower phone off the higher phone's handshake`() {
        val gate = Gate()
        gate.dial("b", 1_000L)
        gate.dial("b", 21_000L)
        assertEquals(HoldStep.KEEP, gate.dial("b", 22_000L, peer = "a").step)
    }

    @Test
    fun `one phone alone starts no hold and says nothing`() {
        val top = Gate(linked = emptySet())
        assertEquals(HoldStep.NONE, top.dial("a", 1_000L).step)
        assertEquals(ListHoldState(), top.state)
        val lower = Gate(linked = emptySet())
        assertEquals(HoldStep.NONE, lower.dial("b", 1_000L).step)
        assertEquals(ListHoldState(), lower.state)
        val unlisted = Gate(linked = emptySet())
        assertEquals(HoldStep.NONE, unlisted.dial("c", 1_000L).step)
        assertEquals(ListHoldState(), unlisted.state)
    }

    /** Counts the steps that log a line, per phone, the way the manager logs them. */
    private fun lines(turns: List<Pair<String, HoldTurn>>, step: (HoldStep) -> Boolean) =
        turns.filter { step(it.second.step) }.map { it.first }

    @Test
    fun `two phones held under one higher phone each get one start and one end, and alternate dials log nothing`() {
        val gate = Gate()
        val turns = ArrayList<Pair<String, HoldTurn>>()
        var now = 1_000L
        while (now < 22_000L) {
            for (remote in listOf("b", "c")) turns.add(remote to gate.dial(remote, now).also { now += 150L })
        }
        assertEquals(listOf("b", "c"), lines(turns) { it == HoldStep.START })
        assertEquals(listOf("b", "c"), lines(turns) { it == HoldStep.END_CEILING || it == HoldStep.END_EARLY })
        assertTrue(turns.all { it.second.step != HoldStep.KEEP || it.second.state.waits(it.first) })
        assertEquals(listOf("a", "a"), turns.filter { it.second.step == HoldStep.END_CEILING }.map { it.second.above })
    }

    @Test
    fun `a phone let in early is not held again while another phone still waits`() {
        val gate = Gate()
        assertEquals(HoldStep.START, gate.dial("b", 1_000L).step)
        assertEquals(HoldStep.START, gate.dial("c", 1_100L).step)
        gate.linked = emptySet()
        gate.dials.remove("a")
        val end = gate.dial("b", 5_000L)
        assertEquals(HoldStep.END_EARLY, end.step)
        assertEquals("a", end.above)
        assertEquals(HoldStep.KEEP, gate.dial("c", 5_100L).step)
        gate.linked = setOf("a")
        assertEquals(HoldStep.NONE, gate.dial("b", 6_000L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("c", 21_100L).step)
    }

    private fun graceGate(list: List<String> = listOf("a", "b"), explicit: String? = null, ruledOut: Set<String> = emptySet()) =
        Gate(linked = emptySet(), list = list, grace = true, explicit = explicit, ruledOut = ruledOut)

    @Test
    fun `a lower phone that dials first is held for the grace and let in at 5s when nobody higher comes`() {
        val gate = graceGate()
        val first = gate.dial("b", 1_000L)
        assertEquals(HoldStep.GRACE_START, first.step)
        assertEquals("a", first.above)
        assertEquals(HoldStep.GRACE_KEEP, gate.dial("b", 3_000L).step)
        assertEquals(HoldStep.GRACE_KEEP, gate.dial("b", 5_999L).step)
        val end = gate.dial("b", 6_000L)
        assertEquals(HoldStep.GRACE_END, end.step)
        assertEquals("a", end.above)
        assertEquals(HoldStep.NONE, gate.dial("b", 6_100L).step)
        assertFalse(gate.state.waits("b"))
    }

    @Test
    fun `a higher phone that dials in the grace wins and the lower phone then waits under the hold`() {
        val gate = graceGate()
        assertEquals(HoldStep.GRACE_START, gate.dial("b", 1_000L).step)
        assertEquals(HoldStep.NONE, gate.dial("a", 2_000L).step)
        val held = gate.dial("b", 6_000L)
        assertEquals(HoldStep.START, held.step)
        assertEquals("a", held.above)
        assertEquals(1_000L, gate.state.startedAt)
        gate.dial("a", 12_000L)
        gate.dial("a", 18_000L)
        assertEquals(HoldStep.KEEP, gate.dial("b", 20_999L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("b", 21_000L).step)
        assertEquals(HoldStep.NONE, gate.dial("b", 21_100L).step)
    }

    @Test
    fun `the grace is not given twice before a clear`() {
        val gate = graceGate()
        gate.dial("b", 1_000L)
        gate.dial("b", 6_000L)
        assertEquals(HoldStep.NONE, gate.dial("b", 6_100L).step)
        assertEquals(HoldStep.NONE, gate.dial("b", 60_000L).step)
        gate.state = ListHoldState()
        assertEquals(HoldStep.GRACE_START, gate.dial("b", 70_000L).step)
    }

    @Test
    fun `a grace that turned into a hold is not given again`() {
        val gate = graceGate()
        gate.dial("b", 1_000L)
        gate.dial("a", 2_000L)
        gate.dial("b", 3_000L)
        gate.dial("b", 30_000L)
        assertEquals(HoldStep.NONE, gate.dial("b", 31_000L).step)
    }

    @Test
    fun `the top phone, the explicit pick, an empty list and a ruled out higher phone get no grace`() {
        assertEquals(HoldStep.NONE, graceGate().dial("a", 1_000L).step)
        assertEquals(HoldStep.NONE, graceGate(explicit = "b").dial("b", 1_000L).step)
        assertEquals(HoldStep.NONE, graceGate(list = emptyList()).dial("b", 1_000L).step)
        assertEquals(HoldStep.NONE, graceGate(ruledOut = setOf("a")).dial("b", 1_000L).step)
        assertEquals(HoldStep.NONE, graceGate(list = listOf("b")).dial("b", 1_000L).step)
    }

    @Test
    fun `the grace names the first higher phone that is not ruled out`() {
        val gate = graceGate(list = listOf("a", "b", "c"), ruledOut = setOf("a"))
        val first = gate.dial("c", 1_000L)
        assertEquals(HoldStep.GRACE_START, first.step)
        assertEquals("b", first.above)
    }

    @Test
    fun `an unlisted phone counts as below every listed phone`() {
        assertEquals(HoldStep.GRACE_START, graceGate().dial("z", 1_000L).step)
    }

    @Test
    fun `two lower phones in grace each get one start and one end`() {
        var state = ListHoldState()
        val turns = ArrayList<Pair<String, HoldTurn>>()
        var now = 1_000L
        while (now < 9_000L) {
            for (remote in listOf("b", "c")) {
                val turn = NativeDriverSelectionPolicy.listHoldStep(state, remote, null, now, graceAbove = "a")
                state = turn.state
                turns.add(remote to turn)
                now += 150L
            }
        }
        assertEquals(listOf("b", "c"), lines(turns) { it == HoldStep.GRACE_START })
        assertEquals(listOf("b", "c"), lines(turns) { it == HoldStep.GRACE_END })
    }

    @Test
    fun `graceFor skips a phone the switch left`() {
        assertNull(NativeDriverSelectionPolicy.graceFor("b", listOf("a", "b"), null, "A", emptySet()))
    }

    @Test
    fun `a joining phone with an older grace does not move the first held phone's ceiling`() {
        val gate = graceGate(list = listOf("a", "b", "c"))
        assertEquals(HoldStep.GRACE_START, gate.dial("b", 1_000L).step)
        gate.linked = setOf("a")
        assertEquals(HoldStep.START, gate.dial("c", 4_500L).step)
        assertEquals(HoldStep.START, gate.dial("b", 6_000L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("b", 21_000L).step)
        assertEquals(HoldStep.KEEP, gate.dial("c", 21_000L).step)
        assertEquals(HoldStep.KEEP, gate.dial("c", 24_499L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("c", 24_500L).step)
    }

    @Test
    fun `a grace two graces old is not read as a ceiling already past`() {
        val gate = graceGate(list = listOf("a", "b", "c"))
        assertEquals(HoldStep.GRACE_START, gate.dial("b", 1_000L).step)
        gate.linked = setOf("a")
        assertEquals(HoldStep.START, gate.dial("c", 30_000L).step)
        assertEquals(HoldStep.START, gate.dial("b", 61_000L).step)
        assertEquals(HoldStep.KEEP, gate.dial("b", 80_999L).step)
        assertEquals(HoldStep.END_CEILING, gate.dial("b", 81_000L).step)
    }

    @Test
    fun `an unpaired higher phone gives no grace and no hold`() {
        assertNull(hold("b", linked = setOf("a"), ruledOut = setOf("A")))
        assertEquals(
            ListHold("b", HereBy.LINKED),
            hold("c", list = listOf("a", "b", "c"), linked = setOf("a", "b"), ruledOut = setOf("a"))
        )
        val gate = Gate(linked = emptySet(), grace = true, unpaired = setOf("a"))
        assertEquals(HoldStep.NONE, gate.dial("b", 1_000L).step)
        assertEquals(ListHoldState(), gate.state)
        assertEquals("b", NativeDriverSelectionPolicy.graceFor("c", listOf("a", "b", "c"), null, null, setOf("A")))
    }

    @Test
    fun `a held phone that becomes the explicit pick is let in with no early end line`() {
        val gate = Gate(list = listOf("a", "c"))
        assertEquals(HoldStep.START, gate.dial("c", 1_000L).step)
        gate.explicit = "c"
        val end = gate.dial("c", 9_000L)
        assertEquals(HoldStep.END_PICKED, end.step)
        assertFalse(end.state.waits("c"))
        assertEquals(HoldStep.NONE, gate.dial("c", 9_100L).step)
    }

    @Test
    fun `a phone in its grace that becomes the explicit pick is let in at once`() {
        val gate = graceGate()
        assertEquals(HoldStep.GRACE_START, gate.dial("b", 1_000L).step)
        gate.explicit = "b"
        assertEquals(HoldStep.END_PICKED, gate.dial("b", 2_000L).step)
        assertFalse(gate.state.waits("b"))
    }

    @Test
    fun `two phones kept off a spent hold's handshake are each told once`() {
        val gate = Gate(list = listOf("a", "b", "c"))
        gate.dial("b", 1_000L)
        gate.dial("c", 1_100L)
        gate.dial("b", 21_000L)
        gate.dial("c", 21_100L)
        val told = ArrayList<String>()
        var now = 22_000L
        while (now < 60_000L) {
            for (remote in listOf("b", "c")) {
                val before = gate.state.told
                val turn = gate.dial(remote, now, peer = "a")
                assertEquals(HoldStep.KEEP, turn.step)
                assertTrue(turn.state.waits(remote))
                if (turn.state.told.size > before.size) told.add(remote)
                now += 150L
            }
        }
        assertEquals(listOf("b", "c"), told)
        assertEquals(HoldStep.NONE, gate.dial("b", now).step)
        assertFalse(gate.state.waits("b"))
    }
}
