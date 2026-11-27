package com.andrerinas.openheadunit.connection.carplaynative

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarPlayTunnelHoldPolicyTest {
    @Test fun `a missing or non positive extra holds nothing`() {
        assertNull(CarPlayTunnelHoldPolicy.seconds(0))
        assertNull(CarPlayTunnelHoldPolicy.seconds(-5))
    }

    @Test fun `one to the maximum passes and a larger value is cut to the maximum`() {
        assertEquals(1, CarPlayTunnelHoldPolicy.seconds(1))
        assertEquals(60, CarPlayTunnelHoldPolicy.seconds(60))
        assertEquals(120, CarPlayTunnelHoldPolicy.seconds(120))
        assertEquals(120, CarPlayTunnelHoldPolicy.seconds(100_000))
    }

    @Test fun `the action names the debug intent the rig sends`() {
        assertEquals("com.andrerinas.openheadunit.DEBUG_CARPLAY_TUNNEL_HOLD", CarPlayTunnelHoldPolicy.ACTION)
    }

    @Test fun `a live Android Auto or native session refuses the hold with its own line`() {
        val aa = CarPlayTunnelHoldPolicy.decide(60, aaSessionLive = true, nativeLive = true)
        assertEquals("CarPlayNative: tunnel hold refused, an Android Auto session is live", (aa as CarPlayTunnelHoldPolicy.Decision.Refused).line)
        val native = CarPlayTunnelHoldPolicy.decide(60, aaSessionLive = false, nativeLive = true)
        assertEquals("CarPlayNative: tunnel hold refused, a native CarPlay session is live", (native as CarPlayTunnelHoldPolicy.Decision.Refused).line)
    }

    @Test fun `a bad extra is refused first and a free unit holds for the cut seconds`() {
        val bad = CarPlayTunnelHoldPolicy.decide(0, aaSessionLive = true, nativeLive = false)
        assertEquals("CarPlayNative: tunnel hold ignored, the seconds extra is not 1 to 120", (bad as CarPlayTunnelHoldPolicy.Decision.Refused).line)
        assertEquals(120, (CarPlayTunnelHoldPolicy.decide(500, aaSessionLive = false, nativeLive = false) as CarPlayTunnelHoldPolicy.Decision.Hold).seconds)
    }

    @Test fun `only the shell can send the hold`() {
        assertEquals("android.permission.DUMP", CarPlayTunnelHoldPolicy.SENDER_PERMISSION)
    }
}
