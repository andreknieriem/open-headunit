package com.andrerinas.openheadunit.aap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two things are pinned here, and both came from measurements rather than reasoning.
 *
 * Every head unit below Android 10 reports the group frequency as zero, which is most of the
 * hardware that files a bug about projected video, and the original line told all of them to
 * disconnect the network they were joined to on the strength of a comparison it could not make. On
 * the unit that prompted the change that advice pointed at the only configuration that worked.
 *
 * Then a rig on Android 14, where both frequencies *are* readable and 260 MHz apart, ran ten clean
 * minutes at 45-55 fps in exactly the state the surviving warning describes. So no branch prescribes
 * anything any more - `assertNoPrescription` is applied to all five.
 */
class StationCoexistencePolicyTest {

    private fun describe(sta: Int, group: Int) = StationCoexistencePolicy.describe(sta, group)

    /** No branch may tell the user to change anything. Nothing measured supports it. */
    private fun assertNoPrescription(finding: StationCoexistencePolicy.Finding) {
        listOf("Disconnecting", "disconnect", "hotspot instead", "removes the").forEach {
            assertFalse("must not prescribe ($it): $finding", finding.message.contains(it))
        }
    }

    @Test
    fun `two known frequencies that differ is the one case worth a warning`() {
        val finding = describe(sta = 5745, group = 2437)
        assertEquals(StationCoexistencePolicy.Level.WARN, finding.level)
        assertTrue("both frequencies belong in the line", finding.message.contains("5745 MHz"))
        assertTrue(finding.message.contains("2437 MHz"))
        assertTrue("the retune is the fact being reported",
            finding.message.contains("retune between"))
        assertNoPrescription(finding)
    }

    @Test
    fun `the rig's own reading warns and prescribes nothing`() {
        // Station 5500 MHz, group 5240 MHz, as measured on hardware during ten clean minutes.
        val finding = describe(sta = 5500, group = 5240)
        assertEquals(StationCoexistencePolicy.Level.WARN, finding.level)
        assertTrue(finding.message.contains("5500 MHz"))
        assertTrue(finding.message.contains("5240 MHz"))
        assertNoPrescription(finding)
    }

    @Test
    fun `the same channel is stated and not prescribed for`() {
        val finding = describe(sta = 5745, group = 5745)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue(finding.message.contains("same channel"))
        assertNoPrescription(finding)
    }

    @Test
    fun `an unreadable group frequency prescribes nothing`() {
        // API 27: WifiP2pGroup.getFrequency() does not exist, so the group side reads 0. This is
        // the combination the reporter's unit produced on every station-joined run, all of which
        // were the clean ones.
        val finding = describe(sta = 5745, group = 0)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue("say what is known", finding.message.contains("5745 MHz"))
        assertTrue("and say why the rest is not",
            finding.message.contains("cannot be told from here"))
        assertNoPrescription(finding)
    }

    @Test
    fun `an unreadable station frequency prescribes nothing either`() {
        val finding = describe(sta = 0, group = 2437)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue(finding.message.contains("2437 MHz"))
        assertTrue(finding.message.contains("cannot be told from here"))
        assertNoPrescription(finding)
    }

    @Test
    fun `neither frequency readable still reports the coexistence itself`() {
        val finding = describe(sta = 0, group = 0)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue("the fact that both are up is still worth a line",
            finding.message.contains("connected to another WiFi network"))
        assertNoPrescription(finding)
    }

    @Test
    fun `a negative frequency is treated as unavailable`() {
        // getFrequency() has been seen returning -1 as well as 0 for "no answer".
        val finding = describe(sta = -1, group = -1)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertFalse(finding.message.contains("-1"))
    }

    @Test
    fun `no branch prescribes anything`() {
        val combinations = listOf(
            5745 to 2437, 5500 to 5240, 5745 to 5745, 5745 to 0, 0 to 5745, 0 to 0,
            2412 to 2412, -1 to 5180
        )
        combinations.forEach { (sta, group) -> assertNoPrescription(describe(sta, group)) }
        listOf(5805, 2437, 0, -1).forEach {
            assertNoPrescription(StationCoexistencePolicy.describeNotAssociated(it))
        }
    }

    // The unjoined arm. It printed nothing at all until now, which is why a capture could not be
    // sorted into the arm that decides whether the session was ever going to work.

    @Test
    fun `an unjoined unit says so, with the group frequency it does have`() {
        val finding = StationCoexistencePolicy.describeNotAssociated(5805)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue("the group frequency is known here and belongs in the line",
            finding.message.contains("5805 MHz"))
        assertTrue(finding.message.contains("not connected to any other WiFi network"))
        assertNoPrescription(finding)
    }

    @Test
    fun `an unjoined unit below Android 10 still says so`() {
        // The group frequency is unreadable on the whole class of hardware that files these
        // reports, and the arm is worth naming regardless of whether the number is available.
        val finding = StationCoexistencePolicy.describeNotAssociated(0)
        assertEquals(StationCoexistencePolicy.Level.INFO, finding.level)
        assertTrue(finding.message.contains("not connected to any other WiFi network"))
        assertFalse("no bare zero in a user-facing line", finding.message.contains("0 MHz"))
        assertNoPrescription(finding)
    }

    @Test
    fun `the two arms are separable by one grep`() {
        // This is the whole point of the change: a reporter's capture has to fall on exactly one
        // side of a single search. Both phrases must be unambiguous against every other branch.
        val joinedPhrase = "is connected to another WiFi network"
        val alonePhrase = "not connected to any other WiFi network"

        val joined = listOf(
            describe(5745, 2437), describe(5500, 5240), describe(5745, 5745),
            describe(5745, 0), describe(0, 2437), describe(0, 0)
        )
        joined.forEach {
            assertTrue("joined arm must match its own grep: $it", it.message.contains(joinedPhrase))
            assertFalse("and must not match the other: $it", it.message.contains(alonePhrase))
        }

        listOf(5805, 0).map { StationCoexistencePolicy.describeNotAssociated(it) }.forEach {
            assertTrue("unjoined arm must match its own grep: $it", it.message.contains(alonePhrase))
            assertFalse("and must not match the other: $it", it.message.contains(joinedPhrase))
        }
    }
}
