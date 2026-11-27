package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport
import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.utils.Settings

/**
 * Turns the fingers on the screen into the two contacts the iPhone's touch device has. A finger keeps its slot
 * (id 0 or 1) from its first down to its up, a third finger is ignored, and a slot is free again after its up report.
 */
class CarPlayNativeTouch {
    /** One pointer of a motion event, in view pixels. [down] is false for the pointer that lifts in this event. */
    class Point(val pointerId: Int, val x: Float, val y: Float, val down: Boolean)

    private val slotOf = HashMap<Int, Int>()

    /** Contacts in pixels of the picture ([videoWidth] by [videoHeight]), mapped by the same fit rule as the view. */
    fun contacts(
        points: List<Point>,
        viewWidth: Float, viewHeight: Float,
        videoWidth: Int, videoHeight: Int,
        fitMode: Settings.VideoFitMode,
    ): List<AirPlayTouchReport.Contact> {
        val out = ArrayList<AirPlayTouchReport.Contact>(MAX_CONTACTS)
        for (point in points) {
            val slot = slotOf[point.pointerId] ?: run {
                if (!point.down) return@run null
                (0 until MAX_CONTACTS).firstOrNull { it !in slotOf.values }?.also { slotOf[point.pointerId] = it }
            } ?: continue
            val mapped = TouchCoordinateMapper.map(
                point.x, point.y, viewWidth, viewHeight, videoWidth, videoHeight, 0f, 0f, fitMode, hudMirroring = false,
            )
            out.add(AirPlayTouchReport.Contact(slot, mapped.x.toDouble(), mapped.y.toDouble(), point.down))
            if (!point.down) slotOf.remove(point.pointerId)
        }
        return out
    }

    /** Forgets every finger, for a new session. */
    fun reset() = slotOf.clear()

    companion object {
        const val MAX_CONTACTS = 2
    }
}
