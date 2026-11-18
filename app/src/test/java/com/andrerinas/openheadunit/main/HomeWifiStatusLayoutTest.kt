package com.andrerinas.openheadunit.main

import android.app.Application
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.utils.HomeUiHelper
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home with Headunit Server's WiFi line and Scan WiFi button, laid out on the real layout with
 * the real painting and fitting code: nothing is cut on head unit screens, and on a portrait phone
 * the main button keeps its room because Scan WiFi drops to its icon.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34])
class HomeWifiStatusLayoutTest {

    private fun layOut(connected: Boolean): View {
        val controller = Robolectric.buildActivity(AppCompatActivity::class.java)
        controller.get().setTheme(R.style.AppTheme)
        val activity = controller.create().get()
        val dm = activity.resources.displayMetrics
        val settings = Settings(activity).apply { wifiConnectionMode = WifiLauncherMode.AUTO }
        val root = FrameLayout(activity)
        activity.layoutInflater.inflate(R.layout.fragment_home, root, true)
        val decision = HomePrimaryPolicy.decide(HomePrimaryPolicy.Input(
            chosen = setOf(HomePrimaryPolicy.Connection.WIFI), lastConnection = HomePrimaryPolicy.Connection.WIFI,
            usbPhoneDetected = false, sessionActive = false, reconnectTarget = null, wifiScanning = false,
        ))
        val palette = HomeUiHelper.palette(activity, HomeThemePolicy.Mode.DAY)
        HomeUiHelper.applyPrimaryCard(activity, root, settings, decision, null, 0, palette)
        val facts = if (connected) HomeWifiStatusPolicy.Facts(true, true, "\"Galaxy S24 Ultra\"", 5180, -60)
            else HomeWifiStatusPolicy.Facts(true, false)
        HomeUiHelper.applyWifiStatus(activity, root, settings, decision, palette,
            HomeWifiStatusPolicy.line(true, true, facts), scanning = false)
        for (pass in 0 until 6) {
            root.measure(View.MeasureSpec.makeMeasureSpec(dm.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(dm.heightPixels, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, dm.widthPixels, dm.heightPixels)
            if (!HomeUiHelper.fitWifiStatus(activity, root)) break
        }
        return root
    }

    private fun ellipsized(v: TextView) = (0 until v.layout.lineCount).any { v.layout.getEllipsisCount(it) > 0 }

    private fun assertHeadUnitFits() {
        for (connected in listOf(true, false)) {
            val root = layOut(connected)
            val action = root.findViewById<TextView>(R.id.home_primary_action)
            val scan = root.findViewById<View>(R.id.home_wifi_scan)
            val label = root.findViewById<TextView>(R.id.home_wifi_scan_label)
            assertEquals(View.VISIBLE, scan.visibility)
            assertEquals("Scan WiFi keeps its label", View.VISIBLE, label.visibility)
            assertEquals("both buttons are as tall", action.height, scan.height)
            assertTrue(action.right <= scan.left)
            assertTrue("the main button's label is whole", !ellipsized(action) && action.layout.lineCount == 1)
            assertTrue("the network name is whole", !ellipsized(root.findViewById(R.id.home_wifi_name)))
            assertTrue("Scan WiFi stays inside the card", scan.right <= (scan.parent.parent as View).width)
        }
    }

    @Test @Config(qualifiers = "w1920dp-h720dp-land-mdpi") fun `a 1920x720 head unit`() = assertHeadUnitFits()
    @Test @Config(qualifiers = "w1280dp-h480dp-land-mdpi") fun `a 1280x480 head unit`() = assertHeadUnitFits()
    @Test @Config(qualifiers = "w1024dp-h600dp-land-mdpi") fun `a 1024x600 head unit`() = assertHeadUnitFits()
    @Test @Config(qualifiers = "w853dp-h320dp-land-hdpi") fun `a 1280x480 hdpi head unit`() = assertHeadUnitFits()
    @Test @Config(qualifiers = "w533dp-h300dp-land-hdpi") fun `an 800x450 head unit`() = assertHeadUnitFits()

    @Test @Config(qualifiers = "w411dp-h891dp-port-xxhdpi")
    fun `a portrait phone keeps the main button whole and steps Scan WiFi aside`() {
        for (connected in listOf(true, false)) {
            val root = layOut(connected)
            val action = root.findViewById<TextView>(R.id.home_primary_action)
            assertEquals(View.GONE, root.findViewById<View>(R.id.home_wifi_scan).visibility)
            assertTrue("the main button's label is whole", !ellipsized(action))
            assertEquals("the WiFi line still tells the state", View.VISIBLE, root.findViewById<View>(R.id.home_wifi_status).visibility)
            assertTrue("the network name is whole", !ellipsized(root.findViewById(R.id.home_wifi_name)))
        }
    }

    @Test @Config(qualifiers = "w600dp-h960dp-port-hdpi")
    fun `a portrait tablet keeps Scan WiFi`() {
        val root = layOut(connected = false)
        val action = root.findViewById<TextView>(R.id.home_primary_action)
        assertEquals(View.VISIBLE, root.findViewById<View>(R.id.home_wifi_scan).visibility)
        assertTrue(!ellipsized(action))
    }
}
