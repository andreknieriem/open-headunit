package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class GalVideoOutputTimingTest {
    @Test fun `codec local keys preserve source cadence inside arrival window`() {
        val controller = GalPresentationController()
        val options = GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(10_000_000, 50_000_000))
        controller.start(Channel.ID_VID, 7, options)
        var now = 1_000_000_000L
        val bridge = GalVideoOutputTiming(controller, 7, nanoTime = { now }) { now += it }
        bridge.register(17, 9_000_000, now)
        assertEquals(1_010_000_000L, bridge.deadline(17))
        now += 2_000_000
        bridge.register(21, 9_033_333, now)
        assertEquals(1_043_333_000L, bridge.deadline(21))
        // No source-to-local uptime comparison: source is nine seconds, local is one second.
        assertEquals(0L, bridge.await(1_043_333_000L))
        assertEquals(1_043_333_000L, now)
    }

    @Test fun `late decoded frame drops without a wait and unknown mapping releases immediately`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 3, GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(10, 100)))
        val bridge = GalVideoOutputTiming(controller, 3, nanoTime = { 1_000L }) { fail("late output waited") }
        bridge.register(99, 0, 100)
        assertEquals(Long.MIN_VALUE, bridge.deadline(99))
        assertEquals(0L, bridge.deadline(1234))
    }

    @Test fun `runtime disable interrupts a legacy wait within one bounded slice`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 3, GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100_000_000, 200_000_000)))
        var now = 1_000_000L
        var slices = 0
        val bridge = GalVideoOutputTiming(controller, 3, nanoTime = { now }) {
            assertTrue(it <= VideoPresentationWait.SLICE_NS)
            now += it
            slices++
            controller.update(Channel.ID_VID, 3, GalRuntimeOptions())
        }
        bridge.register(99, 0, now)
        assertEquals(0L, bridge.await(bridge.deadline(99)))
        assertEquals(1, slices)
    }

    @Test fun `retirement cancels wait and never leaks metadata to replacement`() {
        var now = 1L
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 3, GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(20_000_000, 40_000_000)))
        lateinit var old: GalVideoOutputTiming
        old = GalVideoOutputTiming(controller, 3, nanoTime = { now }) { now += it; old.retire() }
        old.register(5, 42, now)
        assertEquals(Long.MIN_VALUE, old.await(old.deadline(5)))
        assertEquals(Long.MIN_VALUE, old.deadline(5))
        controller.start(Channel.ID_VID, 4, GalRuntimeOptions())
        val replacement = GalVideoOutputTiming(controller, 4, nanoTime = { now }) { fail("replacement waited") }
        assertEquals(0L, replacement.deadline(5))
    }

    @Test fun `only matched actual frame feedback observes video and retired callbacks are ignored`() {
        val controller = mock<GalPresentationController>()
        whenever(controller.isVideoTimingEnabled(6)).thenReturn(true)
        whenever(controller.isVideoSessionActive(6)).thenReturn(true)
        whenever(controller.beginVideoOutput(6)).thenReturn(1L)
        var now = 1_000L
        val bridge = GalVideoOutputTiming(controller, 6, VideoSurfaceKind.DIRECT_DISPLAY, { now }) { fail("immediate output waited") }
        bridge.register(5, 88, 900)
        bridge.deadline(5)
        bridge.releasing(5, 0, true)
        verify(controller, never()).observeVideoOutput(any(), any(), any(), any())
        now = 1_200L
        bridge.onFrameRendered(5, 1_200)
        verify(controller).observeVideoOutput(6, 1, 88, 1_200)
        bridge.onFrameRendered(5, 1_300)
        bridge.register(6, 99, 950)
        bridge.deadline(6)
        bridge.releasing(6, 0, true)
        bridge.retire()
        bridge.onFrameRendered(6, 1_400)
        verify(controller, times(1)).observeVideoOutput(any(), any(), any(), any())
    }

    @Test fun `disabled timing does not retain input metadata and old generation cannot release`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 9, GalRuntimeOptions())
        val bridge = GalVideoOutputTiming(controller, 9, nanoTime = { 1_000L }) { fail("disabled timing waited") }
        bridge.register(10, 700, 900)
        controller.update(Channel.ID_VID, 9, GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100, 200)))
        assertEquals(0L, bridge.deadline(10))
        bridge.register(11, 800, 950)
        assertEquals(1_050L, bridge.deadline(11))
        controller.start(Channel.ID_VID, 10, GalRuntimeOptions())
        assertEquals(Long.MIN_VALUE, bridge.deadline(99))
    }

    @Test fun `foreign callback clock remains raw and delayed local callback preserves actual time`() {
        val controller = mock<GalPresentationController>()
        whenever(controller.isVideoTimingEnabled(6)).thenReturn(true)
        whenever(controller.isVideoSessionActive(6)).thenReturn(true)
        whenever(controller.beginVideoOutput(6)).thenReturn(1L)
        var now = 16_018_155_329_872L
        val bridge = GalVideoOutputTiming(controller, 6, VideoSurfaceKind.DIRECT_DISPLAY, { now }) { fail("immediate output waited") }
        fun counter(name: String) = bridge.javaClass.getDeclaredField(name).apply { isAccessible = true }.getLong(bridge)
        bridge.register(1, 12_681_847_256, now - 10_000_000)
        bridge.deadline(1)
        bridge.releasing(1, 0, true)
        bridge.onFrameRendered(1, 13_100_171_000)
        verify(controller, never()).observeVideoOutput(any(), any(), any(), any())
        assertEquals(0L, counter("observedCount"))
        assertEquals(1L, counter("rejectedClockCount"))
        assertEquals(13_100_171_000L, counter("lastRawCallbackNs"))

        now += 20_000_000
        bridge.register(2, 12_681_880_589, now - 5_000_000)
        bridge.deadline(2)
        bridge.releasing(2, 0, true)
        val actual = now + 2_000_000
        now = actual + 3_000_000_000 // Callback batching is not presentation latency.
        bridge.onFrameRendered(2, actual)
        verify(controller).observeVideoOutput(6, 1, 12_681_880_589, actual)
        assertEquals(1L, counter("observedCount"))
        assertEquals(2L, counter("callbackCount"))
        assertEquals(actual, counter("lastActualNs"))
        assertEquals(actual, counter("lastRawCallbackNs"))
        assertEquals(now, counter("lastCallbackReceiptNs"))
        bridge.register(3, 12_681_913_922, now)
        bridge.deadline(3)
        bridge.releasing(3, 0, true)
        bridge.onFrameRendered(3, 13_166_837_000)
        assertEquals(1L, counter("observedCount"))
        assertEquals(2L, counter("rejectedClockCount"))
        assertEquals(actual, counter("lastActualNs"))
        assertEquals(12_681_880_589L, counter("lastObservedSourceUs"))
        assertEquals(13_166_837_000L, counter("lastRawCallbackNs"))
        verify(controller, times(1)).observeVideoOutput(any(), any(), any(), any())
    }
    @Test fun `same media generation output replacement discards old surface render lag`() {
        val controller = GalPresentationController()
        val options = GalRuntimeOptions(avSyncRequested = true,
            avSync = GalRuntimeOptions.AvSync(averageLagWeight = 0, syncIntervalNs = 1_000_000,
                maxVideoDelayNs = 250_000_000))
        controller.start(Channel.ID_AUD, 10, options)
        controller.start(Channel.ID_VID, 20, options)
        controller.observeAudio(Channel.ID_AUD, 10, 0, 1_000_000_000)
        var now = 1_000_000_000L
        val old = GalVideoOutputTiming(controller, 20, VideoSurfaceKind.DIRECT_DISPLAY, { now })
        old.register(1, 40_000, now)
        assertEquals(1_040_000_000L, old.deadline(1))
        old.releasing(1, 1_040_000_000, true)
        now = 1_140_000_000
        old.onFrameRendered(1, now)
        assertEquals(1_200_000_000L, controller.videoReleaseTimeNs(20, 300_000, now, now))
        old.retire()
        val replacement = GalVideoOutputTiming(controller, 20, VideoSurfaceKind.TEXTURE_CONSUMER, { now })
        replacement.register(2, 300_000, now)
        assertEquals("new consumer inherited retired SurfaceView's 100ms lag", 1_300_000_000L, replacement.deadline(2))
        old.onFrameRendered(1, now + 20_000_000)
        replacement.register(3, 333_333, now)
        assertEquals(1_333_333_000L, replacement.deadline(3))
        assertEquals(1L, controller.videoObservationCount)
    }

    @Test fun `custom final admission repaces an updated future deadline without holding control locks`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(100_000_000, 300_000_000)))
        var now = 1_000_000_000L
        var waited = 0L
        lateinit var bridge: GalVideoOutputTiming
        bridge = GalVideoOutputTiming(controller, 1, VideoSurfaceKind.TEXTURE_CONSUMER, { now }) {
            assertFalse(Thread.holdsLock(controller))
            assertFalse(Thread.holdsLock(bridge))
            now += it
            waited += it
        }
        bridge.register(1, 0, now)
        assertEquals(0L, bridge.await(bridge.deadline(1)))
        controller.update(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(200_000_000, 300_000_000)))
        assertEquals(0L, bridge.admit(platformTiming = false))
        assertEquals(1_200_000_000L, now)
        assertEquals(200_000_000L, waited)
    }

    @Test fun `replaced output cannot plan observe or retire its successors correction`() {
        val controller = GalPresentationController()
        val options = GalRuntimeOptions(avSyncRequested = true,
            avSync = GalRuntimeOptions.AvSync(averageLagWeight = 0, syncIntervalNs = 1_000_000,
                maxVideoDelayNs = 250_000_000))
        controller.start(Channel.ID_AUD, 10, options)
        controller.start(Channel.ID_VID, 20, options)
        controller.observeAudio(Channel.ID_AUD, 10, 0, 1_000_000_000)
        var now = 1_000_000_000L
        val old = GalVideoOutputTiming(controller, 20, VideoSurfaceKind.DIRECT_DISPLAY, { now })
        old.register(1, 40_000, now)
        old.releasing(1, old.deadline(1), true)
        now = 1_140_000_000
        old.onFrameRendered(1, now)
        old.register(2, 300_000, now)
        old.releasing(2, old.deadline(2), true)
        // Replacement itself invalidates the old output, even before its delayed retire call.
        val current = GalVideoOutputTiming(controller, 20, VideoSurfaceKind.DIRECT_DISPLAY, { now })
        current.register(3, 300_000, now)
        assertEquals(1_300_000_000L, current.deadline(3))
        current.releasing(3, 1_300_000_000, true)
        old.register(99, 900_000, now)
        assertEquals(Long.MIN_VALUE, old.deadline(99))
        assertEquals(Long.MIN_VALUE, old.admit(platformTiming = true))
        now = 1_400_000_000
        old.onFrameRendered(2, now)
        assertEquals(1L, controller.videoObservationCount)
        current.onFrameRendered(3, 1_320_000_000)
        assertEquals(2L, controller.videoObservationCount)
        old.retire()
        current.register(4, 500_000, now)
        assertEquals(1_480_000_000L, current.deadline(4))
        assertEquals(1_480_000_000L, current.admit(platformTiming = true))
    }

}
