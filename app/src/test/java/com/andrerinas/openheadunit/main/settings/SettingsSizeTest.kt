package com.andrerinas.openheadunit.main.settings

import android.app.Application
import com.andrerinas.openheadunit.R
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The settings sizes come from resource qualifiers. Head units around 160 dpi are read from an
 * arm's length away, so their landscape panels get the car scale (1.3 times the phone sizes),
 * while phones and dense head units keep the phone sizes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsSizeTest {

    private fun rowHeightDp(): Float {
        val res = RuntimeEnvironment.getApplication().resources
        return res.getDimension(R.dimen.settings_row_min_height) / res.displayMetrics.density
    }

    private fun railWidthDp(): Float {
        val res = RuntimeEnvironment.getApplication().resources
        return res.getDimension(R.dimen.settings_rail_width) / res.displayMetrics.density
    }

    @Test
    @Config(qualifiers = "w1280dp-h720dp-land-mdpi")
    fun `a 1280x720 head unit at 160 dpi gets the car scale`() {
        assertEquals(84f, rowHeightDp(), 0.5f)
        assertEquals(336f, railWidthDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w1024dp-h600dp-land-mdpi")
    fun `a 1024x600 head unit at 160 dpi gets the car scale`() {
        assertEquals(78f, rowHeightDp(), 0.5f)
        assertEquals(296f, railWidthDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w873dp-h393dp-land-xxhdpi")
    fun `a phone in landscape keeps the phone sizes`() {
        assertEquals(56f, rowHeightDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w393dp-h873dp-port-xxhdpi")
    fun `a phone in portrait keeps the phone sizes`() {
        assertEquals(64f, rowHeightDp(), 0.5f)
    }

    @Test
    @Config(qualifiers = "w683dp-h400dp-land-hdpi")
    fun `a dense 1024x600 head unit at 240 dpi keeps the phone sizes`() {
        assertEquals(56f, rowHeightDp(), 0.5f)
    }
}
