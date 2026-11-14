package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.MfiChipLocationPolicy.Open
import com.andrerinas.openheadunit.connection.carplaynative.MfiChipLocationPolicy.Observation
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.io.FileInputStream
import java.io.IOException

/** Looks at the candidate paths and opens each one for read at once. It reads no register. */
object MfiChipProbe {

    fun probe(
        dev: File = File("/dev"),
        softI2c: File = File(MfiChipLocationPolicy.SOFT_I2C),
        mcu: File = File(MfiChipLocationPolicy.MCU_DIRECTORY),
    ): MfiChipLocationPolicy.Verdict {
        val buses = dev.list()?.mapNotNull { Regex("i2c-(\\d+)").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
            ?: emptyList()
        val observations = MfiChipLocationPolicy.candidates(buses).map { path ->
            val file = when (path) {
                MfiChipLocationPolicy.SOFT_I2C -> softI2c
                MfiChipLocationPolicy.MCU_DIRECTORY -> mcu
                else -> File(dev, path.removePrefix("/dev/"))
            }
            observe(path, file)
        }
        val verdict = MfiChipLocationPolicy.choose(observations)
        for (o in observations.filter { it.exists && it.isDirectory }) {
            AppLog.i("CarPlayNative: MFi candidate ${o.path} is a directory: " +
                (File(o.path).list()?.sorted()?.joinToString(",") ?: ""))
        }
        when (verdict) {
            is MfiChipLocationPolicy.Verdict.Candidate ->
                if (observations.firstOrNull { it.path == verdict.path }?.isDirectory != true) {
                    AppLog.i("CarPlayNative: MFi candidate ${verdict.path} openable, chip unknown")
                }
            is MfiChipLocationPolicy.Verdict.None -> AppLog.i("CarPlayNative: no MFi candidate (${verdict.reason})")
        }
        return verdict
    }

    private fun observe(path: String, file: File): Observation {
        if (!file.exists()) return Observation(path, false, false, Open.NOT_TRIED)
        // A directory is listed by its caller and never opened.
        if (file.isDirectory) return Observation(path, true, true, Open.NOT_TRIED)
        val open = try {
            FileInputStream(file).close()
            Open.OK
        } catch (e: SecurityException) {
            Open.DENIED
        } catch (e: IOException) {
            if (e.message?.contains("denied", ignoreCase = true) == true) Open.DENIED else Open.FAILED
        }
        return Observation(path, true, false, open)
    }
}
