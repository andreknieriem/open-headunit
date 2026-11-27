package com.andrerinas.openheadunit.connection.carplaynative

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkSignerTest {

    private fun reply(status: Int, data: ByteArray) =
        byteArrayOf(status.toByte(), (data.size shr 8).toByte(), data.size.toByte()) + data

    private class Link(replyBytes: ByteArray) {
        val sent = ByteArrayOutputStream()
        val streams = LinkStreams(ByteArrayInputStream(replyBytes), sent)
    }

    /** Hands out the prepared links one by one; an entry of null is a connect failure. */
    private class Connector(private val links: List<Link?>) {
        var connects = 0
        val sleeps = ArrayList<Long>()
        fun signer() = LinkSigner(
            connect = {
                val link = links.getOrNull(connects++) ?: throw IOException("connection refused")
                link.streams
            },
            sleepMillis = { sleeps.add(it) },
        )
    }

    @Test
    fun `the requests are the three opcodes`() {
        assertArrayEquals(byteArrayOf(1), LinkCodec.certificateRequest())
        assertArrayEquals(byteArrayOf(3), LinkCodec.protocolMajorRequest())
        assertArrayEquals(byteArrayOf(2, 0, 3, 7, 8, 9), LinkCodec.signRequest(byteArrayOf(7, 8, 9)))
        val max = LinkCodec.signRequest(ByteArray(128))
        assertEquals(3 + 128, max.size)
        assertEquals(128, max[2].toInt() and 0xff)
    }

    @Test
    fun `a challenge outside 1 to 128 bytes makes no request`() {
        for (n in listOf(0, 129)) {
            try { LinkCodec.signRequest(ByteArray(n)); org.junit.Assert.fail("accepted $n") } catch (e: IllegalArgumentException) { }
        }
    }

    @Test
    fun `a reply is status, a big-endian length and data`() {
        val data = ByteArray(300) { it.toByte() }
        val r = LinkCodec.readReply(ByteArrayInputStream(reply(0, data) + byteArrayOf(9)))
        assertArrayEquals(data, (r as LinkCodec.Reply.Ok).data)
        val refused = LinkCodec.readReply(ByteArrayInputStream(reply(1, ByteArray(0))))
        assertEquals(1, (refused as LinkCodec.Reply.Refused).status)
    }

    @Test
    fun `a reply that stops early is an IOException`() {
        try { LinkCodec.readReply(ByteArrayInputStream(byteArrayOf(0, 0, 5, 1, 2))); org.junit.Assert.fail("short reply") } catch (e: IOException) { }
    }

    @Test
    fun `a reply that arrives in pieces is read whole`() {
        val bytes = reply(0, byteArrayOf(4, 5, 6))
        val slow = object : InputStream() {
            var pos = 0
            override fun read() = if (pos < bytes.size) bytes[pos++].toInt() and 0xff else -1
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= bytes.size) return -1
                b[off] = bytes[pos++]
                return 1
            }
        }
        assertArrayEquals(byteArrayOf(4, 5, 6), (LinkCodec.readReply(slow) as LinkCodec.Reply.Ok).data)
    }

    @Test
    fun `the signer reads the major, the certificate and a signature over one connection`() {
        val link = Link(reply(0, byteArrayOf(3)) + reply(0, byteArrayOf(0x30, 1)) + reply(0, ByteArray(64) { 2 }))
        val c = Connector(listOf(link))
        val signer = c.signer()
        assertEquals(3, signer.protocolMajor())
        assertArrayEquals(byteArrayOf(0x30, 1), signer.certificate())
        assertArrayEquals(ByteArray(64) { 2 }, signer.sign(ByteArray(32)))
        assertEquals(1, c.connects)
        assertArrayEquals(byteArrayOf(3, 1, 2, 0, 32) + ByteArray(32), link.sent.toByteArray())
    }

    @Test
    fun `an I-O failure is retried once on a new connection after a wait`() {
        val dead = Link(ByteArray(0))
        val good = Link(reply(0, byteArrayOf(9, 9)))
        val c = Connector(listOf(dead, good))
        assertArrayEquals(byteArrayOf(9, 9), c.signer().certificate())
        assertEquals(2, c.connects)
        assertEquals(listOf(LinkSigner.RETRY_WAIT_MILLIS), c.sleeps)
    }

    @Test
    fun `two failures in a row end in a SignerException`() {
        val c = Connector(listOf(Link(ByteArray(0)), Link(ByteArray(0))))
        try { c.signer().certificate(); org.junit.Assert.fail("no failure") } catch (e: SignerException) {
            assertTrue(e.message!!.contains("retry"))
        }
        assertEquals(2, c.connects)
    }

    @Test
    fun `a refusal from the link is final and is not retried`() {
        val c = Connector(listOf(Link(reply(1, ByteArray(0))), Link(reply(0, byteArrayOf(1)))))
        try { c.signer().certificate(); org.junit.Assert.fail("refusal accepted") } catch (e: SignerException) { }
        assertEquals(1, c.connects)
        assertTrue(c.sleeps.isEmpty())
    }

    @Test
    fun `a connection that failed is not used again by the next request`() {
        val first = Link(reply(0, byteArrayOf(1)))
        val second = Link(reply(0, byteArrayOf(2)))
        val c = Connector(listOf(first, second))
        val signer = c.signer()
        assertArrayEquals(byteArrayOf(1), signer.certificate())
        // The first connection has no more bytes, so the next request fails on it and moves to the second.
        assertArrayEquals(byteArrayOf(2), signer.certificate())
        assertEquals(2, c.connects)
    }

    @Test
    fun `a major other than 2 or 3 is refused`() {
        val c = Connector(listOf(Link(reply(0, byteArrayOf(7)))))
        try { c.signer().protocolMajor(); org.junit.Assert.fail("major 7 accepted") } catch (e: SignerException) { }
    }
}
