package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.HotspotRestartPolicy.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class HotspotRestartPolicyTest {

    @Test
    fun `up and still up after the hold is confirmed`() {
        assertEquals(Outcome.CONFIRMED, HotspotRestartPolicy.afterHold(true, true, false))
    }

    @Test
    fun `up then down is asked again once`() {
        assertEquals(Outcome.ASK_AGAIN, HotspotRestartPolicy.afterHold(true, false, false))
    }

    @Test
    fun `up then down with the second ask spent gives up, never a third ask`() {
        assertEquals(Outcome.GIVE_UP, HotspotRestartPolicy.afterHold(true, false, true))
    }

    @Test
    fun `never up after the first ask keeps the single-ask rule`() {
        assertEquals(Outcome.GIVE_UP, HotspotRestartPolicy.afterHold(false, false, false))
        assertEquals(Outcome.GIVE_UP, HotspotRestartPolicy.afterHold(false, true, false))
    }
}
