package com.andrerinas.openheadunit.connection.carplaynative

/**
 * Where a unit may expose its MFi chip, in the order head unit vendors use. An openable path proves
 * a bus or a driver, not a chip: only a register read can confirm one.
 */
object MfiChipLocationPolicy {

    const val SOFT_I2C = "/dev/hk_softi2c"
    const val MCU_DIRECTORY = "/sys/bus/platform/drivers/mtc-car/mfi"

    enum class Open { OK, DENIED, FAILED, NOT_TRIED }

    class Observation(val path: String, val exists: Boolean, val isDirectory: Boolean, val open: Open)

    sealed class Verdict {
        /** The path can be reached; the chip behind it is unknown. */
        data class Candidate(val path: String) : Verdict()
        data class None(val reason: String) : Verdict()
    }

    const val REASON_ABSENT = "absent"
    const val REASON_DENIED = "permission denied"
    const val REASON_OPEN_FAILED = "open failed"

    fun candidates(i2cBuses: List<Int>): List<String> =
        i2cBuses.sorted().map { "/dev/i2c-$it" } + SOFT_I2C + MCU_DIRECTORY

    /** The first path that exists and opens, or a directory that exists; else the most telling reason. */
    fun choose(observations: List<Observation>): Verdict {
        observations.firstOrNull { it.exists && (it.isDirectory || it.open == Open.OK) }
            ?.let { return Verdict.Candidate(it.path) }
        val present = observations.filter { it.exists }
        return when {
            present.any { it.open == Open.DENIED } -> Verdict.None(REASON_DENIED)
            present.any { it.open == Open.FAILED } -> Verdict.None(REASON_OPEN_FAILED)
            else -> Verdict.None(REASON_ABSENT)
        }
    }
}
