package com.andrerinas.openheadunit.decoder.video

import android.media.MediaCodec
import android.view.Surface
import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class VideoSurfaceKindTest {
    @Test fun `texture and unknown consumers always require worker pacing even on API35`() {
        for (kind in listOf(VideoSurfaceKind.TEXTURE_CONSUMER, VideoSurfaceKind.UNKNOWN)) {
            for (sdk in listOf(16, 20, 21, 23, 35)) {
                assertFalse(kind.usesPlatformTiming(sdk))
                assertFalse(kind.supportsRenderedFeedback(sdk))
            }
        }
        assertFalse(VideoSurfaceKind.DIRECT_DISPLAY.usesPlatformTiming(20))
        assertTrue(VideoSurfaceKind.DIRECT_DISPLAY.usesPlatformTiming(21))
        assertFalse(VideoSurfaceKind.DIRECT_DISPLAY.supportsRenderedFeedback(22))
        assertTrue(VideoSurfaceKind.DIRECT_DISPLAY.supportsRenderedFeedback(23))
        assertTrue(VideoSurfaceKind.DIRECT_DISPLAY.supportsRenderedFeedback(35))
    }

    @Test fun `plausible assigned texture timestamp is never accepted as display feedback`() {
        val controller = mock<GalPresentationController>()
        whenever(controller.isVideoSessionActive(1)).thenReturn(true)
        whenever(controller.isVideoTimingEnabled(1)).thenReturn(true)
        whenever(controller.beginVideoOutput(1)).thenReturn(1L)
        var now = 1_000_000_000L
        val bridge = GalVideoOutputTiming(controller, 1, VideoSurfaceKind.TEXTURE_CONSUMER, { now }) {}
        bridge.register(12, 42, now - 1_000_000)
        bridge.deadline(12)
        bridge.releasing(12, 0, true)
        now += 1_000_000
        bridge.onFrameRendered(12, now)
        verify(controller, never()).observeVideoOutput(any(), any(), any(), any())
        val metadata = bridge.javaClass.getDeclaredField("metadata").apply { isAccessible = true }.get(bridge)
        assertNull(metadata.javaClass.getDeclaredField("rendered").apply { isAccessible = true }.get(metadata))
    }

    @Test fun `surface consumer kind belongs to target and changing kind retires captured bridge`() {
        val decoder = VideoDecoder(mock<Settings> { on { fpsLimit } doReturn 30 })
        val surface = mock<Surface>()
        val codec = mock<MediaCodec>()
        decoder.setSurface(surface, VideoSurfaceKind.DIRECT_DISPLAY)
        val bridge = GalVideoOutputTiming(null, 0, VideoSurfaceKind.DIRECT_DISPLAY)
        VideoDecoder::class.java.getDeclaredField("codec").apply { isAccessible = true }.set(decoder, codec)
        VideoDecoder::class.java.getDeclaredField("outputTiming").apply { isAccessible = true }.set(decoder, bridge)
        decoder.setSurface(surface, VideoSurfaceKind.TEXTURE_CONSUMER)
        assertEquals(VideoSurfaceKind.TEXTURE_CONSUMER, decoder.currentSurfaceKind)
        verify(codec).stop()
        verify(codec).release()
        assertEquals(Long.MIN_VALUE, bridge.deadline(12))
        decoder.stop("test cleanup")
    }
}
