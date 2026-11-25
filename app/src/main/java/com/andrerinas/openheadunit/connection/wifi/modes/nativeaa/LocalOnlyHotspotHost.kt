package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.LocalHotspotPolicy.RestartCause
import com.andrerinas.openheadunit.utils.AppLog

/**
 * The Android shell around `WifiManager.startLocalOnlyHotspot`: the call, its callback on a
 * main-looper handler, the reservation and its close. Every decision lives in [LocalHotspotPolicy].
 * Its own class so API 16 to 25 never load it.
 */
@RequiresApi(Build.VERSION_CODES.O)
class LocalOnlyHotspotHost(
    private val context: Context,
    private val ledger: LocalHotspotLedger<LocalOnlyHotspotHost> = processLedger,
) {

    sealed class Event {
        data class Started(
            val ssid: String?,
            val passphrase: String?,
            val securityType: Int,
            val bssid: String?,
            /** The band bits the reservation asked for, 0 when none could be read. */
            val bandMask: Int = 0,
        ) : Event()
        data class Failed(val reason: Int) : Event()
        object Stopped : Event()
        data class Threw(val error: RuntimeException) : Event()
    }

    private val handler = Handler(Looper.getMainLooper())
    /** [close] and the platform's answer run on different threads: one of them must see the other. */
    private val stateLock = Any()
    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var started = false
    /** A [LocalHotspotFrequencyWatch], as `Any` so API 26 to 32 never load it. Guarded by [stateLock]. */
    private var watch: Any? = null
    @Volatile private var closed = false

    /** True while a reservation is held and nothing has ended it. */
    val isLive: Boolean get() = reservation != null && !closed

    /**
     * Asks for a hotspot. [onEvent] runs on the main thread, so it must not block. [currentPair] names
     * the interface that is up and new now, so the next start knows which pair this hotspot held.
     * [onFrequencies] gets the frequencies it runs on, on the main thread, from API 33.
     */
    @SuppressLint("MissingPermission")
    fun start(
        currentPair: () -> Pair<String, String>?,
        onFrequencies: (List<Int>) -> Unit = {},
        onEvent: (Event) -> Unit,
    ) {
        val call = ledger.called(SystemClock.elapsedRealtime(), this)
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            watchFrequency(wifi, onFrequencies)
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    // Before any close: a late answer still tells the next start which pair to wait out.
                    ledger.recordPair(currentPair())
                    if (!take(res)) {
                        ledger.lateAnswer(this@LocalOnlyHotspotHost) { closeQuietly(res) }
                        return
                    }
                    ledger.answered(this@LocalOnlyHotspotHost)
                    onEvent(read(res))
                }

                override fun onStopped() {
                    ledger.answered(this@LocalOnlyHotspotHost)
                    if (closed) return
                    ended(RestartCause.PLATFORM_STOP)
                    onEvent(Event.Stopped)
                }

                override fun onFailed(reason: Int) {
                    ledger.answered(this@LocalOnlyHotspotHost)
                    if (closed) return
                    // After a start the platform took the hotspot down: nothing is left to close.
                    if (started) ended(RestartCause.PLATFORM_STOP)
                    onEvent(Event.Failed(reason))
                }
            }, handler)
            ledger.accepted()
        } catch (e: RuntimeException) {
            // IllegalStateException while an earlier request of this app is open; never let it reach the scope.
            ledger.threw(call, SystemClock.elapsedRealtime())
            stopWatch()
            onEvent(Event.Threw(e))
        }
    }

    /** Releases the reservation. Safe to call twice. */
    fun close() {
        val held = synchronized(stateLock) {
            if (closed) return
            closed = true
            reservation.also { reservation = null }
        }
        stopWatch()
        held?.let { closeQuietly(it) }
        ledger.closed(this)
    }

    /** Keeps [res], or answers false when the host is already closed and the caller must close it. */
    private fun take(res: WifiManager.LocalOnlyHotspotReservation): Boolean = synchronized(stateLock) {
        if (closed) return false
        reservation = res
        started = true
        true
    }

    private fun ended(cause: RestartCause) {
        synchronized(stateLock) {
            closed = true
            reservation = null
        }
        stopWatch()
        ledger.ended(this, cause)
    }

    private fun watchFrequency(wifi: WifiManager, onFrequencies: (List<Int>) -> Unit) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val new = LocalHotspotFrequencyWatch(wifi) { handler.post(it) }
        synchronized(stateLock) { watch = new }
        new.start { if (!closed) onFrequencies(it) }
    }

    private fun stopWatch() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val held = synchronized(stateLock) { watch.also { watch = null } }
        (held as? LocalHotspotFrequencyWatch)?.stop()
    }

    private fun closeQuietly(res: WifiManager.LocalOnlyHotspotReservation) {
        try {
            res.close()
        } catch (e: Exception) {
            AppLog.w("LocalOnlyHotspotHost: closing the reservation threw: ${e.message}")
        }
    }

    // WPA2_PSK is bit 4 on every level this runs on; only its public name came in API 34.
    @SuppressLint("InlinedApi")
    @Suppress("DEPRECATION")
    private fun read(res: WifiManager.LocalOnlyHotspotReservation): Event.Started {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val ssid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                config.wifiSsid?.toString() ?: config.ssid
            } else {
                config.ssid
            }
            return Event.Started(
                LocalHotspotPolicy.cleanSsid(ssid), config.passphrase, config.securityType, config.bssid?.toString(),
                bandMask(config),
            )
        }
        val config = res.wifiConfiguration
        val keys = config?.allowedKeyManagement
        return Event.Started(
            LocalHotspotPolicy.cleanSsid(config?.SSID),
            config?.preSharedKey,
            LocalHotspotPolicy.legacySecurityType(
                wpa2Psk = keys?.get(WifiConfiguration.KeyMgmt.WPA2_PSK) == true,
                open = keys?.get(WifiConfiguration.KeyMgmt.NONE) == true,
            ),
            config?.BSSID,
        )
    }

    /** `getChannels()` is public from API 36. API 30 to 35 read the config text; a failed read gives 0. */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun bandMask(config: SoftApConfiguration): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            try {
                val channels = config.channels
                val mask = LocalHotspotPolicy.bandMaskFromChannelKeys(List(channels.size()) { channels.keyAt(it) })
                if (mask > 0) return mask
            } catch (e: RuntimeException) {
                AppLog.w("LocalOnlyHotspotHost: getChannels threw: ${e.message}")
            }
        }
        return try {
            LocalHotspotPolicy.bandMaskFromConfigText(config.toString())
        } catch (e: RuntimeException) {
            0
        }
    }

    companion object {
        /** The one ledger of this process: a launcher swap does not reset it. */
        val processLedger = LocalHotspotLedger<LocalOnlyHotspotHost>()
    }
}
