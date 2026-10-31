package com.andrerinas.openheadunit.connection.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.net.Uri
import com.andrerinas.openheadunit.connection.UsbRecheck


import com.andrerinas.openheadunit.utils.AppLog

// The no-argument manifest receiver observes the bus even after AapService or the process exits.
class UsbReceiver(private val mListener: Listener? = null)
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

        // Observation is independent of the service. Never build AppComponent here: Android may
        // deliver these broadcasts before first unlock, or solely to record a genuine unplug.
        if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
            action == UsbManager.ACTION_USB_DEVICE_DETACHED ||
            action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
            allowsAutomaticInput(context)
        }
        val listener = mListener ?: return

        if (action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
            listener.onUsbAccessoryDetach()
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
                listener.onUsbDetach(device)
            // Handle detached device
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> // If attach...
                listener.onUsbAttach(device)
            ACTION_USB_DEVICE_PERMISSION -> {
                // If Our App specific Intent for permission request...
                val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                val connect = intent.getBooleanExtra(EXTRA_CONNECT, false)
                val recovery = intent.data?.takeIf { it.scheme == RECOVERY_PERMISSION_SCHEME }
                if (recovery != null) {
                    // A late recovery reply must never become an ordinary connect request.
                    listener.onUsbRecoveryPermission(recovery.schemeSpecificPart, permissionGranted, device)
                } else {
                    val reply = intent.data?.takeIf { it.scheme == PERMISSION_SCHEME } ?: return
                    val recheck = decodeRecheck(reply.schemeSpecificPart) ?: return
                    listener.onUsbPermission(permissionGranted, connect, device, recheck, reply.fragment)
                }
            }
        }
    }

    companion object {
        // AOA changes VID/PID and deviceName; serial needs permission. Hold the Android-phone bus
        // conservatively, not a guessed identity. 10 s exceeds the measured 1.3–2.7 s re-enumeration
        // and leaves room for real phones. Continuous presence never expires the user's decision.
        internal const val USER_STOP_ABSENCE_MS = 10_000L
        private const val INPUT_PREFS = "usb-input"
        private const val HELD = "user-stopped"
        private const val ABSENT_SINCE = "absent-since"
        private const val BOOT = "boot-count"
        private const val OBSERVED_AT = "observed-at"
        private const val UNPLUG_RESUME = "resumed-by-unplug"
        private val inputLock = Any()

        private fun inputPrefs(context: Context): SharedPreferences {
            val storage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                context.createDeviceProtectedStorageContext() else context
            return storage.getSharedPreferences(INPUT_PREFS, Context.MODE_PRIVATE)
        }

        internal fun bootCount(context: Context): Int =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                android.provider.Settings.Global.getInt(context.contentResolver, "boot_count", -1)
            else -1

        // Only actual bus observations establish absence. A missing USB service is unknown, and
        // a late detach broadcast with another phone already present is not an unplug interval.
        private fun phonePresent(context: Context): Boolean? = try {
            UsbDeviceCompat.usbManager(context)?.deviceList?.values?.any(UsbDeviceCompat::isAndroidDevice)
        } catch (_: RuntimeException) {
            // A ROM that cannot enumerate the bus has not proved a genuine unplug. In particular,
            // this observation must not prevent a user Stop on an otherwise wireless session.
            null
        }

        private fun heldInThisBoot(prefs: SharedPreferences, now: Long, boot: Int): Boolean {
            if (prefs.getInt(BOOT, boot) != boot || now < prefs.getLong(OBSERVED_AT, 0L)) {
                // A reboot does not prove an unplug or a new user input. Keep the decision,
                // but never compare absence timestamps across elapsed-realtime epochs.
                prefs.edit().remove(ABSENT_SINCE).putBoolean(UNPLUG_RESUME, false)
                    .putInt(BOOT, boot).putLong(OBSERVED_AT, now).commit()
            }
            return prefs.getBoolean(HELD, false)
        }

        /**
         * Persist at accepted input, before teardown can kill the process. Its caller observes
         * the bus and reads bootCount before entering the lifecycle monitor, so neither Binder
         * lookup runs under that lock. Concurrent bus observations share inputLock with this write.
         */
        internal fun holdAfterUserStop(context: Context, boot: Int) = synchronized(inputLock) {
            val prefs = inputPrefs(context)
            val now = SystemClock.elapsedRealtime()
            heldInThisBoot(prefs, now, boot)
            prefs.edit().putBoolean(HELD, true).putBoolean(UNPLUG_RESUME, false).putInt(BOOT, boot)
                .putLong(OBSERVED_AT, now).commit()
            Unit
        }

        /** Used only to decide whether a genuine Open owes an explicit USB input. */
        internal fun isUserStopped(context: Context): Boolean = synchronized(inputLock) {
            inputPrefs(context).getBoolean(HELD, false)
        }

        /** The caller validates its captured input before releasing a later user's Stop. */
        internal fun releaseForExplicitInput(context: Context) = synchronized(inputLock) {
            inputPrefs(context).edit().clear().commit()
            Unit
        }

        /** A genuine plug also releases the service's earlier status-pill cancellation once. */
        internal fun takeUnplugResume(context: Context): Boolean = synchronized(inputLock) {
            val prefs = inputPrefs(context)
            if (prefs.getBoolean(HELD, false) || !prefs.getBoolean(UNPLUG_RESUME, false)) return@synchronized false
            prefs.edit().putBoolean(UNPLUG_RESUME, false).commit()
            true
        }

        /** Automatic inputs observe the current bus, but cannot turn a short re-enumeration into Run. */
        internal fun allowsAutomaticInput(context: Context): Boolean = synchronized(inputLock) {
            val prefs = inputPrefs(context)
            val now = SystemClock.elapsedRealtime()
            val boot = bootCount(context)
            var held = heldInThisBoot(prefs, now, boot)
            val present = phonePresent(context) ?: return@synchronized !held
            val absentSince = prefs.getLong(ABSENT_SINCE, -1L)
            val unplugged = held && absentSince >= 0L && now - absentSince >= USER_STOP_ABSENCE_MS
            if (unplugged) held = false
            val nextAbsent = if (present) -1L else if (absentSince >= 0L) absentSince else now
            // Record real bus transitions even before Stop: ACC START may have detached already
            // when Stop is accepted. No device identity, service or process-local flag is needed.
            if (nextAbsent != absentSince || held != prefs.getBoolean(HELD, false) || !prefs.contains(BOOT)) {
                prefs.edit().putBoolean(HELD, held).putLong(ABSENT_SINCE, nextAbsent)
                    .putBoolean(UNPLUG_RESUME, unplugged || prefs.getBoolean(UNPLUG_RESUME, false))
                    .putInt(BOOT, boot).putLong(OBSERVED_AT, now).commit()
            }
            !held
        }

        const val ACTION_USB_DEVICE_PERMISSION = "com.andrerinas.openheadunit" + ".ACTION_USB_DEVICE_PERMISSION"
        const val EXTRA_CONNECT = "EXTRA_CONNECT"

        /**
         * A null [recheck] requests the grant only; its reply cannot start a connection. Otherwise
         * the reply carries [recheck] (the owed scan minted when the dialog was raised), so a user
         * exit, Stop or held end before the answer withdraws what the grant would start. [token]
         * names the launcher's own request, so its reply continues exactly that request's scan.
         */
        fun createPermissionPendingIntent(context: Context, recheck: UsbRecheck?, token: String? = null): PendingIntent {
            return PendingIntent.getBroadcast(
                context, 0,
                Intent(ACTION_USB_DEVICE_PERMISSION).apply {
                    setPackage(context.packageName)
                    if (recheck != null) data = Uri.fromParts(PERMISSION_SCHEME, encodeRecheck(recheck), token)
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
