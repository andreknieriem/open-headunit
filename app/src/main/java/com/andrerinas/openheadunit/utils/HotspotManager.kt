package com.andrerinas.openheadunit.utils

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.andrerinas.openheadunit.connection.wifi.modes.native.ApBand
import com.android.dx.DexMaker
import com.android.dx.TypeId
import java.lang.reflect.Method
import java.net.Inet4Address
import java.net.NetworkInterface
import com.andrerinas.openheadunit.connection.wifi.modes.native.ApInterfaceCandidate
import com.andrerinas.openheadunit.connection.wifi.modes.native.SoftApBandPolicy
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

    /** How long to leave the access point down so a joined client notices it has gone. */
    private const val RESTART_SETTLE_MS = 2_000L

    /** TetheringManager.TETHERING_WIFI. */
    private const val TETHERING_WIFI = 0

    private var cachedCallbackClass: Class<*>? = null

    /**
     * Whether a start is already running. Bringing an access point up takes up to
     * [AP_STATE_TIMEOUT_MS] per band, and more than one caller asks for it: the credentials
     * provider auto-enables, and every "still waiting for credentials" refresh can ask again. Two
     * overlapping sweeps race each other's bands and end with a log claiming the access point came
     * up twice, on both of them.
     */
    @Volatile private var startInFlight = false

    /**
     * Takes the access point down and brings it straight back up.
     *
     * Removing the network is the only way this app can put a phone off it — nothing in the public
     * API disconnects a client from your own access point, and the one thing that comes close,
     * `SoftApConfiguration`'s blocked-client list, needs the same `setSoftApConfiguration()` call
     * that head units routinely refuse outright. So the network has to go; it does not have to stay
     * gone. Bringing it back here costs seconds nobody is waiting through, where leaving it down
     * charges the same seconds to the next connection, with the phone waiting.
     *
     * Putting it back is the part that is not free, and it is asked for **once** — measured rather
     * than assumed. Tearing an access point down and asking for it again is the sequence some
     * drivers handle worst: hostapd begins its channel scan before the interface is back, the scan
     * returns `ENODEV`, and it aborts instead of starting. Asking again at this layer does not beat
     * that. Across three runs, **nine** asks spaced ~12.5 s apart failed with that same signature
     * and not one recovered; the single bring-up that did succeed came from a start posted **127 ms**
     * behind a failed one — 270 ms in an earlier capture — landing in a window open for a few hundred
     * milliseconds and shut long before this layer can ask again. Where the hardware handles a
     * stop/start cleanly one ask is all that was ever needed; where it does not, the access point
     * stays down and the next connection's auto-enable brings it back, which is the cost this
     * restart exists to avoid, paid only on hardware that will not cooperate — rather than that same
     * cost plus half a minute of asking that never works.
     *
     * There is also a window this cannot close. Between the two calls the access point is genuinely
     * down, and a process killed outright in that window — `am force-stop`, or the system reclaiming
     * the app — runs none of the code below, so the hotspot stays off. Measured, and bounded rather
     * than fixed: the next connection's `SoftApCredentialsProvider` auto-enable switches it back on,
     * which is the same cost the restart exists to avoid paying, not a permanent break. Shrinking
     * [RESTART_SETTLE_MS] narrows the window; nothing removes it.
     */
    fun restart(context: Context): Boolean {
        AppLog.i("HotspotManager: Restarting the hotspot so any joined client is put off it.")
        setHotspotEnabled(context, false)
        try {
            Thread.sleep(RESTART_SETTLE_MS)
        } catch (e: InterruptedException) {
            // Interrupted with the access point already down, which is the one state this method
            // must not leave behind: switching one back on is best effort, and on a unit without
            // WRITE_SETTINGS nothing else can. Put it back before unwinding, then restore the flag
            // so whatever cancelled us still sees it.
            AppLog.w("HotspotManager: Interrupted while the hotspot was down; bringing it back before giving up.")
            val restored = setHotspotEnabled(context, true)
            Thread.currentThread().interrupt()
            return restored
        }

        if (setHotspotEnabled(context, true)) return true

        AppLog.e("HotspotManager: The hotspot was taken down to put the phone off the network and would not come back up. It is off now, and this app cannot force it: switch it on in system settings, or just connect again — the app switches it back on itself at the start of a connection.")
        return false
    }

    fun setHotspotEnabled(context: Context, enabled: Boolean): Boolean {
        AppLog.i("HotspotManager: Setting hotspot enabled=$enabled (API ${Build.VERSION.SDK_INT}, canWriteSettings=${AppPermissions.isWriteSettingsGranted(context)})")

        // Disabling has no band to choose and nothing to confirm afterwards, and never collides
        // with a start: only one caller ever asks for it.
        if (!enabled) return startOnBand(context, enabled = false, band = ApBand.BAND_5GHZ).attempted

        // Claimed before anything slow runs, or the WiFi-disable sleep below is long enough for a
        // second caller to walk straight past the check.
        synchronized(this) {
            if (startInFlight) {
                AppLog.i("HotspotManager: A hotspot start is already running; letting it finish rather than starting a second one.")
                return isApUp(context)
            }
            startInFlight = true
        }

        // On Android 8+, WiFi must be disabled before tethering can start. Ask, then say what
        // actually happened: setWifiEnabled() is a no-op for apps targeting API 29+ and this app
        // targets well past that, so on most devices the request is silently ignored and the
        // framework drops the station itself when it needs the radio. Announcing the attempt as if
        // it worked is how the radio state ends up being read as ours.
        try {
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

            // 5 GHz first, 2.4 GHz only if the radio will not host an access point on it. See
            // SoftApBandPolicy for why the order is not a preference.
            var attemptedAny = false
            for ((index, band) in SoftApBandPolicy.attemptOrder().withIndex()) {
                if (index > 0) {
                    AppLog.w("HotspotManager: no access point on ${SoftApBandPolicy.describe(SoftApBandPolicy.attemptOrder()[index - 1])}; retrying on ${SoftApBandPolicy.describe(band)}. Android Auto is known to drop within seconds on 2.4 GHz — if the projection dies shortly after connecting, this line is why.")
                }
                val outcome = startOnBand(context, enabled = true, band = band)
                attemptedAny = attemptedAny || outcome.attempted
                if (outcome.up) {
                    AppLog.i(describeApUp(outcome.configured, band))
                    return true
                }
                if (!outcome.configured) {
                    // The band never reached the framework, so the next one would post the same
                    // request against the same stored configuration and start the same access
                    // point again. Measured on a unit that refuses setSoftApConfiguration(): three
                    // start requests, all `channels {3=0}`, two of them tearing down an access
                    // point the previous one had just brought up.
                    AppLog.w("HotspotManager: This device would not take a band request, so trying ${SoftApBandPolicy.describe(SoftApBandPolicy.attemptOrder().last())} would start the same access point again. Leaving the band to the device.")
                    break
                }
            }

            // A request the framework accepted late still brings an access point up, after the band
            // it belonged to has been written off. Look once more before reporting failure: saying
            // no here is what makes a caller start a second, overlapping sweep.
            if (attemptedAny && awaitApUp(context)) {
                AppLog.i("HotspotManager: An access point came up after its band's window had expired; taking it. Which band it chose is not something this app can read.")
                return true
            }

            if (attemptedAny) {
                AppLog.w("HotspotManager: Every start path was tried on every band and no access point came up within ${AP_STATE_TIMEOUT_MS / 1000}s each. On a non-privileged install this usually cannot be done from an app — switch the hotspot on in system settings instead.")
            } else {
                AppLog.w("HotspotManager: All hotspot attempts failed.")
            }
            warnIfRadioLeftDown(context)
            return false
        } finally {
            startInFlight = false
        }
    }

    /**
     * What one band's worth of start attempts achieved: whether anything was tried, whether an
     * access point actually came up, and whether the band we asked for ever reached the framework.
     */
    private data class BandOutcome(val attempted: Boolean, val up: Boolean, val configured: Boolean)

    /**
     * What to say about an access point that is up, given whether the band request was accepted.
     *
     * Naming a band we only *asked* for is how a log ends up contradicting the radio. Measured on a
     * unit that refuses `setSoftApConfiguration()`: this said 2.4 GHz while the access point that
     * came up 8.7 s later was on 5745 MHz, so a reader with only the log would have concluded the
     * exact opposite of what happened. The band is not readable from an ordinary app — `SoftApInfo`
     * arrives on a callback that needs NETWORK_SETTINGS — so the honest line names what was
     * requested and what became of the request, and nothing else.
     */
    private fun describeApUp(configured: Boolean, band: ApBand): String =
        if (configured) {
            "HotspotManager: Hotspot is up, and this device accepted the request for ${SoftApBandPolicy.describe(band)}."
        } else {
            "HotspotManager: Hotspot is up, but this device refused the request for ${SoftApBandPolicy.describe(band)} — the band is whatever it already had configured, which this app cannot read. If the projection dies seconds after connecting, check the hotspot's channel: Android Auto is known to drop on 2.4 GHz."
        }

    private fun startOnBand(context: Context, enabled: Boolean, band: ApBand): BandOutcome {
        // [BUG_FIX] Must fall through, not return. enableHotspot() only calls
        // setSoftApConfiguration() — it configures an access point, it does not start one — yet
        // its `true` used to short-circuit the whole function, so on API 30+ we wrote the SSID and
        // passphrase, reported success, and ran no start path at all. The hotspot stayed off.
        val configured = SoftApConfigCompat.enableHotspot(context, enabled, band)
        if (configured) {
            AppLog.i("HotspotManager: SoftAp configuration applied; now starting the hotspot.")
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

        if (!enabled) return BandOutcome(attempted, up = false, configured = configured)

        // [BUG_FIX] Confirm the access point instead of trusting the call that asked for it. Every
        // start path here is reflection over an API whose real answer arrives later on a callback
        // we cannot construct, so `invoke()` returning tells us only that the request was posted:
        // on one head unit the framework refused it a millisecond later ("Tethering is already
        // active or in recovering") while this method reported success and logged nothing at all.
        // Only "an access point is up" is worth reporting as success.
        return BandOutcome(attempted, up = awaitApUp(context), configured = configured)
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

    @SuppressLint("NewApi")
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

    @SuppressLint("NewApi")
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
