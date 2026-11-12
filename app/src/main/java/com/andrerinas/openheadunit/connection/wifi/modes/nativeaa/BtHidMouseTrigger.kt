package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.BluetoothHelper
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serverless Bluetooth HID Combo (Keyboard + Mouse) trigger (API 28+).
 *
 * Registers this unit's own Android Bluetooth radio as a composite HID peripheral
 * (Mouse + Keyboard + Consumer Control) and automatically connects to the target phone(s).
 *
 * Why this works where an unprivileged RFCOMM poke alone fails:
 *  1. `BluetoothHidDevice.connect(device)` is executed by Android's privileged `HidDevService`
 *     (`com.android.bluetooth`), reliably paging a locked/sleeping phone and bringing up an
 *     encrypted Bluetooth ACL + L2CAP HID Host connection.
 *  2. Once `STATE_CONNECTED` is reached (after a 300ms settle for the phone's kernel `uhid`
 *     input node to initialize), it sends a zero-net-movement mouse nudge (+2, -2 on X) and a
 *     harmless Left-Shift press/release to wake the locked phone's screen and lift Doze Wi-Fi
 *     scan throttling.
 *  3. Immediately notifies [onHidConnected] so [NativeAaHandshakeManager] can dial the phone's
 *     HFP/AA RFCOMM channel over the now-active ACL link to trigger Wireless Android Auto.
 *  4. When the Android Auto projection session goes live, [releaseActiveConnections] disconnects
 *     the HID link (while keeping the SDP record registered) so the phone's soft keyboard is not
 *     suppressed during driving and the next session gets a fresh `STATE_CONNECTED` edge.
 */
@SuppressLint("MissingPermission")
class BtHidMouseTrigger(
    private val context: Context,
    private val onHidConnected: ((BluetoothDevice) -> Unit)? = null
) {

    @Volatile private var currentAdapter: BluetoothAdapter? = null
    @Volatile private var armedForWake = false
    @Volatile private var lastPluggedDevice: BluetoothDevice? = null
    private val pendingDevices = Collections.synchronizedSet(mutableSetOf<BluetoothDevice>())
    private var autoConnectTask: ScheduledFuture<*>? = null

    init {
        if (onHidConnected != null) {
            sharedOnHidConnected = onHidConnected
        }
    }

    /**
     * Starts the HID device profile proxy, registers the composite Keyboard+Mouse SDP record,
     * and starts the automatic connection loop to paired target phone(s).
     */
    fun start(adapter: BluetoothAdapter) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        if (onHidConnected != null) {
            sharedOnHidConnected = onHidConnected
        }
        currentAdapter = adapter
        armedForWake = true
        startAutoConnectLoop()

        val existingHid = sharedHidDevice
        if (existingHid != null && sharedAppRegistered) {
            AppLog.i("NativeAA-HID: serverless keyboard/mouse already registered; connecting to target phone(s)...")
            activeOwner = this
            scheduler.execute { connectAndWakeTargets(existingHid) }
            return
        }

        activeOwner = this
        if (existingHid == null) {
            try {
                AppLog.i("NativeAA-HID: requesting BluetoothProfile.HID_DEVICE proxy...")
                adapter.getProfileProxy(context.applicationContext, profileListener, PROFILE_HID_DEVICE)
            } catch (e: Exception) {
                AppLog.w("NativeAA-HID: could not request the HID device profile: ${e.message}")
            }
        }
    }

    /**
     * Queues [device] for immediate HID connection and wake nudge.
     * If the HID profile is still registering asynchronously, [device] is saved in [pendingDevices]
     * and connected the instant `onAppStatusChanged(..., registered = true)` fires.
     */
    fun pokeAndWake(device: BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        pendingDevices.add(device)
        val hid = sharedHidDevice
        if (hid == null || !sharedAppRegistered) {
            AppLog.i("NativeAA-HID: queued ${device.name ?: "unnamed"} (${device.address}) for HID connect once registration completes.")
            currentAdapter?.let { start(it) }
                ?: BluetoothHelper.getBluetoothAdapter(context)?.takeIf { it.isEnabled }?.let { start(it) }
            return true
        }
        scheduler.execute { connectOrWakeSingle(hid, device, forceWakeIfConnected = true) }
        return true
    }

    /**
     * Quiesces wake report sending once Android Auto projection is live, while keeping the
     * Bluetooth HID connection and SDP record active so the phone's Bluetooth connection never
     * drops mid-session.
     */
    fun releaseActiveConnections() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        armedForWake = false
        AppLog.i("NativeAA-HID: projection session live; keeping Bluetooth HID connected in idle mode.")
    }

    /**
     * Stops the auto-connect loop when the handshake manager stops, unless persistent HID retry is
     * enabled and no projection session is active. Only unregisters the HID SDP app if the user
     * disabled `btHidWakeTrigger` in Settings, so opening Settings or Android Bluetooth Settings to
     * pair a phone keeps the HID profile registered and retrying until connected.
     */
    fun stop(adapter: BluetoothAdapter?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val appContext = context.applicationContext
        val comp = try { App.provide(appContext) } catch (_: Exception) { null }
        val settings = comp?.settings
        val sessionActive = comp?.commManager?.isConnected == true

        if (settings?.btHidWakeTrigger == false) {
            armedForWake = false
            stopAutoConnectLoop()
            pendingDevices.clear()
            wokenConnectedAddresses.clear()
            unregisterShared(adapter ?: currentAdapter)
        } else if (sessionActive) {
            releaseActiveConnections()
        } else {
            // Keep armedForWake and the auto-connect retry loop alive while in Settings so
            // selecting a wake device keeps trying to connect until it reaches STATE_CONNECTED.
            armedForWake = true
            startAutoConnectLoop()
        }
    }

    private fun startAutoConnectLoop() {
        if (autoConnectTask?.isCancelled == false && autoConnectTask?.isDone == false) return
        autoConnectTask = scheduler.scheduleWithFixedDelay({
            try {
                if (!armedForWake) return@scheduleWithFixedDelay
                val hid = sharedHidDevice
                if (hid != null && sharedAppRegistered) {
                    connectAndWakeTargets(hid)
                }
            } catch (e: Exception) {
                AppLog.d("NativeAA-HID: auto-connect tick error: ${e.message}")
            }
        }, 1_000L, AUTO_CONNECT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    private fun stopAutoConnectLoop() {
        autoConnectTask?.cancel(false)
        autoConnectTask = null
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun resolveTargetDevices(): List<BluetoothDevice> {
        val appContext = context.applicationContext
        val settings = try { App.provide(appContext).settings } catch (_: Exception) { null }
        val adapter = currentAdapter ?: BluetoothHelper.getBluetoothAdapter(appContext) ?: return emptyList()
        if (!adapter.isEnabled) return emptyList()

        val result = LinkedHashMap<String, BluetoothDevice>()

        // 0. Any unsaved / pending target MACs selected in the Settings wake picker
        for (mac in extraTargetMacs) {
            try {
                val dev = adapter.getRemoteDevice(mac)
                result[dev.address.uppercase()] = dev
            } catch (_: Exception) {}
        }

        // 1. Any explicitly queued devices from pokeAndWake()
        synchronized(pendingDevices) {
            for (dev in pendingDevices) {
                result[dev.address.uppercase()] = dev
            }
        }

        // 2. User-configured "Devices to wake" (nativePokeBtMacs) and "Preferred device" (nativePreferredDeviceMac)
        val preferredMac = settings?.nativePreferredDeviceMac.orEmpty().trim()
        if (preferredMac.isNotEmpty()) {
            try {
                val dev = adapter.getRemoteDevice(preferredMac)
                result[dev.address.uppercase()] = dev
            } catch (_: Exception) {}
        }

        val wakeMacs = settings?.nativePokeBtMacs.orEmpty()
        for (mac in wakeMacs) {
            try {
                val dev = adapter.getRemoteDevice(mac)
                result[dev.address.uppercase()] = dev
            } catch (_: Exception) {}
        }

        // If the user explicitly selected wake devices, only target those (+ preferred).
        if (wakeMacs.isNotEmpty() || extraTargetMacs.isNotEmpty()) {
            return result.values.toList()
        }

        // 3. Last plugged HID host reported by Android's HidDevService
        lastPluggedDevice?.let { dev ->
            if (dev.bondState == BluetoothDevice.BOND_BONDED) {
                result[dev.address.uppercase()] = dev
            }
        }

        // 4. All bonded phones / candidate devices on the adapter
        if (settings?.nativePokeAllPairedDevices != false) {
            val candidates = try {
                BluetoothHelper.driverCandidates(
                    appContext,
                    settings?.nativePreferredDeviceMac.orEmpty(),
                    settings?.lastConnectedNativeMac.orEmpty()
                ).offered
            } catch (_: Exception) { emptyList() }
            for (dev in candidates) {
                result[dev.address.uppercase()] = dev
            }
            // Fallback: if SDP UUIDs were not cached yet so driverCandidates returned empty,
            // include all bonded devices not ruled out as non-phones.
            if (result.isEmpty()) {
                val bonded = try { adapter.bondedDevices.orEmpty() } catch (_: Exception) { emptySet() }
                for (dev in bonded) {
                    val verdict = try {
                        BluetoothHelper.classifyDevice(
                            dev,
                            BluetoothHelper.pinFor(
                                dev,
                                settings?.nativePreferredDeviceMac.orEmpty(),
                                settings?.lastConnectedNativeMac.orEmpty()
                            )
                        ).verdict
                    } catch (_: Exception) { DriverCandidatePolicy.Verdict.UNKNOWN }
                    if (verdict != DriverCandidatePolicy.Verdict.NOT_A_PHONE) {
                        result[dev.address.uppercase()] = dev
                    }
                }
            }
        }

        return result.values.toList()
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun connectAndWakeTargets(hid: BluetoothHidDevice) {
        if (!armedForWake) return
        val targets = resolveTargetDevices()
        if (targets.isEmpty()) {
            AppLog.d("NativeAA-HID: no bonded target phones found for automatic HID connection.")
            return
        }
        for (device in targets) {
            if (!armedForWake) break
            connectOrWakeSingle(hid, device)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun connectOrWakeSingle(
        hid: BluetoothHidDevice,
        device: BluetoothDevice,
        forceWakeIfConnected: Boolean = false
    ) {
        try {
            val addr = device.address.uppercase()
            when (device.bondState) {
                BluetoothDevice.BOND_NONE -> {
                    wokenConnectedAddresses.remove(addr)
                    val bonding = try { device.createBond() } catch (_: Exception) { false }
                    AppLog.i("NativeAA-HID: initiating Bluetooth pairing (createBond) with ${device.name ?: "unnamed"} ($addr) -> $bonding")
                    return
                }
                BluetoothDevice.BOND_BONDING -> {
                    AppLog.d("NativeAA-HID: waiting for Bluetooth pairing (BOND_BONDING) with ${device.name ?: "unnamed"} ($addr)...")
                    return
                }
            }
            when (val state = hid.getConnectionState(device)) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (wokenConnectedAddresses.add(addr) || forceWakeIfConnected) {
                        AppLog.i("NativeAA-HID: STATE_CONNECTED to ${device.name ?: "unnamed"} ($addr); sending initial wake nudge")
                        sendWakeSequence(hid, device)
                        (onHidConnected ?: sharedOnHidConnected)?.invoke(device)
                    }
                }
                BluetoothProfile.STATE_CONNECTING -> {
                    // Never call hid.disconnect(device) while connecting: L2CAP/SDP/encryption
                    // negotiation takes several seconds, and disconnecting aborts the connection.
                    AppLog.d("NativeAA-HID: STATE_CONNECTING to ${device.name ?: "unnamed"} ($addr); waiting for connection to complete")
                }
                else -> {
                    wokenConnectedAddresses.remove(addr)
                    val requested = hid.connect(device)
                    AppLog.i("NativeAA-HID: hid.connect(${device.name ?: "unnamed"} [$addr], prevState=$state) -> $requested (retrying until connected)")
                }
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA-HID: connectOrWakeSingle(${device.address}) failed: ${e.message}")
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun sendWakeSequence(hid: BluetoothHidDevice, device: BluetoothDevice) {
        // Send immediately + at 300ms + at 700ms so the phone's kernel uhid node is guaranteed
        // to be ready after STATE_CONNECTED.
        sendWakeReports(hid, device)
        scheduler.schedule({
            if (armedForWake) sendWakeReports(hid, device)
        }, 300L, TimeUnit.MILLISECONDS)
        scheduler.schedule({
            if (armedForWake) {
                sendWakeReports(hid, device)
                (activeOwner?.onHidConnected ?: sharedOnHidConnected)?.invoke(device)
            }
        }, 700L, TimeUnit.MILLISECONDS)
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun sendWakeReports(hid: BluetoothHidDevice, device: BluetoothDevice) {
        try {
            if (hid.getConnectionState(device) != BluetoothProfile.STATE_CONNECTED) return
            // 1. Mouse nudge: +2 then -2 on X (zero net cursor movement, wakes input dispatcher)
            hid.sendReport(device, REPORT_ID_MOUSE, byteArrayOf(0x00, 0x02, 0x00))
            hid.sendReport(device, REPORT_ID_MOUSE, byteArrayOf(0x00, 0xFE.toByte(), 0x00))
            // 2. Keyboard modifier tap: Left-Shift down (0x02) then release (0x00) — wakes lock screen without typing
            hid.sendReport(device, REPORT_ID_KEYBOARD, byteArrayOf(0x02, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            hid.sendReport(device, REPORT_ID_KEYBOARD, byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
            AppLog.i("NativeAA-HID: sent keyboard+mouse wake reports to ${device.name ?: "unnamed"} (${device.address})")
        } catch (e: Exception) {
            AppLog.d("NativeAA-HID: wake report to ${device.address} failed: ${e.message}")
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        @RequiresApi(Build.VERSION_CODES.P)
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != PROFILE_HID_DEVICE) return
            val hid = proxy as? BluetoothHidDevice ?: return
            sharedHidDevice = hid
            try {
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "OpenHeadunit HID Trigger",
                    "Serverless Keyboard & Mouse",
                    "OpenHeadunit",
                    BluetoothHidDevice.SUBCLASS1_COMBO,
                    COMBO_HID_DESCRIPTOR
                )
                val registeredCall = hid.registerApp(sdp, null, null, scheduler, object : BluetoothHidDevice.Callback() {
                    override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
                        sharedAppRegistered = registered
                        if (pluggedDevice != null) {
                            lastPluggedDevice = pluggedDevice
                        }
                        AppLog.i("NativeAA-HID: serverless keyboard+mouse registered=$registered, pluggedDevice=${pluggedDevice?.address}")
                        if (registered) {
                            activeOwner?.let { owner ->
                                if (pluggedDevice != null) owner.lastPluggedDevice = pluggedDevice
                                owner.connectAndWakeTargets(hid)
                            }
                        }
                    }

                    override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
                        val stateName = when (state) {
                            BluetoothProfile.STATE_DISCONNECTED -> "DISCONNECTED"
                            BluetoothProfile.STATE_CONNECTING -> "CONNECTING"
                            BluetoothProfile.STATE_CONNECTED -> "CONNECTED"
                            BluetoothProfile.STATE_DISCONNECTING -> "DISCONNECTING"
                            else -> state.toString()
                        }
                        AppLog.i("NativeAA-HID: connection state for ${device?.name ?: "unnamed"} (${device?.address}) -> $stateName")
                        if (device != null) {
                            val addr = device.address.uppercase()
                            if (state == BluetoothProfile.STATE_CONNECTED) {
                                wokenConnectedAddresses.add(addr)
                                activeOwner?.let { owner ->
                                    owner.lastPluggedDevice = device
                                    if (owner.armedForWake) {
                                        owner.sendWakeSequence(hid, device)
                                    }
                                }
                                com.andrerinas.openheadunit.aap.AapService.instance?.onTargetBluetoothConnected(
                                    device,
                                    "Serverless Bluetooth HID Keyboard/Mouse connected"
                                )
                            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                                wokenConnectedAddresses.remove(addr)
                            }
                        }
                    }
                })
                AppLog.i("NativeAA-HID: registerApp call returned $registeredCall")
            } catch (e: Exception) {
                AppLog.w("NativeAA-HID: registerApp failed: ${e.message}")
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == PROFILE_HID_DEVICE) {
                AppLog.w("NativeAA-HID: HID_DEVICE profile service disconnected")
                sharedHidDevice = null
                sharedAppRegistered = false
            }
        }
    }

    companion object {
        /** BluetoothProfile.HID_DEVICE, public since API 28. */
        private const val PROFILE_HID_DEVICE = 19
        private const val REPORT_ID_MOUSE = 1
        private const val REPORT_ID_KEYBOARD = 2
        private const val AUTO_CONNECT_INTERVAL_MS = 3_000L

        @Volatile private var sharedHidDevice: BluetoothHidDevice? = null
        @Volatile private var sharedAppRegistered = false
        @Volatile private var activeOwner: BtHidMouseTrigger? = null
        @Volatile private var sharedOnHidConnected: ((BluetoothDevice) -> Unit)? = null
        private val wokenConnectedAddresses = Collections.synchronizedSet(mutableSetOf<String>())
        private val extraTargetMacs = Collections.synchronizedSet(mutableSetOf<String>())

        private val scheduler = Executors.newSingleThreadScheduledExecutor { job ->
            Thread(job, "bt_hid_trigger").apply { isDaemon = true }
        }

        /**
         * Starts or updates the persistent Serverless Bluetooth Keyboard/Mouse connection loop for
         * [targetMacs] immediately (even while Settings is open), keeping it retrying every 3s
         * until the selected device is connected.
         */
        fun ensurePersistentConnect(context: Context, targetMacs: Set<String>, enabled: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            synchronized(extraTargetMacs) {
                extraTargetMacs.clear()
                extraTargetMacs.addAll(targetMacs.map { it.trim() }.filter { it.isNotEmpty() })
            }
            val adapter = BluetoothHelper.getBluetoothAdapter(context.applicationContext) ?: return
            if (!enabled) {
                activeOwner?.let {
                    it.armedForWake = false
                    it.stopAutoConnectLoop()
                }
                unregisterShared(adapter)
                return
            }
            if (!adapter.isEnabled) return
            val owner = activeOwner ?: BtHidMouseTrigger(context.applicationContext)
            owner.start(adapter)
        }

        fun isTargetDeviceConnected(targetMacs: Set<String>): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
            val hid = sharedHidDevice ?: return false
            return try {
                val connected = hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                if (targetMacs.isEmpty()) {
                    connected.isNotEmpty()
                } else {
                    connected.any { dev ->
                        val addr = dev.address ?: return@any false
                        targetMacs.any { it.equals(addr, ignoreCase = true) }
                    }
                }
            } catch (_: Exception) {
                false
            }
        }

        /**
         * Sends an immediate HID Mouse+Keyboard wake sequence to any connected target phone (or
         * triggers a connect+wake tick) so a locked phone plugged into USB wakes its screen and
         * unlocks its USB AOA gadget immediately.
         */
        fun wakeConnectedTargetsNow(context: Context? = null) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val hid = sharedHidDevice
            val owner = activeOwner
            if (hid != null && sharedAppRegistered && owner != null) {
                scheduler.execute {
                    try {
                        val connected = hid.getDevicesMatchingConnectionStates(intArrayOf(BluetoothProfile.STATE_CONNECTED))
                        if (connected.isNotEmpty()) {
                            for (dev in connected) {
                                owner.sendWakeSequence(hid, dev)
                            }
                        } else {
                            owner.connectAndWakeTargets(hid)
                        }
                    } catch (_: Exception) {}
                }
            } else if (context != null) {
                try {
                    val settings = App.provide(context.applicationContext).settings
                    if (settings.btHidWakeTrigger) {
                        ensurePersistentConnect(context.applicationContext, settings.configuredBluetoothTargetMacs, true)
                    }
                } catch (_: Exception) {}
            }
        }

        private fun unregisterShared(adapter: BluetoothAdapter?) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
            val hid = sharedHidDevice ?: return
            sharedHidDevice = null
            val wasRegistered = sharedAppRegistered
            sharedAppRegistered = false
            try { if (wasRegistered) hid.unregisterApp() } catch (_: Exception) {}
            try { adapter?.closeProfileProxy(PROFILE_HID_DEVICE, hid) } catch (_: Exception) {}
        }

        /**
         * Composite HID Report Descriptor:
         * - Report ID 1: 3-Button Mouse (3 bytes: buttons, X, Y)
         * - Report ID 2: Standard Boot Keyboard (8 bytes: modifier, reserved, 6 keycodes)
         */
        private val COMBO_HID_DESCRIPTOR = intArrayOf(
            // --- Mouse (Report ID 1) ---
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1, 0x01,       // Collection (Application)
            0x85, REPORT_ID_MOUSE, // Report ID (1)
            0x09, 0x01,       //   Usage (Pointer)
            0xA1, 0x00,       //   Collection (Physical)
            0x05, 0x09,       //     Usage Page (Buttons)
            0x19, 0x01,       //     Usage Minimum (1)
            0x29, 0x03,       //     Usage Maximum (3)
            0x15, 0x00,       //     Logical Minimum (0)
            0x25, 0x01,       //     Logical Maximum (1)
            0x95, 0x03,       //     Report Count (3)
            0x75, 0x01,       //     Report Size (1)
            0x81, 0x02,       //     Input (Data, Variable, Absolute)
            0x95, 0x01,       //     Report Count (1)
            0x75, 0x05,       //     Report Size (5)
            0x81, 0x01,       //     Input (Constant)
            0x05, 0x01,       //     Usage Page (Generic Desktop)
            0x09, 0x30,       //     Usage (X)
            0x09, 0x31,       //     Usage (Y)
            0x15, 0x81,       //     Logical Minimum (-127)
            0x25, 0x7F,       //     Logical Maximum (127)
            0x75, 0x08,       //     Report Size (8)
            0x95, 0x02,       //     Report Count (2)
            0x81, 0x06,       //     Input (Data, Variable, Relative)
            0xC0,             //   End Collection
            0xC0,             // End Collection

            // --- Keyboard (Report ID 2) ---
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x06,       // Usage (Keyboard)
            0xA1, 0x01,       // Collection (Application)
            0x85, REPORT_ID_KEYBOARD, // Report ID (2)
            0x05, 0x07,       //   Usage Page (Key Codes)
            0x19, 0xE0,       //   Usage Minimum (224 - Left Control)
            0x29, 0xE7,       //   Usage Maximum (231 - Right GUI)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x01,       //   Logical Maximum (1)
            0x75, 0x01,       //   Report Size (1)
            0x95, 0x08,       //   Report Count (8)
            0x81, 0x02,       //   Input (Data, Variable, Absolute) - Modifier byte
            0x95, 0x01,       //   Report Count (1)
            0x75, 0x08,       //   Report Size (8)
            0x81, 0x01,       //   Input (Constant) - Reserved byte
            0x95, 0x06,       //   Report Count (6)
            0x75, 0x08,       //   Report Size (8)
            0x15, 0x00,       //   Logical Minimum (0)
            0x25, 0x65,       //   Logical Maximum (101)
            0x05, 0x07,       //   Usage Page (Key Codes)
            0x19, 0x00,       //   Usage Minimum (0)
            0x29, 0x65,       //   Usage Maximum (101)
            0x81, 0x00,       //   Input (Data, Array) - Key array (6 keys)
            0xC0              // End Collection
        ).map { it.toByte() }.toByteArray()
    }
}
