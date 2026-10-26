package com.andrerinas.openheadunit.decoder.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One worker owns this fixed 10ms block. Java bulk transfers and synchronous JNI
 * calls share the same storage; a bound engine holds JNI global references until
 * close so its cached addresses cannot outlive their backing buffers. The output
 * block remains untouched while a full bank applies backpressure. Allocate only
 * at worker startup, and set byte order before creating the short view. */
internal class TimeStretchBlock {
    val bytes: ByteBuffer = ByteBuffer.allocateDirect(960 * 2).order(ByteOrder.nativeOrder())
    private val samples = bytes.asShortBuffer()
    val size: Int get() = samples.capacity()
    operator fun get(index: Int): Short = samples.get(index)
    operator fun set(index: Int, value: Short) { samples.put(index, value) }
    fun put(source: ShortArray, offset: Int, length: Int, destinationOffset: Int) {
        samples.clear()
        samples.position(destinationOffset)
        samples.put(source, offset, length)
    }
    fun get(sourceOffset: Int, destination: ShortArray, offset: Int, length: Int) {
        samples.clear()
        samples.position(sourceOffset)
        samples.get(destination, offset, length)
    }
}

/** Variable output-length processor. One owner calls every method, including finish/close. */
internal interface TimeStretchEngine {
    fun bind(input: TimeStretchBlock, output: TimeStretchBlock) {}
    fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double)
    fun receive(pcm: TimeStretchBlock): Int
    fun finish()
    fun finished(): Boolean
    fun close()
}

internal class NativeTimeStretchEngine(mode: AudioTimeCorrectionMode) : TimeStretchEngine {
    private var handle = NativeTimeStretch.open(mode.nativeEngine).also { check(it != 0L) }
    private var input: TimeStretchBlock? = null
    private var output: TimeStretchBlock? = null
    override fun bind(input: TimeStretchBlock, output: TimeStretchBlock) {
        NativeTimeStretch.bind(handle, input.bytes, output.bytes)
        this.input = input
        this.output = output
    }
    override fun process(pcm: TimeStretchBlock, frames: Int, tempo: Double) {
        check(pcm === input) { "Unbound native input" }
        NativeTimeStretch.process(handle, frames, tempo)
    }
    override fun receive(pcm: TimeStretchBlock): Int {
        check(pcm === output) { "Unbound native output" }
        return NativeTimeStretch.receive(handle)
    }
    override fun finish() = NativeTimeStretch.finish(handle)
    override fun finished() = NativeTimeStretch.finished(handle)
    override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) NativeTimeStretch.close(old)
        input = null
        output = null
    }
}

internal object NativeTimeStretch {
    val supported: Boolean by lazy {
        try {
            System.loadLibrary("hur_time_stretch")
            true
        } catch (_: LinkageError) { false }
    }
    external fun open(engine: Int): Long
    external fun bind(handle: Long, input: ByteBuffer, output: ByteBuffer)
    external fun process(handle: Long, frames: Int, tempo: Double)
    external fun receive(handle: Long): Int
    external fun finished(handle: Long): Boolean
    external fun finish(handle: Long)
    external fun close(handle: Long)
}
