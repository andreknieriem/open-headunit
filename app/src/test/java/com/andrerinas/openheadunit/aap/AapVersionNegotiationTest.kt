package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.proto.Control
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AapVersionNegotiationTest {

    /** ch 0, flags 3, len, type 0x0002, then major/minor/status. */
    private fun frame(major: Int, minor: Int, status: Int, tail: ByteArray = ByteArray(0)): ByteArray {
        fun hi(v: Int) = ((v shr 8) and 0xFF).toByte()
        fun lo(v: Int) = (v and 0xFF).toByte()
        return byteArrayOf(0, 3, hi(8 + tail.size), lo(8 + tail.size), 0, 2, hi(major), lo(major), hi(minor), lo(minor),
            hi(status), lo(status)) + tail
    }

    @Test
    fun `reads the version the phone selected, not the one we asked for`() {
        val r = AapVersionNegotiation.parse(frame(1, 6, 0), 12)!!
        assertEquals(1, r.major)
        assertEquals(6, r.minor)
        assertEquals(0, r.status)
        assertEquals("STATUS_SUCCESS", r.statusName)
    }

    @Test
    fun `a negative status arrives as an unsigned short and is named`() {
        val r = AapVersionNegotiation.parse(frame(1, 2, 0xFFFF), 12)!!
        assertEquals(-1, r.status)
        assertEquals("STATUS_NO_COMPATIBLE_VERSION", r.statusName)
    }

    @Test
    fun `the connection parameters the phone asks for are read back`() {
        val opts = Control.VersionResponseOptions.newBuilder()
            .setConnectionConfiguration(
                Control.ConnectionConfiguration.newBuilder().setPingConfiguration(
                    Control.PingConfiguration.newBuilder().setTimeoutMs(9000)
                )
            ).build().toByteArray()
        val f = frame(1, 6, 0, opts)
        val r = AapVersionNegotiation.parse(f, f.size)!!
        assertEquals(9000, r.requestedConfig!!.pingConfiguration.timeoutMs)
    }

    @Test
    fun `no options means no request, not a failure`() {
        assertNull(AapVersionNegotiation.parse(frame(1, 2, 0), 12)!!.requestedConfig)
    }

    @Test
    fun `trailing bytes that are not the options proto do not lose the version`() {
        val f = frame(1, 4, 0, byteArrayOf(-1, -1, -1, -1))
        val r = AapVersionNegotiation.parse(f, f.size)!!
        assertEquals(4, r.minor)
        assertNull(r.requestedConfig)
    }

    @Test
    fun `a frame too short to hold a version is refused`() {
        assertNull(AapVersionNegotiation.parse(frame(1, 2, 0), 11))
    }
    @Test
    fun `parser rejects incomplete extra or wrongly framed bytes`() {
        val good = frame(1, 7, 0)
        assertNull(AapVersionNegotiation.parse(good, good.size + 1))
        assertNull(AapVersionNegotiation.parse(good + byteArrayOf(0), good.size + 1))
        for (index in listOf(0, 1, 2, 3, 4, 5)) {
            val bad = good.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull(AapVersionNegotiation.parse(bad, bad.size))
        }
        assertTrue(AapVersionNegotiation.parse(frame(1, 1, 0), 12)!!.accepted)
        assertTrue(AapVersionNegotiation.parse(frame(6, 1, 0), 12)!!.accepted)
    }

    @Test fun `only no compatible version refuses TLS`() {
        assertTrue(AapVersionNegotiation.parse(frame(1, 7, 0), 12)!!.accepted)
        assertFalse(AapVersionNegotiation.parse(frame(1, 7, -1), 12)!!.accepted)
        // GAL codes: 1(STATUS_UNSOLICITED_MESSAGE), -8(STATUS_MEDIA_CONFIG_MISMATCH).
        // The remaining values exercise unknown codes and the signed 16-bit boundaries.
        for (status in listOf(1, -8, 255, 32767, -32768)) {
            assertTrue("status=$status", AapVersionNegotiation.parse(frame(1, 7, status), 12)!!.accepted)
        }
    }
}
