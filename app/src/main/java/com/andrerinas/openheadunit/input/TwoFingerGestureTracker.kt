package com.andrerinas.openheadunit.input

import kotlin.math.abs
import kotlin.math.hypot

internal class TwoFingerGestureTracker(
    private val firstX: Float,
    private val firstY: Float,
    private val secondX: Float,
    private val secondY: Float,
    private val width: Float,
    private val touchSlop: Float,
    density: Float
) {
    enum class Swipe { LEFT_EDGE, RIGHT_EDGE, DOWN }

    private val edge = GestureDistances.edgeZonePx(density)
    private val travel = GestureDistances.travelPx(density)
    private val sideways = GestureDistances.sidewaysLimitPx(density)
    private val downDistance = GestureDistances.downSwipePx(density)
    private val pinchSpread = GestureDistances.pinchSpreadPx(density)
    private val startSpread = hypot(secondX - firstX, secondY - firstY)
    private var lastX1 = firstX
    private var lastY1 = firstY
    private var lastX2 = secondX
    private var lastY2 = secondY

    var canLongPress = true
        private set

    private val inLeftZone get() = firstX < edge
    private val inRightZone get() = firstX > width - edge

    /** Every gesture starts with the first finger in an edge zone. */
    val inEdgeZone get() = inLeftZone || inRightZone

    /** Whether this touch can still turn into one of the enabled gestures. */
    fun mayBecome(left: Boolean, right: Boolean, down: Boolean, longPress: Boolean): Boolean =
        startsAt(firstX, width, edge, left, right, down, longPress)

    /** False once the last move shows no enabled gesture can fire, such as a pinch. */
    fun canStillFire(left: Boolean, right: Boolean, down: Boolean, longPress: Boolean): Boolean {
        if (abs(hypot(lastX2 - lastX1, lastY2 - lastY1) - startSpread) > pinchSpread) return false
        val dx1 = lastX1 - firstX
        val dy1 = lastY1 - firstY
        return (left && inLeftZone && abs(dy1) <= sideways && dx1 >= -touchSlop) ||
            (right && inRightZone && abs(dy1) <= sideways && dx1 <= touchSlop) ||
            (down && inEdgeZone && dy1 >= -touchSlop) ||
            (longPress && inEdgeZone && canLongPress)
    }

    fun move(x1: Float, y1: Float, x2: Float, y2: Float): Swipe? {
        lastX1 = x1
        lastY1 = y1
        lastX2 = x2
        lastY2 = y2
        val dx1 = x1 - firstX
        val dy1 = y1 - firstY
        val dx2 = x2 - secondX
        val dy2 = y2 - secondY
        if (dx1 * dx1 + dy1 * dy1 > touchSlop * touchSlop ||
            dx2 * dx2 + dy2 * dy2 > touchSlop * touchSlop) {
            canLongPress = false
        }
        return when {
            inEdgeZone && dy1 > downDistance && dy2 > downDistance &&
                abs(dx1) < dy1 / 2 && abs(dx2) < dy2 / 2 -> Swipe.DOWN
            inLeftZone && dx1 > travel && abs(dy1) < sideways -> Swipe.LEFT_EDGE
            inRightZone && dx1 < -travel && abs(dy1) < sideways -> Swipe.RIGHT_EDGE
            else -> null
        }
    }

    companion object {
        /** The same edge test as [mayBecome], for a first finger before a second one lands. */
        fun mayStartAt(x: Float, width: Float, density: Float, left: Boolean, right: Boolean, down: Boolean, longPress: Boolean) =
            startsAt(x, width, GestureDistances.edgeZonePx(density), left, right, down, longPress)

        private fun startsAt(x: Float, width: Float, edge: Float, left: Boolean, right: Boolean, down: Boolean, longPress: Boolean): Boolean {
            val inLeft = x < edge
            val inRight = x > width - edge
            return (left && inLeft) || (right && inRight) || ((down || longPress) && (inLeft || inRight))
        }
    }
}
