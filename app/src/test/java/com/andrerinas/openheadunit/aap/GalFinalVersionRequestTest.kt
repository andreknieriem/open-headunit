package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GalFinalVersionRequestTest {
    private fun reply(version: GalVersion, status: Int = 0, options: ByteArray = byteArrayOf()): ByteArray {
        fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        return byteArrayOf(0, 3) + u16(8 + options.size) + byteArrayOf(0, 2) +
            u16(version.major) + u16(version.minor) + u16(status) + options
    }

    private inner class Phone(
        private val reply: ByteArray,
        private val chunkSize: Int = Int.MAX_VALUE,
        private val writeResult: Int? = null,
        private val responseDelayMs: Long = 0
    ) {
        var now = 0L
        var remaining = byteArrayOf()
        val requests = mutableListOf<GalVersion>()
        private var delayRemaining = 0L
        val request = GalFinalVersionRequest(HandshakeFrameReader({ now }) { out, length, timeout ->
            if (delayRemaining >= timeout) {
                now += timeout
                delayRemaining -= timeout
                0
            } else {
                now += delayRemaining
                delayRemaining = 0
                if (remaining.isEmpty()) {
                    now += timeout
                    0
                } else {
                    val count = minOf(length, chunkSize, remaining.size)
                    remaining.copyInto(out, 0, 0, count)
                    remaining = remaining.copyOfRange(count, remaining.size)
                    count
                }
            }
        }, { now }, { bytes, _ ->
            requests.add(GalVersion(((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255),
                ((bytes[8].toInt() and 255) shl 8) or (bytes[9].toInt() and 255)))
            writeResult ?: run {
                remaining += reply
                delayRemaining = responseDelayMs
                bytes.size
            }
        }, {})

        fun send(profile: GalProfile = GalProfile.GAL_1_6, deadline: Long = 1000, staleReplies: Int = 0) =
            request.request(profile, deadline, OPENING, staleReplies)
    }

    @Test fun `final response owns its options and coalesced TLS data stays with the next reader`() {
        val options = Control.VersionResponseOptions.newBuilder()
            .setConnectionConfiguration(Control.ConnectionConfiguration.newBuilder()
                .setPingConfiguration(Control.PingConfiguration.newBuilder().setTimeoutMs(9000)))
            .build().toByteArray()
        val tls = byteArrayOf(0, 3, 0, 3, 0, 3, 22)
        val phone = Phone(reply(GalVersion(1, 7), options = options) + tls, chunkSize = 3)
        val session = phone.send()
        assertEquals(GalProfile.GAL_1_6, session.profile)
        assertEquals(listOf(GalVersion(1, 6)), phone.requests)
        assertEquals(9000, session.finalResponse.requestedConfig!!.pingConfiguration.timeoutMs)
        assertArrayEquals(tls, phone.remaining)
    }

    @Test fun `failure never retransmits the final request`() {
        val failures = listOf(
            reply(GalVersion(1, 7), status = -1),
            byteArrayOf(0, 3, 0, 2, 0, 2), // Complete envelope, missing version body.
            reply(GalVersion(1, 7)).copyOf(9), // A partial response must not restart its deadline.
            byteArrayOf(0, 3, 0, 2, 0, 15),
            byteArrayOf()
        )
        for (failure in failures) {
            val phone = Phone(failure)
            assertThrows(IOException::class.java) { phone.send() }
            assertEquals(listOf(GalVersion(1, 6)), phone.requests)
            assertFalse(phone.request.transportFailed)
        }
    }

    @Test fun `zero partial and ambiguous writes fail as transport errors`() {
        for (written in listOf(0, 5, -1)) {
            val phone = Phone(reply(GalVersion(1, 7)), writeResult = written)
            assertThrows(IOException::class.java) { phone.send() }
            assertEquals(1, phone.requests.size)
            assertTrue(phone.request.transportFailed)
        }
    }

    // Inspected senders answer success even above their maximum: status alone is not enough.
    @Test fun `successful reply below or outside the request cannot authorize the session`() {
        for (version in listOf(GalVersion(1, 5), GalVersion(0, 0), GalVersion(65535, 1))) {
            val phone = Phone(reply(version))
            assertThrows(IOException::class.java) { phone.send() }
        }
    }

    @Test fun `final request uses the remaining negotiation deadline`() {
        val phone = Phone(reply(GalVersion(1, 7)), responseDelayMs = 60)
        assertThrows(IOException::class.java) { phone.send(deadline = 50) }
        assertEquals(50L, phone.now)
    }

    @Test fun `accepted nonzero statuses retain the existing compatibility behavior`() {
        val session = Phone(reply(GalVersion(1, 7), status = 1)).send(GalProfile.COMPATIBILITY)
        assertEquals(GalProfile.COMPATIBILITY, session.profile)
        assertEquals(1, session.finalResponse.status)
    }

    // A USB opening request resent after a silent window can be answered after the final request.
    @Test fun `repeated opening replies from resends are skipped but never beyond the resend count`() {
        val stale = reply(OPENING.version)
        val phone = Phone(stale + stale + reply(GalVersion(1, 7)))
        val session = phone.send(staleReplies = 2)
        assertEquals(GalVersion(1, 7), session.finalResponse.version)
        assertEquals(listOf(GalVersion(1, 6)), phone.requests)

        // Without an outstanding resend, or beyond the resend count, an equal reply is this
        // request's own answer.
        assertEquals(OPENING.version, Phone(stale).send().finalResponse.version)
        val counted = Phone(stale + stale)
        assertEquals(OPENING.version, counted.send(staleReplies = 1).finalResponse.version)
        assertTrue(counted.remaining.isEmpty())
    }

    private companion object {
        val OPENING = AapVersionNegotiation.Result(6, 0, 0, null)
    }
}
