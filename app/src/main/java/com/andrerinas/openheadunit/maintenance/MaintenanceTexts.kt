package com.andrerinas.openheadunit.maintenance

import android.content.Context
import com.andrerinas.openheadunit.R
import java.text.NumberFormat
import java.util.Date
import java.util.Locale

/** How maintenance values read on screen, shared by the settings screens and the home. */
object MaintenanceTexts {

    private val itemNames = mapOf(
        MaintenanceCatalog.ENGINE_OIL to R.string.maintenance_item_engine_oil,
        MaintenanceCatalog.MANUAL_GEARBOX_OIL to R.string.maintenance_item_manual_gearbox_oil,
        MaintenanceCatalog.AUTOMATIC_GEARBOX_FLUID to R.string.maintenance_item_automatic_gearbox_fluid,
        MaintenanceCatalog.CVT_FLUID to R.string.maintenance_item_cvt_fluid,
        MaintenanceCatalog.BRAKE_FLUID to R.string.maintenance_item_brake_fluid,
        MaintenanceCatalog.COOLANT to R.string.maintenance_item_coolant,
        MaintenanceCatalog.POWER_STEERING_FLUID to R.string.maintenance_item_power_steering_fluid,
        MaintenanceCatalog.AIR_FILTER to R.string.maintenance_item_air_filter,
        MaintenanceCatalog.CABIN_FILTER to R.string.maintenance_item_cabin_filter,
        MaintenanceCatalog.SPARK_PLUGS to R.string.maintenance_item_spark_plugs,
        MaintenanceCatalog.TIMING_BELT to R.string.maintenance_item_timing_belt,
        MaintenanceCatalog.TIRE_ROTATION to R.string.maintenance_item_tire_rotation,
        MaintenanceCatalog.BATTERY_CHECK to R.string.maintenance_item_battery_check,
        MaintenanceCatalog.FLUID_LEVELS to R.string.maintenance_item_fluid_levels,
    )

    fun itemName(context: Context, item: MaintenanceItem): String =
        item.customName ?: itemNames[item.templateId]?.let { context.getString(it) } ?: item.id

    /** A distance in the user's unit, with the locale's grouping: "60,000 km". */
    fun distance(context: Context, meters: Long, miles: Boolean): String =
        context.getString(
            if (miles) R.string.maintenance_distance_mi else R.string.maintenance_distance_km,
            number(Distance.toUnits(meters, miles))
        )

    fun number(value: Long): String = NumberFormat.getIntegerInstance().format(value)

    fun date(context: Context, ms: Long): String =
        android.text.format.DateFormat.getMediumDateFormat(context).format(Date(ms))

    fun days(context: Context, days: Int): String =
        context.resources.getQuantityString(R.plurals.maintenance_days, days, days)

    fun months(context: Context, months: Int): String =
        context.resources.getQuantityString(R.plurals.maintenance_months, months, months)

    fun factor(factor: Double): String = String.format(Locale.getDefault(), "%.2f", factor)

    /** Where an item stands, for its row: "Coming up · Due at 60,000 km or on 14 Mar 2027". */
    fun status(context: Context, item: MaintenanceItem, odometerMeters: Long, nowMs: Long, miles: Boolean): String {
        val due = MaintenanceDuePolicy.evaluate(item, odometerMeters, nowMs)
        val label = when {
            item.mode == MaintenanceMode.OFF -> return context.getString(R.string.maintenance_mode_off)
            item.mode == MaintenanceMode.TRACK -> context.getString(R.string.maintenance_mode_track)
            else -> context.getString(when (due.state) {
                MaintenanceDuePolicy.State.OK -> R.string.maintenance_state_ok
                MaintenanceDuePolicy.State.COMING_UP -> R.string.maintenance_state_coming_up
                MaintenanceDuePolicy.State.OVERDUE -> R.string.maintenance_state_overdue
                MaintenanceDuePolicy.State.UNKNOWN -> R.string.maintenance_state_unknown
            })
        }
        val dueText = dueText(context, due, miles) ?: return label
        return context.getString(R.string.maintenance_item_value, label, dueText)
    }

    private fun dueText(context: Context, due: MaintenanceDuePolicy.Due, miles: Boolean): String? {
        val atDistance = due.dueMeters?.let { distance(context, it, miles) }
        val onDate = due.dueMs?.let { date(context, it) }
        return when {
            atDistance != null && onDate != null -> context.getString(R.string.maintenance_due_either, atDistance, onDate)
            atDistance != null -> context.getString(R.string.maintenance_due_at, atDistance)
            onDate != null -> context.getString(R.string.maintenance_due_on, onDate)
            else -> null
        }
    }

    /**
     * The home's line in two parts: the item's name, the one part that may be shortened, and the
     * line built around it. The state, the distance or the days left and the "+N" are what the
     * driver acts on, so on a narrow screen only the name gives up room.
     */
    class HomeLine(
        /** The item named on the line, or null for the kinds that name none. */
        val name: String?,
        private val build: (String) -> String,
    ) {
        val text: String get() = build(name.orEmpty())

        /** The line without the name: the part that always shows whole. */
        val rest: String get() = build("")

        fun withName(shownName: CharSequence): String = build(shownName.toString())
    }

    /** The home's line, or the text for its kind. */
    fun homeLine(context: Context, status: MaintenanceHomeStatusPolicy.Status, miles: Boolean): HomeLine {
        val entry = status.entry
        val line: (String) -> String = when (status.kind) {
            MaintenanceHomeStatusPolicy.Kind.OVERDUE -> { name -> context.getString(R.string.home_maintenance_overdue, name) }
            MaintenanceHomeStatusPolicy.Kind.COMING_UP_DISTANCE -> {
                val left = distance(context, entry!!.due.remainingMeters ?: 0L, miles)
                ({ name -> context.getString(R.string.home_maintenance_in, name, left) })
            }
            MaintenanceHomeStatusPolicy.Kind.COMING_UP_TIME -> {
                val left = days(context, wholeDays(entry!!.due.remainingMs ?: 0L))
                ({ name -> context.getString(R.string.home_maintenance_in, name, left) })
            }
            MaintenanceHomeStatusPolicy.Kind.LOCATION_OFF -> constant(context, R.string.home_maintenance_location_off)
            MaintenanceHomeStatusPolicy.Kind.CHECK_MILEAGE -> constant(context, R.string.home_maintenance_check_mileage)
            MaintenanceHomeStatusPolicy.Kind.ALL_GOOD -> constant(context, R.string.home_maintenance_all_good)
        }
        val name = entry?.let { itemName(context, it.item) }
        val build = if (status.more > 0) {
            { shown: String -> context.getString(R.string.home_maintenance_more, line(shown), status.more) }
        } else line
        return HomeLine(name, build)
    }

    /** The notice's headline: "Cabin filter is overdue", "Engine oil is coming up". */
    fun noticeTitle(context: Context, entry: MaintenanceReminderPolicy.Entry): String = context.getString(
        if (entry.due.state == MaintenanceDuePolicy.State.OVERDUE) R.string.maintenance_notice_overdue
        else R.string.maintenance_notice_coming_up,
        itemName(context, entry.item)
    )

    /** How far it is or how late, by distance, by date, or both: "820 km to go · 12 days to go". */
    fun noticeDetail(context: Context, entry: MaintenanceReminderPolicy.Entry, miles: Boolean): String {
        val parts = mutableListOf<String>()
        entry.due.remainingMeters?.let { left ->
            parts.add(
                if (left > 0) context.getString(R.string.maintenance_notice_to_go, distance(context, left, miles))
                else context.getString(R.string.maintenance_notice_distance_over, distance(context, -left, miles))
            )
        }
        entry.due.remainingMs?.let { left ->
            parts.add(
                if (left > 0) context.getString(R.string.maintenance_notice_to_go, days(context, wholeDays(left)))
                else context.getString(R.string.maintenance_notice_days_late, days(context, wholeDays(-left)))
            )
        }
        return parts.reduceOrNull { a, b -> context.getString(R.string.maintenance_item_value, a, b) }.orEmpty()
    }

    /** When it was last done: "Last done on 14 Jan 2026 at 75,035 km". */
    fun noticeLast(context: Context, item: MaintenanceItem, miles: Boolean): String {
        val at = item.lastMeters?.let { distance(context, it, miles) }
        val on = item.lastMs?.let { date(context, it) }
        return when {
            at != null && on != null -> context.getString(R.string.maintenance_notice_last_both, on, at)
            on != null -> context.getString(R.string.maintenance_notice_last_date, on)
            at != null -> context.getString(R.string.maintenance_notice_last_distance, at)
            else -> context.getString(R.string.maintenance_notice_last_none)
        }
    }

    /** A "remind me later" choice: "500 km", "3 days". */
    fun later(context: Context, later: MaintenanceNoticePolicy.Later, miles: Boolean): String =
        later.meters?.let { distance(context, it, miles) } ?: days(context, later.days ?: 0)

    private fun constant(context: Context, res: Int): (String) -> String {
        val text = context.getString(res)
        return { _ -> text }
    }

    /** Days left, rounded up: anything still due later today reads as one day. */
    private fun wholeDays(ms: Long): Int =
        Math.max(1L, (ms + MaintenanceDuePolicy.DAY_MS - 1) / MaintenanceDuePolicy.DAY_MS).toInt()
}
