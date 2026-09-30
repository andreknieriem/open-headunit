package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class RecoveringPcmOutputTest {
    @Test fun `a complete short prompt survives initial open backoff without more packets`() {
        var now = 0L
        var opens = 0
        val device = Device()
        val output = RecoveringPcmOutput(null, {
            if (++opens == 1) error("route restarting")
            device
        }, { now }, {})
        output.start()
        val prompt = ShortArray(640) { (it + 100).toShort() }
        assertEquals(prompt.size, AudioWriteLoop.writeFully(prompt.size, { true },
            { offset, remaining -> output.write(prompt, offset, remaining) }, {}, { now++ }))
        assertEquals(251L, now)
        assertEquals(2, opens)
        assertEquals(prompt.toList(), device.accepted)
    }

    @Test fun `terminal output retains the block until an explicit new Start`() {
        var now = 0L
        var opens = 0
        var working = false
        val device = Device()
        val output = RecoveringPcmOutput(null, {
            opens++
            if (!working) error("route unavailable")
            device
        }, { now }, {})
        val pcm = ShortArray(320) { it.toShort() }
        repeat(3) { output.write(pcm, 0, pcm.size); now += 250 }
        assertEquals(-6, output.write(pcm, 0, pcm.size))
        assertEquals(3, opens)
        working = true
        assertEquals(-6, output.write(pcm, 0, pcm.size))
        output.preparePlayback()
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(pcm.size, output.write(pcm, 0, pcm.size))
        assertEquals(pcm.toList(), device.accepted)
    }

    private class Device(var result: Int = Int.MAX_VALUE) : PcmOutput {
        override val name = "AudioTrack"
        override val capacityFrames = 19200
        override var bufferFrames = 19200
        override val burstFrames = 480
        override val underruns = 0
        var closes = 0
        var starts = 0
        val accepted = mutableListOf<Short>()
        override fun start() { starts++ }
        override fun pause() {}
        override fun close() { closes++ }
        override fun setBufferFrames(frames: Int): Int { bufferFrames = frames; return frames }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            val n = minOf(count, result)
            if (n > 0) accepted.addAll(data.slice(offset until offset + n))
            return n
        }
    }

    @Test fun `dead audioserver after a partial write reopens and writes only remaining samples`() {
        val first = Device(320)
        val replacement = Device()
        val output = RecoveringPcmOutput(first, { replacement }, { 0 }, {})
        output.setBufferFrames(1440)
        output.start()
        val pcm = ShortArray(960) { it.toShort() }
        assertEquals(960, AudioWriteLoop.writeFully(960, { true }, { off, size ->
            output.write(pcm, off, size)
        }, { first.result = -6 }, {}))
        assertEquals(pcm.toList(), first.accepted + replacement.accepted)
        assertEquals(1, first.closes)
        assertEquals(1, replacement.starts)
        assertEquals(1440, replacement.bufferFrames)
    }

    @Test fun `repeated route errors are bounded even when no output accepts any PCM`() {
        var opened = 0
        val output = RecoveringPcmOutput(Device(-6), { opened++; Device(-6) }, { 0 }, {})
        assertEquals(-6, AudioWriteLoop.writeFully(960, { true }, { off, size ->
            output.write(ShortArray(960), off, size)
        }, {}, {}))
        assertEquals(2, opened)
    }

    @Test fun `zero writes retain the half second watchdog and learned output budget`() {
        var now = 0L
        val replacement = Device()
        val output = RecoveringPcmOutput(Device(0), { replacement }, { now }, {})
        output.setBufferFrames(1920)
        output.start()
        val pcm = ShortArray(960)
        assertEquals(0, output.write(pcm, 0, pcm.size))
        now = 499
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(0, replacement.starts)
        now = 500
        assertEquals(0, output.write(pcm, 0, pcm.size))
        assertEquals(1, replacement.starts)
        assertEquals(1920, replacement.bufferFrames)
        assertEquals(960, output.write(pcm, 0, pcm.size))
    }

    @Test fun `invalid write arguments never churn hardware outputs`() {
        val output = RecoveringPcmOutput(Device(-2), { error("must not reopen") }, { 0 }, {})
        assertEquals(-2, output.write(ShortArray(960), 0, 960))
    }

    @Test fun `a few priming samples cannot reset the recovery budget`() {
        val devices = mutableListOf(Device(32))
        val output = RecoveringPcmOutput(devices.first(), {
            Device(32).also { devices.add(it) }
        }, { 0 }, {})
        var writes = 0
        assertEquals(-6, AudioWriteLoop.writeFully(960, { writes < 20 }, { offset, size ->
            writes++
            output.write(ShortArray(960), offset, size)
        }, { devices.last().result = -6 }, {}))
        assertEquals(3, devices.size)
        assertEquals(6, writes)
    }

    @Test fun `a failed reopen preserves the partial block while awaiting its next attempt`() {
        var now = 0L
        var opens = 0
        val first = Device(320)
        val replacement = Device()
        val output = RecoveringPcmOutput(first, {
            if (++opens == 1) error("audioserver still restarting")
            replacement
        }, { now }, {})
        output.start()
        val pcm = ShortArray(960) { it.toShort() }
        val count = AudioWriteLoop.writeFully(pcm.size, { true }, { offset, remaining ->
            output.write(pcm, offset, remaining)
        }, { first.result = -6 }, { now++ })
        assertEquals(960, count)
        assertTrue(now in 250L..251L)
        assertEquals(2, opens)
        assertEquals(pcm.toList(), first.accepted + replacement.accepted)
        assertEquals(1, first.closes)
    }

    @Test fun `permanent reopen exceptions consume the budget and close once`() {
        var now = 0L
        var opens = 0
        val first = Device(-6)
        val output = RecoveringPcmOutput(first, { opens++; error("no route") }, { now }, {})
        val count = AudioWriteLoop.writeFully(960, { true }, { offset, remaining ->
            output.write(ShortArray(960), offset, remaining)
        }, {}, { now++ })
        assertEquals(-6, count)
        output.close()
        assertEquals(2, opens)
        assertEquals(1, first.closes)
        assertTrue(output.isParked)
    }
}
