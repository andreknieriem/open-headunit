package com.andrerinas.openheadunit.decoder.audio

import org.junit.Assert.*
import org.junit.Test

class PcmOutputRecoveryTest {
    private class FakeOutput(private val replies: MutableList<Int> = mutableListOf()) : PcmOutput {
        override val name = "fake"
        override val capacityFrames = 19200
        override var bufferFrames = 960
        override var burstFrames = 192
        override val underruns = 0
        override var minimumBufferFrames = 960
        override var stagingBufferFrames = 0
        override var producerUnderruns = 0
        var starts = 0
        var closes = 0
        var pauses = 0
        var failStart = false
        var failBuffer = false
        var failPause = false
        var unplayedSamples = 0
        val samples = mutableListOf<Short>()
        override fun setBufferFrames(frames: Int): Int { if (failBuffer) return -898; bufferFrames = frames; return frames }
        override fun start() { starts++; check(!failStart) }
        override fun close() { closes++ }
        override fun closeForRecovery(): ShortArray {
            close()
            val prefix = samples.takeLast(unplayedSamples).toShortArray()
            repeat(unplayedSamples) { samples.removeAt(samples.lastIndex) }
            unplayedSamples = 0
            return prefix
        }
        override fun pause() { pauses++; check(!failPause) }
        override fun write(data: ShortArray, offset: Int, count: Int): Int {
            val result = if (replies.isEmpty()) count else replies.removeAt(0)
            if (result > 0) samples.addAll(data.slice(offset until offset + result))
            return result
        }
    }

    @Test fun `disconnection after partial native write preserves the remaining PCM on fallback`() {
        val native = FakeOutput(mutableListOf(4, -899))
        val legacy = FakeOutput()
        val output = FallbackPcmOutput(native, { legacy }, {})
        output.setBufferFrames(1440)
        output.start()
        val data = ShortArray(16) { it.toShort() }
        val result = AudioWriteLoop.writeFully(data.size, { true }, { offset, remaining ->
            output.write(data, offset, remaining)
        }, {}, {})
        assertEquals(16, result)
        assertEquals(data.toList(), native.samples + legacy.samples)
        assertEquals(1, native.closes)
        assertEquals(1, legacy.starts)
        assertEquals(1440, legacy.bufferFrames)
        output.close()
        assertEquals(1, legacy.closes)
    }

    @Test fun `an AAudio start failure switches before the first PCM write`() {
        val native = FakeOutput().also { it.failStart = true }
        val legacy = FakeOutput()
        val output = FallbackPcmOutput(native, { legacy }, {})
        output.start()
        assertEquals(1, native.closes)
        assertEquals(1, legacy.starts)
    }

    @Test fun `initial and replacement start errors keep the first PCM block pending`() {
        val initial = FakeOutput().also { it.failStart = true }
        val failed = FakeOutput().also { it.failStart = true }
        val healthy = FakeOutput()
        var now = 0L
        var opens = 0
        val output = RecoveringPcmOutput(initial, { if (opens++ == 0) failed else healthy }, { now }, {})
        output.start()
        assertEquals("fake", output.name)
        assertEquals(960, output.bufferFrames) // no query of a released delegate is needed
        val pcm = ShortArray(16) { it.toShort() }
        assertEquals(16, AudioWriteLoop.writeFully(16, { true }, { offset, remaining ->
            output.write(pcm, offset, remaining)
        }, {}, { now++ }))
        assertEquals(pcm.toList(), healthy.samples)
        assertEquals(1, initial.closes)
        assertEquals(1, failed.closes)
        assertEquals(2, opens)
    }

    @Test fun `pause failure exposes unread recovery PCM so lifecycle cannot leave it parked`() {
        val native = FakeOutput().also { it.failPause = true; it.unplayedSamples = 4 }
        val healthy = FakeOutput()
        val output = RecoveringPcmOutput(native, { healthy }, { 0 }, {})
        output.start()
        val pcm = shortArrayOf(1, 2, 3, 4)
        assertEquals(4, output.write(pcm, 0, 4))
        output.pause()
        assertTrue(output.hasPendingRecovery)
        output.start()
        assertEquals(0, output.write(ShortArray(2), 0, 2))
        assertFalse(output.hasPendingRecovery)
        assertEquals(pcm.toList(), healthy.samples)
    }

    @Test fun `unread software staging precedes the unwritten caller tail without replaying consumed PCM`() {
        for (consumed in listOf(0, 2, 6)) {
            val native = FakeOutput(mutableListOf(6, -899)).also { it.unplayedSamples = 6 - consumed }
            val legacy = FakeOutput()
            val output = FallbackPcmOutput(native, { legacy }, {})
            output.start()
            val pcm = ShortArray(16) { it.toShort() }
            assertEquals(16, AudioWriteLoop.writeFully(16, { true }, { offset, remaining ->
                output.write(pcm, offset, remaining)
            }, {}, {}))
            assertEquals(pcm.toList(), native.samples + legacy.samples)
            assertEquals(1, native.closes)
        }
    }

    @Test fun `fallback construction failure transfers its saved prefix to the outer recovery owner`() {
        val native = FakeOutput(mutableListOf(6, -899)).also { it.unplayedSamples = 6 }
        val fallback = FallbackPcmOutput(native, { error("audioserver restarting") }, {})
        val legacy = FakeOutput()
        val output = RecoveringPcmOutput(fallback, { legacy }, { 0 }, {})
        output.start()
        val pcm = ShortArray(16) { it.toShort() }
        assertEquals(16, AudioWriteLoop.writeFully(16, { true }, { offset, remaining ->
            output.write(pcm, offset, remaining)
        }, {}, {}))
        assertEquals(pcm.toList(), legacy.samples)
        assertEquals(1, native.closes)
    }

    @Test fun `a second failure midway through staging transfer preserves just its unwritten remainder`() {
        val native = FakeOutput(mutableListOf(6, -899)).also { it.unplayedSamples = 6 }
        val firstLegacy = FakeOutput(mutableListOf(2, -6))
        val fallback = FallbackPcmOutput(native, { firstLegacy }, {})
        val replacement = FakeOutput()
        val output = RecoveringPcmOutput(fallback, { replacement }, { 0 }, {})
        output.start()
        val pcm = ShortArray(16) { it.toShort() }
        assertEquals(16, AudioWriteLoop.writeFully(16, { true }, { offset, remaining ->
            output.write(pcm, offset, remaining)
        }, {}, {}))
        assertEquals(pcm.toList(), firstLegacy.samples + replacement.samples)
    }

    @Test fun `buffer configuration failure falls back without starting during setup`() {
        val native = FakeOutput().also { it.failBuffer = true }
        val legacy = FakeOutput()
        val output = FallbackPcmOutput(native, { legacy }, {})
        assertEquals(960, output.setBufferFrames(960))
        assertEquals(1, native.closes)
        assertEquals(0, legacy.starts)
        output.start()
        assertEquals(1, legacy.starts)
    }

    @Test fun `pause and resume reuse the output instead of opening another stream`() {
        val native = FakeOutput()
        val output = FallbackPcmOutput(native, { error("unexpected fallback") }, {})
        output.start()
        output.pause()
        output.start()
        assertEquals(1, native.pauses)
        assertEquals(2, native.starts)
        assertEquals(0, native.closes)
    }

    @Test fun `a stalled native output falls back once and a second error is surfaced`() {
        val native = FakeOutput(mutableListOf(0, 0))
        val legacy = FakeOutput(mutableListOf(-6))
        var now = 0L
        var switches = 0
        val output = FallbackPcmOutput(native, { switches++; legacy }, {}, { now })
        output.start()
        assertEquals(0, output.write(ShortArray(960), 0, 960))
        assertEquals(0, switches)
        now = 250_000_000
        assertEquals(0, output.write(ShortArray(960), 0, 960))
        assertEquals(1, switches)
        assertEquals(-6, output.write(ShortArray(960), 0, 960))
        assertEquals(1, switches)
    }

    @Test fun `AAudio never takes over legacy speech vendor or hardware DSP routes`() {
        assertTrue(AudioOutputPolicy.useAAudio(33, 3, false, true))
        assertFalse(AudioOutputPolicy.useAAudio(25, 3, false, true))
        assertFalse(AudioOutputPolicy.useAAudio(26, 3, false, true))
        assertFalse(AudioOutputPolicy.useAAudio(27, 3, false, true))
        assertTrue(AudioOutputPolicy.useAAudio(28, 3, false, true))
        assertFalse(AudioOutputPolicy.useAAudio(33, 3, true, true))
        assertFalse(AudioOutputPolicy.useAAudio(33, 3, false, false))
        for (stream in intArrayOf(0, 1, 2, 4, 5, 6, 7, 8, 9, 10, 11, 99)) {
            assertFalse(AudioOutputPolicy.useAAudio(33, stream, false, true))
        }
    }

    @Test fun `fallback publishes the replacement burst floor and staging diagnostics`() {
        val native = FakeOutput(mutableListOf(-899)).also {
            it.minimumBufferFrames = 576; it.stagingBufferFrames = 192; it.producerUnderruns = 3
        }
        val legacy = FakeOutput().also { it.burstFrames = 480 }
        val output = FallbackPcmOutput(native, { legacy }, {})
        assertEquals(576, output.minimumBufferFrames)
        assertEquals(192, output.stagingBufferFrames)
        assertEquals(3, output.producerUnderruns)
        output.setBufferFrames(576)
        output.start()
        output.write(ShortArray(960), 0, 960)
        assertEquals(480, output.burstFrames)
        assertEquals(960, output.minimumBufferFrames)
        assertEquals(960, legacy.bufferFrames) // enforce the new floor before its first write
        assertEquals(0, output.stagingBufferFrames)
        assertEquals(0, output.producerUnderruns)
    }
}
