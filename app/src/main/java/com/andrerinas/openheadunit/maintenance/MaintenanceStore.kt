package com.andrerinas.openheadunit.maintenance

import android.os.SystemClock
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
 * On a unit with a CAN box the box's trip counters count instead of the GPS whenever the box is
 * delivering (see [DistanceSourcePolicy]). Their anchor is written together with the odometer, so
 * after a power cut both are at the same point and the next reading adds exactly what was lost.
 *
 * One instance per process (see AppComponent): the tracker in the service and the screens share it.
 */
class MaintenanceStore(private val settings: Settings) {

    /** Uncorrected GPS meters counted but not written yet. */
    private var pendingRawMeters = 0.0

    /** When the tracker last got a usable fix (elapsed realtime), 0 when not in this process. */
    @Volatile
    var lastFixElapsedMs = 0L

    /** CAN box meters counted but not written yet. They count like the dashboard: no factor. */
    private var pendingBoxMeters = 0.0

    private var boxAnchor: CanBoxDistancePolicy.Anchor? = null
    private var boxAnchorLoaded = false

    /** When the anchor's reading came in this run (elapsed realtime), 0 when it is from an earlier run. */
    private var boxAnchorElapsedMs = 0L

    /** The anchor changed since it was last written. */
    private var boxAnchorDirty = false

    private var latestBoxReading: TripReading? = null

    /** When the box last delivered (elapsed realtime), 0 when it has not in this run. */
    @Volatile
    var lastBoxReadingElapsedMs = 0L
        private set

    /** When the box reader started in this run, 0 while it is not running. */
    @Volatile
    private var boxStartedElapsedMs = 0L

    /** Whether the box's first reading of this run measured the time the app was closed. */
    @Volatile
    private var boxFirstReadingCovered: Boolean? = null

    /** Whether this unit's box can be read; the Maintenance screen shows it. */
    @Volatile
    var boxAccess = DistanceSourcePolicy.Access.NO_BOX

    /** The source that counted last, to log the switches. */
    private var loggedSource = DistanceSourcePolicy.Source.GPS

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
    fun odometerMeters(): Long =
        settings.maintenanceOdometerMeters + Math.round(pendingRawMeters * correctionFactor + pendingBoxMeters)

    /**
     * Adds GPS distance, unless the CAN box is counting; returns whether this write reached the
     * settings.
     */
    @Synchronized
    fun addDistance(rawMeters: Double, nowElapsedMs: Long = SystemClock.elapsedRealtime()): Boolean {
        if (noteSource(nowElapsedMs) == DistanceSourcePolicy.Source.CAN_BOX) return false
        if (rawMeters <= 0) return false
        pendingRawMeters += rawMeters
        // The box will measure this stretch too when it talks again; remember what is already in.
        loadBoxAnchor()?.let {
            boxAnchor = it.copy(gpsMetersSince = it.gpsMetersSince + rawMeters * correctionFactor)
            boxAnchorDirty = true
        }
        return flushIfDue()
    }

    /** The source counting now: the box while it delivers, the GPS otherwise. */
    fun countingSource(nowElapsedMs: Long = SystemClock.elapsedRealtime()): DistanceSourcePolicy.Source =
        DistanceSourcePolicy.active(lastBoxReadingElapsedMs, nowElapsedMs)

    private fun noteSource(nowElapsedMs: Long): DistanceSourcePolicy.Source {
        val source = countingSource(nowElapsedMs)
        if (source != loggedSource) {
            AppLog.i(if (source == DistanceSourcePolicy.Source.CAN_BOX) {
                "$TAG: the CAN box is delivering, it counts the distance now"
            } else {
                "$TAG: the CAN box went silent, the GPS counts the distance until it is back"
            })
            loggedSource = source
        }
        return source
    }

    /** The box reader started: its first reading decides whether the closed time is covered. */
    @Synchronized
    fun boxStarted(nowElapsedMs: Long) {
        boxStartedElapsedMs = nowElapsedMs
        boxFirstReadingCovered = null
    }

    @Synchronized
    fun boxStopped() {
        boxStartedElapsedMs = 0L
        lastBoxReadingElapsedMs = 0L
        latestBoxReading = null
    }

    /** Whether the first GPS fix of this run should look for driving the app did not see. */
    fun gapCoverage(nowElapsedMs: Long): DistanceSourcePolicy.Coverage =
        DistanceSourcePolicy.gapCoverage(boxStartedElapsedMs, boxFirstReadingCovered, nowElapsedMs)

    /** Counts a CAN box reading. See [CanBoxDistancePolicy]. */
    @Synchronized
    fun addBoxReading(reading: TripReading, nowElapsedMs: Long, nowWallMs: Long): CanBoxDistancePolicy.Step {
        val anchor = loadBoxAnchor()
        val sinceAnchor = if (anchor != null && boxAnchorElapsedMs > 0) nowElapsedMs - boxAnchorElapsedMs else null
        val step = CanBoxDistancePolicy.step(anchor, reading, sinceAnchor, nowWallMs)
        latestBoxReading = reading
        lastBoxReadingElapsedMs = nowElapsedMs
        if (boxFirstReadingCovered == null) boxFirstReadingCovered = step.coversGap
        if (step.anchor !== anchor) {
            boxAnchor = step.anchor
            boxAnchorElapsedMs = nowElapsedMs
            boxAnchorDirty = true
        }
        noteSource(nowElapsedMs)
        pendingBoxMeters += step.addMeters
        flushIfDue()
        return step
    }

    private fun flushIfDue(): Boolean {
        if (pendingRawMeters * correctionFactor + pendingBoxMeters < SAVE_EVERY_METERS) return false
        flush()
        return true
    }

    @Synchronized
    fun flush() {
        if (pendingRawMeters > 0 || pendingBoxMeters > 0) {
            settings.maintenanceOdometerMeters = odometerMeters()
            settings.maintenanceRawSinceCorrectionMeters = settings.maintenanceRawSinceCorrectionMeters + pendingRawMeters
            settings.maintenanceBoxSinceCorrectionMeters = settings.maintenanceBoxSinceCorrectionMeters + pendingBoxMeters
            pendingRawMeters = 0.0
            pendingBoxMeters = 0.0
        }
        if (boxAnchorDirty) {
            settings.maintenanceBoxAnchor = CanBoxDistancePolicy.encode(boxAnchor)
            boxAnchorDirty = false
        }
    }

    private fun loadBoxAnchor(): CanBoxDistancePolicy.Anchor? {
        if (!boxAnchorLoaded) {
            boxAnchor = CanBoxDistancePolicy.decode(settings.maintenanceBoxAnchor)
            boxAnchorLoaded = true
        }
        return boxAnchor
    }

    /**
     * A typed or set up odometer already holds everything driven so far: the box starts again from
     * its current reading, or from its next one when it is not delivering right now.
     */
    private fun rebaseBox(nowElapsedMs: Long, nowWallMs: Long) {
        pendingBoxMeters = 0.0
        val reading = latestBoxReading
            ?.takeIf { countingSource(nowElapsedMs) == DistanceSourcePolicy.Source.CAN_BOX }
        boxAnchor = reading?.let {
            CanBoxDistancePolicy.Anchor(it.tripATenths, it.tripBTenths, it.miles, 0.0, nowWallMs)
        }
        boxAnchorLoaded = true
        boxAnchorElapsedMs = if (reading != null) lastBoxReadingElapsedMs else 0L
        boxAnchorDirty = false
        settings.maintenanceBoxAnchor = CanBoxDistancePolicy.encode(boxAnchor)
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
            snoozeUntilMeters = null,
            snoozeUntilMs = null,
        ))
        AppLog.i("$TAG: ${item.templateId ?: "custom item"} marked as done")
    }

    // --- reminders ---

    /** Items answered with "next time I start", or in a notice that was closed, in this run. */
    private val setAside = mutableSetOf<String>()

    /** The notification was swiped away in this run, so it waits for the next start. */
    @Volatile
    var notificationDismissed = false

    /** When the last start was handled (elapsed realtime), 0 before the first one. */
    private var lastStartElapsedMs = 0L

    /**
     * A new run of the unit: what was set aside in the last one comes back. Returns false for a
     * start heard twice (boot and wake broadcasts often arrive together).
     */
    @Synchronized
    fun beginRun(nowElapsedMs: Long): Boolean {
        if (!MaintenanceNoticePolicy.isNewStart(lastStartElapsedMs, nowElapsedMs)) return false
        lastStartElapsedMs = nowElapsedMs
        setAside.clear()
        notificationDismissed = false
        return true
    }

    @Synchronized
    fun setAside(ids: Collection<String>) {
        setAside.addAll(ids)
    }

    /** What the notice and the notification bring up now, the most urgent first. */
    @Synchronized
    fun pendingReminders(nowMs: Long): List<MaintenanceReminderPolicy.Entry> {
        val odometer = odometerMeters()
        val summary = MaintenanceReminderPolicy.summary(items(), odometer, nowMs)
        return MaintenanceNoticePolicy.pending(summary, odometer, nowMs, setAside.toSet())
    }

    fun snooze(id: String, later: MaintenanceNoticePolicy.Later, nowMs: Long) {
        val item = item(id) ?: return
        updateItem(MaintenanceNoticePolicy.snooze(item, later, odometerMeters(), nowMs))
        AppLog.i("$TAG: ${item.templateId ?: "custom item"} snoozed (meters=${later.meters}, days=${later.days})")
    }

    var remindScreen: Boolean
        get() = settings.maintenanceRemindScreen
        set(value) { settings.maintenanceRemindScreen = value }

    var remindNotification: Boolean
        get() = settings.maintenanceRemindNotification
        set(value) { settings.maintenanceRemindNotification = value }

    var remindBanner: Boolean
        get() = settings.maintenanceRemindBanner
        set(value) { settings.maintenanceRemindBanner = value }

    var bannerPosition: MaintenanceBannerPolicy.Position
        get() = MaintenanceBannerPolicy.Position.fromName(settings.maintenanceBannerPosition)
        set(value) { settings.maintenanceBannerPosition = value.name }

    /**
     * Turns the feature on for a car that has no items yet: the typed odometer starts the first
     * correction stretch and every item starts as freshly serviced.
     */
    @Synchronized
    fun setUp(odometerMeters: Long, miles: Boolean, gearbox: Gearbox, steering: Steering, nowMs: Long) {
        pendingRawMeters = 0.0
        rebaseBox(SystemClock.elapsedRealtime(), nowMs)
        settings.maintenanceBoxSinceCorrectionMeters = 0.0
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
        pendingBoxMeters = 0.0
        // Driving while off went uncounted by design, so the box does not bring it back either.
        boxAnchor = null
        boxAnchorLoaded = true
        boxAnchorElapsedMs = 0L
        boxAnchorDirty = false
        settings.maintenanceBoxAnchor = ""
        settings.maintenanceCorrectionBaseMeters = null
        settings.maintenanceRawSinceCorrectionMeters = 0.0
        settings.maintenanceBoxSinceCorrectionMeters = 0.0
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
    fun correct(
        typedMeters: Long,
        nowMs: Long,
        nowElapsedMs: Long = SystemClock.elapsedRealtime(),
    ): OdometerCorrectionPolicy.Result {
        // What was counted before the number was read is already in it.
        val raw = settings.maintenanceRawSinceCorrectionMeters + pendingRawMeters
        val box = settings.maintenanceBoxSinceCorrectionMeters + pendingBoxMeters
        pendingRawMeters = 0.0
        rebaseBox(nowElapsedMs, nowMs)
        val result = OdometerCorrectionPolicy.correct(
            correctionFactor, settings.maintenanceCorrectionBaseMeters, raw, typedMeters, box
        )
        AppLog.i("$TAG: mileage corrected after ${Math.round(raw)} m of GPS and ${Math.round(box)} m of CAN box; factor ${result.factor} " +
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
            settings.maintenanceBoxSinceCorrectionMeters = 0.0
        } else {
            settings.maintenanceRawSinceCorrectionMeters = raw
            settings.maintenanceBoxSinceCorrectionMeters = box
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
        settings.maintenanceBoxSinceCorrectionMeters = 0.0
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
