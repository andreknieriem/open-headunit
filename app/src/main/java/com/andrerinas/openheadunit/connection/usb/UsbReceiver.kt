package com.andrerinas.openheadunit.connection.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.net.Uri
import com.andrerinas.openheadunit.App


import com.andrerinas.openheadunit.utils.AppLog

class UsbReceiver(private val mListener: Listener)          // USB Broadcast Receiver enabled by start() & disabled by stop()
    : BroadcastReceiver() {

    init {
        AppLog.d("UsbReceiver registered")
    }

    interface Listener {
        fun onUsbDetach(device: UsbDevice)
        fun onUsbAttach(device: UsbDevice)
        fun onUsbAccessoryDetach()
        /** [token] is the launcher's own permission request, or null for one raised elsewhere. */
        fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice, ticket: Long, token: String?)
        fun onUsbRecoveryPermission(token: String, granted: Boolean, device: UsbDevice) {}
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("USB Intent: $intent")

        if (action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
            mListener.onUsbAccessoryDetach()
            return
        }

        val device: UsbDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return

        when (action) {
            UsbManager.ACTION_USB_DEVICE_DETACHED -> // If detach...
                mListener.onUsbDetach(device)
            // Handle detached device
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> // If attach...
                mListener.onUsbAttach(device)
            ACTION_USB_DEVICE_PERMISSION -> {
                // If Our App specific Intent for permission request...
                val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val connect = intent.getBooleanExtra(EXTRA_CONNECT, false)
                val recovery = intent.data?.takeIf { it.scheme == RECOVERY_PERMISSION_SCHEME }
                if (recovery != null) {
                    // A late recovery reply must never become an ordinary connect request.
                    mListener.onUsbRecoveryPermission(recovery.schemeSpecificPart, permissionGranted, device)
                } else {
                    val reply = intent.data?.takeIf { it.scheme == PERMISSION_SCHEME } ?: return
                    val ticket = reply.schemeSpecificPart?.toLongOrNull() ?: return
                    mListener.onUsbPermission(permissionGranted, connect, device, ticket, reply.fragment)
                }
            }
        }
    }

    companion object {
        const val ACTION_USB_DEVICE_PERMISSION = "com.andrerinas.openheadunit" + ".ACTION_USB_DEVICE_PERMISSION"
        const val EXTRA_CONNECT = "EXTRA_CONNECT"

        /**
         * A null ticket requests the grant only; its reply cannot start a connection. [token] names
         * the launcher's own request, so its reply continues exactly that request's owed scan.
         */
        fun createPermissionPendingIntent(context: Context, ticket: Long?, token: String? = null): PendingIntent {
            return PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).apply {
                    setPackage(context.packageName)
                    if (ticket != null) data = Uri.fromParts(PERMISSION_SCHEME, ticket.toString(), token)
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        private const val PERMISSION_SCHEME = "ohu-usb-attempt"
        private const val RECOVERY_PERMISSION_SCHEME = "ohu-usb-recovery"

        fun createRecoveryPermissionPendingIntent(context: Context, token: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).setPackage(context.packageName)
                    .setData(Uri.fromParts(RECOVERY_PERMISSION_SCHEME, token, null)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0)

        fun createPermissionResultFilter() = IntentFilter(ACTION_USB_DEVICE_PERMISSION).apply {
            addDataScheme(RECOVERY_PERMISSION_SCHEME)
            addDataScheme(PERMISSION_SCHEME)
        }

        fun createFilter(): IntentFilter {
            val filter = IntentFilter()
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            filter.addAction(ACTION_USB_DEVICE_PERMISSION)
            return filter
        }

        fun match(action: String): Boolean {
            return when (action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> true
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> true
                ACTION_USB_DEVICE_PERMISSION -> true
                else -> false
            }
        }
    }
}
