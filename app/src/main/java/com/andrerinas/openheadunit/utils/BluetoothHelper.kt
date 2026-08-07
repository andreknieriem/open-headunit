package com.andrerinas.openheadunit.utils

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.os.IBinder
import com.andrerinas.openheadunit.App
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
