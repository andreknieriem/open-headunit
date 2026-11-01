package com.andrerinas.openheadunit.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingHelpPolicyTest {

    private fun toggle(help: Int?) = SettingItem.ToggleSettingEntry(
        stableId = "t", nameResId = 1, descriptionResId = null, isChecked = false,
        helpResId = help, onCheckedChanged = {}
    )

    @Test
    fun `a toggle with a help id shows help`() {
        assertTrue(SettingHelpPolicy.showsHelp(toggle(7)))
    }

    @Test
    fun `a toggle without a help id shows none`() {
        assertFalse(SettingHelpPolicy.showsHelp(toggle(null)))
        assertNull(SettingHelpPolicy.helpResId(toggle(null)))
    }

    @Test
    fun `every row type that carries text can show help`() {
        assertTrue(SettingHelpPolicy.showsHelp(SettingItem.SettingEntry("a", 1, "v", helpResId = 5, onClick = {})))
        assertTrue(
            SettingHelpPolicy.showsHelp(
                SettingItem.StreamSettingEntry("b", 1, "v", helpResId = 5, onClick = {}, onTest = {})
            )
        )
        assertTrue(
            SettingHelpPolicy.showsHelp(
                SettingItem.SliderSettingEntry("c", 1, "v", 0f, 0f, 1f, 1f, helpResId = 5, onValueChanged = {})
            )
        )
        assertTrue(
            SettingHelpPolicy.showsHelp(
                SettingItem.SegmentedButtonSettingEntry("d", 1, listOf("x"), 0, helpResId = 5, onOptionSelected = {})
            )
        )
    }

    @Test
    fun `headers, banners, buttons and notices never show help`() {
        assertFalse(SettingHelpPolicy.showsHelp(SettingItem.CategoryHeader("h", 1)))
        assertFalse(SettingHelpPolicy.showsHelp(SettingItem.InfoBanner("i", 1)))
        assertFalse(SettingHelpPolicy.showsHelp(SettingItem.ActionButton("j", 1, onClick = {})))
        assertFalse(SettingHelpPolicy.showsHelp(SettingItem.ScopeNotice("k", "t", 0, 0, 0, {})))
    }

    @Test
    fun `the id and the flag agree`() {
        assertEquals(7, SettingHelpPolicy.helpResId(toggle(7)))
        assertEquals(SettingHelpPolicy.helpResId(toggle(7)) != null, SettingHelpPolicy.showsHelp(toggle(7)))
    }
}
