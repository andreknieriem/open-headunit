package com.andrerinas.openheadunit.connection.carplaynative.codec

import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases come from LIVI `rtsp.rs` and its `ts_vectors.json` entries `rtsp` and `rtsp500`. */
class RtspTest {

    private fun ok(buf: ByteArray) = Rtsp.parse(buf) as Rtsp.Parsed.Ok

    @Test
    fun theLiviResponseVectorsMatch() {
        val request = ok("GET /info RTSP/1.0\r\nCSeq: 5\r\n\r\n".toByteArray()).requests[0]
        val reply = Rtsp.buildResponse(
            request,
            Rtsp.Response(
                headers = listOf("Content-Type" to "application/x-apple-binary-plist", "CSeq" to "0"),
                body = "ab".toByteArray(),
            ),
        )
        assertEquals(
            "525453502f312e3020323030204f4b0d0a436f6e74656e742d547970653a206170706c69636174696f6e2f782d6170706c652d62696e6172792d706c6973740d0a435365713a20350d0a436f6e74656e742d4c656e6774683a20320d0a0d0a6162",
            reply.toHex(),
        )
        assertEquals(
            "525453502f312e302035303020496e7465726e616c20536572766572204572726f720d0a435365713a20350d0a436f6e74656e742d4c656e6774683a20300d0a0d0a",
            Rtsp.buildResponse(request, Rtsp.Response(status = 500)).toHex(),
        )
    }

    @Test
    fun parsesRequestsAndKeepsThePartialRest() {
        val buf = ("POST /pair-verify RTSP/1.0\r\nCSeq: 3\r\nContent-Length: 4\r\n\r\nbody" +
            "GET /info RTSP/1.0\r\nCSeq: 4\r\n\r\n" +
            "POST /feedback RTSP/1.0\r\nContent-Length: 10\r\n\r\npart").toByteArray()
        val parsed = ok(buf)
        assertEquals(2, parsed.requests.size)
        assertEquals("POST", parsed.requests[0].method)
        assertEquals("/pair-verify", parsed.requests[0].path)
        assertEquals("3", parsed.requests[0].headers["cseq"])
        assertArrayEquals("body".toByteArray(), parsed.requests[0].body)
        assertEquals("/info", parsed.requests[1].path)
        assertEquals(0, parsed.requests[1].body.size)
        assertTrue(String(parsed.rest).startsWith("POST /feedback"))
    }

    @Test
    fun aRequestSplitOverThreeReadsParsesOnce() {
        val whole = "POST /pair-setup RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 6\r\n\r\nabcdef".toByteArray()
        var pending = ByteArray(0)
        var seen = 0
        for ((from, to) in listOf(0 to 20, 20 to 55, 55 to whole.size)) {
            val parsed = ok(pending + whole.copyOfRange(from, to))
            seen += parsed.requests.size
            pending = parsed.rest
            if (to < whole.size) assertEquals(0, parsed.requests.size)
        }
        assertEquals(1, seen)
        assertEquals(0, pending.size)
    }

    @Test
    fun aMissingProtocolDefaultsToRtsp() {
        assertEquals("RTSP/1.0", ok("OPTIONS *\r\n\r\n".toByteArray()).requests[0].protocol)
        assertEquals("", ok("OPTIONS\r\n\r\n".toByteArray()).requests[0].path)
    }

    @Test
    fun contentLengthReadsLeadingDigits() {
        assertEquals(12, Rtsp.contentLength("12abc"))
        assertEquals(0, Rtsp.contentLength("x"))
        assertEquals(0, Rtsp.contentLength(null))
    }

    @Test
    fun aBodyOverOneMiBIsRefusedInsteadOfBuffered() {
        for (length in listOf("18446744073709551615", "99999999999999999999999", (Rtsp.MAX_BODY + 1).toString())) {
            val head = "POST /command RTSP/1.0\r\nContent-Length: $length\r\n\r\n".toByteArray()
            assertSame(Rtsp.Parsed.TooLarge, Rtsp.parse(head))
        }
        val exact = "POST /command RTSP/1.0\r\nContent-Length: ${Rtsp.MAX_BODY}\r\n\r\n".toByteArray()
        assertEquals(0, ok(exact).requests.size)
    }

    @Test
    fun aHeaderSectionPastTheCapIsTooLargeEndedOrNot() {
        val open = "GET /info RTSP/1.0\r\nX: ".toByteArray() + ByteArray(Rtsp.MAX_HEADER) { 'a'.code.toByte() }
        assertSame(Rtsp.Parsed.TooLarge, Rtsp.parse(open))
        assertSame(Rtsp.Parsed.TooLarge, Rtsp.parse(open + "\r\n\r\n".toByteArray()))
        assertEquals(0, ok(open.copyOf(Rtsp.MAX_HEADER)).requests.size)
    }

    @Test
    fun aResponseEchoesCseqAndCountsTheBody() {
        val request = ok("GET /info RTSP/1.0\r\nCSeq: 9\r\n\r\n".toByteArray()).requests[0]
        val reply = Rtsp.buildResponse(
            request,
            Rtsp.Response(headers = listOf("Content-Type" to "application/x-apple-binary-plist"), body = "xy".toByteArray()),
        )
        assertEquals(
            "RTSP/1.0 200 OK\r\nContent-Type: application/x-apple-binary-plist\r\nCSeq: 9\r\nContent-Length: 2\r\n\r\nxy",
            String(reply),
        )
        assertEquals(
            "RTSP/1.0 404 Not Found\r\nCSeq: 9\r\nContent-Length: 0\r\n\r\n",
            String(Rtsp.buildResponse(request, Rtsp.Response(status = 404))),
        )
    }
}
