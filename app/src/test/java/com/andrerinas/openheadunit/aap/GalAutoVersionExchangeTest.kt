package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class GalAutoVersionExchangeTest {
    private fun reply(version: GalVersion, status: Int = 0, options: ByteArray = byteArrayOf()): ByteArray {
        fun u16(value: Int) = byteArrayOf((value shr 8).toByte(), value.toByte())
        return byteArrayOf(0, 3) + u16(8 + options.size) + byteArrayOf(0, 2) +
            u16(version.major) + u16(version.minor) + u16(status) + options
    }

    private inner class Phone(
        private val replies: List<ByteArray>,
        private val chunkSize: Int = Int.MAX_VALUE,
        private val failWriteAt: Int = -1,
        private val writeResult: Int = -1,
        private val responseDelayMs: Long = 0
    ) {
        var now = 0L
        var remaining = byteArrayOf()
        val requests = mutableListOf<GalVersion>()
        private var delayRemaining = 0L
        val exchange = GalAutoVersionExchange(HandshakeFrameReader({ now }) { out, length, timeout ->
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
            val phase = requests.size
            requests.add(GalVersion(((bytes[6].toInt() and 255) shl 8) or (bytes[7].toInt() and 255),
                ((bytes[8].toInt() and 255) shl 8) or (bytes[9].toInt() and 255)))
            if (phase == failWriteAt) writeResult else {
                remaining += replies.getOrElse(phase) { byteArrayOf() }
                delayRemaining = responseDelayMs
                bytes.size
            }
        }, {})

        fun negotiate(deadline: Long = 1000) = exchange.negotiate(deadline)
    }

    @Test fun `legacy ceiling keeps compatibility without probing or changing its request`() {
        val phone = Phone(listOf(reply(GalVersion(1, 5))))
        val session = phone.negotiate()
        assertEquals(GalProfile.COMPATIBILITY, session.profile)
        assertEquals(listOf(GalVersion(1, 2)), phone.requests)
        assertEquals(GalVersion(1, 5), session.finalResponse.version)
    }

    @Test fun `supported legacy upgrade gets an explicit final request without the modern probe`() {
        val phone = Phone(List(2) { reply(GalVersion(1, 6)) })
        assertEquals(GalProfile.GAL_1_6, phone.negotiate().profile)
        assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 6)), phone.requests)
    }

    @Test fun `hidden ceiling selects only implemented profiles and cannot exceed receiver support`() {
        val cases = listOf(
            GalVersion(4, 5) to GalProfile.GAL_1_6,
            GalVersion(5, 0) to GalProfile.GAL_5_0,
            GalVersion(5, 1) to GalProfile.GAL_5_1,
            GalVersion(5, 9) to GalProfile.GAL_5_1,
            GalVersion(6, 0) to GalProfile.GAL_6_0,
            GalVersion(6, 1) to GalProfile.GAL_6_1,
            GalVersion(8, 0) to GalProfile.GAL_6_1
        )
        for ((ceiling, selected) in cases) {
            val finalReply = if (selected.version.major == 1) GalVersion(1, 7) else ceiling
            val phone = Phone(listOf(reply(GalVersion(1, 7)), reply(ceiling), reply(finalReply)), chunkSize = 1)
            assertEquals(selected, phone.negotiate().profile)
            assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8), selected.version), phone.requests)
        }
    }

    @Test fun `probe cannot leave the sender configured for 1 dot 8 when falling back to compatibility`() {
        val phone = Phone(listOf(reply(GalVersion(1, 7)), reply(GalVersion(1, 5)), reply(GalVersion(1, 5))))
        assertEquals(GalProfile.COMPATIBILITY, phone.negotiate().profile)
        assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8), GalVersion(1, 2)), phone.requests)
    }

    @Test fun `final response owns options and coalesced TLS data stays with the next reader`() {
        fun options(timeout: Int) = Control.VersionResponseOptions.newBuilder()
            .setConnectionConfiguration(Control.ConnectionConfiguration.newBuilder()
                .setPingConfiguration(Control.PingConfiguration.newBuilder().setTimeoutMs(timeout)))
            .build().toByteArray()
        val tls = byteArrayOf(0, 3, 0, 3, 0, 3, 22)
        val phone = Phone(listOf(reply(GalVersion(1, 7), options = options(100)),
            reply(GalVersion(6, 1), options = options(200)),
            reply(GalVersion(6, 1), options = options(9000)) + tls), chunkSize = 3)
        val session = phone.negotiate()
        assertEquals(9000, session.finalResponse.requestedConfig!!.pingConfiguration.timeoutMs)
        assertArrayEquals(tls, phone.remaining)
    }

    @Test fun `failure in any phase never retransmits or advances to another request`() {
        val valid = listOf(reply(GalVersion(1, 7)), reply(GalVersion(6, 1)), reply(GalVersion(6, 1)))
        val failures = listOf(
            reply(GalVersion(6, 1), status = -1),
            byteArrayOf(0, 3, 0, 2, 0, 2), // Complete envelope, missing version body.
            reply(GalVersion(6, 1)).copyOf(9), // A partial response must not restart its deadline.
            byteArrayOf(0, 3, 0, 2, 0, 15),
            byteArrayOf()
        )
        for (phase in valid.indices) for (failure in failures) {
            val phone = Phone(valid.take(phase) + listOf(failure))
            assertThrows(IOException::class.java) { phone.negotiate() }
            assertEquals(phase + 1, phone.requests.size)
        }
    }

    @Test fun `zero partial and ambiguous writes never introduce duplicate responses`() {
        val replies = listOf(reply(GalVersion(1, 7)), reply(GalVersion(6, 1)), reply(GalVersion(6, 1)))
        for (phase in replies.indices) for (written in listOf(0, 5, -1)) {
            val phone = Phone(replies, failWriteAt = phase, writeResult = written)
            assertThrows(IOException::class.java) { phone.negotiate() }
            assertEquals(phase + 1, phone.requests.size)
            assertTrue(phone.exchange.transportFailed)
        }
    }

    @Test fun `successful final status below the selected profile cannot authorize the session`() {
        val phone = Phone(listOf(reply(GalVersion(1, 7)), reply(GalVersion(6, 1)), reply(GalVersion(6, 0))))
        assertThrows(IOException::class.java) { phone.negotiate() }
        assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8), GalVersion(6, 1)), phone.requests)
    }

    @Test fun `phases share one deadline instead of each receiving a fresh timeout`() {
        val phone = Phone(listOf(reply(GalVersion(1, 7)), reply(GalVersion(6, 1)), reply(GalVersion(6, 1))),
            responseDelayMs = 60)
        assertThrows(IOException::class.java) { phone.negotiate(deadline = 100) }
        assertEquals(listOf(GalVersion(1, 2), GalVersion(1, 8)), phone.requests)
        assertEquals(100L, phone.now)
    }

    @Test fun `invalid or below-minimum ceilings cannot activate a receiver profile`() {
        for (ceiling in listOf(GalVersion(0, 0), GalVersion(65535, 1), GalVersion(1, 1))) {
            val phone = Phone(listOf(reply(ceiling)))
            assertThrows(IOException::class.java) { phone.negotiate() }
            assertEquals(listOf(GalVersion(1, 2)), phone.requests)
        }
    }

    @Test fun `accepted nonzero statuses retain the existing compatibility behavior`() {
        val phone = Phone(listOf(reply(GalVersion(1, 7), status = 255),
            reply(GalVersion(6, 1), status = -8), reply(GalVersion(6, 1), status = 1)))
        val session = phone.negotiate()
        assertEquals(GalProfile.GAL_6_1, session.profile)
        assertEquals(1, session.finalResponse.status)
    }
}
