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
    density: Float,
    edgeZoneDp: Float,
    private val secondDownMs: Long
) {
    enum class Swipe { LEFT_EDGE, RIGHT_EDGE }
    enum class Tap { LEFT_TAP, RIGHT_TAP }

    /** Which gesture slots have an action. */
    data class Enabled(
        val left: Boolean,
        val right: Boolean,
        val longPress: Boolean,
        val leftTap: Boolean,
        val rightTap: Boolean
    )

    private val edge = GestureDistances.edgeZonePx(density, edgeZoneDp)
    private val travel = GestureDistances.travelPx(density)
    private val sideways = GestureDistances.sidewaysLimitPx(density)
    private val pinchSpread = GestureDistances.pinchSpreadPx(density)
    private val startSpread = hypot(secondX - firstX, secondY - firstY)
    private var lastX1 = firstX
    private var lastY1 = firstY
    private var lastX2 = secondX
    private var lastY2 = secondY

    /** True while no finger has moved past touch slop: a long press or a tap needs it. */
    var still = true
        private set

    private val inLeftZone get() = firstX < edge
    private val inRightZone get() = firstX > width - edge

    /** Every gesture starts with the first finger in an edge zone. */
    val inEdgeZone get() = inLeftZone || inRightZone

    /** Whether this touch can still turn into one of the enabled gestures. */
    fun mayBecome(enabled: Enabled): Boolean = startsAt(firstX, width, edge, enabled)

    /** False once no enabled gesture can fire, such as after a pinch or a tap that took too long. */
    fun canStillFire(enabled: Enabled, nowMs: Long): Boolean {
        if (abs(hypot(lastX2 - lastX1, lastY2 - lastY1) - startSpread) > pinchSpread) return false
        val dx1 = lastX1 - firstX
        val dy1 = lastY1 - firstY
        return (enabled.left && inLeftZone && abs(dy1) <= sideways && dx1 >= -touchSlop) ||
            (enabled.right && inRightZone && abs(dy1) <= sideways && dx1 <= touchSlop) ||
            (enabled.longPress && inEdgeZone && still) ||
            mayTap(enabled, nowMs)
    }

    /** A tap needs every finger still and every finger up within [TAP_MS] of the second finger's down. */
    fun mayTap(enabled: Enabled, nowMs: Long): Boolean =
        still && nowMs - secondDownMs <= TAP_MS &&
            ((enabled.leftTap && inLeftZone) || (enabled.rightTap && inRightZone))

    /** How long a tap may still take at [nowMs]. */
    fun tapEndsInMs(nowMs: Long): Long = (secondDownMs + TAP_MS - nowMs).coerceAtLeast(0L)

    /** The tap that fires when the last finger lifts at [nowMs], or null. */
    fun tap(enabled: Enabled, nowMs: Long): Tap? = when {
        !mayTap(enabled, nowMs) -> null
        enabled.leftTap && inLeftZone -> Tap.LEFT_TAP
        else -> Tap.RIGHT_TAP
    }

    fun move(x1: Float, y1: Float, x2: Float, y2: Float): Swipe? {
        lastX1 = x1
        lastY1 = y1
        lastX2 = x2
        lastY2 = y2
        val dx1 = x1 - firstX
        val dy1 = y1 - firstY
        if (dx1 * dx1 + dy1 * dy1 > touchSlop * touchSlop || moved(x2, y2, secondX, secondY)) still = false
        return when {
            inLeftZone && dx1 > travel && abs(dy1) < sideways -> Swipe.LEFT_EDGE
            inRightZone && dx1 < -travel && abs(dy1) < sideways -> Swipe.RIGHT_EDGE
            else -> null
        }
    }

    /** The finger left down after the other lifted; only its stillness still matters. */
    fun moveOne(isFirst: Boolean, x: Float, y: Float) {
        if (if (isFirst) moved(x, y, firstX, firstY) else moved(x, y, secondX, secondY)) still = false
    }

    private fun moved(x: Float, y: Float, fromX: Float, fromY: Float): Boolean {
        val dx = x - fromX
        val dy = y - fromY
        return dx * dx + dy * dy > touchSlop * touchSlop
    }

    companion object {
        /** Short enough that a deliberate two-finger press on Android Auto still reaches it. */
        const val TAP_MS = 300L

        /** The same edge test as [mayBecome], for a first finger before a second one lands. */
        fun mayStartAt(x: Float, width: Float, density: Float, edgeZoneDp: Float, enabled: Enabled) =
            startsAt(x, width, GestureDistances.edgeZonePx(density, edgeZoneDp), enabled)

        private fun startsAt(x: Float, width: Float, edge: Float, enabled: Enabled): Boolean {
            val inLeft = x < edge
            val inRight = x > width - edge
            return ((enabled.left || enabled.leftTap) && inLeft) ||
                ((enabled.right || enabled.rightTap) && inRight) ||
                (enabled.longPress && (inLeft || inRight))
        }
    }
}
