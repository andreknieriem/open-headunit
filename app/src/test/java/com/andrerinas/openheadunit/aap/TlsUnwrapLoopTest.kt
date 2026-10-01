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
        val output = TlsUnwrapLoop.decode(engine, input, ByteBuffer.allocate(1)) {}
        assertFalse(input.hasRemaining())
        assertEquals(3, output.position())
        assertArrayEquals(byteArrayOf(1, 2, 3), output.array().copyOf(3))
    }

    @Test(expected = SSLException::class) fun `incomplete record cannot silently discard ciphertext`() {
        val engine = mock<SSLEngine>()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(result(SSLEngineResult.Status.BUFFER_UNDERFLOW, 0, 0))
        TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocate(8)) {}
    }

    @Test(expected = SSLException::class) fun `no progress cannot spin forever`() {
        val engine = mock<SSLEngine>()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(result(SSLEngineResult.Status.OK, 0, 0))
        TlsUnwrapLoop.decode(engine, ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocate(8)) {}
    }
}
