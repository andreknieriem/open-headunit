package com.andrerinas.openheadunit.main

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.HeadunitServerNetwork
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home with Headunit Server's WiFi line, on the phone's hotspot, laid out on the real layout
 * with the real painting and fitting code: on head unit screens, phones and tablets, in the day and
 * the dark palette, connected, not connected, scanning and waiting for the hotspot, nothing is cut
 * and nothing leaves the card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HomeWifiStatusLayoutTest {

    private enum class State { CONNECTED, NOT_CONNECTED, SCANNING, WAITING, OFF }

    private fun layOut(activity: AppCompatActivity, state: State, mode: HomeThemePolicy.Mode = HomeThemePolicy.Mode.DAY): View {
        val dm = activity.resources.displayMetrics
        val settings = Settings(activity).apply {
            wifiConnectionMode = WifiLauncherMode.AUTO
            headunitServerNetwork = HeadunitServerNetwork.PHONE_HOTSPOT
        }
        val root = FrameLayout(activity)
        activity.layoutInflater.inflate(R.layout.fragment_home, root, true)
        val decision = HomePrimaryPolicy.decide(HomePrimaryPolicy.Input(
            chosen = setOf(HomePrimaryPolicy.Connection.WIFI), lastConnection = HomePrimaryPolicy.Connection.WIFI,
            usbPhoneDetected = false, sessionActive = false, reconnectTarget = null, wifiScanning = false,
        ))
        val palette = HomeUiHelper.palette(activity, mode)
        HomeUiHelper.applyPrimaryCard(activity, root, settings, decision, null, 0, palette)
        HomeUiHelper.applyHotspotWait(activity, root, waiting = state == State.WAITING)
        val facts = when (state) {
            State.CONNECTED -> HomeWifiStatusPolicy.Facts(true, true, "\"Galaxy S24 Ultra\"", 5180, -60)
            State.OFF -> HomeWifiStatusPolicy.Facts(false, false)
            else -> HomeWifiStatusPolicy.Facts(true, false)
        }
        val line = HomeWifiStatusPolicy.line(
            HomeWifiStatusPolicy.appliesTo(settings.wifiConnectionMode, settings.headunitServerNetwork), true, facts)
        HomeUiHelper.applyWifiStatus(activity, root, settings, decision, palette, line,
            scanning = state == State.SCANNING || state == State.WAITING)
        for (pass in 0 until 8) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            if (!HomeUiHelper.fitCardWidth(activity, root)) break
        }
        return root
    }

    private fun ellipsized(v: TextView) = (0 until v.layout.lineCount).any { v.layout.getEllipsisCount(it) > 0 }

    private fun rightIn(root: View, v: View): Int {
        var x = v.right
        var p = v.parent as? View
        while (p != null && p !== root) { x += p.left; p = p.parent as? View }
        return x
    }

    /** One activity per screen and language, inflated into afresh for every case, then let go. */
    private fun assertFits() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.create().get()
        try {
            assertFits(activity)
        } finally {
            controller.destroy()
        }
    }

    private fun assertFits(activity: AppCompatActivity) {
        for (mode in listOf(HomeThemePolicy.Mode.DAY, HomeThemePolicy.Mode.DARK)) for (state in State.entries) {
            val what = "$state $mode"
            val root = layOut(activity, state, mode)
            val action = root.findViewById<TextView>(R.id.home_primary_action)
            val cancel = root.findViewById<TextView>(R.id.home_primary_cancel)
            val status = root.findViewById<View>(R.id.home_wifi_status)
            val card = root.findViewById<View>(R.id.home_primary_card)
            assertEquals("$what: the WiFi line shows", View.VISIBLE, status.visibility)
            assertTrue("$what: the network name is whole", !ellipsized(root.findViewById(R.id.home_wifi_name)))
            assertTrue("$what: the line under it is whole", !ellipsized(root.findViewById(R.id.home_wifi_meta)))
            assertTrue("$what: the WiFi line stays inside the card", rightIn(root, status) <= rightIn(root, card))
            if (state == State.WAITING) {
                assertEquals("$what: Cancel stands where the main button was", View.GONE, action.visibility)
                assertEquals(View.VISIBLE, cancel.visibility)
                assertTrue("$what: Cancel is whole", !ellipsized(cancel))
                assertTrue("$what: the subtitle says what it waits for", !ellipsized(root.findViewById(R.id.home_primary_subtitle)))
            } else {
                assertEquals(what, View.VISIBLE, action.visibility)
                assertEquals(what, View.GONE, cancel.visibility)
                assertTrue("$what: the main button's label is whole", !ellipsized(action))
            }
        }
    }

    @Test @Config(qualifiers = "w1920dp-h720dp-land-mdpi") fun `a 1920x720 head unit`() = assertFits()
    @Test @Config(qualifiers = "w1280dp-h480dp-land-mdpi") fun `a 1280x480 head unit`() = assertFits()
    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi") fun `a 1024x600 head unit`() = assertFits()
    @Test @Config(qualifiers = "w853dp-h320dp-land-hdpi") fun `a 1280x480 hdpi head unit`() = assertFits()
    @Test @Config(qualifiers = "w533dp-h300dp-land-hdpi") fun `an 800x450 head unit`() = assertFits()
    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi") fun `a portrait phone`() = assertFits()
    @Test @Config(qualifiers = "w600dp-h960dp-port-hdpi") fun `a portrait tablet`() = assertFits()

    // The longest translations, on the narrowest cards.
    @Test @Config(qualifiers = "de-w411dp-h891dp-port-xxhdpi") fun `a portrait phone in German`() = assertFits()
    @Test @Config(qualifiers = "ru-w411dp-h891dp-port-xxhdpi") fun `a portrait phone in Russian`() = assertFits()
    @Test @Config(qualifiers = "tr-w411dp-h891dp-port-xxhdpi") fun `a portrait phone in Turkish`() = assertFits()
    @Test @Config(qualifiers = "fr-w411dp-h891dp-port-xxhdpi") fun `a portrait phone in French`() = assertFits()
    @Test @Config(qualifiers = "de-w533dp-h300dp-land-hdpi") fun `an 800x450 head unit in German`() = assertFits()
    @Test @Config(qualifiers = "ru-w533dp-h300dp-land-hdpi") fun `an 800x450 head unit in Russian`() = assertFits()
    @Test @Config(qualifiers = "tr-w1920dp-h720dp-land-mdpi") fun `a 1920x720 head unit in Turkish`() = assertFits()

    private val locales = listOf("", "ar", "cs", "de", "es", "es-rES", "fr", "hi", "hu", "in", "it", "ja", "ka", "ko",
        "nl", "pl", "pt-rBR", "pt-rPT", "ro", "ru", "th", "tr", "uk", "vi", "zh-rCN", "zh-rTW")

    private fun assertFitsInEveryLanguage(screen: String) {
        for (locale in locales) {
            RuntimeEnvironment.setQualifiers(if (locale.isEmpty()) screen else "$locale-$screen")
            try {
                assertFits()
            } catch (e: AssertionError) {
                throw AssertionError("${locale.ifEmpty { "en" }} on $screen: ${e.message}", e)
            }
        }
    }

    @Test fun `every language on a portrait phone`() = assertFitsInEveryLanguage("w411dp-h891dp-port-xxhdpi")
    @Test fun `every language on an 800x450 head unit`() = assertFitsInEveryLanguage("w533dp-h300dp-land-hdpi")
    @Test fun `every language on a 1920x720 head unit`() = assertFitsInEveryLanguage("w1920dp-h720dp-land-mdpi")

    @Test @Config(qualifiers = "w1920dp-h720dp-land-mdpi")
    fun `the other networks show no WiFi line`() {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.create().get()
        for (network in listOf(HeadunitServerNetwork.SHARED, HeadunitServerNetwork.UNIT_HOTSPOT)) {
            val settings = Settings(activity).apply {
                wifiConnectionMode = WifiLauncherMode.AUTO
                headunitServerNetwork = network
            }
            val root = FrameLayout(activity)
            activity.layoutInflater.inflate(R.layout.fragment_home, root, true)
            val decision = HomePrimaryPolicy.decide(HomePrimaryPolicy.Input(
                chosen = setOf(HomePrimaryPolicy.Connection.WIFI), lastConnection = HomePrimaryPolicy.Connection.WIFI,
                usbPhoneDetected = false, sessionActive = false, reconnectTarget = null, wifiScanning = false,
            ))
            val palette = HomeUiHelper.palette(activity, HomeThemePolicy.Mode.DAY)
            HomeUiHelper.applyPrimaryCard(activity, root, settings, decision, null, 0, palette)
            val line = HomeWifiStatusPolicy.line(
                HomeWifiStatusPolicy.appliesTo(settings.wifiConnectionMode, settings.headunitServerNetwork),
                true, HomeWifiStatusPolicy.Facts(true, false))
            HomeUiHelper.applyWifiStatus(activity, root, settings, decision, palette, line, scanning = false)
            assertEquals("$network", View.GONE, root.findViewById<View>(R.id.home_wifi_status).visibility)
            assertEquals("$network", View.VISIBLE, root.findViewById<View>(R.id.home_primary_action).visibility)
        }
    }
}
