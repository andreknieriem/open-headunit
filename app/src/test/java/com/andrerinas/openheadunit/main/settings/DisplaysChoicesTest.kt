package com.andrerinas.openheadunit.main.settings

import android.app.Application
import android.content.Context
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.DisplayTargetPolicy
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class DisplaysChoicesTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val prefs = context.getSharedPreferences(Settings.PREFS_NAME, Context.MODE_PRIVATE)
    private val settings = Settings(context)

    @Test
    fun `the entry keywords hold the choice labels of the moved rows`() {
        val keywords = DisplaysChoices.searchKeywords(context, settings)
        listOf(
            R.string.aux_display_content_turn_card, R.string.aux_display_content_map,
            R.string.aux_display_role_cluster, R.string.aux_display_role_auxiliary,
            R.string.aux_ms912x_format_yuv, R.string.aux_ms912x_format_rgb,
            R.string.projection_display_builtin, R.string.projection_display_automatic,
            R.string.aux_output_rtp_push, R.string.aux_display_role,
        ).forEach { assertTrue(context.getString(it), keywords.contains(context.getString(it))) }
        DisplaysChoices.ms912xModeLabels().forEach { assertTrue(it, keywords.contains(it)) }
    }

    @Test
    fun `picking the choice already stored writes nothing`() {
        prefs.edit().clear().commit()
        val choices = DisplaysChoices.projectionChoices(context, settings)
        choices.picks[choices.selected]()
        assertFalse(prefs.contains("preferred-display-mode"))
        assertFalse(prefs.contains("preferred-display-id"))
    }

    @Test
    fun `picking another choice writes only the mode`() {
        prefs.edit().clear().commit()
        DisplaysChoices.projectionChoices(context, settings).picks[1]()
        assertEquals(DisplayTargetPolicy.Mode.AUTO.ordinal, settings.preferredDisplayMode)
        assertFalse(prefs.contains("preferred-display-id"))
    }
}
