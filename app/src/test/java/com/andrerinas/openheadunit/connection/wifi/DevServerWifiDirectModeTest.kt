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
        assertFalse(disabled.usesServerWifiDirect())
        assertTrue(enabled.usesServerWifiDirect())
        assertTrue(enabled.hasLocalDiscovery())
        assertTrue(enabled.hasWirelessServer())
        assertFalse(disabled.hasSameStartConfiguration(enabled))
        assertTrue(enabled.hasSameStartConfiguration(WifiLauncherAuto(manager, true)))
        assertFalse(WifiLauncherMock.create(WifiLauncherMode.MANUAL).hasWifiDirect())
    }

    @Test fun wifiOffClosesPhoneServerEvenOverP2p() {
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.WIFI_STATION_DISABLING, WifiLauncherAuto(manager, true),
            peerIsHeadUnitServer = true))
        assertTrue(LinkLossTeardownPolicy.shouldTearDown(
            LinkLossTrigger.DEVICE_SHUTDOWN, WifiLauncherAuto(manager, true)))
    }

    @Test fun serverRouteIsCapturedInLauncherWithoutReadingLiveSettings() {
        val enabled: WifiLauncher = WifiLauncherAuto(manager, true)
        val disabled: WifiLauncher = WifiLauncherAuto(manager, false)
        repeat(3) {
            assertTrue(enabled.usesServerWifiDirect())
            assertFalse(disabled.usesServerWifiDirect())
        }
        verifyNoMoreInteractions(manager)
    }

    @Test fun automaticStopDoesNotLatchUserCancel() {
        val realManager = WifiLauncherManager(mock())
        realManager.stop()
        assertFalse(realManager.cancelledByUser)
        assertFalse(WirelessCancelPolicy.refusesBringUp(realManager.cancelledByUser, userRequested = false))
    }

    @Test fun ordinaryStopDoesNotClearAnExplicitUserCancel() {
        val realManager = WifiLauncherManager(mock())
        realManager.stopForUser()
        realManager.stop()
        assertTrue(realManager.cancelledByUser)
        assertTrue(WirelessCancelPolicy.refusesBringUp(realManager.cancelledByUser, userRequested = false))
    }

    @Test fun exitRefusesAllAutomaticRearmsUntilUserStartsAgain() {
        assertTrue(WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = false))
        assertFalse(WirelessCancelPolicy.refusesBringUp(cancelledByUser = true, userRequested = true))
    }
}
