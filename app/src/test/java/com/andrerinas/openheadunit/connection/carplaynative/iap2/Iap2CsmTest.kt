package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.crypto.hex
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ported from LIVI (https://github.com/f-io/LIVI) `iap2-csm/tests/codec.rs` and `vectors.txt`,
 * and from DiPlay (https://github.com/shihabal3amri/DiPlay) `Iap2ProtocolTest` and `Iap2WireCodecTest`.
 */
class Iap2CsmTest {
    @Test fun everyReferenceVectorFramesOnceAndEncodesBackToItself() {
        for ((name, hexText) in Iap2Vectors.frames) {
            val bytes = hex(hexText)
            val frames = Iap2Csm.Framer().offer(bytes)
            assertEquals(name, 1, frames.size)
            frames[0].params()
            assertEquals(name, hexText, frames[0].encode().toHex())
        }
    }

    @Test fun aVectorSplitAtEveryByteStillFramesOnce() {
        val bytes = hex(Iap2Vectors.frame("IdentificationInformation"))
        for (cut in 1 until bytes.size) {
            val framer = Iap2Csm.Framer()
            val first = framer.offer(bytes.copyOfRange(0, cut))
            val second = framer.offer(bytes.copyOfRange(cut, bytes.size))
            assertEquals("cut $cut", 1, first.size + second.size)
        }
    }

    @Test fun emptyRequestsAreSixBytes() {
        assertEquals(Iap2Vectors.frame("RequestAuthenticationCertificate"), Iap2Csm.build(0xAA00).encode().toHex())
        assertEquals(Iap2Vectors.frame("IdentificationAccepted"), Iap2Csm.build(0x1D02).encode().toHex())
        assertEquals(Iap2Vectors.frame("StartVehicleStatusUpdates"), Iap2Csm.build(0xA100).encode().toHex())
    }

    @Test fun bytesAndFlagParametersMatchTheVectors() {
        assertEquals(
            Iap2Vectors.frame("AuthenticationCertificate"),
            Iap2Csm.build(0xAA01) { bytes(0, byteArrayOf(1, 2, 0xFF.toByte())) }.encode().toHex(),
        )
        assertEquals(
            Iap2Vectors.frame("AuthenticationResponse"),
            Iap2Csm.build(0xAA03) { bytes(0, ByteArray(0)) }.encode().toHex(),
        )
        assertEquals(
            Iap2Vectors.frame("StartPowerUpdates"),
            Iap2Csm.build(0xAE00) { void(0); void(6) }.encode().toHex(),
        )
        assertEquals(
            Iap2Vectors.frame("PowerSourceUpdate"),
            Iap2Csm.build(0xAE03) { u16(0, 2400); u8(1, 1) }.encode().toHex(),
        )
    }

    @Test fun anEmptyGroupStillWritesItsHeader() {
        assertArrayEquals(
            byteArrayOf(0x40, 0x40, 0x00, 0x0A, 0x43, 0x01, 0x00, 0x04, 0x00, 0x00),
            Iap2Csm.build(0x4301) { group(0) {} }.encode(),
        )
    }

    @Test fun aWiredStartSessionMatchesTheVectorWithItsAddressesInOneParameter() {
        val addresses = "10.0.0.1\u0000169.254.5.5\u0000".toByteArray()
        val frame = Iap2Csm.build(0x4301) { group(0) { bytes(0, addresses) }; u32(2, 5000) }
        assertEquals(Iap2Vectors.frame("CarPlayStartSession#wired"), frame.encode().toHex())
    }

    @Test fun theAvailabilityVectorReadsBack() {
        val frame = Iap2Csm.Framer().offer(hex(Iap2Vectors.frame("CarPlayAvailability"))).single()
        val wired = Iap2Csm.parseParams(frame.first(0)!!)
        assertEquals(1, Iap2Csm.readU8(wired.first { it.id == 0 }.payload))
        assertEquals("usbid", Iap2Csm.readString(wired.first { it.id == 1 }.payload))
        assertEquals(0x4300, frame.messageId)
    }

    @Test fun unknownParametersAreKeptAndTheFirstDuplicateWins() {
        val frame = Iap2Csm.build(0xA101) {
            u16(0x63, 0xEE)
            u16(3, 1)
            u16(3, 2)
        }
        assertEquals(listOf(0x63, 3, 3), frame.params().map { it.id })
        assertArrayEquals(byteArrayOf(0, 1), frame.first(3))
        assertEquals(null, frame.first(9))
    }

    @Test fun repeatedParametersKeepEveryValue() {
        val decoded = Iap2Csm.parseParams(Iap2Body().bytes(10, byteArrayOf(0xFF.toByte(), 0xA6.toByte())).bytes(10, byteArrayOf(0, 0x2D)).build())
        assertEquals(2, decoded.count { it.id == 10 })
        assertArrayEquals(byteArrayOf(0, 0x2D), decoded[1].payload)
    }

    @Test fun nestedGroupsReadBack() {
        val frame = Iap2Csm.build(0x0D01) { u16(0, 1); group(4) { u32(0, 7); u8(1, 2) } }
        val inner = Iap2Csm.parseParams(frame.first(4)!!)
        assertArrayEquals(byteArrayOf(0, 0, 0, 7), inner[0].payload)
        assertEquals(2, Iap2Csm.readU8(inner[1].payload))
    }

    @Test fun aTruncatedOrShortParameterIsRefused() {
        for (bad in listOf(byteArrayOf(0, 5, 0, 1), byteArrayOf(0, 3, 0, 1, 0), byteArrayOf(0, 4, 0))) {
            try {
                Iap2Csm.parseParams(bad)
                fail("accepted ${bad.toHex()}")
            } catch (_: Iap2FormatException) {
            }
        }
    }

    @Test fun scalarReadersRefuseTheWrongSize() {
        try {
            Iap2Csm.readU8(ByteArray(0))
            fail("an empty scalar is refused")
        } catch (_: Iap2FormatException) {
        }
    }

    @Test fun framerAcceptsFragmentedAndConcatenatedFrames() {
        val first = Iap2Frame(0x4300, byteArrayOf(0, 4, 0, 0))
        val second = Iap2Frame(0x4301, byteArrayOf(0, 5, 0, 0, 1))
        val bytes = first.encode() + second.encode()
        val framer = Iap2Csm.Framer()
        assertTrue(framer.offer(bytes.copyOfRange(0, 3)).isEmpty())
        val frames = framer.offer(bytes.copyOfRange(3, bytes.size))
        assertEquals(listOf(0x4300, 0x4301), frames.map { it.messageId })
        assertArrayEquals(byteArrayOf(0, 5, 0, 0, 1), frames[1].body)
    }

    @Test fun framerSkipsGarbageAndAFalseLengthBelowTheHeader() {
        val good = Iap2Csm.build(0x1D00).encode()
        val noisy = byteArrayOf(1, 2, 3, 0x40, 0x41, 0x40, 0x40, 0x00, 0x02, 0x7F) + good
        val frames = Iap2Csm.Framer().offer(noisy)
        assertEquals(listOf(0x1D00), frames.map { it.messageId })
    }

    @Test fun aWrongStartMarkerIsNeverAFrame() {
        assertTrue(Iap2Csm.Framer().offer(byteArrayOf(0x40, 0x41, 0x00, 0x06, 0xA1.toByte(), 0x00)).isEmpty())
    }

    @Test fun aNulTerminatedStringRoundTrips() {
        val encoded = Iap2Body().string(0, "hello").build()
        assertEquals("hello", Iap2Csm.readString(Iap2Csm.parseParams(encoded)[0].payload))
    }

    @Test fun stringsWithAnEmbeddedNulOrNoTerminatorAreRefused() {
        for (bad in listOf(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte(), 0), "abc".toByteArray())) {
            try {
                Iap2Csm.readString(bad)
                fail("accepted ${bad.toHex()}")
            } catch (_: Iap2FormatException) {
            }
        }
        try {
            Iap2Body().string(0, "a\u0000b")
            fail("the encoder accepts a NUL")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun idsAndSizesOutsideTheirFieldsAreRefused() {
        try {
            Iap2Body().void(0x10000)
            fail("id over u16")
        } catch (_: IllegalArgumentException) {
        }
        try {
            Iap2Csm.frame(0x10000, ByteArray(0))
            fail("message id over u16")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun u16ListsRoundTrip() {
        val params = Iap2Csm.parseParams(Iap2Body().u16List(6, listOf(0x5000, 0xAE03, 0x4301)).build())
        assertEquals(listOf(0x5000, 0xAE03, 0x4301), Iap2Csm.readU16List(params[0].payload))
    }
}
