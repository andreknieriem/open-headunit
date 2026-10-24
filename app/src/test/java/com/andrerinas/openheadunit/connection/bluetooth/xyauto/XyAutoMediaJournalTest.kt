package com.andrerinas.openheadunit.connection.bluetooth.xyauto

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class XyAutoMediaJournalTest {
    private fun raw(previous: String, boot: String) = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { it.writeInt(1); it.writeUTF(previous); it.writeUTF(boot) }
    }.toByteArray()

    @Test fun roundTripAndBootIdentityValidation() {
        for (value in listOf("", "none")) {
            val record = XyAutoMediaLease.Journal(value, "123")
            assertEquals(record, XyAutoMediaJournal.decode(XyAutoMediaJournal.encode(record)))
        }
        for (boot in listOf(null, "", "abc", "0", "-1", "2147483648")) {
            assertNull(XyAutoMediaJournal.bootId(boot))
        }
    }

    @Test fun malformedRecordCannotSupplyAPropertyValue() {
        val valid = raw("none", "1")
        val invalid = listOf(byteArrayOf(), valid.copyOf(valid.size - 1), valid + byteArrayOf(1),
            ByteArray(65), valid.copyOf().also { it[3] = 2 }, raw("hicar", "1"), raw("auto_wireless", "1"), raw("none", "abc"),
            raw("none", "0"), raw("none", "-1"), raw("none", "01"))
        for (bytes in invalid) {
            try { XyAutoMediaJournal.decode(bytes); fail("Accepted invalid recovery record") }
            catch (_: IOException) { }
        }
    }
}
