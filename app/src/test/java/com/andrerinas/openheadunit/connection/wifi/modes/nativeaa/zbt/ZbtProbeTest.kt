package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppFraming
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.WppMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The probe's two judgements, which are the whole point of the run: what arrived, and what that
 * means.
 *
 * The verdict is not decoration. It is the line the reporter reads off the screen and the line we
 * read out of an exported log, and its three interesting cases lead to three different next steps —
 * build the transport, work out how to be the module's only client, or work out how to make the
 * phone start Android Auto at all. A verdict that blurred two of those would send the next round in
 * the wrong direction, which is exactly what happened when round two had no way to tell them apart.
 */
class ZbtProbeTest {

    private fun verdict(
        rfcommFrames: Int = 0,
        rfcommBytes: Int = 0,
        answeredVersionRequest: Boolean = false,
        linkedType: Int? = null,
        controlFrames: Int = 0,
        phoneConnected: Boolean = false,
        phoneName: String? = null,
        askedToReconnect: Boolean = false
    ) = ZbtProbe.verdict(
        rfcommFrames, rfcommBytes, answeredVersionRequest, linkedType,
        controlFrames, phoneConnected, phoneName, askedToReconnect
    )

    // ---------------------------------------------------------------------------------------------
    // The verdict
    // ---------------------------------------------------------------------------------------------

    @Test
    fun `bytes both ways is the answer that unblocks the transport`() {
        val v = verdict(
            rfcommFrames = 4, rfcommBytes = 96, answeredVersionRequest = true,
            phoneConnected = true, phoneName = "HONOR Magic8 Lite"
        )
        assertTrue(v, v.contains("both ways"))
        assertTrue(v, v.contains("4 message(s)") && v.contains("96 bytes"))
        assertTrue(v, v.contains("HONOR Magic8 Lite"))
    }

    @Test
    fun `bytes one way is reported as such rather than rounded up to success`() {
        // Data arriving proves the daemon will feed us; our reply going unanswered is a separate
        // fact and a separate problem. Reporting the first as if it were both would have the next
        // round build a transport whose writes go nowhere.
        val v = verdict(rfcommFrames = 2, rfcommBytes = 30, answeredVersionRequest = false)
        assertTrue(v, v.contains("2 Android Auto message(s)"))
        assertTrue(v, v.contains("Nothing came back"))
        assertTrue(v, !v.contains("both ways"))
    }

    @Test
    fun `a projection link with no data is the single-owner answer`() {
        // The distinction round two could not make. A phone that started Android Auto while we
        // received nothing means the daemon hands the channel to one program, which is a completely
        // different problem from a phone that never started it.
        val v = verdict(linkedType = 2, phoneConnected = true, phoneName = "HONOR Magic8 Lite", controlFrames = 9)
        assertTrue(v, v.contains("started Android Auto"))
        assertTrue(v, v.contains("link type 2"))
        assertTrue(v, v.contains("one program at a time"))
    }

    @Test
    fun `a link with no data outranks the phone simply being connected`() {
        // Both conditions hold in this case, and the projection link is the more specific one.
        val v = verdict(linkedType = 1, phoneConnected = true)
        assertTrue(v, v.contains("one program at a time"))
    }

    @Test
    fun `a connected phone that never projected is a trigger problem, not a protocol one`() {
        val v = verdict(phoneConnected = true, phoneName = "HONOR Magic8 Lite", controlFrames = 15)
        assertTrue(v, v.contains("never started Android Auto"))
        assertTrue(v, !v.contains("one program at a time"))
    }

    @Test
    fun `being asked to reconnect and still doing nothing is worth saying out loud`() {
        // It is the difference between "we did not ask" and "we asked and it declined", and the
        // second is what would send the next round back to the vendor app as the trigger.
        val asked = verdict(phoneConnected = true, askedToReconnect = true)
        val notAsked = verdict(phoneConnected = true, askedToReconnect = false)
        assertTrue(asked, asked.contains("even after being asked"))
        assertTrue(notAsked, !notAsked.contains("even after being asked"))
    }

    @Test
    fun `no phone at all is not reported as a failure of the module`() {
        val v = verdict(controlFrames = 7)
        assertTrue(v, v.contains("No phone was connected"))
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
    fun `a busy module is reported as working, not as silence`() {
        val said = ZbtProbe.busyCarrierVerdict()
        assertTrue(said, said.contains("one program at a time"))
        assertTrue(said, said.contains("the route works"))
        assertFalse(said, said.contains("never answered"))
    }

    @Test
    fun `a test that gave the module back says so, and does not report a fault`() {
        val said = ZbtProbe.carrierTookOverVerdict()
        assertTrue(said, said.contains("one program at a time"))
        assertTrue(said, said.contains("gave the module back"))
        assertFalse(said, said.contains("never answered"))
        assertFalse(said, said.contains("Nothing is listening"))
    }

    @Test
    fun `a port that refuses is the only one reported as no daemon`() {
        val said = ZbtProbe.notConnectedVerdict(ZbtReachabilityPolicy.Verdict.NOTHING_LISTENING)
        assertTrue(said, said.contains("Nothing is listening"))
    }

    @Test
    fun `a port that will not take the connection is not reported as an absent module`() {
        // #1005's module refuses connections while a phone is linked to it, and the old message
        // told that reporter their unit had no daemon at all.
        val said = ZbtProbe.notConnectedVerdict(ZbtReachabilityPolicy.Verdict.LISTENING_SILENT)
        assertTrue(said, said.contains("Something is listening"))
        assertTrue(said, said.contains("Bluetooth off"))
        assertFalse(said, said.contains("Nothing is listening"))
    }

    @Test
    fun `a failure that named no verdict falls back to the plain answer`() {
        val said = ZbtProbe.notConnectedVerdict(null)
        assertTrue(said, said.contains("Nothing is listening"))
    }
}
