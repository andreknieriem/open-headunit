package com.andrerinas.openheadunit.aap

/** What the service does to reach the media button route while the keymap screen learns. */
object KeyLearnSessionPolicy {

    data class Plan(val activate: Boolean, val silentTrack: Boolean, val deactivate: Boolean)

    // From API 26 the platform sends media keys to the app that played last; setActive routes nothing.
    private const val FIRST_PLAYER_RANKED_API = 26

    fun decide(
        sdkInt: Int,
        learning: Boolean,
        wasLearning: Boolean,
        dialogOpen: Boolean,
        selfMode: Boolean,
        phoneConnected: Boolean,
    ): Plan {
        if (selfMode) return Plan(activate = false, silentTrack = false, deactivate = false)
        val ranked = sdkInt >= FIRST_PLAYER_RANKED_API
        return Plan(
            activate = learning && !ranked,
            silentTrack = learning && dialogOpen && ranked,
            deactivate = !learning && wasLearning && !ranked && !phoneConnected,
        )
    }
}
