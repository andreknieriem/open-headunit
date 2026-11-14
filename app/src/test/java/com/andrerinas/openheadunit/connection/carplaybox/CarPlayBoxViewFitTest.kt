package com.andrerinas.openheadunit.connection.carplaybox

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlayBoxViewFitTest {

    @Test
    fun `contain letterboxes a wide picture in a narrower container`() {
        assertEquals(1024 to 576, CarPlayBoxViewFit.size(1024, 600, 1280, 720, Settings.VideoFitMode.CONTAIN))
    }

    @Test
    fun `contain pillarboxes a portrait picture`() {
        assertEquals(337 to 600, CarPlayBoxViewFit.size(1024, 600, 720, 1280, Settings.VideoFitMode.CONTAIN))
    }

    @Test
    fun `cover crops instead`() {
        assertEquals(1066 to 600, CarPlayBoxViewFit.size(1024, 600, 1280, 720, Settings.VideoFitMode.COVER))
    }

    @Test
    fun `fill takes the whole container and an unknown size changes nothing`() {
        assertEquals(1024 to 600, CarPlayBoxViewFit.size(1024, 600, 1280, 720, Settings.VideoFitMode.FILL))
        assertEquals(1024 to 600, CarPlayBoxViewFit.size(1024, 600, 0, 0, Settings.VideoFitMode.CONTAIN))
    }
}
