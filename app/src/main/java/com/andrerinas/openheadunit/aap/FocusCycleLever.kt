package com.andrerinas.openheadunit.aap

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Exclusive claim on the video-focus release/regain cycle.
 *
 * The cycle is the only thing in AAP that makes the phone produce a keyframe on demand, and it is
 * one operation split across a 400 ms gap: a release, then a regain. Two escalations spend it -
 * [KeyframeCycleEscalationPolicy], for a picture left corrupt by a shed frame, and
 * [WarmRelaunchKeyframePolicy], for a surface that has never shown one - each with its own budget
 * and its own handler.
 *
 * Their class comments assert the two can never overlap, and until now nothing enforced it. That was
 * tolerable while a decoder rebuild switched the first policy's clock off; it stopped being
 * tolerable the moment a rebuilt decoder started arming it instead, because a rebuild storm is
 * exactly when both policies see a surface with no picture at the same time.
 *
 * The cost of getting it wrong is not a wasted message. A second release issued between the first
 * cycle's two halves is answered with a second sink stop, and the single shared regain runnable that
 * each side keeps - replaced rather than tracked per cycle - can then complete only one of them. The
 * phone is left with its video sink down and nothing asking for it back: a permanent black screen
 * with audio still playing.
 *
 * So: whoever claims it owns it until it hands it back. A refused claim is not a failure - the cycle
 * already in flight brings the same keyframe both callers wanted - so the refused caller waits rather
 * than spending its own budget on a release it never sent.
 */
class FocusCycleLever {

    private val held = AtomicBoolean(false)

    /** Takes the lever for one cycle. False when somebody else already holds it. */
    fun tryClaim(): Boolean = held.compareAndSet(false, true)

    /**
     * Hands it back.
     *
     * Idempotent on purpose: a cycle can be completed early by a settle path as well as by its own
     * delayed regain, and both must be safe to call.
     */
    fun release() {
        held.set(false)
    }

    /** Whether a cycle is between its two halves. */
    val isHeld: Boolean get() = held.get()
}
