package com.andrerinas.openheadunit.main

/**
 * The four favorite apps under the home screen's main button, kept as package names in slot
 * order. An empty string is an empty slot, shown as "+".
 *
 * Pure, so the slot rules are unit tests rather than taps on a head unit.
 */
object FavoriteAppsPolicy {

    const val SLOT_COUNT = 4

    private const val SEPARATOR = ","

    /** Always [SLOT_COUNT] slots, whatever was stored, so a corrupt value never breaks the row. */
    fun decode(stored: String): List<String> =
        stored.split(SEPARATOR).map { it.trim() }.take(SLOT_COUNT)
            .let { it + List(SLOT_COUNT - it.size) { "" } }

    fun encode(slots: List<String>): String = slots.joinToString(SEPARATOR)

    /** Puts [packageName] in [slot], taking it out of any other slot so it never shows twice. */
    fun place(slots: List<String>, slot: Int, packageName: String): List<String> =
        slots.mapIndexed { index, current ->
            when {
                index == slot -> packageName
                current == packageName -> ""
                else -> current
            }
        }

    /** @return the slots with [packageName] in the first free one, or null when all four are taken. */
    fun add(slots: List<String>, packageName: String): List<String>? {
        if (packageName in slots) return slots
        val free = slots.indexOf("")
        return if (free < 0) null else place(slots, free, packageName)
    }

    fun remove(slots: List<String>, slot: Int): List<String> =
        slots.mapIndexed { index, current -> if (index == slot) "" else current }
}
