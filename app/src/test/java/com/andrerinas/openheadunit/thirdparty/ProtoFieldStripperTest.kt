package com.andrerinas.openheadunit.thirdparty

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProtoFieldStripperTest {

    @Test
    fun removesEveryOccurrenceOfTheFieldAndKeepsTheRestByteForByte() {
        val keep1 = byteArrayOf(0x0A, 0x02, 'h'.code.toByte(), 'i'.code.toByte())          // field 1, length-delimited
        val drop = byteArrayOf(0x22, 0x03, 1, 2, 3)                                         // field 4, length-delimited
        val keep2 = byteArrayOf(0x30, 0xC8.toByte(), 0x01)                                  // field 6, varint 200
        val fixed32 = byteArrayOf(0x3D, 1, 2, 3, 4)                                         // field 7, wire type 5
        val fixed64 = byteArrayOf(0x41, 1, 2, 3, 4, 5, 6, 7, 8)                             // field 8, wire type 1
        val dropVarint = byteArrayOf(0x20, 0x05)                                            // field 4 as a varint
        val payload = keep1 + drop + keep2 + drop + fixed32 + dropVarint + fixed64
        assertArrayEquals(keep1 + keep2 + fixed32 + fixed64, ProtoFieldStripper.removeField(payload, 4))
    }

    @Test
    fun emptyAndFieldFreePayloadsPassThrough() {
        assertArrayEquals(ByteArray(0), ProtoFieldStripper.removeField(ByteArray(0), 4))
        val p = byteArrayOf(0x0A, 0x01, 65)
        assertArrayEquals(p, ProtoFieldStripper.removeField(p, 4))
    }

    @Test
    fun messagesThatDoNotParseGiveNull() {
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x22, 0x05, 1), 4))            // length runs past the end
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x80.toByte()), 4))            // truncated key
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x0B, 1), 4))                  // wire type 3 (group) is not supported
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x0D, 1, 2), 4))               // fixed32 cut short
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x09, 1, 2, 3), 4))            // fixed64 cut short
        assertNull(ProtoFieldStripper.removeField(byteArrayOf(0x22, 0xFF.toByte()), 4))      // truncated length
    }
}
