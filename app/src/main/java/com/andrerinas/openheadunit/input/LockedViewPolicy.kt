package com.andrerinas.openheadunit.input

/**
 * Whether a locked unit shows the night overlay in place of the Android Auto picture
 * ("Night overlay while locked" on). The overlay is drawn by this app from the navigation and
 * media data the phone sends beside the picture, so it shows no map, no saved places and none of
 * Android Auto's pop-ups, such as a caller's name.
 *
 * It changes only what this unit draws. Nothing is sent to the phone for it, and the input hold
 * of [LockedInputPolicy] stays as it is. Kept free of Android calls so it can be unit tested.
 */
object LockedViewPolicy {
    enum class Step { SHOW, HIDE, KEEP }

    /**
     * @param engaged        the input hold of a locked unit, [LockedInputPolicy.engaged].
     * @param enabled        the "Night overlay while locked" setting.
     * @param overlayVisible the night overlay is up now.
     * @param ownsOverlay    the locked unit put the overlay up, rather than the "Night overlay"
     *                       gesture action of the unlocked projection.
     *
     * Unlocked, or with the setting off, only an overlay the locked unit put up is taken away: one
     * opened by the gesture action keeps its own tap to close.
     */
    fun decide(engaged: Boolean, enabled: Boolean, overlayVisible: Boolean, ownsOverlay: Boolean): Step = when {
        engaged && enabled -> if (overlayVisible) Step.KEEP else Step.SHOW
        ownsOverlay && overlayVisible -> Step.HIDE
        else -> Step.KEEP
    }
}
