package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WirelessRearmPolicyTest {

    @Test
    fun `headunit server P2P checkbox re-arms the running configuration`() {
        val before = config(mode = WifiLauncherMode.AUTO)
        val after = before.copy(headunitServerWifiDirect = true)
        assertTrue(WirelessRearmPolicy.requiresRearm(before, after))
        assertTrue(WirelessRearmPolicy.requiresRearm(after, before))
        assertFalse(WirelessRearmPolicy.requiresRearm(after, after.copy()))
    }

    private fun config(
        mode: WifiLauncherMode = WifiLauncherMode.NATIVE,
        helper: HelperStrategy = HelperStrategy.NEARBY_DEVICES,
        native: NativeStrategy = NativeStrategy.WIFI_DIRECT,
        bluetoothService: String = "bluetooth_manager",
        wirelessSelected: Boolean = true,
        band: Int = 0,
        channel: Int = 0,
        zbtTransport: Boolean = false,
        ignoreExternalBt: Boolean = false,
        autoEnableHotspot: Boolean = false,
        insecureRfcomm: Boolean = false,
    ) = WirelessRearmPolicy.Config(
        wifiConnectionMode = mode,
        helperConnectionStrategy = helper,
        nativeApStrategy = native,
        bluetoothManagerServiceName = bluetoothService,
        wirelessSelected = wirelessSelected,
        wifiDirectBand = band,
        fiveGhzChannel = channel,
        externalBtZbtTransport = zbtTransport,
        nativeAaIgnoreExternalBt = ignoreExternalBt,
        autoEnableHotspot = autoEnableHotspot,
        insecureAaRfcommListener = insecureRfcomm,
    )

    @Test
    fun `an unchanged configuration re-arms nothing`() {
        assertFalse(WirelessRearmPolicy.requiresRearm(config(), config()))
    }

    @Test
    fun `the wireless mode re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(mode = WifiLauncherMode.HELPER))
        )
    }

    @Test
    fun `the helper strategy re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(helper = HelperStrategy.WIFI_DIRECT))
        )
    }

    /** The one that was missing: a saved hotspot left the launcher hosting a P2P group. */
    @Test
    fun `the native transport re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(native = NativeStrategy.HOTSPOT))
        )
    }

    @Test
    fun `the Bluetooth service name re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(bluetoothService = "syu_bt"))
        )
    }

    /** Unchecking WiFi in Connection mode has to reach the running stack, not wait for a restart. */
    @Test
    fun `dropping wireless from the chosen connection modes re-arms`() {
        assertTrue(
            WirelessRearmPolicy.requiresRearm(config(), config(wirelessSelected = false))
        )
    }

    /**
     * The six below were applied only as a side effect of the settings screen closing, which used
     * to re-arm unconditionally. Saving them now has to ask for the re-arm itself.
     */
    @Test
    fun `the WiFi Direct band re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(band = 2)))
    }

    @Test
    fun `the 5 GHz channel re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(channel = 149)))
    }

    @Test
    fun `the external Bluetooth module transport re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(zbtTransport = true)))
    }

    @Test
    fun `ignoring the external Bluetooth module re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(ignoreExternalBt = true)))
    }

    @Test
    fun `the hotspot auto-enable re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(autoEnableHotspot = true)))
    }

    @Test
    fun `the insecure RFCOMM listener re-arms`() {
        assertTrue(WirelessRearmPolicy.requiresRearm(config(), config(insecureRfcomm = true)))
    }
}
