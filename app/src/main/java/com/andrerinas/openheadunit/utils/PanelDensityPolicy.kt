package com.andrerinas.openheadunit.utils

/**
 * One DPI value per physical panel, for a device that switches panels (a foldable). Stays off
 * until a second panel size is read, so a unit with one panel keeps the global DPI value.
 */
object PanelDensityPolicy {

    /** getRealMetrics on the Activity's display is trusted from this API level. */
    const val MIN_SDK = 28

    /** [layout] is the one Auto works inside, as [Settings.dpiAutoLayout] holds it; empty is the plain Automatic. */
    data class Entry(val dpi: Int, val floorOverride: Boolean, val layout: String = "") {
        val pane: DisplayLayoutPolicy.Pane? get() = DisplayLayoutPolicy.Pane.fromStored(layout)
    }

    data class Panels(val firstKey: String?, val active: Boolean)

    /** The panel as "long x short" pixels, so a rotation keeps the key; null when unknown. */
    fun key(w: Int, h: Int, sdkInt: Int = MIN_SDK): String? {
        if (sdkInt < MIN_SDK || w <= 0 || h <= 0) return null
        return "${maxOf(w, h)}x${minOf(w, h)}"
    }

    /** The latch never turns off: one second key switches per-panel values on for good. */
    fun observe(state: Panels, key: String?): Panels = when {
        key == null -> state
        state.firstKey == null -> state.copy(firstKey = key)
        key != state.firstKey -> state.copy(active = true)
        else -> state
    }

    fun resolve(map: Map<String, Entry>, key: String?, active: Boolean, global: Entry): Entry {
        if (!active || key == null) return global
        return map[key] ?: global
    }

    /** The map to store, or null when the global DPI value takes the Save. */
    fun save(map: Map<String, Entry>, key: String?, active: Boolean, picked: Entry, global: Entry): Map<String, Entry>? {
        if (!active || key == null) return null
        if (picked == resolve(map, key, true, global)) return map
        return LinkedHashMap(map).also { it[key] = picked }
    }

    /** A bad string reads as an empty map, whole, so one bad entry never half-applies. */
    fun parse(stored: String?): Map<String, Entry> {
        if (stored.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, Entry>()
        for (part in stored.split(';')) {
            val eq = part.indexOf('=')
            if (eq <= 0) return emptyMap()
            val k = part.substring(0, eq)
            if (!KEY.matches(k)) return emptyMap()
            val at = part.indexOf('@')
            val layout = if (at < 0) "" else part.substring(at + 1)
            if (at >= 0 && !LAYOUT.matches(layout)) return emptyMap()
            var v = if (at < 0) part.substring(eq + 1) else part.substring(eq + 1, at)
            val override = v.endsWith("!")
            if (override) v = v.dropLast(1)
            if (v.isEmpty() || !v.all { it in '0'..'9' }) return emptyMap()
            out[k] = Entry(v.toIntOrNull() ?: return emptyMap(), override, layout)
        }
        return out
    }

    fun format(map: Map<String, Entry>): String =
        map.entries.joinToString(";") { (key, e) ->
            "$key=${e.dpi}${if (e.floorOverride) "!" else ""}${if (e.layout.isEmpty()) "" else "@${e.layout}"}"
        }

    private val KEY = Regex("\\d+x\\d+")
    private val LAYOUT = Regex("[A-Z_]+")
}
