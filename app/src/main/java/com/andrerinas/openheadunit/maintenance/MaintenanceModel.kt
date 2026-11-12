package com.andrerinas.openheadunit.maintenance

/** What the app does with one maintenance item. */
enum class MaintenanceMode {
    /** Tracked, and shown when it needs attention. */
    REMIND,
    /** Tracked with its history and due date, but never shown as a reminder (the car warns already). */
    TRACK,
    /** Not tracked and hidden from the home. */
    OFF;

    companion object {
        fun fromName(name: String?): MaintenanceMode = values().firstOrNull { it.name == name } ?: REMIND
    }
}

enum class Gearbox { MANUAL, AUTOMATIC, CVT }

enum class Steering { HYDRAULIC, ELECTRIC, UNSURE }

/** One service done on an item: where the odometer stood (when known) and when. */
data class ServiceRecord(val atMeters: Long?, val atMs: Long)

/**
 * One thing to look after. Distances are kept in meters whatever unit the user reads them in,
 * so switching between kilometers and miles never rounds anything away.
 *
 * [templateId] names a built-in item (its label comes from resources); a custom item has
 * [customName] instead.
 */
data class MaintenanceItem(
    val id: String,
    val templateId: String?,
    val customName: String?,
    /** Null when this item is not due by distance. */
    val intervalMeters: Long?,
    /** Null when this item is not due by date. */
    val intervalMonths: Int?,
    /** How far before the due distance it starts showing as coming up. */
    val warnMeters: Long,
    /** How many days before the due date it starts showing as coming up. */
    val warnDays: Int,
    val mode: MaintenanceMode,
    val lastMeters: Long?,
    val lastMs: Long?,
    val history: List<ServiceRecord> = emptyList(),
    /** "Remind me later" by distance: quiet until the odometer reaches this. Cleared by a service. */
    val snoozeUntilMeters: Long? = null,
    /** "Remind me later" by date: quiet until this moment. Cleared by a service. */
    val snoozeUntilMs: Long? = null,
) {
    val byDistance: Boolean get() = intervalMeters != null
    val byTime: Boolean get() = intervalMonths != null
}

object Distance {
    const val METERS_PER_KM = 1000.0
    const val METERS_PER_MILE = 1609.344

    fun metersPerUnit(miles: Boolean): Double = if (miles) METERS_PER_MILE else METERS_PER_KM

    /** Whole units, rounded, for display and for the number the user types. */
    fun toUnits(meters: Long, miles: Boolean): Long = Math.round(meters / metersPerUnit(miles))

    fun fromUnits(units: Long, miles: Boolean): Long = Math.round(units * metersPerUnit(miles))
}
