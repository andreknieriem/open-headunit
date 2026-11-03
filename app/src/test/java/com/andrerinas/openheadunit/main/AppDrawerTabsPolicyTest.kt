package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.main.AppDrawerTabsPolicy.Tab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppDrawerTabsPolicyTest {

    private val chrome = AppDrawerItem("Chrome", "com.android.chrome", "Main")
    private val newTab = AppDrawerItem("New tab", "com.android.chrome", "Main", shortcutId = "new-tab")
    private val maps = AppDrawerItem("Maps", "com.google.android.apps.maps", "Maps")
    private val home = AppDrawerItem("Home", "com.google.android.apps.maps", "Maps", shortcutId = "home")
    private val all = listOf(chrome, newTab, maps, home)

    @Test
    fun `tabs show only with apps and shortcuts both present`() {
        assertTrue(AppDrawerTabsPolicy.tabsShown(all))
        assertFalse(AppDrawerTabsPolicy.tabsShown(listOf(chrome, maps)))
        assertFalse(AppDrawerTabsPolicy.tabsShown(listOf(newTab)))
        assertFalse(AppDrawerTabsPolicy.tabsShown(emptyList()))
    }

    @Test
    fun `each tab holds its own kind, in the order given`() {
        assertEquals(listOf(chrome, maps), AppDrawerTabsPolicy.itemsFor(Tab.APPS, all))
        assertEquals(listOf(newTab, home), AppDrawerTabsPolicy.itemsFor(Tab.SHORTCUTS, all))
    }

    @Test
    fun `without tabs the grid holds everything`() {
        assertEquals(listOf(chrome, maps), AppDrawerTabsPolicy.itemsFor(Tab.SHORTCUTS, listOf(chrome, maps)))
    }

    @Test
    fun `the shortcuts tab falls back to apps once the shortcuts are gone`() {
        assertEquals(Tab.SHORTCUTS, AppDrawerTabsPolicy.effectiveTab(Tab.SHORTCUTS, all))
        assertEquals(Tab.APPS, AppDrawerTabsPolicy.effectiveTab(Tab.SHORTCUTS, listOf(chrome, maps)))
    }

    @Test
    fun `the tabs leave the toolbar only on a narrow screen`() {
        assertTrue(AppDrawerTabsPolicy.tabsInToolbar(624))
        assertTrue(AppDrawerTabsPolicy.tabsInToolbar(480))
        assertFalse(AppDrawerTabsPolicy.tabsInToolbar(320))
    }
}
