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
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.DriverCandidatePolicy
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import com.andrerinas.openheadunit.connection.wifi.MacAddressPolicy

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
     * Whether this unit holds a hands-free link, adapter-wide; null when the adapter will not say.
     * `HEADSET_CLIENT` is the role a head unit plays and the one the wake poke was measured
     * destroying. [includeGatewayRole] adds `HEADSET`, the phone's role, which some OEM stacks report
     * a hands-free connection under.
     */
    fun handsFreeLinkState(context: Context, includeGatewayRole: Boolean = true): Boolean? {
        val roles = if (includeGatewayRole) {
            intArrayOf(PROFILE_HEADSET_CLIENT, BluetoothProfile.HEADSET)
        } else {
            intArrayOf(PROFILE_HEADSET_CLIENT)
        }
        return profileLinkState(context, roles, "hands-free")
    }

    /**
     * Whether this unit holds a hands-free link in the gateway role alone: it is the phone side of
     * that link, so the other end is a car kit or headset, never a phone the wake poke could reach.
     */
    fun gatewayHandsFreeLinkState(context: Context): Boolean? =
        profileLinkState(context, intArrayOf(BluetoothProfile.HEADSET), "gateway hands-free")

    private fun profileLinkState(context: Context, roles: IntArray, what: String): Boolean? {
        val resolved = try {
            getBluetoothAdapter(context)
        } catch (e: Exception) {
            AppLog.w("BluetoothHelper: could not resolve an adapter for the $what check: ${e.message}")
            return null
        }
        val adapter = resolved ?: return false
        val enabled = try { adapter.isEnabled } catch (e: Exception) { null }
        if (enabled == false) return false

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
     * Whether [device] is connected to this unit, or null when that cannot be read.
     *
     * Reflection on the hidden `BluetoothDevice.isConnected`, which needs only BLUETOOTH_CONNECT and
     * carries no `maxTargetSdk`, so it answers on API 21 through 36. It does not exist before API 21,
     * where this returns null rather than a wrong "not connected".
     */
    fun deviceConnectionState(device: BluetoothDevice): Boolean? {
        val method = isConnectedMethod ?: return null
        return try {
            method.invoke(device) as? Boolean
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Checks whether a specific Bluetooth device is currently connected to this unit.
     *
     * An unreadable answer counts as not connected. Callers that must tell those apart - a guard
     * that would act on absence - ask [deviceConnectionState] instead.
     */
    fun isDeviceConnected(device: BluetoothDevice): Boolean = deviceConnectionState(device) == true

    /** One bonded device with the policy's verdict on it. */
    data class DriverCandidate(
        val device: BluetoothDevice,
        val classification: DriverCandidatePolicy.Classification,
        val connected: Boolean
    ) {
        val verdict: DriverCandidatePolicy.Verdict get() = classification.verdict
    }

    /**
     * Every bonded device classified, and the tier offered as a driver: the phones, else the
     * devices nothing could classify, never the ones ruled out. Those stay reachable by a
     * deliberate tap under "Show all"; nothing automatic reaches for them.
     */
    class DriverCandidates(val all: List<DriverCandidate>, val presenceReadable: Boolean) {
        val offeredVerdict: DriverCandidatePolicy.Verdict =
            DriverCandidatePolicy.offeredVerdict(all.map { it.verdict })
        val offered: List<BluetoothDevice> =
            if (offeredVerdict == DriverCandidatePolicy.Verdict.NOT_A_PHONE) emptyList()
            else all.filter { it.verdict == offeredVerdict }.map { it.device }
        val hidden: List<DriverCandidate> = all.filter { it.verdict != offeredVerdict || offered.isEmpty() }
        val connectedAll: List<BluetoothDevice> = all.filter { it.connected }.map { it.device }
        val connectedOffered: List<BluetoothDevice> =
            connectedAll.filter { device -> offered.any { it.address == device.address } }

        fun verdictOf(mac: String): DriverCandidatePolicy.Verdict =
            all.firstOrNull { it.device.address.equals(mac, ignoreCase = true) }?.verdict
                ?: DriverCandidatePolicy.Verdict.UNKNOWN

        fun deviceFor(mac: String?): BluetoothDevice? =
            mac?.let { m -> all.firstOrNull { it.device.address.equals(m, ignoreCase = true) }?.device }
    }

    @Volatile
    private var lastCandidateSummary: String? = null

    /**
     * The bonded devices classified for driver selection and the wake poke. Enumerated every poke
     * round and every resume, so the roll-up is INFO only when its composition changes.
     */
    @SuppressLint("MissingPermission")
    fun driverCandidates(context: Context, preferredMac: String, lastConnectedMac: String): DriverCandidates {
        val adapter = try { getBluetoothAdapter(context) } catch (e: Exception) { null }
        val bonded = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (e: Exception) { emptyList() }
        var presenceReadable = false
        val all = bonded.map { device ->
            val state = deviceConnectionState(device)
            if (state != null) presenceReadable = true
            val classification = classifyDevice(device, pinFor(device, preferredMac, lastConnectedMac))
            AppLog.d(
                "BluetoothHelper: driver candidate ${device.name} (${device.address}) -> " +
                    "${classification.verdict}, ${DriverCandidatePolicy.reasonText(classification, deviceClassOf(device))}"
            )
            DriverCandidate(device, classification, state == true)
        }
        val candidates = DriverCandidates(all, presenceReadable)
        val counts = DriverCandidatePolicy.Verdict.entries.joinToString(", ") { verdict ->
            "${all.count { it.verdict == verdict }} ${verdict.name.lowercase().replace('_', ' ')}"
        }
        val hidden = candidates.hidden.joinToString {
            "${it.device.name} (${it.classification.reason.name.lowercase().replace('_', ' ')})"
        }
        val summary = "BluetoothHelper: driver candidates: $counts" +
            if (hidden.isEmpty()) "" else " - hidden: $hidden"
        if (summary != lastCandidateSummary) {
            lastCandidateSummary = summary
            AppLog.i(summary)
        } else {
            AppLog.d(summary)
        }
        return candidates
    }

    /** The policy's verdict on one device, read from what the bond cached: records, class, type. */
    @SuppressLint("MissingPermission")
    fun classifyDevice(device: BluetoothDevice, pin: DriverCandidatePolicy.Pin): DriverCandidatePolicy.Classification {
        val uuids = try { device.uuids?.map { it.uuid.toString() } } catch (e: Exception) { null }
        val btClass = try { device.bluetoothClass } catch (e: Exception) { null }
        val type = if (Build.VERSION.SDK_INT >= 18) {
            try { device.type } catch (e: Exception) { DriverCandidatePolicy.DEVICE_TYPE_UNKNOWN }
        } else {
            DriverCandidatePolicy.DEVICE_TYPE_UNKNOWN
        }
        return DriverCandidatePolicy.classify(
            uuids = uuids,
            hasDeviceClass = btClass != null,
            majorDeviceClass = btClass?.majorDeviceClass ?: 0,
            deviceClass = btClass?.deviceClass ?: 0,
            deviceType = type,
            pin = pin
        )
    }

    /** The last-connected MAC was written by a completed handshake; the preferred one was typed. */
    fun pinFor(device: BluetoothDevice, preferredMac: String, lastConnectedMac: String): DriverCandidatePolicy.Pin {
        val address = try { device.address } catch (_: Exception) { "" }
        return when {
            address.isEmpty() -> DriverCandidatePolicy.Pin.NONE
            address.equals(lastConnectedMac, ignoreCase = true) -> DriverCandidatePolicy.Pin.PROVEN
            address.equals(preferredMac, ignoreCase = true) -> DriverCandidatePolicy.Pin.USER
            else -> DriverCandidatePolicy.Pin.NONE
        }
    }

    private fun deviceClassOf(device: BluetoothDevice): Int =
        try { device.bluetoothClass?.deviceClass ?: 0 } catch (e: Exception) { 0 }

    /**
     * Whether one device may stand as a phone, meaning it was not ruled out. Lists go through
     * [driverCandidates]; this serves the pickers that ask about one device at a time.
     */
    fun isLikelyPhone(
        device: BluetoothDevice,
        preferredMac: String = "",
        lastConnectedMac: String = ""
    ): Boolean = classifyDevice(device, pinFor(device, preferredMac, lastConnectedMac)).verdict !=
        DriverCandidatePolicy.Verdict.NOT_A_PHONE


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
            val normalized = normalizeMacAddress(SystemProperties.get(key, ""))
            if (normalized != null) {
                AppLog.i("BluetoothHelper: Resolved hardware BT MAC $normalized from property $key")
                return normalized
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
     * A positive answer is held for the process; a negative one is re-read, because the vendor app
     * sets its properties only once it has run after a boot.
     */
    private val externalBtLatch = ExternalBtPolicy.Latch {
        ExternalBtPolicy.detect(
            nodeExists = { path -> try { java.io.File(path).exists() } catch (e: Exception) { false } },
            property = { key -> SystemProperties.get(key, "") }
        )
    }

    val externalBtEvidence: String?
        get() = externalBtLatch.evidence()

    /**
     * The address as canonical `AA:BB:CC:DD:EE:FF`, or null when it is not an address at all.
     *
     * Bluetooth is the one caller that accepts the separator-less form, because vendor properties
     * publish it that way. [MacAddressPolicy.parse] carries the rule and what it cost to learn.
     */
    fun normalizeMacAddress(raw: String?): String? = MacAddressPolicy.parse(raw, allowBare = true)

    private fun isValidMacAddress(mac: String): Boolean = normalizeMacAddress(mac) != null

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
