package com.andrerinas.openheadunit.main

/**
 * The drawer's two tabs: the apps, and the shortcuts those apps publish. With both in one grid a
 * long list of shortcuts buried the apps, so they are split, and the tabs only show when there is
 * something to split (shortcuts are only read in launcher mode).
 *
 * Pure, so the rules are unit tests rather than taps on a head unit.
 */
object AppDrawerTabsPolicy {

    enum class Tab { APPS, SHORTCUTS }

    /** Below this width the tabs would push the toolbar's title out, so they get a row of their own. */
    const val TABS_IN_TOOLBAR_MIN_WIDTH_DP = 480

    /**
     * The tabs sit in the toolbar where there is width for them and the title, which keeps a row of
     * icons on a short head unit; on a narrow screen they move under it so the title stays.
     */
    fun tabsInToolbar(screenWidthDp: Int): Boolean = screenWidthDp >= TABS_IN_TOOLBAR_MIN_WIDTH_DP

    fun tabsShown(items: List<AppDrawerItem>): Boolean =
        items.any { it.shortcutId == null } && items.any { it.shortcutId != null }

    /** The tab to show: the chosen one, or Apps once there is nothing left to split. */
    fun effectiveTab(chosen: Tab, items: List<AppDrawerItem>): Tab =
        if (tabsShown(items)) chosen else Tab.APPS

    /** What the grid holds for [tab]. Without tabs it is everything, in the order given. */
    fun itemsFor(tab: Tab, items: List<AppDrawerItem>): List<AppDrawerItem> = when {
        !tabsShown(items) -> items
        tab == Tab.APPS -> items.filter { it.shortcutId == null }
        else -> items.filter { it.shortcutId != null }
    }
}
