package com.andrerinas.openheadunit.main.settings

/**
 * What the settings list shows for the chosen tab, the search query and the connection types.
 *
 * The redesign moved the tabs and the search into the header, but which rows survive did not
 * change. The rules live here, away from the fragment and its views, so they can be tested.
 */
object SettingsFilterPolicy {

    /** The bottom Save action stays visible while there are changes to save. */
    private const val BOTTOM_SAVE_ID = "bottomSaveButton"

    /** The Wireless Connection section belongs to WiFi. */
    private const val WIRELESS_CATEGORY_ID = "wirelessConnection"

    /** Rows that only apply when the app connects over USB. */
    private val USB_ONLY_IDS = setOf("useLibusb")

    /** The head unit specific screen, which only the Advanced tab lists, also while searching. */
    private const val DEVICE_SPECIFIC_ID = "deviceSpecificSettings"

    /**
     * [items] is the complete list built for the screen, headers included. [text] resolves a
     * string resource, so the caller keeps the fragment's `getString` and the rules stay pure.
     * A section header survives only when at least one of its rows does.
     */
    fun filter(
        items: List<SettingItem>,
        query: String,
        advanced: Boolean,
        showsUsb: Boolean,
        showsWifi: Boolean,
        basicIds: Set<String>,
        text: (Int) -> String
    ): List<SettingItem> {
        val search = query.trim()
        val result = mutableListOf<SettingItem>()
        var pendingHeader: SettingItem.CategoryHeader? = null
        var categoryId: String? = null
        var headerMatches = false

        for (item in items) {
            if (item is SettingItem.CategoryHeader) {
                pendingHeader = item
                categoryId = item.stableId
                headerMatches = search.isNotEmpty() &&
                    text(item.titleResId).contains(search, ignoreCase = true)
                continue
            }
            if (!shows(item, search, headerMatches, advanced, showsUsb, showsWifi, basicIds, categoryId, text)) {
                continue
            }
            if (!standsAlone(item)) {
                pendingHeader?.let { result.add(it) }
                pendingHeader = null
            }
            result.add(item)
        }
        return result
    }

    /**
     * The list with [note] added when a search matched no setting, so the screen says so instead
     * of showing only the scope note. The note goes right after the scope note, above the bottom
     * Save action. Without a query, or with matches, [filtered] comes back unchanged.
     */
    fun withNoResultsNote(filtered: List<SettingItem>, query: String, note: SettingItem): List<SettingItem> {
        if (query.isBlank() || filtered.any { !standsAlone(it) }) return filtered
        val at = filtered.indexOfFirst { it !is SettingItem.ScopeNotice }.let { if (it < 0) filtered.size else it }
        return filtered.toMutableList().apply { add(at, note) }
    }

    private fun shows(
        item: SettingItem,
        search: String,
        headerMatches: Boolean,
        advanced: Boolean,
        showsUsb: Boolean,
        showsWifi: Boolean,
        basicIds: Set<String>,
        categoryId: String?,
        text: (Int) -> String
    ): Boolean {
        // The bottom Save action is always relevant when present.
        if (item is SettingItem.ActionButton && item.stableId == BOTTOM_SAVE_ID) return true
        // So is the note about what the connection choice hides, in both tabs and in search.
        if (item is SettingItem.ScopeNotice) return true

        if (hiddenByConnection(item, categoryId, showsUsb, showsWifi)) return false

        if (item.stableId == DEVICE_SPECIFIC_ID && !advanced) return false

        if (search.isNotEmpty()) {
            return headerMatches || searchableText(item, text).contains(search, ignoreCase = true)
        }

        if (advanced) return true

        return item.stableId in basicIds
    }

    /**
     * Rows that stand outside the sections: they never bring a category header with them. The
     * scope note sits above the list and the bottom Save action below it, so a section whose rows
     * were all filtered out does not come back just to hold them.
     */
    private fun standsAlone(item: SettingItem): Boolean =
        item is SettingItem.ScopeNotice ||
            (item is SettingItem.ActionButton && item.stableId == BOTTOM_SAVE_ID)

    /**
     * Hides the settings the chosen connection types make irrelevant, in both tabs and while
     * searching: WiFi hides the USB-only rows, USB hides the Wireless Connection section, and
     * Self Mode (or no choice) hides neither.
     */
    private fun hiddenByConnection(
        item: SettingItem,
        categoryId: String?,
        showsUsb: Boolean,
        showsWifi: Boolean
    ): Boolean {
        if (categoryId == WIRELESS_CATEGORY_ID) return !showsWifi
        if (item.stableId in USB_ONLY_IDS) return !showsUsb
        return false
    }

    /** The text a query is matched against: names, the value shown and the extra keywords. */
    private fun searchableText(item: SettingItem, text: (Int) -> String): String = when (item) {
        is SettingItem.SettingEntry ->
            "${item.nameOverride ?: text(item.nameResId)} ${item.value} ${item.searchKeywords ?: ""}"
        is SettingItem.ToggleSettingEntry ->
            "${item.nameOverride ?: text(item.nameResId)} ${if (item.descriptionResId != null) text(item.descriptionResId) else ""} ${item.searchKeywords ?: ""}"
        is SettingItem.StreamSettingEntry ->
            "${text(item.nameResId)} ${item.value} ${if (item.descriptionResId != null) text(item.descriptionResId) else ""} ${item.searchKeywords ?: ""}"
        is SettingItem.SliderSettingEntry ->
            "${text(item.nameResId)} ${item.value}"
        is SettingItem.SegmentedButtonSettingEntry ->
            "${text(item.nameResId)} ${item.options.joinToString(" ")}"
        is SettingItem.InfoBanner -> item.text ?: text(item.textResId)
        is SettingItem.ActionButton -> text(item.textResId)
        is SettingItem.CategoryHeader -> text(item.titleResId)
        is SettingItem.ScopeNotice -> item.text
    }
}
