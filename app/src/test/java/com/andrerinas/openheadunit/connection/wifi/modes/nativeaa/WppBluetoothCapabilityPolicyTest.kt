package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Test

class WppBluetoothCapabilityPolicyTest {
    @Test
    fun `unknown band capability never enables fallback`() {
        assertEquals(emptyList<Int>(), WppBluetoothCapabilityPolicy.channelsFor(
            WppSessionChannel.Unavailable("unknown"), null
        ))
    }

    @Test
    fun `invalid measured channel never enables fallback`() {
        assertEquals(emptyList<Int>(), WppBluetoothCapabilityPolicy.channelsFor(
            WppSessionChannel.Measured(0), false
        ))
    }

    @Test
    fun `measured channel remains authoritative`() {
        assertEquals(
            listOf(2447),
            WppBluetoothCapabilityPolicy.channelsFor(WppSessionChannel.Measured(2447), false)
        )
    }

    @Test
    fun `unavailable channel on 2 point 4 GHz only unit uses fixed capabilities`() {
        assertEquals(
            listOf(2412, 2437, 2462),
            WppBluetoothCapabilityPolicy.channelsFor(
                WppSessionChannel.Unavailable("legacy API"), false
            )
        )
    }

    @Test
    fun `unavailable channel on 5 GHz capable unit remains unavailable`() {
        assertEquals(
            emptyList<Int>(),
            WppBluetoothCapabilityPolicy.channelsFor(
                WppSessionChannel.Unavailable("not measured"), true
            )
        )
    }
}
