package com.andrerinas.openheadunit.decoder.audio

/** Crossfade limiter activation on the sample clock, including silent/muted contributors. */
internal class MixerSoftClipper {
    private var wet = 0f
    private var target = 0f
    private var step = 0f
    private var remaining = 0

    fun setEnabled(enabled: Boolean) {
        val next = if (enabled) 1f else 0f
        if (next == target) return
        target = next
        remaining = if (enabled) 480 else 5760
        step = (target - wet) / remaining
    }

    fun advance() {
        if (remaining > 0) {
            remaining--
            wet = if (remaining == 0) target else wet + step
        }
    }

    fun process(sample: Int): Short {
        if (wet == 0f) return sample.coerceIn(-32768, 32767).toShort()
        val s = sample.coerceIn(-98304, 98304)
        val clipped = when {
            s > 20480 -> { val d = s - 20480; 20480 + d * 12287 / (d + 24574) }
            s < -20480 -> { val d = -s - 20480; -(20480 + d * 12287 / (d + 24574)) }
            else -> s
        }
        return (sample + (clipped - sample) * wet).toInt().coerceIn(-32768, 32767).toShort()
    }
}
