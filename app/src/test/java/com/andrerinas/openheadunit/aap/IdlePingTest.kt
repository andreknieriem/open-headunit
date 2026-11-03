package com.andrerinas.openheadunit.aap

import android.content.Context
import android.os.Handler
import android.os.Message
import android.os.SystemClock
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.connection.projection.ProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.video.VideoFaultInjector
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.UninitializedMessageException
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any
import java.util.concurrent.CountDownLatch

/** Real transport admission, scheduling and protobuf framing; no wall-clock waits or worker threads. */
class IdlePingTest {
    private class Fixture {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        val writer = mock(AapTlsWriter::class.java)
        val handler = mock(Handler::class.java)
        val poll = mock(Handler::class.java)
        val reader = mock(AapRead::class.java)
        val settings = mock(Settings::class.java)
        val frames = mutableListOf<ByteArray>()
        val delayed = mutableListOf<Pair<Runnable, Long>>()
        var now = 10_000L
        var acceptMessages = true
        private val tickMethod = AapTransport::class.java.getDeclaredMethod("sendKeepalive")
            .apply { isAccessible = true }
        // CALLS_REAL_METHODS skips initializers, so retain the production callback's exact target.
        val tick = Runnable { tickMethod.invoke(transport) }

        init {
            set("lifecycleLock", Any())
            set("presentationSession", AaPresentationSession())
            set("presentationController", GalPresentationController())
            set("tlsWriter", writer)
            set("sendHandler", handler)
            set("pollHandler", poll)
            set("aapRead", reader)
            set("connection", mock(SocketProjectionConnection::class.java))
            set("keepaliveRunnable", tick)
            set("handshakeStarted", true)
            set("handshakeFinished", CountDownLatch(0))
            set("linkGapMonitor", LinkGapMonitor())
            set("inboundRateMonitor", InboundRateMonitor())
            set("videoGapMonitor", LinkGapMonitor())
            set("audioGapMonitor", LinkGapMonitor())
            set("settings", settings)
            set("context", mock(Context::class.java))
            set("micRecorder", mock(MicRecorder::class.java))
            set("aapAudio", mock(AapAudio::class.java))
            set("aapVideo", mock(AapVideo::class.java))
            set("ssl", mock(AapSsl::class.java))
            `when`(settings.debugVideoFaultInjection).thenReturn(VideoFaultInjector.Mode.OFF)
            `when`(writer.isReady).thenReturn(true)
            `when`(handler.postDelayed(any<Runnable>(), anyLong())).thenAnswer {
                delayed += it.getArgument<Runnable>(0) to it.getArgument<Long>(1)
                true
            }
            doAnswer {
                val callback = it.getArgument<Runnable>(0)
                delayed.removeAll { queued -> queued.first === callback }
                null
            }.`when`(handler).removeCallbacks(any<Runnable>())
            `when`(handler.obtainMessage(anyInt(), anyInt(), anyInt(), any())).thenAnswer {
                val bytes = it.getArgument<ByteArray>(3).copyOf(it.getArgument<Int>(2))
                val message = mock(Message::class.java)
                doAnswer {
                    if (acceptMessages) frames += bytes
                    acceptMessages
                }.`when`(handler).sendMessage(message)
                message
            }
        }

        fun set(name: String, value: Any?) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)

        fun receive(channel: Int = Channel.ID_CTR) = transport.noteMessageReceived(channel, 8)
        fun fireNext() {
            val (callback, delay) = delayed.removeAt(0)
            assertSame(tick, callback)
            assertEquals(1_000L, delay)
            callback.run()
        }
        fun ping(index: Int = frames.lastIndex): Control.PingRequest {
            val frame = frames[index]
            assertEquals(Channel.ID_CTR, frame[0].toInt() and 255)
            assertEquals(Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE,
                ((frame[4].toInt() and 255) shl 8) or (frame[5].toInt() and 255))
            assertEquals(frame.size - 4,
                ((frame[2].toInt() and 255) shl 8) or (frame[3].toInt() and 255))
            return Control.PingRequest.parseFrom(frame.copyOfRange(6, frame.size))
        }
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            mockStatic(SystemClock::class.java).use { clock ->
                val f = Fixture()
                clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.now }
                test(f)
            }
        } finally {
            AppLog.LOGGER = logger
        }
    }

    @Test fun periodicPingUsesStandardFrameWithoutRefreshingInboundHealth() = withFixture { f ->
        f.receive()
        f.now = 11_000
        f.tick.run()
        assertEquals(1, f.frames.size)
        assertEquals(11_000_000_000L, f.ping().timestamp)
        assertEquals(10_000L, f.transport.lastMessageReceivedMs)
        f.now = 12_000
        f.fireNext()
        assertEquals(2, f.frames.size)
        assertEquals(12_000_000_000L, f.ping().timestamp)
        assertEquals(10_000L, f.transport.lastMessageReceivedMs)
        assertEquals(1, f.delayed.size)
        // Publishing a ping must not wrap/write TLS while holding the lifecycle lock.
        verify(f.writer, atLeastOnce()).isReady
        verifyNoMoreInteractions(f.writer)
    }

    @Test fun activeVideoAndAudioCannotSuppressPeriodicKeepalive() = withFixture { f ->
        f.receive()
        f.tick.run()
        assertEquals(1, f.frames.size)
        f.now = 10_999
        f.receive(Channel.ID_VID)
        f.now = 11_000
        f.fireNext()
        assertEquals(2, f.frames.size)
        assertEquals(11_000_000_000L, f.ping().timestamp)
        assertEquals(10_999L, f.transport.lastMessageReceivedMs)
        assertEquals(1, f.delayed.size)
        f.now = 11_999
        f.receive(Channel.ID_AUD)
        f.now = 12_000
        f.fireNext()
        assertEquals(3, f.frames.size)
        assertEquals(12_000_000_000L, f.ping().timestamp)
        assertEquals(11_999L, f.transport.lastMessageReceivedMs)
        assertEquals(1, f.delayed.size)
    }

    @Test fun failedQueuePublicationCannotPretendThePhoneReplied() = withFixture { f ->
        f.receive()
        f.now += 1_000
        f.acceptMessages = false
        f.tick.run()
        assertTrue(f.frames.isEmpty())
        assertEquals(10_000L, f.transport.lastMessageReceivedMs)
        f.acceptMessages = true
        f.now += 1_000
        f.fireNext()
        assertEquals(1, f.frames.size)
        assertEquals(10_000L, f.transport.lastMessageReceivedMs)
    }

    @Test fun retiringPresentationCancelsTimerAndAlreadyDequeuedCallbackCannotRearm() = withFixture { f ->
        f.receive()
        f.tick.run()
        val alreadyDequeued = f.delayed.single().first
        f.transport.retirePresentation()
        assertTrue(f.delayed.isEmpty())
        verify(f.reader).retirePresentation()
        f.now += 20_000
        alreadyDequeued.run()
        assertEquals(1, f.frames.size)
        assertTrue(f.delayed.isEmpty())
    }

    @Test fun ineligibleOrDetachedTransportCannotSendOrRearmStaleCallback() {
        for (state in listOf("closing", "reader", "auth", "usb", "handler")) {
            withFixture { f ->
                when (state) {
                    "closing" -> f.set("closing", true)
                    "reader" -> f.set("aapRead", null)
                    "auth" -> `when`(f.writer.isReady).thenReturn(false)
                    "usb" -> f.set("connection", mock(ProjectionConnection::class.java).also {
                        `when`(it.type).thenReturn(ProjectionConnection.Type.USB)
                    })
                    "handler" -> f.set("sendHandler", null)
                }
                f.tick.run()
                assertTrue(state, f.frames.isEmpty())
                assertTrue(state, f.delayed.isEmpty())
            }
        }
    }

    @Test fun readerStartupArmsOnlyAuthenticatedSocketAndNeverDuplicatesTimer() {
        // Factory and startReading remain real. Codec/reader constructors are not part of this timer test.
        mockConstruction(AapMessageHandlerType::class.java).use {
            mockConstruction(AapReadSingleMessage::class.java).use {
                mockConstruction(AapReadMultipleMessages::class.java).use {
                    for (state in listOf("socket", "notStarted", "handshaking", "unauthenticated", "usb", "retired")) {
                        withFixture { f ->
                            f.set("aapRead", null)
                            when (state) {
                                "notStarted" -> f.set("handshakeStarted", false)
                                "handshaking" -> f.set("handshakeFinished", CountDownLatch(1))
                                "unauthenticated" -> `when`(f.writer.isReady).thenReturn(false)
                                "usb" -> f.set("connection", mock(ProjectionConnection::class.java).also {
                                    `when`(it.type).thenReturn(ProjectionConnection.Type.USB)
                                })
                                "retired" -> f.transport.retirePresentation()
                            }
                            f.transport.startReading()
                            f.transport.startReading()
                            assertEquals(state, if (state == "socket") 1 else 0, f.delayed.size)
                            assertTrue(f.frames.isEmpty())
                            if (state == "socket") {
                                verify(f.poll, times(1)).sendEmptyMessage(anyInt())
                                f.fireNext()
                                assertEquals(1, f.frames.size)
                                assertEquals(10_000_000_000L, f.ping().timestamp)
                                assertEquals(1, f.delayed.size)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun validPongEndsExchangeAndMalformedPongIsRejectedWithoutReply() = withFixture { f ->
        val control = AapControlService(f.transport, mock(AapAudio::class.java), f.settings,
            mock(Context::class.java))
        f.receive()
        val pong = Control.PingResponse.newBuilder().setTimestamp(123_456_789L).build()
        assertEquals(0, control.execute(AapMessage(Channel.ID_CTR,
            Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, pong)))
        assertTrue(f.frames.isEmpty())
        assertEquals(10_000L, f.transport.lastMessageReceivedMs)
        for (payload in listOf(byteArrayOf(8, -128), byteArrayOf())) {
            try {
                control.execute(AapMessage(Channel.ID_CTR, 0x0b,
                    Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE, 0, payload.size, payload))
                fail("Malformed/missing required ping timestamp was accepted")
            } catch (error: Exception) {
                assertTrue(error.toString(), error is InvalidProtocolBufferException ||
                    error is UninitializedMessageException)
            }
        }
        assertTrue(f.frames.isEmpty())
        // Preserve the opposite direction: a phone ping still receives exactly one echo.
        val request = Control.PingRequest.newBuilder().setTimestamp(987_654_321L).build()
        assertEquals(0, control.execute(AapMessage(Channel.ID_CTR,
            Control.ControlMsgType.MESSAGE_PING_REQUEST_VALUE, request)))
        val response = f.frames.single()
        assertEquals(Control.ControlMsgType.MESSAGE_PING_RESPONSE_VALUE,
            ((response[4].toInt() and 255) shl 8) or (response[5].toInt() and 255))
        assertEquals(request.timestamp,
            Control.PingResponse.parseFrom(response.copyOfRange(6, response.size)).timestamp)
    }
}
