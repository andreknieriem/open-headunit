package com.andrerinas.openheadunit.decoder.audio

/** Variable output-length processor. One owner calls every method, including finish/close. */
internal interface TimeStretchEngine {
    fun process(pcm: ShortArray, frames: Int, tempo: Double)
    fun receive(pcm: ShortArray): Int
    fun finish()
    fun finished(): Boolean
    fun close()
}

internal class NativeTimeStretchEngine(mode: AudioTimeCorrectionMode) : TimeStretchEngine {
    private var handle = NativeTimeStretch.open(mode.nativeEngine).also { check(it != 0L) }
    override fun process(pcm: ShortArray, frames: Int, tempo: Double) = NativeTimeStretch.process(handle, pcm, frames, tempo)
    override fun receive(pcm: ShortArray) = NativeTimeStretch.receive(handle, pcm)
    override fun finish() = NativeTimeStretch.finish(handle)
    override fun finished() = NativeTimeStretch.finished(handle)
    override fun close() {
        val old = handle
        handle = 0
        if (old != 0L) NativeTimeStretch.close(old)
    }
}

internal object NativeTimeStretch {
    // Current NDK native dependencies target API 21+. The Kotlin OHU path still supports
    // the app's minSdk; loading an optional native engine must not break old head units.
    val supported: Boolean by lazy {
        if (android.os.Build.VERSION.SDK_INT < 21) false else try {
            System.loadLibrary("hur_time_stretch")
            true
        } catch (_: LinkageError) { false }
    }
    external fun open(engine: Int): Long
    external fun process(handle: Long, data: ShortArray, frames: Int, tempo: Double)
    external fun receive(handle: Long, data: ShortArray): Int
    external fun finished(handle: Long): Boolean
    external fun finish(handle: Long)
    external fun close(handle: Long)
}
