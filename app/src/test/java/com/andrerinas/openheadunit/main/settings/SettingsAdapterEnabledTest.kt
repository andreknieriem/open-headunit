package com.andrerinas.openheadunit.main.settings

import android.app.Application
import android.view.ContextThemeWrapper
import android.view.LayoutInflater
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.appcompat.widget.SwitchCompat
import com.andrerinas.openheadunit.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class SettingsAdapterEnabledTest {
    private val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)

    private fun assertDisabled(view: View) {
        assertFalse(view.isEnabled)
        assertFalse(view.isClickable)
        val node = AccessibilityNodeInfo.obtain()
        try {
            view.onInitializeAccessibilityNodeInfo(node)
            assertFalse(node.isEnabled)
            assertFalse(node.isClickable)
        } finally {
            node.recycle()
        }
    }

    @Test fun `disabled picker cannot select and recycled row becomes usable again`() {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_setting_item, null)
        val holder = SettingsAdapter.SettingViewHolder(view)
        var selections = 0
        val row = SettingItem.SettingEntry("lab", R.string.lab_av_sync, "Auto", isEnabled = false) { selections++ }
        holder.bind(row)
        assertDisabled(view)
        view.performClick()
        assertEquals(0, selections)
        holder.bind(row.copy(isEnabled = true))
        assertTrue(view.isEnabled)
        assertTrue(view.isClickable)
        view.performClick()
        assertEquals(1, selections)
    }

    @Test fun `disabled toggle preserves saved value and recycled row can toggle`() {
        val view = LayoutInflater.from(context).inflate(R.layout.layout_setting_item_toggle, null)
        val holder = SettingsAdapter.ToggleSettingViewHolder(view, SettingsPalette.from(context))
        val toggle = view.findViewById<SwitchCompat>(R.id.settingSwitch)
        val selected = mutableListOf<Boolean>()
        val row = SettingItem.ToggleSettingEntry("lab", R.string.lab_guidance, null,
            isChecked = true, isEnabled = false) { selected.add(it) }
        holder.bind(row)
        assertDisabled(view)
        assertFalse(toggle.isEnabled)
        view.performClick()
        assertTrue(toggle.isChecked)
        assertTrue(selected.isEmpty())
        holder.bind(row.copy(isEnabled = true))
        assertTrue(toggle.isEnabled)
        view.performClick()
        assertFalse(toggle.isChecked)
        assertEquals(listOf(false), selected)
    }
}
