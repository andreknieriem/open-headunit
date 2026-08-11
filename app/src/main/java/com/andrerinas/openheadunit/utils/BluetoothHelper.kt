package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.os.IBinder
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.ExternalBtPolicy
import java.lang.reflect.Constructor

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
            AppLog.e("BluetoothHelper: adapterForService($serviceName) failed: ${e.message}", e)
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

    /** All distinct, enabled Bluetooth adapters exposed by the system. See [getAllBluetoothAdapterHandles]. */
    fun getAllBluetoothAdapters(context: Context): List<BluetoothAdapter> =
        getAllBluetoothAdapterHandles(context).map { it.adapter }

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
     * Both roles are read. `HEADSET_CLIENT` is the one a head unit normally plays and the one the
     * wake poke was measured destroying; `HEADSET` is checked too because some OEM stacks report a
     * hands-free connection under the gateway role instead, and for this decision a false "yes" only
     * costs a skipped poke while a false "no" costs the user their calls.
     *
     * Profile-wide, not per-device: `getProfileConnectionState` answers for the adapter, and the
     * per-device equivalents are system-only. On a head unit paired with one phone that distinction
     * does not arise; where it does, this errs toward reporting a link.
     */
    fun handsFreeLinkState(context: Context): Boolean? {
        val resolved = try {
            getBluetoothAdapter(context)
        } catch (e: Exception) {
            AppLog.w("BluetoothHelper: could not resolve an adapter for the hands-free check: ${e.message}")
            return null
        }
        val adapter = resolved ?: return false
        val enabled = try { adapter.isEnabled } catch (e: Exception) { null }
        if (enabled == false) return false

        var readAnyState = false
        for (profile in intArrayOf(PROFILE_HEADSET_CLIENT, BluetoothProfile.HEADSET)) {
            val state = try {
                adapter.getProfileConnectionState(profile)
            } catch (e: Exception) {
                // SecurityException without BLUETOOTH_CONNECT, or an adapter that rejects the
                // hidden client profile. Try the other role before giving up.
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

    /** Resolve a single, arbitrary system service name to a BluetoothAdapterHandle, if it backs a
     *  real, enabled adapter. Used for the manual secondary-radio override in Settings, where the
     *  user supplies a service name automatic discovery (listBluetoothServices()) didn't find.
     *  Forgiving of the AIDL interface descriptor `adb shell service list` prints in brackets
     *  (e.g. "com.qf.btsdk.IBTBinderPool") in place of the actual service name before the colon
     *  (e.g. "btBinderPool"): users copy the bracketed part, it being the more distinctive-looking
     *  of the two. */
    fun getAdapterHandleForService(context: Context, serviceName: String): BluetoothAdapterHandle? {
        val input = serviceName.trim().removePrefix("[").removeSuffix("]").trim()
        if (input.isEmpty()) return null
        val resolvedName = if (serviceExists(input)) input else findServiceNameByInterfaceDescriptor(input) ?: input
        val adapter = adapterForService(context, resolvedName) ?: return null
        return try { if (adapter.isEnabled) BluetoothAdapterHandle(resolvedName, adapter) else null }
        catch (e: Exception) { null }
    }

    private fun serviceExists(name: String): Boolean = try {
        val serviceManagerClass = Class.forName("android.os.ServiceManager")
        val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
        getServiceMethod.invoke(null, name) != null
    } catch (e: Exception) { false }

    /** Reverse-looks-up a system service by its AIDL interface descriptor (the bracketed part of
     *  `adb shell service list` output) instead of its name (the part before the colon).
     *  IBinder.getInterfaceDescriptor() is a standard, side-effect-free query safe to call on any
     *  service, unlike actually invoking interface methods on a mismatched proxy - so this is safe
     *  to run across every registered service, not just ones already known to be Bluetooth-shaped. */
    private fun findServiceNameByInterfaceDescriptor(descriptor: String): String? {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val listServicesMethod = serviceManagerClass.getMethod("listServices")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val services = listServicesMethod.invoke(null) as? Array<String> ?: return null
            services.firstOrNull { name ->
                try {
                    val binder = getServiceMethod.invoke(null, name) as? IBinder
                    binder?.interfaceDescriptor == descriptor
                } catch (e: Exception) {
                    false
                }
            }
        } catch (e: Exception) {
            AppLog.e("BluetoothHelper: findServiceNameByInterfaceDescriptor($descriptor) failed: ${e.message}", e)
            null
        }
    }

    /**
     * Resolves the real Bluetooth MAC address of the headunit's Bluetooth chip.
     * On Android 6+ (API 23+), adapter.address returns dummy "02:00:00:00:00:00".
     * We fall back to reflection and Chinese OEM SystemProperties to find the true hardware BDADDR.
     */
    @SuppressLint("MissingPermission", "HardwareIds")
    fun getBluetoothMacAddress(context: Context, adapter: BluetoothAdapter? = null): String? {
        val targetAdapter = adapter ?: getBluetoothAdapter(context)

        // 1. Try standard adapter property if valid
        try {
            val addr = targetAdapter?.address
            if (!addr.isNullOrEmpty() && addr != "02:00:00:00:00:00" && addr != "00:00:00:00:00:00") {
                return addr.uppercase()
            }
        } catch (e: SecurityException) {
            AppLog.w("BluetoothHelper: SecurityException reading adapter address")
        } catch (e: Exception) {}

        // 2. Try reflection via hidden getAddress() on BluetoothAdapter / IBluetooth
        try {
            if (targetAdapter != null) {
                val getAddressMethod = targetAdapter.javaClass.getMethod("getAddress")
                getAddressMethod.isAccessible = true
                val addr = getAddressMethod.invoke(targetAdapter) as? String
                if (!addr.isNullOrEmpty() && addr != "02:00:00:00:00:00" && addr != "00:00:00:00:00:00") {
                    return addr.uppercase()
                }
            }
        } catch (e: Exception) {}

        // 3. Fallback to Chinese Headunit Vendor System Properties
        val propKeys = arrayOf(
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

        for (key in propKeys) {
            val valStr = SystemProperties.get(key, "").trim()
            if (valStr.isNotEmpty() && isValidMacAddress(valStr)) {
                AppLog.i("BluetoothHelper: Resolved hardware BT MAC $valStr from property $key")
                return valStr.uppercase()
            }
        }

        return null
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
