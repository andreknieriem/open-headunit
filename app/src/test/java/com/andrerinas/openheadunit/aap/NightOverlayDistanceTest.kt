package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.NightOverlayDistance.Display
import org.junit.Assert.assertEquals
import org.junit.Test

class NightOverlayDistanceTest {

    @Test
    fun `below 1 km the distance is in whole metres`() {
        assertEquals(Display.Meters(0), NightOverlayDistance.of(0))
        assertEquals(Display.Meters(92), NightOverlayDistance.of(92))
        assertEquals(Display.Meters(999), NightOverlayDistance.of(999))
    }

    @Test
    fun `from 1 km to below 100 km it is kilometres with one decimal`() {
        assertEquals(Display.Kilometers(1.0), NightOverlayDistance.of(1000))
        assertEquals(Display.Kilometers(12.345), NightOverlayDistance.of(12_345))
        assertEquals(Display.Kilometers(99.949), NightOverlayDistance.of(99_949))
    }

    @Test
    fun `from 100 km it is whole kilometres`() {
        assertEquals(Display.WholeKilometers(100), NightOverlayDistance.of(100_000))
        assertEquals(Display.WholeKilometers(123), NightOverlayDistance.of(123_456))
        assertEquals(Display.WholeKilometers(124), NightOverlayDistance.of(123_500))
    }

    @Test
    fun `a distance that one decimal would round to 100 km is already whole kilometres`() {
        // 99.95 km with one decimal would be "100.0 km", wider than any string below 100 km.
        assertEquals(Display.WholeKilometers(100), NightOverlayDistance.of(99_950))
        assertEquals(Display.WholeKilometers(100), NightOverlayDistance.of(99_999))
    }
}
