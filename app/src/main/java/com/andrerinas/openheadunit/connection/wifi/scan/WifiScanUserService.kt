package com.andrerinas.openheadunit.connection.wifi.scan

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.andrerinas.openheadunit.IWifiScanControl
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Shizuku creates this class in a separate shell process; it is not an Android Service. */
class WifiScanUserService(context: Context) : IWifiScanControl.Stub() {
    // Shizuku supplies a default Application, not Open Headunit's Application. Use shell
    // attribution for shell-mode calls rather than attributing privileged calls to the app UID.
    private val shell = context.createPackageContext("com.android.shell", 0)
    private val wifi by lazy { shell.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val released = ReleasedScanLeases()
    private data class Held(val id: String, val mode: Int, val original: Int)
    private var owner: IBinder? = null
    private var held: Held? = null
    private val death = IBinder.DeathRecipient { synchronized(this) {
        held?.let { runCatching { restore(it.id, it.mode, it.original) } }
    } }
    private val lease = ScanControlLease(::readState, ::writeState)

    @Synchronized override fun readState(mode: Int): Int = when (mode) {
        ScanControlPolicy.AUTOJOIN -> {
            check(Build.VERSION.SDK_INT >= 33)
            val result = CompletableFuture<Boolean>()
            wifi.queryAutojoinGlobal({ it.run() }) { result.complete(it) }
            if (result.get(4, TimeUnit.SECONDS)) 1 else 0
        }
        ScanControlPolicy.LEGACY_AUTOJOIN -> {
            check(Build.VERSION.SDK_INT in 30..32)
            check(shell.checkSelfPermission("android.permission.NETWORK_SETTINGS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            ScanControlPolicy.UNKNOWN
        }
        ScanControlPolicy.LEGACY_CONNECTIVITY -> {
            check(Build.VERSION.SDK_INT in 26..29)
            check(shell.checkSelfPermission("android.permission.CONNECTIVITY_INTERNAL") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
            legacyConnectivityMethod() // Detect removed OEM methods before recording a lease.
            ScanControlPolicy.UNKNOWN
        }
        ScanControlPolicy.HOTSPOT_SCAN -> {
            // A lease from Android 12 can survive an OTA to 13+. Its persistent
            // scan-always value still needs restoration even though new sessions use autojoin.
            check(Build.VERSION.SDK_INT >= 26)
            if (Build.VERSION.SDK_INT <= 29) {
                android.provider.Settings.Global.getInt(shell.contentResolver, "wifi_scan_always_enabled", 0)
            } else {
                // R+ stores this outside Settings.Global. The public getter includes airplane
                // mode, so it cannot reveal the stored value while airplane mode is enabled.
                check(android.provider.Settings.Global.getInt(shell.contentResolver,
                    android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 0) { "Scan state hidden by airplane mode" }
                if (wifi.isScanAlwaysAvailable) 1 else 0
            }
        }
        else -> error("Unsupported scan control")
    }

    private fun legacyConnectivityMethod(): java.lang.reflect.Method = try {
        WifiManager::class.java.getMethod("enableWifiConnectivityManager", Boolean::class.javaPrimitiveType)
    } catch (error: ReflectiveOperationException) {
        throw IllegalStateException("Wi-Fi connectivity control is unavailable", error)
    }

    private fun writeState(mode: Int, value: Int) {
        when (mode) {
            ScanControlPolicy.AUTOJOIN -> {
                check(Build.VERSION.SDK_INT >= 33)
                wifi.allowAutojoinGlobal(value == 1)
            }
            ScanControlPolicy.LEGACY_AUTOJOIN -> {
                check(Build.VERSION.SDK_INT >= 30)
                // AOSP R/S WifiServiceImpl.allowAutojoinGlobal checks NETWORK_SETTINGS and
                // sets WifiConnectivityManager.mAutoJoinEnabledExternal. stop() cancels
                // periodic/PNO scans without removing the P2P group. queryAutojoinGlobal
                // only arrived in T; acceptance here cannot establish the previous state.
                // Source: frameworks/opt/net/wifi android-11.0.0_r1 WifiServiceImpl and
                // packages/modules/Wifi android-12.1.0_r1 WifiConnectivityManager.
                wifi.allowAutojoinGlobal(value == 1)
            }
            ScanControlPolicy.LEGACY_CONNECTIVITY -> {
                check(Build.VERSION.SDK_INT in 26..29)
                // AOSP O–Q WifiServiceImpl.enableWifiConnectivityManager checks
                // CONNECTIVITY_INTERNAL (held by shell), then posts a state-machine command.
                // WifiNetworkFactory can re-enable this flag. Renew sparingly: in O/P, even a
                // repeated false calls stop() -> clearBssidBlacklist() -> firmware roaming
                // configuration. Q adds an mRunning guard, but OEM implementations vary.
                // Source: android-8.0.0_r1 through android-10.0.0_r1
                // frameworks/opt/net/wifi, WifiConnectivityManager.enable/stop.
                // No current-state getter exists, so release explicitly enables the manager.
                try {
                    legacyConnectivityMethod().invoke(wifi, value == 1)
                } catch (error: ReflectiveOperationException) {
                    // Reflection wraps a remote refusal in a checked exception that Binder
                    // cannot marshal. Preserve it as a supported exception, keeping the
                    // helper alive so its lease can still be released or retried.
                    throw IllegalStateException("Wi-Fi connectivity control failed", error.cause ?: error)
                }
            }
            ScanControlPolicy.HOTSPOT_SCAN -> {
                // From R this lives in WifiSettingsConfigStore, not Settings.Global.
                if (Build.VERSION.SDK_INT >= 30) command("/system/bin/cmd", "wifi", "set-scan-always-available",
                    if (value == 1) "enabled" else "disabled")
                else command("/system/bin/settings", "put", "global", "wifi_scan_always_enabled", value.toString())
            }
            else -> error("Unsupported scan control")
        }
        // The legacy APIs expose no readback. Do not fake an observed disabled state, or
        // poll forever waiting for a getter which does not exist on these Android releases.
        if (!ScanControlPolicy.hasReadback(mode)) return
        val deadline = android.os.SystemClock.elapsedRealtime() + 5_000
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            if (readState(mode) == value) return
            Thread.sleep(50)
        }
        error("Wi-Fi did not apply scan control")
    }

    @Synchronized override fun apply(owner: IBinder, id: String, mode: Int, original: Int): Boolean {
        require(id.isNotBlank() && !released.contains(id))
        // A daemon can outlive an app whose death-time restore failed. The user may then
        // restore through Android settings and clear the app journal before rebinding us.
        // Reconcile that dead owner's receipt before accepting a new one; never steal a
        // live owner's lease or forget an unconfirmed restore.
        try {
            held?.takeIf { this.owner?.isBinderAlive != true }?.let {
                check(restore(it.id, it.mode, it.original)) { "Previous scan control restore is incomplete" }
            }
            check(held == null) { "Scan control already owned" }
        } catch (error: Exception) {
            // The caller persisted this new id before applying. Nothing was written for it,
            // so its recovery must not get stuck behind the previous owner's receipt.
            // A duplicate of the held id still belongs to that owner and must remain restorable.
            if (held?.id != id) released.record(id)
            throw error
        }
        // A system hotspot may already have disabled STA. Only the current state matters:
        // scan-always controls the remaining Wi-Fi-off path, never STA's periodic scans.
        // The app observes STA re-enabling and restores this lease instead of fighting it.
        if (mode == ScanControlPolicy.HOTSPOT_SCAN &&
            (Build.VERSION.SDK_INT !in 26..32 || wifi.wifiState != WifiManager.WIFI_STATE_DISABLED)) { released.record(id); return false }
        return try {
            owner.linkToDeath(death, 0)
            this.owner = owner
            if (!owner.isBinderAlive || !lease.apply(mode, original) { held = Held(id, mode, original) }) {
                if (held != null) check(restore(id, mode, original))
                else { runCatching { owner.unlinkToDeath(death, 0) }; this.owner = null; released.record(id) }
                false
            } else true
        } catch (e: Exception) {
            if (held != null) runCatching { restore(id, mode, original) }
            else { runCatching { owner.unlinkToDeath(death, 0) }; this.owner = null; released.record(id) }
            throw e
        }
    }

    @Synchronized override fun restore(id: String, mode: Int, original: Int): Boolean {
        if (released.contains(id)) return true
        check(held == null || held == Held(id, mode, original))
        if (!lease.restore(mode, original)) return false
        owner?.let { runCatching { it.unlinkToDeath(death, 0) } }
        owner = null
        held = null
        released.record(id)
        return true
    }

    @Synchronized override fun renew(id: String): Boolean {
        val current = held ?: return false
        if (current.id != id || owner?.isBinderAlive != true ||
            current.mode != ScanControlPolicy.LEGACY_CONNECTIVITY) return false
        lease.renew(current.mode)
        return true
    }

    @Synchronized override fun destroy() {
        held?.let { runCatching { restore(it.id, it.mode, it.original) } }
        kotlin.system.exitProcess(0)
    }

    private fun command(vararg args: String) {
        val process = ProcessBuilder(*args).redirectErrorStream(true).start()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit {
                process.inputStream.use { it.copyTo(java.io.ByteArrayOutputStream()) }
                check(process.waitFor() == 0) { "Wi-Fi command refused" }
            }.get(5, TimeUnit.SECONDS)
        } finally {
            process.destroy()
            executor.shutdownNow()
        }
    }
}
