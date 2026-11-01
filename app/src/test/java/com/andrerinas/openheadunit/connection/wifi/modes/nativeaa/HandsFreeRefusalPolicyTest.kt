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
}
