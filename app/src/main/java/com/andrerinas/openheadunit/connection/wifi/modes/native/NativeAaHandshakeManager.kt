package com.andrerinas.openheadunit.connection.wifi.modes.native

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.BluetoothWakePolicy
import com.andrerinas.openheadunit.aap.NativeHandoffPolicy
import com.andrerinas.openheadunit.aap.WppAction
import com.andrerinas.openheadunit.aap.WppEvent
import com.andrerinas.openheadunit.aap.WppFraming
import com.andrerinas.openheadunit.aap.WppHandshakeSession
import com.andrerinas.openheadunit.aap.WppMessageType
import com.andrerinas.openheadunit.aap.WppStage
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import android.os.Build
import android.os.SystemClock
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.utils.Settings
import java.io.DataInputStream
import java.io.OutputStream
import java.util.*

/**
 * Manages the official Android Auto Wireless Bluetooth handshake.
 * This class implements the RFCOMM server protocol to exchange WiFi credentials with the phone.
 */
class NativeAaHandshakeManager(
    private val context: AapService,
    private val launcher: WifiLauncherNative,
    private val scope: CoroutineScope
) {
    companion object {
        private val AA_UUID = UUID.fromString("4de17a00-52cb-11e6-bdf4-0800200c9a66")
        private val HFP_UUID = UUID.fromString("0000111e-0000-1000-8000-00805f9b34fb")
        // The phone-wake targets, and the rules for when a poke may run at all, live in
        // BluetoothWakePolicy — one of those records is also the one a phone call rides on.

        /** How long to wait for this head unit's own WiFi network to come up before giving up on
         *  a handshake. P2P group creation is the slow case. */
        private const val CREDENTIALS_WAIT_MS = 60_000L

        /** How long to wait for the AAP TCP port to be bound before giving up on a handshake. */
        private const val PORT_WAIT_MS = 3_000L

        /** Which of [allServiceNames] are secondary Bluetooth radios, i.e. not [primaryServiceName]
         *  (dual-Bluetooth-radio head units). Pure and unit-testable: identity is by system
         *  service name, not MAC address, since BluetoothAdapter.getAddress() returns the fixed
         *  placeholder "02:00:00:00:00:00" for any non-privileged app on every device since
         *  Android 6.0 (API 23), so every real adapter instance looks identical by address alone. */
        internal fun filterSecondaryServiceNames(
            primaryServiceName: String,
            allServiceNames: List<String>
        ): List<String> {
            val primary = primaryServiceName.ifEmpty { "bluetooth_manager" }
            return allServiceNames.filter { it != primary }.distinct()
        }

        /**
         * The one-line explanation to log and show when this unit's Bluetooth is an external
         * module, or null when it isn't. Kept here so the handshake manager and the settings
         * compatibility probe say exactly the same thing.
         */
        fun externalBtDiagnostic(): String? = BluetoothHelper.externalBtEvidence?.let { evidence ->
            "NativeAA: external Bluetooth module detected ($evidence) — the phone is bonded to " +
                "the head unit's own Bluetooth chip, not the one Android exposes, so nothing we " +
                "write over RFCOMM reaches it. Bluetooth-based wireless cannot work on this unit; " +
                "use USB, or one of the WiFi modes that does not need the Bluetooth handshake."
        }

        /**
         * Whether to run the Bluetooth route anyway on a unit [externalBtDiagnostic] flagged.
         *
         * A manually configured secondary Bluetooth service is the user telling us which radio to
         * use, having found one automatic enumeration missed. That is exactly the case the
         * detection cannot see, so it must not be the one case we refuse to try — the diagnostic
         * still goes in the log either way.
         */
        fun externalBtOverridden(context: Context): Boolean =
            App.provide(context)
                .settings.manualSecondaryBluetoothServiceName.isNotEmpty()

        fun checkCompatibility(context: Context): Boolean {
            externalBtDiagnostic()?.let {
                AppLog.w(it)
                if (!externalBtOverridden(context)) return false
                AppLog.w("NativeAA: continuing anyway — a secondary Bluetooth service is configured manually.")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                    != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w("NativeAA: Compatibility Check skipped - Missing BLUETOOTH_CONNECT")
                    return false
                }
            }
            val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return false
            if (!adapter.isEnabled) return false
            return try {
                val socket = adapter.listenUsingRfcommWithServiceRecord("Compatibility Check", AA_UUID)
                socket.close()
                AppLog.i("NativeAA: Compatibility Check SUCCESS")
                true
            } catch (e: Exception) {
                AppLog.w("NativeAA: Compatibility Check FAILED: ${e.message}")
                false
            }
        }
    }

    private val settings = App.provide(context).settings
    private val commManager = App.provide(context).commManager
    private var aaServerSocket: BluetoothServerSocket? = null
    private var hfpServerSocket: BluetoothServerSocket? = null
    // Extra RFCOMM listeners opened on secondary Bluetooth radios (dual-Bluetooth head units).
    // Split by UUID so a successful handoff can close just the AA listeners (see
    // closeAaListeners()) without taking down the HFP ones too.
    private val extraAaServerSockets = Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private val extraHfpServerSockets = Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private var isRunning = false
    // Set by closeAaListeners() so the AA accept loops can tell "we closed this on purpose
    // after a successful handoff" apart from a real socket error, for logging only.
    @Volatile private var aaListenersClosedForSession = false
    // Whether the "already have a hands-free link, not poking" line has been said at info level for
    // the current run of skips. Cleared as soon as a poke does go ahead, so a later skip says so
    // again rather than hiding behind a line from minutes earlier.
    @Volatile private var handsFreeSkipLogged = false

    private var currentSsid: String? = null
    private var currentPsk: String? = null
    private var currentIp: String? = null
    private var currentBssid: String? = null
    private var pokeJob: Job? = null
    // Last (ssid, ip, bssid) triggerPoke() restarted for - dedupes redundant restarts when
    // WifiDirectManager redelivers the same credentials, which was starving the poke before it
    // could ever finish.
    private var lastPokeTriggerCredentials: Triple<String, String, String>? = null
    // elapsedRealtime() when handleHandshake() started, or 0 when no exchange is running; lets
    // WifiDirectManager's join watchdog know a real exchange is in progress.
    //
    // [BUG_FIX] A stamp rather than a boolean, because handleHandshake() cannot be relied on to
    // clear it: on stacks where closing the socket does not unblock the wait for Type 2, the
    // coroutine never reaches its finally. Seen as three failed handshakes and zero "BT Handshake
    // socket closed." lines, with the old boolean stuck true for the rest of the process. See
    // NativeHandoffPolicy.isHandshaking.
    @Volatile private var handshakeStartedAt = 0L
    // True for the duration of a single pokeDevice() attempt (its socket.connect() call itself
    // can fire an OS-level ACL_CONNECTED broadcast before any real handshake starts) - see
    // isAttemptInFlight().
    @Volatile private var pokeAttemptInFlight = false
    // elapsedRealtime() when the last WifiInfoResponse (Type 3) went out, or 0 when no handoff is
    // settling. The phone spends the next several seconds associating, doing WPS and getting a
    // DHCP lease; see isHandoffSettling() and NativeHandoffPolicy.
    @Volatile private var handoffSettlingSince = 0L
    // The socket of the handshake currently being served. Kept so a phone that gives up and
    // reconnects over Bluetooth during a settle supersedes the stale one instead of running a
    // second handleHandshake() alongside it.
    @Volatile private var activeHandshakeSocket: BluetoothSocket? = null
    // The coroutine serving [activeHandshakeSocket]. Closing a superseded handshake's socket only
    // ends it on stacks where close() interrupts a pending read; some do not, and it runs on for
    // minutes. Cancelling cannot break a blocking JNI read either, but it does end every real
    // suspension point in the handshake. Do both; whichever the stack honours wins.
    @Volatile private var activeHandshakeJob: Job? = null
    // Name of the primary Bluetooth radio we listen and poke on, captured in start(). A field
    // rather than a local so the diagnostic below can name the radio the phone is ignoring.
    @Volatile private var localRadioName: String = "?"
    // [BUG_FIX] How many wake pokes the phone has answered without ever opening the AA channel,
    // and whether it ever has. The pair exists because "poke succeeds, nothing comes back" makes a
    // broken unit's log identical to a healthy one waiting for the user, while the phone is in
    // fact reconnecting every 12 s to the unit's own OEM Bluetooth module, which advertises the
    // same service record. See NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack.
    @Volatile private var pokesSinceLastAccept = 0
    @Volatile private var everAcceptedAaConnection = false
    // [BUG_FIX] Handshakes that timed out waiting for Type 2, back to back. Where close() does not
    // interrupt a pending read each one strands a Dispatchers.IO thread forever, so this bounds
    // how many we are willing to strand. See NativeHandoffPolicy.shouldServeHandshake.
    @Volatile private var consecutiveHandshakeFailures = 0
    // Whether the "not serving handshakes" warning has already been logged for the current
    // backoff, so a phone retrying every ~12 s does not repeat the long explanation each time.
    @Volatile private var loggedHandshakeBackoff = false

    /** Polls until the AAP TCP port is bound, or [timeoutMs] passes. */
    private suspend fun awaitWirelessServerListening(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            if (launcher.isWirelessServerListening()) return true
            if (SystemClock.elapsedRealtime() >= deadline) return false
            delay(250)
        }
    }

    /**
     * Updates the WiFi credentials that will be sent to the phone during the next handshake.
     */
    fun updateWifiCredentials(ssid: String, psk: String, ip: String, bssid: String) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid")
        currentSsid = ssid
        currentPsk = psk
        currentIp = ip
        currentBssid = bssid
    }

    /** Clears cached credentials so an in-progress wait doesn't hand out stale ones for a group
     *  that's about to be torn down. */
    fun invalidateCredentials() {
        currentSsid = null
        currentPsk = null
        currentIp = null
        currentBssid = null
    }

    // isRunning alone isn't enough once closeAaListeners() can close the AA_UUID listener while
    // leaving the manager otherwise running (HFP stays up) — callers like AutoStartReceiver's
    // BT-reconnect re-arm need to know whether a connection can actually be accepted right now,
    // not just whether the manager was start()ed. See the "Re-arm on Bluetooth reconnect" fix
    // this restores the invariant for: isActive() must mean "genuinely able to accept," not
    // "believed to be running."
    fun isActive(): Boolean = isRunning && !aaListenersClosedForSession

    fun isHandshakeInFlight(): Boolean =
        NativeHandoffPolicy.isHandshaking(handshakeStartedAt, SystemClock.elapsedRealtime())

    /**
     * True between delivering the WiFi credentials (Type 3) and the phone's TCP session actually
     * landing — the window in which it is still associating, doing WPS and getting a DHCP lease.
     *
     * [isHandshakeInFlight] deliberately goes false the instant Type 3 is written, because the
     * *credential exchange* is done at that point. The phone's work is not: measured joining the
     * group 0.73 s after Type 3 and still without an IP 2.4 s later. Anything that must not disturb
     * the phone mid-join — the wake poke, the BT auto-start re-arm, WifiDirectManager's join
     * watchdog — has to check this, not isHandshakeInFlight().
     */
    fun isHandoffSettling(): Boolean =
        NativeHandoffPolicy.isSettling(handoffSettlingSince, SystemClock.elapsedRealtime())

    // True while either a wake-up poke's socket.connect() or a real handshake is in progress, or
    // a delivered handoff is still settling. AutoStartReceiver's own poke can generate the
    // ACL_CONNECTED broadcast that re-triggers AapService's BT auto-start re-arm; callers
    // deciding whether it's safe to force-reinit should check this instead of isActive() alone.
    fun isAttemptInFlight(): Boolean = isHandshakeInFlight() || pokeAttemptInFlight || isHandoffSettling()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        // Leave isRunning false, like the "adapter disabled" case below: isActive() callers must
        // see this as genuinely stopped. Nothing here is retryable, but a listener that was never
        // opened must not be reported as up.
        externalBtDiagnostic()?.let {
            if (!externalBtOverridden(context)) {
                AppLog.e(it)
                return
            }
            AppLog.w("$it\nNativeAA: starting anyway — a secondary Bluetooth service is configured manually.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.e("NativeAA: Missing BLUETOOTH_CONNECT permission. Handshake server cannot start.")
                return
            }
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            // Leave isRunning false — isActive() callers (e.g. AapService's BT auto-start
            // re-arm check) need to see this as genuinely stopped so they retry later,
            // instead of believing the listener sockets are up when nothing was ever opened.
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            return
        }

        isRunning = true
        aaListenersClosedForSession = false
        // Local Bluetooth radio name; logged on every accept so a dual-radio head unit's logs
        // show which radio the phone actually reached (compare with the HU name in the phone's
        // log). Uses adapter.name, not adapter.address: getAddress() returns the fixed masked
        // placeholder "02:00:00:00:00:00" for any non-privileged app since Android 6.0 (API 23),
        // but getName() returns the real radio name (confirmed on-device: e.g. "Navegadortz2").
        localRadioName = try { adapter.name ?: "?" } catch (e: Exception) { "?" }
        AppLog.i("NativeAA: Starting Bluetooth Handshake Servers (primary radio [$localRadioName])...")

        // Start AA RFCOMM Server
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer")) {
            try {
                aaServerSocket = adapter.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID) on radio [$localRadioName]... Waiting for phone to connect back!")
                while (isRunning && isActive) {
                    val socket = aaServerSocket?.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name} (${socket.remoteDevice.address}) on local radio [$localRadioName]")
                        if (refuseWhileBackedOff(socket)) continue
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket, localRadioName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) {
                    AppLog.d("NativeAA: AA Server socket closed after successful handoff.")
                } else if (isRunning) {
                    AppLog.e("NativeAA: AA Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: AA Server socket closed cleanly.")
                }
            }
        }

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        logHfpAccept(socket, localRadioName)
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            handleHfp(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) {
                    AppLog.e("NativeAA: HFP Server socket error: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: HFP Server socket closed cleanly.")
                }
            }
        }

        // Some head units have two Bluetooth radios (e.g. "K706" and "CAR8032"). The phone may
        // be bonded to whichever one isn't the primary, so it never reaches the listener above.
        // Match radios by system service name, not MAC address: BluetoothAdapter.getAddress()
        // returns the fixed placeholder "02:00:00:00:00:00" for any non-privileged app since
        // Android 6.0 (API 23), on every device - primary and secondary always look identical
        // by address alone.
        val handles = try {
            BluetoothHelper.getAllBluetoothAdapterHandles(context)
        } catch (e: Exception) { emptyList() }

        // Manual fallback: some ROMs' second radio isn't discoverable via
        // ServiceManager.listServices() at all (blocked, or named without "bluetooth"), so
        // automatic enumeration never finds it. Let the user force it by exact system service
        // name instead.
        val manualServiceName = settings.manualSecondaryBluetoothServiceName
        val allHandles = if (manualServiceName.isNotEmpty() && handles.none { it.serviceName == manualServiceName }) {
            val manualHandle = try { BluetoothHelper.getAdapterHandleForService(context, manualServiceName) } catch (e: Exception) { null }
            if (manualHandle != null) {
                AppLog.i("NativeAA: Manual secondary Bluetooth service '$manualServiceName' resolved successfully.")
                handles + manualHandle
            } else {
                AppLog.w("NativeAA: Manual secondary Bluetooth service '$manualServiceName' could not be resolved to a working adapter.")
                handles
            }
        } else handles

        val secondaryNames = filterSecondaryServiceNames(
            settings.bluetoothManagerServiceName,
            allHandles.map { it.serviceName }
        ).toSet()
        val secondaries = allHandles.filter { it.serviceName in secondaryNames }
        if (secondaries.isNotEmpty()) {
            AppLog.i("NativeAA: Opening AA listeners on ${secondaries.size} secondary Bluetooth radio(s) for dual-radio head units: ${secondaries.joinToString { it.serviceName }}")
            secondaries.forEach { launchExtraServers(it.serviceName, it.adapter) }
        }
    }

    /**
     * Open supplementary AA + HFP RFCOMM listeners on a secondary Bluetooth radio, so a phone
     * bonded to that radio (dual-Bluetooth head units) can still reach us. Experimental, and
     * fully guarded so a bad radio cannot affect the primary listener.
     */
    private fun launchExtraServers(serviceName: String, extra: BluetoothAdapter) {
        // extra.name, not extra.address - see the comment on localRadioName in start(); the
        // address is always the masked placeholder, the name is the real, useful identifier.
        val radioName = try { extra.name ?: "?" } catch (e: Exception) { "?" }
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-RfcommServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("AA BT Listener", AA_UUID)
                extraAaServerSockets.add(server)
                AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID on secondary radio '$serviceName' [$radioName]")
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        AppLog.i("NativeAA: Connection accepted (secondary radio '$serviceName' [$radioName]) from ${socket.remoteDevice.name} (${socket.remoteDevice.address})")
                        if (refuseWhileBackedOff(socket)) continue
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket, radioName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) AppLog.d("NativeAA: Secondary AA server closed after successful handoff ['$serviceName' $radioName].")
                else if (isRunning) AppLog.e("NativeAA: Secondary AA server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary AA server closed cleanly ['$serviceName' $radioName].")
            }
        }
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                extraHfpServerSockets.add(server)
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        logHfpAccept(socket, "$serviceName $radioName")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            handleHfp(socket)
                        }
                    }
                }
            } catch (e: Exception) {
                if (isRunning) AppLog.e("NativeAA: Secondary HFP server error ['$serviceName' $radioName]: ${e.message}", e)
                else AppLog.d("NativeAA: Secondary HFP server closed cleanly ['$serviceName' $radioName].")
            }
        }
    }

    /**
     * Stop accepting new AA_UUID connections (primary + any secondary radios) after a
     * successful handoff to WiFi. Closing just the client socket isn't enough: the phone reads
     * that as an unexpected drop and immediately retries, and with the listener still up we'd
     * accept, bail out (already connected), and close again — a tight reconnect storm (confirmed
     * on-device: hundreds of accept/close cycles a second, indistinguishable from a Bluetooth
     * pairing loop). HFP listeners are left running. Re-opened the next time start() runs, which
     * AapService already does on disconnect.
     */
    private fun closeAaListeners() {
        aaListenersClosedForSession = true
        try { aaServerSocket?.close() } catch (e: Exception) {}
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
    }

    /**
     * Says what an accepted hands-free connection means, not only that it happened. On its own it
     * reads like success; it is the phone attaching its hands-free link to this app rather than to
     * the head unit's own Bluetooth stack, and the responder below can never carry call audio —
     * it answers OK to everything and negotiates neither a codec nor a SCO link.
     *
     * Whether it ever fires is still open: five rig rounds and every reporter log so far, no
     * accepts. Address as well as name, because getName() is null for an unbonded device.
     */
    private fun logHfpAccept(socket: BluetoothSocket, radio: String) {
        val device = socket.remoteDevice
        AppLog.i("NativeAA: HFP connection accepted from ${device.name ?: "unnamed"} (${device.address}) " +
            "on radio [$radio] — the phone's hands-free link now terminates in this app, " +
            "which cannot carry call audio. If calls are not heard on this unit, look here first.")
    }

    /**
     * Minimal HFP responder to satisfy phones that require a stable HFP connection
     * during the Android Auto Wireless handshake.
     */
    private suspend fun handleHfp(socket: BluetoothSocket) = withContext(Dispatchers.IO) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val buf = ByteArray(1024)

            AppLog.i("NativeAA: HFP responder active for ${socket.remoteDevice.name}")

            while (isRunning && isActive && socket.isConnected) {
                if (input.available() > 0) {
                    val read = input.read(buf)
                    if (read == -1) break

                    val cmd = String(buf, 0, read, Charsets.US_ASCII).trim()
                    AppLog.d("NativeAA: HFP RX: $cmd")

                    // Respond to standard HFP initialization commands
                    when {
                        cmd.contains("AT+BRSF") -> {
                            output.write("+BRSF: 20\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND=?") -> {
                            output.write("+CIND: (\"service\",(0,1)),(\"call\",(0,1))\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        cmd.contains("AT+CIND?") -> {
                            output.write("+CIND: 1,0\r\n".toByteArray())
                            output.write("OK\r\n".toByteArray())
                        }
                        else -> {
                            output.write("OK\r\n".toByteArray())
                        }
                    }
                    output.flush()
                }
                delay(200)
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: HFP responder error: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: HFP socket for ${socket.remoteDevice.address} closed.")
        }
    }

    /**
     * Read a device's pairing state, keeping "not paired" and "could not tell" apart.
     *
     * `getBondState()` answers `BOND_NONE` when the Bluetooth service is unavailable rather than
     * saying it does not know, so an adapter that is off would otherwise look exactly like a phone
     * the user unpaired. Everything that cannot be established reads as
     * [BluetoothWakePolicy.BondReading.UNREADABLE], and the policy decides what each is worth.
     */
    private fun bondReadingFor(device: BluetoothDevice): BluetoothWakePolicy.BondReading {
        val adapter = try {
            BluetoothHelper.getBluetoothAdapter(context)
        } catch (e: Exception) {
            null
        } ?: return BluetoothWakePolicy.BondReading.UNREADABLE
        val enabled = try { adapter.isEnabled } catch (e: Exception) { false }
        if (!enabled) return BluetoothWakePolicy.BondReading.UNREADABLE
        val state = try {
            device.bondState
        } catch (e: Exception) {
            return BluetoothWakePolicy.BondReading.UNREADABLE
        }
        return if (state == BluetoothDevice.BOND_BONDED) BluetoothWakePolicy.BondReading.BONDED
        else BluetoothWakePolicy.BondReading.NOT_BONDED
    }

    /** As [bondReadingFor], for a MAC that has not been resolved to a device yet. */
    private fun bondReadingFor(adapter: BluetoothAdapter, mac: String): BluetoothWakePolicy.BondReading {
        val device = try {
            adapter.getRemoteDevice(mac)
        } catch (e: IllegalArgumentException) {
            // Not a Bluetooth address. It can never become one, so this is the one reading that is
            // safe to forget without the adapter having said anything.
            return BluetoothWakePolicy.BondReading.MALFORMED
        } catch (e: Exception) {
            return BluetoothWakePolicy.BondReading.UNREADABLE
        }
        return bondReadingFor(device)
    }

    /**
     * Say once per run of skips that the poke stood down. Info first so it survives a log exported
     * at the default level, then debug: the retry loop asks again every ~30 s, and a line per
     * half-minute for a whole session buries everything around it.
     */
    private fun noteHandsFreePokeSkip(device: BluetoothDevice) {
        val message = "NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — this " +
            "head unit already holds a Bluetooth hands-free link, which a poke would take over " +
            "and leave disconnected. That link is itself the connection a poke exists to create."
        if (!handsFreeSkipLogged) {
            handsFreeSkipLogged = true
            AppLog.i(message)
        } else {
            AppLog.d(message)
        }
    }

    /**
     * Tries each of [BluetoothWakePolicy.POKE_TARGETS] in turn, holding whichever connects for
     * [holdMs]. Returns true if any of them did, false without opening anything if either guard
     * below stands the poke down. Both poke entry points come through here, so one check covers
     * the retry loop and the manual poke alike.
     */
    private suspend fun pokeDevice(device: BluetoothDevice, holdMs: Long): Boolean {
        // A poke that connects takes the phone's single hands-free slot, and this unit's own client
        // is dropped to make room. See BluetoothWakePolicy for the measurement.
        val handsFreeLink = BluetoothWakePolicy.HandsFreeLink.of(BluetoothHelper.handsFreeLinkState(context))
        if (!BluetoothWakePolicy.shouldPoke(handsFreeLink)) {
            noteHandsFreePokeSkip(device)
            return false
        }
        handsFreeSkipLogged = false

        // connect() against an unpaired device makes the OS solicit pairing as a side effect, and
        // the user meant "wake my phone", not "ask to pair with it again".
        if (!BluetoothWakePolicy.mayPoke(bondReadingFor(device))) {
            AppLog.w("NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — it is not " +
                "currently paired with this head unit, and connecting to an unpaired device would " +
                "ask the user to pair rather than wake anything.")
            return false
        }

        pokeAttemptInFlight = true
        try {
            for (uuid in BluetoothWakePolicy.POKE_TARGETS) {
                val profile = BluetoothWakePolicy.profileName(uuid)
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(uuid)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name} via $profile ($uuid)...")
                    socket.connect()
                    // Named, not just the UUID: which record we ended up on is the first thing to
                    // check when a reporter's calls come out of the phone instead of the car.
                    AppLog.i("NativeAA: Successfully poked ${device.name} via $profile. Holding ${holdMs}ms...")
                    // Counted before the hold, so a poke that is cancelled mid-hold still counts:
                    // the phone answered, which is the whole point of the count.
                    pokesSinceLastAccept++
                    delay(holdMs)
                    return true
                } catch (e: CancellationException) {
                    // Rethrow instead of falling through to the next UUID: a cancelled poke (e.g.
                    // handleHandshake()'s pokeJob?.cancel() once a real handshake lands) must stop
                    // immediately, not fire another real, blocking socket.connect() on the same
                    // physical radio right as the critical WifiStartRequest send is about to happen.
                    throw e
                } catch (e: Exception) {
                    // Address as well as name: getName() is null for an unbonded device, and a log line
                    // reading "to null" names nothing at all for the reader of a bug report.
                    AppLog.d("NativeAA: Poke via $profile to ${device.name ?: "unnamed"} (${device.address}) failed: ${e.message}")
                } finally {
                    try { socket?.close() } catch (e: Exception) {}
                }
            }
            return false
        } finally {
            pokeAttemptInFlight = false
        }
    }

    /**
     * Wakes up the phone by attempting a brief connection to an HFP/HSP profile, signaling it
     * to start looking for the head unit. Retried every 15s (matching the retry cadence of both
     * nisargjhaveri/WirelessAndroidAutoDongle and mossyhub/openautolink) until a real handshake
     * starts or another session (USB/etc.) takes over, instead of giving up after a single pass.
     *
     * Never runs while a handoff is settling: AapService re-invokes this on every credential
     * re-delivery, and the phone *joining our group* is itself a P2P connection change, hence a
     * re-delivery. That put a real RFCOMM connect() in the middle of the phone's DHCP exchange.
     */
    fun triggerPoke() {
        if (isHandoffSettling()) {
            // Info, not debug: this line is the evidence the suppression is working, and reporter
            // logs default to INFO.
            AppLog.i("NativeAA: Handoff still settling — not starting a poke that would compete with the phone's WiFi association.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot triggerPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return

        val credentials = Triple(currentSsid ?: "", currentIp ?: "", currentBssid ?: "")
        if (pokeJob?.isActive == true && credentials == lastPokeTriggerCredentials) {
            AppLog.d("NativeAA: triggerPoke() called again with unchanged credentials while a poke is already running - not restarting it.")
            return
        }
        lastPokeTriggerCredentials = credentials

        pokeJob?.cancel()
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            AppLog.d("NativeAA: triggerPoke() delay starting (2s)...")
            delay(2000) // Small safety delay before connecting

            while (isRunning && isActive) {
                val settling = isHandoffSettling()
                val handshaking = isHandshakeInFlight()
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                if (!NativeHandoffPolicy.shouldPoke(settling, handshaking, sessionUp)) {
                    AppLog.i(
                        "NativeAA: Stopping poke retry loop " +
                            "(settling=$settling, handshake=$handshaking, session=$sessionUp)."
                    )
                    break
                }

                val lastMacs = settings.autoStartBluetoothDeviceMacs
                val devicesToPoke = if (lastMacs.isNotEmpty()) {
                    // Two questions, two answers. Skipping a poke is retried seconds later;
                    // forgetting a MAC is permanent, so it needs evidence the device is really gone
                    // rather than an adapter that happened to be off. Both rules are in the policy.
                    val bonded = mutableListOf<BluetoothDevice>()
                    val staleMacs = mutableSetOf<String>()
                    lastMacs.forEach { mac ->
                        val reading = bondReadingFor(adapter, mac)
                        if (BluetoothWakePolicy.mayPoke(reading)) {
                            try { bonded.add(adapter.getRemoteDevice(mac)) } catch (e: Exception) {}
                        }
                        if (BluetoothWakePolicy.shouldForget(reading)) staleMacs.add(mac)
                    }
                    if (staleMacs.isNotEmpty()) {
                        AppLog.w("NativeAA: Dropping Auto Start BT MAC(s) no longer paired: $staleMacs")
                        val remaining = lastMacs - staleMacs
                        settings.autoStartBluetoothDeviceMacs = remaining
                        Settings.syncAutoStartBtMacsToDeviceStorage(context, remaining)
                    }
                    bonded
                } else {
                    AppLog.w("NativeAA: No 'Auto Start BT Device' selected in settings. Poking all paired devices as fallback...")
                    adapter.bondedDevices.toList()
                }

                if (devicesToPoke.isEmpty()) {
                    AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                    return@launch
                }

                for (device in devicesToPoke) {
                    if (!isRunning || !isActive || isHandshakeInFlight() || isHandoffSettling()) break
                    if (commManager.isConnected) {
                        AppLog.i("NativeAA: USB/other session became active mid-poke. Stopping poke loop.")
                        break
                    }

                    // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                    // If RFCOMM connects before WiFi credentials exist, the phone times out after 10s waiting for WifiStartRequest.
                    if (currentSsid.isNullOrEmpty() || currentIp.isNullOrEmpty()) {
                        AppLog.i("NativeAA: WiFi credentials not ready before poke. Requesting WiFi refresh...")
                        launcher.triggerWifiDirectRefresh()
                        var waitedMs = 0
                        while ((currentSsid.isNullOrEmpty() || currentIp.isNullOrEmpty()) && waitedMs < 4000 && isRunning && isActive) {
                            delay(200)
                            waitedMs += 200
                        }
                    }

                    AppLog.i("NativeAA: Attempting active poke to device: ${device.name} (${device.address})...")
                    pokeDevice(device, holdMs = 15000)
                }

                // [BUG_FIX] Say out loud that the phone answers but never calls back. Untold, that
                // unit's log is indistinguishable from a healthy one waiting for the user, which is
                // what hid the real cause — Android Auto bound to the head unit's own OEM Bluetooth
                // module, still advertising the AA service record after its OEM app stopped
                // answering. Warning, not info, so it survives a log exported at the default
                // level.
                if (NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack(
                        pokesSinceLastAccept, everAcceptedAaConnection
                    )
                ) {
                    AppLog.w(
                        "NativeAA: The phone has answered $pokesSinceLastAccept wake pokes but has " +
                            "never opened the Android Auto channel on radio [$localRadioName]. Its " +
                            "Android Auto is most likely bound to a different Bluetooth device that " +
                            "also advertises the Android Auto service — typically this head unit's " +
                            "own OEM/factory Bluetooth module (a second name alongside this one in " +
                            "the phone's paired list), or another car. Remove that device from the " +
                            "phone's Bluetooth paired list and retry. If this phone has never " +
                            "projected wirelessly to any head unit, check that it supports wireless " +
                            "Android Auto first."
                    )
                }

                delay(15000) // retry cadence, matches both reference implementations' 15-20s interval
            }
        }
    }

    /**
     * Start a manual poke (wakeup) for a specific Bluetooth device.
     */
    fun manualPoke(address: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                AppLog.w("NativeAA: Missing BLUETOOTH_CONNECT. Cannot manualPoke.")
                return
            }
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        try {
            val device = adapter.getRemoteDevice(address)
            AppLog.i("NativeAA: Manual poke requested for ${device.name} ($address)")
            // The user asking to try again is the way out of a handshake backoff — it is the only
            // gesture the UI offers, and it means they want another attempt whatever we concluded.
            resetHandshakeBackoff()

            pokeJob?.cancel()
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                if (currentSsid.isNullOrEmpty() || currentIp.isNullOrEmpty()) {
                    AppLog.i("NativeAA: WiFi credentials not ready before manual poke. Requesting WiFi refresh...")
                    launcher.triggerWifiDirectRefresh()
                    var waitedMs = 0
                    while ((currentSsid.isNullOrEmpty() || currentIp.isNullOrEmpty()) && waitedMs < 4000 && isRunning && isActive) {
                        delay(200)
                        waitedMs += 200
                    }
                    AppLog.i("NativeAA: Pre-poke credential wait completed. SSID=$currentSsid, IP=$currentIp (waited ${waitedMs}ms)")
                }

                AppLog.i("NativeAA: Attempting manual poke to ${device.name}...")
                pokeDevice(device, holdMs = 20000)
                AppLog.i("NativeAA: Manual poke to ${device.name} finished.")
            }
        } catch (e: Exception) {
            AppLog.e("NativeAA: Manual poke error", e)
        }
    }

    /**
     * Drop an incoming Android Auto connection instead of serving it, once too many handshakes in
     * a row have timed out waiting for the phone's Type 2. Returns true if the socket was refused.
     *
     * Each timed-out handshake strands a thread that cannot be reclaimed (see
     * [NativeHandoffPolicy.shouldServeHandshake]), so past the limit the only useful thing to do
     * is stop starting new ones. The phone will keep reconnecting; closing immediately costs it
     * nothing beyond the retry it was going to make anyway.
     */
    private fun refuseWhileBackedOff(socket: BluetoothSocket): Boolean {
        if (NativeHandoffPolicy.shouldServeHandshake(consecutiveHandshakeFailures)) return false
        if (!loggedHandshakeBackoff) {
            loggedHandshakeBackoff = true
            AppLog.w(
                "NativeAA: $consecutiveHandshakeFailures handshakes in a row ended with no answer from the phone, " +
                    "so this connection is being dropped instead of served. Each attempt costs a thread that " +
                    "cannot be recovered, and this head unit's Bluetooth is not delivering our messages. " +
                    "Use the manual poke button, or switch Android Auto mode off and on, to try again."
            )
        } else {
            AppLog.d("NativeAA: Dropping Android Auto connection — still backed off after $consecutiveHandshakeFailures failed handshakes.")
        }
        try { socket.close() } catch (e: Exception) {}
        return true
    }

    /** Clears the handshake backoff. Called wherever the user or the system asks for a fresh try. */
    private fun resetHandshakeBackoff() {
        consecutiveHandshakeFailures = 0
        loggedHandshakeBackoff = false
    }

    /**
     * Runs [block] only while [socket] is still the handshake this manager is serving.
     *
     * Every write a handshake makes to shared manager state goes through this. Losing ownership
     * does not stop a superseded handshake — where close() cannot interrupt a pending read it runs
     * on for minutes — and its late writes would clear the live session's settling stamp, cancel
     * its poke, close listeners it still needs, or wipe a backoff it had legitimately earned.
     */
    private inline fun ifOwner(socket: BluetoothSocket, block: () -> Unit) {
        if (activeHandshakeSocket === socket) block()
    }

    private suspend fun handleHandshake(socket: BluetoothSocket, localRadio: String? = null) = withContext(Dispatchers.IO) {
        // The phone reached us. Recorded here rather than at either accept site so both the
        // primary and the secondary-radio loops are covered by one statement.
        everAcceptedAaConnection = true
        pokesSinceLastAccept = 0

        // The wake poke is deliberately left running here. It used to be cancelled on entry, on
        // the reasoning that a real AA_UUID connection means the poke has done its job and is now
        // just competing for radio time — but cancelling it closes the HFP/HSP socket, and a
        // phone-side Gearhead log shows the phone reacting within milliseconds:
        //   GH.BtConnectionTracker: profile connection removed
        //   GH.CurrentCarTracker:   current car bluetooth connection is lost / is gone
        //   ...WIRELESS_SETUP_CAR_BLUETOOTH_DISAPPEAR
        // and then, when its own first-message timer expires 12 s later, refusing to retry:
        //   GH.WIRELESS.SETUP: WiFi Projection Protocol cannot start as HU is not present.
        // Real head units hold the profile link across the exchange, so hold it too, until the
        // credentials are actually delivered (see the Type 3 branch) or this handshake ends.

        // The listener stays open across the settling window, so the phone can reconnect over
        // Bluetooth while an earlier handoff is still settling. That reconnect means the earlier
        // one failed: retire it rather than serving both from the same manager state.
        val previousSocket = activeHandshakeSocket
        val previousJob = activeHandshakeJob
        // Ownership is claimed *before* the previous session is torn down, not after: cancelling
        // it makes its finally block run on another thread at a moment we do not control, and the
        // only thing keeping that block off this handshake's state is the ifOwner fence. Take
        // ownership first and the fence is already closed when the old one unwinds.
        activeHandshakeSocket = socket
        activeHandshakeJob = coroutineContext[Job]
        // Stamped after claiming ownership above, so a superseded handshake's cleanup — which
        // only fires when it still owns activeHandshakeSocket — can't wipe this one's stamp.
        handshakeStartedAt = SystemClock.elapsedRealtime()
        if (previousSocket != null && previousSocket !== socket) {
            AppLog.i("NativeAA: A new handshake arrived while one was still settling — closing the previous session.")
            handoffSettlingSince = 0L
            // Cancel *and* close, in that order: see activeHandshakeJob. Cancelling first means
            // the old coroutine cannot mistake the close for a phone-side drop and act on it.
            previousJob?.cancel()
            try { previousSocket.close() } catch (_: Exception) {}
        }
        // Whether this handshake put anything on the wire at all, and whether the phone answered
        // any of it. Together with abortedLocally they decide, once in the fenced finally below,
        // whether this attempt counts against consecutiveHandshakeFailures.
        var spokeToPhone = false
        var abortedLocally = false
        // Captured once, so a settings change mid-exchange cannot split it across two rulesets.
        val transport = settings.nativeApStrategy
        val session = WppHandshakeSession(settings.nativeWifiVersionExchange)
        // Everything the phone sends, in order. Replaces the single bounded read this used to do:
        // types 6 and 7 arrive *after* the credentials go out, so a one-shot read could never see
        // them, and the phone is free to interject a ping at any point in between.
        val inbound = Channel<ProtobufMessage>(Channel.UNLIMITED)
        var readerJob: Job? = null
        try {
            val device = socket.remoteDevice
            AppLog.i("NativeAA: Handling handshake for ${device.name} (${device.address}) on local radio [${localRadio ?: "?"}]")

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session already active. Aborting BT handshake so phone does not start a parallel wireless attempt.")
                abortedLocally = true
                try { socket.close() } catch (_: Exception) {}
                return@withContext
            }

            val macs = settings.autoStartBluetoothDeviceMacs
            if (!macs.contains(device.address)) {
                AppLog.i("NativeAA: Saving ${device.address} (${device.name}) to the list of auto-start devices.")
                val newMacs = macs + device.address
                settings.autoStartBluetoothDeviceMacs = newMacs
                settings.autoStartBluetoothDeviceName = device.name ?: "Unknown Device"
                Settings.syncAutoStartBtMacsToDeviceStorage(context, newMacs)
            }

            val input = DataInputStream(socket.inputStream)
            val output = socket.outputStream

            // [BUG_FIX] There is no BluetoothSocket.setSoTimeout(), and the old workaround —
            // close the socket to unblock readFully() — only works where close() interrupts a
            // pending read. Where it does not, the handshake never unwinds and takes the wake poke
            // and the P2P join watchdog down with it for the rest of the session. Time out the
            // *wait* instead: read on a coroutine of its own and take messages from a channel,
            // which resumes on schedule whether or not the read ever returns. The reader itself is
            // still unreclaimable on such a stack; consecutiveHandshakeFailures bounds that.
            readerJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Reader-${device.address}")) {
                try {
                    while (isActive) inbound.send(readProtobuf(input))
                } catch (e: Exception) {
                    AppLog.d("NativeAA: Bluetooth reader ended: ${e.message}")
                } finally {
                    inbound.close()
                }
            }

            // --- everything below drives the WppHandshakeSession state machine ---

            var stageEnteredAt = SystemClock.elapsedRealtime()
            var readerClosed = false
            // Filled in once the credentials resolve, before any action can need them.
            var credSsid = ""
            var credPsk = ""
            var credIp = ""
            var credBssid = ""
            // Whether the credentials went out with no BSSID at all. A join failure means something
            // different when they did — see the Fail action below.
            var bssidOmitted = false

            suspend fun runAction(action: WppAction, source: ProtobufMessage?) {
                when (action) {
                    WppAction.SendVersionRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiVersionRequest (Type 4) v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR}")
                        sendWifiVersionRequest(output)
                        spokeToPhone = true
                    }
                    WppAction.SendStartRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiStartRequest (Type 1)")
                        sendWifiStartRequest(output, credIp, 5288)
                        spokeToPhone = true
                    }
                    WppAction.SendInfoResponse -> {
                        AppLog.i("NativeAA: Phone ready for WiFi association. Delivering credentials...")
                        AppLog.i("NativeAA: [TX] Sending WifiInfoResponse (Type 3) with full credentials in 1000ms...")
                        delay(1000) // [FIX] Increased delay to give phone more processing time
                        sendWifiSecurityResponse(output, credSsid, credPsk, credBssid, transport)
                        AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                        ifOwner(socket) {
                            // The exchange is done; the phone's work is not — it still has to
                            // associate, run WPS and get a DHCP lease. See isHandoffSettling().
                            handshakeStartedAt = 0L
                            handoffSettlingSince = SystemClock.elapsedRealtime()
                            // Nothing left for the poke to wake, and it holds an RFCOMM channel on
                            // the radio the phone is about to associate over — Bluetooth work
                            // across the join strands it on "Obtaining IP".
                            pokeJob?.cancel()
                        }
                    }
                    WppAction.SendPingResponse -> {
                        // Echo the request's own bytes: whatever the phone put in a keepalive,
                        // handing it straight back cannot fail on a schema guess.
                        AppLog.d("NativeAA: [TX] Echoing WifiPingResponse (Type 9)")
                        sendProtobuf(output, source?.payload ?: ByteArray(0), WppMessageType.PING_RESPONSE)
                    }
                    WppAction.ExtendSettle -> {
                        AppLog.i("NativeAA: Phone reports it is still joining — extending the settling window.")
                        // Re-stamp rather than only extending our own deadline: isHandoffSettling()
                        // is what keeps the poke off the radio during the join, and it measures
                        // from this stamp. The session caps the total.
                        ifOwner(socket) { handoffSettlingSince = SystemClock.elapsedRealtime() }
                    }
                    WppAction.CompleteSuccess -> {
                        AppLog.i("NativeAA: WiFi session landed. Handshake session ending, releasing Bluetooth connection.")
                        ifOwner(socket) {
                            handoffSettlingSince = 0L
                            // Stop accepting new AA_UUID connections too, not just this socket —
                            // otherwise the phone's immediate reconnect-retry gets accepted,
                            // bounced (already connected), and retried again in a tight loop. See
                            // closeAaListeners() kdoc.
                            closeAaListeners()
                        }
                    }
                    is WppAction.Fail -> {
                        AppLog.w("NativeAA: Handshake failed — ${action.reason}.")
                        // Measured against a current Gearhead: it joins with a WifiNetworkSpecifier,
                        // which matches SSID *and* BSSID under a full ff:ff:ff:ff:ff:ff mask, and
                        // refuses credentials carrying no BSSID outright. So on this route a join
                        // failure right after we omitted the field is that omission, not the
                        // network — and the retry will fail the same way until an address exists.
                        if (bssidOmitted) {
                            AppLog.e(
                                "NativeAA: These credentials carried no BSSID, which this phone may " +
                                    "have refused for that reason alone. Read the access point's MAC " +
                                    "and set it as the static BSSID in Advanced settings."
                            )
                        }
                    }
                    WppAction.ResumePoke -> ifOwner(socket) {
                        // Clear the settling stamp first: triggerPoke() refuses to start while a
                        // handoff is settling, which is the whole point of that guard.
                        handoffSettlingSince = 0L
                        triggerPoke()
                    }
                }
            }

            suspend fun feed(event: WppEvent, source: ProtobufMessage? = null) {
                val before = session.stage
                val actions = session.on(event)
                for (action in actions) runAction(action, source)
                if (session.stage != before) {
                    // Stamped after the actions, so the 1 s pause before Type 3 is not charged to
                    // the settling window it opens.
                    stageEnteredAt = SystemClock.elapsedRealtime()
                    AppLog.d("NativeAA: Handshake stage $before -> ${session.stage}")
                }
            }

            /** Waits up to [budgetMs] for one message, then services timers. */
            suspend fun tick(budgetMs: Long) {
                if (readerClosed) {
                    delay(budgetMs)
                } else {
                    // Polled with tryReceive() rather than awaited with a timeout around
                    // receive(): cancelling a suspended receive can consume the element it was
                    // about to hand over, and losing the phone's Type 2 that way would stall the
                    // handshake until its stage deadline for no visible reason. tryReceive()
                    // cannot lose anything; 25 ms of latency costs nothing here.
                    val deadline = SystemClock.elapsedRealtime() + budgetMs
                    var msg: ProtobufMessage? = null
                    while (true) {
                        val result = inbound.tryReceive()
                        val received = result.getOrNull()
                        if (received != null) { msg = received; break }
                        if (result.isClosed) {
                            readerClosed = true
                            // Not a failure in itself: aa-proxy-rs treats a reset mid-bootstrap as
                            // retriable, and a phone that has our credentials may legitimately
                            // drop Bluetooth while it associates. Stage deadlines still bound us.
                            AppLog.d("NativeAA: Bluetooth read channel closed by the phone or the socket.")
                            break
                        }
                        if (SystemClock.elapsedRealtime() >= deadline) break
                        delay(25)
                    }
                    if (msg != null) {
                        AppLog.i("NativeAA: [RX] Received Type ${msg.type} (Payload size: ${msg.payload.size})")
                        logReceivedDetail(msg)
                        // The phone answered, so the channel carries data in at least one
                        // direction. Whatever the type turns out to be, this was not a silent unit.
                        ifOwner(socket) { resetHandshakeBackoff() }
                        feed(WppEvent.MessageReceived(msg.type, parseStatus(msg)), msg)
                        if (session.isTerminal()) return
                    }
                }
                if (session.stage == WppStage.SETTLING &&
                    (commManager.isConnected ||
                        commManager.connectionState.value is CommManager.ConnectionState.Connecting)) {
                    feed(WppEvent.TcpSessionUp)
                    return
                }
                val limit = session.currentStageTimeoutMs() ?: return
                if (SystemClock.elapsedRealtime() - stageEnteredAt < limit) return
                if (session.stage == WppStage.SETTLING) {
                    // The handoff never completed, so there is no session for a reconnect to
                    // collide with — the reconnect storm closeAaListeners() guards against can't
                    // happen here. Leave the listener up so the phone's own retry can be accepted
                    // (start() early-returns while isRunning, so a close here would strand us
                    // until AapService stopped and restarted the manager), and restart the poke,
                    // which was cancelled once the credentials went out.
                    AppLog.w("NativeAA: No WiFi session within ${limit / 1000}s of delivering credentials — keeping the AA listener open and resuming the wake poke.")
                    feed(WppEvent.SettleTimeout)
                } else {
                    feed(WppEvent.StageTimeout)
                }
            }

            // Some Bluetooth stacks report the RFCOMM socket "connected" slightly before the
            // underlying channel is actually ready to carry data - writing immediately can be
            // silently dropped on such hardware (a known real class of RFCOMM race: see the
            // kernel's "Move pending packets from RFCOMM socket to TTY" fix for the same
            // symptom on the HFP profile). A short delay costs nothing against either side's
            // timeout budget (phone's own first-message timeout is ~12s, ours is 15s) but gives
            // a flaky chip a moment to settle before the one message that matters most.
            delay(300)

            // The version exchange opens the conversation, *before* the wait for credentials
            // rather than after it. The phone starts its own ~12 s first-message timer when it
            // opens this channel, and on a cold P2P group the credential wait alone can outlast
            // that — a head unit that has said nothing by then is a head unit that "is not
            // present" as far as Gearhead is concerned. Saying something first costs nothing and
            // buys the whole bring-up window.
            feed(WppEvent.SocketReady)

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${currentSsid ?: "<null>"}, IP=${currentIp ?: "<null>"}")
            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max ${CREDENTIALS_WAIT_MS / 1000}s)...")

            // Wait for credentials (P2P group / hotspot bring-up can be slow), servicing the
            // phone's messages while we do: an early Type 2 or Type 5 lands here, not in the loop
            // below.
            val credentialsDeadline = SystemClock.elapsedRealtime() + CREDENTIALS_WAIT_MS
            var lastRefreshAt = SystemClock.elapsedRealtime()
            var lastProgressLogAt = SystemClock.elapsedRealtime()
            while ((currentSsid == null || currentIp == null) && isRunning && isActive &&
                !session.isTerminal() && SystemClock.elapsedRealtime() < credentialsDeadline) {
                val now = SystemClock.elapsedRealtime()
                val waitedS = (CREDENTIALS_WAIT_MS - (credentialsDeadline - now)) / 1000
                if (now - lastRefreshAt >= 10_000) {
                    lastRefreshAt = now
                    AppLog.w("NativeAA: Still waiting for credentials after ${waitedS}s. Requesting WiFi refresh...")
                    launcher.triggerWifiDirectRefresh()
                } else if (now - lastProgressLogAt >= 5_000) {
                    lastProgressLogAt = now
                    AppLog.d("NativeAA: Still waiting... SSID=${currentSsid != null}, IP=${currentIp != null} (${waitedS}s)")
                }
                tick(500)
            }

            if (currentSsid == null || currentIp == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after ${CREDENTIALS_WAIT_MS / 1000}s wait. Missing: ${if (currentSsid == null) "SSID " else ""}${if (currentIp == null) "IP" else ""}")
                abortedLocally = true
                feed(WppEvent.CredentialsUnavailable)
                return@withContext
            }

            credIp = currentIp!!
            credSsid = currentSsid!!
            credPsk = currentPsk ?: ""
            credBssid = (currentBssid ?: "").uppercase()

            // [FIX] Ensure BSSID is uppercase and not zeroed if possible
            if (!NativeCredentialsPolicy.isUsableBssid(credBssid)) {
                when (NativeCredentialsPolicy.onUnusableBssid(transport)) {
                    UnusableBssidAction.ABORT -> {
                        AppLog.e("NativeAA: BSSID is still masked/empty ($credBssid) at Type 3 time — phone WILL reject these credentials. Aborting handshake. PLEASE CHECK IF LOCATION (GPS) IS ENABLED ON THIS DEVICE!")
                        // Location is the usual cause and the one worth naming first, but it is not
                        // the only one: where this head unit is an ordinary phone rather than
                        // purpose-built hardware, every source in the chain is blocked by permission
                        // and no setting will unblock them. Say so, or the log sends the reader back
                        // to a location toggle that is already on.
                        AppLog.e("NativeAA: If location is already on, this device cannot read its own WiFi Direct MAC at all. Read it from the system (P2P device address) and set it as the static BSSID in Advanced settings.")
                        // Triggering a P2P refresh so the next attempt has a valid BSSID
                        launcher.triggerWifiDirectRefresh()
                        // Not fed to the session as CredentialsUnavailable: its failure reason
                        // would say the credentials never arrived, when in fact they arrived
                        // unusable, and the line above is the one the reporter needs to act on.
                        abortedLocally = true
                        return@withContext
                    }
                    UnusableBssidAction.SEND_WITH_EMPTY_BSSID -> {
                        // Sending is worth a try rather than expected to work — no implementation
                        // ships without a real BSSID, see NativeCredentialsPolicy. The point is that
                        // a refusal is a message we can explain; this line is the first to look at
                        // when one arrives.
                        AppLog.w("NativeAA: No usable BSSID for this access point — sending the credentials without one, which most phones refuse. Set a BSSID by hand in Advanced settings if the phone does not join.")
                        credBssid = ""
                        bssidOmitted = true
                    }
                }
            }

            // The port the credentials point at must be bound before they go out. The phone's next
            // move after Type 3 is to join the network and dial it; if nothing is listening it
            // gets a refusal, and the log reads as a perfect handshake followed by nothing at all.
            // Short wait rather than none: start() binds the port at service start, so being here
            // with it unbound means a genuine failure, not a race — but a session torn down and
            // rebuilt a moment ago can still be releasing it.
            if (!awaitWirelessServerListening(PORT_WAIT_MS)) {
                AppLog.e("NativeAA: Handshake aborted — nothing is listening on port 5288 after ${PORT_WAIT_MS / 1000}s, so the phone would join the network and find no head unit. Restart the app if this persists.")
                abortedLocally = true
                feed(WppEvent.CredentialsUnavailable)
                return@withContext
            }

            AppLog.i("NativeAA: Starting Handshake Exchange:")
            AppLog.i("  > Target SSID: $credSsid")
            AppLog.i("  > Target IP:   $credIp:5288")
            AppLog.i("  > BSSID:       $credBssid")

            feed(WppEvent.CredentialsReady)

            // Runs until the session finishes: the projection session lands, the phone reports
            // the join failed (Type 6), or a stage deadline expires.
            //
            // [BUG_FIX] The settle was once a flat delay(3000) before closing Bluetooth — a race,
            // not a grace period, since the phone needs however long it needs. Association has
            // been measured at 21 s on hardware where the 3 s close killed it dead. Wait for the
            // session, and where the phone reports its own progress, let it.
            while (isRunning && isActive && !session.isTerminal()) {
                tick(250)
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            // Only clear the stamps if this handshake still owns them — a superseding handshake
            // has already taken over and set its own.
            if (activeHandshakeSocket === socket) {
                activeHandshakeSocket = null
                activeHandshakeJob = null
                handshakeStartedAt = 0L
                handoffSettlingSince = 0L
                // [BUG_FIX] Every silent ending counts, not just the timeout that used to
                // increment inline: a socket error, a swallowed write and a cancellation are one
                // failure from the outside, and each strands an unreclaimable IO thread.
                // Excludes our own pre-exchange aborts (no credentials, masked BSSID, USB already
                // up) — those repeat for as long as location services are off, and backing off
                // would bury the log line saying how to fix it.
                if (spokeToPhone && !abortedLocally && session.messagesReceived == 0) {
                    consecutiveHandshakeFailures++
                }
            }
            // Best effort only, exactly as before: on a stack where close() does not interrupt a
            // pending read this cannot end the reader — a blocking JNI read has no suspension
            // point to cancel at — so its thread is stranded from here on.
            readerJob?.cancel()
            inbound.close()
            try { socket.close() } catch (e: Exception) {}
            AppLog.i("NativeAA: BT Handshake socket closed.")
        }
    }

    /**
     * The status field of a message that carries one, or null when it has none, when the message
     * cannot be parsed, or when the type does not have one.
     *
     * Null is deliberately not a failure: [WppHandshakeSession] only ever treats a non-null,
     * non-zero status as the phone reporting trouble, so a message we fail to decode can never
     * abort a handshake that was going fine.
     */
    private fun parseStatus(msg: ProtobufMessage): Int? = try {
        when (msg.type) {
            WppMessageType.VERSION_RESPONSE ->
                Wireless.WifiVersionResponse.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            WppMessageType.CONNECT_STATUS ->
                Wireless.WifiConnectStatus.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            WppMessageType.START_RESPONSE ->
                Wireless.WifiStartResponse.parseFrom(msg.payload).let { if (it.hasStatus()) it.status else null }
            else -> null
        }
    } catch (e: Exception) {
        AppLog.d("NativeAA: Could not parse Type ${msg.type} payload (${msg.payload.size} bytes): ${e.message}")
        null
    }

    /** Says what a received message actually contained, where that is worth having in a log. */
    private fun logReceivedDetail(msg: ProtobufMessage) {
        try {
            when (msg.type) {
                WppMessageType.VERSION_RESPONSE -> {
                    val v = Wireless.WifiVersionResponse.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiVersionResponse v${v.major}.${v.minor} status=${if (v.hasStatus()) v.status else "-"}")
                }
                WppMessageType.CONNECT_STATUS -> {
                    val s = Wireless.WifiConnectStatus.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiConnectStatus status=${if (s.hasStatus()) s.status else "-"} (0 = the phone got onto our network)")
                }
                WppMessageType.START_RESPONSE -> {
                    val r = Wireless.WifiStartResponse.parseFrom(msg.payload)
                    AppLog.i("NativeAA: [RX] WifiStartResponse ip=${r.ipAddress} status=${if (r.hasStatus()) r.status else "-"}")
                }
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: Type ${msg.type} payload did not parse for logging: ${e.message}")
        }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = Wireless.WifiStartRequest.newBuilder()
            .setIpAddress(ip)
            .setPort(port)
            .setStatus(0)
            .build()
        sendProtobuf(output, request.toByteArray(), WppMessageType.START_REQUEST)
    }

    /**
     * Declares our protocol version, opening the modern exchange. Real head units send this first
     * — the OEM ZLink app does — while aa-proxy-rs's own dongle does not, which is why it sits
     * behind [Settings.nativeWifiVersionExchange].
     *
     * The version numbers are a guess and known to be one; see [WppHandshakeSession].
     */
    private fun sendWifiVersionRequest(output: OutputStream) {
        val request = Wireless.WifiVersionRequest.newBuilder()
            .setMajor(WppHandshakeSession.WPP_VERSION_MAJOR)
            .setMinor(WppHandshakeSession.WPP_VERSION_MINOR)
            .build()
        sendProtobuf(output, request.toByteArray(), WppMessageType.VERSION_REQUEST)
    }

    /**
     * Sends the credentials.
     *
     * All five fields go out every time, including an empty [bssid] where we have no real address:
     * the schema the other implementations use marks bssid, security_mode and access_point_type
     * `required`, and aa-proxy-rs sets an empty string on the one path where it has no MAC rather
     * than dropping the field. Omitting it risks a strict parser rejecting the whole message, which
     * would surface as silence rather than as the specific refusal an empty one produces.
     *
     * [strategy] picks the access-point type: DYNAMIC for a hotspot, matching both reference
     * implementations, and STATIC for a WiFi Direct group as before.
     */
    private fun sendWifiSecurityResponse(
        output: OutputStream,
        ssid: String,
        key: String,
        bssid: String?,
        strategy: NativeStrategy
    ) {
        val response = Wireless.WifiInfoResponse.newBuilder()
            .setSsid(ssid)
            .setKey(key)
            .setSecurityMode(Wireless.SecurityMode.WPA2_PERSONAL)
            .setAccessPointType(
                if (strategy == NativeStrategy.HOTSPOT) Wireless.AccessPointType.DYNAMIC
                else Wireless.AccessPointType.STATIC
            )
            .setBssid(bssid.orEmpty())
            .build()
        sendProtobuf(output, response.toByteArray(), WppMessageType.INFO_RESPONSE)
    }

    private fun sendProtobuf(output: OutputStream, data: ByteArray, type: Int) {
        output.write(WppFraming.encodeFrame(data, type))
        output.flush()
        // Not "successfully delivered": write() and flush() returned, nothing more. A stack that
        // accepts the write and puts nothing on the air logs every send exactly like this, so the
        // old wording made a dead radio read as a textbook handshake. Proof is the phone's reply.
        AppLog.i("NativeAA: [TX] Wrote TYPE $type (size ${data.size}) to Bluetooth (write() returned; delivery unconfirmed)")
    }

    private fun readProtobuf(input: DataInputStream): ProtobufMessage {
        val header = ByteArray(WppFraming.HEADER_SIZE)
        input.readFully(header)
        val size = WppFraming.decodePayloadSize(header)
        val type = WppFraming.decodeType(header)
        val payload = if (size > 0) {
            val p = ByteArray(size)
            input.readFully(p)
            p
        } else ByteArray(0)
        return ProtobufMessage(type, payload)
    }

    data class ProtobufMessage(val type: Int, val payload: ByteArray)

    fun stop() {
        isRunning = false
        try { aaServerSocket?.close() } catch (e: Exception) {}
        try { hfpServerSocket?.close() } catch (e: Exception) {}
        synchronized(extraAaServerSockets) {
            extraAaServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraAaServerSockets.clear()
        }
        synchronized(extraHfpServerSockets) {
            extraHfpServerSockets.forEach { try { it.close() } catch (e: Exception) {} }
            extraHfpServerSockets.clear()
        }
        aaServerSocket = null
        hfpServerSocket = null
        currentSsid = null
        currentIp = null
        currentPsk = null
        currentBssid = null
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
        // Neither a handshake nor a settle can outlive the manager: leaving these set would keep
        // isAttemptInFlight() true across a restart, blocking the very poke the next start()
        // needs.
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        // Cancel before dropping the reference, for the same reason a supersede does: the socket
        // this manager just closed does not necessarily end the coroutine reading from it.
        activeHandshakeJob?.cancel()
        activeHandshakeJob = null
        activeHandshakeSocket = null
        // Only the per-attempt count resets: everAcceptedAaConnection is deliberately kept, so a
        // unit that has connected before is not warned just because the manager was re-armed.
        pokesSinceLastAccept = 0
        // A mode change or a user exit is a fresh start, so the next start() serves handshakes
        // again rather than inheriting a backoff the user cannot see.
        resetHandshakeBackoff()
    }
}
