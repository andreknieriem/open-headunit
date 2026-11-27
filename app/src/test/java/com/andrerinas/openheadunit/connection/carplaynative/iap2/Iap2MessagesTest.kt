package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Frame sizes are those of the recorded iOS 26 session in xcertplay (https://github.com/shilapi/xcertplay)
 * `common/src/test/resources/handshake-wireless.txt`. Byte vectors are from LIVI (https://github.com/f-io/LIVI) `vectors.txt`.
 */
class Iap2MessagesTest {
    private fun frameOf(name: String) = Iap2Csm.Framer().offer(hex(Iap2Vectors.frame(name))).single()

    @Test fun theSubscriptionsHaveTheRecordedFrameSizes() {
        // 0x5000 54 B, 0x5200 6 B, 0xae00 18 B, 0x4157 18 B, 0x4154 30 B.
        assertEquals(
            listOf(0x5000 to 54, 0x5200 to 6, 0xae00 to 18, 0x4157 to 18, 0x4154 to 30),
            Iap2Messages.subscriptions().map { it.messageId to it.encode().size },
        )
    }

    @Test fun theMediaRemoteStartIs59BytesAndStopNamesTheComponent() {
        val start = Iap2Messages.startMediaRemote()
        assertEquals(Iap2Messages.START_HID, start.messageId)
        assertEquals(59, start.encode().size)
        assertEquals(31, start.first(4)!!.size)
        assertEquals(listOf(0, 1, 2, 4), start.params().map { it.id })
        assertEquals("6803" + "0006" + "0000" + "0000", Iap2Messages.stopMediaRemote().encode().toHex().substring(8))
    }

    @Test fun theCertificateAndResponseFramesAddTenBytes() {
        // Recorded: 0xaa01 617 B for a 607 byte certificate, 0xaa03 74 B for a 64 byte signature.
        assertEquals(617, Iap2Messages.certificate(ByteArray(607)).encode().size)
        assertEquals(74, Iap2Messages.response(ByteArray(64)).encode().size)
    }

    @Test fun thePowerSourceUpdateIsTheReferenceVector() {
        assertEquals(Iap2Vectors.frame("PowerSourceUpdate"), Iap2Messages.powerSourceUpdate(2400).encode().toHex())
    }

    @Test fun theStartSessionCarriesAddressPortDeviceKeyAndVersion() {
        val frame = Iap2Messages.StartSession("fe80::2", 7000, "02:00:00:00:00:02", "ab".repeat(32), "950.7.1").frame()
        assertEquals(0x4301, frame.messageId)
        assertEquals(listOf(0, 2, 3, 4, 5), frame.params().map { it.id })
        val wired = Iap2Csm.parseParams(frame.first(0)!!)
        assertEquals(1, wired.size)
        assertEquals("fe80::2", Iap2Csm.readString(wired[0].payload))
        assertArrayEquals(byteArrayOf(0, 0, 0x1B, 0x58), frame.first(2))
        assertEquals("02:00:00:00:00:02", Iap2Csm.readString(frame.first(3)!!))
        assertEquals("ab".repeat(32), Iap2Csm.readString(frame.first(4)!!))
        assertEquals("950.7.1", Iap2Csm.readString(frame.first(5)!!))
        assertNull(frame.first(8))
    }

    @Test fun aStartSessionWithABadFieldIsRefused() {
        val bad = listOf<() -> Any>(
            { Iap2Messages.StartSession("192.168.1.2", 7000, "d", "k", "v") },
            { Iap2Messages.StartSession("fe80::2%usb0", 7000, "d", "k", "v") },
            { Iap2Messages.StartSession("fe80::2", 0, "d", "k", "v") },
            { Iap2Messages.StartSession("fe80::2", 7000, "", "k", "v") },
            { Iap2Messages.StartSession("fe80::2", 7000, "d", "k\u0000", "v") },
        )
        for (make in bad) {
            try {
                make()
                throw AssertionError("accepted a bad field")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun theAvailabilityVectorSaysWiredIsAvailable() {
        assertEquals(true, Iap2Messages.wiredAvailable(frameOf("CarPlayAvailability")))
    }

    @Test fun anAvailabilityWithoutAWiredGroupStatesNothing() {
        assertNull(Iap2Messages.wiredAvailable(Iap2Csm.build(0x4300) { group(1) { u8(0, 1) } }))
        assertNull(Iap2Messages.wiredAvailable(Iap2Csm.build(0x4300)))
    }

    @Test fun aWiredGroupWithAFalseFlagIsNotAvailable() {
        assertEquals(false, Iap2Messages.wiredAvailable(Iap2Csm.build(0x4300) { group(0) { u8(0, 0) } }))
    }

    @Test fun theRejectionVectorFlagsTwoParameters() {
        val r = Iap2Messages.rejection(frameOf("IdentificationRejected"))
        assertTrue(r.messageIds.isEmpty())
        assertEquals(setOf(0, 3), r.otherParams)
    }

    @Test fun aRejectionListsBigEndianMessageIds() {
        // carplayd's fixture, as DiPlay tests it: empty 30, BE16 0x415a in 6, empty 20.
        val body = byteArrayOf(0, 4, 0, 30, 0, 6, 0, 6, 0x41, 0x5A, 0, 4, 0, 20)
        val r = Iap2Messages.rejection(Iap2Frame(0x1D03, body))
        assertEquals(setOf(0x415A), r.messageIds)
        assertEquals(setOf(30, 20), r.otherParams)
        assertFalse(r.messageIds.isEmpty())
    }

    @Test fun anOddLengthIdListIsMalformed() {
        val body = Iap2Body().bytes(7, byteArrayOf(0x41, 0x5A, 0x50)).build()
        try {
            Iap2Messages.rejection(Iap2Frame(0x1D03, body))
            throw AssertionError("accepted an odd list")
        } catch (_: Iap2FormatException) {
        }
    }

    @Test fun hexIdPadsToFourDigits() {
        assertEquals("0x00ab", Iap2Messages.hexId(0xAB))
        assertEquals("0x4301", Iap2Messages.hexId(0x4301))
    }

    private fun Iap2Frame.params() = Iap2Csm.parseParams(body)
}
