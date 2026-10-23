package com.andrerinas.openheadunit.main.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsFilterPolicyTest {

    private val titles = mapOf(
        GENERAL to "General",
        LANGUAGE to "App language",
        DEBUG to "Debug logging",
        WIRELESS to "Wireless Connection",
        WIFI_MODE to "WiFi mode",
        INPUT to "Input",
        LIBUSB to "Use libusb",
        SAVE to "Save",
        NOTICE to "USB settings. WiFi settings are hidden.",
        DEVICE_SPECIFIC to "Head unit specific settings"
    )

    private fun text(id: Int): String = titles[id] ?: ""

    private val basicIds = setOf("language", "wifiMode", "useLibusb")

    private val language = SettingItem.SettingEntry(
        stableId = "language",
        nameResId = LANGUAGE,
        value = "English",
        onClick = {}
    )

    private val debug = SettingItem.SettingEntry(
        stableId = "debug",
        nameResId = DEBUG,
        value = "Off",
        searchKeywords = "log cat",
        onClick = {}
    )

    private val wifiMode = SettingItem.SettingEntry(
        stableId = "wifiMode",
        nameResId = WIFI_MODE,
        value = "Automatic",
        onClick = {}
    )

    private val libusb = SettingItem.ToggleSettingEntry(
        stableId = "useLibusb",
        nameResId = LIBUSB,
        descriptionResId = null,
        isChecked = true,
        onCheckedChanged = {}
    )

    private val notice = SettingItem.ScopeNotice(
        stableId = "scopeNotice",
        text = titles.getValue(NOTICE),
        iconResId = 0,
        filledIconResId = 0,
        actionResId = 0,
        onAction = {}
    )

    private val bottomSave = SettingItem.ActionButton(stableId = "bottomSaveButton", textResId = SAVE, onClick = {})

    /** The notice sits first, as it does on the screen. */
    private val items = listOf(
        notice,
        SettingItem.CategoryHeader(stableId = "general", titleResId = GENERAL),
        language,
        debug,
        SettingItem.CategoryHeader(stableId = "wirelessConnection", titleResId = WIRELESS),
        wifiMode,
        SettingItem.CategoryHeader(stableId = "input", titleResId = INPUT),
        libusb
    )

    private fun visible(
        query: String = "",
        advanced: Boolean = false,
        showsUsb: Boolean = true,
        showsWifi: Boolean = true,
        basics: Set<String> = basicIds
    ): List<String> = SettingsFilterPolicy.filter(
        items = items,
        query = query,
        advanced = advanced,
        showsUsb = showsUsb,
        showsWifi = showsWifi,
        basicIds = basics,
        text = ::text
    ).map { it.stableId }

    @Test
    fun `basic tab keeps the everyday rows and drops the advanced ones`() {
        assertEquals(
            listOf("scopeNotice", "general", "language", "wirelessConnection", "wifiMode", "input", "useLibusb"),
            visible()
        )
    }

    @Test
    fun `advanced tab keeps every row the connection allows`() {
        assertEquals(
            listOf(
                "scopeNotice", "general", "language", "debug", "wirelessConnection", "wifiMode", "input", "useLibusb"
            ),
            visible(advanced = true)
        )
    }

    @Test
    fun `a section without surviving rows takes its header with it`() {
        assertEquals(listOf("scopeNotice", "general", "language"), visible(basics = setOf("language")))
    }

    @Test
    fun `search spans both tabs and matches names, values and keywords`() {
        assertEquals(listOf("scopeNotice", "general", "debug"), visible(query = "debug"))
        assertEquals(listOf("scopeNotice", "general", "language"), visible(query = "english"))
        assertEquals(listOf("scopeNotice", "general", "debug"), visible(query = "log cat"))
    }

    @Test
    fun `search ignores case and surrounding spaces`() {
        assertEquals(visible(query = "debug"), visible(query = "  DEBUG  "))
    }

    @Test
    fun `a matching section title brings the rows under it, even outside the basic tab`() {
        assertEquals(listOf("scopeNotice", "input", "useLibusb"), visible(query = "input", basics = setOf("language")))
    }

    @Test
    fun `wifi hides the wireless section`() {
        assertEquals(
            listOf("scopeNotice", "general", "language", "debug", "input", "useLibusb"),
            visible(advanced = true, showsWifi = false)
        )
    }

    @Test
    fun `usb hides the usb only rows`() {
        assertEquals(
            listOf("scopeNotice", "general", "language", "debug", "wirelessConnection", "wifiMode"),
            visible(advanced = true, showsUsb = false)
        )
    }

    @Test
    fun `self mode hides both scopes`() {
        assertEquals(
            listOf("scopeNotice", "general", "language", "debug"),
            visible(advanced = true, showsUsb = false, showsWifi = false)
        )
    }

    @Test
    fun `the connection filter also applies while searching`() {
        assertEquals(listOf("scopeNotice"), visible(query = "wifi", showsWifi = false))
    }

    @Test
    fun `a title that matches but hides all its rows does not show the section`() {
        assertEquals(listOf("scopeNotice"), visible(query = "wireless", showsWifi = false))
    }

    @Test
    fun `the scope note and the bottom save action always survive`() {
        assertEquals(listOf("scopeNotice"), visible(query = "nothing matches this"))

        val withSave = SettingsFilterPolicy.filter(
            items = listOf(notice, debug, bottomSave),
            query = "nothing matches this",
            advanced = false,
            showsUsb = true,
            showsWifi = true,
            basicIds = emptySet(),
            text = ::text
        ).map { it.stableId }
        assertEquals(listOf("scopeNotice", "bottomSaveButton"), withSave)
    }

    @Test
    fun `the bottom save action does not bring a section header with it`() {
        val rows = SettingsFilterPolicy.filter(
            items = listOf(
                SettingItem.CategoryHeader(stableId = "input", titleResId = INPUT),
                libusb,
                bottomSave
            ),
            query = "",
            advanced = false,
            showsUsb = false,
            showsWifi = true,
            basicIds = basicIds,
            text = ::text
        ).map { it.stableId }

        // The section's only row is hidden by the connection choice; the save action still shows,
        // but without a header for a section that has nothing left.
        assertEquals(listOf("bottomSaveButton"), rows)
    }

    private val noResults = SettingItem.InfoBanner(stableId = "searchNoResults", textResId = 0, text = "No settings match")

    @Test
    fun `a search with no matches says so right after the scope note`() {
        val filtered = listOf(notice, bottomSave)
        assertEquals(
            listOf("scopeNotice", "searchNoResults", "bottomSaveButton"),
            SettingsFilterPolicy.withNoResultsNote(filtered, "zzz", noResults).map { it.stableId }
        )
    }

    @Test
    fun `the no results note also shows when there is no scope note`() {
        assertEquals(
            listOf("searchNoResults"),
            SettingsFilterPolicy.withNoResultsNote(emptyList(), "zzz", noResults).map { it.stableId }
        )
    }

    @Test
    fun `the head unit specific screen only shows in the advanced tab`() {
        val deviceSpecific = SettingItem.SettingEntry(
            stableId = "deviceSpecificSettings",
            nameResId = DEVICE_SPECIFIC,
            value = "",
            onClick = {}
        )
        fun shown(query: String, advanced: Boolean) = SettingsFilterPolicy.filter(
            items = listOf(SettingItem.CategoryHeader(stableId = "general", titleResId = GENERAL), deviceSpecific),
            query = query,
            advanced = advanced,
            showsUsb = true,
            showsWifi = true,
            basicIds = setOf("deviceSpecificSettings"),
            text = ::text
        ).map { it.stableId }

        assertEquals(listOf("general", "deviceSpecificSettings"), shown(query = "", advanced = true))
        assertEquals(emptyList<String>(), shown(query = "", advanced = false))
        assertEquals(emptyList<String>(), shown(query = "head unit", advanced = false))
    }

    @Test
    fun `no note while there are matches or no search`() {
        val withMatch = listOf(notice, SettingItem.CategoryHeader(stableId = "general", titleResId = GENERAL), language)
        assertEquals(withMatch, SettingsFilterPolicy.withNoResultsNote(withMatch, "english", noResults))
        val empty = listOf(notice)
        assertEquals(empty, SettingsFilterPolicy.withNoResultsNote(empty, "  ", noResults))
    }

    private companion object {
        const val GENERAL = 1
        const val LANGUAGE = 2
        const val DEBUG = 3
        const val WIRELESS = 4
        const val WIFI_MODE = 5
        const val INPUT = 6
        const val LIBUSB = 7
        const val SAVE = 8
        const val NOTICE = 9
        const val DEVICE_SPECIFIC = 10
    }
}
