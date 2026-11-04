package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.Route
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.ExternalBtTransportPolicy.WifiButton
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDiscoveryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HctRoutingTest {
    private fun snapshot(mode: ZbtDiscoveryPolicy.Mode, reachable: Boolean? = null) =
        ZbtDiscoveryPolicy.Snapshot(
            ZbtDiscoveryPolicy.Decision(mode, if (mode == ZbtDiscoveryPolicy.Mode.NONE) null else "module evidence"),
            reachable
        )

    private fun route(
        discovery: ZbtDiscoveryPolicy.Snapshot,
        zbt: Boolean = false,
        ignore: Boolean = false,
        blink: Boolean = false,
        fyt: String? = null,
        hct: Boolean = true
    ) = ExternalBtTransportPolicy.route(discovery, zbt, ignore, blink, fyt, hct)

    @Test
    fun `verified HCT works without ZBT markers and without probing a different daemon`() {
        for (mode in ZbtDiscoveryPolicy.Mode.values()) {
            for (reachable in listOf(null, false)) {
                val discovery = snapshot(mode, reachable)
                assertEquals(Route.HCT, route(discovery))
                assertFalse(ExternalBtTransportPolicy.needsDaemonMeasurement(
                    discovery, false, false, hctServiceAvailable = true
                ))
                assertFalse(ExternalBtTransportPolicy.refusesBringUp(
                    discovery, false, false, hctServiceAvailable = true
                ))
                assertFalse(ExternalBtTransportPolicy.remeasuresOnPress(
                    discovery, false, false, hctServiceAvailable = true
                ))
                assertEquals(WifiButton.MODULE, ExternalBtTransportPolicy.wifiButton(
                    discovery, false, false, hctServiceAvailable = true
                ))
            }
        }
    }

    @Test
    fun `measured ZBT and its guarded manual override retain precedence`() {
        for (mode in listOf(ZbtDiscoveryPolicy.Mode.REACHABILITY, ZbtDiscoveryPolicy.Mode.IDENTITY)) {
            assertEquals(Route.ZBT, route(snapshot(mode, true)))
            assertEquals(Route.ZBT, route(snapshot(mode, false), zbt = true))
            assertEquals(Route.ZBT, route(snapshot(mode), zbt = true, ignore = true))
        }
        // A stale toggle/result without ZBT evidence must not override the verified service.
        assertEquals(Route.HCT, route(snapshot(ZbtDiscoveryPolicy.Mode.NONE), zbt = true))
        assertEquals(Route.HCT, route(snapshot(ZbtDiscoveryPolicy.Mode.NONE, true)))
    }

    @Test
    fun `explicit Android radio override wins over automatic HCT`() {
        for (mode in ZbtDiscoveryPolicy.Mode.values()) {
            val discovery = snapshot(mode)
            assertEquals(Route.NORMAL, route(discovery, ignore = true))
            assertEquals(WifiButton.ANDROID_RADIO, ExternalBtTransportPolicy.wifiButton(
                discovery, false, true, hctServiceAvailable = true
            ))
        }
    }

    @Test
    fun `FYT and BLINK retain their own service selection`() {
        val discovery = snapshot(ZbtDiscoveryPolicy.Mode.REACHABILITY, true)
        assertEquals(Route.BLOCKED, route(discovery, zbt = true, fyt = "FYT"))
        assertEquals(Route.NORMAL, route(discovery, zbt = true, ignore = true, fyt = "FYT"))
        assertEquals(Route.BLINK, route(discovery, zbt = true, ignore = true, blink = true, fyt = "FYT"))
        assertEquals(WifiButton.REFUSED, ExternalBtTransportPolicy.wifiButton(
            discovery, true, false, fytModuleEvidence = "FYT", hctServiceAvailable = true
        ))
    }

    @Test
    fun `missing verified HCT preserves normal fallback and measured refusal`() {
        assertEquals(Route.NORMAL, route(snapshot(ZbtDiscoveryPolicy.Mode.NONE), hct = false))
        assertEquals(Route.NORMAL, route(snapshot(ZbtDiscoveryPolicy.Mode.IDENTITY, false), hct = false))
        assertEquals(Route.BLOCKED, route(snapshot(ZbtDiscoveryPolicy.Mode.REACHABILITY, false), hct = false))
        assertTrue(ExternalBtTransportPolicy.needsDaemonMeasurement(
            snapshot(ZbtDiscoveryPolicy.Mode.IDENTITY), false, false
        ))
        assertTrue(ExternalBtTransportPolicy.remeasuresOnPress(
            snapshot(ZbtDiscoveryPolicy.Mode.REACHABILITY, false), false, false
        ))
    }

    @Test
    fun `HCT module rearming never opens Android radio controls`() {
        assertTrue(ExternalBtTransportPolicy.usesExternalModule(Route.HCT))
        assertTrue(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.HCT))
        assertFalse(ExternalBtTransportPolicy.usesExternalModule(Route.NORMAL))
        assertFalse(ExternalBtTransportPolicy.usesExternalModule(Route.BLOCKED))
        assertFalse(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.NORMAL))
        assertFalse(ExternalBtTransportPolicy.rearmsWithoutAndroidRadio(Route.ZBT))
    }
    @Test
    fun `an HCT service alone counts as a module, so this unit's own Bluetooth is offered`() {
        fun detected(hct: Boolean, fyt: String? = null) = ExternalBtTransportPolicy.externalModuleDetected(
            snapshot(ZbtDiscoveryPolicy.Mode.NONE), fyt, hct, false)
        assertTrue(detected(hct = true))
        assertTrue(detected(hct = false, fyt = "FYT"))
        assertFalse(detected(hct = false))
    }

}
