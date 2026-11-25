package com.andrerinas.openheadunit.utils

import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class SettingsUseSystemHotspotTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val raw get() = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)

    @Test
    fun `a fresh store reads false and writes nothing`() {
        raw.edit().clear().commit()
        assertFalse(Settings(context).useSystemHotspot)
        assertFalse(raw.contains("use-system-hotspot"))
    }

    @Test
    fun `a write of true reads true`() {
        raw.edit().clear().commit()
        val settings = Settings(context)
        settings.useSystemHotspot = true
        assertTrue(Settings(context).useSystemHotspot)
        assertEquals(true, raw.getBoolean("use-system-hotspot", false))
    }
}
