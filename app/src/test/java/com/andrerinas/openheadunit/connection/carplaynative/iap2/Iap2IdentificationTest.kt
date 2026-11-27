package com.andrerinas.openheadunit.connection.carplaynative.iap2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The message lists and parameter ids are those of xcertplay's `Iap2WirelessSessionReplayTest` (https://github.com/shilapi/xcertplay),
 * which replays a recorded iOS 26 session, with the wired transport component in place of Bluetooth.
 */
class Iap2IdentificationTest {
    private val config = Iap2Identification.Config("Open Headunit", "OHU-1", "Open Headunit", "0000001", "1.0.0", "1.0", 3)

    private val recordedSent = listOf(
        0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4159, 0x4154, 0x4156, 0x4301, 0x6800, 0x6802, 0x6803, 0x5703,
    )
    private val recordedReceived = listOf(
        0xea00, 0xea01, 0x5001, 0x5201, 0x5202, 0xae01, 0x4158, 0x4155, 0x4300, 0x6801, 0x4e0d, 0x4e0e, 0x5702,
    )

    private fun u16s(frame: Iap2Frame, id: Int) = Iap2Csm.readU16List(frame.first(id)!!)

    @Test fun theParameterIdsAreTheRecordedOnesWithTheUsbHostComponent() {
        val ids = Iap2Identification.build(config).params().map { it.id }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 16, 18), ids)
        // The recorded wireless list: 0..10, 12, 13, 17 (Bluetooth), 18 and 24 (wireless CarPlay).
        val wireless = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 13, 17, 18, 24)
        assertEquals(wireless - listOf(17, 24), ids - 16)
    }

    @Test fun theMessageListsAreTheRecordedOnesWithoutTheWirelessIdsAndWithPowerSource() {
        val frame = Iap2Identification.build(config)
        val sent = u16s(frame, 6)
        val received = u16s(frame, 7)
        assertEquals((recordedSent - 0x5703).toSet() + 0xae03, sent.toSet())
        assertEquals(recordedSent.size, sent.size)
        assertEquals(recordedReceived - listOf(0x4e0d, 0x4e0e, 0x5702), received)
        assertEquals(listOf(0x5000, 0x5002, 0x5200, 0x5203, 0xae00, 0xae02, 0x4157, 0x4159, 0x4154, 0x4156, 0xae03, 0x4301, 0x6800, 0x6802, 0x6803), sent)
    }

    @Test fun everyIdTheStartSendsIsInTheSentList() {
        val sentByUs = Iap2Messages.subscriptions().map { it.messageId } +
            listOf(Iap2Messages.POWER_SOURCE_UPDATE, Iap2Messages.START_HID, Iap2Messages.STOP_HID, Iap2Messages.CARPLAY_START_SESSION)
        assertTrue(Iap2Identification.SENT.containsAll(sentByUs))
        assertTrue(Iap2Identification.RECEIVED.containsAll(listOf(Iap2Messages.CARPLAY_AVAILABILITY, Iap2Messages.POWER_UPDATE)))
    }

    @Test fun theUsbHostComponentNamesTheInterfaceAndTheRemoteIsAHidComponent() {
        val frame = Iap2Identification.build(config)
        val host = Iap2Csm.parseParams(frame.first(16)!!)
        assertEquals("USBHostTransport", Iap2Csm.readString(host.first { it.id == 1 }.payload))
        assertEquals(3, Iap2Csm.readU8(host.first { it.id == 3 }.payload))
        val hid = Iap2Csm.parseParams(frame.first(18)!!)
        assertEquals("Media Playback Remote", Iap2Csm.readString(hid.first { it.id == 1 }.payload))
        assertEquals(2, Iap2Csm.readU8(frame.first(8)!!))
    }

    @Test fun anotherInterfaceNumberReachesTheFrame() {
        val frame = Iap2Identification.build(Iap2Identification.Config("n", "m", "v", "s", "f", "h", 7))
        assertEquals(7, Iap2Csm.readU8(Iap2Csm.parseParams(frame.first(16)!!).first { it.id == 3 }.payload))
    }

    @Test fun omittedIdsLeaveBothLists() {
        val frame = Iap2Identification.build(config, setOf(0x4300, 0x4301))
        assertFalse(0x4301 in u16s(frame, 6))
        assertFalse(0x4300 in u16s(frame, 7))
        assertTrue(0x5000 in u16s(frame, 6))
        assertTrue(0xae01 in u16s(frame, 7))
    }

    @Test fun aTextWithANulOrAnEmptyTextIsRefused() {
        for (bad in listOf("", "a\u0000b")) {
            try {
                Iap2Identification.Config(bad, "m", "v", "s", "f", "h", 3)
                throw AssertionError("accepted '$bad'")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    private fun Iap2Frame.params() = Iap2Csm.parseParams(body)
}
