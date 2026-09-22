package com.andrerinas.openheadunit.secondscreen.ms912x

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Ms912xModesTest {

    @Test
    fun `1366 in RGB888 is widened to a 4-byte row and nothing else is`() {
        assertEquals(1368, Ms912xModes.transferWidth(Ms912xMode.MODE_1366x768, Ms912xWireFormat.RGB888))
        assertEquals(1366, Ms912xModes.transferWidth(Ms912xMode.MODE_1366x768, Ms912xWireFormat.YUV422))
        for (mode in Ms912xMode.values()) for (format in Ms912xWireFormat.values()) {
            assertEquals(0, Ms912xModes.transferWidth(mode, format) * format.bytesPerPixel % 4)
        }
    }

    @Test
    fun `a frame is its padded rows times its height`() {
        assertEquals(1280 * 720 * 2, Ms912xModes.frameBytes(Ms912xMode.MODE_1280x720, Ms912xWireFormat.YUV422))
        assertEquals(1368 * 768 * 3, Ms912xModes.frameBytes(Ms912xMode.MODE_1366x768, Ms912xWireFormat.RGB888))
    }

    @Test
    fun `1080p RGB888 falls back to YUV because it does not fit the chip`() {
        assertFalse(Ms912xModes.supports(Ms912xMode.MODE_1920x1080, Ms912xWireFormat.RGB888))
        assertEquals(Ms912xWireFormat.YUV422, Ms912xModes.effectiveFormat(Ms912xMode.MODE_1920x1080, Ms912xWireFormat.RGB888))
        assertTrue(Ms912xModes.supports(Ms912xMode.MODE_1680x1050, Ms912xWireFormat.RGB888))
    }

    @Test
    fun `the colour byte carries the wire colorspace in both nibbles`() {
        assertEquals(0x22, Ms912xModes.colorIn(Ms912xWireFormat.YUV422))
        assertEquals(0x11, Ms912xModes.colorIn(Ms912xWireFormat.RGB888))
    }

    @Test
    fun `unknown stored values read as the defaults`() {
        assertEquals(Ms912xMode.MODE_1280x720, Ms912xModes.modeOf("MODE_4K"))
        assertEquals(Ms912xWireFormat.YUV422, Ms912xModes.formatOf(null))
    }
}
