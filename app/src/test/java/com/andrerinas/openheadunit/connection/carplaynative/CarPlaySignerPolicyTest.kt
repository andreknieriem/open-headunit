package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySignerPolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlaySignerPolicyTest {

    private fun choose(chip: Boolean, link: Boolean, file: Boolean) =
        CarPlaySignerPolicy.choose(chipConfirmed = chip, linkReachable = link, identityFileValid = file)

    @Test
    fun `chip wins then link then file then none`() {
        assertEquals(Choice.CHIP, choose(chip = true, link = true, file = true))
        assertEquals(Choice.CHIP, choose(chip = true, link = false, file = false))
        assertEquals(Choice.LINK, choose(chip = false, link = true, file = true))
        assertEquals(Choice.LINK, choose(chip = false, link = true, file = false))
        assertEquals(Choice.FILE, choose(chip = false, link = false, file = true))
        assertEquals(Choice.NONE, choose(chip = false, link = false, file = false))
    }

    @Test
    fun `an openable candidate with a valid file gives the file signer`() {
        val candidate = MfiChipLocationPolicy.choose(listOf(
            MfiChipLocationPolicy.Observation("/dev/i2c-1", true, false, MfiChipLocationPolicy.Open.OK),
        ))
        assertEquals(true, candidate is MfiChipLocationPolicy.Verdict.Candidate)
        // An openable path confirms no chip, so the caller passes false.
        assertEquals(Choice.FILE, choose(chip = false, link = false, file = true))
    }

    @Test
    fun `labels are the log words`() {
        assertEquals(listOf("chip", "link", "file", "none"), Choice.values().map { it.label })
    }
}
