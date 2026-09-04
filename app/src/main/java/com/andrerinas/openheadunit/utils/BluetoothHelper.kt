package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.IBinder
import com.andrerinas.openheadunit.App
import java.lang.reflect.Constructor
import java.lang.reflect.Method

object BluetoothHelper {

    fun getBluetoothAdapter(context: Context): BluetoothAdapter? {
        val settings = App.provide(context).settings
        val serviceName = settings.bluetoothManagerServiceName

        if (serviceName.isEmpty() || serviceName == "bluetooth_manager") {
            return getDefaultAdapter(context)
        }

        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder ?: return getDefaultAdapter(context)

            val iBluetoothManagerStubClass = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
            val asInterfaceMethod = iBluetoothManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val managerService = asInterfaceMethod.invoke(null, binder) ?: return getDefaultAdapter(context)

            val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager")
            val ctor = BluetoothAdapter::class.java.getDeclaredConstructor(iBluetoothManagerClass)
            ctor.isAccessible = true
            return ctor.newInstance(managerService) as? BluetoothAdapter
        } catch (e: Exception) {
            AppLog.e("BluetoothHelper: Failed to instantiate custom BluetoothAdapter with service $serviceName, falling back: ${e.message}", e)
        }

        return getDefaultAdapter(context)
    }

    private fun getDefaultAdapter(context: Context): BluetoothAdapter? {
        return if (Build.VERSION.SDK_INT >= 18) {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        } else {
            @Suppress("DEPRECATION")
            BluetoothAdapter.getDefaultAdapter()
        }
    }

    /** Instantiate the BluetoothAdapter backed by a specific system bluetooth service (reflection). */
    private fun adapterForService(context: Context, serviceName: String): BluetoothAdapter? {
        if (serviceName.isEmpty() || serviceName == "bluetooth_manager") return getDefaultAdapter(context)
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder ?: return null
            val iBluetoothManagerStubClass = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
            val asInterfaceMethod = iBluetoothManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val managerService = asInterfaceMethod.invoke(null, binder) ?: return null
            val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager")
            val ctor = BluetoothAdapter::class.java.getDeclaredConstructor(iBluetoothManagerClass)
            ctor.isAccessible = true
            ctor.newInstance(managerService) as? BluetoothAdapter
        } catch (e: Exception) {
            // Not an error. The service sweep matches on name fragments, so most candidates are not
            // Bluetooth managers at all and throw here; skipping them is the documented outcome.
            AppLog.d("BluetoothHelper: adapterForService($serviceName) failed: ${e.message}")
            null
        }
    }

    data class BluetoothAdapterHandle(val serviceName: String, val adapter: BluetoothAdapter)

    /**
     * All distinct, enabled Bluetooth adapters exposed by the system, paired with the system
     * service name each is backed by. Usually just the default radio; some head units expose a
     * second Bluetooth chip as an extra service. Best-effort: bogus/non-adapter services resolve
     * to null and are skipped.
     */
    fun getAllBluetoothAdapterHandles(context: Context): List<BluetoothAdapterHandle> {
        val result = mutableListOf<BluetoothAdapterHandle>()
        getDefaultAdapter(context)?.let { result.add(BluetoothAdapterHandle("bluetooth_manager", it)) }
        for (service in listBluetoothServices()) {
            if (service == "bluetooth_manager") continue
            adapterForService(context, service)?.let { result.add(BluetoothAdapterHandle(service, it)) }
        }
        return result.filter { try { it.adapter.isEnabled } catch (e: Exception) { false } }
    }

    /**
     * `BluetoothProfile.HEADSET_CLIENT`. Hidden from the SDK, but
     * [BluetoothAdapter.getProfileConnectionState] takes a plain profile int and answers for it, and
     * this is the hands-free role a head unit plays: the phone is the audio gateway, the unit is the
     * hands-free device that carries the call.
     */
    private const val PROFILE_HEADSET_CLIENT = 16

    /**
     * Whether this head unit currently holds a Bluetooth hands-free link, in either role.
     *
     * Null when the adapter will not say. Callers must treat that as "no link known" rather than
     * "no link", because the only decision hanging off this is whether to skip an action that is
     * load-bearing elsewhere.
     *
     * `HEADSET_CLIENT` is the role a head unit plays and the one the wake poke was measured
     * destroying. [includeGatewayRole] adds `HEADSET`, the role the *phone* plays, because some OEM
     * stacks report a hands-free connection under it instead. Callers want different widths because
     * they pay differently for a false "yes": the poke asks "would connecting take the phone's
     * slot", where over-reporting costs one skipped poke, so it reads both. Standing in as a
     * hands-free device asks "is a real one already here", where over-reporting costs the whole
     * wireless session, and a gateway link on this device is this unit's own headset rather than
     * anything competing with the stand-in, so it reads the client role alone.
     *
     * Profile-wide, not per-device: `getProfileConnectionState` answers for the adapter, and the
     * per-device equivalents are system-only. On a head unit paired with one phone that distinction
     * does not arise; where it does, this errs toward reporting a link.
     */
    fun handsFreeLinkState(context: Context, includeGatewayRole: Boolean = true): Boolean? {
        val resolved = try {
            getBluetoothAdapter(context)
        } catch (e: Exception) {
            AppLog.w("BluetoothHelper: could not resolve an adapter for the hands-free check: ${e.message}")
            return null
        }
        val adapter = resolved ?: return false
        val enabled = try { adapter.isEnabled } catch (e: Exception) { null }
        if (enabled == false) return false

        val roles = if (includeGatewayRole) {
            intArrayOf(PROFILE_HEADSET_CLIENT, BluetoothProfile.HEADSET)
        } else {
            intArrayOf(PROFILE_HEADSET_CLIENT)
        }
        var readAnyState = false
        for (profile in roles) {
            val state = try {
                adapter.getProfileConnectionState(profile)
            } catch (e: Exception) {
                // SecurityException without BLUETOOTH_CONNECT, or an adapter that rejects the
                // hidden client profile. Try any remaining role before giving up.
                continue
            }
            readAnyState = true
            if (state == BluetoothProfile.STATE_CONNECTED) return true
        }
        return if (readAnyState) false else null
    }

    /**
     * `BluetoothProfile.A2DP_SINK`. Hidden from the SDK, but [BluetoothAdapter.getProfileConnectionState]
     * takes a plain profile int and answers for it, and the sink role is the one a head unit plays:
     * the phone is the source, we render its audio.
     */
    private const val PROFILE_A2DP_SINK = 11

    /**
     * Whether a Bluetooth media link to this head unit is up, in either role.
     *
     * Used to decide against taking system audio focus for Android Auto playback: when the phone is
     * also our A2DP source, the sink service answers our focus grab with an AVRCP pause aimed at
     * that same phone, which stops the stream we are trying to play. Callers treat an unknown
     * answer as "a link may be up", so this returns true when the state cannot be read — a car
     * radio playing over AA is an annoyance, silence is a broken app.
     */
    fun isA2dpMediaLinkActive(context: Context): Boolean = a2dpMediaLinkState(context) ?: true

    /**
     * The same probe as [isA2dpMediaLinkActive], but saying so when it does not know: null means no
     * profile state could be read at all, rather than "no link".
     *
     * The two callers want opposite things from that answer. Audio focus treats unknown as a link
     * being up, because a car radio playing over Android Auto is an annoyance and silence is a
     * broken app. Media-key routing treats unknown as no link, because a doubled track skip is an
     * annoyance and buttons that quietly do nothing are a broken app. Neither default is right for
     * both, so the resolution belongs to the caller.
     */
    fun a2dpMediaLinkState(context: Context): Boolean? {
        // The configured adapter only, never getAllBluetoothAdapterHandles(): this is called on the
        // AAP transport thread every time a track starts, and enumerating the system service list
        // by reflection there would stall video alongside audio.
        val adapter = try {
            getBluetoothAdapter(context)
        } catch (e: Exception) {
            AppLog.w("BluetoothHelper: could not resolve an adapter for the A2DP check: ${e.message}")
            return null
        }
        if (adapter == null) return false
        // An adapter that will not say whether it is on is treated as on, and left to the profile
        // probe below to decide.
        val enabled = try { adapter.isEnabled } catch (e: Exception) { true }
        if (!enabled) return false

        var readAnyState = false
        for (profile in intArrayOf(BluetoothProfile.A2DP, PROFILE_A2DP_SINK)) {
            val state = try {
                adapter.getProfileConnectionState(profile)
            } catch (e: Exception) {
                // SecurityException without BLUETOOTH_CONNECT, or an adapter that rejects the
                // hidden sink profile. Try the other one before giving up.
                continue
            }
            readAnyState = true
            if (state == BluetoothProfile.STATE_CONNECTED || state == BluetoothProfile.STATE_CONNECTING) {
                return true
            }
        }
        if (!readAnyState) {
            AppLog.w("BluetoothHelper: the adapter would not report its A2DP state")
            return null
        }
        return false
    }

    private val isConnectedMethod: Method? by lazy {
        try {
            BluetoothDevice::class.java.getMethod("isConnected")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks whether a specific Bluetooth device is currently connected to this unit.
     *
     * Uses reflection on the hidden `@hide` [BluetoothDevice.isConnected] method (available since
     * Android 4.0 / API 14), making it compatible from SDK 16 up to Android 15. Catches any security
     * or reflection exceptions and returns false if unreadable.
     */
    fun isDeviceConnected(device: BluetoothDevice): Boolean {
        return try {
            isConnectedMethod?.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Determines whether a Bluetooth device is likely to be a smartphone / Android Auto candidate,
     * as opposed to dedicated audio accessories (Bluetooth speakers, headphones, headsets),
     * wearables (watches), or peripherals (keyboards, mice).
     *
     * Never filters out a device that the user previously connected to or designated as preferred.
     * Safe on all Android versions (SDK 16+).
     */
    fun isLikelyPhone(
        device: BluetoothDevice,
        preferredMac: String = "",
        lastUsedMac: String = ""
    ): Boolean {
        val address = try { device.address } catch (_: Exception) { "" }
        if (address.isNotEmpty()) {
            if (address.equals(preferredMac, ignoreCase = true) || address.equals(lastUsedMac, ignoreCase = true)) {
                return true
            }
        }

        val btClass = try { device.bluetoothClass } catch (_: Exception) { null } ?: return true
        val major = btClass.majorDeviceClass

        // Definite non-phone peripherals: mice, keyboards, watches, printers
        if (major == android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL ||
            major == android.bluetooth.BluetoothClass.Device.Major.WEARABLE ||
            major == android.bluetooth.BluetoothClass.Device.Major.IMAGING) {
            return false
        }

        // Filter out dedicated audio output accessories (speakers, headphones, headsets)
        if (major == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO) {
            return when (btClass.deviceClass) {
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> false
                else -> true
            }
        }

        return true
    }

    /**
     * Returns the list of bonded Bluetooth devices that are confirmed to be currently connected.
     * Safe on all Android versions (SDK 16+).
     */
    fun getConnectedBluetoothDevices(context: Context): List<BluetoothDevice> {
        val adapter = try {
            getBluetoothAdapter(context)
        } catch (e: Exception) {
            null
        } ?: return emptyList()

        val bonded = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        return bonded.filter { isDeviceConnected(it) }
    }


    /**
     * Resolves the real Bluetooth MAC address of this head unit's Bluetooth chip, or null.
     *
     * `adapter.address` is the fixed placeholder `02:00:00:00:00:00` for any non-privileged app
     * since Android 6.0, so most of this is fallbacks. When they all fail, [logAddressSourceDump]
     * says what each one answered, because "could not be read" tells a reporter's log nothing.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getBluetoothMacAddress(context: Context, adapter: BluetoothAdapter? = null): String? {
        val targetAdapter = adapter ?: getBluetoothAdapter(context)

        // 1. The public API. Masked on every device since API 23, kept for the ones that are not.
        val direct = adapterAddress(targetAdapter)
        if (direct != null) return direct.uppercase()

        // 2. Reflection over getAddress(). Resolves to the same public method on a stock ROM, so it
        // only differs where a vendor overrode it - which some head units do.
        val reflected = reflectedAddress(targetAdapter)
        if (reflected != null) return reflected.uppercase()

        // 3. Where Android's own settings screen reads it, and no permission gates it.
        val secure = secureSettingAddress(context)
        if (secure != null) return secure.uppercase()

        // 4. Vendor properties, for head units that publish it.
        for (key in ADDRESS_PROPERTY_KEYS) {
            val valStr = SystemProperties.get(key, "").trim()
            if (valStr.isNotEmpty() && isValidMacAddress(valStr)) {
                AppLog.i("BluetoothHelper: Resolved hardware BT MAC $valStr from property $key")
                return valStr.uppercase()
            }
        }

        logAddressSourceDump(context, targetAdapter)
        return null
    }

    private val ADDRESS_PROPERTY_KEYS = arrayOf(
        "persist.sys.bt.mac",
        "persist.sys.btmac",
        "persist.vendor.bt.mac",
        "sys.bt.mac",
        "persist.sys.bluetooth.mac",
        "ro.boot.btmacaddr",
        "vendor.bt.bdaddr",
        "persist.zj.BTmac",
        "persist.zlink.carplay.mac",
        "sys.bt.bdaddr"
    )

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun adapterAddress(adapter: BluetoothAdapter?): String? = try {
        adapter?.address?.takeIf { isValidMacAddress(it) }
    } catch (e: SecurityException) {
        AppLog.w("BluetoothHelper: SecurityException reading adapter address")
        null
    } catch (e: Exception) {
        null
    }

    private fun reflectedAddress(adapter: BluetoothAdapter?): String? = try {
        adapter?.let {
            val method = it.javaClass.getMethod("getAddress")
            method.isAccessible = true
            (method.invoke(it) as? String)?.takeIf { addr -> isValidMacAddress(addr) }
        }
    } catch (e: Exception) {
        null
    }

    private fun secureSettingAddress(context: Context): String? = try {
        android.provider.Settings.Secure
            .getString(context.contentResolver, "bluetooth_address")
            ?.trim()
            ?.takeIf { isValidMacAddress(it) }
    } catch (e: Exception) {
        null
    }

    /** Once per process: the answer is a property of the hardware, and this is called per row render. */
    @Volatile
    private var addressSourceDumped = false

    /**
     * One line per source with what it answered, in the shape of WifiDirectManager's BSSID dump.
     * Logged only when every source failed, which is the only time the detail is worth the lines.
     */
    private fun logAddressSourceDump(context: Context, adapter: BluetoothAdapter?) {
        if (addressSourceDumped) return
        addressSourceDumped = true
        AppLog.i("BluetoothHelper: == Bluetooth address source dump ==")
        val sources = linkedMapOf<String, String?>(
            "adapter.address" to try { adapter?.address } catch (e: Exception) { null },
            "getAddress() reflection" to try {
                adapter?.javaClass?.getMethod("getAddress")
                    ?.also { it.isAccessible = true }
                    ?.invoke(adapter) as? String
            } catch (e: Exception) { null },
            "Settings.Secure bluetooth_address" to try {
                android.provider.Settings.Secure.getString(context.contentResolver, "bluetooth_address")
            } catch (e: Exception) { null }
        )
        for (key in ADDRESS_PROPERTY_KEYS) {
            sources["property $key"] = SystemProperties.get(key, "").trim().ifEmpty { null }
        }
        for ((label, value) in sources) {
            AppLog.i("BluetoothHelper:   ${label.padEnd(36)} = ${value ?: "null"}")
        }
        AppLog.i("BluetoothHelper: == end Bluetooth address source dump ==")
        AppLog.w("BluetoothHelper: no source named this unit's Bluetooth address. Read it under " +
            "Settings/About/Status, or off the paired phone under the gear beside this device, and " +
            "enter it under Wireless connection. Without it the phone is not told where to connect " +
            "hands-free and no setup QR can be drawn.")
    }

    /**
     * Evidence that this head unit's Bluetooth is an external module on a serial link rather than
     * the radio behind `android.bluetooth`, or null when it is a normal built-in radio. See
     * [ExternalBtPolicy] for what the evidence means and why it decides whether Bluetooth-based
     * wireless can work here at all.
     *
     * Cached: the answer is a property of the hardware and cannot change within a process, and
     * this is consulted on every handshake start.
     */
    val externalBtEvidence: String? by lazy {
        ExternalBtPolicy.detect(
            nodeExists = { path -> try { java.io.File(path).exists() } catch (e: Exception) { false } },
            property = { key -> SystemProperties.get(key, "") }
        )
    }

    private fun isValidMacAddress(mac: String): Boolean {
        if (mac == "02:00:00:00:00:00" || mac == "00:00:00:00:00:00") return false
        val regex = Regex("^([0-9A-Fa-f]{2}[:-]){5}([0-9A-Fa-f]{2})$")
        return regex.matches(mac)
    }

    fun listBluetoothServices(): List<String> {
        val bluetoothServices = mutableListOf<String>()
        val keywords = listOf("bluetooth", "bt", "syu", "hct", "mtc", "goc", "winca", "qf")
        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val listServicesMethod = serviceManagerClass.getMethod("listServices")
            val services = listServicesMethod.invoke(null) as? Array<String>
            if (services != null) {
                for (service in services) {
                    val lower = service.lowercase()
                    if (keywords.any { lower.contains(it) }) {
                        bluetoothServices.add(service)
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("BluetoothHelper: Failed to list bluetooth services from ServiceManager: ${e.message}", e)
        }

        if (!bluetoothServices.contains("bluetooth_manager")) {
            bluetoothServices.add(0, "bluetooth_manager")
        }
        return bluetoothServices.distinct()
    }

    fun getAdapterDescription(context: Context, serviceName: String): String {
        if (serviceName == "bluetooth_manager") {
            val adapter = getDefaultAdapter(context)
            val name = try { adapter?.name } catch (e: SecurityException) { null }
            val address = getBluetoothMacAddress(context, adapter)
            val suffix = if (!name.isNullOrEmpty()) " ($name)" else ""
            val addrSuffix = if (!address.isNullOrEmpty()) " [$address]" else ""
            return "Default ($serviceName)$suffix$addrSuffix"
        }

        try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, serviceName) as? IBinder ?: return serviceName

            val iBluetoothManagerStubClass = Class.forName("android.bluetooth.IBluetoothManager\$Stub")
            val asInterfaceMethod = iBluetoothManagerStubClass.getMethod("asInterface", IBinder::class.java)
            val managerService = asInterfaceMethod.invoke(null, binder) ?: return serviceName

            val iBluetoothManagerClass = Class.forName("android.bluetooth.IBluetoothManager")
            val ctor = BluetoothAdapter::class.java.getDeclaredConstructor(iBluetoothManagerClass)
            ctor.isAccessible = true
            val adapter = ctor.newInstance(managerService) as? BluetoothAdapter
            val name = try { adapter?.name } catch (e: SecurityException) { null }
            val address = getBluetoothMacAddress(context, adapter)
            val suffix = if (!name.isNullOrEmpty()) " ($name)" else ""
            val addrSuffix = if (!address.isNullOrEmpty()) " [$address]" else ""
            return "Secondary ($serviceName)$suffix$addrSuffix"
        } catch (e: Exception) {
            return "Secondary ($serviceName)"
        }
    }
}
