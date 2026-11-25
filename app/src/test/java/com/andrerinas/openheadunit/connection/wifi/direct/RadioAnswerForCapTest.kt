package com.andrerinas.openheadunit.connection.wifi.direct

import android.app.Application
import android.content.Context
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.SettingsBackupManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
@ConscryptMode(ConscryptMode.Mode.OFF)
class RadioAnswerForCapTest {

    private val settings = Settings(RuntimeEnvironment.getApplication())
    private val info = mock(WifiInfo::class.java)
    private val wifi = mock(WifiManager::class.java).also {
        @Suppress("DEPRECATION")
        `when`(it.connectionInfo).thenReturn(info)
    }
    private val context = mock(Context::class.java).also {
        `when`(it.applicationContext).thenReturn(it)
        `when`(it.getSystemService(Context.WIFI_SERVICE)).thenReturn(wifi)
    }

    @Before
    fun hotspotOnAUnitThatDeniesFiveGhz() {
        settings.wifiConnectionMode = WifiLauncherMode.NATIVE
        settings.nativeApStrategy = NativeStrategy.HOTSPOT
        settings.useSystemHotspot = true
        settings.hotspotBand = 0
        settings.radioSeenAbove24Ghz = false
        `when`(wifi.is5GHzBandSupported).thenReturn(false)
        `when`(info.frequency).thenReturn(-1)
    }

    private fun answer() = WifiBandCapability.radioAnswerForCap(context, settings)

    @Test
    fun `with nothing known the denial stands`() {
        assertEquals(false, answer())
        assertFalse(settings.radioSeenAbove24Ghz)
    }

    @Test
    fun `a station on 5 GHz disproves it, and the fact outlives the station`() {
        `when`(info.frequency).thenReturn(5785)
        assertNull(answer())
        `when`(info.frequency).thenReturn(-1)
        assertNull(answer())
        assertTrue(settings.radioSeenAbove24Ghz)
    }

    @Test
    fun `a station on 2_4 GHz disproves nothing`() {
        `when`(info.frequency).thenReturn(2437)
        assertEquals(false, answer())
        assertFalse(settings.radioSeenAbove24Ghz)
    }

    @Test
    fun `5 GHz only counts on the system hotspot and not on the local one`() {
        settings.hotspotBand = 1
        assertNull(answer())
        settings.useSystemHotspot = false
        assertEquals(false, answer())
    }

    @Test
    fun `2_4 GHz only and automatic leave the denial`() {
        settings.hotspotBand = 2
        assertEquals(false, answer())
        settings.radioSeenAbove24Ghz = true
        assertEquals(false, answer())
    }

    @Test
    fun `the stored 2_4 GHz choice says nothing about the local hotspot`() {
        settings.hotspotBand = 2
        settings.useSystemHotspot = false
        settings.radioSeenAbove24Ghz = true
        assertNull(answer())
    }

    @Test
    fun `another wireless mode keeps the denial`() {
        settings.radioSeenAbove24Ghz = true
        for (mode in WifiLauncherMode.values().filter { it != WifiLauncherMode.NATIVE }) {
            settings.wifiConnectionMode = mode
            assertEquals(mode.name, false, answer())
        }
    }

    @Test
    fun `WiFi Direct keeps the denial`() {
        settings.radioSeenAbove24Ghz = true
        settings.nativeApStrategy = NativeStrategy.WIFI_DIRECT
        assertEquals(false, answer())
    }

    @Test
    fun `the fact about this radio is not exported`() {
        settings.radioSeenAbove24Ghz = true
        assertFalse(SettingsBackupManager.exportFromContext(RuntimeEnvironment.getApplication()).contains("radio-seen-above-24ghz"))
    }
}
