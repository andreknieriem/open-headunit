package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlaySignerPolicy.Choice
import org.junit.Assert.assertEquals
import org.junit.Test

class CarPlaySignerPolicyTest {

    @Test
    fun `chip wins then file then none`() {
        assertEquals(Choice.CHIP, CarPlaySignerPolicy.choose(chipConfirmed = true, identityFileValid = true))
        assertEquals(Choice.CHIP, CarPlaySignerPolicy.choose(chipConfirmed = true, identityFileValid = false))
        assertEquals(Choice.FILE, CarPlaySignerPolicy.choose(chipConfirmed = false, identityFileValid = true))
        assertEquals(Choice.NONE, CarPlaySignerPolicy.choose(chipConfirmed = false, identityFileValid = false))
    }

    @Test
    fun `an openable candidate with a valid file gives the file signer`() {
        val candidate = MfiChipLocationPolicy.choose(listOf(
            MfiChipLocationPolicy.Observation("/dev/i2c-1", true, false, MfiChipLocationPolicy.Open.OK),
        ))
        assertEquals(true, candidate is MfiChipLocationPolicy.Verdict.Candidate)
        // No code confirms a chip yet, so the caller passes false.
        assertEquals(Choice.FILE, CarPlaySignerPolicy.choose(chipConfirmed = false, identityFileValid = true))
    }

    @Test
    fun `labels are the log words`() {
        assertEquals(listOf("chip", "file", "none"), Choice.values().map { it.label })
    }
}
