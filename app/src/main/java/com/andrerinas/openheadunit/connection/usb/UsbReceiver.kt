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
import androidx.core.content.IntentCompat
import com.andrerinas.openheadunit.connection.UsbRecheck


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
        /**
         * [recheck] is the owed scan the dialog was raised for, as its producer minted it then;
         * [token] is the launcher's own permission request, or null for one raised elsewhere.
         */
        fun onUsbPermission(granted: Boolean, connect: Boolean, device: UsbDevice, recheck: UsbRecheck, token: String?)
        fun onUsbRecoveryPermission(token: String, granted: Boolean, device: UsbDevice) {}
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        AppLog.i("USB Intent: $intent")

        if (action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
            mListener.onUsbAccessoryDetach()
            return
        }

        val device: UsbDevice = deviceOf(intent) ?: return

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
                } else if (intent.data?.scheme == ASK_PERMISSION_SCHEME) {
                    val reply = intent.data ?: return
                    // A grant-only ask carries no owed scan; its reply cannot start a connection.
                    val recheck = decodeRecheck(reply.fragment) ?: return
                    mListener.onUsbPermission(permissionGranted, connect, device, recheck,
                        reply.schemeSpecificPart.takeIf { it.startsWith(LAUNCHER_TOKEN_PREFIX) })
                }
            }
        }
    }

    companion object {
        const val ACTION_USB_DEVICE_PERMISSION = "com.andrerinas.openheadunit" + ".ACTION_USB_DEVICE_PERMISSION"
        const val EXTRA_CONNECT = "EXTRA_CONNECT"

        /**
         * The token in the data makes each request its own PendingIntent, so no answer can carry
         * another's. A launcher token ([newLauncherToken]) also names the launcher's own request, so
         * its reply continues exactly that request's scan; any other token is a new input. A null
         * [recheck] requests the grant only; its reply cannot start a connection. Otherwise the reply
         * carries [recheck] (the owed scan minted when the dialog was raised), so a user exit, Stop
         * or held end before the answer withdraws what the grant would start.
         */
        fun createPermissionPendingIntent(context: Context, token: String, recheck: UsbRecheck?): PendingIntent {
            return PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).apply {
                    setPackage(context.packageName)
                    data = Uri.fromParts(ASK_PERMISSION_SCHEME, token, recheck?.let(::encodeRecheck))
                },
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                else PendingIntent.FLAG_UPDATE_CURRENT
            )
        }

        /** The reply address names the dialog's owed scan by its two fields: "ticket.epoch". */
        internal fun encodeRecheck(recheck: UsbRecheck): String = "${recheck.ticket}.${recheck.epoch}"

        internal fun decodeRecheck(part: String?): UsbRecheck? {
            val ticket = part?.substringBefore('.', "")?.toLongOrNull() ?: return null
            val epoch = part.substringAfter('.', "").toLongOrNull() ?: return null
            return UsbRecheck(ticket, epoch)
        }

        internal const val ASK_PERMISSION_SCHEME = "ohu-usb-ask"
        internal const val RECOVERY_PERMISSION_SCHEME = "ohu-usb-recovery"
        private const val LAUNCHER_TOKEN_PREFIX = "launcher-"

        internal fun newLauncherToken(): String = LAUNCHER_TOKEN_PREFIX + UsbPermissionAsks.newToken()

        fun createRecoveryPermissionPendingIntent(context: Context, token: String): PendingIntent =
            PendingIntent.getBroadcast(context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).setPackage(context.packageName)
                    .setData(Uri.fromParts(RECOVERY_PERMISSION_SCHEME, token, null)),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE
                else 0)

        /** Every permission answer carries a token in its data, so only this filter matches one. */
        fun createPermissionFilter() = IntentFilter(ACTION_USB_DEVICE_PERMISSION).apply {
            addDataScheme(ASK_PERMISSION_SCHEME)
            addDataScheme(RECOVERY_PERMISSION_SCHEME)
        }

        /** The request token of a permission answer, ordinary or recovery. */
        fun permissionToken(intent: Intent): String? = intent.data
            ?.takeIf { it.scheme == ASK_PERMISSION_SCHEME || it.scheme == RECOVERY_PERMISSION_SCHEME }
            ?.schemeSpecificPart

        fun deviceOf(intent: Intent): UsbDevice? =
            IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

        fun createFilter(): IntentFilter {
            val filter = IntentFilter()
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
            return filter
        }

        fun match(action: String): Boolean {
            return when (action) {
                UsbManager.ACTION_USB_DEVICE_DETACHED -> true
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> true
                UsbManager.ACTION_USB_ACCESSORY_DETACHED -> true
                else -> false
            }
        }
    }
}
