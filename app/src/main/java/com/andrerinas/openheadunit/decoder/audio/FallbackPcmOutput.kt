package com.andrerinas.openheadunit.decoder.audio

/** Switch once from AAudio, transferring its known-unplayed software prefix to AudioTrack. */
internal class FallbackPcmOutput(
    initial: PcmOutput,
    private val fallback: () -> PcmOutput,
    private val report: (String) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) : PcmOutput {
    private var delegate: PcmOutput? = initial
    private var metadata = PcmOutputMetadata(initial)
    private val pending = PendingPcm()
    private var canFallback = true
    override val outputEpoch: Long get() = if (canFallback) 0 else 1
    private var started = false
    private var requestedFrames = 960
    private var stalledSinceNs = -1L
    override var recoveryProgressSamples = 0L
        private set
    override val hasPendingRecovery get() = !pending.isEmpty || delegate?.hasPendingRecovery == true
    override val name get() = delegate?.name ?: metadata.name
    override val capacityFrames get() = delegate?.capacityFrames ?: metadata.capacityFrames
    override val bufferFrames get() = delegate?.bufferFrames ?: metadata.bufferFrames
    override val burstFrames get() = delegate?.burstFrames ?: metadata.burstFrames
    override val minimumBufferFrames get() = delegate?.minimumBufferFrames ?: metadata.minimumBufferFrames
    override val stagingBufferFrames get() = delegate?.stagingBufferFrames ?: metadata.stagingBufferFrames
    override val underruns get() = delegate?.underruns ?: metadata.underruns
    override val underrunsSupported get() = delegate?.underrunsSupported ?: metadata.underrunsSupported
    override val producerUnderruns get() = delegate?.producerUnderruns ?: metadata.producerUnderruns

    override fun setBufferFrames(frames: Int): Int {
        requestedFrames = frames
        val live = checkNotNull(delegate)
        val result = live.setBufferFrames(maxOf(frames, live.minimumBufferFrames))
        if (result < 0 && canFallback) { replace("buffer configuration: $result"); return bufferFrames }
        return result
    }
    override fun start() {
        started = true
        try { checkNotNull(delegate).start() } catch (e: Exception) { replace("start: ${e.message}") }
    }
    override fun pause() {
        started = false
        try { checkNotNull(delegate).pause() } catch (e: Exception) { replace("pause: ${e.message}") }
    }
    override fun write(data: ShortArray, offset: Int, count: Int): Int {
        val live = delegate ?: return -6
        val prefix = !pending.isEmpty
        val result = if (prefix) pending.writeTo(live) else live.write(data, offset, count)
        if (result > 0) {
            stalledSinceNs = -1L
            if (prefix) { recoveryProgressSamples += result; return 0 }
            return result
        }
        if (result == 0) {
            val now = nanoTime()
            if (stalledSinceNs < 0) stalledSinceNs = now
            if (now - stalledSinceNs < 250_000_000L) return 0
        }
        if (!canFallback) return if (result < 0) result else -6
        replace("write: $result")
        return 0
    }
    private fun retireForRecovery() {
        val old = delegate ?: return
        try { metadata = PcmOutputMetadata(old) } catch (_: Exception) { }
        delegate = null
        pending.prepend(old.closeForRecovery())
    }
    private fun replace(reason: String) {
        check(canFallback) { "Audio output failed after fallback: $reason" }
        canFallback = false
        report("AAudio -> AudioTrack ($reason)")
        retireForRecovery()
        // Publish before configuration so the outer recovery owner can still close a failed
        // replacement and take this wrapper's pending prefix.
        val next = fallback()
        delegate = next
        check(next.setBufferFrames(maxOf(requestedFrames, next.minimumBufferFrames)) >= 0)
        if (started) next.start()
        metadata = PcmOutputMetadata(next)
        stalledSinceNs = -1L
    }
    override fun closeForRecovery(): ShortArray {
        retireForRecovery()
        return pending.take()
    }
    override fun close() {
        val old = delegate
        delegate = null
        pending.take()
        old?.close()
    }
}
