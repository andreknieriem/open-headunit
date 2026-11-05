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
    private val USB_ONLY_IDS = setOf("useLibusb", "listenForUsbDevices")

    /** The head unit specific screen, which only the Advanced tab lists, also while searching. */
    private const val DEVICE_SPECIFIC_ID = "deviceSpecificSettings"

    private val SEARCH_WHITESPACE = Regex("\\s+")

    /**
     * [items] is the complete list built for the screen, headers included. [text] resolves a
     * string resource, so the caller keeps the fragment's `getString` and the rules stay pure.
     * A section header, and a sub-header inside it, survives only when at least one of its rows does.
     * Search matches all whitespace-separated terms within one field in any order,
     * ignoring case and Russian yo/e. Latin and Cyrillic terms must start at a word boundary.
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
        val search = normalizeSearchText(query).split(SEARCH_WHITESPACE).filter { it.isNotEmpty() }
        val result = mutableListOf<SettingItem>()
        var pendingHeader: SettingItem.CategoryHeader? = null
        var categoryId: String? = null
        var headerMatches = false
        var pendingSubHeader: SettingItem.SubHeader? = null
        var subHeaderMatches = false

        for (item in items) {
            if (item is SettingItem.CategoryHeader) {
                pendingHeader = item
                categoryId = item.stableId
                headerMatches = search.isNotEmpty() &&
                    matchesSearch(text(item.titleResId), search)
                pendingSubHeader = null
                subHeaderMatches = false
                continue
            }
            if (item is SettingItem.SubHeader) {
                pendingSubHeader = item
                subHeaderMatches = search.isNotEmpty() &&
                    matchesSearch(text(item.titleResId), search)
                continue
            }
            if (!shows(item, search, headerMatches || subHeaderMatches, advanced, showsUsb, showsWifi, basicIds, categoryId, text)) {
                continue
            }
            if (!standsAlone(item)) {
                pendingHeader?.let { result.add(it) }
                pendingHeader = null
                pendingSubHeader?.let { result.add(it) }
                pendingSubHeader = null
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
        search: List<String>,
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
            return headerMatches || searchableFields(item, text).any { matchesSearch(it, search) }
        }

        if (advanced) return true

        return item.stableId in basicIds
    }

    /**
     * Normalize search text:
     * - Treats Russian yo and e as equivalent without changing other letters or their case. */
    private fun normalizeSearchText(text: String): String =
        text.replace('\u0451', '\u0435').replace('\u0401', '\u0415')

    /** Matches all terms in one field; only Latin and Cyrillic terms require a word start. */
    private fun matchesSearch(text: String, terms: List<String>): Boolean {
        val normalized = normalizeSearchText(text)
        return terms.all { term ->
            if (!requiresWordStart(term)) {
                normalized.contains(term, ignoreCase = true)
            } else {
                normalized.indices.any { index ->
                    (index == 0 || !normalized[index - 1].isLetterOrDigit()) &&
                        normalized.startsWith(term, startIndex = index, ignoreCase = true)
                }
            }
        }
    }

    /**
     * Uses the first letter to choose matching rules per term, including mixed-language queries.
     * Explicit Unicode ranges keep this compatible with Android versions before UnicodeScript.
     */
    private fun requiresWordStart(term: String): Boolean {
        val letter = term.firstOrNull { it.isLetter() } ?: return false
        return letter in '\u0041'..'\u024F' ||
            letter in '\u1D00'..'\u1DBF' ||
            letter in '\u1E00'..'\u1EFF' ||
            letter in '\u2C60'..'\u2C7F' ||
            letter in '\uA720'..'\uA7FF' ||
            letter in '\uAB30'..'\uAB6F' ||
            letter in '\uFF21'..'\uFF3A' ||
            letter in '\uFF41'..'\uFF5A' ||
            letter in '\u0400'..'\u052F' ||
            letter in '\u2DE0'..'\u2DFF' ||
            letter in '\uA640'..'\uA69F'
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

    /** Each name, value, summary, help text and keywords field is searched independently. */
    private fun searchableFields(item: SettingItem, text: (Int) -> String): List<String> {
        val help = SettingHelpPolicy.helpResId(item)?.let(text) ?: ""
        return when (item) {
            is SettingItem.SettingEntry ->
                listOfNotNull(item.nameOverride ?: text(item.nameResId), item.value, item.descriptionResId?.let(text), help, item.searchKeywords)
            is SettingItem.ToggleSettingEntry ->
                listOfNotNull(item.nameOverride ?: text(item.nameResId), item.descriptionResId?.let(text), help, item.searchKeywords)
            is SettingItem.StreamSettingEntry ->
                listOfNotNull(text(item.nameResId), item.value, item.descriptionResId?.let(text), help, item.searchKeywords)
            is SettingItem.SliderSettingEntry ->
                listOf(text(item.nameResId), item.value.toString(), help)
            is SettingItem.SegmentedButtonSettingEntry ->
                listOf(text(item.nameResId), help) + item.options
            is SettingItem.InfoBanner -> listOf(item.text ?: text(item.textResId))
            is SettingItem.ActionButton -> listOf(text(item.textResId))
            is SettingItem.CategoryHeader -> listOf(text(item.titleResId))
            is SettingItem.SubHeader -> listOf(text(item.titleResId))
            is SettingItem.ScopeNotice -> listOf(item.text)
        }
    }
}
