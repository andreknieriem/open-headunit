package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

/** What the wake poke does when it looks at its target list. */
sealed class PokeTargets {
    /** Poke these addresses, in the order the caller resolves them; [dropped] were chosen but are not phones. */
    data class Selected(val macs: Set<String>, val dropped: Set<String> = emptySet()) : PokeTargets()

    /** Poke every paired device: the opt-in is on and nothing specific was chosen. */
    object AllPaired : PokeTargets()

    /** Poke nothing, because no target is set and widening was not asked for. */
    object None : PokeTargets()
}

/**
 * Who the Native AA wake poke connects to. A chosen list is widened by the learned phones, and an
 * empty one wakes every paired device only when opted in. `notPhones` count as not chosen.
 */
object PokeTargetPolicy {

    fun targets(
        selected: Set<String>,
        allPairedOptIn: Boolean,
        notPhones: Set<String> = emptySet(),
        learned: List<String> = emptyList()
    ): PokeTargets {
        val offered = selected - notPhones
        val learnedPhones = learned.filter { it !in notPhones }
        return when {
            offered.isNotEmpty() -> PokeTargets.Selected(offered + learnedPhones, selected - offered)
            allPairedOptIn -> PokeTargets.AllPaired
            learnedPhones.isNotEmpty() -> PokeTargets.Selected(learnedPhones.toSet())
            else -> PokeTargets.None
        }
    }
}
