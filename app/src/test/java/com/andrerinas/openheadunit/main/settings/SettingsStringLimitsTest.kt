package com.andrerinas.openheadunit.main.settings

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test

/** Length and locale rules for the settings summaries and help texts, read from the resource files. */
class SettingsStringLimitsTest {

    private val res = File("src/main/res")
    private val githubRes = File("src/github/res")
    private val mainSources = File("src/main/java/com/andrerinas/openheadunit/main")

    private fun strings(file: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).associate { i ->
            val node = nodes.item(i)
            val raw = node.textContent
            node.attributes.getNamedItem("name").nodeValue to
                raw.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    private val defaults get() = strings(File(res, "values/strings.xml"))
    private val githubDefaults get() = strings(File(githubRes, "values/strings.xml"))

    /** Every `R.string.<key>` on lines that set [param], plus the next line for a split `if`. */
    private fun keysAfter(param: String): Set<String> {
        val keys = mutableSetOf<String>()
        mainSources.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { i, line ->
                if (!line.contains("$param =")) return@forEachIndexed
                val text = if (line.trimEnd().endsWith(",") || i + 1 >= lines.size) line else line + lines[i + 1]
                Regex("""R\.string\.(\w+)""").findAll(text).forEach { keys.add(it.groupValues[1]) }
            }
        }
        return keys
    }

    // Not a row summary: a format string with placeholders.
    private val notRowSummaries = setOf("onb_ready_summary")

    private val rewrittenHelp = listOf(
        "enable_audio_sink_description", "wireless_mode_description", "helper_strategy_help",
        "video_codec_help", "view_mode_help", "static_audio_focus_description",
        "stand_down_station_hint", "narrow_band_profile_cap_description",
        "external_bt_transport_description", "external_bt_blink_transport_description"
    )

    private val auditHelp = listOf(
        "audio_queue_capacity_help", "software_video_decoder_help", "custom_insets_help",
        "server_mode_help", "pixel_aspect_ratio_help", "log_source_help", "use_libusb_help",
        "force_software_decoding_help", "aaudio_output_help", "kill_on_disconnect_help",
        "bluetooth_address_help", "native_ap_transport_help", "native_driver_selection_help"
    )

    private val screenHelp = listOf(
        "auto_connect_screen_help", "dpi_screen_help", "keymap_screen_help",
        "customization_screen_help", "loading_screen_screen_help"
    )

    @Test
    fun `every summary string is at most 80 characters`() {
        val summaries = defaults.filterKeys { it.endsWith("_summary") && it !in notRowSummaries }
        assertTrue("the scan found only ${summaries.size} summary keys", summaries.size >= 40)
        val long = summaries.filterValues { it.length > 80 }.map { "${it.key} (${it.value.length})" }
        assertTrue("summaries over 80 characters: $long", long.isEmpty())
    }

    @Test
    fun `rewritten help texts and screen help texts are at most 500 characters`() {
        val all = defaults + githubDefaults
        val long = (rewrittenHelp + auditHelp + screenHelp).filter { (all[it]?.length ?: 0) > 500 }
        val missing = (rewrittenHelp + auditHelp + screenHelp).filter { it !in all }
        assertTrue("missing keys: $missing", missing.isEmpty())
        assertTrue("help texts over 500 characters: $long", long.isEmpty())
    }

    @Test
    fun `every string a row uses as its summary is at most 80 characters`() {
        val all = defaults + githubDefaults
        val keys = keysAfter("descriptionResId") + "keep_dummy_vpn_summary"
        assertTrue("the scan found only ${keys.size} row summaries", keys.size >= 40)
        val long = keys.filter { (all[it]?.length ?: 0) > 80 }.map { "$it (${all.getValue(it).length})" }
        val missing = keys.filter { it !in all }
        assertTrue("row summaries missing: $missing", missing.isEmpty())
        assertTrue("row summaries over 80 characters: $long", long.isEmpty())
    }

    @Test
    fun `every help and summary key a row uses exists in every locale`() {
        val keys = keysAfter("helpResId") + keysAfter("descriptionResId").filter { it.endsWith("_summary") } +
            rewrittenHelp + screenHelp + "setting_help_for"
        val githubOnly = setOf("keep_dummy_vpn_summary")
        val gaps = mutableListOf<String>()
        localeDirs().forEach { dir ->
            val have = strings(File(dir, "strings.xml")).keys
            keys.filter { it !in have && it !in githubDefaults }.forEach { gaps.add("${dir.name}: $it") }
        }
        githubRes.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .sortedBy { it.name }
            .forEach { dir ->
                val have = strings(File(dir, "strings.xml")).keys
                githubOnly.filter { it !in have }.forEach { gaps.add("github ${dir.name}: $it") }
            }
        assertTrue("missing translations (${gaps.size}): ${gaps.take(20)}", gaps.isEmpty())
    }

    private fun localeDirs(): List<File> =
        res.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").exists() }!!
            .sortedBy { it.name }
            .filter { it.name.removePrefix("values-").matches(Regex("[a-z]{2,3}(-r[A-Z]{2})?")) }

    /** The summary and help keys of each `stableId = "<id>"` row in [file], up to the next row. */
    private fun rowTextKeys(file: File): Map<String, Set<String>> {
        val lines = file.readLines()
        val rows = mutableMapOf<String, MutableSet<String>>()
        var current: MutableSet<String>? = null
        lines.forEachIndexed { i, line ->
            Regex("""stableId = "([^"]+)"""").find(line)?.let {
                current = rows.getOrPut(it.groupValues[1]) { mutableSetOf() }
            }
            val keys = current ?: return@forEachIndexed
            listOf("descriptionResId", "helpResId").forEach { param ->
                if (!line.contains("$param =")) return@forEach
                val text = if (line.trimEnd().endsWith(",") || i + 1 >= lines.size) line else line + lines[i + 1]
                Regex("""R\.string\.(\w+)""").findAll(text).forEach { m -> keys.add("$param:${m.groupValues[1]}") }
            }
        }
        return rows
    }

    @Test
    fun `a setting on the main and quick screens carries the same summary and help`() {
        val main = rowTextKeys(File(mainSources, "SettingsFragment.kt"))
        val quick = rowTextKeys(File(mainSources, "QuickSettingsFragment.kt"))
        val pairs = main.keys.filter { it in quick }.map { it to it } + ("startInFullscreenMode" to "fullscreenMode")
        assertTrue("the scan found only ${pairs.size} rows on both screens", pairs.size >= 10)
        val differ = pairs.filter { (m, q) -> main[m] != quick[q] }
            .map { (m, q) -> "$m ${main[m]} / $q ${quick[q]}" }
        assertTrue("rows that differ between the screens: $differ", differ.isEmpty())
    }

    @Test
    fun `hint rows that moved into help are gone`() {
        val hintIds = listOf(
            "fiveGhzChannelHint", "wifiDirectBandHint", "hotspotBandHint", "nativeApTransportHint",
            "mediaKeyRoutingHint", "playbackFocusModeHint", "autoDisconnectBtKillHint", "sunriseHelp_"
        )
        val left = listOf("SettingsFragment.kt", "AutoStartFragment.kt", "DarkModeFragment.kt").flatMap { name ->
            val text = File(mainSources, name).readText()
            hintIds.filter { text.contains(it) }.map { "$name: $it" }
        }
        assertTrue("hint rows still built: $left", left.isEmpty())

        val joined = mapOf(
            "media_key_routing_help" to listOf(
                "media_key_routing_always", "media_key_routing_auto", "media_key_routing_never"
            ),
            "playback_focus_mode_help" to listOf(
                "playback_focus_mode_auto", "playback_focus_mode_always", "playback_focus_mode_never"
            )
        )
        val gaps = mutableListOf<String>()
        (listOf(File(res, "values")) + localeDirs()).forEach { dir ->
            val have = strings(File(dir, "strings.xml"))
            joined.forEach { (key, labels) ->
                val text = have[key]
                if (text == null) gaps.add("${dir.name}: $key missing")
                else labels.filter { label -> have[label]?.let { text.contains(it) } != true }
                    .forEach { gaps.add("${dir.name}: $key lacks $it") }
            }
        }
        assertTrue("joined help gaps (${gaps.size}): ${gaps.take(20)}", gaps.isEmpty())
    }

    @Test
    fun `the experimental label is gone from the AAC and AAudio strings`() {
        val keys = listOf("use_aac_audio_description", "use_aac_audio_summary", "aaudio_output")
        val bad = mutableListOf<String>()
        (listOf(File(res, "values")) + localeDirs()).forEach { dir ->
            val have = strings(File(dir, "strings.xml"))
            keys.forEach { key ->
                val text = have[key]
                if (text == null) bad.add("${dir.name}: $key missing")
                else if (text.contains("(") || text.contains("\uFF08")) bad.add("${dir.name}: $key")
            }
        }
        assertTrue("strings that still carry a label (${bad.size}): ${bad.take(30)}", bad.isEmpty())
    }
}
