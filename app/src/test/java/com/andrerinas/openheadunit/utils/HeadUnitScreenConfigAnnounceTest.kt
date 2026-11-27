package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28, 35], qualifiers = "w1000dp-h600dp-land-mdpi")
class HeadUnitScreenConfigAnnounceTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val owner = Any()
    private lateinit var settings: Settings
    private val a = "2376x968"
    private val b = "2160x1856"

    @Before fun setUp() {
        settings = Settings(context)
        settings.dpiPixelDensity = 200
        settings.dpiPanelsActive = true
        settings.dpiPanelValues = PanelDensityPolicy.format(
            linkedMapOf(a to PanelDensityPolicy.Entry(132, false), b to PanelDensityPolicy.Entry(400, true))
        )
    }

    @After fun tearDown() {
        HeadUnitScreenConfig.unlockResolution()
        HeadUnitScreenConfig.releaseWindowMeasurement(owner)
        HeadUnitScreenConfig.setPanelKey(null)
        context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun measure() {
        val metrics = context.resources.displayMetrics
        val orientation = context.resources.configuration.orientation
        HeadUnitScreenConfig.beginWindowMeasurement(owner, settings.fullscreenMode)
        HeadUnitScreenConfig.publishWindowGeometry(
            owner, context, metrics, settings, 1000, 1160, 0, 0, 0, 0,
            orientation, HeadUnitScreenConfig.computeSettingsHash(settings, orientation),
        )
    }

    private fun announce(o: Any = owner) =
        HeadUnitScreenConfig.announceGeometry(o, context, context.resources.displayMetrics, settings)

    @Test fun `a kept window measurement survives the announce for the other panel`() {
        measure()
        val w = HeadUnitScreenConfig.getUsableWidth()
        val h = HeadUnitScreenConfig.getUsableHeight()
        HeadUnitScreenConfig.setPanelKey(b)
        val g = announce()
        assertEquals(400, g.densityDecision.requested)
        assertEquals(AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE, g.densityDecision.reason)
        assertEquals(b, g.panelKey)
        assertEquals(w, HeadUnitScreenConfig.getUsableWidth())
        assertEquals(h, HeadUnitScreenConfig.getUsableHeight())
    }

    @Test fun `an invalidated measurement still announces the value of the panel`() {
        measure()
        HeadUnitScreenConfig.invalidateWindowMeasurement(owner)
        HeadUnitScreenConfig.setPanelKey(b)
        assertEquals(400, announce().densityDecision.requested)
    }

    @Test fun `two panels with one value keep their own flag`() {
        settings.dpiPanelValues = PanelDensityPolicy.format(
            linkedMapOf(a to PanelDensityPolicy.Entry(400, false), b to PanelDensityPolicy.Entry(400, true))
        )
        measure()
        HeadUnitScreenConfig.setPanelKey(a)
        val first = announce().densityDecision
        HeadUnitScreenConfig.unlockResolution()
        HeadUnitScreenConfig.setPanelKey(b)
        val second = announce(Any()).densityDecision
        assertNotEquals(AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE, first.reason)
        assertEquals(AnnouncedDensityPolicy.Reason.FLOOR_OVERRIDE, second.reason)
        assertEquals(400, second.announced)
    }

    @Test fun `no key or an off latch gives the global value`() {
        measure()
        HeadUnitScreenConfig.setPanelKey(null)
        assertEquals(200, announce().densityDecision.requested)
        HeadUnitScreenConfig.unlockResolution()
        settings.dpiPanelsActive = false
        HeadUnitScreenConfig.setPanelKey(a)
        assertEquals(200, announce(Any()).densityDecision.requested)
    }

    @Test fun `a panel's own layout wins over the global one`() {
        settings.dpiPixelDensity = 0
        settings.dpiAutoLayout = "ONE_APP"
        settings.dpiPanelValues = PanelDensityPolicy.format(
            linkedMapOf(a to PanelDensityPolicy.Entry(0, false), b to PanelDensityPolicy.Entry(0, false, "ONE_APP"))
        )
        measure()
        HeadUnitScreenConfig.setPanelKey(a)
        assertNotEquals(AnnouncedDensityPolicy.Reason.AUTO_LAYOUT, announce().densityDecision.reason)
        HeadUnitScreenConfig.unlockResolution()
        HeadUnitScreenConfig.setPanelKey(b)
        assertEquals(AnnouncedDensityPolicy.Reason.AUTO_LAYOUT, announce(Any()).densityDecision.reason)
        HeadUnitScreenConfig.unlockResolution()
        HeadUnitScreenConfig.setPanelKey("1000x600")
        assertEquals(AnnouncedDensityPolicy.Reason.AUTO_LAYOUT, announce(Any()).densityDecision.reason)
    }

    @Test fun `a second announce for the same owner returns the cached decision`() {
        measure()
        HeadUnitScreenConfig.setPanelKey(a)
        val first = announce()
        HeadUnitScreenConfig.setPanelKey(b)
        assertSame(first, announce())
    }

    @Test fun `the settings hash ignores the panel map and follows the global value`() {
        val orientation = Configuration.ORIENTATION_LANDSCAPE
        val before = HeadUnitScreenConfig.computeSettingsHash(settings, orientation)
        settings.dpiPanelValues = "1x1=5"
        assertEquals(before, HeadUnitScreenConfig.computeSettingsHash(settings, orientation))
        settings.dpiPixelDensity = 201
        assertNotEquals(before, HeadUnitScreenConfig.computeSettingsHash(settings, orientation))
    }
}
