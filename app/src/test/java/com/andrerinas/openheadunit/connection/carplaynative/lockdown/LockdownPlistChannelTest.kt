package com.andrerinas.openheadunit.connection.carplaynative.lockdown

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class LockdownPlistChannelTest {
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }

    private class Script(val chunks: List<ByteArray?>) : BlockingDuplexByteStream {
        var index = 0
        val sent = ArrayList<ByteArray>()
        override fun send(data: ByteArray) { sent.add(data) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
            if (index >= chunks.size) return null
            val c = chunks[index] ?: run { index++; return null }
            if (c.size <= maxBytes) { index++; return c }
            return c.copyOf(maxBytes).also { }.also { _ -> (chunks as MutableList<ByteArray?>)[index] = c.copyOfRange(maxBytes, c.size) }
        }
        override fun close() {}
    }

    private fun frame(vararg entries: Pair<String, PlistValue>) = LockdownFrame.encode(PlistValue.dict(*entries))

    @Test fun theHeaderIsAFourByteBigEndianLength() {
        val frame = frame("Request" to PlistValue.Text("GetValue"))
        val declared = ((frame[0].toInt() and 0xff) shl 24) or ((frame[1].toInt() and 0xff) shl 16) or ((frame[2].toInt() and 0xff) shl 8) or (frame[3].toInt() and 0xff)
        assertEquals(frame.size - 4, declared)
    }

    @Test fun aReplySplitAcrossReadsIsJoined() {
        val frame = frame("Value" to PlistValue.Text("x"))
        val channel = LockdownPlistChannel(Script(frame.toList().chunked(5).map { it.toByteArray() }.toMutableList()), clock)
        assertEquals("x", channel.receive(1000)["Value"]?.asText)
    }

    @Test fun aRequestSendsOneFrameAndReadsTheReply() {
        val script = Script(mutableListOf(frame("Value" to PlistValue.Text("y"))))
        val reply = LockdownPlistChannel(script, clock).request(PlistValue.dict("Request" to PlistValue.Text("GetValue")), 1000)
        assertEquals("y", reply["Value"]?.asText)
        assertEquals(1, script.sent.size)
    }

    @Test fun aLengthOfZeroOrOverTheCapIsRefused() {
        assertNull(LockdownFrame.bodyLength(byteArrayOf(0, 0, 0, 0)))
        assertNull(LockdownFrame.bodyLength(byteArrayOf(0, 0x10, 0, 1)))
        assertEquals(LockdownFrame.MAX_MESSAGE_BYTES, LockdownFrame.bodyLength(byteArrayOf(0, 0x10, 0, 0)))
        try { LockdownPlistChannel(Script(mutableListOf(byteArrayOf(0x7f, 0, 0, 0))), clock).receive(1000); fail() } catch (_: IphoneUsbException.Protocol) { }
    }

    @Test fun silenceTimesOut() {
        try { LockdownPlistChannel(Script(mutableListOf(null)), clock).receive(100); fail() } catch (_: IphoneUsbException.TimedOut) { }
    }

    @Test fun aStreamThatEndsInsideTheBodyFails() {
        val frame = frame("Value" to PlistValue.Text("x"))
        val script = Script(mutableListOf(frame.copyOf(frame.size - 3), ByteArray(0)))
        try { LockdownPlistChannel(script, clock).receive(1000); fail() } catch (_: IphoneUsbException.Protocol) { }
    }

    @Test fun aBodyThatIsNotAPlistFails() {
        val body = "nonsense".toByteArray()
        val frame = byteArrayOf(0, 0, 0, body.size.toByte()) + body
        try { LockdownPlistChannel(Script(mutableListOf(frame)), clock).receive(1000); fail() } catch (_: IphoneUsbException.Protocol) { }
    }
}
