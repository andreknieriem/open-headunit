package com.andrerinas.openheadunit.maintenance

/**
 * What the reminders do at the two moments they speak up: when the unit starts and when the car
 * comes to a stop after a drive. The home line is always there; this decides the full screen
 * notice and the Android notification on top of it.
 *
 * Within one run of the unit an item the driver already answered ("next time I start", or a
 * notice closed) stays quiet until the next start, so a stop at every red light after a drive
 * does not bring the same notice back. "Remind me later" is kept with the item instead, by
 * distance or by date, so it outlives the run and the app.
 */
object MaintenanceNoticePolicy {

    enum class Moment { START, STOP }

    /** The choices "Remind me later" offers, in the user's units for distance. */
    val LATER_DISTANCE_UNITS = longArrayOf(200, 500, 1_000)
    val LATER_DAYS = intArrayOf(3, 7, 14)

    /** One "remind me later" choice: by distance or by days, never both. */
    data class Later(val meters: Long?, val days: Int?)

    data class Plan(val showScreen: Boolean, val notify: Boolean)

    fun isSnoozed(item: MaintenanceItem, odometerMeters: Long, nowMs: Long): Boolean =
        (item.snoozeUntilMeters != null && odometerMeters < item.snoozeUntilMeters) ||
            (item.snoozeUntilMs != null && nowMs < item.snoozeUntilMs)

    /**
     * The items to bring up, the most urgent first: overdue before coming up, as the home orders
     * them, leaving out what is snoozed and what was set aside in this run.
     */
    fun pending(
        summary: MaintenanceReminderPolicy.Summary,
        odometerMeters: Long,
        nowMs: Long,
        setAside: Set<String>,
    ): List<MaintenanceReminderPolicy.Entry> =
        (summary.overdue + summary.comingUp).filter {
            it.item.id !in setAside && !isSnoozed(it.item, odometerMeters, nowMs)
        }

    /**
     * Whether a moment shows the notice and posts the notification.
     *
     * The notice stays away while the settings are open (the user is busy configuring, maybe
     * this very feature) and when one is already on screen. A notification the user swiped away
     * in this run stays away until the next start.
     */
    fun plan(
        pendingCount: Int,
        screenWanted: Boolean,
        notificationWanted: Boolean,
        settingsOpen: Boolean,
        noticeShowing: Boolean,
        notificationDismissed: Boolean,
    ): Plan {
        if (pendingCount == 0) return Plan(showScreen = false, notify = false)
        return Plan(
            showScreen = screenWanted && !settingsOpen && !noticeShowing,
            notify = notificationWanted && !notificationDismissed,
        )
    }

    /** "Remind me later" choices that make sense for this item: by distance, by date, or both. */
    fun laterChoices(item: MaintenanceItem, miles: Boolean): List<Later> {
        val choices = mutableListOf<Later>()
        if (item.byDistance) {
            for (units in LATER_DISTANCE_UNITS) choices.add(Later(Distance.fromUnits(units, miles), null))
        }
        if (item.byTime) {
            for (days in LATER_DAYS) choices.add(Later(null, days))
        }
        return choices
    }

    fun snooze(item: MaintenanceItem, later: Later, odometerMeters: Long, nowMs: Long): MaintenanceItem = item.copy(
        snoozeUntilMeters = later.meters?.let { odometerMeters + it },
        snoozeUntilMs = later.days?.let { nowMs + it * MaintenanceDuePolicy.DAY_MS },
    )

    /**
     * The notice at a start waits for the first GPS fix, because a "start" can also be the app
     * coming back after Android killed it mid drive. True shows it now, false means the car is
     * moving and the next stop will show it instead. A fix without speed reads as standing.
     */
    fun startNoticeOnFix(speedMps: Float?): Boolean = speedMps == null || speedMps < OdometerStepPolicy.STILL_SPEED_MPS

    /** How long the start notice waits for a fix before it shows anyway (a garage, a cold GPS). */
    const val START_FIX_WAIT_MS = 60_000L

    /** Two starts this close are the same start heard twice (boot, a wake broadcast, screen on). */
    const val SAME_START_MS = 60_000L

    fun isNewStart(lastStartElapsedMs: Long, nowElapsedMs: Long): Boolean =
        lastStartElapsedMs == 0L || nowElapsedMs - lastStartElapsedMs >= SAME_START_MS
}
