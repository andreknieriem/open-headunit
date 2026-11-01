package com.andrerinas.openheadunit.view

import com.andrerinas.openheadunit.view.DimmerLayerPolicy.Kind.OTHER
import com.andrerinas.openheadunit.view.DimmerLayerPolicy.Kind.PROJECTION
import org.junit.Assert.assertEquals
import org.junit.Test

class DimmerLayerPolicyTest {
    @Test fun theDimmerSitsJustAboveTheProjection() {
        // projection, touch, night, loading, HUD
        assertEquals(1, DimmerLayerPolicy.index(listOf(PROJECTION, OTHER, OTHER, OTHER, OTHER)))
    }

    @Test fun aRebuiltProjectionKeepsTheDimmerBelowTheHudAndNight() {
        // touch, loading, projection, HUD, night
        assertEquals(3, DimmerLayerPolicy.index(listOf(OTHER, OTHER, PROJECTION, OTHER, OTHER)))
    }

    @Test fun withNoProjectionTheDimmerGoesLast() {
        assertEquals(2, DimmerLayerPolicy.index(listOf(OTHER, OTHER)))
    }
}
