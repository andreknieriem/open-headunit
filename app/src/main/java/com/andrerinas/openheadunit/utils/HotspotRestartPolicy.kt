package com.andrerinas.openheadunit.utils

/**
 * What to do once a restarted access point has been read back after its hold.
 *
 * Asking again is only worth it when the access point came up and then fell; one that never came
 * up is the shape where repeated asks were measured to lose.
 */
object HotspotRestartPolicy {

    enum class Outcome { CONFIRMED, ASK_AGAIN, GIVE_UP }

    fun afterHold(upAfterAsk: Boolean, upAfterHold: Boolean, secondAskSpent: Boolean): Outcome = when {
        !upAfterAsk -> Outcome.GIVE_UP
        upAfterHold -> Outcome.CONFIRMED
        !secondAskSpent -> Outcome.ASK_AGAIN
        else -> Outcome.GIVE_UP
    }
}
