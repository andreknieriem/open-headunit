package com.andrerinas.openheadunit.aap

import android.os.Handler
import android.os.Message
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.decoder.video.FocusCycleLever
import com.andrerinas.openheadunit.decoder.video.VideoDecoder
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Exercise the transport's actual send path; only the Android queue and TLS readiness are doubled. */
class VideoFocusStartupTest {
    private class Fixture {
        val transport = mock(AapTransport::class.java, CALLS_REAL_METHODS)
        val writer = mock(AapTlsWriter::class.java)
        val handler = mock(Handler::class.java)
        val sent = mutableListOf<ByteArray>()
        var accept = true
        var beforeEnqueue: (() -> Unit)? = null
        init {
            set("lifecycleLock", Any())
            set("presentationSession", AaPresentationSession())
            set("tlsWriter", writer)
            set("aapRead", mock(AapRead::class.java))
            set("sendHandler", handler)
            `when`(writer.isReady).thenReturn(true)
            `when`(handler.obtainMessage(anyInt(), anyInt(), anyInt(), any())).thenAnswer {
                val bytes = it.getArgument<ByteArray>(3).copyOf(it.getArgument<Int>(2))
                val message = mock(Message::class.java)
                doAnswer {
                    beforeEnqueue?.invoke()
                    if (accept) sent += bytes
                    accept
                }.`when`(handler).sendMessage(message)
                message
            }
        }
        fun set(name: String, value: Any?) = AapTransport::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(transport, value)
        fun focus(gain: Boolean) = transport.send(VideoFocusEvent(gain, unsolicited = true))
        fun setup(): Boolean = transport.sendVideoSetupResponse(AapMessage(Channel.ID_VID,
            Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, Media.Config.newBuilder()
                .setStatus(Media.Config.ConfigStatus.STATUS_READY).setMaxUnacked(1).addConfigurationIndices(0).build()))
        fun types() = sent.map { ((it[4].toInt() and 255) shl 8) or (it[5].toInt() and 255) }
        fun gains() = sent.filter { (((it[4].toInt() and 255) shl 8) or (it[5].toInt() and 255)) ==
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE }.map {
            Media.VideoFocusNotification.parseFrom(it.copyOfRange(6, it.size)).mode == Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED
        }
    }

    @Test fun latestEarlyStateFollowsConfigWithoutWaitingForVideoStart() {
        val f = Fixture()
        f.focus(true)
        f.focus(false)
        assertTrue(f.sent.isEmpty())
        assertTrue(f.setup())
        assertEquals(listOf(Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE,
            Media.MsgType.MEDIA_MESSAGE_VIDEO_FOCUS_NOTIFICATION_VALUE), f.types())
        assertEquals(listOf(false), f.gains())
        f.focus(true)
        f.focus(false) // After Setup, preserve deliberate focus-cycle transitions.
        f.setup() // Repeated Setup cannot replay the old pending state.
        assertEquals(listOf(false, true, false), f.gains())
    }

    @Test fun overlayAndRetirementPreventStaleGain() {
        val f = Fixture()
        f.focus(true)
        f.transport.setProjectionVideoFocusSuppressed(true)
        f.focus(true)
        f.setup()
        assertTrue(f.gains().isEmpty())
        f.focus(false)
        assertEquals(listOf(false), f.gains())
        f.transport.setProjectionVideoFocusSuppressed(false)
        f.focus(true)
        assertEquals(listOf(false, true), f.gains())

        val retired = Fixture()
        retired.focus(true)
        retired.transport.retirePresentation()
        assertFalse(retired.setup())
        retired.focus(true)
        assertTrue(retired.sent.isEmpty())
    }

    @Test fun authAndQueueFailureCannotOpenTheGate() {
        val f = Fixture()
        `when`(f.writer.isReady).thenReturn(false)
        f.focus(true)
        assertFalse(f.setup())
        `when`(f.writer.isReady).thenReturn(true)
        f.accept = false
        f.focus(false)
        assertFalse(f.setup())
        f.focus(true)
        assertTrue(f.sent.isEmpty())
        f.accept = true
        f.setup()
        assertEquals(listOf(true), f.gains())
    }

    @Test fun actualMediaSetupOpensOnlyVideoAndPreservesFocusCyclesAfterStop() {
        val logger = AppLog.LOGGER
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        try {
            val f = Fixture()
            val settings = mock(Settings::class.java)
            `when`(settings.softwareVideoDecoder).thenReturn(Settings.SoftwareVideoDecoder.DEVICE_MEDIACODEC)
            f.set("settings", settings)
            val video = mock(AapVideo::class.java)
            `when`(video.announcedCodec).thenReturn(VideoDecoder.CodecType.H264)
            f.set("aapVideo", video)
            f.set("focusCycleLever", FocusCycleLever())
            doNothing().`when`(f.transport).gainVideoFocus()
            val media = AapControlMedia(f.transport, mock(MicRecorder::class.java), mock(AapAudio::class.java))
            assertFalse(f.transport.beginFocusCycle())
            f.transport.send(VideoFocusEvent(false, unsolicited = false))
            val request = Media.MediaSetupRequest.newBuilder().setType(1).build()
            // A setup for another sink cannot release a pending video notification.
            media.execute(AapMessage(Channel.ID_MIC, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, request))
            assertTrue(f.gains().isEmpty())
            media.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_SETUP_VALUE, request))
            assertEquals(listOf(false), f.gains())
            val pending = f.sent.last()
            assertFalse(Media.VideoFocusNotification.parseFrom(pending.copyOfRange(6, pending.size)).unsolicited)
            verify(f.transport).gainVideoFocus()
            assertTrue(f.transport.beginFocusCycle())
            f.transport.sendKeyframeCycleRelease()
            media.execute(AapMessage(Channel.ID_VID, 0x0b, Media.MsgType.MEDIA_MESSAGE_STOP_VALUE, 0, 0, byteArrayOf()))
            f.transport.endFocusCycle()
            f.focus(true)
            assertEquals(listOf(false, false, true), f.gains())
            f.transport.retirePresentation()
            assertFalse(f.transport.beginFocusCycle())
        } finally { AppLog.LOGGER = logger }
    }

    @Test fun concurrentLossCannotOvertakeConfigAndThenBeOverwrittenByOldGain() {
        val f = Fixture()
        f.focus(true)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.beforeEnqueue = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val setup = thread { f.setup() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val loss = thread { f.focus(false) }
        release.countDown()
        setup.join(5_000)
        loss.join(5_000)
        assertFalse(setup.isAlive || loss.isAlive)
        assertEquals(listOf(true, false), f.gains())
        assertEquals(Media.MsgType.MEDIA_MESSAGE_CONFIG_VALUE, f.types().first())
    }
}
