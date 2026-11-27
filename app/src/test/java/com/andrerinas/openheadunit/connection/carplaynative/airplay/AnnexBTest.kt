package com.andrerinas.openheadunit.connection.carplaynative.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The conversion cases come from xcertplay `ScreenCodecTest`. */
class AnnexBTest {

    private val sps1 = byteArrayOf(0x67, 0x64, 0x00, 0x1f)
    private val sps2 = byteArrayOf(0x67, 0x4d, 0x40)
    private val pps = byteArrayOf(0x68, 0xee.toByte(), 0x3c)

    private fun u16(n: Int) = byteArrayOf((n shr 8).toByte(), n.toByte())

    private fun avcc(lengthByte: Int = 0xff, spsList: List<ByteArray> = listOf(sps1, sps2), ppsList: List<ByteArray> = listOf(pps)): ByteArray =
        byteArrayOf(1, 0x64, 0, 0x1f, lengthByte.toByte(), (0xe0 or spsList.size).toByte()) +
            spsList.fold(ByteArray(0)) { acc, s -> acc + u16(s.size) + s } +
            byteArrayOf(ppsList.size.toByte()) +
            ppsList.fold(ByteArray(0)) { acc, p -> acc + u16(p.size) + p }

    private val start = byteArrayOf(0, 0, 0, 1)

    @Test
    fun twoSpsAndOnePpsGiveStartCodesInOrder() {
        val parsed = AnnexB.parseAvcc(avcc())!!
        assertEquals(4, parsed.nalLengthSize)
        assertArrayEquals(start + sps1 + start + sps2 + start + pps, parsed.parameterSets)
    }

    @Test
    fun theLengthSizeComesFromTheLowTwoBits() {
        assertEquals(2, AnnexB.parseAvcc(avcc(lengthByte = 0xfd))!!.nalLengthSize)
        assertEquals(1, AnnexB.parseAvcc(avcc(lengthByte = 0xfc))!!.nalLengthSize)
    }

    @Test
    fun aNalLengthOfThreeIsRefused() {
        assertNull(AnnexB.parseAvcc(avcc(lengthByte = 0xfe)))
    }

    @Test
    fun aTruncatedOrEmptyRecordIsRefused() {
        val whole = avcc()
        for (cut in listOf(0, 5, 6, 8, 12, whole.size - 1)) assertNull("cut $cut", AnnexB.parseAvcc(whole.copyOf(cut)))
        assertNull(AnnexB.parseAvcc(avcc(spsList = emptyList())))
        assertNull(AnnexB.parseAvcc(avcc(ppsList = emptyList())))
        assertNotNull(AnnexB.parseAvcc(whole))
    }

    @Test
    fun aSetThatRunsPastTheEndIsRefused() {
        val whole = avcc()
        whole[7] = 0x7f
        assertNull(AnnexB.parseAvcc(whole))
    }

    @Test
    fun lengthPrefixesBecomeStartCodes() {
        val first = byteArrayOf(0x40, 0x01)
        val second = byteArrayOf(0x42, 0x01, 0x02)
        val payload = byteArrayOf(0, 0, 0, 2) + first + byteArrayOf(0, 0, 0, 3) + second
        assertArrayEquals(start + first + start + second, AnnexB.convert(payload, 4))
    }

    @Test
    fun lengthSizesOneAndTwoWork() {
        for (lengthSize in listOf(1, 2)) {
            val nal = byteArrayOf(0x26, 1, 0x80.toByte())
            val prefix = ByteArray(lengthSize).also { it[lengthSize - 1] = nal.size.toByte() }
            assertArrayEquals(start + nal, AnnexB.convert(prefix + nal, lengthSize))
        }
    }

    @Test
    fun aLengthSizeOfThreeOrAnyOtherIsRefused() {
        for (size in listOf(0, 3, 5)) assertNull(AnnexB.convert(byteArrayOf(0, 0, 1, 9), size))
    }

    @Test
    fun aLengthPastTheEndIsRefusedWithoutChangingTheInput() {
        val payload = byteArrayOf(0, 0, 0, 5, 0x40, 0x01)
        val copy = payload.copyOf()
        assertNull(AnnexB.convert(payload, 4))
        assertArrayEquals(copy, payload)
    }

    @Test
    fun aPayloadShorterThanItsPrefixIsRefused() {
        assertNull(AnnexB.convert(byteArrayOf(0, 0), 4))
    }

    @Test
    fun aOneByteNalIsNotTakenForAStartCode() {
        val payload = byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 2, 0x65, 1)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 9, 0, 0, 0, 1, 0x65, 1), AnnexB.convert(payload, 4))
    }

    @Test
    fun aNalOfLengthZeroIsSkippedAndAnEmptyPayloadGivesNothing() {
        assertArrayEquals(start + byteArrayOf(9), AnnexB.convert(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1, 9), 4))
        assertEquals(0, AnnexB.convert(ByteArray(0), 4)!!.size)
    }
}
