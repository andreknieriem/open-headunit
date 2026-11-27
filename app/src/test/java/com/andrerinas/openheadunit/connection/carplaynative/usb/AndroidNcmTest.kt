package com.andrerinas.openheadunit.connection.carplaynative.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock

class AndroidNcmTest {
    private val control = IphoneConfiguration.Interface(1, 0, 0x02, 0x0d, 0)
    private val data = IphoneConfiguration.Interface(
        2, 1, 0x0a, 0, 1,
        listOf(IphoneConfiguration.Endpoint(0x81, isIn = true, isBulk = true), IphoneConfiguration.Endpoint(0x02, isIn = false, isBulk = true)),
    )
    private val model = IphoneConfiguration.Configuration(6, listOf(control, data))

    private val pipe = object : IphoneUsbPipe {
        override fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int) = -1
        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int) = -1
        override fun read(buffer: ByteArray, timeoutMs: Int) = -1
        override fun close() = Unit
    }

    private fun openWith(configurationId: Int): String {
        val live = mock<UsbConfiguration> { on { id } doReturn configurationId; on { interfaceCount } doReturn 0 }
        val device = mock<UsbDevice> { on { configurationCount } doReturn 1; on { getConfiguration(0) } doReturn live }
        val usb = AndroidIphoneUsb.Opened(device, mock<UsbDeviceConnection>(), model, pipe)
        try { AndroidNcm.open(usb) { 0L }; fail(); return "" } catch (e: IphoneUsbException.Protocol) { return e.message!! }
    }

    @Test fun aLiveDescriptorWithoutTheConfigurationIsAProtocolFault() {
        assertEquals("the iPhone has no configuration 6 now", openWith(5))
    }

    @Test fun aLiveConfigurationWithoutTheNcmInterfaceIsAProtocolFault() {
        assertEquals("the iPhone configuration has no NCM interface 1/0 now", openWith(6))
    }
}
