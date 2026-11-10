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
        DEVICE_SPECIFIC to "Head unit specific settings",
        AUDIO_SINK to "Audio Sink",
        AUDIO_SUMMARY to "Plays phone audio through this unit.",
        AUDIO_HELP to "Audio from apps. Calls never use it.",
        PICKER_SUMMARY to "Chooses the codec.",
        PICKER_HELP to "Pick the video codec the phone sends.",
        ROUTE to "Bluetooth route",
        RELAY to "Bluetooth relay (ESP32)"
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
    fun `search matches all terms in any order within a single field`() {
        assertEquals(listOf("scopeNotice", "general", "debug"), visible(query = "logging debug"))
        assertEquals(listOf("scopeNotice", "general", "debug"), visible(query = "cat log"))
        assertEquals(listOf("scopeNotice", "general", "language"), visible(query = "language app"))
        assertEquals(listOf("scopeNotice"), visible(query = "cat debug"))
        assertEquals(listOf("scopeNotice"), visible(query = "english app"))
        assertEquals(listOf("scopeNotice"), visible(query = "debug missing"))
        assertEquals(listOf("scopeNotice"), visible(query = "english debug"))
    }

    @Test
    fun `search ignores repeated whitespace between terms`() {
        assertEquals(visible(query = "log cat"), visible(query = "  log   cat  "))
        assertEquals(visible(query = "log cat"), visible(query = "\tlog\t\ncat\r\n"))
        assertEquals(visible(), visible(query = " \t\n "))
    }

    @Test
    fun `search matches word prefixes but not the middle of words`() {
        val darkMode = language.copy(nameOverride = "Тёмный режим", value = "")
        val audioMode = debug.copy(nameOverride = "Выберите этот режим. Не затрагивает системный аудиофокус.")

        assertEquals(listOf("general", "language"), helpFilter(darkMode, audioMode, query = "режим темный"))
        assertEquals(listOf("general", "language"), helpFilter(darkMode, audioMode, query = "темн реж"))
        assertEquals(emptyList<String>(), helpFilter(audioMode, query = "темный"))
        assertEquals(listOf("general", "debug"), helpFilter(audioMode, query = "режим систем"))
    }

    @Test
    fun `word starts include punctuation boundaries and later valid occurrences`() {
        val row = language.copy(
            nameOverride = "Системный: «тёмный режим», Wi-Fi (H.264)",
            value = ""
        )

        for (query in listOf("темный режим", "fi wi", "h.264", "264", "«темн")) {
            assertEquals(query, listOf("general", "language"), helpFilter(row, query = query))
        }
        for (query in listOf("истемный", "264x", "i-fi")) {
            assertEquals(query, emptyList<String>(), helpFilter(row, query = query))
        }
    }

    @Test
    fun `non Latin and Cyrillic scripts keep substring matching`() {
        val examples = listOf(
            "深色模式（Android Auto）" to "模式",
            "ダークモード (Android Auto)" to "モード",
            "โหมดมืด (Android Auto)" to "มืด",
            "डार्क मोड (Android Auto)" to "मोड",
            "오디오 수신" to "디오",
            "الصوت" to "صوت"
        )
        for ((name, query) in examples) {
            val row = language.copy(nameOverride = name, value = "")
            assertEquals(name, listOf("general", "language"), helpFilter(row, query = query))
        }
    }

    @Test
    fun `Latin and Cyrillic word boundaries apply per term in multilingual text`() {
        val row = language.copy(nameOverride = "深色模式（Android Auto）", value = "")
        assertEquals(listOf("general", "language"), helpFilter(row, query = "auto 模式"))
        assertEquals(emptyList<String>(), helpFilter(row, query = "droid 模式"))

        for ((name, query) in listOf(
            "Übertragung" to "ber",
            "Écran" to "cran",
            "Điều khiển" to "iều",
            "Системний режим" to "темний"
        )) {
            assertEquals(
                name,
                emptyList<String>(),
                helpFilter(language.copy(nameOverride = name, value = ""), query = query)
            )
        }
    }

    @Test
    fun `search ignores Russian yo and e in both directions and cases`() {
        for (name in listOf("Приём звука", "Прием звука", "ПРИЁМ ЗВУКА", "ПРИЕМ ЗВУКА")) {
            val row = language.copy(nameOverride = name, value = "")
            for (query in listOf("прием", "приём", "ПРИЕМ", "ПРИЁМ", "  ЗВУКА   ПРИЁМ  ")) {
                assertEquals("$name / $query", listOf("general", "language"), helpFilter(row, query = query))
            }
        }
    }

    @Test
    fun `search normalizes every searchable row field`() {
        val row = language.copy(
            nameResId = AUDIO_SINK,
            value = "Приём звука",
            descriptionResId = AUDIO_SUMMARY,
            helpResId = AUDIO_HELP,
            searchKeywords = "Трёх каналов"
        )
        val localized = titles + mapOf(
            AUDIO_SUMMARY to "Всё включено",
            AUDIO_HELP to "Ещё подробнее"
        )
        fun shown(query: String) = SettingsFilterPolicy.filter(
                items = listOf(row),
                query = query,
                advanced = true,
                showsUsb = true,
                showsWifi = true,
                basicIds = emptySet(),
                text = localized::getValue
            ).map { it.stableId }

        for (query in listOf("звука прием", "включено все", "подробнее еще", "каналов трех")) {
            assertEquals(query, listOf("language"), shown(query))
        }
        for (query in listOf("прием все", "еще трех", "sink прием", "все еще")) {
            assertEquals(query, emptyList<String>(), shown(query))
        }
    }

    @Test
    fun `section and sub-header search use the same normalized terms`() {
        val localized = titles + mapOf(
            WIRELESS to "Беспроводной приём",
            ROUTE to "Трёхканальный маршрут"
        )
        fun shown(query: String, showsWifi: Boolean = true) = SettingsFilterPolicy.filter(
            items = withRoute,
            query = query,
            advanced = false,
            showsUsb = true,
            showsWifi = showsWifi,
            basicIds = emptySet(),
            text = localized::getValue
        ).map { it.stableId }

        assertEquals(
            listOf("wirelessConnection", "wifiMode", "bluetoothRoute", "relay"),
            shown("  ПРИЕМ   БЕСПРОВОДНОЙ ")
        )
        assertEquals(
            listOf("wirelessConnection", "bluetoothRoute", "relay"),
            shown("маршрут \t ТРЕХ")
        )
        assertEquals(emptyList<String>(), shown("прием беспроводной", showsWifi = false))
        assertEquals(emptyList<String>(), shown("прием missing"))
        assertEquals(emptyList<String>(), shown("маршрут missing"))
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


    private fun helpFilter(vararg rows: SettingItem, query: String): List<String> =
        SettingsFilterPolicy.filter(
            items = listOf(SettingItem.CategoryHeader(stableId = "general", titleResId = GENERAL)) + rows,
            query = query,
            advanced = true,
            showsUsb = true,
            showsWifi = true,
            basicIds = emptySet(),
            text = ::text
        ).map { it.stableId }

    private val audioSink = SettingItem.ToggleSettingEntry(
        stableId = "audioSink",
        nameResId = AUDIO_SINK,
        descriptionResId = AUDIO_SUMMARY,
        helpResId = AUDIO_HELP,
        isChecked = true,
        onCheckedChanged = {}
    )

    @Test
    fun `a query found only in the help text keeps the row and its header`() {
        assertEquals(listOf("general", "audioSink"), helpFilter(audioSink, query = "never use"))
    }

    @Test
    fun `a query found in no text drops the row`() {
        assertEquals(emptyList<String>(), helpFilter(audioSink, query = "zebra"))
    }

    @Test
    fun `calls finds Audio Sink although its summary lacks the word`() {
        assertEquals(false, text(AUDIO_SUMMARY).contains("calls", ignoreCase = true))
        assertEquals(listOf("general", "audioSink"), helpFilter(audioSink, query = "calls"))
    }

    @Test
    fun `a picker row is searched by its help text and its summary`() {
        val picker = SettingItem.SettingEntry(
            stableId = "videoCodec",
            nameResId = LIBUSB,
            value = "H.264",
            descriptionResId = PICKER_SUMMARY,
            helpResId = PICKER_HELP,
            onClick = {}
        )
        assertEquals(listOf("general", "videoCodec"), helpFilter(picker, query = "phone sends"))
        assertEquals(listOf("general", "videoCodec"), helpFilter(picker, query = "chooses the"))
    }

    @Test
    fun `a segmented row is searched by its help text`() {
        val segmented = SettingItem.SegmentedButtonSettingEntry(
            stableId = "mode",
            nameResId = WIFI_MODE,
            options = listOf("A", "B"),
            selectedIndex = 0,
            helpResId = PICKER_HELP,
            onOptionSelected = {}
        )
        assertEquals(listOf("general", "mode"), helpFilter(segmented, query = "video codec"))
    }

    private val relay = SettingItem.SettingEntry(stableId = "relay", nameResId = RELAY, value = "Off", onClick = {})

    /** A sub-header at the end of the wireless section, the way the Bluetooth route group sits. */
    private val withRoute = listOf(
        SettingItem.CategoryHeader(stableId = "general", titleResId = GENERAL),
        language,
        SettingItem.CategoryHeader(stableId = "wirelessConnection", titleResId = WIRELESS),
        wifiMode,
        SettingItem.SubHeader(stableId = "bluetoothRoute", titleResId = ROUTE),
        relay,
        SettingItem.CategoryHeader(stableId = "input", titleResId = INPUT),
        libusb
    )

    private fun routeFilter(query: String = "", basics: Set<String> = basicIds + "relay") =
        SettingsFilterPolicy.filter(
            items = withRoute, query = query, advanced = false, showsUsb = true, showsWifi = true,
            basicIds = basics, text = ::text
        ).map { it.stableId }

    @Test
    fun `a sub-header shows above its surviving row`() {
        assertEquals(
            listOf("general", "language", "wirelessConnection", "wifiMode", "bluetoothRoute", "relay", "input", "useLibusb"),
            routeFilter()
        )
    }

    @Test
    fun `a sub-header whose rows are all filtered out goes with them`() {
        assertEquals(
            listOf("general", "language", "wirelessConnection", "wifiMode", "input", "useLibusb"),
            routeFilter(basics = basicIds)
        )
    }

    @Test
    fun `searching a sub-header's title brings its rows and its section header`() {
        assertEquals(listOf("wirelessConnection", "bluetoothRoute", "relay"), routeFilter(query = "bluetooth route"))
    }

    @Test
    fun `a row found by search keeps its sub-header`() {
        assertEquals(listOf("wirelessConnection", "bluetoothRoute", "relay"), routeFilter(query = "esp32"))
    }

    @Test
    fun `the next section header ends a sub-header's group`() {
        assertEquals(listOf("input", "useLibusb"), routeFilter(query = "libusb"))
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
        const val AUDIO_SINK = 10
        const val AUDIO_SUMMARY = 11
        const val AUDIO_HELP = 12
        const val PICKER_SUMMARY = 13
        const val PICKER_HELP = 14
        const val DEVICE_SPECIFIC = 15
        const val ROUTE = 16
        const val RELAY = 17
    }
}
