package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Band
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Facts
import com.andrerinas.openheadunit.main.HomeWifiStatusPolicy.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeWifiStatusPolicyTest {

    private val onPhone = Facts(wifiEnabled = true, connected = true, rawSsid = "\"Galaxy S24\"", frequencyMhz = 5180, rssiDbm = -65)

    private fun line(facts: Facts, headunitServer: Boolean = true, cardIsWifi: Boolean = true) =
        HomeWifiStatusPolicy.line(headunitServer, cardIsWifi, facts)

    @Test
    fun `shown only for Headunit Server while the card is the WiFi one`() {
        assertEquals(Kind.HIDDEN, line(onPhone, headunitServer = false).kind)
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
    fun `the scan button shows while the card is idle or only listening`() {
        val l = line(Facts(wifiEnabled = true, connected = false))
        for (status in listOf(HomePrimaryPolicy.Status.READY, HomePrimaryPolicy.Status.SEARCHING,
            HomePrimaryPolicy.Status.WAITING, HomePrimaryPolicy.Status.PHONE_DETECTED)) {
            assertTrue(status.name, HomeWifiStatusPolicy.showsScanButton(l, HomeWifiStatusPolicy.cardIdle(status)))
        }
    }

    @Test
    fun `the scan button stays out of a bring-up, the countdown and a session`() {
        val l = line(onPhone)
        for (status in listOf(HomePrimaryPolicy.Status.CONNECTING, HomePrimaryPolicy.Status.RECONNECTING,
            HomePrimaryPolicy.Status.ACTIVE_SESSION)) {
            assertFalse(status.name, HomeWifiStatusPolicy.showsScanButton(l, HomeWifiStatusPolicy.cardIdle(status)))
        }
        assertFalse("never outside Headunit Server",
            HomeWifiStatusPolicy.showsScanButton(line(onPhone, headunitServer = false), cardIdle = true))
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

    @Test
    fun `Scan WiFi keeps its label while the row holds both buttons`() {
        assertFalse(HomeWifiStatusPolicy.scanCompact(availablePx = 1644, actionNeededPx = 360, gapPx = 20, scanFullPx = 289, othersPx = 0))
        assertTrue("a portrait phone", HomeWifiStatusPolicy.scanCompact(availablePx = 873, actionNeededPx = 640, gapPx = 60, scanFullPx = 869, othersPx = 0))
        assertTrue("Cancel beside them", HomeWifiStatusPolicy.scanCompact(availablePx = 900, actionNeededPx = 360, gapPx = 20, scanFullPx = 289, othersPx = 300))
        assertFalse("not measured yet", HomeWifiStatusPolicy.scanCompact(availablePx = 0, actionNeededPx = 360, gapPx = 20, scanFullPx = 289, othersPx = 0))
    }
}
