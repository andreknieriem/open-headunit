package com.andrerinas.openheadunit.decoder.audio

/** Sample-clock ducking: buffered prompts and overlapping speech channels share one envelope.
 * No transport/main-thread timers can restore media while a prompt is still being rendered. */
internal class MixerDuckingEnvelope(private val sampleRate: Int = 48000) {
    private var gain = 1f
    private var target = 1f
    private var step = 0f
    private var remaining = 0

    fun setSpeechActive(active: Boolean) {
        val next = if (active) 0.4f else 1f
        if (next == target) return
        target = next
        remaining = sampleRate * (if (active) 10 else 120) / 1000
        step = (target - gain) / remaining
    }

    fun nextGain(): Float {
        if (remaining > 0) {
            remaining--
            gain = if (remaining == 0) target else gain + step
        }
        return gain
    }
}
