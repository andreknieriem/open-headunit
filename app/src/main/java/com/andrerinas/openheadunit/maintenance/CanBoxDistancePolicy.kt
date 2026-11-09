package com.andrerinas.openheadunit.maintenance

/**
 * Turns the trip counters of a CAN box into distance driven.
 *
 * The counters are the dashboard's own, so they keep counting while the app is closed: the
 * difference from the last reading the app kept (the anchor) is the whole drive, gap included.
 * They also count like the dashboard, so the GPS correction factor never applies to them.
 *
 * What can go wrong, and what happens:
 * - A counter goes back (the user reset it, or it rolled over at the dashboard's limit). Nothing
 *   is subtracted. When the other counter still moves forward, it measures the stretch. When
 *   both went back, the new value is distance really driven since the reset, so it is counted
 *   (within the limits below); what came before the reset is unknown and not invented.
 * - The dashboard switched between km and miles: the numbers mean something else, so this
 *   reading becomes the new anchor without counting.
 * - A jump no car makes in the time it had: not counted, the reading becomes the new anchor.
 * - The GPS counted while the box was silent: the box's difference covers that time too, so what
 *   the GPS already added is taken off, never counting the same stretch twice.
 */
object CanBoxDistancePolicy {

    data class Anchor(
        val tripATenths: Long?,
        val tripBTenths: Long?,
        val miles: Boolean,
        /** Meters the GPS added to the odometer since this reading, while the box was silent. */
        val gpsMetersSince: Double,
        /** Wall clock time of this reading, to bound the gap to the next run. */
        val atWallMs: Long,
    )

    enum class Kind {
        /** No anchor yet: this reading becomes it. */
        FIRST,
        /** The counters did not move. */
        SAME,
        /** Counted from TRIP A, or from TRIP B when A is missing. */
        ADDED,
        /** One counter went back; the other one measured the stretch. */
        COUNTED_BY_OTHER,
        /** Both counters went back: only the distance since the reset is counted. */
        RESET,
        /** The dashboard changed units. */
        UNIT_CHANGED,
        /** More than the car could have driven. */
        IMPLAUSIBLE,
    }

    data class Step(
        val kind: Kind,
        /** Meters to add to the odometer, the GPS part already taken off. */
        val addMeters: Double,
        /** Meters the box measured for this step, before taking the GPS part off. */
        val boxMeters: Double,
        val anchor: Anchor,
    ) {
        /** The box measured everything since its anchor, so no driving went uncounted. */
        val coversGap: Boolean
            get() = kind == Kind.SAME || kind == Kind.ADDED || kind == Kind.COUNTED_BY_OTHER
    }

    /** About 250 km/h between two readings of the same run. */
    const val MAX_SPEED_MPS = 70.0

    /** About 160 km/h averaged over the time the app was closed. */
    const val MAX_GAP_SPEED_MPS = 45.0

    /** Room for the counters' 0.1 resolution and for readings that arrive in bursts. */
    const val SLACK_METERS = 300.0

    /** The bound when the clock cannot say how long the gap was (it went back, as on units without a clock battery). */
    const val MAX_UNTIMED_GAP_METERS = 1_000_000.0

    /**
     * @param sinceAnchorMs monotonic time since the anchor's reading when it was taken in this
     *   run, or null when the anchor comes from an earlier run (then the wall clock bounds it).
     */
    fun step(anchor: Anchor?, reading: TripReading, sinceAnchorMs: Long?, nowWallMs: Long): Step {
        val fresh = Anchor(reading.tripATenths, reading.tripBTenths, reading.miles, 0.0, nowWallMs)
        if (anchor == null) return Step(Kind.FIRST, 0.0, 0.0, fresh)
        if (anchor.miles != reading.miles) return Step(Kind.UNIT_CHANGED, 0.0, 0.0, fresh)

        val deltaA = delta(anchor.tripATenths, reading.tripATenths)
        val deltaB = delta(anchor.tripBTenths, reading.tripBTenths)
        val (kind, tenths) = when {
            deltaA != null && deltaA >= 0 -> (if (deltaA == 0L) Kind.SAME else Kind.ADDED) to deltaA
            deltaB != null && deltaB >= 0 -> when {
                deltaA != null -> Kind.COUNTED_BY_OTHER to deltaB
                deltaB == 0L -> Kind.SAME to 0L
                else -> Kind.ADDED to deltaB
            }
            deltaA != null || deltaB != null ->
                Kind.RESET to Math.max(reading.tripATenths ?: 0L, reading.tripBTenths ?: 0L)
            // The counter that was there is gone and the other one is new: nothing to compare.
            else -> Kind.FIRST to 0L
        }
        val boxMeters = tenths * Distance.metersPerUnit(reading.miles) / 10.0
        if (boxMeters > maxMeters(anchor, sinceAnchorMs, nowWallMs)) {
            return Step(Kind.IMPLAUSIBLE, 0.0, boxMeters, fresh)
        }
        if (kind == Kind.SAME) {
            // Nothing moved: keep the anchor, GPS part and time included, so a parked car that
            // later drives on is still measured from the reading the GPS part belongs to.
            return Step(Kind.SAME, 0.0, 0.0, anchor)
        }
        val add = Math.max(0.0, boxMeters - anchor.gpsMetersSince)
        return Step(kind, add, boxMeters, fresh)
    }

    /** The anchor as stored in the settings: "tripA,tripB,miles,gpsMeters,wallMs", a missing counter left empty. */
    fun encode(anchor: Anchor?): String {
        if (anchor == null) return ""
        return listOf(
            anchor.tripATenths?.toString() ?: "",
            anchor.tripBTenths?.toString() ?: "",
            if (anchor.miles) "1" else "0",
            anchor.gpsMetersSince.toString(),
            anchor.atWallMs.toString(),
        ).joinToString(",")
    }

    /** Null for an empty or damaged value: the next reading then starts a new anchor. */
    fun decode(text: String): Anchor? {
        val parts = text.split(',')
        if (parts.size != 5) return null
        val tripA = parts[0].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        val tripB = parts[1].takeIf { it.isNotEmpty() }?.let { it.toLongOrNull() ?: return null }
        if (tripA == null && tripB == null) return null
        val miles = when (parts[2]) {
            "1" -> true
            "0" -> false
            else -> return null
        }
        val gps = parts[3].toDoubleOrNull()?.takeIf { !it.isNaN() && it >= 0 } ?: return null
        val atWallMs = parts[4].toLongOrNull() ?: return null
        return Anchor(tripA, tripB, miles, gps, atWallMs)
    }

    private fun delta(before: Long?, now: Long?): Long? = if (before == null || now == null) null else now - before

    private fun maxMeters(anchor: Anchor, sinceAnchorMs: Long?, nowWallMs: Long): Double {
        if (sinceAnchorMs != null) return MAX_SPEED_MPS * Math.max(0L, sinceAnchorMs) / 1000.0 + SLACK_METERS
        val gapMs = nowWallMs - anchor.atWallMs
        if (gapMs < 0) return MAX_UNTIMED_GAP_METERS
        return MAX_GAP_SPEED_MPS * gapMs / 1000.0 + SLACK_METERS
    }
}
