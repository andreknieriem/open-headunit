package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.Message
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.doAnswer
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [33])
class AapTransportKeySendTest {
    private class Wire {
        val transport: AapTransport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        val writer: AapTlsWriter = mock(AapTlsWriter::class.java)
        val handler: Handler = mock(Handler::class.java)
        val sent = mutableListOf<ByteArray>()
        val context: android.content.Context = mock(android.content.Context::class.java)
        val broadcasts = mutableListOf<String?>()

        init {
            set("lifecycleLock", Any())
            set("presentationSession", AaPresentationSession())
            set("tlsWriter", writer)
            set("aapRead", mock(AapRead::class.java))
            set("sendHandler", handler)
            set("context", context)
            `when`(context.packageName).thenReturn("test.pkg")
            doAnswer { broadcasts += (it.getArgument<android.content.Intent>(0)).action; null }
                .`when`(context).sendBroadcast(any())
            `when`(writer.isReady).thenReturn(true)
            `when`(handler.obtainMessage(anyInt(), anyInt(), anyInt(), any())).thenAnswer {
                val bytes = it.getArgument<ByteArray>(3).copyOf(it.getArgument<Int>(2))
                val message = mock(Message::class.java)
                doAnswer { sent += bytes; true }.`when`(handler).sendMessage(message)
                message
            }
        }

        fun set(name: String, value: Any?) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
    }

    @Test fun unknownCodeSendsNothing() {
        val w = Wire()
        w.transport.send(289, true)
        assertEquals(0, w.sent.size)
    }

    @Test fun rawFytCodeSendsNothing() {
        val w = Wire()
        w.transport.send(547, true)
        assertEquals(0, w.sent.size)
    }

    @Test fun supportedKeySendsOneMessage() {
        val w = Wire()
        w.transport.send(KeyEvent.KEYCODE_MEDIA_NEXT, true)
        assertEquals(1, w.sent.size)
    }

    @Test fun volumeMuteSendsOneMessage() {
        val w = Wire()
        w.transport.send(KeyEvent.KEYCODE_VOLUME_MUTE, true)
        assertEquals(1, w.sent.size)
    }

    @Test fun nightKeyRaisesItsBroadcastAndSendsNothing() {
        val w = Wire()
        w.transport.send(com.andrerinas.openheadunit.input.KeyCode.KEY_NIGHT_MODE, true)
        assertEquals(0, w.sent.size)
        assertEquals(listOf<String?>(AapService.ACTION_REQUEST_NIGHT_MODE_UPDATE), w.broadcasts)
    }
}
