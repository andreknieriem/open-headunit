package com.andrerinas.openheadunit.connection.carplaynative.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android side of the NCM link: claims the CDC control and data interfaces on the connection the usbmux link opened,
 * selects data alternate 1 and reads our host MAC. Closing the pipe releases these claims, and the usbmux link owns the connection.
 * API 21 calls only (`UsbConfiguration`); `claimInterface`, `setInterface` and `getRawDescriptors` are older.
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/NcmUsbBridge.kt` `open`.
 */
object AndroidNcm {
    class Opened(val link: NcmUsbLink, val hostMac: ByteArray?)

    fun open(usb: AndroidIphoneUsb.Opened, clock: () -> Long): Opened {
        val function = IphoneConfiguration.ncm(usb.configuration) ?: throw IphoneUsbException.Protocol("the iPhone configuration has no NCM function")
        // The model came from the descriptors; a live device that no longer matches them is a protocol fault.
        val configuration = (0 until usb.device.configurationCount).map(usb.device::getConfiguration).firstOrNull { it.id == usb.configuration.id }
            ?: throw IphoneUsbException.Protocol("the iPhone has no configuration ${usb.configuration.id} now")
        val interfaces = (0 until configuration.interfaceCount).map(configuration::getInterface)
        fun find(model: IphoneConfiguration.Interface): UsbInterface = interfaces.firstOrNull { it.id == model.id && it.alternateSetting == model.alternate }
            ?: throw IphoneUsbException.Protocol("the iPhone configuration has no NCM interface ${model.id}/${model.alternate} now")
        val control = find(function.control)
        val data = find(function.data)
        val bulkIn = endpoint(data, function.bulkIn.address)
        val bulkOut = endpoint(data, function.bulkOut.address)
        val connection = usb.connection
        val claimed = ArrayList<UsbInterface>(2)
        try {
            val hostMac = readHostMac(connection, function.control.id)
            AppLog.i("CarPlayNative: NCM host MAC ${if (hostMac != null) "read from the descriptor" else "not in the descriptor"}")
            if (NcmDescriptors.retry(pause = Thread::sleep) { connection.claimInterface(control, true) } == null) {
                throw IphoneUsbException.DeviceUnavailable("Android could not claim the NCM control interface")
            }
            claimed.add(control)
            if (data.id != control.id) {
                if (NcmDescriptors.retry(pause = Thread::sleep) { connection.claimInterface(data, true) } == null) {
                    throw IphoneUsbException.DeviceUnavailable("Android could not claim the NCM data interface")
                }
                claimed.add(data)
            }
            if (NcmDescriptors.retry(pause = Thread::sleep) { connection.setInterface(data) } == null) {
                throw IphoneUsbException.DeviceUnavailable("Android could not select the NCM data alternate")
            }
            AppLog.i("CarPlayNative: NCM claimed (control ${control.id}, data ${data.id}/${data.alternateSetting})")
            val pipe = Pipe(connection, bulkIn, bulkOut, claimed)
            return Opened(NcmUsbLink(pipe, bulkIn.maxPacketSize.takeIf { it > 0 } ?: DEFAULT_PACKET_SIZE, clock), hostMac)
        } catch (e: Exception) {
            claimed.asReversed().forEach { runCatching { connection.releaseInterface(it) } }
            throw e as? IphoneUsbException ?: IphoneUsbException.DeviceUnavailable("NCM open failed (${e.javaClass.simpleName})", e)
        }
    }

    private fun endpoint(usbInterface: UsbInterface, address: Int): UsbEndpoint =
        (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).firstOrNull { it.address == address }
            ?: throw IphoneUsbException.Protocol("the NCM data interface has no endpoint 0x${address.toString(16)} now")

    private fun readHostMac(connection: UsbDeviceConnection, controlInterfaceId: Int): ByteArray? {
        val index = connection.rawDescriptors?.let { NcmDescriptors.macStringIndex(it, controlInterfaceId) } ?: return null
        val buffer = ByteArray(256)
        val length = connection.controlTransfer(
            UsbConstants.USB_DIR_IN or UsbConstants.USB_TYPE_STANDARD, GET_DESCRIPTOR,
            (NcmDescriptors.STRING_DESCRIPTOR_TYPE shl 8) or index, NcmDescriptors.ENGLISH_US, buffer, buffer.size, CONTROL_TIMEOUT_MS,
        )
        return NcmDescriptors.parseMac(buffer, length)
    }

    private class Pipe(
        private val connection: UsbDeviceConnection,
        private val bulkIn: UsbEndpoint,
        private val bulkOut: UsbEndpoint,
        private val claimed: List<UsbInterface>,
    ) : NcmPipe {
        private val closed = AtomicBoolean(false)

        override fun read(buffer: ByteArray, timeoutMs: Int) = connection.bulkTransfer(bulkIn, buffer, minOf(buffer.size, NcmUsbLink.READ_BYTES), timeoutMs)

        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int) = connection.bulkTransfer(bulkOut, buffer, length, timeoutMs)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            claimed.asReversed().forEach { runCatching { connection.releaseInterface(it) } }
        }
    }

    private const val GET_DESCRIPTOR = 0x06
    private const val CONTROL_TIMEOUT_MS = 1000
    private const val DEFAULT_PACKET_SIZE = 512
}
