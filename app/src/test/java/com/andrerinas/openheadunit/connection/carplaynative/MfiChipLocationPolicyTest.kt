package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.MfiChipLocationPolicy.Observation
import com.andrerinas.openheadunit.connection.carplaynative.MfiChipLocationPolicy.Open
import com.andrerinas.openheadunit.connection.carplaynative.MfiChipLocationPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MfiChipLocationPolicyTest {

    private fun obs(path: String, exists: Boolean = true, dir: Boolean = false, open: Open = Open.OK) =
        Observation(path, exists, dir, open)

    @Test
    fun `candidates run bus nodes in bus order then the soft bus then the MCU path`() {
        assertEquals(
            listOf("/dev/i2c-0", "/dev/i2c-2", "/dev/i2c-10", MfiChipLocationPolicy.SOFT_I2C, MfiChipLocationPolicy.MCU_DIRECTORY),
            MfiChipLocationPolicy.candidates(listOf(10, 0, 2)),
        )
    }

    @Test
    fun `the first openable path is the candidate`() {
        val v = MfiChipLocationPolicy.choose(listOf(
            obs("/dev/i2c-0", open = Open.DENIED), obs("/dev/i2c-1"), obs(MfiChipLocationPolicy.SOFT_I2C),
        ))
        assertEquals(Verdict.Candidate("/dev/i2c-1"), v)
    }

    @Test
    fun `an openable bus node is a candidate and never more`() {
        val v = MfiChipLocationPolicy.choose(listOf(obs("/dev/i2c-3")))
        assertTrue(v is Verdict.Candidate)
    }

    @Test
    fun `a directory MCU path is a candidate without an open`() {
        val v = MfiChipLocationPolicy.choose(listOf(
            obs("/dev/i2c-0", exists = false, open = Open.NOT_TRIED),
            obs(MfiChipLocationPolicy.MCU_DIRECTORY, dir = true, open = Open.NOT_TRIED),
        ))
        assertEquals(Verdict.Candidate(MfiChipLocationPolicy.MCU_DIRECTORY), v)
    }

    @Test
    fun `each failure gives its own reason`() {
        assertEquals(Verdict.None("absent"), MfiChipLocationPolicy.choose(listOf(obs("/dev/i2c-0", exists = false, open = Open.NOT_TRIED))))
        assertEquals(Verdict.None("absent"), MfiChipLocationPolicy.choose(emptyList()))
        assertEquals(Verdict.None("permission denied"), MfiChipLocationPolicy.choose(listOf(obs("/dev/i2c-0", open = Open.DENIED))))
        assertEquals(Verdict.None("open failed"), MfiChipLocationPolicy.choose(listOf(obs("/dev/i2c-0", open = Open.FAILED))))
        assertEquals(
            Verdict.None("permission denied"),
            MfiChipLocationPolicy.choose(listOf(obs("/dev/i2c-0", open = Open.FAILED), obs("/dev/i2c-1", open = Open.DENIED))),
        )
    }
}
