package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from DiPlay (https://github.com/shihabal3amri/DiPlay) `UsbMuxFrameBufferTest`. */
class UsbmuxFrameBufferTest {
    @Test fun versionPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(version, zeroPadding, tcp(data = data))
    }

    @Test fun synAckPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(synAck, capturedPadding, tcp(data = data))
    }

    @Test fun unpaddedFramesAndUnknownNormalProtocolsArePreserved() {
        val unknown = mux(73, byteArrayOf(1, 2, 3), magic = 9)
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(version + synAck + unknown + tcp(data = data))
        assertEquals(0, buffer.takeFrame()!!.protocol)
        assertEquals(6, buffer.takeFrame()!!.protocol)
        val parsedUnknown = buffer.takeFrame()!!
        assertEquals(73, parsedUnknown.protocol)
        assertArrayEquals(byteArrayOf(1, 2, 3), parsedUnknown.payload)
        assertArrayEquals(tcp(data = data).copyOfRange(16, 16 + 20 + data.size), buffer.takeFrame()!!.payload)
        assertNull(buffer.takeFrame())
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun coalescedPaddedAndUnpaddedRepliesKeepAllFollowingPayloads() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(version + zeroPadding + synAck + capturedPadding + tcp(data = data) + tcp(data = data))
        assertEquals(20, buffer.takeFrame()!!.length)
        assertEquals(36, buffer.takeFrame()!!.length)
        repeat(2) { assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size)) }
        assertNull(buffer.takeFrame())
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun incompleteNextMuxAndTcpHeadersRemainBuffered() {
        val next = tcp(data = data)
        val buffer = after(synAck)
        buffer.append(capturedPadding.copyOfRange(0, 2))
        assertNull(buffer.takeFrame())
        buffer.append(capturedPadding.copyOfRange(2, 4) + next.copyOfRange(0, 12))
        assertNull(buffer.takeFrame())
        assertEquals(16, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(12, 16))
        assertNull(buffer.takeFrame())
        assertEquals(20, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(16, 25))
        assertNull(buffer.takeFrame())
        assertEquals(29, buffer.bufferedBytes)
        buffer.append(next.copyOfRange(25, next.size))
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun paddingWithoutFurtherDataDoesNotDisappear() {
        val buffer = after(version)
        buffer.append(zeroPadding)
        repeat(10) { assertNull(buffer.takeFrame()) }
        assertEquals(4, buffer.bufferedBytes)
    }

    @Test fun tcpOptionsInTheFollowingFrameArePreserved() {
        val next = tcp(data = data, headerBytes = 24)
        val buffer = after(synAck)
        buffer.append(capturedPadding + next)
        assertArrayEquals(next.copyOfRange(16, next.size), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun validPayloadContainingHeaderLikeBytesIsNeverResynchronized() {
        val payload = data + capturedPadding + synAck + data
        val buffer = after(version)
        buffer.append(tcp(data = payload))
        assertArrayEquals(payload, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + payload.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun incorrectMagicIsNotAcceptedAsAPaddingRecoveryTarget() {
        rejectAfter(version, capturedPadding + tcp(data = data, magic = 0xfeedface.toInt()))
    }

    @Test fun unknownProtocolIsNotAcceptedAsAPaddingRecoveryTarget() {
        rejectAfter(version, capturedPadding + mux(7, ByteArray(20)))
    }

    @Test fun invalidTcpPortsDoNotQualifyAsARecoveryTarget() {
        val invalid = tcp(data = data).also { it[18] = 0; it[19] = 0 }
        rejectAfter(version, capturedPadding + invalid)
    }

    @Test fun unrecognizedSynAckMagicDoesNotEnableRecovery() {
        rejectAfter(tcp(flags = 0x12, magic = 7), capturedPadding + tcp(data = data))
    }

    @Test fun tooShortTcpHeaderIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = tcp(data = data).also { it[28] = 0x30 }
        rejectAfter(synAck, capturedPadding + invalid)
    }

    @Test fun tcpHeaderBeyondDeclaredLengthIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = synAck.copyOf().also { it[28] = 0xf0.toByte() }
        rejectAfter(synAck, capturedPadding + invalid)
    }

    @Test fun overlargeNextFrameIsNotAcceptedAsAPaddingRecoveryTarget() {
        val invalid = synAck.copyOf().also { putU32(it, 4, 65_537) }
        rejectAfter(version, capturedPadding + invalid)
    }

    @Test fun invalidNormalTcpLengthStillFailsImmediately() {
        val invalid = synAck.copyOf(16).also { putU32(it, 4, 6) }
        rejectAfter(version, invalid)
    }

    @Test fun arbitraryLeadingJunkDoesNotTriggerResynchronization() {
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(capturedPadding + tcp(data = data))
        assertProtocolFailure { buffer.takeFrame() }
    }

    @Test fun eightPaddingBytesAreNotScannedForANextFrame() {
        rejectAfter(synAck, zeroPadding + capturedPadding + tcp(data = data))
    }

    @Test fun payloadReplyPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(tcp(data = data), reportedPadding, tcp(data = data))
    }

    @Test fun payloadFreeAckPaddingWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(tcp(flags = 0x10), reportedPadding, tcp(data = data))
    }

    @Test fun synAckWithDataAllowsBoundedPaddingRecovery() {
        assertEverySplitPreservesFrames(tcp(flags = 0x12, data = data), reportedPadding, tcp(data = data))
    }

    @Test fun payloadReplyWithTcpOptionsAllowsBoundedPaddingRecovery() {
        assertEverySplitPreservesFrames(tcp(data = data, headerBytes = 24), reportedPadding,
            tcp(data = data, headerBytes = 24))
    }

    @Test fun reportedLockdownReplyLengthsKeepAllPayloadsAcrossEveryTransferSplit() {
        // An iPhone sends replies of these declared lengths, each split at every boundary a USB read can cut.
        // Give each payload distinctive bytes, including embedded header-like data.
        val lastPayload = ByteArray(918) { (it % 239).toByte() }.also {
            (capturedPadding + synAck).copyInto(it, 100)
        }
        val replies = listOf(version, synAck, tcp(data = ByteArray(4) { it.toByte() }),
            tcp(data = ByteArray(360) { (it % 251).toByte() }), tcp(data = lastPayload))
        val wire = replies.reduce { all, next -> all + reportedPadding + next }
        for (split in 0..wire.size) {
            val buffer = UsbmuxCodec.FrameBuffer()
            val parsed = mutableListOf<UsbmuxCodec.Frame>()
            fun drain() { while (true) parsed.add(buffer.takeFrame() ?: break) }
            buffer.append(wire.copyOfRange(0, split)); drain()
            buffer.append(wire.copyOfRange(split, wire.size)); drain()
            assertEquals("transfer split at $split", replies.size, parsed.size)
            replies.indices.forEach { index ->
                assertArrayEquals("reply $index at split $split",
                    replies[index].copyOfRange(16, replies[index].size), parsed[index].payload)
            }
            assertEquals(0, buffer.bufferedBytes)
        }
    }

    @Test fun dataReplyTrailerAndIncompleteNextFrameRemainBuffered() {
        val buffer = after(tcp(data = data))
        buffer.append(reportedPadding)
        repeat(10) { assertNull(buffer.takeFrame()) }
        assertEquals(4, buffer.bufferedBytes)
        val next = tcp(data = data)
        next.forEach { byte ->
            buffer.append(byteArrayOf(byte))
            if (buffer.bufferedBytes < next.size) assertNull(buffer.takeFrame())
        }
        assertArrayEquals(next.copyOfRange(16, next.size), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun unknownPreviousProtocolDoesNotEnablePaddingRecovery() {
        rejectAfter(mux(73, data), reportedPadding + tcp(data = data))
    }

    @Test fun truncatedPreviousTcpHeaderDoesNotEnablePaddingRecovery() {
        rejectAfter(mux(6, ByteArray(19)), reportedPadding + tcp(data = data))
    }

    @Test fun invalidPreviousTcpDataOffsetDoesNotEnablePaddingRecovery() {
        listOf(0x30, 0xf0).forEach { offset ->
            val invalid = tcp(data = data).also { it[28] = offset.toByte() }
            rejectAfter(invalid, reportedPadding + tcp(data = data))
        }
    }

    @Test fun zeroPreviousTcpPortsDoNotEnablePaddingRecovery() {
        listOf(16, 18).forEach { port ->
            val invalid = tcp(data = data).also { it[port] = 0; it[port + 1] = 0 }
            rejectAfter(invalid, reportedPadding + tcp(data = data))
        }
    }

    @Test fun malformedFollowingDataReplyIsNotRecoveredAfterAPayloadReply() {
        val previous = tcp(data = data)
        rejectAfter(previous, reportedPadding + tcp(data = data, magic = 0xfeedface.toInt()))
        rejectAfter(previous, reportedPadding + tcp(data = data).also { it[28] = 0x30 })
        rejectAfter(previous, reportedPadding + tcp(data = data).also { it[16] = 0; it[17] = 0 })
        rejectAfter(previous, zeroPadding + capturedPadding + tcp(data = data))
    }

    @Test fun plausibleOffsetZeroLengthIsNeverScannedForPadding() {
        val buffer = after(tcp(data = data))
        val suffix = zeroPadding + reportedPadding + tcp(data = data)
        buffer.append(suffix)
        assertNull(buffer.takeFrame())
        assertEquals(suffix.size, buffer.bufferedBytes)
    }

    @Test fun nonVersionTwoReplyDoesNotEnablePaddingRecovery() {
        val invalidVersion = version.copyOf().also { putU32(it, 8, 1) }
        rejectAfter(invalidVersion, capturedPadding + tcp(data = data))
    }

    @Test fun invalidLengthWithoutAnEligibleReplyStillFails() {
        val invalid = ByteArray(16)
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(invalid)
        assertProtocolFailure { buffer.takeFrame() }
    }

    @Test fun aMaximumSizeDataFrameRemainsIntact() {
        val payload = ByteArray(65_536 - 36) { (it % 251).toByte() }
        val next = tcp(data = payload)
        val buffer = after(version)
        buffer.append(zeroPadding + next.copyOfRange(0, 25))
        assertNull(buffer.takeFrame())
        buffer.append(next.copyOfRange(25, next.size))
        val frame = buffer.takeFrame()!!
        assertEquals(65_536, frame.length)
        assertArrayEquals(payload, frame.payload.copyOfRange(20, frame.payload.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun paddingDiagnosticsAreCappedAndContainOnlyFramingMetadata() {
        val reports = mutableListOf<String>()
        val buffer = UsbmuxCodec.FrameBuffer(reports::add)
        repeat(10) {
            buffer.append(synAck + capturedPadding + tcp(data = data))
            buffer.takeFrame(); buffer.takeFrame()
        }
        assertEquals(4, reports.size)
        assertTrue(reports.all { it.startsWith("USBMUX optional reply padding skipped bytes=4 ") })
        assertTrue(reports.none { it.contains("mCon") || it.contains("private-sample") || it.contains("62078") })
    }

    @Test fun aDiagnosticCallbackFailureDoesNotBreakFraming() {
        val buffer = UsbmuxCodec.FrameBuffer { throw IllegalStateException("logger unavailable") }
        buffer.append(version + zeroPadding + tcp(data = data))
        buffer.takeFrame()
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
    }

    @Test fun capturedProtocolOneBoundaryWorksAcrossEveryTransferSplit() {
        // A recorded 187-byte TCP transfer and a 73-byte diagnostic transfer, each with four bytes past its frame.
        // The declared frames are 183 and 69 bytes, each followed by four extra bytes.
        assertEquals(187, capturedTcpTransfer.size)
        assertEquals(73, capturedDiagnosticTransfer.size)
        assertEverySplitPreservesFrames(capturedTcpTransfer.copyOfRange(0, 183),
            capturedTcpTransfer.copyOfRange(183, 187), capturedDiagnostic)

        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(capturedTcpTransfer + capturedDiagnosticTransfer)
        assertEquals(6, buffer.takeFrame()!!.protocol)
        val diagnostic = buffer.takeFrame()!!
        assertEquals(1, diagnostic.protocol)
        assertEquals(69, diagnostic.length)
        assertEquals(0x0185, diagnostic.sequence)
        assertArrayEquals(capturedDiagnostic.copyOfRange(16, 69), diagnostic.payload)
        assertNull(buffer.takeFrame())
        assertEquals(4, buffer.bufferedBytes)
    }

    @Test fun diagnosticTrailersKeepRepeatedDiagnosticsAndFollowingTcpAcrossEverySplit() {
        val following = tcp(data = data + capturedDiagnosticTransfer + data)
        val frames = listOf(capturedTcpTransfer.copyOfRange(0, 183), capturedDiagnostic,
            secondCapturedDiagnostic, following)
        val wire = capturedTcpTransfer + capturedDiagnosticTransfer + secondCapturedDiagnosticTransfer + following
        for (split in 0..wire.size) {
            val buffer = UsbmuxCodec.FrameBuffer()
            val parsed = mutableListOf<UsbmuxCodec.Frame>()
            fun drain() { while (true) parsed.add(buffer.takeFrame() ?: break) }
            buffer.append(wire.copyOfRange(0, split)); drain()
            buffer.append(wire.copyOfRange(split, wire.size)); drain()
            assertEquals("transfer split at $split", frames.size, parsed.size)
            frames.indices.forEach { index ->
                assertArrayEquals("reply $index at split $split",
                    frames[index].copyOfRange(16, frames[index].size), parsed[index].payload)
            }
            assertEquals(0, buffer.bufferedBytes)
        }
    }

    @Test fun incompleteDiagnosticDoesNotConsumeTrailerOrAnyPayloadBytes() {
        val reports = mutableListOf<String>()
        val buffer = UsbmuxCodec.FrameBuffer(reports::add)
        buffer.append(capturedTcpTransfer)
        buffer.takeFrame()
        capturedDiagnostic.forEachIndexed { index, byte ->
            buffer.append(byteArrayOf(byte))
            if (index < capturedDiagnostic.lastIndex) {
                repeat(3) { assertNull(buffer.takeFrame()) }
                assertEquals(index + 1 + 4, buffer.bufferedBytes)
                assertTrue(reports.isEmpty())
            }
        }
        assertArrayEquals(capturedDiagnostic.copyOfRange(16, 69), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
        assertEquals(1, reports.size)
    }

    @Test fun malformedDiagnosticsDoNotQualifyAsRecoveryTargets() {
        val wrongMagic = capturedDiagnostic.copyOf().also { putU32(it, 8, 0xfeedface.toInt()) }
        val wrongSubtype = capturedDiagnostic.copyOf().also { it[16] = 3 }
        val controlByte = capturedDiagnostic.copyOf().also { it[25] = 0 }
        val nonAscii = capturedDiagnostic.copyOf().also { it[25] = 0x80.toByte() }
        val overlarge = capturedDiagnostic.copyOf().also { putU32(it, 4, 16 + 1_025) }
        listOf(wrongMagic, wrongSubtype, controlByte, nonAscii, overlarge,
            mux(1, ByteArray(0)), mux(1, byteArrayOf(4))).forEach { invalid ->
            rejectAfter(tcp(data = data), reportedPadding + invalid)
        }
    }

    @Test fun malformedPreviousDiagnosticsDoNotEnableTrailerRecovery() {
        listOf(
            capturedDiagnostic.copyOf().also { putU32(it, 8, 7) },
            capturedDiagnostic.copyOf().also { it[16] = 3 },
            capturedDiagnostic.copyOf().also { it[25] = 0 },
            mux(1, byteArrayOf(4)),
            mux(1, byteArrayOf(4) + ByteArray(1_024) { 0x41 }),
        ).forEach { invalid -> rejectAfter(invalid, reportedPadding + tcp(data = data)) }
    }

    @Test fun diagnosticRecoveryStillNeedsAnEligiblePreviousReplyAndExactlyFourBytes() {
        rejectAfter(mux(73, data), reportedPadding + capturedDiagnostic)
        rejectAfter(tcp(data = data), zeroPadding + capturedPadding + capturedDiagnostic)
        rejectAfter(capturedDiagnostic, reportedPadding + mux(7, ByteArray(20)))
        val buffer = UsbmuxCodec.FrameBuffer()
        buffer.append(reportedPadding + capturedDiagnostic)
        assertProtocolFailure { buffer.takeFrame() }
    }

    @Test fun normalProtocolOnePayloadIsPreservedWithoutRecoveryValidation() {
        val payload = byteArrayOf(0, 0x80.toByte()) + capturedDiagnosticTransfer + synAck
        val normal = mux(1, payload, magic = 9)
        val buffer = after(tcp(data = data))
        buffer.append(normal + tcp(data = data))
        assertArrayEquals(payload, buffer.takeFrame()!!.payload)
        assertArrayEquals(tcp(data = data).copyOfRange(16, 16 + 20 + data.size), buffer.takeFrame()!!.payload)
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun maximumBoundedDiagnosticPreservesAllTextAndFollowingPayload() {
        val text = byteArrayOf(4) + ByteArray(1_023) { 0x41 }
        val maximum = mux(1, text)
        val buffer = after(tcp(data = data))
        buffer.append(reportedPadding + maximum + reportedPadding + tcp(data = data))
        assertArrayEquals(text, buffer.takeFrame()!!.payload)
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun diagnosticPaddingReportsOnlyFramingMetadata() {
        val reports = mutableListOf<String>()
        val buffer = UsbmuxCodec.FrameBuffer(reports::add)
        buffer.append(capturedTcpTransfer + capturedDiagnosticTransfer + tcp(data = data))
        repeat(3) { buffer.takeFrame() }
        assertEquals(2, reports.size)
        assertTrue(reports.first().contains("nextProtocol=1 nextLength=69"))
        assertTrue(reports.none { it.contains("detected") || it.contains("Expected") || it.contains("received") })
    }

    @Test fun aVersionReplyAfterATrailerWorksAcrossEveryTransferSplit() {
        assertEverySplitPreservesFrames(version, zeroPadding, version)
        assertEverySplitPreservesFrames(synAck, capturedPadding, version)
    }

    @Test fun theReportedVersionTransferAfterALeftoverTrailerIsRead() {
        // A 4-byte trailer is left over when a 24-byte read brings the next VERSION reply, so 28 bytes wait.
        val buffer = after(version)
        buffer.append(zeroPadding)
        assertNull(buffer.takeFrame())
        buffer.append(version + zeroPadding)
        assertEquals(28, buffer.bufferedBytes)
        val second = buffer.takeFrame()!!
        assertEquals(0, second.protocol)
        assertEquals(20, second.length)
        assertNull(buffer.takeFrame())
        assertEquals(4, buffer.bufferedBytes)
        buffer.append(tcp(data = data))
        assertArrayEquals(data, buffer.takeFrame()!!.payload.copyOfRange(20, 20 + data.size))
        assertEquals(0, buffer.bufferedBytes)
    }

    @Test fun onlyAnExactVersionHeaderQualifiesAfterATrailer() {
        rejectAfter(version, zeroPadding + version.copyOf().also { putU32(it, 8, 1) })
        rejectAfter(version, zeroPadding + mux(0, ByteArray(8), magic = 2))
        rejectAfter(synAck, capturedPadding + mux(0, byteArrayOf(0x49, 0x28, 0x73, 0), magic = 0xfaceface.toInt()))
    }

    private fun assertEverySplitPreservesFrames(first: ByteArray, padding: ByteArray, next: ByteArray) {
        val wire = first + padding + next
        for (split in 0..wire.size) {
            val buffer = UsbmuxCodec.FrameBuffer()
            val frames = mutableListOf<UsbmuxCodec.Frame>()
            fun drain() { while (true) frames.add(buffer.takeFrame() ?: break) }
            buffer.append(wire.copyOfRange(0, split)); drain()
            buffer.append(wire.copyOfRange(split, wire.size)); drain()
            assertEquals("transfer split at $split", 2, frames.size)
            assertArrayEquals(first.copyOfRange(16, first.size), frames[0].payload)
            assertArrayEquals(next.copyOfRange(16, next.size), frames[1].payload)
            assertEquals(0, buffer.bufferedBytes)
        }
    }

    private fun after(frame: ByteArray): UsbmuxCodec.FrameBuffer = UsbmuxCodec.FrameBuffer().also {
        it.append(frame); assertEquals(frame.size, it.takeFrame()!!.length)
    }

    private fun rejectAfter(previous: ByteArray, suffix: ByteArray) {
        val buffer = after(previous)
        buffer.append(suffix)
        assertProtocolFailure { buffer.takeFrame() }
        assertEquals(suffix.size, buffer.bufferedBytes)
    }

    private fun assertProtocolFailure(block: () -> Unit) {
        try { block(); throw AssertionError("Expected malformed framing to remain a protocol error") }
        catch (_: UsbmuxFramingException) { }
    }

    private companion object {
        val zeroPadding = ByteArray(4)
        val capturedPadding = byteArrayOf(0x6d, 0x43, 0x6f, 0x6e)
        val reportedPadding = byteArrayOf(0, 0, 1, 0x68)
        val data = "private-sample payload mCon".toByteArray()
        val version = mux(0, byteArrayOf(0x49, 0x28, 0x73, 0), magic = 2)
        val synAck = tcp(flags = 0x12)
        // A first TCP completion byte for byte, as DiPlay's usbmux regression test records it.
        // TLS bytes are opaque to framing; the last four bytes (38343134) are outside length 183.
        val capturedTcpTransfer = hex("""
            00000006000000b7faceface01840197e05600020005015d0000051650100200
            00000000170303008ee0f6cf38b2e09782cb37c255c64a1cc0941687c920f807
            acfbe4741aa5a10776fe70c26d5059c78ba2d486c7b3145a1bd4c258f7096a00
            2e00dc19aafc87cbe8b72ff25d962b129b462704d35fcc29a703f6004983fc07
            d315c4c7baec956b97b4dabfbdfee1da166e19cc07c922617e8a67bd70917b51
            9fa0cf713b0d9ee97036efade9e4b83e40a2904a9f1ba438343134
        """)
        val capturedDiagnostic = hex("0000000100000045faceface01850197") + byteArrayOf(4) +
            "detected duplicate packet. Expected 408 received 410".toByteArray(Charsets.US_ASCII)
        val capturedDiagnosticTransfer = capturedDiagnostic + hex("a5a10776")
        val secondCapturedDiagnostic = hex("0000000100000045faceface01870197") + byteArrayOf(4) +
            "detected duplicate packet. Expected 408 received 411".toByteArray(Charsets.US_ASCII)
        val secondCapturedDiagnosticTransfer = secondCapturedDiagnostic + hex("e724c574")

        fun hex(value: String): ByteArray = value.filterNot(Char::isWhitespace).chunked(2)
            .map { it.toInt(16).toByte() }.toByteArray()

        fun tcp(flags: Int = 0x10, data: ByteArray = ByteArray(0), headerBytes: Int = 20,
            magic: Int = 0xfaceface.toInt()): ByteArray {
            val payload = ByteArray(headerBytes + data.size)
            payload[0] = 0xf2.toByte(); payload[1] = 0x7e; payload[3] = 1
            payload[12] = ((headerBytes / 4) shl 4).toByte(); payload[13] = flags.toByte()
            data.copyInto(payload, headerBytes)
            return mux(6, payload, magic)
        }

        fun mux(protocol: Int, payload: ByteArray, magic: Int = 0xfaceface.toInt()): ByteArray =
            ByteArray(16 + payload.size).also {
                putU32(it, 0, protocol); putU32(it, 4, it.size); putU32(it, 8, magic)
                it[13] = 1; payload.copyInto(it, 16)
            }

        fun putU32(bytes: ByteArray, offset: Int, value: Int) {
            for (i in 0..3) bytes[offset + i] = (value ushr (24 - i * 8)).toByte()
        }
    }
}
