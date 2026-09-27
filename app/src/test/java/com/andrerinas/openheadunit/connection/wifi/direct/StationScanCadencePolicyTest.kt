package com.andrerinas.openheadunit.connection.wifi.direct

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StationScanCadencePolicyTest {

    @Test
    fun `no scans produces no line, so a quiet unit says nothing`() {
        assertNull(StationScanCadencePolicy.summarise(emptyList(), 30_000L))
    }

    @Test
    fun `a single scan has no cadence to report yet`() {
        val line = StationScanCadencePolicy.summarise(listOf(1_000L), 30_000L)
        assertNotNull(line)
        assertTrue(line!!.contains("1 in 30000ms"))
        assertTrue(line.contains("no cadence yet"))
    }

    @Test
    fun `a steady ten second cadence is reported as ten seconds`() {
        val scans = (0..3).map { 1_000L + it * 10_000L }
        val line = StationScanCadencePolicy.summarise(scans, 30_000L)!!
        assertTrue(line, line.contains("4 in 30000ms"))
        assertTrue(line, line.contains("every 10.0s"))
    }

    @Test
    fun `the shortest and longest gaps are both named`() {
        val scans = listOf(0L, 2_000L, 12_000L, 30_000L)
        val line = StationScanCadencePolicy.summarise(scans, 30_000L)!!
        assertTrue(line, line.contains("shortest 2.0s"))
        assertTrue(line, line.contains("longest 18.0s"))
    }

    @Test
    fun `each earlier scan is placed before the line, so a stutter can be matched to one`() {
        val line = StationScanCadencePolicy.summarise(listOf(1_000L, 11_000L, 31_000L), 30_000L)!!
        assertTrue(line, line.contains("the earlier ones 30.0s, 20.0s before this line"))
        assertTrue(line, line.endsWith("off the group's channel."))
    }

    @Test
    fun `a flood places only the most recent scans, so the line stays bounded`() {
        val scans = (0..30).map { it * 1_000L }
        val line = StationScanCadencePolicy.summarise(scans, 30_000L)!!
        assertTrue(line, line.contains("the earlier ones 10.0s, 9.0s"))
        assertTrue(line, line.contains(" 1.0s before this line"))
        assertTrue(line, !line.contains("11.0s"))
    }

    @Test
    fun `the reported window is the real one, not the nominal one`() {
        val line = StationScanCadencePolicy.summarise(listOf(0L, 10_000L), 31_411L)!!
        assertTrue(line, line.contains("in 31411ms"))
    }

    @Test
    fun `an unknown station adds nothing, so a line reads as it always has`() {
        val line = StationScanCadencePolicy.summarise(listOf(0L, 10_000L), 30_000L)!!
        assertTrue(line, line.endsWith("off the group's channel."))
    }

    @Test
    fun `a stood-down station is named, because that is the state that scans`() {
        val line = StationScanCadencePolicy.summarise(
            listOf(0L, 10_000L), 30_000L, StationStandDownOutcome.STOOD_DOWN
        )!!
        assertTrue(line, line.contains("off the group's channel."))
        assertTrue(line, line.contains("stood down for this bring-up"))
    }

    @Test
    fun `a station left joined is named too, so the other arm is attributable`() {
        val line = StationScanCadencePolicy.summarise(
            listOf(0L, 10_000L), 30_000L, StationStandDownOutcome.JOINED
        )!!
        assertTrue(line, line.contains("stayed joined to its own network"))
    }

    @Test
    fun `no scans stays null whatever the station did`() {
        assertNull(
            StationScanCadencePolicy.summarise(
                emptyList(), 30_000L, StationStandDownOutcome.STOOD_DOWN
            )
        )
    }

    @Test
    fun `every outcome either names the station or says nothing, and only UNKNOWN says nothing`() {
        for (outcome in StationStandDownOutcome.values()) {
            val expectSilence = outcome == StationStandDownOutcome.UNKNOWN
            assertEquals(outcome.name, expectSilence, outcome.scanClause == null)
            assertTrue(outcome.name, outcome.token.isNotBlank())
        }
    }
}
