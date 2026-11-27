package com.andrerinas.openheadunit.connection.carplaynative.airplay

import com.andrerinas.openheadunit.connection.carplaynative.codec.PlistValue
import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class StreamKeysTest {

    private val shared = ByteArray(32) { (it * 3).toByte() }

    private fun hkdf(salt: String, info: String) =
        CarPlayCrypto.hkdfSha512(shared, salt.toByteArray(), info.toByteArray())

    @Test
    fun theDataStreamKeyUsesTheConnectionIdInItsSalt() {
        assertArrayEquals(
            hkdf("DataStream-Salt12345", "DataStream-Output-Encryption-Key"),
            StreamKeys.dataStream(shared, "12345"),
        )
        assertFalse(StreamKeys.dataStream(shared, "12345").contentEquals(StreamKeys.dataStream(shared, "12346")))
    }

    @Test
    fun theEventKeysUseTheirOwnLabelsAndTheReadKeyIsNotTheWriteKey() {
        val keys = StreamKeys.events(shared)
        assertArrayEquals(hkdf("Events-Salt", "Events-Read-Encryption-Key"), keys.readKey)
        assertArrayEquals(hkdf("Events-Salt", "Events-Write-Encryption-Key"), keys.writeKey)
        assertFalse(keys.readKey.contentEquals(keys.writeKey))
    }

    @Test
    fun theEventKeysDifferFromTheControlKeys() {
        val control = hkdf("Control-Salt", "Control-Read-Encryption-Key")
        assertFalse(control.contentEquals(StreamKeys.events(shared).readKey))
    }

    @Test
    fun aConnectionIdReadsAsAnUnsignedDecimal() {
        assertEquals("0", StreamKeys.connectionIdText(PlistValue.Integer(0)))
        assertEquals("18446744073709551615", StreamKeys.connectionIdText(PlistValue.Integer(-1)))
        assertEquals("9223372036854775808", StreamKeys.connectionIdText(PlistValue.Integer(Long.MIN_VALUE)))
        assertEquals("42", StreamKeys.connectionIdText(PlistValue.Real(42.0)))
        assertEquals("abc", StreamKeys.connectionIdText(PlistValue.Text("abc")))
        assertNull(StreamKeys.connectionIdText(PlistValue.Real(1.5)))
        assertNull(StreamKeys.connectionIdText(PlistValue.Bool(true)))
        assertNull(StreamKeys.connectionIdText(null))
    }
}
