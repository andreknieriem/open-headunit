package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserExitHotspotPolicyTest {

    private fun exit(
        mode: WifiLauncherMode,
        helperStrategy: HelperStrategy = HelperStrategy.NEARBY_DEVICES,
        nativeStrategy: NativeStrategy = NativeStrategy.WIFI_DIRECT,
        autoEnableHotspot: Boolean = true,
        teardownProvenUnsafe: Boolean = false
    ) = UserExitHotspotPolicy.onUserExit(
        mode, helperStrategy, nativeStrategy, autoEnableHotspot, teardownProvenUnsafe
    )

    @Test
    fun `native AA on this unit's own access point takes it down when the app may drive it`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = WifiLauncherMode.NATIVE, nativeStrategy = NativeStrategy.HOTSPOT, autoEnableHotspot = true)
        )
    }

    @Test
    fun `the helper's head-unit-hotspot strategy is the same route by another name`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = WifiLauncherMode.HELPER, helperStrategy = HelperStrategy.HEADUNIT_HOTSPOT, autoEnableHotspot = true)
        )
    }

    @Test
    fun `an access point the app was never given charge of is left up, not switched off`() {
        // Switching one back on is best effort and usually impossible without WRITE_SETTINGS, so a
        // hotspot the user turned on by hand could be left off with nothing able to restore it.
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(mode = WifiLauncherMode.NATIVE, nativeStrategy = NativeStrategy.HOTSPOT, autoEnableHotspot = false)
        )
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(mode = WifiLauncherMode.HELPER, helperStrategy = HelperStrategy.HEADUNIT_HOTSPOT, autoEnableHotspot = false)
        )
    }

    @Test
    fun `WiFi Direct routes are never this policy's to answer`() {
        // The caller removes the P2P group on these; both branches firing for one disconnect would
        // tear down the group and the hotspot at once.
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = WifiLauncherMode.NATIVE, nativeStrategy = NativeStrategy.WIFI_DIRECT, autoEnableHotspot = true)
        )
        assertEquals(HotspotExitAction.NONE, exit(mode = WifiLauncherMode.HELPER, helperStrategy = HelperStrategy.WIFI_DIRECT, autoEnableHotspot = true))
    }

    @Test
    fun `the helper's other strategies and the server mode are left alone`() {
        for (strategy in listOf(HelperStrategy.COMMON_WIFI, HelperStrategy.NEARBY_DEVICES, HelperStrategy.PHONE_HOTSPOT)) {
            assertEquals(
                "helper strategy $strategy hosts no access point",
                HotspotExitAction.NONE,
                exit(mode = WifiLauncherMode.HELPER, helperStrategy = strategy)
            )
        }
        assertEquals(HotspotExitAction.NONE, exit(mode = WifiLauncherMode.AUTO, helperStrategy = HelperStrategy.COMMON_WIFI))
    }

    @Test
    fun `the transport only speaks for native mode`() {
        // Mode 2 keeps its own selector; reading the native transport there would make strategy 4
        // depend on a setting that has no meaning in that mode.
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(mode = WifiLauncherMode.HELPER, helperStrategy = HelperStrategy.HEADUNIT_HOTSPOT, nativeStrategy = NativeStrategy.WIFI_DIRECT)
        )
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = WifiLauncherMode.HELPER, helperStrategy = HelperStrategy.COMMON_WIFI, nativeStrategy = NativeStrategy.HOTSPOT)
        )
    }

    @Test
    fun `a device that could not switch its access point back on is not asked to again`() {
        // Learned the only way it can be — by trying once and watching it fail. From then on the
        // hotspot is left alone even though the user gave the app charge of it, because on such a
        // device taking it down costs them a hotspot per session and never buys the phone leaving.
        assertEquals(
            HotspotExitAction.WARN_LEFT_UP,
            exit(
                mode = WifiLauncherMode.NATIVE,
                nativeStrategy = NativeStrategy.HOTSPOT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = true
            )
        )
    }

    @Test
    fun `the flag does not make this policy claim a disconnect that was never its own`() {
        // It withdraws; it never reaches. A WiFi Direct disconnect stays WiFi Direct's.
        assertEquals(
            HotspotExitAction.NONE,
            exit(
                mode = WifiLauncherMode.NATIVE,
                nativeStrategy = NativeStrategy.WIFI_DIRECT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = true
            )
        )
        assertEquals(
            HotspotExitAction.NONE,
            exit(mode = WifiLauncherMode.AUTO, autoEnableHotspot = true, teardownProvenUnsafe = true)
        )
    }

    @Test
    fun `an untried device is still asked, so the flag costs nothing until it is earned`() {
        assertEquals(
            HotspotExitAction.DISABLE,
            exit(
                mode = WifiLauncherMode.NATIVE,
                nativeStrategy = NativeStrategy.HOTSPOT,
                autoEnableHotspot = true,
                teardownProvenUnsafe = false
            )
        )
    }

    @Test
    fun `this policy and usesWifiDirect never both claim the same disconnect`() {
        for (mode in WifiLauncherMode.entries) {
            if (mode == WifiLauncherMode.MANUAL)
                continue

            for (strategy in HelperStrategy.entries) {
                for (transport in NativeStrategy.entries) {
                    val hotspot = UserExitHotspotPolicy.usesHeadUnitHotspot(mode, strategy, transport)
                    val p2p = WifiModePolicy.usesWifiDirect(mode, strategy, transport)
                    assertFalse("mode=$mode strategy=$strategy transport=$transport", hotspot && p2p)
                }
            }
        }
        // Not vacuous: each does claim something.
        assertTrue(UserExitHotspotPolicy.usesHeadUnitHotspot(WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, NativeStrategy.HOTSPOT))
        assertTrue(WifiModePolicy.usesWifiDirect(WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, NativeStrategy.WIFI_DIRECT))
    }

    /**
     * The other half of "exact complements", and the one that goes wrong silently.
     *
     * "Never both" stays true if one arm is deleted outright, which is how the soft-AP half came to
     * have no caller while this test file was still green. Every route where the phone is sitting
     * on a network this device is hosting has to be claimed by one of the two, because being
     * claimed by neither means the network is left up and the phone never leaves it.
     */
    @Test
    fun `every route that hosts the phone's network is claimed by one of the two`() {
        val hosting = listOf(
            Triple(WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, NativeStrategy.WIFI_DIRECT),
            Triple(WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, NativeStrategy.HOTSPOT),
            Triple(WifiLauncherMode.HELPER, HelperStrategy.WIFI_DIRECT, NativeStrategy.WIFI_DIRECT),
            Triple(WifiLauncherMode.HELPER, HelperStrategy.HEADUNIT_HOTSPOT, NativeStrategy.WIFI_DIRECT)
        )
        for ((mode, strategy, transport) in hosting) {
            assertTrue(
                "mode=$mode strategy=$strategy transport=$transport hosts the network and nothing takes it down",
                WifiModePolicy.usesWifiDirect(mode, strategy, transport) ||
                    UserExitHotspotPolicy.usesHeadUnitHotspot(mode, strategy, transport)
            )
        }
    }

    @Test
    fun `the launcher and the settings answer the hotspot question the same way`() {
        // The settings form is asked after the launcher has been stopped, so the two cannot be one
        // function. Nothing held them to each other until now, and the launcher-side copy was a
        // private duplicate inside LinkLossTeardownPolicy.
        for (mode in WifiLauncherMode.values()) {
            for (helper in HelperStrategy.values()) {
                for (native in NativeStrategy.values()) {
                    assertEquals(
                        "$mode/$helper/$native",
                        UserExitHotspotPolicy.usesHeadUnitHotspot(mode, helper, native),
                        WifiModePolicy.hostsOwnAccessPoint(mode, helper, native)
                    )
                }
            }
        }
    }

    @Test
    fun `hosting an access point and running a group remain exact complements`() {
        for (mode in WifiLauncherMode.values()) {
            for (helper in HelperStrategy.values()) {
                for (native in NativeStrategy.values()) {
                    val hostsAp = WifiModePolicy.hostsOwnAccessPoint(mode, helper, native)
                    val p2p = WifiModePolicy.usesWifiDirect(mode, helper, native)
                    assertFalse("$mode/$helper/$native claims both", hostsAp && p2p)
                }
            }
        }
    }
}
