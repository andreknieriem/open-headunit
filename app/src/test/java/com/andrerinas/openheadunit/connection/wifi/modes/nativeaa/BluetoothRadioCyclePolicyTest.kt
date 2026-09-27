package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothRadioCyclePolicy.StandDownAction
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothRadioCyclePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothRadioCyclePolicyTest {

    private val now = 1_000_000L
    private val standDown = now - BluetoothRadioCyclePolicy.CYCLE_AFTER_MS

    private fun decide(
        sdkInt: Int = BluetoothRadioCyclePolicy.LAST_CYCLEABLE_SDK,
        verdict: Verdict = Verdict.UNKNOWN,
        reason: BluetoothWakePolicy.WakeReason = BluetoothWakePolicy.WakeReason.TARGET_CONNECTED,
        pairingHasRunAaHere: Boolean = true,
        callActive: Boolean = false,
        sessionInProgress: Boolean = false,
        standDownSinceMs: Long = standDown,
        lastCycleMs: Long = 0L,
        cyclesUsed: Int = 0,
    ) = BluetoothRadioCyclePolicy.cycleDecision(
        sdkInt, verdict, reason, pairingHasRunAaHere, callActive, sessionInProgress,
        standDownSinceMs, lastCycleMs, cyclesUsed, now
    )

    @Test
    fun `a stand-down that has run its wait cycles the radio`() {
        assertTrue(decide())
    }

    /** From Android 13 `BluetoothAdapter.disable()` is a no-op, so the lever does not exist. */
    @Test
    fun `nothing cycles from the release where the call stopped working`() {
        assertTrue(decide(sdkInt = BluetoothRadioCyclePolicy.LAST_CYCLEABLE_SDK))
        assertFalse(decide(sdkInt = BluetoothRadioCyclePolicy.LAST_CYCLEABLE_SDK + 1))
        assertFalse(decide(sdkInt = 34))
    }

    @Test
    fun `a unit whose radio did not come back is never cycled again`() {
        assertTrue(decide(verdict = Verdict.UNKNOWN))
        assertTrue(decide(verdict = Verdict.RECOVERS))
        assertFalse(decide(verdict = Verdict.STRANDS))
    }

    /**
     * TARGET_UNREADABLE could not tell whose link it is, and a guess is not a reason to drop one.
     * Every other reason poked in the first place, so there is no stand-down to escalate.
     */
    @Test
    fun `only a link taken to be the target's is worth a cycle`() {
        for (reason in BluetoothWakePolicy.WakeReason.entries) {
            assertEquals(
                reason.name,
                reason == BluetoothWakePolicy.WakeReason.TARGET_CONNECTED,
                decide(reason = reason)
            )
        }
    }

    @Test
    fun `a phone paired only for calls is never disturbed`() {
        assertFalse(decide(pairingHasRunAaHere = false))
    }

    /** The cycle drops the ACL a call is riding on, which is the one cost that cannot be undone. */
    @Test
    fun `a call in progress stops the cycle`() {
        assertFalse(decide(callActive = true))
    }

    @Test
    fun `nothing touches the radio while a session is up`() {
        assertFalse(decide(sessionInProgress = true))
    }

    @Test
    fun `a stand-down shorter than the wait is left alone`() {
        assertFalse(decide(standDownSinceMs = now))
        assertFalse(decide(standDownSinceMs = now - BluetoothRadioCyclePolicy.CYCLE_AFTER_MS + 1))
        assertTrue(decide(standDownSinceMs = now - BluetoothRadioCyclePolicy.CYCLE_AFTER_MS))
    }

    /** Zero means no stand-down has been stamped, which is not an old one. */
    @Test
    fun `an unstamped stand-down never cycles`() {
        assertFalse(decide(standDownSinceMs = 0L))
        assertFalse(decide(standDownSinceMs = -1L))
    }

    @Test
    fun `a second cycle waits out the cooldown, and there is only one per arming anyway`() {
        assertFalse(decide(lastCycleMs = now - BluetoothRadioCyclePolicy.CYCLE_COOLDOWN_MS + 1))
        assertFalse(decide(cyclesUsed = BluetoothRadioCyclePolicy.MAX_CYCLES_PER_ARMING))
    }

    /** Asking the wake spends its budget and arms its probe, so a chosen cycle must not ask. */
    @Test
    fun `the two levers are exclusive, and the cycle does not even ask the wake`() {
        var asked = false
        assertEquals(
            StandDownAction.CYCLE_RADIO,
            BluetoothRadioCyclePolicy.chooseAction(cycleAllowed = true) { asked = true; true }
        )
        assertFalse("the wake was asked behind a chosen cycle", asked)
        assertEquals(
            StandDownAction.ESCALATED_WAKE,
            BluetoothRadioCyclePolicy.chooseAction(cycleAllowed = false) { true }
        )
        assertEquals(
            StandDownAction.NOTHING,
            BluetoothRadioCyclePolicy.chooseAction(cycleAllowed = false) { false }
        )
    }

    @Test
    fun `a radio that came back and reconnected recovers, and one that did neither strands`() {
        assertEquals(Verdict.RECOVERS, BluetoothRadioCyclePolicy.verdictFrom(true, true))
        assertEquals(Verdict.STRANDS, BluetoothRadioCyclePolicy.verdictFrom(true, false))
        assertEquals(Verdict.STRANDS, BluetoothRadioCyclePolicy.verdictFrom(false, null))
    }

    /**
     * A radio that never came back is the worst outcome there is, so it is recorded even with the
     * link unreadable. Anything else unreadable stays unmeasured rather than condemning the unit.
     */
    @Test
    fun `an unreadable answer leaves the unit unmeasured, unless the radio itself stayed off`() {
        assertEquals(Verdict.UNKNOWN, BluetoothRadioCyclePolicy.verdictFrom(null, true))
        assertEquals(Verdict.UNKNOWN, BluetoothRadioCyclePolicy.verdictFrom(true, null))
        assertEquals(Verdict.STRANDS, BluetoothRadioCyclePolicy.verdictFrom(false, true))
    }

    @Test
    fun `only a real answer is worth storing, and only an unmeasured unit is probed`() {
        assertFalse(BluetoothRadioCyclePolicy.recordable(Verdict.UNKNOWN))
        assertTrue(BluetoothRadioCyclePolicy.recordable(Verdict.RECOVERS))
        assertTrue(BluetoothRadioCyclePolicy.recordable(Verdict.STRANDS))
        assertTrue(BluetoothRadioCyclePolicy.isProbe(Verdict.UNKNOWN))
        assertFalse(BluetoothRadioCyclePolicy.isProbe(Verdict.RECOVERS))
        assertFalse(BluetoothRadioCyclePolicy.isProbe(Verdict.STRANDS))
    }

    @Test
    fun `the stored int round-trips and an unknown one reads as unmeasured`() {
        for (verdict in Verdict.entries) {
            assertEquals(verdict, Verdict.of(verdict.ordinal))
        }
        assertEquals(Verdict.UNKNOWN, Verdict.of(-1))
        assertEquals(Verdict.UNKNOWN, Verdict.of(Verdict.entries.size))
    }

    /**
     * A poke 15 s after the cycle would take back the link the cycle just handed over, and the one
     * measured poke-to-dial-back on a radio that holds hands-free was 66 s.
     */
    @Test
    fun `the quiet window after a cycle outlasts the measurement it waits on`() {
        assertTrue(
            BluetoothRadioCyclePolicy.POST_CYCLE_QUIET_MS > BluetoothRadioCyclePolicy.RECOVERY_WINDOW_MS
        )
        assertTrue(BluetoothRadioCyclePolicy.POST_CYCLE_QUIET_MS >= 66_000L)
    }

    /** Shared with the wake it replaces, so a phone that is merely slow is not disturbed twice. */
    @Test
    fun `the cycle waits exactly as long as the wake it stands in for`() {
        assertEquals(
            HandsFreeWakeEscalationPolicy.ESCALATE_AFTER_MS,
            BluetoothRadioCyclePolicy.CYCLE_AFTER_MS
        )
        assertEquals(
            HandsFreeWakeEscalationPolicy.ESCALATION_COOLDOWN_MS,
            BluetoothRadioCyclePolicy.CYCLE_COOLDOWN_MS
        )
        assertEquals(
            NativeAaWakeDamagePolicy.PROBE_WINDOW_MS,
            BluetoothRadioCyclePolicy.RECOVERY_WINDOW_MS
        )
    }

    @Test
    fun `a cycle that was never measured records nothing`() {
        // What a cancelled cycle now passes. It used to pass false, which is the first arm of
        // verdictFrom and writes STRANDS at once, with no recovery window and nothing to lift it.
        assertEquals(BluetoothRadioCyclePolicy.Verdict.UNKNOWN, BluetoothRadioCyclePolicy.verdictFrom(null, null))
        assertEquals(BluetoothRadioCyclePolicy.Verdict.UNKNOWN, BluetoothRadioCyclePolicy.verdictFrom(null, true))
        assertEquals(BluetoothRadioCyclePolicy.Verdict.UNKNOWN, BluetoothRadioCyclePolicy.verdictFrom(null, false))
        assertFalse(BluetoothRadioCyclePolicy.recordable(BluetoothRadioCyclePolicy.Verdict.UNKNOWN))
    }

    @Test
    fun `a radio that really did not come back is still condemned`() {
        // The complement, so the fix above cannot be read as disabling the measurement.
        assertEquals(BluetoothRadioCyclePolicy.Verdict.STRANDS, BluetoothRadioCyclePolicy.verdictFrom(false, null))
        assertTrue(BluetoothRadioCyclePolicy.recordable(BluetoothRadioCyclePolicy.Verdict.STRANDS))
    }
}
