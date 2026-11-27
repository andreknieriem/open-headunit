package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.BinaryPlist
import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.codec.Rtsp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AirPlayCommandsTest {

    @Test
    fun aHidReportNamesItsDeviceInLowerCaseHex() {
        val command = AirPlayCommands.hidSendReport(HidDevices.KNOB_UID, byteArrayOf(1, 2, 3, 4))
        assertEquals(listOf("type", "uuid", "hidReport"), command.entries.map { it.first })
        assertEquals(PlistValue.Text("hidSendReport"), command["type"])
        assertEquals(PlistValue.Text("2a2a2a2b"), command["uuid"])
        assertEquals(PlistValue.Data(byteArrayOf(1, 2, 3, 4)), command["hidReport"])
    }

    @Test
    fun aTouchCommandGoesToTheTouchDevice() {
        assertEquals(PlistValue.Text("2a2a2a2a"), AirPlayCommands.touch(ByteArray(12))["uuid"])
    }

    @Test
    fun forceKeyFrameHasEmptyParamsAndNoUuid() {
        val command = AirPlayCommands.forceKeyFrame()
        assertEquals(PlistValue.Text("forceKeyFrame"), command["type"])
        assertEquals(PlistValue.Dict(emptyList()), command["params"])
    }

    @Test
    fun theEventRequestParsesAsAPostToCommandWithItsSequenceNumber() {
        val command = AirPlayCommands.hidSendReport(HidDevices.TOUCH_UID, ByteArray(12) { it.toByte() })
        val wire = AirPlayCommands.eventRequest(command, 7)
        val text = String(wire, Charsets.ISO_8859_1)
        assertEquals(
            true,
            text.startsWith("POST /command RTSP/1.0\r\nContent-Type: application/x-apple-binary-plist\r\nContent-Length: "),
        )
        val parsed = Rtsp.parse(wire) as Rtsp.Parsed.Ok
        val request = parsed.requests.single()
        assertEquals("POST", request.method)
        assertEquals("/command", request.path)
        assertEquals("7", request.headers["cseq"])
        assertEquals(0, parsed.rest.size)
        assertEquals(command, BinaryPlist.decode(request.body))
        assertArrayEquals(BinaryPlist.encode(command), request.body)
    }

    @Test
    fun eachRequestCarriesTheSequenceNumberItWasGiven() {
        val one = String(AirPlayCommands.eventRequest(AirPlayCommands.forceKeyFrame(), 1), Charsets.ISO_8859_1)
        val two = String(AirPlayCommands.eventRequest(AirPlayCommands.forceKeyFrame(), 2), Charsets.ISO_8859_1)
        assertEquals(true, one.contains("CSeq: 1\r\n"))
        assertEquals(true, two.contains("CSeq: 2\r\n"))
    }
}
