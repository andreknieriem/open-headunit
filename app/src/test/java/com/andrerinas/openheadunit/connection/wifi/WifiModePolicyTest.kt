package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.native.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiModePolicyTest {

    @Test
    fun `native AA mode uses wifi direct regardless of strategy, which does not apply to it`() {
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 0))
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 1))
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 2))
    }

    @Test
    fun `native AA on the hotspot transport does not use wifi direct`() {
        // The caller force-disables the hotspot whenever this says true, so a wrong answer here
        // tears down the access point the hotspot route is about to hand the phone.
        assertFalse(
            WifiModePolicy.usesWifiDirect(mode = 3, strategy = 0, transport = NativeStrategy.HOTSPOT)
        )
        assertTrue(
            WifiModePolicy.usesWifiDirect(mode = 3, strategy = 0, transport = NativeStrategy.WIFI_DIRECT)
        )
    }

    @Test
    fun `the transport only applies to native AA mode`() {
        assertTrue(
            WifiModePolicy.usesWifiDirect(mode = 2, strategy = 1, transport = NativeStrategy.HOTSPOT)
        )
        assertFalse(
            WifiModePolicy.usesWifiDirect(mode = 2, strategy = 0, transport = NativeStrategy.WIFI_DIRECT)
        )
    }

    @Test
    fun `omitting the transport keeps the pre-existing answer`() {
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 3, strategy = 0))
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 1))
    }

    @Test
    fun `helper mode uses wifi direct only with strategy 1`() {
        assertTrue(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 1))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 0))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 2, strategy = 2))
    }

    @Test
    fun `server and other modes never use wifi direct`() {
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 0, strategy = 0))
        assertFalse(WifiModePolicy.usesWifiDirect(mode = 1, strategy = 1))
    }
}
