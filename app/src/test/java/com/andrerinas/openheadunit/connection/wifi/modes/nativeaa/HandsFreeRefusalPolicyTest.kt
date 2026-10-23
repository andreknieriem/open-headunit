package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.BluetoothWakePolicy.TargetLink
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.HfpSlcInitiator.Stage
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

    // Putting the earbuds away has to be all it takes: the chosen phone is asked again while it
    // refuses, so the session comes up without anybody pressing the button a second time.
    @Test
    fun `a refusing phone is asked again until patience runs out`() {
        assertTrue(HandsFreeRefusalPolicy.keepsAsking(refusedLastRound = true, sinceFirstRefusalMs = 0L))
        assertTrue(
            HandsFreeRefusalPolicy.keepsAsking(true, HandsFreeRefusalPolicy.PATIENCE_MS - 1)
        )
        assertFalse(HandsFreeRefusalPolicy.keepsAsking(true, HandsFreeRefusalPolicy.PATIENCE_MS))
    }

    @Test
    fun `a round that was not refused earns no extra asking`() {
        assertFalse(HandsFreeRefusalPolicy.keepsAsking(refusedLastRound = false, sinceFirstRefusalMs = 0L))
    }

    // Each ask opens Android Auto on the phone for about 5 s, so asking is spaced well apart.
    @Test
    fun `a refusing phone is not asked more often than every half minute`() {
        assertTrue(HandsFreeRefusalPolicy.RETRY_GAP_MS >= 30_000L)
    }

    // The Fold dropped the link 0.34 s after the socket connected; the read must come after that.
    @Test
    fun `the link is read after the phone has had time to drop it`() {
        assertTrue(HandsFreeRefusalPolicy.LINK_PROBE_MS >= 1_000L)
    }
}
