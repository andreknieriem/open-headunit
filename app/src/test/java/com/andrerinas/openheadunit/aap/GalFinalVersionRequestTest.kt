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
        private val responseDelayMs: Long = 0,
        private val replyByRequest: ((Int) -> ByteArray)? = null
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
                remaining += replyByRequest?.invoke(requests.size) ?: reply
                delayRemaining = responseDelayMs
                bytes.size
            }
        }, {})

        fun send(profile: GalProfile = GalProfile.GAL_1_6, deadline: Long = 1000, possibleOpeningReplies: Int = 0) =
            request.request(profile, deadline, possibleOpeningReplies)
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

    @Test fun `final confirmation retransmissions share one deadline`() {
        val phone = Phone(reply(GalVersion(1, 7)), responseDelayMs = 30)
        assertThrows(IOException::class.java) { phone.send(deadline = 50, possibleOpeningReplies = 1) }
        assertEquals(50L, phone.now)
        assertEquals(listOf(GalVersion(1, 6), GalVersion(1, 6)), phone.requests)
    }

    @Test fun `accepted nonzero statuses retain the existing compatibility behavior`() {
        val session = Phone(reply(GalVersion(1, 7), status = 1)).send(GalProfile.COMPATIBILITY)
        assertEquals(GalProfile.COMPATIBILITY, session.profile)
        assertEquals(1, session.finalResponse.status)
    }

    @Test fun `late opening replies require a later valid response and bound same-profile resends`() {
        val stale = reply(GalVersion(6, 0))
        val final = reply(GalVersion(1, 7))
        // Only one write actually produces more data; its first two responses are late
        // opening replies. No more writes are needed after both outstanding replies are covered.
        val phone = Phone(byteArrayOf(), replyByRequest = { if (it == 1) stale + stale + final else byteArrayOf() })
        val session = phone.send(possibleOpeningReplies = 2)
        assertEquals(GalVersion(1, 7), session.finalResponse.version)
        assertEquals(List(3) { GalVersion(1, 6) }, phone.requests)
        assertTrue(phone.remaining.isEmpty())
    }

    @Test fun `identical replies confirm a lower profile even when opening retransmissions were lost`() {
        val same = reply(GalVersion(1, 7))
        val phone = Phone(same)
        assertEquals(GalVersion(1, 7), phone.send(possibleOpeningReplies = 2).finalResponse.version)
        assertEquals(List(3) { GalVersion(1, 6) }, phone.requests)
        assertEquals(GalVersion(1, 7), Phone(same).send().finalResponse.version)
    }

    @Test fun `reply contents alone do not confirm a retried opening exchange`() {
        val phone = Phone(byteArrayOf(), replyByRequest = {
            if (it == 1) reply(GalVersion(1, 7), status = 1) else byteArrayOf()
        })
        assertThrows(IOException::class.java) { phone.send(possibleOpeningReplies = 1) }
        assertEquals(List(2) { GalVersion(1, 6) }, phone.requests)
        assertEquals(0, phone.request.responseSize)
    }
}
