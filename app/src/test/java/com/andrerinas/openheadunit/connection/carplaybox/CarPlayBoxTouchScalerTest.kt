package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.input.TouchCoordinateMapper
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayBoxTouchScalerTest {

    private fun scaled(x: Float, y: Float, viewW: Float, viewH: Float, vw: Int, vh: Int): CarPlayBoxTouchScaler.BoxTouch {
        val c = TouchCoordinateMapper.map(x, y, viewW, viewH, vw, vh, 0f, 0f, Settings.VideoFitMode.CONTAIN, false)
        return CarPlayBoxTouchScaler.scale(c, vw, vh)
    }

    @Test
    fun `landscape video on a wider view maps the centre to the middle`() {
        val t = scaled(512f, 300f, 1024f, 600f, 1280, 720)
        assertEquals(5000, t.x)
        assertEquals(5000, t.y)
    }

    @Test
    fun `landscape letterbox clamps to the edges`() {
        assertEquals(0, scaled(512f, 2f, 1024f, 600f, 1280, 720).y)
        assertEquals(10000, scaled(512f, 598f, 1024f, 600f, 1280, 720).y)
    }

    @Test
    fun `portrait video maps a quarter point`() {
        val t = scaled(360f, 480f, 720f, 1600f, 720, 1280)
        assertEquals(5000, t.x)
        assertEquals(2500, t.y)
    }

    @Test
    fun `portrait pillarbox clamps to the sides`() {
        assertEquals(0, scaled(2f, 800f, 1024f, 600f, 720, 1280).x)
        assertEquals(10000, scaled(1022f, 800f, 1024f, 600f, 720, 1280).x)
    }
}
