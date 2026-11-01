package com.andrerinas.openheadunit.input

import org.junit.Assert.assertEquals
import org.junit.Test

class GestureDistancesTest {
    @Test fun distancesScaleWithDensityAboveTheFloor() {
        assertEquals(2f, GestureDistances.edgeZonePx(5f) / GestureDistances.edgeZonePx(2.5f), 0f)
        assertEquals(2f, GestureDistances.travelPx(5f) / GestureDistances.travelPx(2.5f), 0f)
        assertEquals(2f, GestureDistances.sidewaysLimitPx(5f) / GestureDistances.sidewaysLimitPx(2.5f), 0f)
        assertEquals(2f, GestureDistances.pinchSpreadPx(5f) / GestureDistances.pinchSpreadPx(2.5f), 0f)
    }

    @Test fun lowDensityKeepsTheOldPixelValues() {
        assertEquals(100f, GestureDistances.edgeZonePx(1f), 0f)
        assertEquals(200f, GestureDistances.travelPx(1f), 0f)
        assertEquals(100f, GestureDistances.sidewaysLimitPx(1f), 0f)
        assertEquals(100f, GestureDistances.edgeZonePx(2f), 0f)
        assertEquals(48f, GestureDistances.pinchSpreadPx(1f), 0f)
    }

    @Test fun downSwipeIsEightyDp() {
        assertEquals(80f, GestureDistances.downSwipePx(1f), 0f)
        assertEquals(200f, GestureDistances.downSwipePx(2.5f), 0f)
    }
}
