package com.andrerinas.openheadunit.aap

/**
 * Pacing rule for [AapVideo]'s stream recovery.
 *
 * Recovering from a corrupt frame means asking the phone for a fresh keyframe, which
 * [AapTransport] implements as a gain-only unsolicited focus nudge by default, escalating to a real
 * focus loss/regain cycle only per [KeyframeCycleEscalationPolicy]'s much stricter gates. Either one
 * is expensive and briefly visible on screen, and the phone reacts to it by rebuilding the stream,
 * so a burst of requests costs far more picture than the single frame that triggered the first one.
 * Rate limit them.
 *
 * Suppressing a request is safe to do silently: a corrupt frame is scoped to itself and the stream
 * resumes on the next one either way, so a suppressed request only means recovery waits for the
 * phone's own keyframe cadence rather than being brought forward.
 */
object VideoRecoveryPolicy {

    /** Minimum spacing between keyframe requests, in milliseconds. */
    const val KEYFRAME_REQUEST_THROTTLE_MS = 1000L

    /** Whether enough time has passed since [lastRequestMs] to send another keyframe request. */
    fun canRequestKeyframe(nowMs: Long, lastRequestMs: Long): Boolean =
        nowMs - lastRequestMs > KEYFRAME_REQUEST_THROTTLE_MS

    /**
     * Whether the decoder shedding a frame warrants asking the phone for a keyframe.
     *
     * A shed frame is a reference some later frame predicts from, so the picture drifts
     * washed-out and blocky until a keyframe arrives - and on an idle stream the phone's own
     * cadence can leave that on screen for a minute. The ask is the same throttled gain-only
     * nudge as corrupt-frame recovery.
     *
     * Gated on [hasRendered] because before the first rendered frame the drop means something
     * else entirely: a codec still draining its first buffers sheds frames while perfectly
     * healthy, the nudge is measured to do nothing before the phone has started its video sink,
     * and the not-yet-rendering window already has its own escalation
     * ([WarmRelaunchKeyframePolicy]).
     */
    fun shouldRequestOnDroppedFrame(hasRendered: Boolean, nowMs: Long, lastRequestMs: Long): Boolean =
        hasRendered && canRequestKeyframe(nowMs, lastRequestMs)
}
