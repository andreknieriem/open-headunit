package com.andrerinas.openheadunit.connection.carkey.fyt

import com.andrerinas.openheadunit.input.InputAction

/** Raw steering values from the FYT firmware, and the keys they stand for. */
object FytKeyTranslationPolicy {

    private val table = mapOf(
        547 to 23,
        550 to 21,
        551 to 22,
        574 to 87,
        575 to 88,
        587 to 88,
        588 to 87,
    )

    /** A raw value the user bound, Do nothing included, stays raw, so that binding still matches. */
    fun sendCode(raw: Int, bindings: Map<Int, InputAction>): Int {
        if (bindings[raw].let { it != null && it != InputAction.None }) return raw
        return table[raw] ?: raw
    }
}
