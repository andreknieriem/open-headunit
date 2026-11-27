package com.andrerinas.openheadunit.connection.carplaynative

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostMacPolicyTest {

    @Test fun aDescriptorMacIsUsedAsRead() {
        val mac = byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0x5f)
        val resolved = HostMacPolicy.resolve(mac)
        assertTrue(resolved.fromDescriptor)
        assertArrayEquals(mac, resolved.mac)
    }

    @Test fun noMacOrAShortOneGivesTheLocallyAdministeredFallback() {
        for (missing in listOf(null, ByteArray(0), ByteArray(5), ByteArray(7))) {
            val resolved = HostMacPolicy.resolve(missing)
            assertFalse(resolved.fromDescriptor)
            assertEquals("02:00:00:00:00:02", HostMacPolicy.text(resolved.mac))
        }
    }

    @Test fun theFallbackIsALocallyAdministeredUnicastAddress() {
        assertEquals(0x02, HostMacPolicy.FALLBACK[0].toInt() and 0x03)
    }

    @Test fun theTextIsLowerCaseWithColons() {
        assertEquals("0a:1b:2c:3d:4e:ff", HostMacPolicy.text(byteArrayOf(0x0a, 0x1b, 0x2c, 0x3d, 0x4e, 0xff.toByte())))
    }

    @Test fun theResolvedMacIsACopy() {
        val mac = ByteArray(6) { 1 }
        HostMacPolicy.resolve(mac).mac[0] = 9
        assertEquals(1, mac[0].toInt())
        HostMacPolicy.FALLBACK[0] = 9
        assertEquals(2, HostMacPolicy.FALLBACK[0].toInt())
    }
}
