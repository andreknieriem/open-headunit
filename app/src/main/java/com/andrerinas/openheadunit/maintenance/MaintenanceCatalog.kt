package com.andrerinas.openheadunit.maintenance

/**
 * The built-in items and their suggested intervals.
 *
 * The intervals are generic placeholders, not advice for any particular car: the owner's manual
 * always wins, and every value stays editable. Which items a car gets depends on two questions
 * asked at setup, the gearbox and the power steering.
 */
object MaintenanceCatalog {

    const val ENGINE_OIL = "engine_oil"
    const val MANUAL_GEARBOX_OIL = "manual_gearbox_oil"
    const val AUTOMATIC_GEARBOX_FLUID = "automatic_gearbox_fluid"
    const val CVT_FLUID = "cvt_fluid"
    const val BRAKE_FLUID = "brake_fluid"
    const val COOLANT = "coolant"
    const val POWER_STEERING_FLUID = "power_steering_fluid"
    const val AIR_FILTER = "air_filter"
    const val CABIN_FILTER = "cabin_filter"
    const val SPARK_PLUGS = "spark_plugs"
    const val TIMING_BELT = "timing_belt"
    const val TIRE_ROTATION = "tire_rotation"
    const val BATTERY_CHECK = "battery_check"
    const val FLUID_LEVELS = "fluid_levels"

    /** The share of an interval that counts as "coming up" unless the user changes it. */
    const val DEFAULT_WARN_FRACTION = 0.10

    /** A custom item starts with the engine oil's rhythm until the user edits it. */
    const val CUSTOM_KM = 10_000L
    const val CUSTOM_MONTHS = 12

    private const val DAYS_PER_MONTH = 30

    data class Template(val id: String, val km: Long?, val months: Int?)

    val templates: List<Template> = listOf(
        Template(ENGINE_OIL, 10_000, 12),
        Template(MANUAL_GEARBOX_OIL, 60_000, 48),
        Template(AUTOMATIC_GEARBOX_FLUID, 60_000, 48),
        Template(CVT_FLUID, 40_000, 36),
        Template(BRAKE_FLUID, 40_000, 24),
        Template(COOLANT, 60_000, 48),
        Template(POWER_STEERING_FLUID, 60_000, 48),
        Template(AIR_FILTER, 20_000, 24),
        Template(CABIN_FILTER, 15_000, 12),
        Template(SPARK_PLUGS, 40_000, 48),
        Template(TIMING_BELT, 90_000, 60),
        Template(TIRE_ROTATION, 10_000, 6),
        Template(BATTERY_CHECK, null, 12),
        Template(FLUID_LEVELS, 1_000, 1),
    )

    fun template(id: String?): Template? = templates.firstOrNull { it.id == id }

    /**
     * The starting list for a car. Every item starts as if it had just been serviced at
     * [odometerMeters] on [nowMs]; the user corrects the ones done earlier.
     *
     * The gearbox item matching the car is the only one added; hydraulic steering adds its fluid,
     * electric leaves it out, and "not sure" adds it turned off so it is there to switch on.
     */
    fun build(
        gearbox: Gearbox,
        steering: Steering,
        miles: Boolean,
        odometerMeters: Long,
        nowMs: Long,
    ): List<MaintenanceItem> = templates.mapNotNull { t ->
        val mode = when (t.id) {
            MANUAL_GEARBOX_OIL -> if (gearbox == Gearbox.MANUAL) MaintenanceMode.REMIND else null
            AUTOMATIC_GEARBOX_FLUID -> if (gearbox == Gearbox.AUTOMATIC) MaintenanceMode.REMIND else null
            CVT_FLUID -> if (gearbox == Gearbox.CVT) MaintenanceMode.REMIND else null
            POWER_STEERING_FLUID -> when (steering) {
                Steering.HYDRAULIC -> MaintenanceMode.REMIND
                Steering.UNSURE -> MaintenanceMode.OFF
                Steering.ELECTRIC -> null
            }
            else -> MaintenanceMode.REMIND
        } ?: return@mapNotNull null
        val intervalMeters = t.km?.let { defaultIntervalMeters(it, miles) }
        MaintenanceItem(
            id = t.id,
            templateId = t.id,
            customName = null,
            intervalMeters = intervalMeters,
            intervalMonths = t.months,
            warnMeters = defaultWarnMeters(intervalMeters),
            warnDays = defaultWarnDays(t.months),
            mode = mode,
            lastMeters = odometerMeters,
            lastMs = nowMs,
        )
    }

    /** The items whose presence depends on the gearbox or the steering. */
    private val carDependent = setOf(MANUAL_GEARBOX_OIL, AUTOMATIC_GEARBOX_FLUID, CVT_FLUID, POWER_STEERING_FLUID)

    /**
     * The list after the user changes the gearbox or the steering: the items that no longer fit
     * the car leave, the ones that now fit join as freshly serviced, and everything else (custom
     * items, history, edited intervals) is kept as it was. Built-in items keep the catalog order,
     * custom ones follow in the order they were added.
     */
    fun reconcile(
        items: List<MaintenanceItem>,
        gearbox: Gearbox,
        steering: Steering,
        miles: Boolean,
        odometerMeters: Long,
        nowMs: Long,
    ): List<MaintenanceItem> {
        val wanted = build(gearbox, steering, miles, odometerMeters, nowMs).filter { it.templateId in carDependent }
        val wantedIds = wanted.map { it.templateId }.toSet()
        val kept = items.filter { it.templateId !in carDependent || it.templateId in wantedIds }
        val keptIds = kept.mapNotNull { it.templateId }.toSet()
        val added = wanted.filter { it.templateId !in keptIds }
        return (kept + added).sortedBy { item ->
            val index = templates.indexOfFirst { it.id == item.templateId }
            if (index < 0) Int.MAX_VALUE else index
        }
    }

    /** A new item the user names, starting from [CUSTOM_KM] and [CUSTOM_MONTHS]. */
    fun custom(id: String, name: String, miles: Boolean, odometerMeters: Long, nowMs: Long): MaintenanceItem {
        val intervalMeters = defaultIntervalMeters(CUSTOM_KM, miles)
        return MaintenanceItem(
            id = id,
            templateId = null,
            customName = name,
            intervalMeters = intervalMeters,
            intervalMonths = CUSTOM_MONTHS,
            warnMeters = defaultWarnMeters(intervalMeters),
            warnDays = defaultWarnDays(CUSTOM_MONTHS),
            mode = MaintenanceMode.REMIND,
            lastMeters = odometerMeters,
            lastMs = nowMs,
        )
    }

    /**
     * The template's kilometers, or, for a user who reads miles, the same distance rounded to a
     * figure that reads like an interval (6,000 mi rather than 6,214 mi).
     */
    fun defaultIntervalMeters(km: Long, miles: Boolean): Long {
        if (!miles) return km * 1000
        val rawMiles = km * Distance.METERS_PER_KM / Distance.METERS_PER_MILE
        val step = if (rawMiles >= 5_000) 500.0 else 100.0
        val rounded = Math.max(step, Math.round(rawMiles / step) * step)
        return Distance.fromUnits(rounded.toLong(), true)
    }

    fun defaultWarnMeters(intervalMeters: Long?): Long =
        if (intervalMeters == null) 0L else Math.round(intervalMeters * DEFAULT_WARN_FRACTION)

    fun defaultWarnDays(months: Int?): Int =
        if (months == null) 0 else Math.max(1, Math.round(months * DAYS_PER_MONTH * DEFAULT_WARN_FRACTION).toInt())
}
