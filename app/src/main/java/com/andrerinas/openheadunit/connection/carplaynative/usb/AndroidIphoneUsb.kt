package com.andrerinas.openheadunit.connection.carplaynative.usb

import android.hardware.usb.UsbConfiguration
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.utils.AppLog
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Android side of the iPhone USB link: the descriptor model, the mode request and the claim of the usbmux interface.
 * It holds no rule, the rules sit in [IphoneUsbPolicy] and [IphoneConfiguration]. Every call here is API 21 (configurations, `setConfiguration`).
 * Follows xcertplay (https://github.com/shilapi/xcertplay) `transport/IphoneUsbHost.kt` and LIVI (https://github.com/f-io/LIVI) crate iap2-usbmux `usb_pipe.rs`.
 */
object AndroidIphoneUsb {
    /** The configuration the iPhone starts in. Closing the link sets it back, as LIVI does. */
    const val DEFAULT_CONFIGURATION = 4

    /** Pipe over a connection that only does control transfers: the mode request runs on it. */
    private class ControlOnly(private val connection: UsbDeviceConnection) : IphoneUsbPipe {
        override fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int) =
            connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)
        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int) = -1
        override fun read(buffer: ByteArray, timeoutMs: Int) = -1
        override fun close() = connection.close()
    }

    fun model(device: UsbDevice): List<IphoneConfiguration.Configuration> =
        (0 until device.configurationCount).map { model(device.getConfiguration(it)) }

    private fun model(configuration: UsbConfiguration) = IphoneConfiguration.Configuration(
        configuration.id,
        (0 until configuration.interfaceCount).map { index ->
            val usbInterface = configuration.getInterface(index)
            IphoneConfiguration.Interface(
                usbInterface.id, usbInterface.alternateSetting, usbInterface.interfaceClass,
                usbInterface.interfaceSubclass, usbInterface.interfaceProtocol,
                (0 until usbInterface.endpointCount).map { e ->
                    val endpoint = usbInterface.getEndpoint(e)
                    IphoneConfiguration.Endpoint(
                        endpoint.address, endpoint.direction == UsbConstants.USB_DIR_IN,
                        endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK, endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT,
                    )
                },
            )
        },
    )

    /** Sends the mode request on a short connection and closes it. After an accepted answer the iPhone enumerates again. */
    fun requestMode(manager: UsbManager, device: UsbDevice): IphoneModeAnswer {
        val connection = manager.openDevice(device) ?: throw IphoneUsbException.DeviceUnavailable("the iPhone could not be opened")
        val pipe = ControlOnly(connection)
        try {
            return IphoneUsbLink.requestMode(pipe)
        } finally {
            pipe.close()
        }
    }

    /** An iPhone with its CarPlay configuration set and its usbmux interface claimed. [close] undoes both. */
    class Opened internal constructor(
        val device: UsbDevice,
        val connection: UsbDeviceConnection,
        val configuration: IphoneConfiguration.Configuration,
        val pipe: IphoneUsbPipe,
    ) {
        fun close() = pipe.close()
    }

    /** Sets the CarPlay configuration, claims usbmux and returns the open pipe. Every step that fails closes the connection. */
    fun open(manager: UsbManager, device: UsbDevice): Opened {
        val connection = manager.openDevice(device) ?: throw IphoneUsbException.DeviceUnavailable("the iPhone could not be opened")
        var claimed: UsbInterface? = null
        try {
            val configuration = IphoneConfiguration.find(model(device))
                ?: throw IphoneUsbException.Protocol("the iPhone shows no CarPlay configuration")
            val usbConfiguration = (0 until device.configurationCount).map(device::getConfiguration).first { it.id == configuration.id }
            if (!connection.setConfiguration(usbConfiguration)) AppLog.w("CarPlayNative: setConfiguration ${configuration.id} failed; claiming anyway")
            val muxModel = IphoneConfiguration.usbmuxInterface(configuration) ?: throw IphoneUsbException.Protocol("no usbmux interface")
            val (outModel, inModel) = IphoneConfiguration.usbmuxEndpoints(muxModel) ?: throw IphoneUsbException.Protocol("no usbmux bulk pair")
            val usbInterface = (0 until usbConfiguration.interfaceCount).map(usbConfiguration::getInterface)
                .first { it.id == muxModel.id && it.alternateSetting == muxModel.alternate }
            val out = endpoint(usbInterface, outModel.address)
            val input = endpoint(usbInterface, inModel.address)
            if (!connection.claimInterface(usbInterface, true)) throw IphoneUsbException.DeviceUnavailable("Android could not claim the usbmux interface")
            claimed = usbInterface
            AppLog.i("CarPlayNative: usbmux claimed (configuration ${configuration.id}, ${IphoneConfiguration.describe(configuration)})")
            val restore = (0 until device.configurationCount).map(device::getConfiguration).firstOrNull { it.id == DEFAULT_CONFIGURATION }
            return Opened(device, connection, configuration, AndroidPipe(connection, usbInterface, out, input, restore))
        } catch (e: Exception) {
            claimed?.let { runCatching { connection.releaseInterface(it) } }
            connection.close()
            throw e as? IphoneUsbException ?: IphoneUsbException.DeviceUnavailable("the iPhone USB open failed (${e.javaClass.simpleName})", e)
        }
    }

    private fun endpoint(usbInterface: UsbInterface, address: Int): UsbEndpoint =
        (0 until usbInterface.endpointCount).map(usbInterface::getEndpoint).first { it.address == address }

    private class AndroidPipe(
        private val connection: UsbDeviceConnection,
        private val usbInterface: UsbInterface,
        private val out: UsbEndpoint,
        private val input: UsbEndpoint,
        private val restore: UsbConfiguration?,
    ) : IphoneUsbPipe {
        private val closed = AtomicBoolean(false)

        override fun control(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int, timeoutMs: Int) =
            connection.controlTransfer(requestType, request, value, index, buffer, length, timeoutMs)

        override fun write(buffer: ByteArray, length: Int, timeoutMs: Int) = connection.bulkTransfer(out, buffer, length, timeoutMs)

        override fun read(buffer: ByteArray, timeoutMs: Int) = connection.bulkTransfer(input, buffer, buffer.size, timeoutMs)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            runCatching { connection.releaseInterface(usbInterface) }
            // The iPhone charges and syncs in its own configuration, so the link puts it back.
            if (restore != null) runCatching { connection.setConfiguration(restore) }
            connection.close()
        }
    }
}
