package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import android.os.Handler
import com.andrerinas.openheadunit.aap.AapAudio
import com.andrerinas.openheadunit.aap.AapControlMedia
import com.andrerinas.openheadunit.aap.AapMessage
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.aap.AapVideo
import com.andrerinas.openheadunit.aap.GalMediaFormat
import com.andrerinas.openheadunit.aap.GalMediaOffer
import com.andrerinas.openheadunit.aap.GalMediaSessions
import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.galTestContract
import com.andrerinas.openheadunit.aap.galTestSessions
import com.andrerinas.openheadunit.aap.protocol.proto.Control
import com.andrerinas.openheadunit.aap.protocol.proto.Media
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VideoPresentationStartTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    /** Real Start, decoder promotion and final output admission; only the platform queue/codec
     * are doubled. Seed the state left by pre-Start CSD without starting native codec threads. */
    private inner class StartBoundary(
        private val immediateWorker: Boolean,
        val sessions: GalMediaSessions = galTestSessions(),
    ) {
        val controller = GalPresentationController()
        private val settings = mock<Settings> {
            on { fpsLimit } doReturn 30
            on { debugVideoFaultInjection } doReturn VideoFaultInjector.Mode.OFF
        }
        val decoder = VideoDecoder(settings)
        val codec = mock<MediaCodec>()
        var now = 1_000_000_000L
        val bridge = GalVideoOutputTiming(null, 0, VideoSurfaceKind.DIRECT_DISPLAY, { now }) {
            fail("platform-timed output should not sleep")
        }
        val transport = mock<AapTransport>(defaultAnswer = org.mockito.Mockito.CALLS_REAL_METHODS)
        val video = AapVideo(decoder, settings) { fail("Start requested frame recovery") }
        val handler = mock<Handler>()
        val jobs = mutableListOf<Runnable>()
        val audio = mock<AapAudio>()
        private val control = AapControlMedia(transport, mock(), audio)
        val cache = field(decoder, "parameterSets").get(decoder) as ParameterSetCache
        val stored = cache.store(byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x80.toByte()), 0, 5, false, 7)

        init {
            field(decoder, "codec").set(decoder, codec)
            field(decoder, "outputSize").set(decoder, VideoSize(1280, 720))
            field(decoder, "configuredWidth").set(decoder, 1280)
            field(decoder, "configuredHeight").set(decoder, 720)
            field(decoder, "outputTiming").set(decoder, bridge)
            field(video, "preparingSession").set(video, true)
            video.bindPresentationController(controller)
            for ((name, value) in listOf(
                "aapVideo" to video, "aapAudio" to audio, "mediaSessions" to sessions,
                "presentationController" to controller, "videoHandler" to handler,
            )) field(transport, name).set(transport, value)
            whenever(handler.post(any())).thenAnswer {
                val work = it.getArgument<Runnable>(0)
                // Execute before Handler.post returns: the reader cannot finish Start first.
                if (immediateWorker) work.run() else jobs.add(work)
                true
            }
            doNothing().whenever(transport).quit()
        }

        fun start(timed: Boolean, index: Int = 0): Int {
            val request = Media.Start.newBuilder().setSessionId(41).setConfigurationIndex(index)
            if (timed) request.options = Media.MediaOptions.newBuilder()
                .setEnableVideoTimingController(true)
                .setVideoTimingControllerMinDelay(Media.MediaDuration.newBuilder().setNanos(10_000_000))
                .setVideoTimingControllerMaxDelay(Media.MediaDuration.newBuilder().setNanos(50_000_000))
                .build()
            return control.execute(AapMessage(Channel.ID_VID, Media.MsgType.MEDIA_MESSAGE_START_VALUE, request.build()))
        }

        fun drain() {
            while (jobs.isNotEmpty()) jobs.removeAt(0).run()
        }

        fun assertOutput(timed: Boolean) {
            repeat(3) { frame ->
                val pts = frame.toLong()
                bridge.register(pts, pts * 33_000, now)
                val expected = if (timed) now + 10_000_000 else 0L
                assertEquals(expected, bridge.deadline(pts))
                // deadline alone can return immediate even for an invalid output epoch.
                // The actual MediaCodec/FFmpeg submission gate must admit every frame too.
                assertEquals("final output admission for frame $frame", expected, bridge.admit(platformTiming = true))
                bridge.releasing(pts, expected, true)
                now += 33_000_000
            }
            assertSame(codec, field(decoder, "codec").get(decoder))
            assertSame(bridge, field(decoder, "outputTiming").get(decoder))
            assertSame(stored, cache.latest(7))
            verify(codec, never()).stop()
            verify(codec, never()).release()
            verify(transport, never()).quit()
        }
    }

    @Test fun `Start admits precreated output when video worker runs before publication returns`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        for (timed in listOf(false, true)) {
            val boundary = StartBoundary(immediateWorker = true)
            assertEquals(0, boundary.start(timed))
            boundary.assertOutput(timed)
        }
    }

    @Test fun `Start admits precreated output when video worker runs after reader completes`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        for (timed in listOf(false, true)) {
            val boundary = StartBoundary(immediateWorker = false)
            assertEquals(0, boundary.start(timed))
            boundary.drain()
            boundary.assertOutput(timed)
        }
    }

    @Test fun `promoted old output cannot reacquire ownership from replacement output`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val boundary = StartBoundary(immediateWorker = false)
        assertEquals(0, boundary.start(timed = false))
        boundary.drain()
        boundary.assertOutput(timed = false)
        val generation = boundary.sessions.current(Channel.ID_VID)!!.generation
        val replacement = GalVideoOutputTiming(boundary.controller, generation, nanoTime = { boundary.now })
        repeat(3) {
            assertEquals(Long.MIN_VALUE, boundary.bridge.admit(platformTiming = true))
            assertEquals(0L, replacement.admit(platformTiming = true))
        }
        boundary.bridge.retire()
        assertEquals(0L, replacement.admit(platformTiming = true))
    }

    @Test fun `invalid Start format cannot publish a video owner`() =
        org.mockito.Mockito.mockStatic(android.os.SystemClock::class.java).use {
        val invalid = mock<Control.Service.MediaSinkService.VideoConfiguration> {
            on { codecResolution } doReturn Control.Service.MediaSinkService.VideoConfiguration.VideoCodecResolutionType._1280x720
            // A missing/unsupported frame-rate value must fail the transport's format gate.
        }
        val offer = mock<GalMediaOffer> {
            on { format(Channel.ID_VID, 0) } doReturn GalMediaFormat.Video(invalid)
        }
        val sessions = GalMediaSessions().apply { advertise(galTestContract().media, offer) }
        val boundary = StartBoundary(immediateWorker = true, sessions = sessions)
        assertEquals(-1, boundary.start(timed = false))
        val generation = sessions.current(Channel.ID_VID)!!.generation
        assertFalse(boundary.controller.isVideoSessionActive(generation))
        assertEquals(0L, boundary.controller.beginVideoOutput(generation))
        assertEquals(Long.MIN_VALUE, boundary.controller.admitVideo(generation, 0, null, Long.MIN_VALUE, boundary.now))
        verify(boundary.handler, never()).post(any())
        verify(boundary.transport).quit()
        assertSame(boundary.codec, field(boundary.decoder, "codec").get(boundary.decoder))
        assertSame(boundary.stored, boundary.cache.latest(7))
    }

    @Test fun `initial Start promotes captured prestart codec bridge without replacing codec or CSD`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 })
        val codec = mock<MediaCodec>()
        val worker = Thread("prestart-output-owner")
        val feed = Thread("prestart-feed-owner")
        var now = 1_000L
        val bridge = GalVideoOutputTiming(null, 0, VideoSurfaceKind.DIRECT_DISPLAY, { now }) { fail("timed codec output should not sleep") }
        field(decoder, "codec").set(decoder, codec)
        field(decoder, "outputThread").set(decoder, worker)
        field(decoder, "feedThread").set(decoder, feed)
        field(decoder, "outputTiming").set(decoder, bridge)
        field(decoder, "running").set(decoder, true)
        val cache = field(decoder, "parameterSets").get(decoder) as ParameterSetCache
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x80.toByte())
        val stored = cache.store(sps, 0, sps.size, false, 7)
        assertNotNull(stored)
        assertEquals(0L, bridge.deadline(1))
        assertNull(field(bridge, "metadata").get(bridge))

        val controller = mock<GalPresentationController>()
        whenever(controller.isVideoSessionActive(12)).thenReturn(true)
        whenever(controller.isVideoTimingEnabled(12)).thenReturn(true)
        whenever(controller.beginVideoOutput(12)).thenReturn(1L)
        whenever(controller.admitVideo(12, 1, 500, 900, 1_000)).thenReturn(1_100L)
        // Workers, the API23 listener and FFmpeg gate all retain this same instance.
        val capturedCallback = bridge::onFrameRendered
        decoder.bindPresentationController(controller)
        decoder.beginPresentationSession(12)
        assertSame(codec, field(decoder, "codec").get(decoder))
        assertSame(worker, field(decoder, "outputThread").get(decoder))
        assertSame(feed, field(decoder, "feedThread").get(decoder))
        assertSame(bridge, field(decoder, "outputTiming").get(decoder))
        assertSame(stored, cache.latest(7))
        verify(codec, never()).stop()
        verify(codec, never()).release()
        bridge.register(2, 500, 900)
        assertEquals(1_100L, bridge.deadline(2))
        bridge.releasing(2, 1_100, true)
        now = 1_105L
        capturedCallback(2, 1_105)
        verify(controller).observeVideoOutput(12, 1, 500, 1_105)
    }

    @Test fun `initial narrowband FPS selection rebuild preserves prestart CSD for slice-only DATA`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 60 })
        val codec = mock<MediaCodec>()
        field(decoder, "codec").set(decoder, codec)
        field(decoder, "outputSize").set(decoder, VideoSize(800, 480))
        field(decoder, "configuredWidth").set(decoder, 800)
        field(decoder, "configuredHeight").set(decoder, 480)
        field(decoder, "codecTypePinned").set(decoder, true)
        val cache = field(decoder, "parameterSets").get(decoder) as ParameterSetCache
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0x80.toByte())
        val pps = byteArrayOf(0x68, 0xC0.toByte())
        cache.store(sps, 0, sps.size, false, 7)
        cache.store(pps, 0, pps.size, false, 8)
        val csd0 = cache.codecSpecificData(false, 0)
        val csd1 = cache.codecSpecificData(false, 1)
        assertNotNull(csd0)
        assertNotNull(csd1)
        assertTrue(decoder.selectFormat(800, 480, 30))
        decoder.beginPresentationSession(1)
        verify(codec).stop()
        verify(codec).release()
        assertEquals(30, field(decoder, "selectedFps").get(decoder))
        assertEquals(true, field(decoder, "codecTypePinned").get(decoder))
        // These are precisely the inputs buildFormat supplies to the next codec; DATA need
        // not repeat either parameter set after the selected-rate reconfiguration.
        assertSame(csd0, cache.codecSpecificData(false, 0))
        assertSame(csd1, cache.codecSpecificData(false, 1))
    }

    @Test fun `disabled frame path allocates no rings and does not acquire bridge monitor`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 4, GalRuntimeOptions())
        val bridge = GalVideoOutputTiming(controller, 4, nanoTime = { 1_000L }) { fail("disabled output waited") }
        val finished = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val worker = Thread {
            try {
                repeat(20) {
                    bridge.register(it.toLong(), it.toLong(), 900)
                    assertEquals(0L, bridge.deadline(it.toLong()))
                    bridge.releasing(it.toLong(), 0, true)
                    bridge.onFrameRendered(it.toLong(), 1_005)
                }
            } catch (t: Throwable) { failure.set(t) }
            finally { finished.countDown() }
        }
        synchronized(bridge) {
            worker.start()
            assertTrue("disabled frame path blocked on bridge monitor", finished.await(2, TimeUnit.SECONDS))
        }
        worker.join(2_000)
        failure.get()?.let { throw AssertionError(it) }
        assertNull(field(bridge, "metadata").get(bridge))
    }
}
