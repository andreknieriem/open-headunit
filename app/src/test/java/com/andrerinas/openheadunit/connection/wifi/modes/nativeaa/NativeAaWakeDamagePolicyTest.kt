package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeAaWakeDamagePolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeAaWakeDamagePolicyTest {

    private val fresh = 0
    private val waitedOut = NativeAaWakeDamagePolicy.REPROBE_AFTER_ARMINGS

    @Test
    fun `an unmeasured unit is allowed to wake, because that wake is the measurement`() {
        assertTrue(NativeAaWakeDamagePolicy.allowsEscalation(Verdict.UNKNOWN, fresh))
        assertTrue(NativeAaWakeDamagePolicy.isProbe(Verdict.UNKNOWN, fresh))
    }

    @Test
    fun `a unit whose link came back keeps waking, and is not probed again`() {
        assertTrue(NativeAaWakeDamagePolicy.allowsEscalation(Verdict.SAFE, fresh))
        assertFalse(NativeAaWakeDamagePolicy.isProbe(Verdict.SAFE, fresh))
        assertFalse(NativeAaWakeDamagePolicy.isProbe(Verdict.SAFE, waitedOut))
    }

    @Test
    fun `a unit that lost its link stops waking, until enough armings have got nowhere`() {
        assertFalse(NativeAaWakeDamagePolicy.allowsEscalation(Verdict.DESTRUCTIVE, fresh))
        for (armings in 1 until waitedOut) {
            assertFalse(
                "$armings armings",
                NativeAaWakeDamagePolicy.allowsEscalation(Verdict.DESTRUCTIVE, armings)
            )
        }
        assertTrue(NativeAaWakeDamagePolicy.allowsEscalation(Verdict.DESTRUCTIVE, waitedOut))
    }

    /** The re-measurement is only worth taking if it is recorded, so it has to probe as well. */
    @Test
    fun `the arming that lifts the refusal is itself the new measurement`() {
        assertFalse(NativeAaWakeDamagePolicy.isProbe(Verdict.DESTRUCTIVE, fresh))
        assertTrue(NativeAaWakeDamagePolicy.isProbe(Verdict.DESTRUCTIVE, waitedOut))
    }

    @Test
    fun `only a measured failure is ever re-probed`() {
        assertFalse(NativeAaWakeDamagePolicy.shouldReprobe(Verdict.UNKNOWN, waitedOut))
        assertFalse(NativeAaWakeDamagePolicy.shouldReprobe(Verdict.SAFE, waitedOut))
        assertTrue(NativeAaWakeDamagePolicy.shouldReprobe(Verdict.DESTRUCTIVE, waitedOut))
    }

    @Test
    fun `a link that came back is safe and a link still down is destructive`() {
        assertEquals(Verdict.SAFE, NativeAaWakeDamagePolicy.verdictFrom(true, false))
        assertEquals(Verdict.DESTRUCTIVE, NativeAaWakeDamagePolicy.verdictFrom(false, false))
    }

    /**
     * The session is what the wake was for. A unit that connects only when a poke goes out was
     * condemned on the link reading alone, while every successful run it had came from that poke.
     */
    @Test
    fun `a wake that brought the phone in is never condemned, whatever the link reads`() {
        for (link in listOf(true, false, null)) {
            assertEquals(
                "link=$link",
                Verdict.SAFE,
                NativeAaWakeDamagePolicy.verdictFrom(link, wakeStartedAaSession = true)
            )
        }
    }

    /** The adapter refusing to answer is not evidence the unit is fine, nor that it is broken. */
    @Test
    fun `an unreadable link leaves the unit unmeasured`() {
        assertEquals(Verdict.UNKNOWN, NativeAaWakeDamagePolicy.verdictFrom(null, false))
        assertFalse(NativeAaWakeDamagePolicy.recordable(Verdict.UNKNOWN))
    }

    @Test
    fun `both real answers are worth storing`() {
        assertTrue(NativeAaWakeDamagePolicy.recordable(Verdict.SAFE))
        assertTrue(NativeAaWakeDamagePolicy.recordable(Verdict.DESTRUCTIVE))
    }

    @Test
    fun `the stored int round-trips and an unknown one reads as unmeasured`() {
        for (verdict in Verdict.entries) {
            assertEquals(verdict, Verdict.of(verdict.ordinal))
        }
        assertEquals(Verdict.UNKNOWN, Verdict.of(-1))
        assertEquals(Verdict.UNKNOWN, Verdict.of(Verdict.entries.size))
    }

    /** A destructive unit behaves exactly as the shipped stand-down did until its clock runs out. */
    @Test
    fun `only a measured failure withholds the wake`() {
        for (verdict in Verdict.entries) {
            assertEquals(
                verdict.name,
                verdict != Verdict.DESTRUCTIVE,
                NativeAaWakeDamagePolicy.allowsEscalation(verdict, fresh)
            )
        }
    }
}
