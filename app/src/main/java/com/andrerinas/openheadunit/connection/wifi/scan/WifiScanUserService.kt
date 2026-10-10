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

    private fun writeState(mode: Int, value: Int) {
        when (mode) {
            ScanControlPolicy.AUTOJOIN -> {
                check(Build.VERSION.SDK_INT >= 33)
                // AOSP WifiConnectivityManager's external gate cancels periodic and PNO scans.
                // Unlike the Android 10 enableWifiConnectivityManager flag, requests cannot
                // silently re-enable it. This does not disable app scans or firmware roaming.
                wifi.allowAutojoinGlobal(value == 1)
            }
            ScanControlPolicy.HOTSPOT_SCAN -> {
                // From R this lives in WifiSettingsConfigStore, not Settings.Global. Writing the
                // old key on R+ can report success while leaving scanning enabled.
                if (Build.VERSION.SDK_INT >= 30) command("/system/bin/cmd", "wifi", "set-scan-always-available",
                    if (value == 1) "enabled" else "disabled")
                else command("/system/bin/settings", "put", "global", "wifi_scan_always_enabled", value.toString())
            }
            else -> error("Unsupported scan control")
        }
        // Settings observers and WifiThreadRunner are asynchronous. Bound verification, never
        // expose a permanent 'active' state from the command's exit status alone.
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
