package com.andrerinas.openheadunit.connection.carplaynative.usb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NcmDescriptorsTest {
    private fun interfaceDescriptor(id: Int) = byteArrayOf(9, 4, id.toByte(), 0, 0, 2, 0x0d, 0, 0)
    private fun ethernetFunctional(string: Int) = byteArrayOf(13, 0x24, 0x0f, string.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0)
    private fun stringDescriptor(text: String) = text.toByteArray(Charsets.UTF_16LE).let { byteArrayOf((it.size + 2).toByte(), 3) + it }

    @Test fun theStringIndexComesFromTheControlInterfaceOnly() {
        val raw = interfaceDescriptor(0) + ethernetFunctional(7) + interfaceDescriptor(3) + ethernetFunctional(9)
        assertEquals(9, NcmDescriptors.macStringIndex(raw, 3))
        assertEquals(7, NcmDescriptors.macStringIndex(raw, 0))
        assertNull(NcmDescriptors.macStringIndex(raw, 5))
    }

    @Test fun aZeroIndexOrBrokenLengthGivesNoIndex() {
        assertNull(NcmDescriptors.macStringIndex(interfaceDescriptor(0) + ethernetFunctional(0), 0))
        assertNull(NcmDescriptors.macStringIndex(byteArrayOf(0, 4, 1), 0))
        assertNull(NcmDescriptors.macStringIndex(byteArrayOf(20, 4, 1), 0))
    }

    @Test fun aMacStringHasTwelveHexDigits() {
        val d = stringDescriptor("02AABBCCDD01")
        assertArrayEquals(byteArrayOf(2, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 1), NcmDescriptors.parseMac(d, d.size))
    }

    @Test fun separatorsAreIgnoredAndAShortOrWrongStringIsRefused() {
        val d = stringDescriptor("02:aa:bb:cc:dd:01")
        assertArrayEquals(byteArrayOf(2, 0xaa.toByte(), 0xbb.toByte(), 0xcc.toByte(), 0xdd.toByte(), 1), NcmDescriptors.parseMac(d, d.size))
        val short = stringDescriptor("02AABB")
        assertNull(NcmDescriptors.parseMac(short, short.size))
        val notString = stringDescriptor("02AABBCCDD01").also { it[1] = 4 }
        assertNull(NcmDescriptors.parseMac(notString, notString.size))
        assertNull(NcmDescriptors.parseMac(d, 3))
    }

    @Test fun retryStopsAtTheFirstSuccessAndPausesBetweenAttempts() {
        val pauses = ArrayList<Long>()
        var calls = 0
        assertEquals(3, NcmDescriptors.retry(pause = { pauses.add(it) }) { ++calls == 3 })
        assertEquals(listOf(100L, 100L), pauses)
    }

    @Test fun retryGivesUpAfterFiveAttempts() {
        var calls = 0
        assertNull(NcmDescriptors.retry(pause = {}) { calls++; false })
        assertEquals(5, calls)
    }
}
