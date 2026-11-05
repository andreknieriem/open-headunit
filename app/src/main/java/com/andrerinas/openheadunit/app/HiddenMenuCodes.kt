package com.andrerinas.openheadunit.app

/**
 * Codes that open a head unit's hidden developer or factory menu, where most units keep ADB over
 * Wi-Fi (#1122). Many units have no internet, so the list ships with the app; the full list lives
 * in docs/head-unit-codes.md and grows from what people report.
 *
 * Only [Code.tested] ones were confirmed on a real unit by us. The rest come from forum reports and
 * may not open anything on a given unit, since the code depends on the firmware, not the brand.
 */
object HiddenMenuCodes {

    enum class Opens { ADB_MENU, DEVELOPER_OPTIONS, FACTORY_SETTINGS }

    data class Code(
        val code: String,
        /** The brands, models or platforms it was reported on; null for generic units. */
        val units: String?,
        val opens: Opens,
        val tested: Boolean,
        /** Lowercase pieces of what a unit reports (device, platform, firmware) that point to it. */
        val markers: List<String> = emptyList()
    )

    val ALL = listOf(
        Code("9569", "Reglink (CMZX65)", Opens.ADB_MENU, tested = true, markers = listOf("cmzx")),
        Code("3368", "Pumpkin, UIS7862", Opens.DEVELOPER_OPTIONS, tested = false, markers = listOf("ums512")),
        Code("7890", null, Opens.DEVELOPER_OPTIONS, tested = false),
        Code("126", "Joying, Xtrons, Pumpkin", Opens.FACTORY_SETTINGS, tested = false),
        Code("8888", "Topway TS10 / TS18, 8227L", Opens.FACTORY_SETTINGS, tested = false, markers = listOf("8227l")),
        Code("123456", "Topway TS10, Pumpkin", Opens.FACTORY_SETTINGS, tested = false),
        Code("1617", "UIS7862 / UMS512", Opens.FACTORY_SETTINGS, tested = false, markers = listOf("ums512")),
        Code("000000", "FYT UIS8581A", Opens.FACTORY_SETTINGS, tested = false),
        Code("1234", null, Opens.FACTORY_SETTINGS, tested = false)
    )

    /** The codes whose markers show up in what the unit reports about itself, tested ones first. */
    fun forUnit(reported: List<String>, codes: List<Code> = ALL): List<Code> {
        val text = reported.joinToString(" ").lowercase()
        if (text.isBlank()) return emptyList()
        return codes.filter { c -> c.markers.any { text.contains(it) } }.sortedByDescending { it.tested }
    }

    /** Codes that contain [query] in the code, the units or the markers. A blank query keeps all. */
    fun search(query: String, codes: List<Code> = ALL): List<Code> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return codes
        return codes.filter { c ->
            c.code.contains(q) || (c.units?.lowercase()?.contains(q) ?: false) || c.markers.any { it.contains(q) }
        }
    }
}
