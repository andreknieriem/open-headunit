package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException

class TlsUnwrapLoopTest {
    private fun result(status: SSLEngineResult.Status, consumed: Int, produced: Int) =
        SSLEngineResult(status, SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, consumed, produced)

    @Test fun `all records including empty records are consumed and overflow preserves earlier output`() {
        val engine = mock<SSLEngine>()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            val input = it.arguments[0] as ByteBuffer
            val output = it.arguments[1] as ByteBuffer
            if (!output.hasRemaining()) result(SSLEngineResult.Status.BUFFER_OVERFLOW, 0, 0)
            else {
                val byte = input.get()
                if (byte != 0.toByte()) output.put(byte)
                result(SSLEngineResult.Status.OK, 1, if (byte == 0.toByte()) 0 else 1)
            }
        }
        val input = ByteBuffer.wrap(byteArrayOf(1, 0, 2, 3))
        val output = TlsUnwrapLoop.decode(engine, input, ByteBuffer.allocate(1)) { false }
        assertFalse(input.hasRemaining())
        assertEquals(3, output.position())
        assertArrayEquals(byteArrayOf(1, 2, 3), output.array().copyOf(3))
    }

    @Test(expected = SSLException::class) fun `incomplete record cannot silently discard ciphertext`() {
        val engine = mock<SSLEngine>()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0))
        TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocate(8)) { false }
    }

    @Test(expected = SSLException::class) fun `no progress cannot spin forever`() {
        val engine = mock<SSLEngine>()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(result(SSLEngineResult.Status.OK, 0, 0))
        TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocate(8)) { false }
    }
    @Test fun `completed task permits another unwrap without accepting genuine no progress`() {
        val engine = mock<SSLEngine>()
        var calls = 0
        whenever(engine.handshakeStatus).thenReturn(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING)
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            if (++calls == 1) SSLEngineResult(SSLEngineResult.Status.OK,
                SSLEngineResult.HandshakeStatus.NEED_TASK, 0, 0)
            else {
                (it.arguments[1] as ByteBuffer).put((it.arguments[0] as ByteBuffer).get())
                result(SSLEngineResult.Status.OK, 1, 1)
            }
        }
        val output = TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(7)), ByteBuffer.allocate(8)) {
            it.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK
        }
        assertEquals(2, calls)
        assertEquals(7.toByte(), output.get(0))
    }

    @Test(expected = SSLException::class) fun `task requiring wrap cannot spin in the unwrap driver`() {
        val engine = mock<SSLEngine>()
        whenever(engine.handshakeStatus).thenReturn(SSLEngineResult.HandshakeStatus.NEED_WRAP)
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(
            SSLEngineResult(SSLEngineResult.Status.OK, SSLEngineResult.HandshakeStatus.NEED_TASK, 0, 0))
        TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(7)), ByteBuffer.allocate(8)) { true }
    }

}
