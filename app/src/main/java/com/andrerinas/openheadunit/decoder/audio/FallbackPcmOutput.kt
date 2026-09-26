package com.andrerinas.openheadunit.decoder.audio

/** Switch once after an AAudio failure. The caller retains and retries the unwritten PCM tail. */
internal class FallbackPcmOutput(
    private var delegate: PcmOutput,
    private val fallback: () -> PcmOutput,
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) : PcmOutput {
    private var canFallback = true
    private var started = false
    private var requestedFrames = 960
    private var stalledSinceNs = -1L
    override val name: String get() = delegate.name
    override val capacityFrames: Int get() = delegate.capacityFrames
    override val bufferFrames: Int get() = delegate.bufferFrames
    override val burstFrames: Int get() = delegate.burstFrames
    override val underruns: Int get() = delegate.underruns
    override val stagingBufferFrames: Int get() = delegate.stagingBufferFrames
    override val producerUnderruns: Int get() = delegate.producerUnderruns
    override val minimumBufferFrames: Int get() = delegate.minimumBufferFrames

    override fun setBufferFrames(frames: Int): Int {
        requestedFrames = frames
        val result = delegate.setBufferFrames(maxOf(frames, delegate.minimumBufferFrames))
        if (result < 0 && canFallback) {
            replace("buffer configuration: $result")
            return delegate.bufferFrames
        }
        return result
    }
    override fun start() {
        started = true
        try { delegate.start() } catch (e: Exception) { replace("start: ${e.message}") }
    }
    override fun pause() {
        started = false
        try { delegate.pause() } catch (e: Exception) { replace("pause: ${e.message}") }
    }
    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        val result = delegate.write(data, offset, count)
        if (result > 0) stalledSinceNs = -1L
        if (result == 0) {
            val now = nanoTime()
            if (stalledSinceNs < 0) stalledSinceNs = now
            if (now - stalledSinceNs < 250_000_000L) return 0
        }
        if (result <= 0) {
            if (!canFallback) return if (result < 0) result else -6
            replace("write: $result")
            return 0
        }
        return result
    }
    private fun replace(reason: String) {
        check(canFallback) { "Audio output failed after fallback: $reason" }
        canFallback = false
        report("AAudio -> AudioTrack ($reason)")
        delegate.close()
        delegate = fallback()
        delegate.setBufferFrames(maxOf(requestedFrames, delegate.minimumBufferFrames))
        if (started) delegate.start()
        stalledSinceNs = -1L
    }
    override fun close() = delegate.close()
}
