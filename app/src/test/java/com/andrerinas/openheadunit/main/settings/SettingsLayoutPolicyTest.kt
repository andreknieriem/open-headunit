package com.andrerinas.openheadunit.main.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsLayoutPolicyTest {

    @Test
    fun `group position follows first and last`() {
        assertEquals(GroupPosition.SINGLE, GroupPosition.of(isFirst = true, isLast = true))
        assertEquals(GroupPosition.TOP, GroupPosition.of(isFirst = true, isLast = false))
        assertEquals(GroupPosition.MIDDLE, GroupPosition.of(isFirst = false, isLast = false))
        assertEquals(GroupPosition.BOTTOM, GroupPosition.of(isFirst = false, isLast = true))
    }

    @Test
    fun `short values sit inline`() {
        assertTrue(SettingsLayoutPolicy.showValueInline("USB"))
        assertTrue(SettingsLayoutPolicy.showValueInline("Auto: 213 (960x540 dp)"))
    }

    @Test
    fun `long, blank and multi-line values wrap under the title`() {
        assertFalse(SettingsLayoutPolicy.showValueInline("Run the system check to find the best settings."))
        assertFalse(SettingsLayoutPolicy.showValueInline(""))
        assertFalse(SettingsLayoutPolicy.showValueInline("   "))
        assertFalse(SettingsLayoutPolicy.showValueInline("On\nOff"))
    }

    @Test
    fun `inline limit is inclusive`() {
        val atLimit = "x".repeat(SettingsLayoutPolicy.MAX_INLINE_VALUE_LENGTH)
        assertTrue(SettingsLayoutPolicy.showValueInline(atLimit))
        assertFalse(SettingsLayoutPolicy.showValueInline(atLimit + "x"))
    }

    @Test
    fun `scope notice names what the connection choice hides`() {
        assertEquals(SettingsLayoutPolicy.ScopeNotice.NONE, SettingsLayoutPolicy.scopeNotice(showsUsb = true, showsWifi = true))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.WIFI_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = true, showsWifi = false))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.USB_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = false, showsWifi = true))
        assertEquals(SettingsLayoutPolicy.ScopeNotice.USB_AND_WIFI_HIDDEN, SettingsLayoutPolicy.scopeNotice(showsUsb = false, showsWifi = false))
    }

    @Test
    fun `active category is the last header at or above the first visible row`() {
        val headers = intArrayOf(1, 6, 10)
        assertEquals(0, SettingsLayoutPolicy.activeCategoryIndex(headers, 0))
        assertEquals(0, SettingsLayoutPolicy.activeCategoryIndex(headers, 5))
        assertEquals(1, SettingsLayoutPolicy.activeCategoryIndex(headers, 6))
        assertEquals(1, SettingsLayoutPolicy.activeCategoryIndex(headers, 9))
        assertEquals(2, SettingsLayoutPolicy.activeCategoryIndex(headers, 40))
    }

    @Test
    fun `no sections means no active category`() {
        assertEquals(-1, SettingsLayoutPolicy.activeCategoryIndex(IntArray(0), 3))
    }

    @Test
    fun `a single row is round on all four corners`() {
        assertArrayEquals(
            floatArrayOf(OUTER, OUTER, OUTER, OUTER, OUTER, OUTER, OUTER, OUTER),
            SettingsLayoutPolicy.groupCornerRadii(GroupPosition.SINGLE, OUTER, INNER),
            0f
        )
    }

    @Test
    fun `only the ends of a group take the outer radius`() {
        assertArrayEquals(
            floatArrayOf(OUTER, OUTER, OUTER, OUTER, INNER, INNER, INNER, INNER),
            SettingsLayoutPolicy.groupCornerRadii(GroupPosition.TOP, OUTER, INNER),
            0f
        )
        assertArrayEquals(
            floatArrayOf(INNER, INNER, INNER, INNER, INNER, INNER, INNER, INNER),
            SettingsLayoutPolicy.groupCornerRadii(GroupPosition.MIDDLE, OUTER, INNER),
            0f
        )
        assertArrayEquals(
            floatArrayOf(INNER, INNER, INNER, INNER, OUTER, OUTER, OUTER, OUTER),
            SettingsLayoutPolicy.groupCornerRadii(GroupPosition.BOTTOM, OUTER, INNER),
            0f
        )
    }

    @Test
    fun `only card rows join a group`() {
        assertTrue(SettingsLayoutPolicy.joinsGroup(SettingItem.SettingEntry("a", 0, "", onClick = {})))
        assertTrue(
            SettingsLayoutPolicy.joinsGroup(
                SettingItem.ToggleSettingEntry("b", 0, descriptionResId = null, isChecked = false, onCheckedChanged = {})
            )
        )
        assertTrue(
            SettingsLayoutPolicy.joinsGroup(
                SettingItem.SliderSettingEntry("c", 0, "", 0f, 0f, 1f, 0.1f, onValueChanged = {})
            )
        )
        assertTrue(SettingsLayoutPolicy.joinsGroup(SettingItem.InfoBanner("d", 0)))
        assertTrue(
            SettingsLayoutPolicy.joinsGroup(
                SettingItem.SegmentedButtonSettingEntry("e", 0, listOf("A", "B", "C"), 0, onOptionSelected = {})
            )
        )
        assertTrue(
            SettingsLayoutPolicy.joinsGroup(
                SettingItem.StreamSettingEntry("f", 0, "", onClick = {}, onTest = {})
            )
        )

        assertFalse(SettingsLayoutPolicy.joinsGroup(null))
        assertFalse(SettingsLayoutPolicy.joinsGroup(SettingItem.CategoryHeader("g", 0)))
        assertFalse(SettingsLayoutPolicy.joinsGroup(SettingItem.ActionButton("h", 0, onClick = {})))
        assertFalse(
            SettingsLayoutPolicy.joinsGroup(
                SettingItem.ScopeNotice("i", "", 0, 0, 0, onAction = {})
            )
        )
    }

    private companion object {
        const val OUTER = 16f
        const val INNER = 4f
    }
}
