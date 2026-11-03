package com.andrerinas.openheadunit.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceViewFitPolicyTest {

    private fun decide(
        sdkInt: Int = 19, isSurfaceView: Boolean = true, forcedScale: Boolean = false,
        paddingLeft: Int = 0, paddingTop: Int = 0, contentWidth: Int = 1280, contentHeight: Int = 800,
        scaleX: Float = 1.5005862f, scaleY: Float = 1.3508443f,
    ) = SurfaceViewFitPolicy.decide(
        sdkInt, isSurfaceView, forcedScale, paddingLeft, paddingTop, contentWidth, contentHeight, scaleX, scaleY,
    )

    @Test fun roundOneOnDSam() {
        assertEquals(SurfaceViewFitPolicy.Fit.Layout(1921, 1081, -320, -140, 1280, 800, 1f), decide())
    }

    @Test fun manualInsetsCentreOnPaddedArea() {
        val fit = decide(paddingLeft = 100, paddingTop = 40, contentWidth = 1180, contentHeight = 760, scaleX = 1.5f, scaleY = 1.35f)
        assertEquals(SurfaceViewFitPolicy.Fit.Layout(1770, 1026, -195, -93, 1180, 760, 1f), fit)
    }

    @Test fun mirrorKeepsItsSign() {
        val fit = decide(scaleX = -1.5005862f) as SurfaceViewFitPolicy.Fit.Layout
        assertEquals(1921, fit.width)
        assertEquals(-1f, fit.scaleXSign, 0f)
    }

    @Test fun apiBoundary() {
        assertTrue(decide(sdkInt = 23) is SurfaceViewFitPolicy.Fit.Layout)
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(sdkInt = 24))
    }

    @Test fun otherBackendsAndForcedStayOnScale() {
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(isSurfaceView = false))
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(forcedScale = true))
    }

    @Test fun noParentYet() {
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(contentWidth = 0, contentHeight = 0))
    }

    @Test fun badScale() {
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(scaleX = 0f))
        assertEquals(SurfaceViewFitPolicy.Fit.ViewScale, decide(scaleY = Float.NaN))
    }

    @Test fun identityScaleIsPlainFill() {
        assertEquals(SurfaceViewFitPolicy.Fit.Layout(1280, 800, 0, 0, 1280, 800, 1f), decide(scaleX = 1f, scaleY = 1f))
    }

    @Test fun watchdogReadsBufferNotView() {
        assertEquals(Pair(1280, 800), SurfaceViewFitPolicy.reportedSurfaceSize(1280, 800, 1921, 1081))
        assertEquals(Pair(1280, 800), SurfaceViewFitPolicy.reportedSurfaceSize(0, 0, 1280, 800))
        assertEquals(Pair(1921, 1081), SurfaceViewFitPolicy.reportedSurfaceSize(1280, 0, 1921, 1081))
    }

    @Test fun refitRules() {
        assertTrue(SurfaceViewFitPolicy.refitsOnParentLayout(true, 1280, 800, 800, 1280))
        assertTrue(SurfaceViewFitPolicy.refitsOnParentLayout(true, 1280, 800, 1180, 760))
        assertFalse(SurfaceViewFitPolicy.refitsOnParentLayout(true, 1280, 800, 1280, 800))
        assertFalse(SurfaceViewFitPolicy.refitsOnParentLayout(false, 1280, 800, 800, 1280))
        assertFalse(SurfaceViewFitPolicy.refitsOnParentLayout(true, 1280, 800, 0, 0))
    }
}
