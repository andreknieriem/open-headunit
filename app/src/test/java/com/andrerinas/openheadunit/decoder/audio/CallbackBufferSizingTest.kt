package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class CallbackBufferSizingTest {
    @Test fun `the combined budget includes callback staging instead of duplicating latency`() {
        for (burst in intArrayOf(96, 192, 240, 480)) {
            for (total in burst * 3..2880 step burst) {
                val device = CallbackBufferSizing.deviceFrames(total, burst)
                val queue = CallbackBufferSizing.queueFrames(total, device, burst)
                assertTrue(device >= burst * 2)
                assertTrue(queue >= burst)
                assertEquals(total, device + queue)
            }
        }
    }

    @Test fun `a device grant larger than requested keeps only the minimum staging queue`() {
        assertEquals(192, CallbackBufferSizing.queueFrames(576, 1920, 192))
        assertEquals(384, CallbackBufferSizing.deviceFrames(576, 192))
    }

    @Test fun `non integral budgets round to complete bursts`() {
        val device = CallbackBufferSizing.deviceFrames(961, 192)
        val queue = CallbackBufferSizing.queueFrames(961, device, 192)
        assertEquals(0, device % 192)
        assertEquals(0, queue % 192)
        assertEquals(1152, device + queue)
    }

    @Test fun `a callback larger than one burst fits within the advertised minimum budget`() {
        for (callback in intArrayOf(192, 256, 480, 960)) {
            val total = CallbackBufferSizing.minimumFrames(192, callback)
            val device = CallbackBufferSizing.deviceFrames(total, 192, callback)
            val queue = CallbackBufferSizing.queueFrames(total, device, 192, callback)
            assertEquals(384, device)
            assertTrue(queue >= callback)
            assertEquals(total, device + queue)
        }
    }

    @Test fun `producer gaps grow staging while hardware xruns grow the device share`() {
        val policy = CallbackDeviceBufferPolicy(192)
        assertEquals(384, policy.request(576, 192, 0))
        assertEquals(384, policy.request(768, 192, 0)) // producer xrun adds a staging burst
        assertEquals(576, policy.request(960, 192, 1)) // HAL xrun adds a device burst
        assertEquals(576, policy.request(960, 192, 1))
        assertEquals(576, policy.request(768, 192, 1))
        assertEquals(384, policy.request(576, 192, 1)) // stable budget eventually probes the floor
    }

    @Test fun `hardware can take spare staging budget at the ceiling without exceeding it`() {
        val policy = CallbackDeviceBufferPolicy(192)
        policy.request(2880, 192, 0)
        repeat(40) {
            val device = policy.request(2880, 192, it + 1)
            val queue = CallbackBufferSizing.queueFrames(2880, device, 192)
            assertEquals(2880, device + queue)
            assertTrue(queue >= 192)
        }
    }
}
