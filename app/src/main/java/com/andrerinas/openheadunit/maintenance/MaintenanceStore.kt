package com.andrerinas.openheadunit.maintenance

import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

/**
 * The odometer and the items, kept in the settings so they travel in the backup.
 *
 * The GPS distance is held here in memory and written about every [SAVE_EVERY_METERS] and when
 * the tracker asks (the car stopped, the service is going away), not on every fix: these units
 * lose power the moment the ignition goes off, so at most the last stretch is lost, and a write
 * every second would wear the flash to save a few meters. Everything that reads the odometer adds the part not written yet, and a
 * correction drops it, so a typed number is never followed by distance driven before it.
 *
 * One instance per process (see AppComponent): the tracker in the service and the screens share it.
 */
class MaintenanceStore(private val settings: Settings) {

    /** Uncorrected GPS meters counted but not written yet. */
    private var pendingRawMeters = 0.0

    /** When the tracker last got a usable fix (elapsed realtime), 0 when not in this process. */
    @Volatile
    var lastFixElapsedMs = 0L

    val enabled: Boolean get() = settings.maintenanceEnabled

    /** Shown in miles instead of kilometers. Everything is kept in meters, so nothing is converted. */
    var miles: Boolean
        get() = settings.maintenanceMiles
        set(value) { settings.maintenanceMiles = value }

    val gearbox: Gearbox
        get() = Gearbox.values().firstOrNull { it.name == settings.maintenanceGearbox } ?: Gearbox.MANUAL

    val steering: Steering
        get() = Steering.values().firstOrNull { it.name == settings.maintenanceSteering } ?: Steering.UNSURE

    val correctionFactor: Double get() = OdometerCorrectionPolicy.sanitize(settings.maintenanceCorrectionFactor)

    /** The factor in use was cut to the allowed range when it was worked out. */
    val correctionClamped: Boolean get() = settings.maintenanceCorrectionClamped

    @Synchronized
    fun odometerMeters(): Long = settings.maintenanceOdometerMeters + Math.round(pendingRawMeters * correctionFactor)

    /** Adds GPS distance; returns whether this write reached the settings. */
    @Synchronized
    fun addDistance(rawMeters: Double): Boolean {
        if (rawMeters <= 0) return false
        pendingRawMeters += rawMeters
        if (pendingRawMeters * correctionFactor < SAVE_EVERY_METERS) return false
        flush()
        return true
    }

    @Synchronized
    fun flush() {
        if (pendingRawMeters <= 0) return
        settings.maintenanceOdometerMeters = odometerMeters()
        settings.maintenanceRawSinceCorrectionMeters = settings.maintenanceRawSinceCorrectionMeters + pendingRawMeters
        pendingRawMeters = 0.0
    }

    fun items(): List<MaintenanceItem> = MaintenanceCodec.decode(settings.maintenanceItemsJson)

    fun item(id: String): MaintenanceItem? = items().firstOrNull { it.id == id }

    fun saveItems(items: List<MaintenanceItem>) {
        settings.maintenanceItemsJson = MaintenanceCodec.encode(items)
    }

    fun updateItem(item: MaintenanceItem) {
        saveItems(items().map { if (it.id == item.id) item else it })
    }

    fun removeItem(id: String) {
        saveItems(items().filterNot { it.id == id })
    }

    fun addCustomItem(name: String, nowMs: Long): MaintenanceItem {
        val item = MaintenanceCatalog.custom(CUSTOM_ID_PREFIX + nowMs, name, miles, odometerMeters(), nowMs)
        saveItems(items() + item)
        AppLog.i("$TAG: added a custom item")
        return item
    }

    /** Records a service: the history gains an entry and it becomes the item's last service. */
    fun markDone(id: String, atMeters: Long, atMs: Long) {
        val item = item(id) ?: return
        updateItem(item.copy(
            lastMeters = atMeters,
            lastMs = atMs,
            history = item.history + ServiceRecord(atMeters, atMs),
        ))
        AppLog.i("$TAG: ${item.templateId ?: "custom item"} marked as done")
    }

    /**
     * Turns the feature on for a car that has no items yet: the typed odometer starts the first
     * correction stretch and every item starts as freshly serviced.
     */
    @Synchronized
    fun setUp(odometerMeters: Long, miles: Boolean, gearbox: Gearbox, steering: Steering, nowMs: Long) {
        pendingRawMeters = 0.0
        settings.maintenanceMiles = miles
        settings.maintenanceGearbox = gearbox.name
        settings.maintenanceSteering = steering.name
        settings.maintenanceOdometerMeters = odometerMeters
        settings.maintenanceCorrectionBaseMeters = odometerMeters
        settings.maintenanceRawSinceCorrectionMeters = 0.0
        settings.maintenanceCorrectionFactor = 1.0
        settings.maintenanceCorrectionClamped = false
        settings.maintenanceLastCorrectionMs = nowMs
        settings.maintenanceMissedMeters = 0L
        saveItems(MaintenanceCatalog.build(gearbox, steering, miles, odometerMeters, nowMs))
        settings.maintenanceEnabled = true
        AppLog.i("$TAG: set up with gearbox=$gearbox steering=$steering miles=$miles")
    }

    /**
     * Turns the feature back on for a car that already has its items. Whatever was driven while
     * it was off went uncounted, so the correction stretch starts again with the next typed number.
     */
    @Synchronized
    fun resume() {
        pendingRawMeters = 0.0
        settings.maintenanceCorrectionBaseMeters = null
        settings.maintenanceRawSinceCorrectionMeters = 0.0
        settings.maintenanceEnabled = true
        AppLog.i("$TAG: turned back on with ${items().size} items kept")
    }

    @Synchronized
    fun turnOff() {
        flush()
        settings.maintenanceEnabled = false
        AppLog.i("$TAG: turned off, the items and the odometer are kept")
    }

    /** A different gearbox or steering swaps the items that depend on it and keeps the rest. */
    fun changeCar(gearbox: Gearbox, steering: Steering, nowMs: Long) {
        settings.maintenanceGearbox = gearbox.name
        settings.maintenanceSteering = steering.name
        saveItems(MaintenanceCatalog.reconcile(items(), gearbox, steering, miles, odometerMeters(), nowMs))
        AppLog.i("$TAG: car changed to gearbox=$gearbox steering=$steering")
    }

    /** The user typed the dashboard number. */
    @Synchronized
    fun correct(typedMeters: Long, nowMs: Long): OdometerCorrectionPolicy.Result {
        // What was counted before the number was read is already in it.
        val raw = settings.maintenanceRawSinceCorrectionMeters + pendingRawMeters
        pendingRawMeters = 0.0
        val result = OdometerCorrectionPolicy.correct(
            correctionFactor, settings.maintenanceCorrectionBaseMeters, raw, typedMeters
        )
        AppLog.i("$TAG: mileage corrected after ${Math.round(raw)} m of GPS; factor ${result.factor} " +
            "(updated=${result.updated}, clamped=${result.clamped}, new stretch=${result.restartStretch})")
        settings.maintenanceOdometerMeters = typedMeters
        settings.maintenanceLastCorrectionMs = nowMs
        settings.maintenanceMissedMeters = 0L
        if (result.updated) {
            settings.maintenanceCorrectionFactor = result.factor
            settings.maintenanceCorrectionClamped = result.clamped
        }
        if (result.restartStretch) {
            settings.maintenanceCorrectionBaseMeters = typedMeters
            settings.maintenanceRawSinceCorrectionMeters = 0.0
        } else {
            settings.maintenanceRawSinceCorrectionMeters = raw
        }
        return result
    }

    /**
     * The car moved while the app was not counting. The stretch no longer compares like with
     * like, so it ends here, and the home asks for a mileage check.
     */
    @Synchronized
    fun noteMissedDriving(meters: Long) {
        flush()
        settings.maintenanceMissedMeters = meters
        settings.maintenanceCorrectionBaseMeters = null
        settings.maintenanceRawSinceCorrectionMeters = 0.0
        AppLog.i("$TAG: the car moved about $meters m while the app was not counting; asking for a mileage check")
    }

    val missedMeters: Long get() = settings.maintenanceMissedMeters

    var lastPosition: Pair<Double, Double>?
        get() {
            val parts = settings.maintenanceLastPosition.split(',')
            if (parts.size != 2) return null
            val latitude = parts[0].toDoubleOrNull() ?: return null
            val longitude = parts[1].toDoubleOrNull() ?: return null
            return latitude to longitude
        }
        set(value) {
            settings.maintenanceLastPosition = value?.let { "${it.first},${it.second}" } ?: ""
        }

    private companion object {
        const val TAG = "MaintenanceStore"
        const val SAVE_EVERY_METERS = 1_000.0
        const val CUSTOM_ID_PREFIX = "custom_"
    }
}
