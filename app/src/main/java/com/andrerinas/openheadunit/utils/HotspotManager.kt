package com.andrerinas.openheadunit.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.android.dx.DexMaker
import com.android.dx.TypeId
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.NetworkInterface
import com.andrerinas.openheadunit.connection.wifi.modes.native.ApInterfaceCandidate
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApNetworkPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApState

/**
 * Manages WiFi Hotspot (tethering) using reflection + dexmaker.
 */
object HotspotManager {
    private const val TAG = "OPENHU_WIFI"
    private const val CALLBACK_CLASS = "android.net.ConnectivityManager\$OnStartTetheringCallback"

    /** How long to give the framework to actually bring an access point up. */
    private const val AP_STATE_TIMEOUT_MS = 6_000L

    /** TetheringManager.TETHERING_WIFI. */
    private const val TETHERING_WIFI = 0

    private var cachedCallbackClass: Class<*>? = null

    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        AppLog.i("HotspotManager: Setting hotspot enabled=$enabled (API ${Build.VERSION.SDK_INT}, canWriteSettings=${AppPermissions.isWriteSettingsGranted(context)})")

        // On Android 8+, WiFi must be disabled before tethering can start. Ask, then say what
        // actually happened: setWifiEnabled() is a no-op for apps targeting API 29+ and this app
        // targets well past that, so on most devices the request is silently ignored and the
        // framework drops the station itself when it needs the radio. Announcing the attempt as if
        // it worked is how the radio state ends up being read as ours.
        if (enabled) {
            try {
                val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                if (wm.isWifiEnabled) {
                    @Suppress("DEPRECATION")
                    wm.isWifiEnabled = false
                    Thread.sleep(500) // Let the radio settle
                    if (wm.isWifiEnabled) {
                        AppLog.i("HotspotManager: Asked to disable WiFi and the platform ignored it (expected on modern Android); the framework will take the radio itself if it needs to.")
                    } else {
                        AppLog.i("HotspotManager: WiFi disabled before enabling hotspot.")
                    }
                }
            } catch (e: Exception) {
                AppLog.w("HotspotManager: Failed to disable WiFi: ${e.message}")
            }
        }

        // [BUG_FIX] Must fall through, not return. enableHotspot() only calls
        // setSoftApConfiguration() — it configures an access point, it does not start one — yet
        // its `true` used to short-circuit the whole function, so on API 30+ we wrote the SSID and
        // passphrase, reported success, and ran no start path at all. The hotspot stayed off.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (SoftApConfigCompat.enableHotspot(context, enabled)) {
                AppLog.i("HotspotManager: SoftAp configuration applied; now starting the hotspot.")
            }
        }
        // Newer API: TetheringManager (official) before ConnectivityManager fallback
        var attempted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            attempted = tryTetheringManager(context, enabled) || attempted
        }
        // The remaining reflection paths (startTethering / setWifiApEnabled) require the special
        // "Modify system settings" access (WRITE_SETTINGS). Without it the framework throws a
        // SecurityException, so check first and surface a clear, actionable message instead.
        if (AppPermissions.isWriteSettingsGranted(context)) {
            attempted = tryConnectivityManager(context, enabled) || attempted
            attempted = tryLegacyWifiManager(context, enabled) || attempted
        } else {
            AppLog.w("HotspotManager: Cannot enable the hotspot without the \"Modify system settings\" permission (WRITE_SETTINGS). Grant it in the setup wizard or Settings > Permissions.")
        }

        // [BUG_FIX] Confirm the access point instead of trusting the call that asked for it. Every
        // start path here is reflection over an API whose real answer arrives later on a callback
        // we cannot construct, so `invoke()` returning tells us only that the request was posted:
        // on one head unit the framework refused it a millisecond later ("Tethering is already
        // active or in recovering") while this method reported success and logged nothing at all.
        // Only "an access point is up" is worth reporting as success.
        if (!enabled) return attempted
        val up = awaitApUp(context)
        if (up) {
            AppLog.i("HotspotManager: Hotspot is up.")
        } else if (attempted) {
            AppLog.w("HotspotManager: Every start path was tried and no access point came up within ${AP_STATE_TIMEOUT_MS / 1000}s. On a non-privileged install this usually cannot be done from an app — switch the hotspot on in system settings instead.")
        } else {
            AppLog.w("HotspotManager: All hotspot attempts failed.")
        }
        if (!up) warnIfRadioLeftDown(context)
        return up
    }

    /**
     * Says so when a failed start has left the radio with neither a station nor an access point.
     *
     * The framework drops the station to free the radio for tethering; if the attempt then backs
     * out, nothing brings it back. This app cannot: setWifiEnabled() is a no-op at its target SDK,
     * so the only way out is the user toggling WiFi. Worth one clear line, because the symptom
     * downstream is a WiFi Direct group that never forms and no obvious reason why.
     */
    private fun warnIfRadioLeftDown(context: Context) {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            if (!wm.isWifiEnabled) {
                AppLog.w("HotspotManager: WiFi is off and no access point came up, so this radio is now carrying neither. Nothing here can switch WiFi back on at this target SDK — toggle WiFi on the device before trying WiFi Direct, or it will not form a group.")
            }
        } catch (e: Exception) {
            AppLog.d("HotspotManager: Could not read the WiFi state: ${e.message}")
        }
    }

    /**
     * Polls until a soft AP is up or the timeout expires.
     *
     * `getWifiApState()` is hidden but widely present; where it is blocked, fall back to looking
     * for the interface, which is what a running access point is from the outside anyway.
     */
    private fun awaitApUp(context: Context): Boolean {
        val deadline = System.currentTimeMillis() + AP_STATE_TIMEOUT_MS
        while (true) {
            if (isApUp(context)) return true
            if (System.currentTimeMillis() >= deadline) return false
            try { Thread.sleep(400) } catch (e: InterruptedException) { return false }
        }
    }

    /** Whether a soft AP is running right now, by framework state or, failing that, by interface. */
    private fun isApUp(context: Context): Boolean = when (SoftApStateReader.read(context)) {
        SoftApState.ENABLED -> true
        SoftApState.NOT_ENABLED -> false
        // Nothing was learned, so fall back to what an access point looks like from outside.
        SoftApState.UNKNOWN -> hasApInterface(context)
    }

    /**
     * Whether any interface is one this device is hosting an access point on. Uses the same policy
     * object SoftApCredentialsProvider uses to find the AP it will advertise, so the two cannot
     * disagree about whether one exists.
     */
    private fun hasApInterface(context: Context): Boolean = try {
        val stationIpv4 = NetworkAddresses.stationIpv4(context)
        NetworkInterface.getNetworkInterfaces().toList().any { nif ->
            SoftApNetworkPolicy.isApHost(
                ApInterfaceCandidate(
                    name = nif.name,
                    isLoopback = try { nif.isLoopback } catch (e: Exception) { false },
                    isUp = try { nif.isUp } catch (e: Exception) { false },
                    siteLocalIpv4 = nif.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .firstOrNull { it.isSiteLocalAddress }
                        ?.hostAddress
                ),
                stationIpv4
            )
        }
    } catch (e: Exception) {
        false
    }

    // The try* paths below return "the request was posted without throwing", not "the hotspot
    // started" — the framework answers asynchronously, on a callback we cannot build. Only
    // setHotspotEnabled() decides success, and it does so by looking for the access point.
    private fun tryConnectivityManager(context: Context, enabled: Boolean): Boolean {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            if (!enabled) {
                val stopMethod = cm.javaClass.methods.find { it.name == "stopTethering" }
                if (stopMethod != null) {
                    stopMethod.isAccessible = true
                    stopMethod.invoke(cm, 0)
                    return true
                }
                return false
            }

            val startMethod = cm.javaClass.methods.find {
                it.name == "startTethering" && it.parameterTypes.size >= 4
            } ?: return false

            startMethod.isAccessible = true
            // Same hazard as the TetheringManager path: the framework dispatches onto this object
            // without a null check. If the shim could not be built, skip rather than crash.
            val callbackInst = createTetheringCallback(context) ?: return false
            val handler = Handler(Looper.getMainLooper())

            return when (startMethod.parameterTypes.size) {
                4 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler)
                    true
                }
                5 -> {
                    startMethod.invoke(cm, 0, false, callbackInst, handler, context.packageName)
                    true
                }
                else -> false
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: CM path failed", e)
            return false
        }
    }

    /**
     * A do-nothing implementation of [type], or null if one cannot be made.
     *
     * `TetheringManager.StartTetheringCallback` is an interface whose methods are all default
     * no-ops, so a [java.lang.reflect.Proxy] is enough and none of DexMaker's problems apply. If
     * it ever turns out not to be an interface, returning null makes the caller skip the request
     * rather than fall back to passing null and crashing again.
     *
     * The handler answers `hashCode`/`equals`/`toString` itself: returning null from those is its
     * own NullPointerException the first time anything logs or hashes the object.
     */
    private fun noOpCallback(type: Class<*>): Any? = try {
        if (!type.isInterface) {
            AppLog.w("HotspotManager: ${type.name} is not an interface, so no callback can be made for it; skipping the request rather than passing null.")
            null
        } else {
            java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === args?.getOrNull(0)
                    "toString" -> "${type.simpleName}(no-op)"
                    else -> null
                }
            }
        }
    } catch (e: Exception) {
        AppLog.w("HotspotManager: Could not build a no-op ${type.simpleName}: ${e.message}")
        null
    }

    /**
     * A `TetheringRequest` for WiFi tethering, via its Builder, or null if it cannot be built.
     * Only needed on platforms that dropped the plain `(int, …)` overload of startTethering.
     */
    private fun buildTetheringRequest(): Any? = try {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass.getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)
        builderClass.getMethod("build").invoke(builder)
    } catch (e: Exception) {
        AppLog.d("HotspotManager: Could not build a TetheringRequest: ${e.message}")
        null
    }

    @Suppress("UNCHECKED_CAST")
    private fun createTetheringCallback(context: Context): Any? {
        try {
            cachedCallbackClass?.let { cls ->
                return cls.getDeclaredConstructor().newInstance()
            }

            val parentClass = Class.forName(CALLBACK_CLASS) ?: return null
            val dexMaker = DexMaker()
            val getByName: Method = TypeId::class.java.getDeclaredMethod("get", String::class.java)
            val getByClass: Method = TypeId::class.java.getDeclaredMethod("get", Class::class.java)

            val generatedType = getByName.invoke(null, "LTetheringCallback;") as TypeId<Any>
            val parentType = getByClass.invoke(null, parentClass) as TypeId<Any>

            dexMaker.declare(generatedType, "TetheringCallback.generated", java.lang.reflect.Modifier.PUBLIC, parentType)

            val constructor = generatedType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val parentConstructor = parentType.getConstructor() as com.android.dx.MethodId<Any, Void>
            val code = dexMaker.declare(constructor, java.lang.reflect.Modifier.PUBLIC)
            val thisRef = code.getThis(generatedType)
            code.invokeDirect(parentConstructor, null, thisRef)
            code.returnVoid()

            val dexCache = context.codeCacheDir
            val classLoader = dexMaker.generateAndLoad(this.javaClass.classLoader, dexCache)
            val generatedClass = classLoader.loadClass("TetheringCallback")
            cachedCallbackClass = generatedClass

            return generatedClass.getDeclaredConstructor().newInstance()
        } catch (e: Exception) {
            AppLog.e("HotspotManager: Dexmaker failed", e)
            return null
        }
    }

    private fun tryTetheringManager(context: Context, enabled: Boolean): Boolean {
        try {
            val tm = context.getSystemService("tethering") ?: return false
            if (enabled) {
                // [BUG_FIX] Pick the overload by argument *type*, not by argument count. API 34
                // carries two 3-arg startTethering methods — (TetheringRequest, Executor,
                // StartTetheringCallback) and (int, Executor, StartTetheringCallback) — and
                // find{} returned whichever the reflection order happened to yield, so passing
                // the tethering type as an Integer threw "argument 1 has type TetheringRequest".
                val overloads = tm.javaClass.methods.filter {
                    it.name == "startTethering" && it.parameterTypes.size == 3
                }
                val byType = overloads.find { it.parameterTypes[0] == Int::class.javaPrimitiveType }
                val byRequest = overloads.find { it.parameterTypes[0].name.endsWith("TetheringRequest") }
                val method = byType ?: byRequest ?: return false

                // [BUG_FIX] Never pass null for the callback. The framework dispatches
                // onTetheringStarted()/onTetheringFailed() onto it through the executor with no
                // null check, so null crashed the app outright the moment tethering finished
                // starting. It only shows up when the attempt gets far enough to report back — a
                // request refused immediately never dispatches anything, which is why this hid
                // behind an "already active" refusal the first time round.
                val callback = noOpCallback(method.parameterTypes[2]) ?: return false

                val first: Any = if (method === byType) TETHERING_WIFI else buildTetheringRequest() ?: return false
                method.invoke(tm, first, context.mainExecutor, callback)
                return true
            } else {
                val stopMethod = tm.javaClass.methods.find { it.name == "stopTethering" }
                stopMethod?.invoke(tm, 0)
                return true
            }
        } catch (e: Exception) {
            AppLog.e("HotspotManager: TetheringManager path failed", e)
            return false
        }
    }

    private fun tryLegacyWifiManager(context: Context, enabled: Boolean): Boolean {
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val method = wm.javaClass.getMethod("setWifiApEnabled", android.net.wifi.WifiConfiguration::class.java, Boolean::class.javaPrimitiveType)
            return method.invoke(wm, null, enabled) as Boolean
        } catch (_: Exception) { return false }
    }
}
