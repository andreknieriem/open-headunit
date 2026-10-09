package com.andrerinas.openheadunit.aap

import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.nio.ByteBuffer
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus.*
import javax.net.ssl.SSLEngineResult.Status.*
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSession

class TlsApplicationPumpTest {
    private fun engine(): SSLEngine {
        val engine = mock<SSLEngine>()
        val session = mock<SSLSession>()
        whenever(session.packetBufferSize).thenReturn(32)
        whenever(engine.session).thenReturn(session)
        whenever(engine.handshakeStatus).thenReturn(NOT_HANDSHAKING)
        return engine
    }

    @Test fun wrapKeepsInputAfterControlOnlyOutput() {
        val engine = engine()
        var call = 0
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            val src = it.getArgument<ByteBuffer>(0)
            val dst = it.getArgument<ByteBuffer>(1)
            if (call++ == 0) {
                dst.put(99.toByte())
                SSLEngineResult(OK, NEED_WRAP, 0, 1)
            } else {
                val value = src.get()
                dst.put(value)
                SSLEngineResult(OK, NOT_HANDSHAKING, 1, 1)
            }
        }
        assertArrayEquals(byteArrayOf(99, 10, 20), TlsApplicationPump(engine).wrap(ByteBuffer.wrap(byteArrayOf(10, 20))))
    }

    @Test fun unwrapDrainsKeyUpdateResponseBeforeContinuingApplicationRecords() {
        val engine = engine()
        var status = NOT_HANDSHAKING
        whenever(engine.handshakeStatus).thenAnswer { status }
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            val src = it.getArgument<ByteBuffer>(0)
            val dst = it.getArgument<ByteBuffer>(1)
            val value = src.get()
            if (value == 99.toByte()) {
                status = NEED_WRAP
                SSLEngineResult(OK, status, 1, 0)
            } else {
                dst.put(value)
                SSLEngineResult(OK, status, 1, 1)
            }
        }
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            assertEquals(0, it.getArgument<ByteBuffer>(0).remaining())
            it.getArgument<ByteBuffer>(1).put(42.toByte())
            status = NOT_HANDSHAKING
            SSLEngineResult(OK, status, 0, 1)
        }
        val destination = ByteBuffer.allocate(4)
        val controls = mutableListOf<ByteArray>()
        TlsApplicationPump(engine).unwrap(ByteBuffer.wrap(byteArrayOf(99, 7, 8)), destination) { controls.add(it) }
        assertEquals(1, controls.size)
        assertArrayEquals(byteArrayOf(42), controls.single())
        assertEquals(2, destination.position())
        assertArrayEquals(byteArrayOf(7, 8, 0, 0), destination.array())
    }

    @Test fun delegatedTaskCanRequestControlOutput() {
        val engine = engine()
        var status = NEED_TASK
        whenever(engine.handshakeStatus).thenAnswer { status }
        whenever(engine.delegatedTask).thenReturn(Runnable { status = NEED_WRAP })
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            it.getArgument<ByteBuffer>(1).put(1.toByte())
            status = NOT_HANDSHAKING
            SSLEngineResult(OK, status, 0, 1)
        }
        val controls = mutableListOf<ByteArray>()
        TlsApplicationPump(engine).unwrap(ByteBuffer.allocate(0), ByteBuffer.allocate(1)) { controls.add(it) }
        assertEquals(1, controls.size)
    }

    @Test(expected = SSLException::class) fun stuckWrapDoesNotDropInputOrSpin() {
        val engine = engine()
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(SSLEngineResult(OK, NOT_HANDSHAKING, 0, 0))
        TlsApplicationPump(engine).wrap(ByteBuffer.wrap(byteArrayOf(1)))
    }

    @Test(expected = SSLException::class) fun incompleteRecordFailsBeforeDispatch() {
        val engine = engine()
        whenever(engine.unwrap(any<ByteBuffer>(), any<ByteBuffer>())).thenReturn(SSLEngineResult(BUFFER_UNDERFLOW, NOT_HANDSHAKING, 0, 0))
        TlsApplicationPump(engine).unwrap(ByteBuffer.wrap(byteArrayOf(1)), ByteBuffer.allocate(1)) { fail() }
    }

    @Test(expected = SSLException::class) fun refusesApplicationWriteWaitingForPeerInput() {
        val engine = engine()
        whenever(engine.handshakeStatus).thenReturn(NEED_UNWRAP)
        TlsApplicationPump(engine).wrap(ByteBuffer.wrap(byteArrayOf(1)))
    }

    @Test(expected = SSLException::class) fun endlessControlOutputHasBoundedWork() {
        val engine = engine()
        whenever(engine.handshakeStatus).thenReturn(NEED_WRAP)
        whenever(engine.wrap(any<ByteBuffer>(), any<ByteBuffer>())).thenAnswer {
            it.getArgument<ByteBuffer>(1).put(1.toByte())
            SSLEngineResult(OK, NEED_WRAP, 0, 1)
        }
        TlsApplicationPump(engine).unwrap(ByteBuffer.allocate(0), ByteBuffer.allocate(1)) {}
    }
}
