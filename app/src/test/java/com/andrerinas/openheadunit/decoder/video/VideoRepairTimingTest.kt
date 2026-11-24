package com.andrerinas.openheadunit.decoder.video

import com.andrerinas.openheadunit.aap.GalPresentationController
import com.andrerinas.openheadunit.aap.GalRuntimeOptions
import com.andrerinas.openheadunit.aap.protocol.Channel
import org.junit.Assert.*
import org.junit.Test

class VideoRepairTimingTest {
    private fun controller(now: Long) = GalPresentationController().also {
        val options = GalRuntimeOptions(
            videoTiming = GalRuntimeOptions.VideoTiming(9_000_000, 17_000_000),
            avSync = GalRuntimeOptions.AvSync(),
        )
        it.start(Channel.ID_AUD, 1, options)
        it.start(Channel.ID_VID, 1, options)
        it.observeAudio(Channel.ID_AUD, 1, 250_000, now)
    }

    @Test fun `A-V repair grant covers one bounded attributable frame per candidate`() {
        val now = 2_000_000_000L
        val controller = controller(now)
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
        var now = 2_000_000_000L
        val controller = controller(now)
        val bridge = GalVideoOutputTiming(controller, 1, nanoTime = { now })
        bridge.register(1, 0, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(1))
        assertEquals(0L, bridge.repairDeadline(5))
        assertEquals(0L, bridge.admit(platformTiming = true, hasReplacement = true))
        bridge.register(2, 33_333, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, bridge.deadline(2))
        assertEquals(Long.MIN_VALUE, bridge.repairDeadline(5))
        assertEquals(Long.MIN_VALUE, bridge.admit(platformTiming = true, hasReplacement = true))
        bridge.register(3, 66_666, now - 999_999_999)
        assertEquals(Long.MIN_VALUE, bridge.deadline(3))
        assertEquals(0L, bridge.repairDeadline(6))
        now += 2
        assertEquals(Long.MIN_VALUE, bridge.admit(platformTiming = true, hasReplacement = true))
    }

    @Test fun `granted repair cannot cross an output replacement within the same Start`() {
        val now = 2_000_000_000L
        val controller = controller(now)
        val old = GalVideoOutputTiming(controller, 1, nanoTime = { now })
        old.register(1, 0, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, old.deadline(1))
        assertEquals(0L, old.repairDeadline(5))

        val replacement = GalVideoOutputTiming(controller, 1, nanoTime = { now })
        assertEquals(Long.MIN_VALUE, old.admit(platformTiming = true))
        replacement.register(1, 0, now - 100_000_000)
        assertEquals(Long.MIN_VALUE, replacement.deadline(1))
        assertEquals(Long.MIN_VALUE, replacement.admit(platformTiming = true, hasReplacement = true))
    }

}
