package com.andrerinas.openheadunit.connection.carplaynative

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.connection.carplaynative.lockdown.LockdownClient
import com.andrerinas.openheadunit.connection.carplaynative.lockdown.PairRecordStore
import com.andrerinas.openheadunit.connection.carplaynative.usb.AndroidIphoneUsb
import com.andrerinas.openheadunit.connection.carplaynative.usb.AndroidNcm
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneConfiguration
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneModeAnswer
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbException
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPipe
import com.andrerinas.openheadunit.connection.carplaynative.usb.MuxConnector
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunProbePacket
import com.andrerinas.openheadunit.utils.AppLog
import java.io.File
import java.net.Inet6Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * The Android side of the session: the `UsbDevice` calls, the lockdown client and the tunnel address. It holds no
 * rule, so the JVM tests cover the session through fakes of [IphoneUsbPort] and [CarkitOpener]. API 21 and later.
 */
class AndroidIphoneDevice(val usb: UsbDevice) : IphoneDevice {
    override val id: String get() = usb.deviceName
    override val vendorId: Int get() = usb.vendorId
    override val productId: Int get() = usb.productId
    override val productName: String? get() = usb.productName
    override val label: String get() = usb.productName ?: "iPhone"
}

/** [ask] is the launcher's own permission request, so one rule dedups every ask. */
class AndroidIphoneUsbPort(
    private val manager: UsbManager,
    private val ask: (UsbDevice) -> Unit,
) : IphoneUsbPort {

    private fun raw(device: IphoneDevice) = (device as AndroidIphoneDevice).usb

    override fun hasPermission(device: IphoneDevice) = manager.hasPermission(raw(device))

    override fun isPresent(device: IphoneDevice) = manager.deviceList?.values?.any { it.deviceName == raw(device).deviceName } == true

    override fun configurationCount(device: IphoneDevice) = raw(device).configurationCount

    override fun askPermission(device: IphoneDevice) = ask(raw(device))

    override fun requestMode(device: IphoneDevice): IphoneModeAnswer = AndroidIphoneUsb.requestMode(manager, raw(device))

    /** `getSerialNumber` throws on API 29 and later without permission, so this runs only after the grant. */
    override fun serial(device: IphoneDevice): String? = try { raw(device).serialNumber } catch (_: SecurityException) { null }

    override fun open(device: IphoneDevice): OpenedIphone {
        val opened = AndroidIphoneUsb.open(manager, raw(device))
        return object : OpenedIphone {
            override val pipe: IphoneUsbPipe get() = opened.pipe
            override val configurationId: Int get() = opened.configuration.id
            override val usbmuxEndpoints: Pair<Int, Int>
                get() = IphoneConfiguration.usbmuxInterface(opened.configuration)?.let { IphoneConfiguration.usbmuxEndpoints(it) }
                    ?.let { it.first.address to it.second.address } ?: (0 to 0)
            override val ncmControlInterface: Int
                get() = IphoneConfiguration.ncm(opened.configuration)?.control?.id
                    ?: throw IphoneUsbException.Protocol("the iPhone configuration has no NCM function")

            override fun openNcm(): NcmOpened = AndroidNcm.open(opened) { SystemClock.elapsedRealtime() }.let { NcmOpened(it.link, it.hostMac) }

            override fun close() = opened.close()
        }
    }
}

/** Runs the lockdown client and tells a saved record from a new one by the host id it ends with. */
class LockdownCarkitOpener(private val pairDirectory: File) : CarkitOpener {
    override fun open(connector: MuxConnector, serial: String, label: String, isCancelled: () -> Boolean): Carkit {
        val store = PairRecordStore(pairDirectory)
        val before = store.load(serial)?.hostId
        val stream = LockdownClient(connector, store, { SystemClock.elapsedRealtime() }, Thread::sleep).open(serial, label, isCancelled)
        return Carkit(stream, before != null && store.load(serial)?.hostId == before)
    }
}

/** The address of the tunnel as the kernel holds it, with its scope. The interface shows up a moment after the tunnel starts. */
object TunnelAddress {
    fun find(attempts: Int = 20, pauseMs: Long = 100L): InetAddress? {
        repeat(attempts) {
            val found = try {
                NetworkInterface.getNetworkInterfaces()?.toList()?.flatMap { it.inetAddresses.toList() }
                    ?.firstOrNull { it is Inet6Address && it.address.contentEquals(TunProbePacket.TUN_ADDRESS) }
            } catch (e: SocketException) {
                AppLog.w("CarPlayNative: the interface list could not be read (${e.javaClass.simpleName})")
                null
            }
            if (found != null) return found
            try { Thread.sleep(pauseMs) } catch (_: InterruptedException) { return null }
        }
        return null
    }
}

/** Builds the production pieces of native CarPlay from a context. */
object CarPlayNativeWiring {
    /** The user preflight for the tunnel, over the real VPN seam. Blocking: run it off the main thread. */
    fun tunnelCheck(context: Context): CarPlayTunnelCheck =
        CarPlayTunnelCheck(VpnTunnelControl(context), ServerSocketProbeListeners, { SystemClock.elapsedRealtime() }, aaSessionLive(context))

    /** The Android Auto test the service gives the manager, for the callers that have no service at hand. */
    fun aaSessionLive(context: Context): () -> Boolean {
        val comm = com.andrerinas.openheadunit.App.provide(context).commManager
        return { comm.isConnected || comm.connectionState.value is com.andrerinas.openheadunit.connection.CommManager.ConnectionState.Connecting }
    }

    /** The setting as every reader takes it: off while this unit cannot run native CarPlay. Cheap enough for the main thread. */
    fun enabled(context: Context): Boolean {
        if (!com.andrerinas.openheadunit.App.provide(context).settings.carPlayNativeEnabled) return false
        val sdk = android.os.Build.VERSION.SDK_INT
        // A file stat, not a parse: the session parses the identity when it starts.
        val signer = CarPlaySignerPolicy.choose(chipConfirmed = false, linkReachable = false, identityFileValid = CarPlayIdentityFile.present(context, sdk))
        val unavailable = CarPlayNativeAvailability.check(sdk, com.andrerinas.openheadunit.utils.VpnControl.isCarPlayTunAvailable(), signer)
        return CarPlayNativeAvailability.effectiveEnabled(true, unavailable)
    }
}
