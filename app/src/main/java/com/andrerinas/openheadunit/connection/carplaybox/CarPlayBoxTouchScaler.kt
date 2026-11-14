package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.input.TouchCoordinate

/** Scales a touch in box video pixels to the box's 0..10000 range on each axis. */
object CarPlayBoxTouchScaler {

    const val ACTION_DOWN = 14
    const val ACTION_MOVE = 15
    const val ACTION_UP = 16
    const val RANGE = 10000

    class BoxTouch(val x: Int, val y: Int)

    fun scale(coordinate: TouchCoordinate, videoWidth: Int, videoHeight: Int): BoxTouch {
        val w = videoWidth.coerceAtLeast(1)
        val h = videoHeight.coerceAtLeast(1)
        return BoxTouch(
            x = (coordinate.x.toLong() * RANGE / w).toInt().coerceIn(0, RANGE),
            y = (coordinate.y.toLong() * RANGE / h).toInt().coerceIn(0, RANGE),
        )
    }
}
