package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Diagnostic wording must distinguish observed bytes and state from successful protocol exchange. */
class ZbtProbeTest {

    private fun verdict(
        rfcommFrames: Int = 0,
        rfcommBytes: Int = 0,
        observedDataAfterVersionRequest: Boolean = false,
        linkedType: Int? = null,
        controlFrames: Int = 0,
        phoneConnected: Boolean = false,
        phoneName: String? = null,
        askedToReconnect: Boolean = false
    ) = ZbtProbe.verdict(
        rfcommFrames, rfcommBytes, observedDataAfterVersionRequest, linkedType,
        controlFrames, phoneConnected, phoneName, askedToReconnect
    )

    // ---------------------------------------------------------------------------------------------
    // The verdict
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `data after the request is not a verified matching version response`() {
        val v = verdict(
            rfcommFrames = 4, rfcommBytes = 96, observedDataAfterVersionRequest = true,
            phoneConnected = true, phoneName = "HONOR Magic8 Lite"
        )
        assertTrue(v, v.contains("after the version request"))
        assertTrue(v, v.contains("matching WPP response was not verified"))
        assertFalse(v, v.contains("phone answered"))
        assertTrue(v, v.contains("4 RFCOMM message(s)") && v.contains("96 bytes"))
        assertTrue(v, v.contains("HONOR Magic8 Lite"))
    }

    @Test
    fun `bytes one way is reported as such rather than rounded up to success`() {
        val v = verdict(rfcommFrames = 2, rfcommBytes = 30, observedDataAfterVersionRequest = false)
        assertTrue(v, v.contains("2 RFCOMM message(s)"))
        assertTrue(v, v.contains("response was not verified"))
        assertTrue(v, !v.contains("both ways"))
    }

    @Test
    fun `a reported link with no data does not identify another owner`() {
        val v = verdict(linkedType = 2, phoneConnected = true, phoneName = "HONOR Magic8 Lite", controlFrames = 9)
        assertTrue(v, v.contains("reported a projection link"))
        assertTrue(v, v.contains("link type 2"))
        assertTrue(v, v.contains("no RFCOMM data"))
        assertFalse(v, v.contains("one program at a time"))
    }

    @Test
    fun `a link with no data outranks the phone simply being connected`() {
        // Both conditions hold in this case, and the projection link is the more specific one.
        val v = verdict(linkedType = 1, phoneConnected = true)
        assertTrue(v, v.contains("no RFCOMM data"))
        assertFalse(v, v.contains("one program at a time"))
    }

    @Test
    fun `connected phone status does not prove whether the phone started Android Auto`() {
        val v = verdict(phoneConnected = true, phoneName = "HONOR Magic8 Lite", controlFrames = 15)
        assertTrue(v, v.contains("observed no projection link or RFCOMM data"))
        assertTrue(v, !v.contains("one program at a time"))
    }

    @Test
    fun `being asked to reconnect and still doing nothing is worth saying out loud`() {
        val asked = verdict(phoneConnected = true, askedToReconnect = true)
        val notAsked = verdict(phoneConnected = true, askedToReconnect = false)
        assertTrue(asked, asked.contains("even after requesting a reconnect"))
        assertTrue(notAsked, !notAsked.contains("even after requesting a reconnect"))
    }

    @Test
    fun `no phone at all is not reported as a failure of the module`() {
        val v = verdict(controlFrames = 7)
        assertTrue(v, v.contains("no current report of a connected phone"))
        assertTrue(v, v.contains("7 status message(s)"))
    }

    @Test
    fun `the phone's name is left out when the module never gave us one`() {
        assertTrue(!verdict(rfcommFrames = 1, rfcommBytes = 8).contains("()"))
        assertTrue(!verdict(linkedType = 2).contains("()"))
        assertTrue(!verdict(phoneConnected = true).contains("()"))
    }

    // ---------------------------------------------------------------------------------------------
    // Naming what arrived
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `a real WPP frame is recognised as one`() {
        // WifiVersionRequest{major=1, minor=1} in the framing NativeAaHandshakeManager already uses.
        val payload = byteArrayOf(0x08, 0x01, 0x10, 0x01)
        val frame = WppFraming.encodeFrame(payload, WppMessageType.VERSION_REQUEST)
        assertEquals(
            "(reads as WPP type ${WppMessageType.VERSION_REQUEST}, 4 byte payload)",
            ZbtProbe.describeWpp(frame)
        )
    }

    @Test
    fun `a payload whose declared length disagrees with its size is not called WPP`() {
        // The check that stops the log claiming a coincidence is a protocol. Four bytes that happen
        // to look like a header are common; four that also predict the rest of the frame are not.
        val lying = WppFraming.encodeHeader(99, WppMessageType.START_REQUEST) + byteArrayOf(1, 2, 3)
        val described = ZbtProbe.describeWpp(lying)
        assertTrue(described, described.startsWith("(not WPP framing"))
        assertTrue(described, described.contains("declares 99 of 3"))
    }

    @Test
    fun `a payload with an unknown message type is not called WPP`() {
        val odd = WppFraming.encodeFrame(byteArrayOf(1, 2), type = 0x4142)
        assertTrue(ZbtProbe.describeWpp(odd).startsWith("(not WPP framing"))
    }

    @Test
    fun `a payload too short to hold a header says so instead of guessing`() {
        assertEquals("(too short to be a WPP frame)", ZbtProbe.describeWpp(byteArrayOf(0x00, 0x04)))
        assertEquals("(too short to be a WPP frame)", ZbtProbe.describeWpp(ByteArray(0)))
    }

    @Test
    fun `an empty WPP payload is still recognised`() {
        // Several of these messages carry no fields at all, so a zero-length payload is ordinary.
        val frame = WppFraming.encodeFrame(ByteArray(0), WppMessageType.PING_REQUEST)
        assertEquals(
            "(reads as WPP type ${WppMessageType.PING_REQUEST}, 0 byte payload)",
            ZbtProbe.describeWpp(frame)
        )
    }

    @Test
    fun `a carrier claim is not reported as a working phone connection`() {
        val said = ZbtProbe.busyCarrierVerdict()
        assertTrue(said, said.contains("using or opening"))
        assertTrue(said, said.contains("did not open a second connection"))
        assertFalse(said, said.contains("the route works"))
    }

    @Test
    fun `a diagnostic yields without claiming the carrier has connected`() {
        val said = ZbtProbe.carrierTookOverVerdict()
        assertTrue(said, said.contains("gave the connection back"))
        assertTrue(said, said.contains("does not confirm"))
        assertFalse(said, said.contains("started carrying"))
    }

    @Test
    fun `a refused port describes this attempt rather than installed hardware`() {
        val said = ZbtProbe.notConnectedVerdict(ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING)
        assertTrue(said, said.contains("refused on this attempt"))
        assertFalse(said, said.contains("has no vendor Bluetooth daemon"))
    }

    @Test
    fun `timeout and unclassified failures do not prove a listener or another owner`() {
        for (verdict in listOf(ZbtReachabilityPolicy.Verdict.LISTENING_SILENT, null)) {
            val said = ZbtProbe.notConnectedVerdict(verdict)
            assertTrue(said, said.contains("Could not open"))
            assertFalse(said, said.contains("Something is listening"))
            assertFalse(said, said.contains("Bluetooth off"))
        }
    }
}
