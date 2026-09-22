package com.andrerinas.openheadunit.secondscreen.usbdisplay

import com.andrerinas.openheadunit.secondscreen.usbdisplay.UsbDisplayProtocol.Info
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The two vectors here are repeated in tools/second-screen/pi-receiver/test_ohud_protocol.py. */
class UsbDisplayProtocolTest {

    private fun hex(s: String): ByteArray = s.split(" ").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    private val panel = Info(version = 1, codecs = 1, widthPx = 1024, heightPx = 600, densityDpi = 160, maxFps = 30, flags = 1)
    private val panelBytes = hex("4F 48 55 44 01 01 00 04 58 02 A0 00 1E 01") + ByteArray(18)

    @Test
    fun `the info vector encodes and parses both ways`() {
        assertArrayEquals(panelBytes, UsbDisplayProtocol.encodeInfo(panel))
        assertEquals(panel, UsbDisplayProtocol.parseInfo(panelBytes, panelBytes.size))
        assertTrue(panel.sendsKeyframeRequests)
    }

    @Test
    fun `the frame header vector`() {
        assertArrayEquals(
            hex("4F 48 55 46 D2 04 00 00 04 03 02 01 01 00 00 00"),
            UsbDisplayProtocol.frameHeader(1234, 0x01020304, keyframe = true, config = false),
        )
        assertEquals(0x03, UsbDisplayProtocol.frameHeader(1, 0, keyframe = true, config = true)[12].toInt())
    }

    @Test
    fun `an answer that is short, foreign or without H264 is refused`() {
        assertNull(UsbDisplayProtocol.parseInfo(panelBytes, 13))
        assertNull(UsbDisplayProtocol.parseInfo(panelBytes.copyOf().also { it[0] = 'X'.code.toByte() }, 32))
        assertNull(UsbDisplayProtocol.parseInfo(UsbDisplayProtocol.encodeInfo(panel.copy(codecs = 2)), 32))
        assertNull(UsbDisplayProtocol.parseInfo(UsbDisplayProtocol.encodeInfo(panel.copy(widthPx = 0)), 32))
        assertNull(UsbDisplayProtocol.parseInfo(panelBytes, -1))
    }

    @Test
    fun `only the O D vendor triple is a display interface`() {
        assertTrue(UsbDisplayProtocol.isDisplayInterface(0xFF, 0x4F, 0x44))
        assertFalse(UsbDisplayProtocol.isDisplayInterface(0xFF, 0xFF, 0x00))
        assertFalse(UsbDisplayProtocol.isDisplayInterface(0xFF, 0x42, 0x01))
    }
}
