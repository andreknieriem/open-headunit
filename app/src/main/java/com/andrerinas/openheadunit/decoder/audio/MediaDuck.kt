package com.andrerinas.openheadunit.decoder.audio

/**
 * Lowers Android Auto's music for a moment while the app plays a short sound of its own, such as
 * the maintenance chime.
 *
 * Android does not duck an app's own players for that same app's focus request, and the music
 * here is ours (the mixers play it), so the mixers do the ducking themselves. Each one eases its
 * media sinks toward [GAIN] while [active] is set and back to full after, a step per render cycle,
 * so the change never clicks and costs one multiply that the cycle already does.
 */
object MediaDuck {

    const val GAIN = 0.35f

    /** The most the gain moves in one render cycle (about 10 ms), so a full duck takes a few cycles. */
    const val STEP = 0.1f

    @Volatile
    var active = false

    /** The next gain from [current] toward the target set by [active]. */
    fun next(current: Float, active: Boolean): Float {
        val target = if (active) GAIN else 1f
        return when {
            current < target -> minOf(target, current + STEP)
            current > target -> maxOf(target, current - STEP)
            else -> current
        }
    }
}
