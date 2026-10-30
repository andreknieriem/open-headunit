package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class VideoRepairTimingTest {
    @Test fun `repair grant is one bounded attributable frame per candidate not a disabled timing window`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(9_000_000, 17_000_000)))
        val now = 2_000_000_000L
        val bridge = GalVideoOutputTiming(controller, 1, nanoTime = { now }) { fail("repair should be immediate") }
        bridge.register(1, 0, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(1))
        assertEquals(0L, bridge.repairDeadline(5))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(5))
        bridge.register(2, 33_333, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(2))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(5))
        bridge.register(3, 66_666, now - 1_000_000_001)
        assertEquals(Long.MIN_VALUE, bridge.deadline(3))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(6))
        assertEquals(0L, bridge.deadline(999))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(7))
        bridge.register(4, 100_000, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(4))
        controller.start(Channel.ID_VID, 2, GalRuntimeOptions())
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(8))
        bridge.retire()
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(9))
    }
    @Test fun `final admission keeps repair grant frame local and expires it after one second`() {
        val controller = GalPresentationController()
        controller.start(Channel.ID_VID, 1,
            GalRuntimeOptions(videoTiming = GalRuntimeOptions.VideoTiming(9_000_000, 17_000_000)))
        var now = 2_000_000_000L
        val bridge = GalVideoOutputTiming(controller, 1, nanoTime = { now })
        bridge.register(1, 0, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(1))
        assertEquals(0L, bridge.repairDeadline(5))
        assertEquals(0L, bridge.admit(platformTiming = true))
        bridge.register(2, 33_333, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(2))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(5))
        assertEquals(Long.MIN_VALUE, bridge.admit(platformTiming = true))
        bridge.register(3, 66_666, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(3))
        assertEquals(0L, bridge.repairDeadline(6))
        now += 900_000_001
        assertEquals(Long.MIN_VALUE, bridge.admit(platformTiming = true))
    }

}
