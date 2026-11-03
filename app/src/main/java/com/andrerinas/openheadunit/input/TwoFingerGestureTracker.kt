package com.andrerinas.openheadunit.input

import kotlin.math.abs

internal class TwoFingerGestureTracker(
    private val firstX: Float,
    private val firstY: Float,
    private val secondX: Float,
    private val secondY: Float,
    private val width: Float,
    private val touchSlop: Float,
    private val downSwipeDistance: Float
) {
    enum class Swipe { LEFT_EDGE, RIGHT_EDGE, DOWN }

    var canLongPress = true
        private set

    fun move(x1: Float, y1: Float, x2: Float, y2: Float): Swipe? {
        val dx1 = x1 - firstX
        val dy1 = y1 - firstY
        val dx2 = x2 - secondX
        val dy2 = y2 - secondY
        if (dx1 * dx1 + dy1 * dy1 > touchSlop * touchSlop ||
            dx2 * dx2 + dy2 * dy2 > touchSlop * touchSlop) {
            canLongPress = false
        }
        return when {
            dy1 > downSwipeDistance && dy2 > downSwipeDistance &&
                abs(dx1) < dy1 / 2 && abs(dx2) < dy2 / 2 -> Swipe.DOWN
            firstX < 100 && dx1 > 200 && abs(dy1) < 100 -> Swipe.LEFT_EDGE
            firstX > width - 100 && dx1 < -200 && abs(dy1) < 100 -> Swipe.RIGHT_EDGE
            else -> null
        }
    }
}
