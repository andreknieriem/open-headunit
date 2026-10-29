package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VideoTimedOutputRegressionTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `custom consumer output preserves future due decoded burst while dropping genuinely late frame`() {
        var now = 1_000_000_000L
        var waited = 0L
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { now / 1_000_000 })
        val codec = mock<MediaCodec>()
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100_000_000, 250_000_000)))
        val bridge = GalVideoOutputTiming(controller, 1, VideoSurfaceKind.TEXTURE_CONSUMER, { now }) { now += it; waited += it }
        for (i in 0..3) bridge.register(i + 1L, i * 33_333L, now)
        bridge.register(5, 133_332, 500_000_000)
        field(decoder, "codec").set(decoder, codec)
        field(decoder, "surfaceKind").set(decoder, VideoSurfaceKind.TEXTURE_CONSUMER)
        field(decoder, "codecBufferInfo").set(decoder, MediaCodec.BufferInfo())
        field(decoder, "outputTiming").set(decoder, bridge)
        field(decoder, "running").set(decoder, true)
        field(decoder, "outputThread").set(decoder, Thread.currentThread())
        field(decoder, "startTime").set(decoder, System.nanoTime())
        var next = 0
        whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer {
            val index = next++
            if (index < 5) {
                (it.arguments[0] as MediaCodec.BufferInfo).presentationTimeUs = index + 1L
                index
            } else MediaCodec.INFO_TRY_AGAIN_LATER
        }
        val releases = mutableListOf<Pair<Int, Boolean>>()
        doAnswer {
            val index = it.arguments[0] as Int
            releases.add(index to (it.arguments[1] as Boolean))
            if (index == 4) field(decoder, "running").set(decoder, false)
            null
        }.whenever(codec).releaseOutputBuffer(any(), any<Boolean>())
        val loop = VideoDecoder::class.java.getDeclaredMethod("outputThreadLoop").apply { isAccessible = true }
        try {
            loop.invoke(decoder)
            assertEquals(listOf(0 to true, 1 to true, 2 to true, 3 to true, 4 to false), releases)
            assertTrue("custom consumer worker must actually exercise the intentional wait", waited >= 100_000_000)
            assertEquals(1_199_999_000L, now)
        } finally { decoder.stop("test cleanup") }
    }

    @Test fun `narrow startup window admits one real repair output then resumes ordinary late dropping`() {
        val now = 1_000_000_000L
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 1_000L })
        val codec = mock<MediaCodec>()
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(9_000_000, 17_000_000)))
        val bridge = GalVideoOutputTiming(controller, 1, VideoSurfaceKind.TEXTURE_CONSUMER, { now }) { fail("late repair must not wait") }
        bridge.register(1, 0, now - 100_000_000)
        bridge.register(2, 33_333, now - 100_000_000)
        field(decoder, "codec").set(decoder, codec)
        field(decoder, "codecBufferInfo").set(decoder, MediaCodec.BufferInfo())
        field(decoder, "surfaceKind").set(decoder, VideoSurfaceKind.TEXTURE_CONSUMER)
        field(decoder, "outputTiming").set(decoder, bridge)
        field(decoder, "running").set(decoder, true)
        field(decoder, "outputThread").set(decoder, Thread.currentThread())
        val tracker = field(decoder, "keyframeRepair").get(decoder) as KeyframeRepairTracker
        tracker.commitKeyframe(tracker.beginKeyframe(1))
        assertFalse(tracker.keyframeDecoded)
        var firstFrames = 0
        decoder.onFirstFrameListener = { firstFrames++ }
        var next = 0
        whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer {
            val index = next++
            if (index < 2) {
                (it.arguments[0] as MediaCodec.BufferInfo).presentationTimeUs = index + 1L
                index
            } else {
                // The first empty dequeue ends batching; the next occurs after guarded dispatch.
                if (index >= 3) field(decoder, "running").set(decoder, false)
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        val releases = mutableListOf<Pair<Int, Boolean>>()
        doAnswer {
            assertFalse("input acceptance alone must not prove repair before successful release",
                releases.isEmpty() && tracker.keyframeDecoded)
            releases.add((it.arguments[0] as Int) to (it.arguments[1] as Boolean))
            null
        }.whenever(codec).releaseOutputBuffer(any(), any<Boolean>())
        val loop = VideoDecoder::class.java.getDeclaredMethod("outputThreadLoop").apply { isAccessible = true }
        try {
            loop.invoke(decoder)
            assertEquals(listOf(0 to true, 1 to false), releases)
            assertTrue(tracker.keyframeDecoded)
            assertEquals(1L, decoder.framesRenderedThisSession)
            assertEquals(1, firstFrames)
        } finally { decoder.stop("test cleanup") }
    }

    @Test fun `software retirement before event dispatch preserves first frame listener for replacement`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 10L })
        val oldOwner = Thread("software-old")
        val newOwner = Thread("software-new")
        field(decoder, "running").set(decoder, true)
        field(decoder, "feedThread").set(decoder, oldOwner)
        val collect = VideoDecoder::class.java.getDeclaredMethod("onSoftwareFramesRendered",
            Int::class.javaPrimitiveType, VideoDecoder.OutputEvents::class.java, Thread::class.java,
            Long::class.javaPrimitiveType).apply { isAccessible = true }
        var calls = 0
        val listener = { calls++; Unit }
        decoder.onFirstFrameListener = listener
        val oldEvents = VideoDecoder.OutputEvents()
        collect.invoke(decoder, 1, oldEvents, oldOwner, 0L)
        assertSame(listener, decoder.onFirstFrameListener)
        decoder.stop("restart: software test retirement")
        field(decoder, "running").set(decoder, true)
        field(decoder, "feedThread").set(decoder, newOwner)
        // Even if retirement races after the outer dispatch guard, the claim rechecks ownership.
        oldEvents.dispatch { true }
        assertEquals(0, calls)
        assertSame(listener, decoder.onFirstFrameListener)
        val newEvents = VideoDecoder.OutputEvents()
        val generation = field(decoder, "decoderGeneration").getLong(decoder)
        collect.invoke(decoder, 1, newEvents, newOwner, generation)
        newEvents.dispatch { true }
        assertEquals(1, calls)
        assertNull(decoder.onFirstFrameListener)
        decoder.stop("test cleanup")
    }
}
