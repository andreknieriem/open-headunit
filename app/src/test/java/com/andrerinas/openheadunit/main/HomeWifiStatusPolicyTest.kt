package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.wifi.HeadunitServerNetwork
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Band
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.ConnectAction
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Facts
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWifiStatusPolicyTest {

    private val onPhone = Facts(wifiEnabled = true, connected = true, rawSsid = "\"Galaxy S24\"", frequencyMhz = 5180, rssiDbm = -65)

    private val noWifi = Facts(wifiEnabled = true, connected = false)
    private val wifiOff = Facts(wifiEnabled = false, connected = false)

    private fun line(facts: Facts, phoneHotspot: Boolean = true, cardIsWifi: Boolean = true) =
        HomeWifiStatusPolicy.line(phoneHotspot, cardIsWifi, facts)

    @Test
    fun `applies to Headunit Server Auto on the phone's hotspot and nothing else`() {
        for (mode in WifiLauncherMode.entries) for (network in HeadunitServerNetwork.entries) {
            assertEquals("$mode $network",
                mode == WifiLauncherMode.AUTO && network == HeadunitServerNetwork.PHONE_HOTSPOT,
                HomeWifiStatusPolicy.appliesTo(mode, network))
        }
    }

    @Test
    fun `shown only on the phone's hotspot while the card is the WiFi one`() {
        assertEquals(Kind.HIDDEN, line(onPhone, phoneHotspot = false).kind)
        assertEquals(Kind.HIDDEN, line(onPhone, cardIsWifi = false).kind)
        assertEquals(Kind.CONNECTED, line(onPhone).kind)
    }

    @Test
    fun `a connected unit shows the network, its band and its signal`() {
        val l = line(onPhone)
        assertEquals("Galaxy S24", l.ssid)
        assertEquals(Band.GHZ_5, l.band)
        assertEquals(70, l.signalPercent)
        assertEquals(3, l.bars)
        assertFalse(l.needsAttention)
    }

    @Test
    fun `without location access the name is left out, not made up`() {
        val l = line(onPhone.copy(rawSsid = "<unknown ssid>"))
        assertEquals(Kind.CONNECTED, l.kind)
        assertNull(l.ssid)
    }

    @Test
    fun `off and not connected are told apart, and both call for the scan`() {
        val off = line(Facts(wifiEnabled = false, connected = false))
        val away = line(Facts(wifiEnabled = true, connected = false))
        assertEquals(Kind.OFF, off.kind)
        assertEquals(Kind.NOT_CONNECTED, away.kind)
        assertTrue(off.needsAttention)
        assertTrue(away.needsAttention)
    }

    @Test
    fun `on WiFi the main button does what it always did`() {
        assertEquals(ConnectAction.CONNECT, HomeWifiStatusPolicy.connectAction(onPhone, unitHotspotUp = false))
    }

    @Test
    fun `without WiFi the main button gets the unit onto the hotspot first`() {
        assertEquals(ConnectAction.SCAN_AND_WAIT, HomeWifiStatusPolicy.connectAction(noWifi, unitHotspotUp = false))
        assertEquals(ConnectAction.TURN_WIFI_ON_AND_WAIT, HomeWifiStatusPolicy.connectAction(wifiOff, unitHotspotUp = false))
    }

    @Test
    fun `an access point of the unit's own leaves the main button as it was`() {
        for (facts in listOf(onPhone, noWifi, wifiOff)) {
            assertEquals(ConnectAction.CONNECT, HomeWifiStatusPolicy.connectAction(facts, unitHotspotUp = true))
        }
    }

    @Test
    fun `the wait ends on a joined network`() {
        assertTrue(HomeWifiStatusPolicy.waitJoined(onPhone))
        assertFalse(HomeWifiStatusPolicy.waitJoined(noWifi))
        assertFalse(HomeWifiStatusPolicy.waitJoined(wifiOff))
    }

    @Test
    fun `the home scans by itself only when asked to and only while it can help`() {
        assertTrue(HomeWifiStatusPolicy.shouldAutoScan(true, noWifi, unitHotspotUp = false, sessionActive = false))
        assertFalse("not turned on", HomeWifiStatusPolicy.shouldAutoScan(false, noWifi, false, false))
        assertFalse("already on WiFi", HomeWifiStatusPolicy.shouldAutoScan(true, onPhone, false, false))
        assertFalse("WiFi off: a scan is refused", HomeWifiStatusPolicy.shouldAutoScan(true, wifiOff, false, false))
        assertFalse("its own access point", HomeWifiStatusPolicy.shouldAutoScan(true, noWifi, true, false))
        assertFalse("a session", HomeWifiStatusPolicy.shouldAutoScan(true, noWifi, false, true))
    }

    @Test
    fun `automatic scans are brisk for two minutes, then once a minute`() {
        assertEquals(35_000L, HomeWifiStatusPolicy.autoScanDelayMs(0))
        assertEquals(35_000L, HomeWifiStatusPolicy.autoScanDelayMs(119_999))
        assertEquals(60_000L, HomeWifiStatusPolicy.autoScanDelayMs(120_000))
        assertEquals(60_000L, HomeWifiStatusPolicy.autoScanDelayMs(3_600_000))
    }

    @Test
    fun `no pace of the home's own outruns Android's four scans in two minutes`() {
        // The worst case: the brisk pace from the first scan on, which is at once.
        val scansInTwoMinutes = 1 + (120_000L - 1) / HomeWifiStatusPolicy.RESCAN_MS
        assertTrue("$scansInTwoMinutes", scansInTwoMinutes <= 4)
        assertTrue(HomeWifiStatusPolicy.RESCAN_MS < HomeWifiStatusPolicy.JOIN_WAIT_MS)
    }

    @Test
    fun `bands by frequency`() {
        assertEquals(Band.GHZ_2_4, HomeWifiStatusPolicy.band(2437))
        assertEquals(Band.GHZ_5, HomeWifiStatusPolicy.band(5200))
        assertEquals(Band.GHZ_6, HomeWifiStatusPolicy.band(5955))
        assertNull(HomeWifiStatusPolicy.band(null))
        assertNull(HomeWifiStatusPolicy.band(-1))
    }

    @Test
    fun `signal runs from 0 at -100 dBm to 100 at -50 dBm`() {
        assertEquals(0, HomeWifiStatusPolicy.signalPercent(-100))
        assertEquals(0, HomeWifiStatusPolicy.signalPercent(-110))
        assertEquals(50, HomeWifiStatusPolicy.signalPercent(-75))
        assertEquals(100, HomeWifiStatusPolicy.signalPercent(-50))
        assertEquals(100, HomeWifiStatusPolicy.signalPercent(-30))
        assertNull("Android's no-reading value", HomeWifiStatusPolicy.signalPercent(-127))
        assertNull(HomeWifiStatusPolicy.signalPercent(null))
    }

    @Test
    fun `four bars by quarter, none without a reading`() {
        assertEquals(0, HomeWifiStatusPolicy.barsFor(null))
        assertEquals(0, HomeWifiStatusPolicy.barsFor(0))
        assertEquals(1, HomeWifiStatusPolicy.barsFor(10))
        assertEquals(2, HomeWifiStatusPolicy.barsFor(25))
        assertEquals(3, HomeWifiStatusPolicy.barsFor(74))
        assertEquals(4, HomeWifiStatusPolicy.barsFor(75))
        assertEquals(4, HomeWifiStatusPolicy.barsFor(100))
    }
}
