package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.content.pm.PackageManager
import android.os.Build
import com.andrerinas.openheadunit.utils.Settings
import com.andrerinas.openheadunit.utils.Utils
import java.util.Locale

class UsbDeviceCompat(val wrappedDevice: UsbDevice) {

    val deviceName: String
        get() = wrappedDevice.deviceName

    val vendorId: Int
        get() = wrappedDevice.vendorId

    val productId: Int
        get() = wrappedDevice.productId

    val uniqueName: String
        get() = getUniqueName(wrappedDevice)

    override fun toString(): String {
        return String.format(Locale.US, "%s - %s", uniqueName, wrappedDevice.toString())
    }

    val isInAccessoryMode: Boolean
        get() = isInAccessoryMode(wrappedDevice)

    companion object {
        /** Null on head units whose ROM ships without the USB service; treat that as no devices. */
        fun usbManager(context: Context): UsbManager? =
            context.getSystemService(Context.USB_SERVICE) as? UsbManager

        fun getUniqueName(device: UsbDevice): String {
            val vendorId = device.vendorId
            val productId = device.productId
            val vidHex = Utils.hex_get(vendorId.toShort())
            val pidHex = Utils.hex_get(productId.toShort())
            val vidPid = "VID: $vidHex PID: $pidHex"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val manufacturer = device.manufacturerName?.takeIf { it.isNotBlank() }
                val product = device.productName?.takeIf { it.isNotBlank() }
                if (manufacturer != null || product != null) {
                    // Include VID:PID so devices with identical strings but different
                    // hardware (e.g. internal multimedia module vs external phone) are
                    // treated as distinct entries.
                    return "${listOfNotNull(manufacturer, product).joinToString(" ")} ($vidPid)"
                }
            }

            return vidPid
        }

        /**
         * The IN/OUT pair to run AAP over, bulk preferred. Both transports ask here so they cannot
         * drift apart again; the rule itself is [UsbEndpointSelectionPolicy].
         */
        fun selectEndpoints(iface: UsbInterface): Pair<UsbEndpoint?, UsbEndpoint?> {
            val endpoints = (0 until iface.endpointCount).map { iface.getEndpoint(it) }
            val selection = UsbEndpointSelectionPolicy.select(
                endpoints.map {
                    UsbEndpointSelectionPolicy.Endpoint(
                        isInbound = it.direction == UsbConstants.USB_DIR_IN,
                        isBulk = it.type == UsbConstants.USB_ENDPOINT_XFER_BULK,
                    )
                }
            )
            return Pair(
                selection.inIndex?.let { endpoints[it] },
                selection.outIndex?.let { endpoints[it] },
            )
        }

        fun isInAccessoryMode(device: UsbDevice): Boolean =
            UsbDeviceIdentityPolicy.isInAccessoryMode(device.vendorId, device.productId)

        fun isAndroidDevice(device: UsbDevice): Boolean = evaluate(device).accepted

        /**
         * [isAndroidDevice] plus the user's blacklist. Every path that acts on a device asks this
         * one; [isAndroidDevice] alone is for the list UI and the diagnostic dump, where a
         * blacklisted device still has to appear so it can be taken off the list.
         *
         * The blacklist is read from device-protected storage, so it applies during a locked boot
         * as well. It used to be consulted in one of seven paths, and skipped entirely when the
         * user had not unlocked.
         */
        fun isConnectable(context: Context, device: UsbDevice): Boolean =
            isAndroidDevice(device) && !Settings.isUsbDeviceBlacklisted(context, device)

        /**
         * How the device is named in the user's blacklist. Manufacturer and product rather than
         * VID:PID, because those survive a USB mode change and the numbers do not; see
         * [UsbBlacklistPolicy].
         */
        fun blacklistKey(device: UsbDevice): String {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return UsbBlacklistPolicy.key(device.manufacturerName, device.productName, device.vendorId, device.productId)
            }
            // The API has no names here, so read the same descriptor strings from sysfs.
            val names = UsbSysfsNames.namesFor(device.deviceName)
            return UsbBlacklistPolicy.key(names?.manufacturer, names?.product, device.vendorId, device.productId)
        }

        /**
         * The same decision as [isAndroidDevice], but it says which rule fired. A user reporting
         * "no USB device found" cannot otherwise be told apart from a device we rejected, so this
         * feeds the diagnostic dump rather than any connection logic.
         */
        fun matchReason(device: UsbDevice): String = evaluate(device).toString()

        /**
         * [matchReason] plus the blacklist, for the paths that refuse on [isConnectable]. The
         * descriptor verdict on its own says "accepted" for a device the user has vetoed, which
         * is the opposite of what someone reading the log to find out why their phone will not
         * connect is looking for.
         */
        fun connectableReason(context: Context, device: UsbDevice): String =
            if (Settings.isUsbDeviceBlacklisted(context, device)) BLACKLISTED_REASON
            else matchReason(device)

        /**
         * The blacklist is the one reason a device the descriptors accept still will not connect,
         * and a user who set it months ago will not mention it in a bug report.
         */
        const val BLACKLISTED_REASON = "rejected: blacklisted by the user"

        private fun evaluate(device: UsbDevice): UsbDeviceIdentityPolicy.Verdict =
            UsbDeviceIdentityPolicy.evaluate(describe(device))

        /** Reads the Android-side facts [UsbPermissionAskPolicy] decides on. Unreadable means ask. */
        internal fun permissionDecisionInputs(
            context: Context, settings: Settings, device: UsbDevice, source: UsbPermissionAskPolicy.Source,
            presentPaths: Collection<String>,
        ): UsbPermissionAskPolicy.Inputs {
            val described = describe(device)
            val activityEnabled = try {
                val state = context.packageManager.getComponentEnabledSetting(
                    android.content.ComponentName(context, com.andrerinas.openheadunit.app.UsbAttachedActivity::class.java)
                )
                state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } catch (e: Exception) {
                false
            }
            val automotive = try {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP &&
                    context.packageManager.hasSystemFeature("android.hardware.type.automotive")
            } catch (e: Exception) {
                false
            }
            return UsbPermissionAskPolicy.Inputs(
                accessory = isInAccessoryMode(device),
                mtp = UsbPermissionAskPolicy.isMtp(described),
                filterMatches = UsbManifestFilter.matches(described),
                activityEnabled = activityEnabled,
                automotive = automotive,
                sdkInt = Build.VERSION.SDK_INT,
                listenForUsb = settings.listenForUsbDevices,
                attachAgeMs = UsbPermissionAsks.attachAgeMs(device.deviceName, UsbPermissionAsks.nowMs()),
                ownSwitchAgeMs = UsbPermissionAsks.ownSwitchAgeMs(UsbPermissionAsks.nowMs()),
                alreadyAsked = UsbPermissionAsks.isAsked(device.deviceName, presentPaths),
                source = source,
            )
        }

        /** Maps the Android object onto the plain descriptors the policy decides on. */
        internal fun describe(device: UsbDevice): UsbDeviceIdentityPolicy.Device {
            val interfaces = ArrayList<UsbDeviceIdentityPolicy.Interface>(device.interfaceCount)
            for (i in 0 until device.interfaceCount) {
                interfaces.add(describe(device.getInterface(i)))
            }
            return UsbDeviceIdentityPolicy.Device(
                vendorId = device.vendorId,
                productId = device.productId,
                deviceClass = device.deviceClass,
                interfaces = interfaces,
                deviceSubclass = device.deviceSubclass,
                deviceProtocol = device.deviceProtocol,
            )
        }

        private fun describe(usbInterface: UsbInterface): UsbDeviceIdentityPolicy.Interface {
            var hasBulkIn = false
            var hasBulkOut = false
            for (j in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(j)
                if (endpoint.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                if (endpoint.direction == UsbConstants.USB_DIR_IN) hasBulkIn = true else hasBulkOut = true
            }
            return UsbDeviceIdentityPolicy.Interface(
                ifaceClass = usbInterface.interfaceClass,
                subclass = usbInterface.interfaceSubclass,
                protocol = usbInterface.interfaceProtocol,
                // getName() is API 21; below that the descriptor rules run without it, which
                // only costs the accessory case its tie-breaker.
                name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) usbInterface.name else null,
                hasBulkIn = hasBulkIn,
                hasBulkOut = hasBulkOut,
            )
        }
    }
}
