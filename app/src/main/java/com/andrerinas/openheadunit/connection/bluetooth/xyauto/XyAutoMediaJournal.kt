package com.andrerinas.openheadunit.connection.bluetooth.xyauto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/** Bounded private recovery record. Invalid data must never become a firmware property value. */
internal object XyAutoMediaJournal {
    const val MAX_BYTES = 64

    fun bootId(value: String?): String? = value?.toIntOrNull()?.takeIf { it > 0 }?.toString()

    fun encode(record: XyAutoMediaLease.Journal): ByteArray {
        validate(record)
        return ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { it.writeInt(1); it.writeUTF(record.previous); it.writeUTF(record.boot) }
        }.toByteArray()
    }

    fun decode(bytes: ByteArray): XyAutoMediaLease.Journal {
        if (bytes.size > MAX_BYTES) throw IOException("Oversized XYAuto recovery record")
        return DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            if (input.readInt() != 1) throw IOException("Unknown XYAuto recovery format")
            val record = XyAutoMediaLease.Journal(input.readUTF(), input.readUTF())
            validate(record)
            if (input.read() != -1) throw IOException("Trailing XYAuto recovery data")
            record
        }
    }

    private fun validate(record: XyAutoMediaLease.Journal) {
        if (record.previous !in setOf("", "none") || bootId(record.boot) != record.boot) {
            throw IOException("Invalid XYAuto recovery record")
        }
    }
}
