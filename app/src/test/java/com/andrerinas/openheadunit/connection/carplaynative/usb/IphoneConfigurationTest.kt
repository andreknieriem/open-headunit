package com.andrerinas.openheadunit.connection.carplaynative.usb

import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneConfiguration.Configuration
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneConfiguration.Endpoint
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneConfiguration.Interface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IphoneConfigurationTest {

    private val bulkOut = Endpoint(0x04, isIn = false, isBulk = true)
    private val bulkIn = Endpoint(0x85, isIn = true, isBulk = true)
    private val usbmux = Interface(1, 0, 0xff, 0xfe, 0x02, listOf(bulkOut, bulkIn))
    private val ncmControl = Interface(2, 0, 0x02, 0x0d, 0x00, listOf(Endpoint(0x87, isIn = true, isBulk = false, isInterrupt = true)))
    private val ncmDataIdle = Interface(3, 0, 0x0a, 0x00, 0x01)
    private val ncmData = Interface(3, 1, 0x0a, 0x00, 0x01, listOf(Endpoint(0x86, isIn = true, isBulk = true), Endpoint(0x05, isIn = false, isBulk = true)))
    private val appleEthernet = Interface(4, 0, 0xff, 0xfd, 0x01, listOf(Endpoint(0x88, isIn = true, isBulk = true), Endpoint(0x06, isIn = false, isBulk = true)))
    private val ptp = Interface(0, 0, 0x06, 0x01, 0x01)

    @Test
    fun `a configuration with Apple Ethernet is chosen over one without, whatever the ids`() {
        val plain = Configuration(5, listOf(usbmux, ncmControl, ncmDataIdle, ncmData))
        val full = Configuration(6, listOf(usbmux, ncmControl, ncmDataIdle, ncmData, appleEthernet))
        assertSame(full, IphoneConfiguration.find(listOf(plain, full)))
        assertSame(full, IphoneConfiguration.find(listOf(full, plain)))
    }

    @Test
    fun `without Apple Ethernet the usbmux and NCM configuration still counts`() {
        val plain = Configuration(5, listOf(usbmux, ncmControl, ncmDataIdle, ncmData))
        val other = Configuration(1, listOf(ptp, usbmux))
        assertSame(plain, IphoneConfiguration.find(listOf(other, plain)))
    }

    @Test
    fun `no configuration with usbmux and NCM gives null`() {
        assertNull(IphoneConfiguration.find(listOf(Configuration(1, listOf(ptp, usbmux)), Configuration(2, listOf(ncmControl, ncmData)))))
        assertNull(IphoneConfiguration.find(emptyList()))
    }

    @Test
    fun `the usbmux endpoints prefer 0x04 and 0x85`() {
        val extra = Interface(1, 0, 0xff, 0xfe, 0x02, listOf(Endpoint(0x02, false, true), bulkOut, bulkIn, Endpoint(0x81, true, true)))
        val (out, input) = IphoneConfiguration.usbmuxEndpoints(extra)!!
        assertEquals(0x04, out.address)
        assertEquals(0x85, input.address)
    }

    @Test
    fun `a lone bulk pair on other addresses is taken and an ambiguous one is not`() {
        val lone = Interface(1, 0, 0xff, 0xfe, 0x02, listOf(Endpoint(0x01, false, true), Endpoint(0x82, true, true)))
        assertEquals(0x01, IphoneConfiguration.usbmuxEndpoints(lone)!!.first.address)
        val two = Interface(1, 0, 0xff, 0xfe, 0x02, listOf(Endpoint(0x01, false, true), Endpoint(0x02, false, true), Endpoint(0x82, true, true)))
        assertNull(IphoneConfiguration.usbmuxEndpoints(two))
    }

    @Test
    fun `the NCM pick takes 02 0D and the class 0A alternate 1, never Apple Ethernet`() {
        val function = IphoneConfiguration.ncm(Configuration(6, listOf(usbmux, ncmControl, ncmDataIdle, appleEthernet, ncmData)))!!
        assertSame(ncmControl, function.control)
        assertSame(ncmData, function.data)
        assertEquals(1, function.data.alternate)
        assertEquals(0x86, function.bulkIn.address)
        assertEquals(0x05, function.bulkOut.address)
        assertEquals(0x87, function.statusIn!!.address)
        assertNotNull(function)
    }

    @Test
    fun `Apple Ethernet alone is no NCM function even with bulk endpoints`() {
        assertNull(IphoneConfiguration.ncm(Configuration(6, listOf(usbmux, appleEthernet))))
        assertNull(IphoneConfiguration.ncm(Configuration(6, listOf(usbmux, ncmControl, appleEthernet))))
    }

    @Test
    fun `a data interface with no bulk pair is skipped`() {
        assertNull(IphoneConfiguration.ncm(Configuration(6, listOf(ncmControl, ncmDataIdle))))
    }

    @Test
    fun `describe lists id, alternate, class and endpoint count`() {
        assertEquals("1/0:ff.fe.2x2,2/0:2.d.0x1", IphoneConfiguration.describe(Configuration(1, listOf(usbmux, ncmControl))))
    }
}
