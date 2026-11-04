package com.andrerinas.openheadunit.input

import com.andrerinas.openheadunit.utils.Settings.GestureEdgeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureDistancesTest {
    @Test fun distancesScaleWithDensityAboveTheFloor() {
        assertEquals(2f, GestureDistances.edgeZonePx(5f, 64f) / GestureDistances.edgeZonePx(2.5f, 64f), 0f)
        assertEquals(2f, GestureDistances.travelPx(5f) / GestureDistances.travelPx(2.5f), 0f)
        assertEquals(2f, GestureDistances.sidewaysLimitPx(5f) / GestureDistances.sidewaysLimitPx(2.5f), 0f)
        assertEquals(2f, GestureDistances.pinchSpreadPx(5f) / GestureDistances.pinchSpreadPx(2.5f), 0f)
    }

    @Test fun lowDensityKeepsTheOldPixelValues() {
        assertEquals(200f, GestureDistances.travelPx(1f), 0f)
        assertEquals(100f, GestureDistances.sidewaysLimitPx(1f), 0f)
        assertEquals(48f, GestureDistances.pinchSpreadPx(1f), 0f)
    }

    @Test fun theZoneWidthPerStepKeepsTheFloor() {
        val dpi160 = 1f
        val dpi240 = 1.5f
        assertEquals(listOf(100f, 100f, 100f), GestureEdgeZone.values().map { GestureDistances.edgeZonePx(dpi160, it.dp) })
        assertEquals(listOf(100f, 100f, 144f), GestureEdgeZone.values().map { GestureDistances.edgeZonePx(dpi240, it.dp) })
        assertEquals(listOf(144f, 192f, 288f), GestureEdgeZone.values().map { GestureDistances.edgeZonePx(3f, it.dp) })
    }

    @Test fun theStepsAreNarrowNormalAndWide() {
        assertEquals(listOf(48f, 64f, 96f), GestureEdgeZone.values().map { it.dp })
        assertEquals(GestureEdgeZone.NORMAL, GestureEdgeZone.fromInt(1))
    }
}
