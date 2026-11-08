package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PokeTargetPolicyTest {

    private val phone = setOf("A0:46:5A:97:E4:95")
    private val car = setOf("00:1E:B8:12:34:56")

    /**
     * The narrowing a user asks for by turning the opt-in off: wake this phone or nothing. Widening
     * stays the default, because the poke is the only thing that starts Android Auto on some units.
     */
    @Test
    fun `no target and no opt-in pokes nothing`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = false)
        )
    }

    /** The default, and what an empty list used to do implicitly. */
    @Test
    fun `no target with the opt-in on pokes every paired device`() {
        assertEquals(
            PokeTargets.AllPaired,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = true)
        )
    }

    /** A chosen target is used as chosen, and the opt-in never widens it. */
    @Test
    fun `a chosen target is never widened`() {
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = true)
        )
        assertEquals(
            PokeTargets.Selected(phone),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = false)
        )
    }

    /**
     * The seeded case: the list is copied from the auto-start trigger list, which may name the
     * car's own Bluetooth. A list of only that is a list of nothing, so the opt-in decides.
     */
    @Test
    fun `a selection of only non-phones falls back to every paired device when opted in`() {
        assertEquals(
            PokeTargets.AllPaired,
            PokeTargetPolicy.targets(selected = car, allPairedOptIn = true, notPhones = car)
        )
    }

    @Test
    fun `a selection of only non-phones pokes nothing when opted out`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = car, allPairedOptIn = false, notPhones = car)
        )
    }

    @Test
    fun `a mixed selection offers the phones and reports the rest dropped`() {
        assertEquals(
            PokeTargets.Selected(phone, dropped = car),
            PokeTargetPolicy.targets(selected = phone + car, allPairedOptIn = false, notPhones = car)
        )
    }

    private val other = setOf("DC:B7:2E:5E:4E:59")

    /** The field failure: A's session locked the list, so a second phone was never woken. */
    @Test
    fun `a default unit still wakes the second phone after the first one forms a session`() {
        val targets = PokeTargetPolicy.targets(
            selected = emptySet(), allPairedOptIn = true, learned = listOf("A0:46:5A:97:E4:95")
        )
        assertEquals(PokeTargets.AllPaired, targets)
    }

    @Test
    fun `a default unit still lets the second phone be picked after the first one forms a session`() {
        val paired = listOf("A0:46:5A:97:E4:95", "DC:B7:2E:5E:4E:59")
        val targets = PokeTargetPolicy.targets(
            selected = emptySet(), allPairedOptIn = true, learned = listOf(paired[0])
        )
        val scope = NativeDriverSelectionPolicy.autoScope(paired, targets)
        assertTrue(paired[1] in scope)
    }

    @Test
    fun `a wake list the user chose is widened by the learned phones`() {
        assertEquals(
            PokeTargets.Selected(phone + other),
            PokeTargetPolicy.targets(selected = phone, allPairedOptIn = false, learned = other.toList())
        )
    }

    @Test
    fun `an empty list with all-paired off wakes the learned phones only`() {
        assertEquals(
            PokeTargets.Selected(other),
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = false, learned = other.toList())
        )
    }

    @Test
    fun `an empty list with all-paired off and nothing learned wakes nothing`() {
        assertEquals(
            PokeTargets.None,
            PokeTargetPolicy.targets(selected = emptySet(), allPairedOptIn = false, learned = emptyList())
        )
    }

    @Test
    fun `a non-phone in the chosen list is still dropped`() {
        assertEquals(
            PokeTargets.Selected(phone + other, dropped = car),
            PokeTargetPolicy.targets(
                selected = phone + car, allPairedOptIn = false, notPhones = car, learned = other.toList()
            )
        )
    }
}
