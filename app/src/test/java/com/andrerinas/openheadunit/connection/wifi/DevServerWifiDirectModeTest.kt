package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherAuto
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoMoreInteractions

class DevServerWifiDirectModeTest {
    private val manager = mock<WifiLauncherManager>()

    @Test fun restartDiscoveryDoesNotRepeatGroupBringUp() {
        WifiLauncherAuto(manager, true).restartDiscovery()
        verify(manager).startDiscovery()
        verifyNoMoreInteractions(manager)
    }

    @Test fun checkboxChangesLauncherConfigurationAndCapabilities() {
        val disabled = WifiLauncherAuto(manager, false)
        val enabled = WifiLauncherAuto(manager, true)
        assertFalse(disabled.hasWifiDirect())
        assertTrue(enabled.hasWifiDirect())
        assertTrue(enabled.hasLocalDiscovery())
        assertTrue(enabled.hasWirelessServer())
        assertFalse(disabled.hasSameStartConfiguration(enabled))
        assertTrue(enabled.hasSameStartConfiguration(WifiLauncherAuto(manager, true)))
        assertFalse(WifiLauncherMock.create(WifiLauncherMode.MANUAL).hasWifiDirect())
    }

    @Test fun healthyP2pSessionDoesNotRideStationWifi() {
        assertFalse(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherAuto(manager, true)))
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.DEVICE_SHUTDOWN, WifiLauncherAuto(manager, true)))
    }

    @Test fun exitRefusesAllAutomaticRearmsUntilUserStartsAgain() {
        assertTrue(WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = false))
        assertFalse(WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = true))
    }
}
