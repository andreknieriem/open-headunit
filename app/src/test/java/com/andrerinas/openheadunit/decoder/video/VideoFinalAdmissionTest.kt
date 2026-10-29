package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VideoFinalAdmissionTest {
    private fun field(owner: Any, name: String) = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }
    private val initial = GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100_000_000, 250_000_000))

    private fun releaseAfterBoundary(boundary: (GalPresentationController, GalVideoOutputTiming) -> Unit): List<Long> {
        val now = 1_000_000_000L
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 }, elapsedRealtime = { 1_000L })
        val codec = mock<MediaCodec>()
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1, initial)
        val timing = spy(GalVideoOutputTiming(controller, 1, VideoSurfaceKind.DIRECT_DISPLAY, { now }))
        timing.register(1, 0, now)
        // Pause at the real policy/output boundary, after obtaining a valid old deadline.
        doAnswer {
            val deadline = it.callRealMethod() as Long
            assertEquals(1_100_000_000L, deadline)
            boundary(controller, timing)
            deadline
        }.whenever(timing).deadline(1)
        field(decoder, "codec").set(decoder, codec)
        field(decoder, "codecBufferInfo").set(decoder, MediaCodec.BufferInfo())
        field(decoder, "surfaceKind").set(decoder, mock<VideoSurfaceKind> {
            on { usesPlatformTiming(any()) } doReturn true
        })
        field(decoder, "outputTiming").set(decoder, timing)
        field(decoder, "running").set(decoder, true)
        field(decoder, "outputThread").set(decoder, Thread.currentThread())
        var dequeues = 0
        whenever(codec.dequeueOutputBuffer(any(), any())).thenAnswer {
            if (dequeues++ == 0) {
                (it.arguments[0] as MediaCodec.BufferInfo).presentationTimeUs = 1
                0
            } else {
                if (dequeues > 2) field(decoder, "running").set(decoder, false)
                MediaCodec.INFO_TRY_AGAIN_LATER
            }
        }
        val releases = mutableListOf<Long>()
        doAnswer {
            releases.add(if (it.arguments[1] as Boolean) 0L else Long.MIN_VALUE)
            field(decoder, "running").set(decoder, false)
            null
        }.whenever(codec).releaseOutputBuffer(any(), any<Boolean>())
        doAnswer {
            releases.add(it.arguments[1] as Long)
            field(decoder, "running").set(decoder, false)
            null
        }.whenever(codec).releaseOutputBuffer(any(), any<Long>())
        try {
            VideoDecoder::class.java.getDeclaredMethod("outputThreadLoop").apply { isAccessible = true }.invoke(decoder)
            return releases
        } finally { decoder.stop("test cleanup") }
    }

    @Test fun `disable completed after deadline calculation submits immediately instead of stale timestamp`() {
        assertEquals(listOf(0L), releaseAfterBoundary { controller, _ ->
            controller.update(Channel.ID_VID, 1, GalRuntimeOptions())
        })
    }

    @Test fun `timing update completed after calculation replaces old future deadline`() {
        assertEquals(listOf(1_200_000_000L), releaseAfterBoundary { controller, _ ->
            controller.update(Channel.ID_VID, 1,
                GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(200_000_000, 300_000_000)))
        })
    }

    @Test fun `Stop completed after deadline calculation rejects new presentation admission`() {
        assertTrue(releaseAfterBoundary { controller, _ -> controller.stop(Channel.ID_VID, 1) }
            .all { it == Long.MIN_VALUE })
    }

    @Test fun `replacement Start completed after calculation rejects old owner`() {
        assertTrue(releaseAfterBoundary { controller, _ -> controller.start(Channel.ID_VID, 2, initial) }
            .all { it == Long.MIN_VALUE })
    }

    @Test fun `surface retirement completed after calculation rejects old owner`() {
        assertTrue(releaseAfterBoundary { _, timing -> timing.retire() }.all { it == Long.MIN_VALUE })
    }
}
