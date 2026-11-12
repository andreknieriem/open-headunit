package com.andrerinas.openheadunit.maintenance

/**
 * The small banner over Android Auto while the car is moving: when it shows, for which item, and
 * where on the screen.
 *
 * It speaks up at most once per drive for each item, one item at a time with a gap between two,
 * and never while the car stands (the full screen notice at the stop is for that). A drive ends
 * at the stop [TripStopPolicy] detects, or with a new start of the unit.
 *
 * The items themselves come from [MaintenanceNoticePolicy.pending], so what is snoozed or was set
 * aside in this run stays quiet here too. Reading them decodes the saved items, so it is not done
 * on every GPS fix: [shouldLook] says when it is worth a look.
 */
object MaintenanceBannerPolicy {

    /** How long the banner stays on screen. */
    const val SHOW_MS = 6_000L

    /** Between two banners in the same drive, so a list of items is not read out back to back. */
    const val GAP_MS = 120_000L

    /** How often a moving car looks at the items again. They change by the kilometer, not the second. */
    const val LOOK_EVERY_MS = 30_000L

    /** Wider than this (width over height) counts as an ultrawide screen. */
    const val ULTRAWIDE_RATIO = 2.0

    /** Where the user asked for it, stored by name. */
    enum class Position {
        AUTO, TOP, ABOVE_BAR, SIDE;

        companion object {
            fun fromName(name: String?): Position = values().firstOrNull { it.name == name } ?: AUTO
        }
    }

    /** Where it actually goes on this screen. */
    enum class Placement { TOP, ABOVE_BAR, SIDE }

    data class State(
        /** Items already shown in this drive. */
        val shown: Set<String> = emptySet(),
        /** When the last banner showed (elapsed realtime), or null in this drive. */
        val lastShownMs: Long? = null,
        /** When the items were last looked at (elapsed realtime), or null in this drive. */
        val lastLookMs: Long? = null,
    )

    fun isMoving(speedMps: Float?): Boolean = speedMps != null && speedMps >= OdometerStepPolicy.STILL_SPEED_MPS

    /**
     * Whether this fix is worth reading the items for: the feature and the banner are on, Android
     * Auto is on screen, the car is moving, the gap since the last banner has passed, and the last
     * look is not too recent.
     */
    fun shouldLook(
        state: State,
        enabled: Boolean,
        bannerWanted: Boolean,
        projectionOnScreen: Boolean,
        speedMps: Float?,
        nowMs: Long,
    ): Boolean {
        if (!enabled || !bannerWanted || !projectionOnScreen || !isMoving(speedMps)) return false
        if (state.lastShownMs != null && nowMs - state.lastShownMs < GAP_MS) return false
        return state.lastLookMs == null || nowMs - state.lastLookMs >= LOOK_EVERY_MS
    }

    /** The look itself happened, whatever it found. */
    fun looked(state: State, nowMs: Long): State = state.copy(lastLookMs = nowMs)

    /** The most urgent pending item not shown yet in this drive, or null. */
    fun pick(state: State, pending: List<MaintenanceReminderPolicy.Entry>): MaintenanceReminderPolicy.Entry? =
        pending.firstOrNull { it.item.id !in state.shown }

    /** The banner for [id] made it to the screen. */
    fun shown(state: State, id: String, nowMs: Long): State =
        state.copy(shown = state.shown + id, lastShownMs = nowMs)

    /** The car stopped after a drive, or the unit started: every item may speak again. */
    fun driveEnded(): State = State()

    /**
     * Automatic is the top on the passenger side, or the side edge on an ultrawide screen, where
     * there is width to spare and the top is where Android Auto puts the turn card.
     */
    fun placement(position: Position, widthPx: Int, heightPx: Int): Placement = when (position) {
        Position.TOP -> Placement.TOP
        Position.ABOVE_BAR -> Placement.ABOVE_BAR
        Position.SIDE -> Placement.SIDE
        Position.AUTO ->
            if (heightPx > 0 && widthPx.toDouble() / heightPx >= ULTRAWIDE_RATIO) Placement.SIDE else Placement.TOP
    }

    /** The passenger sits on the left in a right-hand drive car. */
    fun passengerOnLeft(rightHandDrive: Boolean): Boolean = rightHandDrive
}
