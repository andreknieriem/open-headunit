package com.andrerinas.openheadunit.connection.carplaynative.iap2

import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.CONTROL_ACK
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.CONTROL_EAK
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.CONTROL_RST
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.CONTROL_SYN
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.DETECT_MARKER
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.HEADER_LEN
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.SessionInfo
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2LinkFrame.SyncParams
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from LIVI (https://github.com/f-io/LIVI) `iap2-link/tests/engine.rs`.
 * The frame builder here is written apart from the code under test.
 */
class Iap2LinkTest {
    private val control = Iap2Link.CONTROL_SESSION_ID

    private fun sum(bytes: ByteArray): Int = (-bytes.sumOf { it.toInt() and 0xFF }) and 0xFF

    private fun frame(ctl: Int, seq: Int, ack: Int, session: Int, payload: ByteArray?): ByteArray {
        val length = if (payload == null) HEADER_LEN else HEADER_LEN + payload.size + 1
        val head = byteArrayOf(0xFF.toByte(), 0x5A, (length shr 8).toByte(), length.toByte(), ctl.toByte(), seq.toByte(), ack.toByte(), session.toByte())
        val out = head + byteArrayOf(sum(head).toByte())
        return if (payload == null) out else out + payload + byteArrayOf(sum(payload).toByte())
    }

    private fun data(seq: Int, ack: Int, session: Int, payload: ByteArray) = frame(CONTROL_ACK, seq, ack, session, payload)

    private fun deviceParams() = SyncParams(8, 1024, 1000, 100, 3, 2, listOf(SessionInfo(control, 0, 1)))

    private fun handshakenWith(params: SyncParams): Iap2Link {
        val e = Iap2Link()
        e.start(false, 0)
        e.feed(DETECT_MARKER, 0)
        e.feed(frame(CONTROL_SYN, DEVICE_PSN, 0, 0, params.toBytes()), 0)
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 0, 0, null), 0)
        assertEquals(Iap2Link.State.Normal, e.state)
        e.takeOutput()
        while (e.pollEvent() != null) Unit
        return e
    }

    private fun handshaken() = handshakenWith(deviceParams())

    private fun events(e: Iap2Link): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val ev = e.pollEvent() ?: return out
            out += when (ev) {
                is Iap2Link.Event.Control -> "C:" + String(ev.bytes)
                is Iap2Link.Event.FileTransfer -> "F:" + ev.bytes.toHex()
                is Iap2Link.Event.Dead -> "D:" + (ev.error?.text ?: "eof")
            }
        }
    }

    private fun splitFrames(all: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < all.size) {
            val len = if (all.copyOfRange(pos, minOf(pos + 6, all.size)).contentEquals(DETECT_MARKER)) 6
            else ((all[pos + 2].toInt() and 0xFF) shl 8) or (all[pos + 3].toInt() and 0xFF)
            out += all.copyOfRange(pos, pos + len)
            pos += len
        }
        return out
    }

    @Test fun headerPackMatchesReference() {
        val bytes = Iap2LinkFrame.Header(9, 0x40, 0x63, 0x05, 0).toBytes()
        assertEquals("ff5a000940630500f6", bytes.toHex())
        val back = Iap2LinkFrame.parseHeader(bytes, 0)!!
        assertEquals(listOf(9, 0x40, 0x63, 0x05, 0), listOf(back.length, back.control, back.seq, back.ack, back.sessionId))
    }

    @Test fun defaultParamsRoundTrip() {
        val e = Iap2Link()
        val packed = e.localParams.toBytes()
        assertEquals("0104ffff05dc006405020a00010b02010c0102", packed.toHex())
        assertEquals(e.localParams, SyncParams.parse(packed))
    }

    @Test fun initiatorStartEmitsMarkerAndSyn() {
        val e = Iap2Link()
        e.start(true, 0)
        val expected = DETECT_MARKER + frame(CONTROL_SYN, 0, 0, 0, e.localParams.toBytes())
        assertArrayEquals(expected, e.takeOutput())
        assertEquals(Iap2Link.State.Negotiate, e.state)
    }

    @Test fun passiveHandshakeReachesNormal() {
        val e = Iap2Link()
        e.start(false, 0)
        assertArrayEquals(DETECT_MARKER, e.takeOutput())
        e.feed(DETECT_MARKER, 0)
        assertEquals(Iap2Link.State.Negotiate, e.state)
        assertEquals(CONTROL_SYN, e.takeOutput()[4].toInt() and 0xFF)
        e.feed(frame(CONTROL_SYN, DEVICE_PSN, 0, 0, deviceParams().toBytes()), 0)
        assertEquals(deviceParams(), e.linkParams)
        assertArrayEquals(frame(CONTROL_ACK, 0, DEVICE_PSN, 0, null), e.takeOutput())
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 0, 0, null), 0)
        assertEquals(Iap2Link.State.Normal, e.state)
    }

    @Test fun ackForAnotherPsnDoesNotFinishNegotiation() {
        val e = Iap2Link()
        e.start(true, 0)
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 77, 0, null), 0)
        assertEquals(Iap2Link.State.Negotiate, e.state)
    }

    @Test fun repeatedSynAfterHandshakeIsAckedAgain() {
        val e = handshaken()
        e.feed(frame(CONTROL_SYN, DEVICE_PSN, 0, 0, deviceParams().toBytes()), 0)
        assertArrayEquals(frame(CONTROL_ACK, 0, DEVICE_PSN, 0, null), e.takeOutput())
        assertEquals(Iap2Link.State.Normal, e.state)
    }

    @Test fun synAckThatRepeatsAfterNormalIsAckedAgain() {
        val e = handshaken()
        e.feed(frame(CONTROL_SYN or CONTROL_ACK, DEVICE_PSN, 0, 0, deviceParams().toBytes()), 0)
        assertArrayEquals(frame(CONTROL_ACK, 0, DEVICE_PSN, 0, null), e.takeOutput())
    }

    @Test fun aSynWithAnotherPsnInNormalIsIgnored() {
        val e = handshaken()
        e.feed(frame(CONTROL_SYN, 0x55, 0, 0, deviceParams().toBytes()), 0)
        assertEquals(0, e.takeOutput().size)
        assertEquals(deviceParams(), e.linkParams)
    }

    @Test fun synIsResentThenGivesUp() {
        val e = Iap2Link()
        e.start(true, 0)
        val first = e.takeOutput()
        e.advanceTime(500)
        assertArrayEquals(first.copyOfRange(DETECT_MARKER.size, first.size), e.takeOutput())
        for (t in 2..21) e.advanceTime(t * 500L)
        assertEquals(Iap2Link.State.Dead, e.state)
        assertTrue(events(e).contains("D:link synchronisation timed out"))
    }

    @Test fun theMarkerIsResentEverySecondThenTheLinkGivesUp() {
        val e = Iap2Link()
        e.start(false, 0)
        e.takeOutput()
        e.advanceTime(999)
        assertEquals(0, e.takeOutput().size)
        e.advanceTime(1000)
        assertArrayEquals(DETECT_MARKER, e.takeOutput())
        for (t in 2..31) e.advanceTime(t * 1000L)
        assertEquals(Iap2Link.State.Dead, e.state)
        assertTrue(events(e).contains("D:no iAP2 marker from the peer"))
    }

    @Test fun wrongMarkerDies() {
        val e = Iap2Link()
        e.start(false, 0)
        e.feed(ByteArray(6), 0)
        assertEquals(Iap2Link.State.Dead, e.state)
        assertEquals(listOf("D:peer does not speak iAP2"), events(e))
    }

    @Test fun retransmitsMaxTimesThenDies() {
        val e = handshaken()
        e.send(control, byteArrayOf(1, 2, 3), 0)
        val first = e.takeOutput()
        assertArrayEquals(data(1, DEVICE_PSN, control, byteArrayOf(1, 2, 3)), first)
        assertEquals(1000L, e.nextDeadline())
        for (t in listOf(1000L, 2000L, 3000L)) {
            e.advanceTime(t)
            assertArrayEquals("retransmit at $t", first, e.takeOutput())
        }
        assertEquals(Iap2Link.State.Normal, e.state)
        e.advanceTime(4000)
        assertEquals(Iap2Link.State.Dead, e.state)
        assertTrue(events(e).contains("D:packet 1 never acknowledged"))
    }

    @Test fun ackClearsRetransmit() {
        val e = handshaken()
        e.send(control, byteArrayOf(1), 0)
        e.takeOutput()
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 1, 0, null), 10)
        assertNull(e.nextDeadline())
        e.advanceTime(5000)
        assertEquals(Iap2Link.State.Normal, e.state)
    }

    @Test fun windowIsExactlyMaxOutstanding() {
        val e = handshaken()
        for (i in 0 until 10) e.send(control, byteArrayOf(i.toByte()), 0)
        assertEquals(8, splitFrames(e.takeOutput()).size)
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 8, 0, null), 10)
        assertEquals(2, splitFrames(e.takeOutput()).size)
    }

    @Test fun partialAckKeepsTheRestInFlight() {
        val e = handshaken()
        for (i in 0 until 8) e.send(control, byteArrayOf(i.toByte()), 0)
        e.takeOutput()
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 2, 0, null), 10)
        e.advanceTime(1000)
        assertEquals(listOf(3, 4, 5, 6, 7, 8), splitFrames(e.takeOutput()).map { it[5].toInt() })
    }

    @Test fun staleAckIsIgnored() {
        val e = handshaken()
        e.send(control, byteArrayOf(1), 0)
        e.takeOutput()
        e.feed(frame(CONTROL_ACK, DEVICE_PSN, 200, 0, null), 10)
        assertEquals(1000L, e.nextDeadline())
    }

    @Test fun eakFromPeerRetransmitsListedPackets() {
        val e = handshaken()
        for (i in 0 until 3) e.send(control, byteArrayOf(i.toByte()), 0)
        e.takeOutput()
        e.feed(frame(CONTROL_ACK or CONTROL_EAK, DEVICE_PSN, 1, 0, byteArrayOf(3)), 10)
        assertArrayEquals(data(3, DEVICE_PSN, control, byteArrayOf(2)), e.takeOutput())
    }

    @Test fun largePayloadIsSplitToPeerMaxPacketLen() {
        val e = handshakenWith(SyncParams(8, 20, 1000, 100, 3, 2, listOf(SessionInfo(control, 0, 1))))
        val msg = ByteArray(25) { it.toByte() }
        e.send(control, msg, 0)
        val frames = splitFrames(e.takeOutput())
        assertEquals(listOf(10, 10, 5), frames.map { it.size - HEADER_LEN - 1 })
        assertArrayEquals(msg, frames.map { it.copyOfRange(HEADER_LEN, it.size - 1) }.reduce { a, b -> a + b })
    }

    @Test fun aControlMessageOverThePeerLimitSplitsAndJoins() {
        val sender = handshakenWith(SyncParams(8, 20, 1000, 100, 3, 2, listOf(SessionInfo(control, 0, 1))))
        val message = Iap2Csm.build(0x4301) { string(0, "x".repeat(50)); u32(2, 7000) }.encode()
        sender.send(control, message, 0)
        val frames = splitFrames(sender.takeOutput())
        assertTrue(frames.size > 3)
        val receiver = handshaken()
        val framer = Iap2Csm.Framer()
        val joined = ArrayList<Iap2Frame>()
        frames.forEachIndexed { i, f ->
            // Re-address each packet to the receiver's expected psn run.
            val payload = f.copyOfRange(HEADER_LEN, f.size - 1)
            receiver.feed(data(0x2B + i, 0, control, payload), 0)
            while (true) {
                val ev = receiver.pollEvent() ?: break
                if (ev is Iap2Link.Event.Control) joined += framer.offer(ev.bytes)
            }
        }
        assertEquals(1, joined.size)
        assertArrayEquals(message, joined[0].encode())
    }

    @Test fun delayedAckThenCumulativeAck() {
        val e = handshaken()
        e.feed(data(0x2B, 0, control, "abc".toByteArray()), 0)
        assertEquals(listOf("C:abc"), events(e))
        assertEquals(0, e.takeOutput().size)
        assertEquals(100L, e.nextDeadline())
        e.advanceTime(100)
        assertArrayEquals(frame(CONTROL_ACK, 0, 0x2B, 0, null), e.takeOutput())
        e.feed(data(0x2C, 0, control, "d".toByteArray()), 200)
        e.feed(data(0x2D, 0, control, "e".toByteArray()), 200)
        assertArrayEquals(frame(CONTROL_ACK, 0, 0x2D, 0, null), e.takeOutput())
        assertNull(e.nextDeadline())
    }

    @Test fun outOfOrderReassemblySendsEak() {
        val e = handshaken()
        e.feed(data(0x2D, 0, control, "three".toByteArray()), 0)
        assertEquals(emptyList<String>(), events(e))
        assertArrayEquals(frame(CONTROL_ACK or CONTROL_EAK, 0, DEVICE_PSN, 0, byteArrayOf(0x2B, 0x2C)), e.takeOutput())
        e.feed(data(0x2C, 0, control, "two".toByteArray()), 0)
        e.feed(data(0x2B, 0, control, "one".toByteArray()), 0)
        assertEquals(listOf("C:one", "C:two", "C:three"), events(e))
    }

    @Test fun repeatedOutOfOrderPacketIsDeliveredOnce() {
        val e = handshaken()
        e.feed(data(0x2C, 0, control, "two".toByteArray()), 0)
        e.feed(data(0x2C, 0, control, "two".toByteArray()), 0)
        e.feed(data(0x2B, 0, control, "one".toByteArray()), 0)
        e.feed(data(0x2C, 0, control, "two".toByteArray()), 0)
        assertEquals(listOf("C:one", "C:two"), events(e))
    }

    @Test fun duplicateInOrderPacketIsReacked() {
        val e = handshaken()
        e.feed(data(0x2B, 0, control, "x".toByteArray()), 0)
        events(e)
        e.takeOutput()
        e.feed(data(0x2B, 0, control, "x".toByteArray()), 10)
        assertEquals(emptyList<String>(), events(e))
        assertArrayEquals(frame(CONTROL_ACK, 0, 0x2B, 0, null), e.takeOutput())
    }

    @Test fun fileTransferIsDeliveredAndTheEaSessionIsDropped() {
        val e = handshaken()
        e.feed(data(0x2B, 0, Iap2Link.FILE_TRANSFER_SESSION_ID, byteArrayOf(1, 2)), 0)
        e.feed(data(0x2C, 0, Iap2Link.EA_SESSION_ID, byteArrayOf(0, 7, 0x0A)), 0)
        assertEquals(listOf("F:0102"), events(e))
    }

    @Test fun resetFrameDies() {
        val e = handshaken()
        e.feed(frame(CONTROL_RST, 0, 0, 0, null), 0)
        assertEquals(Iap2Link.State.Dead, e.state)
        assertEquals(listOf("D:peer reset the link"), events(e))
    }

    @Test fun resyncSkipsGarbageAndFalseStart() {
        val e = handshaken()
        val noisy = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xFF.toByte(), 0x5A, 0x00, 0x01, 0xBE.toByte(), 0xEF.toByte()) +
            data(0x2B, 0, control, "x".toByteArray())
        e.feed(noisy.copyOfRange(0, 5), 0)
        e.feed(noisy.copyOfRange(5, noisy.size), 0)
        assertEquals(listOf("C:x"), events(e))
    }

    @Test fun aBadPayloadChecksumIsDroppedAndTheLinkResyncs() {
        val e = handshaken()
        val bad = data(0x2B, 0, control, "bad".toByteArray())
        bad[bad.size - 1] = (bad[bad.size - 1] + 1).toByte()
        e.feed(bad + data(0x2B, 0, control, "good".toByteArray()), 0)
        assertEquals(listOf("C:good"), events(e))
        assertEquals(Iap2Link.State.Normal, e.state)
    }

    @Test fun aBadHeaderChecksumIsSkippedByteByByte() {
        val e = handshaken()
        val bad = data(0x2B, 0, control, "bad".toByteArray())
        bad[8] = (bad[8] + 1).toByte()
        e.feed(bad + data(0x2B, 0, control, "good".toByteArray()), 0)
        assertEquals(listOf("C:good"), events(e))
    }

    @Test fun zeroAckLinkDoesNotTrackOrAck() {
        val zero = SyncParams(8, 1024, 0, 0, 0, 0, listOf(SessionInfo(control, 0, 1)))
        val e = handshakenWith(zero)
        for (i in 0 until 20) e.send(control, byteArrayOf(i.toByte()), 0)
        assertEquals(20, splitFrames(e.takeOutput()).size)
        assertNull(e.nextDeadline())
        e.feed(data(0x2B, 0, control, "x".toByteArray()), 0)
        assertEquals(0, e.takeOutput().size)
    }

    @Test fun anOutputThatNobodyDrainsEndsTheLink() {
        val zero = SyncParams(127, 0xFFFF, 0, 0, 0, 0, listOf(SessionInfo(control, 0, 1)))
        val e = handshakenWith(zero)
        val big = ByteArray(60_000)
        var i = 0
        while (e.state != Iap2Link.State.Dead && i < 40) {
            e.send(control, big, 0)
            i++
        }
        assertEquals(Iap2Link.State.Dead, e.state)
        assertTrue(events(e).contains("D:link buffer limit exceeded"))
    }

    @Test fun eventsThatNobodyReadsEndTheLink() {
        val e = handshaken()
        var psn = 0x2B
        var i = 0
        while (e.state != Iap2Link.State.Dead && i < 1000) {
            e.feed(data(psn, 0, control, byteArrayOf(1)), 0)
            psn = (psn + 1) and 0xFF
            i++
        }
        assertEquals(Iap2Link.State.Dead, e.state)
        assertTrue(i < 1000)
    }

    @Test fun nothingIsSentOrReceivedAfterTheLinkDied() {
        val e = handshaken()
        e.feedEof()
        e.takeOutput()
        e.send(control, byteArrayOf(1), 0)
        e.feed(data(0x2B, 0, control, "x".toByteArray()), 0)
        e.advanceTime(10_000)
        assertEquals(0, e.takeOutput().size)
        assertEquals(listOf("D:eof"), events(e))
    }

    private class Lossy(var state: Long) {
        fun roll(percent: Int): Boolean {
            state = state * 6364136223846793005L + 1
            return (state ushr 33) % 100 < percent
        }
    }

    private fun carry(net: Lossy, bytes: ByteArray, held: MutableList<ByteArray>): ByteArray {
        var out = ByteArray(0)
        for (f in splitFrames(bytes)) {
            if (f.size == 6 && f.contentEquals(DETECT_MARKER)) {
                out += f
                continue
            }
            if (net.roll(10)) continue
            if (net.roll(5)) out += f
            if (net.roll(10)) {
                held += f
                continue
            }
            out += f
            for (h in held) out += h
            held.clear()
        }
        return out
    }

    @Test fun lossyLoopbackDeliversEverythingOnceInOrder() {
        for (seed in 1L..50L) loopback(seed)
    }

    private fun loopback(seed: Long) {
        val cfg = Iap2Link.Config(maxOutstanding = 6, maxPacketLen = 64)
        val a = Iap2Link(cfg)
        val b = Iap2Link(cfg)
        a.start(false, 0)
        b.start(true, 0)
        val msgs = (0 until 300).map { i -> ByteArray(i % 120) { x -> (x xor i).toByte() } }.filter { it.isNotEmpty() }
        val expected = msgs.fold(ByteArray(0)) { acc, m -> acc + m }
        val net = Lossy(seed)
        val heldAb = ArrayList<ByteArray>()
        val heldBa = ArrayList<ByteArray>()
        var gotA = ByteArray(0)
        var gotB = ByteArray(0)
        var sent = false
        var t = 0L
        while (t < 600_000 && (gotA.size < expected.size || gotB.size < expected.size)) {
            t += 10
            if (!sent && a.state == Iap2Link.State.Normal && b.state == Iap2Link.State.Normal) {
                for (m in msgs) {
                    a.send(control, m, t)
                    b.send(control, m, t)
                }
                sent = true
            }
            val ab = carry(net, a.takeOutput(), heldAb)
            val ba = carry(net, b.takeOutput(), heldBa)
            b.feed(ab, t)
            a.feed(ba, t)
            a.advanceTime(t)
            b.advanceTime(t)
            for (pair in listOf(a to true, b to false)) {
                while (true) {
                    val ev = pair.first.pollEvent() ?: break
                    when (ev) {
                        is Iap2Link.Event.Control -> if (pair.second) gotA += ev.bytes else gotB += ev.bytes
                        is Iap2Link.Event.Dead -> throw AssertionError("seed $seed: link died: ${ev.error?.text}")
                        else -> Unit
                    }
                }
            }
        }
        assertArrayEquals("seed $seed A", expected, gotA)
        assertArrayEquals("seed $seed B", expected, gotB)
    }

    @Test fun anInitiatorAndAPassiveSideReachNormalAndTheMarkerIsNotMistakenForGarbage() {
        val a = Iap2Link()
        val b = Iap2Link()
        a.start(false, 0)
        b.start(true, 0)
        for (round in 0 until 6) {
            val ab = a.takeOutput()
            val ba = b.takeOutput()
            b.feed(ab, 0)
            a.feed(ba, 0)
        }
        assertEquals(Iap2Link.State.Normal, a.state)
        assertEquals(Iap2Link.State.Normal, b.state)
    }

    private companion object {
        const val DEVICE_PSN = 0x2A
    }

    @Test
    fun anOverflowDuringAResendEndsTheLinkWithTheOverflowError() {
        val e = handshakenWith(SyncParams(32, 0xFFFF, 1000, 100, 3, 2, listOf(SessionInfo(control, 0, 1))))
        repeat(18) {
            e.send(control, ByteArray(60_000), 0)
            e.takeOutput()
        }
        e.advanceTime(1000)
        assertEquals(listOf("D:" + Iap2LinkError.Overflow.text), events(e))
        assertEquals(Iap2Link.State.Dead, e.state)
    }
}
