package com.andrerinas.openheadunit.aap

import android.os.SystemClock
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.*

class MicrophoneWriteFailureTest {
    @Test fun `microphone short write retires the transport and waiting capture does not`() {
        mockStatic(SystemClock::class.java).use {
            for (ready in listOf(false, true)) {
                val transport = mock<AapTransport>(defaultAnswer = CALLS_REAL_METHODS)
                val ssl = mock<AapSsl>()
                whenever(ssl.drainControlRecords()).thenReturn(emptyList())
                whenever(ssl.encrypt(any(), any(), any())).thenAnswer {
                    val data = it.getArgument<ByteArray>(2)
                    ByteArrayWithLimit(data.copyOf(), data.size)
                }
                var calls = 0
                val writer = AapTlsWriter(ssl) { _, length -> calls++; length - 1 }
                AapTransport::class.java.getDeclaredField("tlsWriter").apply { isAccessible = true }.set(transport, writer)
                // Keep the production DATA framing and failure dispatch, replacing Android teardown.
                doAnswer { writer.retire(); null }.whenever(transport).quit(false)
                if (ready) assertTrue(writer.activate())
                transport.sendMicrophoneData(ByteArray(320), 1234L)
                transport.sendMicrophoneData(ByteArray(320), 5678L)
                assertEquals(if (ready) 1 else 0, calls)
                verify(transport, times(if (ready) 1 else 0)).quit(false)
                assertFalse(writer.isReady)
            }
        }
    }
}
