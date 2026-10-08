package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherAuto
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock

class LinkLossTeardownPolicyTest {

    @Test
    fun `the phone server closes on wifi off even when the launcher hosts P2P`() {
        val launcher = WifiLauncherAuto(mock(), true)
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, launcher,
            sessionIsWireless = true, peerIsHeadUnitServer = true
        ))
        assertFalse(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, launcher,
            sessionIsWireless = false, peerIsHeadUnitServer = true
        ))
    }

    @Test
    fun `the exception follows the session peer not just the selected P2P mode`() {
        val launcher = WifiLauncherAuto(mock(), true)
        assertFalse(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, launcher,
            sessionIsWireless = true, peerIsHeadUnitServer = false
        ))
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, launcher = null,
            sessionIsWireless = true, peerIsHeadUnitServer = true
        ))
    }

    @Test
    fun `a device shutdown takes every route down, so every route closes first`() {
        for (mode in 1..3) {
            for (strategy in 0..4) {
                for (transport in NativeStrategy.entries) {
                    val launcher = WifiLauncherMock.create(
                        WifiLauncherMode.byIdOrDefault(mode),
                        HelperStrategy.byIdOrDefault(strategy),
                        transport)

                    assertTrue(
                        "mode=$mode strategy=$strategy transport=$transport",
                        LinkLossTeardownPolicy.shouldTearDown(
                            LinkLossTrigger.DEVICE_SHUTDOWN, launcher
                        )
                    )
                }
            }
        }
    }

    @Test
    fun `station wifi going down closes the routes that ride it`() {
        // Mode 1 (NSD) and mode 2 strategies 0 and 3 all reach the phone over station WiFi.
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.AUTO, HelperStrategy.COMMON_WIFI)))
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.HELPER, HelperStrategy.COMMON_WIFI)))
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.HELPER, HelperStrategy.PHONE_HOTSPOT)))
    }

    @Test
    fun `station wifi going down leaves a wifi direct session alone`() {
        // A P2P group is a separate interface and survives the station toggle on several chipsets.
        // Tearing this down would cost a 45-90s reconnect to prevent nothing.
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING,
                WifiLauncherMock.create(
                    WifiLauncherMode.NATIVE,
                    HelperStrategy.COMMON_WIFI,
                    NativeStrategy.WIFI_DIRECT
                )
            )
        )
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.HELPER,
            HelperStrategy.WIFI_DIRECT)))
    }

    @Test
    fun `station wifi going down leaves a session on our own access point alone`() {
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, NativeStrategy.HOTSPOT)
            )
        )
        // Mode 2 strategy 4 is the head unit hotspot: same reasoning, different route.
        assertFalse(LinkLossTeardownPolicy.shouldTearDown(LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.HELPER, HelperStrategy.HEADUNIT_HOTSPOT)))
    }

    @Test
    fun `a usb session is left alone by a wifi toggle, whatever wireless mode is stored`() {
        // The wireless mode is a setting, not a description of the session. A USB drive with mode 1
        // stored would otherwise be ended by the user switching WiFi off.
        for (mode in 1..3) {
            for (strategy in 0..4) {
                assertFalse(
                    "mode=$mode strategy=$strategy",
                    LinkLossTeardownPolicy.shouldTearDown(
                        LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.byIdOrDefault(mode),
                        HelperStrategy.byIdOrDefault(strategy)),
                        sessionIsWireless = false
                    )
                )
            }
        }
    }

    @Test
    fun `a device shutdown still closes a usb session`() {
        // Nothing survives the shutdown, and the phone's head unit server is wedged just as hard by
        // a USB session that vanishes as by a wireless one.
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.DEVICE_SHUTDOWN,
                WifiLauncherMock.create(WifiLauncherMode.AUTO, HelperStrategy.COMMON_WIFI),
                sessionIsWireless = false
            )
        )
    }

    @Test
    fun `the wifi answer is the exact complement of the two routes that own their own network`() {
        // Guards the pairing with WifiModePolicy: a combination must not be claimed by both, and
        // a station-WiFi combination must not be missed by both. Stated for a wireless session,
        // which is the only kind the complement is about.
        for (mode in 1..3) {
            for (strategy in 0..4) {
                for (transport in NativeStrategy.entries) {
                    val tearsDown = LinkLossTeardownPolicy.shouldTearDown(
                        LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherMock.create(WifiLauncherMode.byIdOrDefault(mode),
                        HelperStrategy.byIdOrDefault(strategy), transport),
                        sessionIsWireless = true
                    )
                    val ownsItsNetwork =
                        WifiModePolicy.usesWifiDirect(mode, strategy, transport) ||
                            (mode == 3 && transport == NativeStrategy.HOTSPOT) ||
                            (mode == 2 && strategy == 4)
                    assertTrue(
                        "mode=$mode strategy=$strategy transport=$transport",
                        tearsDown != ownsItsNetwork
                    )
                }
            }
        }
    }

    /**
     * A wired session quiesces the wireless stack, so `active` is null. A shutdown in that window
     * still has a session to close, and the early return that used to guard this call site skipped
     * the whole orderly teardown - leaving the phone's head unit server holding a peer that never
     * came back, which is the exact failure this policy exists to prevent.
     */
    @Test
    fun `a shutdown with no wireless route armed still tears the session down`() {
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.DEVICE_SHUTDOWN, launcher = null, sessionIsWireless = false
            )
        )
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.DEVICE_SHUTDOWN, launcher = null, sessionIsWireless = true
            )
        )
    }

    @Test
    fun `WiFi going away with no wireless route armed is decided by the session alone`() {
        // Nothing of ours is hosting a network, so there is none of ours to outlive the toggle.
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, launcher = null, sessionIsWireless = true
            )
        )
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.WIFI_STATION_DISABLING, launcher = null, sessionIsWireless = false
            )
        )
    }

    @Test
    fun `a named ACC-off closes every route, because the whole board is going`() {
        for (transport in NativeStrategy.entries) {
            val launcher = WifiLauncherMock.create(
                WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, transport)

            assertTrue(
                "transport=$transport",
                LinkLossTeardownPolicy.shouldTearDown(
                    LinkLossTrigger.ACC_POWER_LOST, launcher,
                    peerIsHeadUnitServer = false, accSignalIsExplicit = true
                )
            )
        }
        // USB rides none of the wireless stack and still has a session to close.
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.ACC_POWER_LOST, launcher = null,
                sessionIsWireless = false, accSignalIsExplicit = true
            )
        )
    }

    @Test
    fun `an inferred ACC-off closes only the head unit server route`() {
        // Power lost plus the screen going is a guess. It is worth acting on where a missed close
        // outlives the drive and coming back is one gateway dial.
        assertTrue(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.ACC_POWER_LOST,
                WifiLauncherMock.create(WifiLauncherMode.AUTO, HelperStrategy.COMMON_WIFI),
                peerIsHeadUnitServer = true, accSignalIsExplicit = false
            )
        )
    }

    @Test
    fun `an inferred ACC-off leaves a native session alone`() {
        // The regression this guards: a charger unplugged beside a screen blank must not cost a
        // Native AA session its 45-90s reconnect.
        for (transport in NativeStrategy.entries) {
            assertFalse(
                "transport=$transport",
                LinkLossTeardownPolicy.shouldTearDown(
                    LinkLossTrigger.ACC_POWER_LOST,
                    WifiLauncherMock.create(
                        WifiLauncherMode.NATIVE, HelperStrategy.COMMON_WIFI, transport),
                    peerIsHeadUnitServer = false, accSignalIsExplicit = false
                )
            )
        }
    }

    @Test
    fun `ACC-off needs one of the two signals, and neither means nothing happens`() {
        assertFalse(
            LinkLossTeardownPolicy.shouldTearDown(
                LinkLossTrigger.ACC_POWER_LOST, launcher = null,
                peerIsHeadUnitServer = false, accSignalIsExplicit = false
            )
        )
    }

    @Test
    fun `the new arguments do not change what the older triggers answer`() {
        val launcher = WifiLauncherMock.create(WifiLauncherMode.AUTO, HelperStrategy.COMMON_WIFI)
        for (peer in listOf(true, false)) {
            for (explicit in listOf(true, false)) {
                assertTrue(
                    LinkLossTeardownPolicy.shouldTearDown(
                        LinkLossTrigger.DEVICE_SHUTDOWN, launcher,
                        peerIsHeadUnitServer = peer, accSignalIsExplicit = explicit
                    )
                )
                assertTrue(
                    LinkLossTeardownPolicy.shouldTearDown(
                        LinkLossTrigger.WIFI_STATION_DISABLING, launcher,
                        peerIsHeadUnitServer = peer, accSignalIsExplicit = explicit
                    )
                )
            }
        }
    }
}
