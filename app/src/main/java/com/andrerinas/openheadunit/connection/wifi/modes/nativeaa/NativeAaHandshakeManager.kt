package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStabilityPolicy
import com.andrerinas.openheadunit.aap.AapService

import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
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

        /** Wake-poke retry cadence, matching both reference implementations' 15 to 20 s interval. */
        private const val POKE_RETRY_GAP_MS = 15_000L

        /** How long to wait for the AAP TCP port to be bound before giving up on a handshake. */
        private const val PORT_WAIT_MS = 3_000L

        /**
         * How long to wait for the port after asking for the server to be (re)started.
         *
         * Deliberately short. [awaitWirelessServerListening] delays without pumping the session's
         * inbound channel, so everything spent here is time the phone's keepalives go unserviced;
         * the bind's own retry budget is under two seconds, so this only has to cover it.
         */
        private const val PORT_ENSURE_MS = 4_000L

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
         * The detection marks a class of hardware rather than measuring the unit in front of us, so
         * it must not be the one refusal a user cannot argue with. The diagnostic still goes in the
         * log either way, and the setting is off by default.
         */
        fun externalBtOverridden(context: Context): Boolean =
            App.provide(context).settings.nativeAaIgnoreExternalBt

        fun checkCompatibility(context: Context): Boolean {
            externalBtDiagnostic()?.let {
                AppLog.w(it)
                if (!externalBtOverridden(context)) return false
                AppLog.w("NativeAA: continuing anyway, because the Bluetooth compatibility check is switched off in Settings.")
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

    // Whether this app is standing in for a radio with no hands-free stack of its own. Answered
    // once in start(), because the poke needs the same answer the HFP listener already acted on:
    // shouldPoke() only refuses while a link is actually up, so a radio that has a real hands-free
    // stack and no current link passes it, and driving a link from here would compete with it.
    @Volatile private var standingInForHfp = false
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

    /**
     * The credentials to hand the phone, as one value.
     *
     * Four separate fields were written by WifiDirectManager's delivery thread and read by the
     * handshake coroutine with no synchronisation, which allowed two failures. A read could see the
     * SSID and passphrase of one group beside the BSSID of another, and Gearhead joins with a
     * WifiNetworkSpecifier matching SSID *and* BSSID under a full mask, so it rejects the pair with
     * no clue as to why. And the null check and the `!!` that followed it were separate reads, so an
     * invalidate landing between them threw a KotlinNullPointerException that surfaced as
     * "Handshake error: null" and named nothing.
     *
     * One immutable snapshot behind one volatile reference: a reader gets all four fields from the
     * same group or none of them, and reads them once. [identity] travels with them for the same
     * reason: whether this network will still exist under this name and address next time is a
     * fact about this group, and WppEndpointPolicy reads it beside the address it describes.
     */
    private data class WifiCredentials(
        val ssid: String,
        val psk: String,
        val ip: String,
        val bssid: String,
        val identity: GroupIdentityStability,
    )

    @Volatile
    private var credentials: WifiCredentials? = null

    /**
     * Whether the user is on a screen the wake poke must not interrupt. Answered by the launcher;
     * the default is the safe one for anything that builds this class without an opinion.
     */
    @Volatile
    var userConfiguringProvider: () -> Boolean = { false }

    // Said once per run of deferred passes, so a long stay in settings is one line rather than one
    // every fifteen seconds.
    @Volatile private var pokeDeferralLogged = false

    /**
     * The WPP-over-TCP listener. From Android Auto 17.4 the phone prefers to run the handshake
     * over TCP once it knows where to dial, and it learns that from the endpoint we advertise in
     * WifiVersionRequest. Started with the Bluetooth listeners so the endpoint we advertise is
     * always one something answers on.
     */
    private var wppTcpServer: WppTcpServer? = null
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

    /** Whether the driver selection UI prompt is currently presented to the user. */
    @Volatile var isSelectionPromptActive: Boolean = false
    /** Whether the driver selection UI was explicitly canceled by the user (stops poke & refuses connections). */
    @Volatile var isSelectionCanceled: Boolean = false
    /** When a target device is selected, only this MAC is allowed to proceed. */
    @Volatile var pendingSelectionTargetMac: String? = null

    /**
     * Targets a specific driver device, ending the prompt window and waking only that device.
     */
    fun selectDriver(mac: String) {
        AppLog.i("NativeAA: Driver selected: $mac")
        isSelectionCanceled = false
        isSelectionPromptActive = false
        pendingSelectionTargetMac = mac
        manualPoke(mac)
    }

    /**
     * Cancels any active poke and refuses incoming connections because the user explicitly cancelled.
     */
    fun cancelPoke() {
        AppLog.i("NativeAA: cancelPoke() called — user explicitly canceled driver selection.")
        isSelectionCanceled = true
        isSelectionPromptActive = false
        pendingSelectionTargetMac = null
        pokeJob?.cancel()
        pokeJob = null
    }

    /**
     * Cancels any automated background multi-device poke loop when the selection prompt is active.
     */
    fun cancelActivePokeLoop() {
        if (pendingSelectionTargetMac == null && pokeJob?.isActive == true) {
            AppLog.i("NativeAA: Cancelling background multi-device poke loop because selection prompt is active.")
            pokeJob?.cancel()
            pokeJob = null
        }
    }

    /**
     * Determines whether an incoming Bluetooth RFCOMM connection from a phone should be accepted.
     */
    fun shouldAcceptHandshake(remoteAddress: String): Boolean {
        if (isSelectionCanceled) {
            AppLog.i("NativeAA: User explicitly canceled driver selection — refusing connection from $remoteAddress")
            return false
        }
        if (isSelectionPromptActive) {
            val target = pendingSelectionTargetMac
            if (target == null || (remoteAddress.isNotEmpty() && !remoteAddress.equals(target, ignoreCase = true))) {
                AppLog.i("NativeAA: Selection prompt active (target=$target) — refusing connection from $remoteAddress")
                return false
            }
        }
        return true
    }

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
    fun updateWifiCredentials(
        ssid: String,
        psk: String,
        ip: String,
        bssid: String,
        identity: GroupIdentityStability,
    ) {
        AppLog.i("NativeAA: Credentials updated. SSID=$ssid, IP=$ip, BSSID=$bssid, identity stable=${GroupIdentityStabilityPolicy.label(identity)}")
        credentials = WifiCredentials(ssid = ssid, psk = psk, ip = ip, bssid = bssid, identity = identity)
    }

    /** Clears cached credentials so an in-progress wait doesn't hand out stale ones for a group
     *  that's about to be torn down. */
    fun invalidateCredentials() {
        credentials = null
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

    /**
     * What a setup QR would carry right now, or null while nothing has been resolved.
     *
     * The live network and the live port, never the settings behind them: the QR writes a record on
     * the phone that outlives this session, so it may only name what something is actually
     * answering on. The Bluetooth address is the exception, being an identity rather than a route.
     * [ProjectionQrPolicy] decides whether that is enough.
     */
    @SuppressLint("MissingPermission")
    fun projectionQrSnapshot(): ProjectionQrSnapshot {
        val creds = credentials
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        val adapterName = try { adapter?.name } catch (e: SecurityException) { null }
        return ProjectionQrSnapshot(
            strategy = launcher.strategy,
            savedStrategy = settings.nativeApStrategy,
            ssid = creds?.ssid,
            passkey = creds?.psk,
            bssid = creds?.bssid,
            ip = creds?.ip,
            listeningPort = wppTcpServer?.listeningPort,
            // The stored address first, normalised: it is the same question ServiceDiscoveryResponse
            // asks for carAddress, and on a device that masks its own adapter it is the only answer.
            bluetoothMac = SoftApBssidPolicy.choose(
                settings.bluetoothAddress,
                listOf(BluetoothHelper.getBluetoothMacAddress(context, adapter))
            ).ifEmpty { null },
            bluetoothName = adapterName,
        )
    }

    // True while either a wake-up poke's socket.connect() or a real handshake is in progress, or
    // a delivered handoff is still settling. AutoStartReceiver's own poke can generate the
    // ACL_CONNECTED broadcast that re-triggers AapService's BT auto-start re-arm; callers
    // deciding whether it's safe to force-reinit should check this instead of isActive() alone.
    fun isAttemptInFlight(): Boolean = isHandshakeInFlight() || pokeAttemptInFlight || isHandoffSettling()

    @SuppressLint("MissingPermission")
    fun start() {
        if (isRunning) return

        // Ahead of every Bluetooth check below, because this listener does not need Bluetooth. A
        // unit whose adapter the phone cannot reach returns early from all of them, and that is
        // exactly the unit for which TCP is the only route left. isRunning stays the answer to
        // "are the RFCOMM listeners up", which is what isActive() callers are asking; stop() takes
        // this down either way.
        startWppTcpServer()

        // Leave isRunning false, like the "adapter disabled" case below: isActive() callers must
        // see this as genuinely stopped. Nothing here is retryable, but a listener that was never
        // opened must not be reported as up.
        externalBtDiagnostic()?.let {
            if (!externalBtOverridden(context)) {
                AppLog.e(it)
                return
            }
            AppLog.w("$it\nNativeAA: starting anyway, because the Bluetooth compatibility check is switched off in Settings.")
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
        launchAaAcceptLoop(adapter, localRadioName)

        // Start HFP RFCOMM Server (Required by some phones to detect HU)
        standingInForHfp = shouldRegisterDummyHfp(adapter, localRadioName)
        if (standingInForHfp) scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer")) {
            try {
                hfpServerSocket = adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                while (isRunning && isActive) {
                    val socket = hfpServerSocket?.accept()
                    if (socket != null) {
                        logHfpAccept(socket, localRadioName)
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            // We publish the Hands-Free record, so the opening exchange is ours to
                            // start whoever opened the socket. Answering always runs; speaking
                            // first is what the gate decides.
                            serveHfpSocket(
                                socket,
                                "radio [$localRadioName]",
                                initiate = shouldInitiateSlc(standingInForHfp),
                                closeWhenDone = true
                            )
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
        val secondaries = secondaryRadioHandles()
        if (secondaries.isNotEmpty()) {
            AppLog.i("NativeAA: Opening AA listeners on ${secondaries.size} secondary Bluetooth radio(s) for dual-radio head units: ${secondaries.joinToString { it.serviceName }}")
            secondaries.forEach { launchExtraServers(it.serviceName, it.adapter) }
        }
    }

    /** The Bluetooth radios other than the primary, matched by system service name. */
    private fun secondaryRadioHandles() = run {
        val handles = try {
            BluetoothHelper.getAllBluetoothAdapterHandles(context)
        } catch (e: Exception) { emptyList() }
        val secondaryNames = filterSecondaryServiceNames(
            settings.bluetoothManagerServiceName,
            handles.map { it.serviceName }
        ).toSet()
        handles.filter { it.serviceName in secondaryNames }
    }

    /**
     * Opens the Android Auto listener on one radio and serves it until the socket closes. The
     * primary radio and every secondary one run this same loop; [serviceName] names a secondary.
     */
    private fun launchAaAcceptLoop(adapter: BluetoothAdapter, radioName: String, serviceName: String? = null) {
        val coroutineName = if (serviceName == null) "NativeAa-RfcommServer" else "NativeAa-RfcommServer-2"
        scope.launch(Dispatchers.IO + CoroutineName(coroutineName)) {
            val label = if (serviceName == null) "AA Server socket" else "Secondary AA server"
            val suffix = if (serviceName == null) "" else " ['$serviceName' $radioName]"
            try {
                val server = listenOnAaUuid(adapter)
                if (serviceName == null) {
                    aaServerSocket = server
                    AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID ($AA_UUID) on radio [$radioName]... Waiting for phone to connect back!")
                } else {
                    extraAaServerSockets.add(server)
                    AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID on secondary radio '$serviceName' [$radioName]")
                }
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        val remoteAddress = try { socket.remoteDevice.address } catch (_: Exception) { "" }
                        if (serviceName == null) {
                            AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name} ($remoteAddress) on local radio [$radioName]")
                        } else {
                            AppLog.i("NativeAA: Connection accepted (secondary radio '$serviceName' [$radioName]) from ${socket.remoteDevice.name} ($remoteAddress)")
                        }
                        if (!shouldAcceptHandshake(remoteAddress)) {
                            try { socket.close() } catch (_: Exception) {}
                            continue
                        }
                        if (refuseWhileBackedOff(socket)) continue
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(socket, radioName)
                        }
                    }
                }
            } catch (e: Exception) {
                if (aaListenersClosedForSession) {
                    AppLog.d("NativeAA: $label closed after successful handoff$suffix.")
                } else if (isRunning) {
                    AppLog.e("NativeAA: $label error$suffix: ${e.message}", e)
                } else {
                    AppLog.d("NativeAA: $label closed cleanly$suffix.")
                }
            }
        }
    }

    /**
     * Puts the Bluetooth side back where it was before the session, without taking it down.
     *
     * A completed handoff closes only the Android Auto listeners and leaves the rest running, so
     * a session that ends on its own needs those back and nothing else. A restart would also
     * rebind the WPP TCP port the phone may have just dialled, drop the hands-free record the
     * phone keys its reconnect on, and forget credentials that still name the live network.
     */
    @SuppressLint("MissingPermission")
    fun rearmForNextSession() {
        if (!isRunning) return
        // A handshake cannot outlive the session it set up, and its stamps are what hold the wake
        // poke off and defer the join watchdog. Nothing else clears them once the socket is gone.
        activeHandshakeJob?.cancel()
        activeHandshakeJob = null
        activeHandshakeSocket = null
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        resetHandshakeBackoff()

        if (!SessionEndGroupPolicy.shouldReopenAaListeners(isRunning, aaListenersClosedForSession)) {
            AppLog.i("NativeAA: the Android Auto listeners are still open, so the phone can come straight back.")
            return
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled, so the Android Auto listeners stay closed.")
            return
        }
        // Cleared before the launch: the loop reads it to tell a handoff's close from a failure.
        aaListenersClosedForSession = false
        AppLog.i("NativeAA: reopening the Android Auto listeners for the phone's return.")
        launchAaAcceptLoop(adapter, localRadioName)
        secondaryRadioHandles().forEach {
            val radioName = try { it.adapter.name ?: "?" } catch (e: Exception) { "?" }
            launchAaAcceptLoop(it.adapter, radioName, it.serviceName)
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
        launchAaAcceptLoop(extra, radioName, serviceName)
        // Held rather than asked twice: this radio's own answer, because standingInForHfp speaks
        // only for the primary and a secondary stand-in still has to open its link.
        val standingInOnExtra = shouldRegisterDummyHfp(extra, "'$serviceName' $radioName")
        if (standingInOnExtra) scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpServer-2")) {
            try {
                val server = extra.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
                extraHfpServerSockets.add(server)
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        logHfpAccept(socket, "$serviceName $radioName")
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-HfpResponder-${socket.remoteDevice.address}")) {
                            serveHfpSocket(
                                socket,
                                "radio [$serviceName $radioName]",
                                initiate = shouldInitiateSlc(standingInOnExtra),
                                closeWhenDone = true
                            )
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
     * Opens the Android Auto record on [adapter], secure unless the user has asked otherwise.
     *
     * An insecure record lets a phone connect before the link is authenticated, which only matters
     * where the bond or its link key is gone on one side. Everything behind it still applies: the
     * accept path refuses a device this unit is not bonded to.
     */
    private fun listenOnAaUuid(adapter: BluetoothAdapter): BluetoothServerSocket =
        if (settings.insecureAaRfcommListener) {
            AppLog.i("NativeAA: publishing the Android Auto record as insecure, at the user's request.")
            adapter.listenUsingInsecureRfcommWithServiceRecord("AndroidAuto", AA_UUID)
        } else {
            adapter.listenUsingRfcommWithServiceRecord("AndroidAuto", AA_UUID)
        }

    /**
     * Whether to publish our stand-in Hands-Free record on [adapter].
     *
     * getUuids() is not public API, so it is read reflectively and a refusal means "register it",
     * which is what this did unconditionally before. [HfpServiceRecordPolicy] holds the rule.
     */
    private fun shouldRegisterDummyHfp(adapter: BluetoothAdapter, radio: String): Boolean {
        val uuids = try {
            when (val raw = adapter.javaClass.getMethod("getUuids").invoke(adapter)) {
                is Array<*> -> raw.mapNotNull { it?.toString() }
                is List<*> -> raw.mapNotNull { it?.toString() }
                else -> null
            }
        } catch (e: Throwable) {
            AppLog.d("NativeAA: could not read radio [$radio]'s service UUIDs: ${e.message}")
            null
        }
        val register = HfpServiceRecordPolicy.shouldRegisterDummyHfp(uuids)
        if (!register) {
            AppLog.i(
                "NativeAA: radio [$radio] already advertises Hands-Free, so the stand-in HFP " +
                    "record is not registered - the real stack answers calls, this app cannot."
            )
        } else {
            // Say which of the two reasons it was. A log that only prints the skip cannot tell a
            // radio with no hands-free stack apart from one whose records could not be read, and
            // that difference decides whether standing in was the right call.
            val why = if (uuids == null) "its records could not be read" else "it advertises no Hands-Free"
            AppLog.i("NativeAA: radio [$radio] gets the stand-in HFP record, because $why.")
        }
        return register
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
     * Serves one hands-free channel, answering the phone and, when [initiate], opening the service
     * level connection ourselves.
     *
     * A phone will not start wireless Android Auto against a head unit that is merely ACL-connected;
     * it wants a profile that has actually reached connected, which only happens once the opening
     * exchange finishes. Accepting the socket and waiting to be spoken to leaves the phone's own
     * state machine half-open until it times out. [HfpSlcInitiator] holds the walk.
     *
     * [closeWhenDone] is false for the wake poke, whose own caller owns that socket.
     */
    private suspend fun serveHfpSocket(
        socket: BluetoothSocket,
        label: String,
        initiate: Boolean,
        closeWhenDone: Boolean
    ) = withContext(Dispatchers.IO) {
        try {
            val input = socket.inputStream
            val output = socket.outputStream
            val buf = ByteArray(1024)
            // The keepalive writes to this same stream from its own coroutine, and two interleaved
            // partial writes on one RFCOMM channel is a corrupt command line.
            val writeLock = Any()
            fun write(lines: List<String>) {
                if (lines.isEmpty()) return
                synchronized(writeLock) {
                    lines.forEach { output.write(it.toByteArray(Charsets.US_ASCII)) }
                    output.flush()
                }
                AppLog.d("NativeAA: HFP TX ($label): ${lines.joinToString("|") { it.trim() }}")
            }

            AppLog.i("NativeAA: HFP responder active for $label")

            coroutineScope {
                var keepAlive: Job? = null
                var stage = HfpSlcInitiator.Stage.IDLE
                if (initiate) {
                    val opening = HfpSlcInitiator.open()
                    stage = opening.stage
                    write(opening.writes)
                }

                while (isRunning && isActive && socket.isConnected) {
                    if (input.available() > 0) {
                        val read = input.read(buf)
                        if (read == -1) break

                        val buffer = String(buf, 0, read, Charsets.US_ASCII)
                        HfpAtResponder.split(buffer).forEach { AppLog.d("NativeAA: HFP RX ($label): $it") }

                        val step = HfpSlcInitiator.onReceived(stage, buffer)
                        stage = step.stage
                        write(step.writes)

                        if (step.establishedNow && keepAlive == null) {
                            // Warning, not info: this is the moment the cost lands, and a reporter
                            // whose calls stop being heard needs it in a log exported at the
                            // default level.
                            AppLog.w(
                                "NativeAA: hands-free service level connection established ($label). " +
                                    "The phone now treats this head unit as its hands-free device, " +
                                    "and this app cannot carry call audio."
                            )
                            keepAlive = launch(CoroutineName("NativeAa-HfpKeepAlive")) {
                                try {
                                    while (isRunning && isActive && socket.isConnected) {
                                        write(listOf(HfpSlcInitiator.command(HfpSlcInitiator.KEEPALIVE_COMMAND)))
                                        delay(HfpSlcInitiator.KEEPALIVE_INTERVAL_MS)
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    AppLog.d("NativeAA: HFP keepalive ended ($label): ${e.message}")
                                }
                            }
                        }
                    }
                    // Only while the walk is in flight: four steps at 200 ms would spend most of a
                    // poke's hold getting to a link the poke exists to establish.
                    delay(if (stage == HfpSlcInitiator.Stage.IDLE ||
                            stage == HfpSlcInitiator.Stage.ESTABLISHED) 200 else 50)
                }
                keepAlive?.cancel()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.d("NativeAA: HFP responder error ($label): ${e.message}")
        } finally {
            if (closeWhenDone) {
                try { socket.close() } catch (e: Exception) {}
                AppLog.i("NativeAA: HFP socket for ${socket.remoteDevice.address} closed.")
            }
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
                    holdPoke(socket, device, profile, uuid, holdMs)
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
     * Whether to speak first on a hands-free socket rather than only answering on it.
     *
     * [standingIn] is per radio, not the field: only the primary radio writes `standingInForHfp`, so
     * a secondary radio publishing its own stand-in has to pass its own answer or it would never
     * open the link. The gateway role is excluded from the link read because it reports this unit's
     * own headset, which does not compete with standing in for the projecting phone.
     */
    private fun shouldInitiateSlc(standingIn: Boolean): Boolean {
        val handsFreeLink = BluetoothWakePolicy.HandsFreeLink.of(
            BluetoothHelper.handsFreeLinkState(context, includeGatewayRole = false)
        )
        val open = HfpServiceRecordPolicy.shouldOpenServiceLevelConnection(
            settings.nativeAaCompleteHfpSlc, standingIn, handsFreeLink
        )
        // Standing down for a real link is the reason this is safe to have on by default, so say it
        // happened. Only reachable from an accepted socket: the poke's own guard refuses earlier on
        // the same reading, so this cannot repeat with the retry loop.
        if (!open && standingIn && handsFreeLink == BluetoothWakePolicy.HandsFreeLink.CONNECTED) {
            AppLog.i(
                "NativeAA: a real hands-free link is up, so the stand-in does not open one - " +
                    "the phone's own hands-free device answers it, this app cannot."
            )
        }
        return open
    }

    /**
     * Holds a connected poke for [holdMs], speaking hands-free over it where that is what it
     * reached.
     *
     * The targets, the hold and the retry cadence are unchanged; what is new is that the channel is
     * not silent. A phone moves its hands-free profile to connecting on our incoming connection and
     * then waits for us to open the exchange, so a silent hold times out there and never becomes the
     * connected profile Android Auto requires before it will start wireless setup.
     */
    private suspend fun holdPoke(
        socket: BluetoothSocket,
        device: BluetoothDevice,
        profile: String,
        uuid: UUID,
        holdMs: Long
    ) {
        if (!shouldInitiateSlc(standingInForHfp) || !BluetoothWakePolicy.carriesServiceLevelConnection(uuid)) {
            delay(holdMs)
            return
        }
        coroutineScope {
            // A child of this poke, not of the service scope, so cancelling the poke job unwinds it
            // and joins it before pokeDevice()'s own finally closes the socket underneath it.
            val slc = launch(CoroutineName("NativeAa-HfpSlc-${device.address}")) {
                serveHfpSocket(socket, "$profile poke to ${device.address}", initiate = true, closeWhenDone = false)
            }
            try {
                delay(holdMs)
            } finally {
                slc.cancel()
            }
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
        if (isSelectionCanceled) {
            AppLog.i("NativeAA: Driver selection was explicitly canceled by user — skipping automated poke.")
            return
        }
        val bonded = try { BluetoothHelper.getBluetoothAdapter(context)?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val connected = BluetoothHelper.getConnectedBluetoothDevices(context)
        val likelyPhones = bonded.filter {
            BluetoothHelper.isLikelyPhone(it, settings.nativePreferredDeviceMac, settings.lastConnectedNativeMac)
        }
        val targetList = likelyPhones.ifEmpty { bonded }
        val hasHistory = settings.lastConnectedNativeMac.isNotEmpty() ||
            settings.nativePreferredDeviceMac.isNotEmpty() ||
            settings.autoStartBluetoothDeviceMacs.isNotEmpty()
        val selectorActive = NativeDriverSelectionPolicy.shouldShowSelector(
            mode = settings.nativeDriverSelectionMode,
            pairedCount = targetList.size,
            connectedCount = connected.size,
            hasHistory = hasHistory
        )
        if ((selectorActive || isSelectionPromptActive) && pendingSelectionTargetMac == null) {
            AppLog.i("NativeAA: Multi-driver selection is active and awaiting user choice — deferring automated multi-device poke loop.")
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

        // Named pokeKey, not credentials: the coroutine below tests the *property* for readiness, and
        // a local called credentials would shadow it with a Triple that is never null.
        val snapshot = credentials
        val pokeKey = Triple(snapshot?.ssid ?: "", snapshot?.ip ?: "", snapshot?.bssid ?: "")
        if (pokeJob?.isActive == true && pokeKey == lastPokeTriggerCredentials) {
            AppLog.d("NativeAA: triggerPoke() called again with unchanged credentials while a poke is already running - not restarting it.")
            return
        }
        lastPokeTriggerCredentials = pokeKey

        pokeJob?.cancel()
        pokeDeferralLogged = false
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            AppLog.d("NativeAA: triggerPoke() delay starting (2s)...")
            delay(2000) // Small safety delay before connecting

            while (isRunning && isActive) {
                val settling = isHandoffSettling()
                val handshaking = isHandshakeInFlight()
                val sessionUp = commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                val userConfiguring = try { userConfiguringProvider() } catch (e: Exception) { false }
                when (NativeHandoffPolicy.loopStep(settling, handshaking, sessionUp, userConfiguring)) {
                    NativeHandoffPolicy.LoopStep.STOP -> {
                        AppLog.i(
                            "NativeAA: Stopping poke retry loop " +
                                "(settling=$settling, handshake=$handshaking, session=$sessionUp)."
                        )
                        break
                    }
                    NativeHandoffPolicy.LoopStep.DEFER -> {
                        if (!pokeDeferralLogged) {
                            pokeDeferralLogged = true
                            AppLog.i("NativeAA: the settings screen is open, so the wake poke waits until it closes.")
                        }
                        delay(POKE_RETRY_GAP_MS)
                        continue
                    }
                    NativeHandoffPolicy.LoopStep.POKE -> pokeDeferralLogged = false
                }

                val selectedMacs = settings.nativePokeBtMacs
                val devicesToPoke = when (val target =
                    PokeTargetPolicy.targets(selectedMacs, settings.nativePokeAllPairedDevices)) {
                    is PokeTargets.Selected -> {
                        // Two questions, two answers. Skipping a poke is retried seconds later;
                        // forgetting a MAC is permanent, so it needs evidence the device is really
                        // gone rather than an adapter that happened to be off. Both are in the policy.
                        val bonded = mutableListOf<BluetoothDevice>()
                        val staleMacs = mutableSetOf<String>()
                        target.macs.forEach { mac ->
                            val reading = bondReadingFor(adapter, mac)
                            if (BluetoothWakePolicy.mayPoke(reading)) {
                                try { bonded.add(adapter.getRemoteDevice(mac)) } catch (e: Exception) {}
                            }
                            if (BluetoothWakePolicy.shouldForget(reading)) staleMacs.add(mac)
                        }
                        if (staleMacs.isNotEmpty()) {
                            AppLog.w("NativeAA: Dropping wake poke MAC(s) no longer paired: $staleMacs")
                            settings.nativePokeBtMacs = target.macs - staleMacs
                        }
                        bonded
                    }
                    PokeTargets.AllPaired -> {
                        AppLog.w("NativeAA: No wake poke device selected, and poking all paired devices is on. Poking all of them...")
                        adapter.bondedDevices.toList()
                    }
                    PokeTargets.None -> {
                        AppLog.w("NativeAA: No wake poke device selected, so nothing is poked. Choose one in Auto Start settings.")
                        emptyList()
                    }
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
                    if (credentials == null) {
                        AppLog.i("NativeAA: WiFi credentials not ready before poke. Requesting WiFi refresh...")
                        launcher.triggerWifiDirectRefresh()
                        var waitedMs = 0
                        while (credentials == null && waitedMs < 4000 && isRunning && isActive) {
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

                delay(POKE_RETRY_GAP_MS)
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
            // The manual job takes the slot the retry loop uses, so forget what the loop last poked
            // for. Left set, a redelivery of unchanged credentials during this poke reads as "the
            // loop is already running for these" and the loop is never started again.
            lastPokeTriggerCredentials = null
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                if (credentials == null) {
                    AppLog.i("NativeAA: WiFi credentials not ready before manual poke. Requesting WiFi refresh...")
                    launcher.triggerWifiDirectRefresh()
                    var waitedMs = 0
                    while (credentials == null && waitedMs < 4000 && isRunning && isActive) {
                        delay(200)
                        waitedMs += 200
                    }
                    AppLog.i("NativeAA: Pre-poke credential wait completed. SSID=${credentials?.ssid}, IP=${credentials?.ip} (waited ${waitedMs}ms)")
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
        // Whether this handshake has already retired the "nothing came back" record. The phone
        // sends several messages and the retraction only has to happen once; repeating it would
        // put a binder call on every inbound message, including through the settling window where
        // the phone is associating and there is nothing to gain by being busy.
        var retiredSilentRecord = false
        var abortedLocally = false
        // The launcher's, not the setting's: what we tell the phone has to be the network we are
        // actually hosting, and a saved transport does not reach the running launcher until it is
        // re-armed.
        val transport = launcher.strategy
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

            // The wake poke target only, and only when there is none. Writing the auto-start list
            // here turned Bluetooth auto-start on for a user who never asked, and undid a clear.
            if (PokeTargetPolicy.adoptsHandshakedDevice(settings.nativePokeBtMacs)) {
                AppLog.i("NativeAA: Saving ${device.address} (${device.name}) as the wake poke device.")
                settings.nativePokeBtMacs = setOf(device.address)
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
            // What the credentials looked like when this exchange captured them, kept as resolved
            // so the re-read before Type 3 compares like with like.
            var capturedCreds = NativeNetworkCredentials("", "", "", "")
            // Set when the network named above stopped existing before Type 3 could go out.
            var credentialsWentStale = false

            suspend fun runAction(action: WppAction, source: ProtobufMessage?) {
                when (action) {
                    WppAction.SendVersionRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiVersionRequest (Type 4) v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR}")
                        sendWifiVersionRequest(output, transport)
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
                        // Read again here rather than trusting the snapshot this exchange started
                        // with. A group removed inside the pause above leaves the phone hunting an
                        // SSID that is gone, which it cannot recover from without a new handshake.
                        val live = credentials
                        when (CredentialFreshnessPolicy.decide(
                            captured = capturedCreds,
                            live = live?.let { NativeNetworkCredentials(it.ssid, it.psk, it.ip, it.bssid.uppercase()) },
                        )) {
                            CredentialFreshnessPolicy.Action.SEND_AS_CAPTURED ->
                                AppLog.d("NativeAA: the live credentials still match the ones this handshake captured.")

                            CredentialFreshnessPolicy.Action.SEND_LIVE -> {
                                val freshBssid = live!!.bssid.uppercase()
                                val usable = NativeCredentialsPolicy.isUsableBssid(freshBssid)
                                if (!usable && transport == NativeTransport.WIFI_DIRECT) {
                                    AppLog.e("NativeAA: the group changed while Type 3 was pending and the new one has no readable address yet, so nothing is sent; the phone retries once a group is up.")
                                    credentialsWentStale = true
                                } else {
                                    AppLog.w("NativeAA: the group changed while Type 3 was pending (was $credSsid/$credBssid, now ${live.ssid}/$freshBssid); sending the live credentials instead.")
                                    credSsid = live.ssid
                                    credPsk = live.psk
                                    credBssid = if (usable) freshBssid else ""
                                    bssidOmitted = credBssid.isEmpty()
                                }
                            }

                            CredentialFreshnessPolicy.Action.ABORT -> {
                                AppLog.e("NativeAA: the network these credentials name was taken down while Type 3 was pending, so nothing is sent; the phone retries once a group is up.")
                                credentialsWentStale = true
                            }
                        }
                        if (credentialsWentStale) {
                            // Not fed to the session: an abort here is ours, and counting it as a
                            // phone that never answered would spend the handshake backoff on it.
                            abortedLocally = true
                            launcher.triggerWifiDirectRefresh()
                            return
                        }
                        sendWifiSecurityResponse(output, credSsid, credPsk, credBssid, transport)
                        // Set after the write returns, not before the delay above: this marks that
                        // we put bytes on the channel, and a phone that opened the exchange itself
                        // can reach this having had nothing from us before it.
                        spokeToPhone = true
                        AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                        val remoteMac = try { socket.remoteDevice.address } catch (_: Exception) { "" }
                        if (remoteMac.isNotEmpty()) {
                            settings.lastConnectedNativeMac = remoteMac
                        }
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
                        spokeToPhone = true
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
                                    "and set it as the static BSSID under Wireless connection in Settings."
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
                        ifOwner(socket) {
                            resetHandshakeBackoff()
                            // The banner's claim is literally that nothing came back, so anything
                            // coming back retires it. Kept as loose as the claim on purpose: a
                            // narrower rule could leave a unit accused after it started working.
                            if (!retiredSilentRecord) {
                                retiredSilentRecord = true
                                ConnectionIssues.clear(context, ConnectionIssue.BLUETOOTH_SENT_NO_DATA)
                            }
                        }
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

            AppLog.i("NativeAA: Phone connected. Current credentials state: SSID=${credentials?.ssid ?: "<null>"}, IP=${credentials?.ip ?: "<null>"}")
            AppLog.i("NativeAA: Waiting for WiFi credentials to be ready (Max ${CREDENTIALS_WAIT_MS / 1000}s)...")

            // Wait for credentials (P2P group / hotspot bring-up can be slow), servicing the
            // phone's messages while we do: an early Type 2 or Type 5 lands here, not in the loop
            // below.
            val credentialsDeadline = SystemClock.elapsedRealtime() + CREDENTIALS_WAIT_MS
            var lastRefreshAt = SystemClock.elapsedRealtime()
            var lastProgressLogAt = SystemClock.elapsedRealtime()
            while (credentials == null && isRunning && isActive &&
                !session.isTerminal() && SystemClock.elapsedRealtime() < credentialsDeadline) {
                val now = SystemClock.elapsedRealtime()
                val waitedS = (CREDENTIALS_WAIT_MS - (credentialsDeadline - now)) / 1000
                if (now - lastRefreshAt >= 10_000) {
                    lastRefreshAt = now
                    AppLog.w("NativeAA: Still waiting for credentials after ${waitedS}s. Requesting WiFi refresh...")
                    launcher.triggerWifiDirectRefresh()
                } else if (now - lastProgressLogAt >= 5_000) {
                    lastProgressLogAt = now
                    AppLog.d("NativeAA: Still waiting... credentials=${credentials != null} (${waitedS}s)")
                }
                tick(500)
            }

            // Read once. The check and the use used to be separate reads of four separate fields,
            // so an invalidate between them threw on the `!!` and reported "Handshake error: null".
            val snapshot = credentials
            if (snapshot == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after ${CREDENTIALS_WAIT_MS / 1000}s wait.")
                abortedLocally = true
                feed(WppEvent.CredentialsUnavailable)
                return@withContext
            }

            credIp = snapshot.ip
            credSsid = snapshot.ssid
            credPsk = snapshot.psk
            credBssid = snapshot.bssid.uppercase()
            capturedCreds = NativeNetworkCredentials(credSsid, credPsk, credIp, credBssid)

            // [FIX] Ensure BSSID is uppercase and not zeroed if possible
            if (!NativeCredentialsPolicy.isUsableBssid(credBssid)) {
                when (NativeCredentialsPolicy.onUnusableBssid(transport)) {
                    UnusableBssidAction.ABORT -> {
                        AppLog.e("NativeAA: BSSID is still masked/empty ($credBssid) at Type 3 time — phone WILL reject these credentials. Aborting handshake. PLEASE CHECK IF LOCATION (GPS) IS ENABLED ON THIS DEVICE!")
                        // Location is the usual cause and the one worth naming first. What follows
                        // it has to be exact: every rung was tried, including the one that needs no
                        // permission at all, and the dump above says what each answered. Telling
                        // the reader the address cannot be read here would send them to type one
                        // in when the log has already shown which source refused.
                        AppLog.e("NativeAA: If location is already on, no source on this unit answered - not the interface's own address, not sysfs, and not the address derived from its IPv6 link-local. The dump above says what each one returned. A group that has not come up yet is the common reason; where it has, read the P2P device address from the system and set it as the static BSSID under Wireless connection in Settings.")
                        // The loudest failure on this route: two error lines in a log, and a phone
                        // that simply never arrives. Recorded so the main screen can say so later,
                        // because nobody is reading a log from the driver's seat.
                        ConnectionIssues.raise(context, ConnectionIssue.BSSID_UNAVAILABLE)
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
                        AppLog.w("NativeAA: No usable BSSID for this access point — every source was tried, including the address derived from the interface's IPv6 link-local. Sending the credentials without one, which most phones refuse. Set a BSSID by hand under Wireless connection in Settings if the phone does not join.")
                        credBssid = ""
                        bssidOmitted = true
                    }
                }
            } else {
                // The record is the claim that this unit could not read its own address -
                // and neither a static override nor this route disproves it. SoftApBssidPolicy
                // .choose takes the override ahead of everything and WifiDirectManager skips its
                // whole fallback chain when one is set, so behind an override the question was
                // never asked; and only the WiFi Direct abort raises this condition at all, so an
                // access-point interface's MAC says nothing about the P2P one. remedyApplied()
                // already hides the banner while an override is set, so keeping the record costs
                // the user nothing and keeps it true if they ever clear it.
                if (transport != NativeTransport.WIFI_DIRECT) {
                    AppLog.i("NativeAA: this route cannot raise the missing-BSSID condition, so the record stays as it is.")
                } else if (SoftApBssidPolicy.disprovesBssidUnavailable(credBssid, settings.staticBSSID)) {
                    AppLog.i("NativeAA: this unit read its own WiFi address, so the missing-BSSID record is retired.")
                    ConnectionIssues.clear(context, ConnectionIssue.BSSID_UNAVAILABLE)
                } else {
                    AppLog.i(
                        "NativeAA: the BSSID being sent is the static override from Settings, which is a " +
                            "way round this unit not reading its own WiFi address rather than proof that " +
                            "it can, so the missing-BSSID record stays as it is."
                    )
                }
            }

            // The port the credentials point at must be bound before they go out. The phone's next
            // move after Type 3 is to join the network and dial it; if nothing is listening it
            // gets a refusal, and the log reads as a perfect handshake followed by nothing at all.
            // Short wait rather than none: start() binds the port at service start, so being here
            // with it unbound means a genuine failure, not a race — but a session torn down and
            // rebuilt a moment ago can still be releasing it.
            if (!awaitWirelessServerListening(PORT_WAIT_MS)) {
                // Ask for a repair before giving up. A server that failed to bind once used to stay
                // dead for the life of the mode, because the only thing that rebuilt it was a full
                // mode re-initialisation - so this abort repeated every few seconds, forever, with
                // the phone woken each time and told nothing.
                if (!ensureWirelessServerListening("the Bluetooth handshake", PORT_ENSURE_MS)) {
                    AppLog.e("NativeAA: Handshake aborted — nothing is listening on port 5288 after ${PORT_WAIT_MS / 1000}s, and starting it here did not work either, so the phone would join the network and find no head unit. Restart the app if this persists.")
                    abortedLocally = true
                    feed(WppEvent.CredentialsUnavailable)
                    return@withContext
                }
                AppLog.i("NativeAA: port 5288 was not bound, and is now. Carrying on with the handshake.")
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
            while (isRunning && isActive && !session.isTerminal() && !credentialsWentStale) {
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
                    // The same fact, written down where the user can be told about it. Our bytes
                    // went out and the phone answered none of them, which is what a head unit
                    // whose Bluetooth accepts writes and airs nothing looks like from in here.
                    // Deliberately the same predicate as the backoff rather than a second one:
                    // it already excludes the aborts that are ours rather than the radio's.
                    AppLog.w("NativeAA: the phone connected over Bluetooth and answered nothing we sent. If this repeats, this unit's Bluetooth cannot carry Android Auto and USB or the Wireless Helper mode are the way round it.")
                    ConnectionIssues.raise(context, ConnectionIssue.BLUETOOTH_SENT_NO_DATA)
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
     * Tries to get the AAP port bound, and reports whether it is.
     *
     * Called by the Bluetooth handshake when it finds the port unbound with credentials already in
     * hand. Until this existed the handshake could only give up, so a server that died once stayed
     * dead for the life of the mode: the phone was woken, told to join a network, and left dialling
     * a port nothing was listening on, every few seconds, indefinitely.
     *
     * The start is marshalled onto Main because every other caller of [startWirelessServer] runs
     * there. Without that, this one arrives from `Dispatchers.IO` and can pass the "nothing is
     * assigned" check at the same moment [initWifiMode] does, and both bind. `SO_REUSEADDR` does not
     * help there - it covers a port in TIME_WAIT, not one with a live listener on it - so the loser
     * throws and spends its retry budget losing to its own sibling.
     *
     * @param reason what asked, for the log.
     * @param timeoutMs how long to wait for the bind after asking.
     */
    suspend fun ensureWirelessServerListening(reason: String, timeoutMs: Long): Boolean {
        val sharedServices = launcher.manager.sharedServices

        if (sharedServices.wirelessServer?.isListening == true)
            return true

        AppLog.i("AapService: $reason found port 5288 unbound. Trying to start the wireless server.")
        withContext(Dispatchers.Main.immediate) { sharedServices.startWirelessServer(launcher) }

        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (sharedServices.wirelessServer?.isListening == true) {
                AppLog.i("AapService: port 5288 is bound now.")
                return true
            }
            delay(250)
        }
        AppLog.w("AapService: port 5288 is still not bound ${timeoutMs}ms after trying to start it.")
        return false
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
                    val device = if (v.hasDeviceInfo()) {
                        " device=${v.deviceInfo.deviceId} lifetime=${v.deviceInfo.connectivityLifetimeId}"
                    } else ""
                    AppLog.i("NativeAA: [RX] WifiVersionResponse v${v.major}.${v.minor} status=${WppStatus.describe(if (v.hasStatus()) v.status else null)}$device")
                }
                WppMessageType.CONNECT_STATUS -> {
                    val s = Wireless.WifiConnectStatus.parseFrom(msg.payload)
                    // The hint is the phone's own words for a refusal, and the only one it sends.
                    val hint = if (s.hasErrorMessageHint()) " hint=\"${s.errorMessageHint}\"" else ""
                    AppLog.i("NativeAA: [RX] WifiConnectStatus status=${WppStatus.describe(if (s.hasStatus()) s.status else null)}$hint (SUCCESS = the phone got onto our network)")
                }
                WppMessageType.START_RESPONSE -> {
                    val r = Wireless.WifiStartResponse.parseFrom(msg.payload)
                    val port = if (r.hasPort()) ":${r.port}" else ""
                    AppLog.i("NativeAA: [RX] WifiStartResponse ip=${r.ipAddress}$port status=${WppStatus.describe(if (r.hasStatus()) r.status else null)}")
                }
            }
        } catch (e: Exception) {
            AppLog.d("NativeAA: Type ${msg.type} payload did not parse for logging: ${e.message}")
        }
    }

    private fun sendWifiStartRequest(output: OutputStream, ip: String, port: Int) {
        val request = WppMessages.startRequest(ip, port)
        sendProtobuf(output, request.toByteArray(), WppMessageType.START_REQUEST)
    }

    /**
     * Declares our protocol version and who we are, and where the phone can reach us over TCP when
     * that is safe to say. Real head units send this first, as does the OEM ZLink app; aa-proxy-rs's
     * dongle does not, which is why it sits behind [Settings.nativeWifiVersionExchange].
     *
     * [WppEndpointPolicy] holds the endpoint back on a network the phone would later fail to find,
     * which is worse than staying quiet: it stores what we advertise and dials it in preference to
     * running this handshake again. On WiFi Direct that is decided by whether this unit's group has
     * been seen to keep its name and address, which travels with the credentials.
     */
    private fun sendWifiVersionRequest(output: OutputStream, transport: NativeStrategy) {
        val endpoint = when (val decision =
            WppEndpointPolicy.decide(
                transport,
                wppTcpServer?.listeningPort,
                credentials?.identity ?: GroupIdentityStability.UNPROVEN,
            )) {
            is WppEndpointDecision.Withhold -> {
                AppLog.i("NativeAA: not advertising WPP over TCP: ${decision.reason}")
                null
            }
            is WppEndpointDecision.Advertise ->
                WppMessages.endpoint(credentials?.ip.orEmpty(), decision.port).also {
                    AppLog.i("NativeAA: advertising WPP over TCP at ${it.ip}:${it.port}")
                }
        }
        val request = WppMessages.versionRequest(carInfo(), endpoint)
        sendProtobuf(output, request.toByteArray(), WppMessageType.VERSION_REQUEST)
    }

    /**
     * Opens the WPP-over-TCP listener.
     *
     * Everything it needs is read through callbacks rather than handed over once: the credentials
     * resolve after this runs and are redelivered several times per group, so a snapshot taken here
     * would be stale by the time a phone dialled in.
     */
    private fun startWppTcpServer() {
        if (wppTcpServer != null) return
        val server = WppTcpServer(context, scope, object : WppTcpServer.Callbacks {
            override fun credentials(): NativeNetworkCredentials? = this@NativeAaHandshakeManager.credentials
                ?.let { NativeNetworkCredentials(it.ssid, it.psk, it.ip, it.bssid) }

            // The hosted transport, not the saved one: see handleHandshake().
            override fun strategy(): NativeStrategy = launcher.strategy

            override fun identity(): GroupIdentityStability =
                this@NativeAaHandshakeManager.credentials?.identity ?: GroupIdentityStability.UNPROVEN

            override fun carInfo(): Wireless.WppCarInfo = this@NativeAaHandshakeManager.carInfo()

            override fun projectionSessionUp(): Boolean = commManager.isConnected

            override fun projectionEndpoint(): Pair<String, Int>? =
                this@NativeAaHandshakeManager.credentials?.ip?.takeIf { it.isNotBlank() }?.let { it to 5288 }
        })
        wppTcpServer = server
        server.start()
    }

    /** Our identity, from the same settings ServiceDiscoveryResponse announces. */
    private fun carInfo(): Wireless.WppCarInfo = WppMessages.carInfo(
        vehicleMake = settings.vehicleMake,
        vehicleModel = settings.vehicleModel,
        vehicleYear = settings.vehicleYear,
        vehicleId = settings.vehicleId,
        headUnitMake = settings.headUnitMake,
        headUnitModel = settings.headUnitModel
    )

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
        val response = WppMessages.infoResponse(ssid, key, bssid, strategy)
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

    /**
     * A session is up, so the wake-up loop has nothing left to do.
     *
     * The loop already refuses to poke once a session exists, but it only asks at the top of each
     * iteration and an iteration is a 15 s hold plus a 15 s gap. A reporter's capture has it
     * exiting 4.1 s after the SSL handshake and 30 s is the worst case, all of it spent opening
     * RFCOMM connections into a link that is already carrying Android Auto. Each one raises an
     * OS-level ACL_CONNECTED that AutoStartReceiver reads as the user's phone arriving.
     *
     * Cheap and idempotent: safe to call on every connect, whatever the transport.
     */
    fun onSessionEstablished() {
        if (pokeJob?.isActive == true) {
            AppLog.i("NativeAA: session is up — cancelling the poke retry loop")
        }
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
    }

    fun stop() {
        isRunning = false
        standingInForHfp = false
        wppTcpServer?.stop()
        wppTcpServer = null
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
        credentials = null
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
        // Neither a handshake nor a settle can outlive the manager: leaving these set would keep
        // isAttemptInFlight() true across a restart, blocking the very poke the next start()
        // needs.
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        // Same reason, worse consequence: a poke stranded in the blocking socket.connect() outlives
        // this manager, and isAttemptInFlight() answers both arms of the Bluetooth arrival path, so
        // leaving it set makes the phone coming back do nothing at all.
        pokeAttemptInFlight = false
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
