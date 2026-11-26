package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy.TargetLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HfpSlcInitiator.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeRefusalPolicyTest {

    // Measured on a Z Fold3 with the car's Bluetooth (and at home a headset) plus earbuds connected:
    // the poke's socket connected, nothing answered AT+BRSF, and the phone dropped the whole link
    // 0.34 s later ("Blocked by isNewConnectionAcceptable").
    @Test
    fun `no answer and the phone's link gone is a refusal`() {
        assertTrue(HandsFreeRefusalPolicy.refused(Stage.BRSF, TargetLink.ABSENT))
        assertTrue(HandsFreeRefusalPolicy.refused(Stage.IDLE, TargetLink.ABSENT))
    }

    // A phone already serving another device's hands-free link keeps ours up and says nothing.
    @Test
    fun `no answer with the link still up is silence, not a refusal`() {
        assertFalse(HandsFreeRefusalPolicy.refused(Stage.BRSF, TargetLink.CONNECTED))
    }

    @Test
    fun `a link that cannot be read is never taken as gone`() {
        assertFalse(HandsFreeRefusalPolicy.refused(Stage.BRSF, TargetLink.UNREADABLE))
    }

    @Test
    fun `a phone that answered did not refuse, whatever its link did afterwards`() {
        for (stage in listOf(Stage.CIND_TEST, Stage.CIND_READ, Stage.CMER, Stage.ESTABLISHED)) {
            assertFalse(stage.name, HandsFreeRefusalPolicy.refused(stage, TargetLink.ABSENT))
        }
    }

    // Putting the earbuds away has to be all it takes: the chosen phone is asked again after it
    // refuses, so the session comes up without anybody pressing the button a second time.
    @Test
    fun `a phone that refused is asked again until patience runs out`() {
        val refusedAt = 1_000L
        assertTrue(HandsFreeRefusalPolicy.keepsAsking(firstRefusalAtMs = refusedAt, nowMs = refusedAt))
        assertTrue(
            HandsFreeRefusalPolicy.keepsAsking(refusedAt, refusedAt + HandsFreeRefusalPolicy.SLOW_PATIENCE_MS - 1)
        )
        assertFalse(HandsFreeRefusalPolicy.keepsAsking(refusedAt, refusedAt + HandsFreeRefusalPolicy.SLOW_PATIENCE_MS))
    }

    // Measured 2026-10-24: the asking stopped ten minutes after the first refusal (11:18), the
    // earbuds went into their case at 11:20, and nothing was asking any more. It slows down instead.
    @Test
    fun `after the fast ten minutes the asking slows instead of stopping`() {
        val refusedAt = 1_000L
        val tenMinutesOn = refusedAt + HandsFreeRefusalPolicy.PATIENCE_MS
        assertTrue(HandsFreeRefusalPolicy.keepsAsking(refusedAt, tenMinutesOn + 2 * 60_000L))
        assertEquals(HandsFreeRefusalPolicy.RETRY_GAP_MS, HandsFreeRefusalPolicy.gapMs(refusedAt, tenMinutesOn - 1, 15_000L))
        assertEquals(HandsFreeRefusalPolicy.SLOW_RETRY_GAP_MS, HandsFreeRefusalPolicy.gapMs(refusedAt, tenMinutesOn, 15_000L))
    }

    // Measured 2026-10-24 on the Fold: the ask after a refusal failed to connect at all, and the
    // wake stopped there because only a take-and-drop round kept it going. Fifteen minutes later the
    // earbuds were in their case and nothing was asking. The refusal, not the last round, decides.
    @Test
    fun `later rounds that fail another way do not end the asking`() {
        val refusedAt = 1_000L
        val aRoundLater = refusedAt + HandsFreeRefusalPolicy.RETRY_GAP_MS
        assertTrue(HandsFreeRefusalPolicy.keepsAsking(firstRefusalAtMs = refusedAt, nowMs = aRoundLater))
    }

    @Test
    fun `a phone that never refused earns no extra asking`() {
        assertFalse(HandsFreeRefusalPolicy.keepsAsking(firstRefusalAtMs = 0L, nowMs = 1_000L))
    }

    // Each ask opens Android Auto on the phone for about 5 s, so asking is spaced well apart.
    @Test
    fun `a refusing phone is not asked more often than every half minute`() {
        assertTrue(HandsFreeRefusalPolicy.RETRY_GAP_MS >= 30_000L)
        assertEquals(HandsFreeRefusalPolicy.RETRY_GAP_MS, HandsFreeRefusalPolicy.gapMs(firstRefusalAtMs = 1_000L, nowMs = 1_000L, ordinaryGapMs = 15_000L))
    }

    @Test
    fun `a phone that never refused keeps the ordinary gap`() {
        assertEquals(15_000L, HandsFreeRefusalPolicy.gapMs(firstRefusalAtMs = 0L, nowMs = 1_000L, ordinaryGapMs = 15_000L))
    }

    // The Fold dropped the link 0.34 s after the socket connected; the read must come after that.
    @Test
    fun `the link is read after the phone has had time to drop it`() {
        assertTrue(HandsFreeRefusalPolicy.LINK_PROBE_MS >= 1_000L)
    }

    private fun afterRounds(
        otherPhones: Boolean = true,
        credentialsReady: Boolean = true,
        namedByDriver: Boolean = false,
        wakeLoopWaits: Boolean = false,
        planCanStart: Boolean = true
    ) = HandsFreeRefusalPolicy.afterRounds(otherPhones, credentialsReady, namedByDriver, wakeLoopWaits, planCanStart)

    @Test
    fun `a refusing phone alone in the plan keeps the wake slot`() {
        for (named in listOf(true, false)) for (waits in listOf(true, false)) {
            assertEquals(
                HandsFreeRefusalPolicy.AfterRounds.KEEP_SLOT,
                afterRounds(otherPhones = false, namedByDriver = named, wakeLoopWaits = waits)
            )
        }
    }

    @Test
    fun `a refusing phone hands over to the plan when another phone waits`() {
        assertEquals(HandsFreeRefusalPolicy.AfterRounds.HAND_OVER, afterRounds())
    }

    @Test
    fun `a phone the driver named hands over only once a wake loop waits`() {
        assertEquals(HandsFreeRefusalPolicy.AfterRounds.GIVE_WAY, afterRounds(namedByDriver = true))
        assertEquals(HandsFreeRefusalPolicy.AfterRounds.HAND_OVER, afterRounds(namedByDriver = true, wakeLoopWaits = true))
    }

    // Another phone's handshake in flight, or the listeners down, stops the plan from starting. A
    // break there ended the chosen wake too, and a handshake that failed before type 3 woke nobody.
    @Test
    fun `a refusing phone keeps the slot when the plan cannot start now`() {
        for (named in listOf(true, false)) {
            assertEquals(
                HandsFreeRefusalPolicy.AfterRounds.GIVE_WAY,
                afterRounds(namedByDriver = named, wakeLoopWaits = true, planCanStart = false)
            )
        }
        assertEquals(
            HandsFreeRefusalPolicy.AfterRounds.KEEP_SLOT, afterRounds(otherPhones = false, planCanStart = false)
        )
    }

    @Test
    fun `nothing is handed over with no credentials to start a loop with`() {
        assertEquals(
            HandsFreeRefusalPolicy.AfterRounds.GIVE_WAY, afterRounds(credentialsReady = false, wakeLoopWaits = true)
        )
    }

    @Test
    fun `the plan asks a refusing pick at the gaps of the chosen wake, then stops after the hour`() {
        val refusedAt = 1_000L
        fun due(sinceFirst: Long, sinceAsk: Long) = HandsFreeRefusalPolicy.askDue(
            HandsFreeRefusalPolicy.RefusingPick("A0:46:5A:97:E4:95", refusedAt, refusedAt + sinceFirst - sinceAsk),
            refusedAt + sinceFirst
        )
        assertFalse(due(60_000L, HandsFreeRefusalPolicy.RETRY_GAP_MS - 1))
        assertTrue(due(60_000L, HandsFreeRefusalPolicy.RETRY_GAP_MS))
        val slow = HandsFreeRefusalPolicy.PATIENCE_MS + HandsFreeRefusalPolicy.SLOW_RETRY_GAP_MS
        assertFalse(due(slow, HandsFreeRefusalPolicy.SLOW_RETRY_GAP_MS - 1))
        assertTrue(due(slow, HandsFreeRefusalPolicy.SLOW_RETRY_GAP_MS))
        assertFalse(due(HandsFreeRefusalPolicy.SLOW_PATIENCE_MS, HandsFreeRefusalPolicy.SLOW_RETRY_GAP_MS))
    }

    // The automatic pick or the WiFi button is not a phone picked by hand, so "Only these phones"
    // leaving it out ends its asks. A phone the driver named is still woken.
    @Test
    fun `only a refusing pick the driver named is asked outside the plan`() {
        val named = HandsFreeRefusalPolicy.RefusingPick("A0:46:5A:97:E4:95", 1_000L, 1_000L, namedByDriver = true)
        val other = "5C:51:4F:0A:11:22"
        assertEquals(named.mac, HandsFreeRefusalPolicy.unplannedAsk(named, listOf(other)))
        assertEquals(null, HandsFreeRefusalPolicy.unplannedAsk(named.copy(namedByDriver = false), listOf(other)))
        assertEquals(null, HandsFreeRefusalPolicy.unplannedAsk(named, listOf(other, "a0:46:5a:97:e4:95")))
        assertEquals(null, HandsFreeRefusalPolicy.unplannedAsk(null, listOf(other)))
    }

    // A second phone that refuses in the wake plan was asked about every 17 s, and each ask
    // opened Android Auto on it and raised ACL_CONNECTED on this unit.
    @Test
    fun `a phone that refused in the plan gets the slow gap, and one that never refused does not`() {
        val mac = "5C:51:4F:0A:11:22"
        val at = 1_000L
        assertTrue(HandsFreeRefusalPolicy.planAskDue(null, at))
        val opened = HandsFreeRefusalPolicy.afterPlanAsk(mac, null, refused = true, nowMs = at)!!
        assertEquals(HandsFreeRefusalPolicy.RefusingPick(mac, at, at), opened)
        assertFalse(HandsFreeRefusalPolicy.planAskDue(opened, at + 17_000L))
        assertTrue(HandsFreeRefusalPolicy.planAskDue(opened, at + HandsFreeRefusalPolicy.RETRY_GAP_MS))
        assertEquals(null, HandsFreeRefusalPolicy.afterPlanAsk(mac, null, refused = false, nowMs = at))
    }

    // On the Fold the ask after a refusal failed to connect at all: that does not end the slow gap.
    @Test
    fun `a later ask moves the plan refusal and keeps its start, and the hour ends it`() {
        val mac = "5C:51:4F:0A:11:22"
        val opened = HandsFreeRefusalPolicy.RefusingPick(mac, 1_000L, 1_000L)
        val later = 1_000L + HandsFreeRefusalPolicy.RETRY_GAP_MS
        assertEquals(
            opened.copy(lastAskAtMs = later),
            HandsFreeRefusalPolicy.afterPlanAsk(mac, opened, refused = false, nowMs = later)
        )
        val pastHour = 1_000L + HandsFreeRefusalPolicy.SLOW_PATIENCE_MS
        assertTrue(HandsFreeRefusalPolicy.planAskDue(opened.copy(lastAskAtMs = pastHour - 1), pastHour))
        assertEquals(null, HandsFreeRefusalPolicy.afterPlanAsk(mac, opened, refused = false, nowMs = pastHour))
        assertEquals(
            HandsFreeRefusalPolicy.RefusingPick(mac, pastHour, pastHour),
            HandsFreeRefusalPolicy.afterPlanAsk(mac, opened, refused = true, nowMs = pastHour)
        )
    }

    // The plan dials B between the asks to a refusing A. B's link cleared A's record, and A's next
    // refusal raised it with a new stamp, so the banner came back over the driver's dismissal.
    @Test
    fun `only the refusing phone's own link retires the refusal record`() {
        val refuser = "A0:46:5A:97:E4:95"
        assertFalse(HandsFreeRefusalPolicy.retiresRefusal("5C:51:4F:0A:11:22", refuser))
        assertTrue(HandsFreeRefusalPolicy.retiresRefusal("a0:46:5a:97:e4:95", refuser))
        // A record from an earlier run names no phone, and any link here retires it as before.
        assertTrue(HandsFreeRefusalPolicy.retiresRefusal("5C:51:4F:0A:11:22", null))
    }
}
