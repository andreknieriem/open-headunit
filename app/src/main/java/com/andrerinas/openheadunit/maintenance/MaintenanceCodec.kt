package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.utils.AppLog
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * The items as one JSON string, so they live in a single preference and travel in the settings
 * backup with everything else. A broken entry is dropped on its own instead of losing the list.
 */
object MaintenanceCodec {

    private const val TAG = "MaintenanceCodec"

    fun encode(items: List<MaintenanceItem>): String {
        val array = JSONArray()
        // Loops rather than forEach: on a List, forEach resolves to Java's Iterable.forEach, API 24.
        for (item in items) {
            val o = JSONObject()
            o.put("id", item.id)
            item.templateId?.let { o.put("template", it) }
            item.customName?.let { o.put("name", it) }
            item.intervalMeters?.let { o.put("intervalM", it) }
            item.intervalMonths?.let { o.put("intervalMonths", it) }
            o.put("warnM", item.warnMeters)
            o.put("warnDays", item.warnDays)
            o.put("mode", item.mode.name)
            item.lastMeters?.let { o.put("lastM", it) }
            item.lastMs?.let { o.put("lastMs", it) }
            item.snoozeUntilMeters?.let { o.put("snoozeM", it) }
            item.snoozeUntilMs?.let { o.put("snoozeMs", it) }
            val history = JSONArray()
            for (record in item.history) {
                val r = JSONObject()
                record.atMeters?.let { r.put("m", it) }
                r.put("ms", record.atMs)
                history.put(r)
            }
            o.put("history", history)
            array.put(o)
        }
        return array.toString()
    }

    fun decode(stored: String?): List<MaintenanceItem> {
        if (stored.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(stored)
        } catch (e: JSONException) {
            AppLog.w("$TAG: the stored items are not readable, starting from an empty list: ${e.message}")
            return emptyList()
        }
        val items = mutableListOf<MaintenanceItem>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            try {
                items.add(decodeItem(o))
            } catch (e: JSONException) {
                AppLog.w("$TAG: dropping an unreadable item at position $i: ${e.message}")
            }
        }
        return items
    }

    private fun decodeItem(o: JSONObject): MaintenanceItem {
        val history = mutableListOf<ServiceRecord>()
        val h = o.optJSONArray("history")
        if (h != null) {
            for (i in 0 until h.length()) {
                val r = h.optJSONObject(i) ?: continue
                if (!r.has("ms")) continue
                history.add(ServiceRecord(r.optLongOrNull("m"), r.getLong("ms")))
            }
        }
        val templateId = o.optStringOrNull("template")
        val customName = o.optStringOrNull("name")
        if (templateId == null && customName == null) throw JSONException("item has neither a template nor a name")
        return MaintenanceItem(
            id = o.getString("id"),
            templateId = templateId,
            customName = customName,
            intervalMeters = o.optLongOrNull("intervalM"),
            intervalMonths = o.optLongOrNull("intervalMonths")?.toInt(),
            warnMeters = o.optLong("warnM", 0L),
            warnDays = o.optInt("warnDays", 0),
            mode = MaintenanceMode.fromName(o.optStringOrNull("mode")),
            lastMeters = o.optLongOrNull("lastM"),
            lastMs = o.optLongOrNull("lastMs"),
            history = history,
            snoozeUntilMeters = o.optLongOrNull("snoozeM"),
            snoozeUntilMs = o.optLongOrNull("snoozeMs"),
        )
    }

    private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null

    private fun JSONObject.optStringOrNull(key: String): String? = if (has(key) && !isNull(key)) getString(key) else null
}
