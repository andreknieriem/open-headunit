package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

/** Expected bytes come from the DER rules and well-known object identifiers, not from this code. */
class DerWriterTest {

    @Test
    fun `object identifiers use base 128 groups`() {
        assertEquals("06092a864886f70d01010b", Der.oid("1.2.840.113549.1.1.11").toHex())
        assertEquals("0603551d13", Der.oid("2.5.29.19").toHex())
    }

    @Test
    fun `lengths use the short form to 127 and the shortest long form above`() {
        assertEquals("0400", Der.octetString(ByteArray(0)).toHex())
        assertEquals("047f" + "00".repeat(127), Der.octetString(ByteArray(127)).toHex())
        assertEquals("048180" + "00".repeat(128), Der.octetString(ByteArray(128)).toHex())
        assertEquals("04820100" + "00".repeat(256), Der.octetString(ByteArray(256)).toHex())
    }

    @Test
    fun `integers keep a leading zero for a high bit`() {
        assertEquals("020100", Der.integer(BigInteger.ZERO).toHex())
        assertEquals("02020080", Der.integer(BigInteger.valueOf(128)).toHex())
        assertEquals("020102", Der.integer(BigInteger.valueOf(2)).toHex())
    }

    @Test
    fun `the small types`() {
        assertEquals("0101ff", Der.boolean(true).toHex())
        assertEquals("0500", Der.nullValue().toHex())
        assertEquals("a003020102", Der.explicit(0, Der.integer(BigInteger.valueOf(2))).toHex())
        assertEquals("030205a0", Der.bitString(byteArrayOf(0xa0.toByte()), unusedBits = 5).toHex())
        assertEquals("3000", Der.sequence().toHex())
        assertEquals("3103020100", Der.set(Der.integer(BigInteger.ZERO)).toHex())
    }
}
