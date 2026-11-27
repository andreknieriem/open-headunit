package com.andrerinas.openheadunit.connection.carplaynative.codec

import com.andrerinas.openheadunit.connection.carplaynative.crypto.CarPlayCrypto
import com.andrerinas.openheadunit.connection.carplaynative.crypto.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cases come from LIVI `tlv8.rs` and its `ts_vectors.json` entry `tlv8`. */
class Tlv8Test {

    private fun item(type: Int, vararg bytes: Int) = Tlv8.Item(type, ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun theLiviTlv8VectorMatches() {
        val encoded = Tlv8.encode(
            listOf(
                item(6, 1),
                Tlv8.Item(3, ByteArray(300) { 7 }),
                Tlv8.Item(3, "x".toByteArray()),
                item(1),
            ),
        )
        assertEquals(314, encoded.size)
        assertEquals("967d480c85875da5c4d93f5069885682a34715f8b85e57c9da3a7d5924fe222c", CarPlayCrypto.sha256(encoded).toHex())
    }

    @Test
    fun aValueOver255BytesSplitsAndJoins() {
        val long = ByteArray(600) { 7 }
        val buf = Tlv8.encode(listOf(item(6, 1), Tlv8.Item(3, long), item(1, 'i'.code, 'd'.code)))
        assertArrayEquals(byteArrayOf(6, 1, 1), buf.copyOfRange(0, 3))
        assertArrayEquals(byteArrayOf(3, 255.toByte()), buf.copyOfRange(3, 5))
        val map = Tlv8.decode(buf)
        assertArrayEquals(byteArrayOf(1), map[6])
        assertArrayEquals(long, map[3])
        assertArrayEquals("id".toByteArray(), map[1])
    }

    @Test
    fun aValueOfExactly255BytesIsOneItemAndAnEmptyTailItemFollowsNothing() {
        val exact = ByteArray(255) { 9 }
        val buf = Tlv8.encode(listOf(Tlv8.Item(3, exact)))
        assertEquals(257, buf.size)
        assertArrayEquals(exact, Tlv8.decode(buf)[3])
    }

    @Test
    fun adjacentItemsOfOneTypeGetASeparator() {
        val buf = Tlv8.encode(listOf(item(1, 'a'.code), item(1, 'b'.code)))
        assertArrayEquals(byteArrayOf(1, 1, 'a'.code.toByte(), 0xff.toByte(), 0, 1, 1, 'b'.code.toByte()), buf)
        assertArrayEquals("b".toByteArray(), Tlv8.decode(buf)[1])
    }

    @Test
    fun anEmptyValueIsOneEmptyItem() {
        assertArrayEquals(byteArrayOf(5, 0), Tlv8.encode(listOf(item(5))))
    }

    @Test
    fun aTruncatedItemKeepsWhatArrived() {
        assertArrayEquals(byteArrayOf(9, 9), Tlv8.decode(byteArrayOf(3, 4, 9, 9))[3])
        assertTrue(Tlv8.decode(byteArrayOf(3)).isEmpty())
    }
}
