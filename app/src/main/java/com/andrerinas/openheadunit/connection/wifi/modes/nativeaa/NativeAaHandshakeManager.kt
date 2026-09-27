package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStability
import com.andrerinas.openheadunit.connection.wifi.direct.GroupIdentityStabilityPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityRotationPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.StoredP2pIdentity
import com.andrerinas.openheadunit.aap.AapService

import com.andrerinas.openheadunit.connection.wifi.direct.WifiBandCapability
import com.andrerinas.openheadunit.utils.BluetoothAddressSeedPolicy
import com.andrerinas.openheadunit.utils.BluetoothHelper
import com.andrerinas.openheadunit.aap.protocol.proto.Wireless
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtAaCarrier
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtAttemptPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtRetransmitPolicy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt.ZbtDaemonReachability
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
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.decoder.audio.CallState
import com.andrerinas.openheadunit.decoder.audio.MicRecorder
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.utils.Settings
import java.io.DataInputStream
import java.io.OutputStream
import java.util.*
import java.util.concurrent.ConcurrentHashMap

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
        /** How often the poke loop re-asks whether a prompt on screen still holds it off. */
        private const val PROMPT_POLL_MS = 1_000L

        /** Ceiling on waiting for the AA listeners before poking anyway, and how often to ask. */
        private const val POKE_READY_WAIT_MS = 2_000L
        private const val POKE_READY_POLL_MS = 100L

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
         * Which Bluetooth route this unit takes on a flagged unit: its own radio, the external
         * module, or neither. The decision itself is pure and tested in [ExternalBtTransportPolicy];
         * this only reads the two settings it needs. Every caller asks here rather than re-deriving
         * it, because four of them drifted apart once already.
         */
        fun transportRoute(context: Context): ExternalBtTransportPolicy.Route {
            val settings = App.provide(context).settings
            return ExternalBtTransportPolicy.route(
                BluetoothHelper.externalBtEvidence,
                settings.externalBtZbtTransport,
                settings.nativeAaIgnoreExternalBt,
                // A read, never a dial. This runs on the UI path, and the dial is a socket connect.
                ZbtDaemonReachability.cached()
            )
        }

        /** What the main screen's WiFi button arms; read here so the screen and the service agree. */
        fun wifiButtonRoute(context: Context): ExternalBtTransportPolicy.WifiButton {
            val settings = App.provide(context).settings
            return ExternalBtTransportPolicy.wifiButton(
                BluetoothHelper.externalBtEvidence,
                settings.externalBtZbtTransport,
                settings.nativeAaIgnoreExternalBt,
                ZbtDaemonReachability.cached()
            )
        }

        fun checkCompatibility(context: Context): Boolean {
            when (transportRoute(context)) {
                // The module has its own listener and its own compatibility, established by the
                // daemon answering at connection time. Nothing below measures that.
                ExternalBtTransportPolicy.Route.ZBT -> {
                    AppLog.i("NativeAA: Bluetooth runs over the external module on this unit, so the RFCOMM compatibility check does not apply.")
                    return true
                }
                ExternalBtTransportPolicy.Route.BLOCKED -> {
                    externalBtDiagnostic()?.let { AppLog.w(it) }
                    return false
                }
                // Either an ordinary unit, or a flagged one whose user switched the check off.
                ExternalBtTransportPolicy.Route.NORMAL -> externalBtDiagnostic()?.let {
                    AppLog.w(it)
                    AppLog.w("NativeAA: continuing anyway, because the Bluetooth compatibility check is switched off in Settings.")
                }
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
    // wakeDecision() only refuses while a link is actually up, so a radio that has a real hands-free
    // stack and no current link passes it, and driving a link from here would compete with it.
    @Volatile private var standingInForHfp = false
    // Extra RFCOMM listeners opened on secondary Bluetooth radios (dual-Bluetooth head units).
    // Split by UUID so a successful handoff can close just the AA listeners (see
    // closeAaListeners()) without taking down the HFP ones too.
    private val extraAaServerSockets = Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    private val extraHfpServerSockets = Collections.synchronizedList(mutableListOf<BluetoothServerSocket>())
    // Read from the poke and handshake loops on Dispatchers.IO and written from start()/stop() on
    // the caller's thread, so the reads have to see the write.
    @Volatile private var isRunning = false

    /**
     * Why [start] gave up, kept for whoever asks later.
     *
     * The reason is logged once at arming time and has rotated out of a reporter's buffer long
     * before they press anything, so a poke on a manager that never started said nothing at all.
     */
    @Volatile private var notStartedReason: String? = null
    // Set by closeAaListeners() so the AA accept loops can tell "we closed this on purpose
    // after a successful handoff" apart from a real socket error, for logging only.
    @Volatile private var aaListenersClosedForSession = false
    // Set when the accept loop died under us rather than being closed. Separate from the flag above
    // because the two need opposite repairs and the manager is still running through both.
    @Volatile private var aaListenerLost = false
    private var aaReopenJob: Job? = null
    private var aaReopenAttempts = 0
    // The phone ended the last session itself, so it is listened for rather than woken. Lifted by
    // the phone coming back, a manual poke, or a start(), never by time.
    @Volatile private var wakeStoodDown = false
    // When the last session ended, so the first poke after it can let the phone's WiFi settle.
    @Volatile private var sessionEndedAt = 0L

    /**
     * The radio coming back is the event a lost listener is really waiting for, and nothing else in
     * the app watches for it.
     */
    private val btStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON) return
            if (!isRunning || !aaListenerLost) return
            aaReopenAttempts = 0
            val adapter = BluetoothHelper.getBluetoothAdapter(this@NativeAaHandshakeManager.context)
            if (adapter != null) reopenAaListener(adapter, "now this unit's Bluetooth is back on")
        }
    }
    private var btStateReceiverRegistered = false
    // Which device the "already have a hands-free link, not poking" line was last said about at info
    // level. Kept per device, not per run: a switch stands the incoming phone down while the line on
    // screen names the outgoing one, and one info line for the wrong phone hid that for a whole round.
    @Volatile private var handsFreeSkipLoggedFor: String? = null
    // When the hands-free stand-down for each device began, so HandsFreeWakeEscalationPolicy can
    // tell a phone that is merely slow from one whose radio will never raise the Bluetooth event
    // Android Auto re-triggers on. Cleared the moment a device stops being stood down.
    private val handsFreeStandDownSince = ConcurrentHashMap<String, Long>()
    @Volatile private var escalatedWakes = 0
    @Volatile private var lastEscalatedWakeAt = 0L
    @Volatile private var escalationSpentLoggedFor: String? = null
    // Until when each device is left alone after an escalated wake took its hands-free slot. The
    // ordinary loop would otherwise re-take it every pass, which is why one wake cost five minutes
    // of link rather than one: the link never got an uninterrupted window to come back.
    private val escalationProbeUntil = ConcurrentHashMap<String, Long>()
    @Volatile private var wakeProbeJob: Job? = null
    // The radio cycle that replaces the wake below API 33, and its own probe. Budgeted per arming
    // like the wake, because it costs every Bluetooth device on the unit a few seconds.
    @Volatile private var radioCycles = 0
    @Volatile private var lastRadioCycleAt = 0L
    @Volatile private var radioCycleJob: Job? = null
    // Until when each device is left alone after a cycle, so the ordinary loop does not poke away
    // the very link the cycle handed back 15 s later.
    private val radioCycleQuietUntil = ConcurrentHashMap<String, Long>()
    // Whether any poke pass this arming was stood down over a hands-free link. Only those armings
    // count towards re-measuring a DESTRUCTIVE verdict; one the phone never reached says nothing.
    @Volatile private var standDownReachedThisArming = false
    // The chosen wake targets last reported as not phones, so the line prints on a change only.
    @Volatile private var droppedPokeTargetsLogged: Set<String>? = null
    // When this manager last closed a socket to each phone, so a Bluetooth loss that follows one
    // can be told apart from the phone leaving. See BtAutoDisconnectPolicy.
    private val ownSocketCloseAt = ConcurrentHashMap<String, Long>()

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

    /** Whether the running wake loop was started with no credentials, by the early wake. */
    private var pokeLoopStartedEmpty = false

    /**
     * Set once an early wake brought the phone back to a head unit with no network to hand it.
     * Cleared when credentials arrive, so the ordinary credential-driven wake still runs.
     */
    @Volatile
    private var earlyWakeSpent = false
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
    // The address a poke is inside socket.connect() for, or null when none is. Narrower than
    // pokeAttemptInFlight, which stays true for the whole hold as well - see PokeOverlapPolicy.
    @Volatile private var pokeConnectingTo: String? = null
    // elapsedRealtime() when the last WifiInfoResponse (Type 3) went out, or 0 when no handoff is
    // settling. The phone spends the next several seconds associating, doing WPS and getting a
    // DHCP lease; see isHandoffSettling() and NativeHandoffPolicy.
    @Volatile private var handoffSettlingSince = 0L
    // The link of the handshake currently being served. Kept so a phone that gives up and
    // reconnects during a settle supersedes the stale one instead of running a second
    // handleHandshake() alongside it.
    @Volatile private var activeHandshakeLink: HandshakeLink? = null
    // The external-Bluetooth-module transport, when that is the route this unit takes. Non-null
    // only between start() and stop() on that route; it replaces the RFCOMM listeners entirely
    // rather than running beside them.
    @Volatile private var zbtCarrier: ZbtAaCarrier? = null
    // The coroutine serving [activeHandshakeLink]. Closing a superseded handshake's link only
    // ends it on stacks where close() interrupts a pending read; some do not, and it runs on for
    // minutes. Cancelling cannot break a blocking JNI read either, but it does end every real
    // suspension point in the handshake. Do both; whichever the stack honours wins.
    @Volatile private var activeHandshakeJob: Job? = null
    // Name of the primary Bluetooth radio we listen and poke on, captured in start(). A field
    // rather than a local so the diagnostic below can name the radio the phone is ignoring.
    @Volatile private var localRadioName: String = "?"
    // [BUG_FIX] Wake pokes answered since the phone last landed a session, and whether it ever
    // opened the AA channel at all. "Poke succeeds, nothing comes back" makes a broken unit's log
    // identical to a healthy one waiting for the user, while the phone is in fact talking to the
    // unit's own OEM Bluetooth module. See NativeHandoffPolicy.shouldWarnPhoneNeverCallsBack.
    @Volatile private var pokesSinceLastAccept = 0
    @Volatile private var everAcceptedAaConnection = false
    // The same fact scoped to this arming. everAcceptedAaConnection answers "has this pairing ever
    // worked here" and so is never reset; the join watchdog needs "has the phone dialled since we
    // armed", or a reconnect attempt recreates groups for a phone that has not asked for one.
    @Volatile private var acceptedAaConnectionThisArming = false
    // [BUG_FIX] Handshakes that timed out waiting for Type 2, back to back. Where close() does not
    // interrupt a pending read each one strands a Dispatchers.IO thread forever, so this bounds
    // how many we are willing to strand. See NativeHandoffPolicy.shouldServeHandshake.
    @Volatile private var consecutiveHandshakeFailures = 0
    // Whether the "not serving handshakes" warning has already been logged for the current
    // backoff, so a phone retrying every ~12 s does not repeat the long explanation each time.
    @Volatile private var loggedHandshakeBackoff = false
    // Handshakes the phone answered in full and then reported it could not join. Invisible to
    // consecutiveHandshakeFailures, which only counts the ones the phone was silent through.
    // See JoinRefusalPolicy.
    @Volatile private var consecutiveJoinRefusals = 0
    // When the last of those landed, so the widened gap can be waited by whichever wake round is
    // alive rather than only by the loop that earned it.
    @Volatile private var lastJoinRefusalAtMs = 0L

    /** Whether the driver selection UI prompt is currently presented to the user. */
    @Volatile var isSelectionPromptActive: Boolean = false
        private set
    /** When the prompt went up, so one nobody answers cannot hold the wake poke off for good. */
    @Volatile private var selectionPromptShownAt = 0L
    /** Whether the poke loop has already said it is holding for this prompt. */
    @Volatile private var promptHoldLogged = false
    /** Whether the driver selection UI was explicitly canceled by the user (stops the poke). */
    @Volatile var isSelectionCanceled: Boolean = false
        private set
    /** When the user cancelled, so the refusal window can expire. */
    @Volatile private var selectionCanceledAt = 0L
    /** Whether a poke aimed at one chosen phone owns the poke slot. */
    @Volatile private var manualPokeInFlight = false
    /** When a target device is selected, only this MAC is allowed to proceed. */
    @Volatile var pendingSelectionTargetMac: String? = null
        private set
    /** When that choice was made, so its exclusive window can expire. */
    @Volatile private var selectionTargetSetAt = 0L
    /** The phone a driver switch moved away from, refused until somebody is chosen. */
    @Volatile private var switchedAwayFromMac: String? = null
    /** When that switch was asked for, so its refusal window can expire. */
    @Volatile private var driverSwitchStartedAt = 0L
    /** Refusals since the accept gate last let somebody in, and who the last one was. */
    @Volatile private var gateRefusalCount = 0
    @Volatile private var lastRefusedMac: String? = null
    @Volatile private var refusalSpokenAt = 0L

    /**
     * Targets a specific driver device, ending the prompt window and waking only that device.
     */
    fun selectDriver(mac: String) {
        AppLog.i("NativeAA: Driver selected: $mac")
        clearSelectionPrompt()
        isSelectionCanceled = false
        selectionCanceledAt = 0L
        pendingSelectionTargetMac = mac
        selectionTargetSetAt = SystemClock.elapsedRealtime()
        // The switch-away stamps deliberately survive the pick. Choosing who to let in is not the
        // same question as whether the phone the driver just left may come straight back.
        clearGateRefusals()
        manualPoke(mac)
    }

    /**
     * The user asked to hand the session to a different phone.
     *
     * [previousMac] is the one projecting now. Ending the session reopens the Android Auto
     * listeners, so without this it simply reconnects before the driver has picked anybody.
     */
    fun beginDriverSwitch(previousMac: String) {
        if (previousMac.isEmpty()) return
        AppLog.i("NativeAA: a driver switch is starting, so $previousMac is not let straight back in.")
        switchedAwayFromMac = previousMac
        driverSwitchStartedAt = SystemClock.elapsedRealtime()
        pendingSelectionTargetMac = null
        selectionTargetSetAt = 0L
        clearGateRefusals()
    }

    /**
     * The driver prompt is on screen. Holds the automated poke off, but only for a bounded window:
     * see [NativeDriverSelectionPolicy.promptDeferralMs].
     */
    fun onSelectionPromptShown() {
        isSelectionPromptActive = true
        selectionPromptShownAt = SystemClock.elapsedRealtime()
        isSelectionCanceled = false
        selectionCanceledAt = 0L
        promptHoldLogged = false
    }

    /**
     * The prompt left the screen without a choice. Backgrounding the app dismisses the dialog
     * without cancelling it, and that used to leave the accept gate shut on every phone.
     */
    fun onSelectionPromptDismissed() {
        if (!isSelectionPromptActive) return
        AppLog.i("NativeAA: the driver prompt is gone without a choice — the accept gate is open again.")
        clearSelectionPrompt()
    }

    /**
     * A phone arriving over Bluetooth is a driver asking for a session, so a cancel from an earlier
     * prompt does not outlive it.
     */
    fun clearSelectionCancel() {
        if (!isSelectionCanceled) return
        AppLog.i("NativeAA: a phone arrived over Bluetooth — the cancelled prompt no longer stands.")
        isSelectionCanceled = false
        selectionCanceledAt = 0L
    }

    private fun clearSelectionPrompt() {
        isSelectionPromptActive = false
        selectionPromptShownAt = 0L
    }

    /** Every driver-selection flag, back to how start() found them. */
    private fun resetSelectionState() {
        clearSelectionPrompt()
        isSelectionCanceled = false
        selectionCanceledAt = 0L
        pendingSelectionTargetMac = null
        selectionTargetSetAt = 0L
        clearDriverSwitch()
        clearGateRefusals()
    }

    /** The switch is over, however it ended. */
    private fun clearDriverSwitch() {
        switchedAwayFromMac = null
        driverSwitchStartedAt = 0L
    }

    /**
     * Refuse a phone at the accept gate: the first attempt of each is logged, the rest counted and
     * restated once a minute.
     *
     * A refused phone retries RFCOMM every 150 ms or so, which wrote 602 identical lines into one
     * 2.5 minute window on the rig.
     */
    private fun refuseAtGate(remoteAddress: String, message: String): Boolean {
        gateRefusalCount++
        val now = SystemClock.elapsedRealtime()
        if (!remoteAddress.equals(lastRefusedMac, ignoreCase = true)) {
            lastRefusedMac = remoteAddress
            refusalSpokenAt = now
            AppLog.i(message)
        } else if (NativeDriverSelectionPolicy.shouldRestateRefusal(now - refusalSpokenAt)) {
            refusalSpokenAt = now
            AppLog.i("NativeAA: $remoteAddress has been turned away $gateRefusalCount times since the gate closed.")
        }
        return false
    }

    /** The gate is open again. Say how many attempts it turned away first. */
    private fun clearGateRefusals(logSummary: Boolean = false) {
        if (logSummary && gateRefusalCount > 1) {
            AppLog.i("NativeAA: turned away $gateRefusalCount connection attempts before this one.")
        }
        gateRefusalCount = 0
        lastRefusedMac = null
        refusalSpokenAt = 0L
    }

    /** Whether an unanswered prompt has held the poke off for as long as it is allowed to. */
    private fun selectionPromptExpired(now: Long): Boolean =
        selectionPromptShownAt != 0L &&
            now - selectionPromptShownAt >=
            NativeDriverSelectionPolicy.promptDeferralMs(settings.nativeDriverSelectionTimeoutSec)

    /**
     * Cancels any active poke because the user explicitly cancelled the prompt.
     *
     * Cancel means "stop waking me", and it stops the poke for as long as the flag stands. It does
     * not deafen the unit: the refusal window in [shouldAcceptHandshake] is one poke cycle, so a
     * phone dialling us after that is accepted.
     */
    fun cancelPoke() {
        AppLog.i("NativeAA: cancelPoke() called — user explicitly canceled driver selection.")
        clearSelectionPrompt()
        isSelectionCanceled = true
        selectionCanceledAt = SystemClock.elapsedRealtime()
        pendingSelectionTargetMac = null
        selectionTargetSetAt = 0L
        clearDriverSwitch()
        clearGateRefusals()
        pokeJob?.cancel()
        pokeJob = null
    }

    /**
     * Determines whether an incoming Bluetooth RFCOMM connection from a phone should be accepted.
     */
    fun shouldAcceptHandshake(remoteAddress: String): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (isSelectionCanceled) {
            if (now - selectionCanceledAt < NativeDriverSelectionPolicy.CANCEL_REFUSAL_MS) {
                return refuseAtGate(
                    remoteAddress,
                    "NativeAA: User explicitly canceled driver selection — refusing connection from $remoteAddress"
                )
            }
            // A phone opening the Android Auto UUID this long after the cancel is the driver asking
            // for a session on the phone, not our poke arriving late.
            AppLog.i("NativeAA: the cancelled prompt has expired — accepting $remoteAddress.")
            isSelectionCanceled = false
            selectionCanceledAt = 0L
        }
        if (isSelectionPromptActive) {
            if (selectionPromptExpired(now)) {
                // Falls through rather than returning: an expired prompt used to skip the switch
                // gate, so the phone a switch moved away from could come back through it.
                AppLog.i("NativeAA: the driver prompt has been unanswered too long — accepting $remoteAddress.")
                clearSelectionPrompt()
            } else {
                val target = pendingSelectionTargetMac
                if (target == null || (remoteAddress.isNotEmpty() && !remoteAddress.equals(target, ignoreCase = true))) {
                    return refuseAtGate(
                        remoteAddress,
                        "NativeAA: Selection prompt active (target=$target) — refusing connection from $remoteAddress"
                    )
                }
            }
        }
        // A handshake in flight during the window can only be the chosen phone's: this gate runs
        // before handleHandshake is ever launched.
        val chosenWakeActive = manualPokeInFlight || isHandshakeInFlight() || isHandoffSettling()
        when (NativeDriverSelectionPolicy.switchGate(
            remoteMac = remoteAddress,
            chosenMac = pendingSelectionTargetMac,
            chosenAgeMs = now - selectionTargetSetAt,
            switchedAwayFrom = switchedAwayFromMac,
            switchAgeMs = now - driverSwitchStartedAt,
            chosenWakeActive = chosenWakeActive
        )) {
            NativeDriverSelectionPolicy.SwitchGate.WRONG_PHONE -> return refuseAtGate(
                remoteAddress,
                "NativeAA: the driver chose $pendingSelectionTargetMac, so $remoteAddress waits until that phone has had its turn."
            )
            NativeDriverSelectionPolicy.SwitchGate.SWITCHED_AWAY -> return refuseAtGate(
                remoteAddress,
                "NativeAA: $remoteAddress is the phone this switch moved away from, so it is not let back in yet."
            )
            NativeDriverSelectionPolicy.SwitchGate.ACCEPT -> clearGateRefusals(logSummary = true)
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
        earlyWakeSpent = false
    }

    /** Clears cached credentials so an in-progress wait doesn't hand out stale ones for a group
     *  that's about to be torn down. */
    fun invalidateCredentials() {
        credentials = null
        credentialsWithdrawals.incrementAndGet()
    }

    /** Counts groups taken down, so a handshake can tell the network it sent has since gone. */
    private val credentialsWithdrawals = java.util.concurrent.atomic.AtomicInteger()

    // isRunning alone isn't enough once closeAaListeners() can close the AA_UUID listener while
    // leaving the manager otherwise running (HFP stays up) — callers like AutoStartReceiver's
    // BT-reconnect re-arm need to know whether a connection can actually be accepted right now,
    // not just whether the manager was start()ed. See the "Re-arm on Bluetooth reconnect" fix
    // this restores the invariant for: isActive() must mean "genuinely able to accept," not
    // "believed to be running."
    fun isActive(): Boolean = isRunning && !aaListenersClosedForSession && !aaListenerLost

    /**
     * Whether the handshake servers were ever brought up, as opposed to [isActive]'s "can accept a
     * connection right now". The two answers need different repairs: a closed listener is reopened
     * by [rearmForNextSession], and only [start] can help one that was never opened.
     */
    fun isStarted(): Boolean = isRunning

    /** Why a poke-path wait gave up at once, in the words of the state it actually found. */
    private fun pokeNotReadyReason(): String? = when {
        !isRunning -> "the handshake servers are not running" + (notStartedReason?.let { " ($it)" } ?: "")
        aaListenersClosedForSession -> "the Android Auto listeners are closed after the last session"
        aaListenerLost -> "the Android Auto listener was lost when this unit's Bluetooth went away"
        else -> null
    }

    /** Why [start] gave up, for a caller that found [isStarted] false and has to say something useful. */
    fun notStartedReason(): String? = notStartedReason

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
     * Whether the phone has ever opened the Android Auto channel on this unit, for the whole
     * process. Kept across a re-arm on purpose, so this answers "has this pairing ever worked
     * here", not "has it worked since the last mode change".
     */
    fun hasPhoneEverOpenedAaChannel(): Boolean = everAcceptedAaConnection

    /**
     * Whether the phone has opened the Android Auto channel since this manager was armed. What the
     * join watchdog asks: a group recreate repairs a phone that took credentials and could not join,
     * and on a fresh arming a phone that has not dialled has taken none, whatever it did last time.
     */
    fun hasPhoneOpenedAaChannelThisArming(): Boolean = acceptedAaConnectionThisArming

    /** Wake pokes sent since the phone last landed a session. Reset by a session, not by an RFCOMM
     *  accept: a phone that dials and never joins is exactly what the stale-group watchdog wants. */
    fun unansweredPokeCount(): Int = pokesSinceLastAccept

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
        // None of these survived a mode rebuild by design, and nothing else clears them: a cancel
        // from the last arming would otherwise refuse every phone this one accepts.
        resetSelectionState()
        // A fresh arming answers to nobody's last session: the mode is being brought up again.
        wakeStoodDown = false
        sessionEndedAt = 0L
        noteWakeDamageVerdict()

        // Ahead of every Bluetooth check below, because this listener does not need Bluetooth. A
        // unit whose adapter the phone cannot reach returns early from all of them, and that is
        // exactly the unit for which TCP is the only route left. isRunning stays the answer to
        // "are the RFCOMM listeners up", which is what isActive() callers are asking; stop() takes
        // this down either way.
        startWppTcpServer()

        // Ask the daemon before deciding, on the units where the answer can change the route.
        // Off the main thread and once per process, then straight back in here with an answer.
        if (ExternalBtTransportPolicy.needsDaemonMeasurement(
                BluetoothHelper.externalBtEvidence,
                settings.externalBtZbtTransport,
                settings.nativeAaIgnoreExternalBt,
                ZbtDaemonReachability.cached()
            )
        ) {
            notStartedReason = "the vendor Bluetooth daemon is still being asked whether it will carry Android Auto."
            AppLog.i("NativeAA: this unit's Bluetooth is an external module; asking the vendor daemon whether it will carry Android Auto before choosing a route.")
            scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ZbtReachability")) {
                ZbtDaemonReachability.resolve()
                withContext(Dispatchers.Main.immediate) { start() }
            }
            return
        }

        // Leave isRunning false, like the "adapter disabled" case below: isActive() callers must
        // see this as genuinely stopped. Nothing here is retryable, but a listener that was never
        // opened must not be reported as up.
        when (transportRoute(context)) {
            // The module carries the handshake instead, over its own channel. None of the RFCOMM
            // setup below applies to it.
            ExternalBtTransportPolicy.Route.ZBT -> {
                startOverExternalModule()
                return
            }
            ExternalBtTransportPolicy.Route.BLOCKED -> {
                externalBtDiagnostic()?.let { AppLog.e(it) }
                notStartedReason = "this unit's Bluetooth is an external module with no route through it " +
                    "(${BluetoothHelper.externalBtEvidence}). Turn on \"Connect through the head unit's " +
                    "Bluetooth module\", or use USB or a WiFi mode."
                return
            }
            // Either an ordinary unit, or a flagged one whose user switched the check off.
            ExternalBtTransportPolicy.Route.NORMAL -> externalBtDiagnostic()?.let {
                AppLog.w("$it\nNativeAA: starting anyway, because the Bluetooth compatibility check is switched off in Settings.")
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                notStartedReason = "the app does not have the Bluetooth permission it needs."
                AppLog.e("NativeAA: Missing BLUETOOTH_CONNECT permission. Handshake server cannot start.")
                notStartedReason = "the app has no BLUETOOTH_CONNECT permission"
                return
            }
        }

        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            // Leave isRunning false — isActive() callers (e.g. AapService's BT auto-start
            // re-arm check) need to see this as genuinely stopped so they retry later,
            // instead of believing the listener sockets are up when nothing was ever opened.
            notStartedReason = "this unit's Bluetooth is switched off or unavailable."
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled")
            notStartedReason = "this unit's Bluetooth was off or unavailable when the mode was armed"
            return
        }

        isRunning = true
        notStartedReason = null
        earlyWakeSpent = false
        aaListenersClosedForSession = false
        aaListenerLost = false
        aaReopenAttempts = 0
        registerBtStateReceiver()
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
            val server = openHfpRecord(adapter, localRadioName)
            if (server == null) {
                if (isRunning) ConnectionIssues.raiseOnce(context, ConnectionIssue.HANDS_FREE_RECORD_REFUSED)
                return@launch
            }
            ConnectionIssues.clear(context, ConnectionIssue.HANDS_FREE_RECORD_REFUSED)
            hfpServerSocket = server
            try {
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
                    // A stood-down wake has nothing to show: the pill would claim an attempt that
                    // is only a socket waiting. The phone's own return below puts it back up.
                    if (!wakeStoodDown) ConnectionStageTracker.report(ConnectionStage.WAITING_FOR_PHONE)
                } else {
                    extraAaServerSockets.add(server)
                    AppLog.i("NativeAA: ACTIVELY LISTENING on Android Auto UUID on secondary radio '$serviceName' [$radioName]")
                }
                while (isRunning && isActive) {
                    val socket = server.accept()
                    if (socket != null) {
                        val remoteAddress = try { socket.remoteDevice.address } catch (_: Exception) { "" }
                        // A phone the gate is already turning away retries every 150 ms or so. Its
                        // first attempt is announced; refuseAtGate counts and restates the repeats.
                        if (!remoteAddress.equals(lastRefusedMac, ignoreCase = true)) {
                            if (serviceName == null) {
                                AppLog.i("NativeAA: Connection accepted from ${socket.remoteDevice.name} ($remoteAddress) on local radio [$radioName]")
                            } else {
                                AppLog.i("NativeAA: Connection accepted (secondary radio '$serviceName' [$radioName]) from ${socket.remoteDevice.name} ($remoteAddress)")
                            }
                        }
                        if (!shouldAcceptHandshake(remoteAddress)) {
                            closePhoneSocket(socket)
                            continue
                        }
                        val link = BluetoothSocketLink(socket, radioName)
                        if (refuseWhileBackedOff(link)) continue
                        // After the gate, not at the log line above: a refused connection is not
                        // the phone answering.
                        wakeStoodDown = false
                        ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                        // [FIX] Launch handshake in a separate coroutine so the server can accept the next connection!
                        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Handshake-${socket.remoteDevice.address}")) {
                            handleHandshake(link)
                        }
                    }
                }
            } catch (e: Exception) {
                when (AaListenerRecoveryPolicy.exitKind(aaListenersClosedForSession, isRunning)) {
                    AaListenerRecoveryPolicy.Exit.HANDOFF ->
                        AppLog.i("NativeAA: $label closed after successful handoff$suffix.")
                    AaListenerRecoveryPolicy.Exit.STOPPED ->
                        AppLog.d("NativeAA: $label closed cleanly$suffix.")
                    AaListenerRecoveryPolicy.Exit.FAILED -> {
                        AppLog.e("NativeAA: $label error$suffix: ${e.message}", e)
                        if (serviceName == null) onAaListenerLost()
                    }
                }
            }
        }
    }

    private fun registerBtStateReceiver() {
        if (btStateReceiverRegistered) return
        try {
            ContextCompat.registerReceiver(
                context,
                btStateReceiver,
                IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            btStateReceiverRegistered = true
        } catch (e: Exception) {
            AppLog.w("NativeAA: could not watch this unit's Bluetooth state: ${e.message}")
        }
    }

    private fun unregisterBtStateReceiver() {
        if (!btStateReceiverRegistered) return
        btStateReceiverRegistered = false
        try { context.unregisterReceiver(btStateReceiver) } catch (e: Exception) {}
    }

    /**
     * The primary Android Auto listener died with the manager still running, so nothing can answer
     * the phone until it is reopened. Says so, then reopens it as soon as there is a radio to
     * reopen it on: the head unit's own Bluetooth bouncing is routine on these units.
     */
    private fun onAaListenerLost() {
        if (aaListenerLost) return
        aaListenerLost = true
        aaServerSocket = null
        AppLog.w(
            "NativeAA: the Android Auto listener is down, so nothing can answer the phone. " +
                "Reopening it as soon as this unit's Bluetooth is back."
        )
        scheduleAaListenerReopen()
    }

    /** Reopens a lost listener, bounded, and gives up to the radio's own return once spent. */
    private fun scheduleAaListenerReopen() {
        if (aaReopenJob?.isActive == true) return
        aaReopenJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ListenerReopen")) {
            while (isRunning && isActive && aaListenerLost) {
                delay(AaListenerRecoveryPolicy.REOPEN_DELAY_MS)
                val adapter = BluetoothHelper.getBluetoothAdapter(context)
                val enabled = try { adapter != null && adapter.isEnabled } catch (e: Exception) { false }
                if (!AaListenerRecoveryPolicy.mayReopen(enabled, aaReopenAttempts)) {
                    if (!enabled) continue
                    AppLog.w(
                        "NativeAA: the Android Auto listener would not reopen after " +
                            "$aaReopenAttempts attempts; waiting for this unit's Bluetooth to come back."
                    )
                    return@launch
                }
                aaReopenAttempts++
                if (reopenAaListener(adapter!!, "after the listener was lost")) return@launch
            }
        }
    }

    /** Opens the primary Android Auto listener again. True when it is serving. */
    @SuppressLint("MissingPermission")
    private fun reopenAaListener(adapter: BluetoothAdapter, why: String): Boolean {
        if (!isRunning || !aaListenerLost) return true
        aaListenerLost = false
        AppLog.i("NativeAA: reopening the Android Auto listener $why.")
        launchAaAcceptLoop(adapter, localRadioName)
        secondaryRadioHandles().forEach {
            val radioName = try { it.adapter.name ?: "?" } catch (e: Exception) { "?" }
            launchAaAcceptLoop(it.adapter, radioName, it.serviceName)
        }
        return true
    }

    /**
     * A session just ended, and whether the phone is to be woken for the next one.
     *
     * Separate from [rearmForNextSession], which also runs for a manual poke and from inside the
     * poke loop itself: neither of those is a session ending and neither may move this.
     */
    fun noteSessionEnded(wakePhone: Boolean) {
        wakeStoodDown = !wakePhone
        sessionEndedAt = SystemClock.elapsedRealtime()
        if (!wakePhone) {
            AppLog.i("NativeAA: the phone ended the session itself, so the listeners reopen without waking it.")
        }
    }

    /** Whether an automatic poke may run, or the phone is only listened for. */
    fun wakesPhone(): Boolean = !wakeStoodDown

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
        if (!isRunning) {
            // The one line whose absence made a reporter's capture unreadable: the caller has
            // already said it is reopening the listeners, and nothing here can.
            AppLog.w(
                "NativeAA: the Android Auto listeners cannot be reopened because the handshake " +
                    "servers are not running" + (notStartedReason?.let { " ($it)" } ?: "") + "."
            )
            return
        }
        // The session that just ended answered the question the prompt asks. The chosen target is
        // left alone: the switch-driver path sets it from a coroutine that races this one.
        clearSelectionPrompt()
        isSelectionCanceled = false
        selectionCanceledAt = 0L
        // A handshake cannot outlive the session it set up, and its stamps are what hold the wake
        // poke off and defer the join watchdog. Nothing else clears them once the socket is gone.
        activeHandshakeJob?.cancel()
        activeHandshakeJob = null
        activeHandshakeLink = null
        handshakeStartedAt = 0L
        handoffSettlingSince = 0L
        resetHandshakeBackoff()

        if (!SessionEndGroupPolicy.shouldReopenAaListeners(isRunning, aaListenersClosedForSession)) {
            if (!aaListenerLost) {
                AppLog.i("NativeAA: the Android Auto listeners are still open, so the phone can come straight back.")
                return
            }
            // The listener died under us rather than being closed, so "still open" was a lie for as
            // long as this state had nowhere to be recorded.
        }
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        if (adapter == null || !adapter.isEnabled) {
            AppLog.e("NativeAA: Bluetooth adapter not available or disabled, so the Android Auto listeners stay closed.")
            return
        }
        // Cleared before the launch: the loop reads it to tell a handoff's close from a failure.
        aaListenersClosedForSession = false
        aaListenerLost = false
        aaReopenAttempts = 0
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
            val server = openHfpRecord(extra, "'$serviceName' $radioName") ?: return@launch
            try {
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
     * Ask [adapter] for the stand-in Hands-Free record, giving a refusal a few more tries.
     *
     * One unit refused it 20 ms after the Android Auto record registered, in every capture it ever
     * sent, and then had no hands-free profile for Android Auto to see. Null means this radio will
     * not publish one at all, which is the caller's to report.
     */
    private suspend fun openHfpRecord(adapter: BluetoothAdapter, radio: String): BluetoothServerSocket? {
        var attempt = 1
        while (isRunning) {
            try {
                return adapter.listenUsingRfcommWithServiceRecord("Hands-Free Unit", HFP_UUID)
            } catch (e: Exception) {
                if (!isRunning) return null
                if (!HfpServiceRecordPolicy.registrationRefused(attempt)) {
                    AppLog.w("NativeAA: radio [$radio] refused the stand-in Hands-Free record (${e.message}); asking again in ${HfpServiceRecordPolicy.REGISTRATION_RETRY_GAP_MS}ms.")
                    delay(HfpServiceRecordPolicy.REGISTRATION_RETRY_GAP_MS)
                    attempt++
                } else {
                    AppLog.e("NativeAA: radio [$radio] would not publish a Hands-Free record in $attempt attempts (${e.message}). Android Auto will not start wireless setup against a head unit with no hands-free profile.", e)
                    return null
                }
            }
        }
        return null
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
        closeWhenDone: Boolean,
        // How far the walk got, for a caller that has to report a hold nothing answered. The socket
        // outlives this coroutine's cancellation, so the stage cannot be returned.
        onStage: ((HfpSlcInitiator.Stage) -> Unit)? = null
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
                    onStage?.invoke(stage)
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
                        onStage?.invoke(stage)
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
                closePhoneSocket(socket)
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

    /** Closes a socket to a phone and records that the close was ours. */
    private fun closePhoneSocket(socket: BluetoothSocket) {
        val mac = try { socket.remoteDevice?.address } catch (_: Exception) { null }
        if (!mac.isNullOrEmpty()) ownSocketCloseAt[mac.uppercase()] = SystemClock.elapsedRealtime()
        try { socket.close() } catch (_: Exception) {}
    }

    /** Closes a handshake link to a phone and records that the close was ours. */
    private fun closePhoneLink(link: HandshakeLink) {
        link.peerAddress?.takeIf { it.isNotEmpty() }?.let { ownSocketCloseAt[it.uppercase()] = SystemClock.elapsedRealtime() }
        try { link.close() } catch (_: Exception) {}
    }

    /** How long ago this manager last closed a socket to [mac], or null if it never has. */
    fun msSinceOwnSocketClose(mac: String): Long? =
        ownSocketCloseAt[mac.uppercase()]?.let { SystemClock.elapsedRealtime() - it }

    private fun classifyChosen(device: BluetoothDevice): DriverCandidatePolicy.Verdict =
        BluetoothHelper.classifyDevice(
            device, BluetoothHelper.pinFor(device, settings.nativePreferredDeviceMac, settings.lastConnectedNativeMac)
        ).verdict

    /** The chosen addresses that are not phones, read the way the poke loop reads them. */
    private fun notPhonesAmong(macs: Set<String>): Set<String> {
        val adapter = try { BluetoothHelper.getBluetoothAdapter(context) } catch (_: Exception) { null } ?: return emptySet()
        return macs.filterTo(mutableSetOf()) { mac ->
            try { classifyChosen(adapter.getRemoteDevice(mac)) == DriverCandidatePolicy.Verdict.NOT_A_PHONE }
            catch (_: Exception) { false }
        }
    }

    /** Say which chosen targets are skipped as not phones, once per composition, then at debug. */
    private fun noteDroppedPokeTargets(dropped: Set<String>, chosen: Map<String, BluetoothDevice>) {
        if (dropped.isEmpty()) return
        val named = dropped.joinToString { mac -> "${chosen[mac]?.name ?: "unnamed"} ($mac)" }
        val message = "NativeAA: not poking $named: chosen for Auto Start but not a phone, and a car " +
            "or headset cannot start Android Auto. Every paired phone is poked instead when that is on."
        if (dropped != droppedPokeTargetsLogged) {
            droppedPokeTargetsLogged = dropped
            AppLog.i(message)
        } else {
            AppLog.d(message)
        }
    }

    /**
     * Say once per device that the poke stood down. Info first so it survives a log exported at the
     * default level, then debug: the retry loop asks again every ~30 s, and a line per half-minute
     * for a whole session buries everything around it.
     */
    private fun noteHandsFreePokeSkip(device: BluetoothDevice, reason: BluetoothWakePolicy.WakeReason) {
        val why = if (reason == BluetoothWakePolicy.WakeReason.TARGET_UNREADABLE) {
            "this head unit holds a Bluetooth hands-free link and could not read whether it is this " +
                "phone's, so a poke might take over somebody else's call."
        } else {
            "this head unit already holds a Bluetooth hands-free link to it, which a poke would take " +
                "over and leave disconnected. Waiting for the phone to start Android Auto itself."
        }
        val message = "NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — $why"
        if (!device.address.equals(handsFreeSkipLoggedFor, ignoreCase = true)) {
            handsFreeSkipLoggedFor = device.address
            AppLog.i(message)
        } else {
            AppLog.d(message)
        }
    }

    /**
     * Whether to wake a phone the hands-free guard stood down, and say so when it happens.
     *
     * Android Auto re-triggers on a Bluetooth connection event, not on a connected state, so a radio
     * that holds hands-free across a session and past its end never gives it one. The poke is what
     * raises that event, at the cost of the phone's hands-free slot.
     */
    private fun escalateHandsFreeWake(
        device: BluetoothDevice,
        reason: BluetoothWakePolicy.WakeReason,
        standDownSince: Long,
        now: Long,
    ): Boolean {
        val verdict = NativeAaWakeDamagePolicy.Verdict.of(settings.nativeAaWakeDamageVerdict)
        val escalate = HandsFreeWakeEscalationPolicy.shouldEscalate(
            unitAllowsWake = NativeAaWakeDamagePolicy.allowsEscalation(
                verdict, settings.nativeAaWakeArmingsWithoutSession
            ),
            reason = reason,
            // Not everAcceptedAaConnection alone: it dies with the process, and a radio that
            // auto-connects hands-free at boot stands every poke down before anything can set it.
            phoneEverOpenedAaChannel = HandsFreeWakeEscalationPolicy.pairingHasRunAaHere(
                acceptedThisProcess = everAcceptedAaConnection,
                targetMac = device.address,
                lastConnectedNativeMac = settings.lastConnectedNativeMac,
            ),
            sessionInProgress = commManager.isConnected || isHandshakeInFlight() || isHandoffSettling(),
            standDownSinceMs = standDownSince,
            lastEscalationMs = lastEscalatedWakeAt,
            escalationsUsed = escalatedWakes,
            now = now,
        )
        if (!escalate) return false
        escalatedWakes++
        lastEscalatedWakeAt = now
        AppLog.i(
            "NativeAA: waking ${device.name ?: "unnamed"} (${device.address}) despite the hands-free " +
                "link — it has not started Android Auto in ${(now - standDownSince) / 1000}s, and a " +
                "link that never changes raises no event for the phone to notice. Wake " +
                "$escalatedWakes of ${HandsFreeWakeEscalationPolicy.MAX_ESCALATED_WAKES}; " +
                "the link is left alone for " +
                "${NativeAaWakeDamagePolicy.PROBE_WINDOW_MS / 1000}s to come back."
        )
        armWakeProbe(device, verdict)
        return true
    }

    /**
     * Whether the radio cycle may take this stand-down instead of the wake. Reads the three live
     * facts the policy cannot: the release, a call on the link this would drop, and a session.
     */
    private fun allowsRadioCycle(
        device: BluetoothDevice,
        reason: BluetoothWakePolicy.WakeReason,
        standDownSince: Long,
        now: Long,
    ): Boolean = BluetoothRadioCyclePolicy.cycleDecision(
        sdkInt = Build.VERSION.SDK_INT,
        verdict = BluetoothRadioCyclePolicy.Verdict.of(settings.nativeAaRadioCycleVerdict),
        reason = reason,
        pairingHasRunAaHere = HandsFreeWakeEscalationPolicy.pairingHasRunAaHere(
            acceptedThisProcess = everAcceptedAaConnection,
            targetMac = device.address,
            lastConnectedNativeMac = settings.lastConnectedNativeMac,
        ),
        callActive = isCallActiveNow(),
        sessionInProgress = commManager.isConnected || isHandshakeInFlight() || isHandoffSettling(),
        standDownSinceMs = standDownSince,
        lastCycleMs = lastRadioCycleAt,
        cyclesUsed = radioCycles,
        now = now,
    )

    /** A call rides on the link a cycle would drop, and the audio mode is the read that needs no permission. */
    private fun isCallActiveNow(): Boolean = try {
        val mode = (context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager).mode
        CallState.isCallActive(mode, MicRecorder.holdsCommunicationMode) || CallState.isCallStarting(mode)
    } catch (e: Exception) {
        // Unreadable is not a licence to drop somebody's call.
        true
    }

    /**
     * Drops and re-raises this unit's own Bluetooth, so the phone sees a fresh `ACL_CONNECTED` and
     * hands-free connect for an address that is connected with its profile. Blocking on purpose:
     * nothing should poke while the radio is down.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    private suspend fun cycleOwnRadio(device: BluetoothDevice, standDownSince: Long, now: Long) {
        val adapter = BluetoothHelper.getBluetoothAdapter(context)
        val enabled = try { adapter != null && adapter.isEnabled } catch (e: Exception) { false }
        if (adapter == null || !enabled) {
            AppLog.i("NativeAA: this unit's Bluetooth is already off, so there is nothing to cycle.")
            return
        }
        radioCycles++
        lastRadioCycleAt = now
        val verdict = BluetoothRadioCyclePolicy.Verdict.of(settings.nativeAaRadioCycleVerdict)
        AppLog.i(
            "NativeAA: cycling this unit's Bluetooth to wake ${device.name ?: "unnamed"} " +
                "(${device.address}) — it has not started Android Auto in " +
                "${(now - standDownSince) / 1000}s, and a hands-free link that never changes raises " +
                "no event for the phone to notice. A poke would take that link and keep it; this " +
                "hands it back."
        )
        // The reconnect this produces is a real ACL_CONNECTED, so AutoStartReceiver will fire once,
        // as it does for a poke. Once per arming against the poke loop's every 15 s.
        var disableCalled = false
        // Cancellation is rethrown rather than caught as a failure. stop() cancels this job on every
        // mode change and user exit, and reading that as "the radio never came back" condemned a
        // healthy unit for the life of the install, which is the latch this policy exists to avoid.
        val offReached = try {
            adapter.disable()
            disableCalled = true
            awaitAdapterState(adapter, BluetoothAdapter.STATE_OFF, BluetoothRadioCyclePolicy.ADAPTER_OFF_WAIT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: this unit would not turn its Bluetooth off: ${e.message}")
            false
        }
        // Unconditional after a disable() that did not throw: the radio may still be on its way
        // down, and leaving it there would cost the user Bluetooth entirely.
        val onReached = if (!disableCalled) false else try {
            adapter.enable()
            awaitAdapterState(adapter, BluetoothAdapter.STATE_ON, BluetoothRadioCyclePolicy.ADAPTER_ON_WAIT_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w("NativeAA: this unit would not turn its Bluetooth back on: ${e.message}")
            false
        }
        if (onReached) {
            reopenListenersAfterCycle()
            radioCycleQuietUntil[device.address] =
                SystemClock.elapsedRealtime() + BluetoothRadioCyclePolicy.POST_CYCLE_QUIET_MS
        }
        // Null where the radio never went down: nothing was cycled, so nothing was measured either.
        noteRadioCycleOutcome(verdict, if (offReached) onReached else null)
    }

    /**
     * Polls rather than listening: [btStateReceiver] is for a bounce nobody asked for. The adapter
     * is held across the transition on purpose - it is a binder proxy and survives one, while
     * re-resolving it would run the secondary-service reflection eighty times a cycle.
     */
    private suspend fun awaitAdapterState(adapter: BluetoothAdapter, target: Int, boundMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + boundMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = try { adapter.state } catch (e: Exception) { null }
            if (state == target) return true
            delay(250)
        }
        return false
    }

    /** The radio is back, so take the listeners up without waiting for the reopen loop's own delay. */
    @SuppressLint("MissingPermission")
    private fun reopenListenersAfterCycle() {
        if (!isRunning || !aaListenerLost) return
        aaReopenAttempts = 0
        val adapter = BluetoothHelper.getBluetoothAdapter(context) ?: return
        reopenAaListener(adapter, "after this unit's Bluetooth was cycled to wake the phone")
    }

    /**
     * Records what the cycle cost, on the first one this unit ever made. A radio that never came
     * back is settled immediately; anything else waits for the unit's own stack to reconnect.
     */
    private fun noteRadioCycleOutcome(verdict: BluetoothRadioCyclePolicy.Verdict, backOn: Boolean?) {
        when (backOn) {
            null -> AppLog.w(
                "NativeAA: this unit's Bluetooth never went down, so nothing was cycled and nothing " +
                    "was measured. The hands-free link is untouched."
            )
            false -> AppLog.w(
                "NativeAA: this unit's Bluetooth did not come back after the cycle, so it will not " +
                    "be cycled again — the phone has to raise the Bluetooth event itself."
            )
            true -> Unit
        }
        if (!BluetoothRadioCyclePolicy.isProbe(verdict)) return
        val window = BluetoothRadioCyclePolicy.RECOVERY_WINDOW_MS
        radioCycleJob?.cancel()
        radioCycleJob = scope.launch(CoroutineName("NativeAa-RadioCycleProbe")) {
            val link = if (backOn != true) false else {
                delay(window)
                BluetoothHelper.handsFreeLinkState(context, includeGatewayRole = false)
            }
            val measured = BluetoothRadioCyclePolicy.verdictFrom(backOn, link)
            if (!BluetoothRadioCyclePolicy.recordable(measured)) {
                AppLog.i(
                    "NativeAA: this unit would not say whether its hands-free link came back after " +
                        "the Bluetooth cycle, so whether cycling works here stays unmeasured."
                )
                return@launch
            }
            settings.nativeAaRadioCycleVerdict = measured.ordinal
            if (measured == BluetoothRadioCyclePolicy.Verdict.RECOVERS) {
                AppLog.i(
                    "NativeAA: this unit's Bluetooth came back and reconnected its hands-free link " +
                        "within ${window / 1000}s of the cycle, so cycling is how this unit wakes a " +
                        "phone from now on."
                )
            } else {
                AppLog.w(
                    "NativeAA: this unit's hands-free link is still down ${window / 1000}s after " +
                        "the Bluetooth cycle, so cycling costs more than it buys here and will not " +
                        "be used again."
                )
            }
        }
    }

    /**
     * Keep the durable record of another device holding this unit's hands-free link in step.
     *
     * The banner is the surface because the lever is the other device rather than a setting here,
     * and the poke loop retires it itself the first pass that reads the link free.
     */
    private fun noteForeignHandsFreeLink(verdict: BluetoothWakePolicy.ForeignLink) {
        when (verdict) {
            // Once, not on every pass: this runs each poke round, and a moving stamp would put the
            // banner back in front of a user who had just dismissed it.
            BluetoothWakePolicy.ForeignLink.RAISE ->
                ConnectionIssues.raiseOnce(context, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE)
            BluetoothWakePolicy.ForeignLink.CLEAR ->
                ConnectionIssues.clear(context, ConnectionIssue.HANDS_FREE_HELD_ELSEWHERE)
            BluetoothWakePolicy.ForeignLink.UNCHANGED -> Unit
        }
    }

    /** Say once per arming what this unit is known to do with its hands-free link when woken. */
    private fun noteWakeDamageVerdict() {
        val message = when (NativeAaWakeDamagePolicy.Verdict.of(settings.nativeAaWakeDamageVerdict)) {
            NativeAaWakeDamagePolicy.Verdict.UNKNOWN ->
                "not yet measured on this unit, so the first one is the measurement"
            NativeAaWakeDamagePolicy.Verdict.SAFE ->
                "measured safe on this unit: the hands-free link came back on its own"
            NativeAaWakeDamagePolicy.Verdict.DESTRUCTIVE ->
                "measured to cost this unit its hands-free link for good, so none will go out " +
                    "until ${NativeAaWakeDamagePolicy.REPROBE_AFTER_ARMINGS} armings have waited " +
                    "it out (${settings.nativeAaWakeArmingsWithoutSession} so far) or " +
                    "\"Re-measure the Bluetooth wake\" is used"
        }
        AppLog.i("NativeAA: waking a phone over a hands-free link it holds is $message.")
    }

    /**
     * Holds the poke loop off [device] while its hands-free link has a chance to return, then, on
     * the first escalated wake this unit has ever made, records whether it did. The hold is the
     * repair; the reading is what lets a unit that never recovers stop waking by itself.
     */
    private fun armWakeProbe(device: BluetoothDevice, verdict: NativeAaWakeDamagePolicy.Verdict) {
        val window = NativeAaWakeDamagePolicy.PROBE_WINDOW_MS
        escalationProbeUntil[device.address] = SystemClock.elapsedRealtime() + window
        if (!NativeAaWakeDamagePolicy.isProbe(verdict, settings.nativeAaWakeArmingsWithoutSession)) return
        val acceptedBefore = acceptedAaConnectionThisArming
        wakeProbeJob?.cancel()
        wakeProbeJob = scope.launch(CoroutineName("NativeAa-WakeProbe")) {
            delay(window)
            val returned = BluetoothHelper.handsFreeLinkState(context, includeGatewayRole = false)
            val measured = NativeAaWakeDamagePolicy.verdictFrom(
                linkReturned = returned,
                wakeStartedAaSession = !acceptedBefore && acceptedAaConnectionThisArming,
            )
            if (!NativeAaWakeDamagePolicy.recordable(measured)) {
                AppLog.i("NativeAA: this unit would not say whether its hands-free link came back " +
                    "after the wake, so whether a wake costs it the link stays unmeasured.")
                return@launch
            }
            settings.nativeAaWakeDamageVerdict = measured.ordinal
            settings.nativeAaWakeArmingsWithoutSession = 0
            if (measured == NativeAaWakeDamagePolicy.Verdict.SAFE) {
                AppLog.i("NativeAA: the wake either brought the phone in or the hands-free link " +
                    "came back within ${window / 1000}s, so waking over it costs this unit a blip " +
                    "and will keep being used.")
            } else {
                AppLog.w("NativeAA: the hands-free link is still down ${window / 1000}s after the " +
                    "wake. On this unit a wake costs the link for good, so it will not be used " +
                    "again — the phone has to raise the Bluetooth event itself.")
            }
        }
    }

    /**
     * Waits out a poke already inside `socket.connect()` for [device], and says whether this one
     * may now open its own. False means another poke has been connecting to this phone for the
     * whole bound: a second socket to a stuck one only made every record fail.
     */
    private suspend fun awaitPokeSlot(device: BluetoothDevice): Boolean {
        var waitedMs = 0L
        while (isRunning) {
            when (PokeOverlapPolicy.step(pokeConnectingTo, device.address, waitedMs)) {
                PokeOverlapPolicy.Step.PROCEED -> return true
                PokeOverlapPolicy.Step.ABANDON -> {
                    AppLog.i("NativeAA: another poke has been connecting to ${device.name} for " +
                        "${waitedMs}ms — not opening a second socket to it.")
                    return false
                }
                PokeOverlapPolicy.Step.WAIT -> {
                    if (waitedMs == 0L) {
                        AppLog.i("NativeAA: another poke is already connecting to ${device.name} — " +
                            "waiting for it rather than opening a second socket.")
                    }
                    delay(PokeOverlapPolicy.POLL_MS)
                    waitedMs += PokeOverlapPolicy.POLL_MS
                }
            }
        }
        // Falling out of the loop means isRunning went false, which used to return here in silence -
        // so a "Wake this device" a user pressed left no line at all saying why nothing happened.
        AppLog.i(
            "NativeAA: not waking ${device.name} — " +
                (pokeNotReadyReason() ?: "the handshake servers stopped while the poke was waiting")
        )
        return false
    }

    /**
     * Tries each of [BluetoothWakePolicy.POKE_TARGETS] in turn, holding whichever connects for
     * [holdMs]. Both poke entry points come through here, so one check covers the retry loop and the
     * manual poke alike. The outcome separates a stand-down, which opens nothing, from a dial that
     * nothing answered: a budget counted in attempts must not spend one on the first.
     */
    private suspend fun pokeDevice(device: BluetoothDevice, holdMs: Long): BluetoothWakePolicy.WakeOutcome {
        // A poke that connects takes the phone's single hands-free slot, and this unit's own client
        // is dropped to make room. The link reads are adapter-wide, so the policy is told which role
        // is up and what the target itself is doing, and says whose link it takes it to be.
        val decision = BluetoothWakePolicy.wakeDecision(
            clientRoleLink = BluetoothWakePolicy.HandsFreeLink.of(
                BluetoothHelper.handsFreeLinkState(context, includeGatewayRole = false)
            ),
            gatewayRoleLink = BluetoothWakePolicy.HandsFreeLink.of(BluetoothHelper.gatewayHandsFreeLinkState(context)),
            targetLink = BluetoothWakePolicy.TargetLink.of(BluetoothHelper.deviceConnectionState(device)),
            linkIsAnotherPhones = NativeDriverSelectionPolicy.handsFreeLinkIsAnotherPhones(
                targetMac = device.address ?: "",
                switchedAwayFrom = switchedAwayFromMac,
                switchAgeMs = SystemClock.elapsedRealtime() - driverSwitchStartedAt
            )
        )
        if (!decision.poke) {
            val now = SystemClock.elapsedRealtime()
            // The stamp outlives an escalated wake on purpose: it dates the stand-down, so the
            // second wake is spaced by the cooldown rather than by a fresh 90 s wait. Only the guard
            // letting go of this device clears it.
            val standDownSince = handsFreeStandDownSince.getOrPut(device.address) { now }
            standDownReachedThisArming = true
            // The cycle and the wake are chosen between in one place so they can never both run for
            // one pass. Below API 33 the cycle goes first: it gives the link back, the wake cannot.
            val action = BluetoothRadioCyclePolicy.chooseAction(
                cycleAllowed = allowsRadioCycle(device, decision.reason, standDownSince, now),
                wakeAllowed = { escalateHandsFreeWake(device, decision.reason, standDownSince, now) },
            )
            when (action) {
                BluetoothRadioCyclePolicy.StandDownAction.CYCLE_RADIO -> {
                    cycleOwnRadio(device, standDownSince, now)
                    return BluetoothWakePolicy.WakeOutcome.STOOD_DOWN
                }
                BluetoothRadioCyclePolicy.StandDownAction.NOTHING -> {
                    noteHandsFreePokeSkip(device, decision.reason)
                    return BluetoothWakePolicy.WakeOutcome.STOOD_DOWN
                }
                BluetoothRadioCyclePolicy.StandDownAction.ESCALATED_WAKE -> Unit
            }
        } else {
            handsFreeStandDownSince.remove(device.address)
        }
        // Recorded here rather than after a successful connect, so a target whose Bluetooth is off
        // still produces the verdict: it reads as absent, which is the arm that raises.
        val foreignLink = BluetoothWakePolicy.foreignHandsFreeLink(decision.reason)
        noteForeignHandsFreeLink(foreignLink)
        if (decision.reason != BluetoothWakePolicy.WakeReason.NO_LINK) {
            val why = when (decision.reason) {
                BluetoothWakePolicy.WakeReason.SWITCH_TARGET -> "the link is the phone being switched away from"
                BluetoothWakePolicy.WakeReason.GATEWAY_ONLY ->
                    "the link is in the gateway role, so its other end is this unit's own car kit or headset"
                else -> "the phone holds no connection to this unit, so the link is not its"
            }
            val cost = if (foreignLink == BluetoothWakePolicy.ForeignLink.RAISE) {
                " A unit that serves one hands-free link at a time has none left to give this " +
                    "phone, so Android Auto on it may never see a connected profile until the " +
                    "other device lets go."
            } else {
                ""
            }
            AppLog.i("NativeAA: poking ${device.name ?: "unnamed"} (${device.address}) " +
                "with a hands-free link up: $why.$cost")
        }
        handsFreeSkipLoggedFor = null

        // connect() against an unpaired device makes the OS solicit pairing as a side effect, and
        // the user meant "wake my phone", not "ask to pair with it again".
        if (!BluetoothWakePolicy.mayPoke(bondReadingFor(device))) {
            AppLog.w("NativeAA: Not poking ${device.name ?: "unnamed"} (${device.address}) — it is not " +
                "currently paired with this head unit, and connecting to an unpaired device would " +
                "ask the user to pair rather than wake anything.")
            return BluetoothWakePolicy.WakeOutcome.NOT_PAIRED
        }

        pokeAttemptInFlight = true
        try {
            for (uuid in BluetoothWakePolicy.POKE_TARGETS) {
                // cancel() cannot interrupt a blocking connect(), so a cancelled poke arrives here
                // with its first record already spent. Stop rather than spend the second one too.
                currentCoroutineContext().ensureActive()
                val profile = BluetoothWakePolicy.profileName(uuid)
                var socket: BluetoothSocket? = null
                try {
                    socket = device.createRfcommSocketToServiceRecord(uuid)
                    AppLog.i("NativeAA: Calling socket.connect() for ${device.name} via $profile ($uuid)...")
                    pokeConnectingTo = device.address
                    socket.connect()
                    // Cleared before the hold, not in the finally below: the marker means "inside
                    // connect()", and a hold that keeps it set would stall the next poke for
                    // nothing - the phone is already awake by then.
                    pokeConnectingTo = null
                    // Named, not just the UUID: which record we ended up on is the first thing to
                    // check when a reporter's calls come out of the phone instead of the car.
                    AppLog.i("NativeAA: Successfully poked ${device.name} via $profile. Holding ${holdMs}ms...")
                    // Counted before the hold, so a poke that is cancelled mid-hold still counts:
                    // the phone answered, which is the whole point of the count.
                    pokesSinceLastAccept++
                    holdPoke(socket, device, profile, uuid, holdMs)
                    return BluetoothWakePolicy.WakeOutcome.ANSWERED
                } catch (e: CancellationException) {
                    // Rethrow instead of falling through to the next UUID: a cancelled poke (e.g.
                    // handleHandshake()'s pokeJob?.cancel() once a real handshake lands) must stop
                    // immediately, not fire another real, blocking socket.connect() on the same
                    // physical radio right as the critical WifiStartRequest send is about to happen.
                    throw e
                } catch (e: Exception) {
                    // Info, not debug: the success above is info, so a reporter at the default level
                    // saw the attempt and never its outcome. Address as well as name, because
                    // getName() is null for an unbonded device.
                    AppLog.i("NativeAA: Poke via $profile to ${device.name ?: "unnamed"} (${device.address}) failed: ${e.message}")
                } finally {
                    pokeConnectingTo = null
                    socket?.let { closePhoneSocket(it) }
                }
            }
            return BluetoothWakePolicy.WakeOutcome.DIALLED
        } finally {
            pokeConnectingTo = null
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
        // Written from the walk's own coroutine and read after the scope below joins it, which is
        // the ordering edge. A poke cancelled by a real handshake landing never reaches the report.
        var reached = HfpSlcInitiator.Stage.IDLE
        coroutineScope {
            // A child of this poke, not of the service scope, so cancelling the poke job unwinds it
            // and joins it before pokeDevice()'s own finally closes the socket underneath it.
            val slc = launch(CoroutineName("NativeAa-HfpSlc-${device.address}")) {
                serveHfpSocket(socket, "$profile poke to ${device.address}", initiate = true, closeWhenDone = false) {
                    reached = it
                }
            }
            try {
                delay(holdMs)
            } finally {
                slc.cancel()
            }
        }
        HfpServiceRecordPolicy.standInRefusalReason(initiated = true, stage = reached)?.let { why ->
            AppLog.i(
                "NativeAA: the hands-free connection to ${device.name ?: "unnamed"} " +
                    "(${device.address}) did not complete in ${holdMs}ms: $why. Android Auto will " +
                    "not start wireless setup without one."
            )
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
    /**
     * Wakes the phone while the group is still forming, rather than waiting for the credentials it
     * does not need yet. The handshake opens with the version exchange and waits for credentials
     * afterwards, so the phone's own wake latency runs alongside the bring-up instead of after it.
     */
    fun triggerEarlyWake(userExited: Boolean) {
        // isActive() is "started, and the listener not closed for this session": the accept loop
        // is launched in the same start() call, so this is as close to "accepting" as there is.
        if (!EarlyWakePolicy.mayWakeBeforeCredentials(
                listenersOpen = isActive(),
                credentialsPresent = credentials != null,
                userExited = userExited,
                sessionUp = commManager.isConnected,
            )
        ) return
        AppLog.i("NativeAA: waking the phone while the WiFi group is still forming.")
        triggerPoke()
    }

    fun triggerPoke() {
        if (isHandoffSettling()) {
            // Info, not debug: this line is the evidence the suppression is working, and reporter
            // logs default to INFO.
            AppLog.i("NativeAA: Handoff still settling — not starting a poke that would compete with the phone's WiFi association.")
            return
        }
        if (wakeStoodDown) {
            // Info for the same reason as the line above: it is the evidence the stand-down holds.
            AppLog.i("NativeAA: the phone ended the last session itself — listening for it rather than waking it.")
            return
        }
        if (isSelectionCanceled) {
            AppLog.i("NativeAA: Driver selection was explicitly canceled by user — skipping automated poke.")
            return
        }
        // A chosen driver outranks the round-robin. manualPoke() takes this same slot and clears
        // lastPokeTriggerCredentials, so without this the next credential redelivery cancelled the
        // poke aimed at the phone the user had just picked and went back to waking everybody.
        if (manualPokeInFlight) {
            AppLog.i("NativeAA: a chosen driver's wake poke is running — not replacing it with the multi-device loop.")
            return
        }
        // On the module route the poke below is meaningless: it dials the phone over the radio the
        // phone is not paired to. Ask the module to bring the link up instead. Branching here rather
        // than at the callers covers the credential path and WppAction.ResumePoke at once.
        zbtCarrier?.let {
            ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
            it.requestWake()
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
        if (!EarlyWakePolicy.shouldRestartLoop(pokeJob?.isActive == true, lastPokeTriggerCredentials, pokeKey)) {
            AppLog.d("NativeAA: a wake poke is already running for these credentials - not restarting it.")
            lastPokeTriggerCredentials = pokeKey
            // The loop re-reads credentials every pass, so from here it is a credentialed one.
            if (!EarlyWakePolicy.isEmptyKey(pokeKey)) pokeLoopStartedEmpty = false
            return
        }
        if (!EarlyWakePolicy.mayStartWithoutCredentials(EarlyWakePolicy.isEmptyKey(pokeKey), earlyWakeSpent)) {
            AppLog.i("NativeAA: not waking the phone again before the WiFi group exists; the last wake brought it back to nothing.")
            return
        }
        lastPokeTriggerCredentials = pokeKey
        pokeLoopStartedEmpty = EarlyWakePolicy.isEmptyKey(pokeKey)

        pokeJob?.cancel()
        pokeDeferralLogged = false
        pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Wakeup")) {
            // isActive() goes true in the same start() that launches the accept loop, so this
            // usually costs nothing; it is here for a poke triggered before start() has run.
            var waitedMs = 0L
            while (!isActive() && waitedMs < POKE_READY_WAIT_MS && isActive) {
                delay(POKE_READY_POLL_MS)
                waitedMs += POKE_READY_POLL_MS
            }
            AppLog.d("NativeAA: wake poke starting (listeners ready after ${waitedMs}ms).")

            // A delay, not a gate that returns: triggerPoke() is edge triggered, so a deadline read
            // on entry would drop this poke and leave nothing to fire the next one.
            val settleMs = SessionEndGroupPolicy.wakeSettleRemainingMs(
                sessionEndedAt, SystemClock.elapsedRealtime()
            )
            if (settleMs > 0) {
                AppLog.i("NativeAA: the session just ended, so the first poke waits ${settleMs}ms for the phone's WiFi to settle.")
                delay(settleMs)
            }

            // Waited here as well as at the foot of the loop, because a refusal always arrives by
            // way of a handshake, and handing over the credentials cancels this loop - so a gap
            // held only at the foot is never reached. Measured held only there: a flat 15 s
            // cadence, eleven pokes past the widening.
            val refusalWaitMs = JoinRefusalPolicy.remainingDelayMs(
                consecutiveJoinRefusals, POKE_RETRY_GAP_MS, lastJoinRefusalAtMs,
                SystemClock.elapsedRealtime()
            )
            if (refusalWaitMs > 0) {
                AppLog.i(
                    "NativeAA: the phone has refused this network $consecutiveJoinRefusals times in " +
                        "a row, so the next wake waits ${refusalWaitMs}ms."
                )
                delay(refusalWaitMs)
            }

            // One re-arm per loop. rearmForNextSession() also clears the driver prompt and the
            // handshake backoff, so asking it every pass would wipe a selection the user is inside.
            var rearmAsked = false
            while (isActive) {
                when (PokeReadinessPolicy.step(isRunning, aaListenersClosedForSession, commManager.isConnected)) {
                    PokeReadinessPolicy.Step.STOP -> break
                    PokeReadinessPolicy.Step.REARM_FIRST -> {
                        if (rearmAsked) break
                        rearmAsked = true
                        AppLog.i("NativeAA: the Android Auto listeners are closed, so the wake poke reopens them before waking the phone.")
                        rearmForNextSession()
                        if (!isActive()) break
                    }
                    PokeReadinessPolicy.Step.POKE -> {}
                }
                // Asked of the screen, never of the settings, and on every pass rather than once on
                // entry: deferring on shouldShowSelector() stranded a unit nobody was in front of,
                // and deciding it on entry made the deadline wait for the next credential delivery,
                // which is about a minute.
                when (NativeDriverSelectionPolicy.pokeHold(
                    promptActive = isSelectionPromptActive,
                    targetChosen = pendingSelectionTargetMac != null,
                    promptAgeMs = SystemClock.elapsedRealtime() - selectionPromptShownAt,
                    timeoutSec = settings.nativeDriverSelectionTimeoutSec
                )) {
                    NativeDriverSelectionPolicy.PokeHold.HOLD -> {
                        if (!promptHoldLogged) {
                            promptHoldLogged = true
                            AppLog.i("NativeAA: Multi-driver selection is active and awaiting user choice — deferring automated multi-device poke loop.")
                        }
                        delay(PROMPT_POLL_MS)
                        continue
                    }
                    NativeDriverSelectionPolicy.PokeHold.EXPIRED -> {
                        AppLog.i("NativeAA: the driver prompt went unanswered — waking every paired phone again.")
                        clearSelectionPrompt()
                        promptHoldLogged = false
                    }
                    NativeDriverSelectionPolicy.PokeHold.GO -> promptHoldLogged = false
                }

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

                // Two questions, two answers. Skipping a poke is retried seconds later; forgetting
                // a MAC is permanent, so it needs evidence the device is really gone rather than an
                // adapter that happened to be off. Both are in the policy.
                val selectedMacs = settings.nativePokeBtMacs
                val chosen = LinkedHashMap<String, BluetoothDevice>()
                val notPhones = mutableSetOf<String>()
                val staleMacs = mutableSetOf<String>()
                selectedMacs.forEach { mac ->
                    val reading = bondReadingFor(adapter, mac)
                    if (BluetoothWakePolicy.mayPoke(reading)) {
                        try {
                            val device = adapter.getRemoteDevice(mac)
                            chosen[mac] = device
                            if (classifyChosen(device) == DriverCandidatePolicy.Verdict.NOT_A_PHONE) notPhones.add(mac)
                        } catch (e: Exception) {}
                    }
                    if (BluetoothWakePolicy.shouldForget(reading)) staleMacs.add(mac)
                }
                if (staleMacs.isNotEmpty()) {
                    AppLog.w("NativeAA: Dropping wake poke MAC(s) no longer paired: $staleMacs")
                    settings.nativePokeBtMacs = selectedMacs - staleMacs
                }
                val devicesToPoke = when (val target = PokeTargetPolicy.targets(
                    selectedMacs - staleMacs, settings.nativePokeAllPairedDevices, notPhones
                )) {
                    is PokeTargets.Selected -> {
                        noteDroppedPokeTargets(target.dropped, chosen)
                        target.macs.mapNotNull { chosen[it] }
                    }
                    PokeTargets.AllPaired -> {
                        noteDroppedPokeTargets(notPhones, chosen)
                        AppLog.w("NativeAA: No wake poke phone selected, and poking all paired devices is on. Poking every paired phone...")
                        // Only a phone answers Native AA: the one advertising the Audio Gateway
                        // record this poke dials. A dongle, a radio or a watch holds the hands-free
                        // slot for nothing, so a device ruled out is never poked here; a MAC chosen
                        // in Auto Start settings is poked without this question.
                        val candidates = BluetoothHelper.driverCandidates(
                            context, settings.nativePreferredDeviceMac, settings.lastConnectedNativeMac
                        )
                        if (candidates.offered.isEmpty()) {
                            AppLog.w(
                                "NativeAA: no paired device advertises the Audio Gateway record, so nothing " +
                                    "is poked. Choose the phone in Auto Start settings if this is wrong."
                            )
                        } else if (candidates.hidden.isNotEmpty()) {
                            AppLog.i("NativeAA: ${candidates.hidden.size} paired device(s) are not phones and are not poked.")
                        }
                        candidates.offered
                    }
                    PokeTargets.None -> {
                        noteDroppedPokeTargets(notPhones, chosen)
                        AppLog.w("NativeAA: No wake poke phone selected, so nothing is poked. Choose one in Auto Start settings.")
                        emptyList()
                    }
                }

                if (devicesToPoke.isEmpty()) {
                    AppLog.w("NativeAA: No paired Bluetooth devices found to poke.")
                    // The credentials handler already said "waking" before this loop ran.
                    ConnectionStageTracker.retreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAITING_FOR_PHONE)
                    return@launch
                }

                // The loop used to be cancelled outright when a prompt went up, which is what made
                // its own deadline unreachable. It stops at the next device and holds instead.
                var promptTookOver = false
                for (device in devicesToPoke) {
                    promptTookOver = isSelectionPromptActive && pendingSelectionTargetMac == null
                    if (!isActive() || !isActive || isHandshakeInFlight() ||
                        isHandoffSettling() || promptTookOver) break
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
                        while (credentials == null && waitedMs < 4000 && isActive() && isActive) {
                            delay(200)
                            waitedMs += 200
                        }
                        if (waitedMs == 0) pokeNotReadyReason()?.let {
                            AppLog.w("NativeAA: not waiting for credentials, because $it.")
                        }
                    }

                    // A credential redelivery cancels this loop and starts a fresh one, and cancel
                    // cannot interrupt a blocking connect() — so the poke we replaced may still be
                    // inside one aimed at this same phone.
                    if (!awaitPokeSlot(device)) continue

                    // An escalated wake has just taken this phone's hands-free slot. The guard now
                    // reads no link to defer to, so without this the ordinary loop re-takes the slot
                    // every pass and the link never gets a window in which to come back.
                    val cycleQuietUntil = radioCycleQuietUntil[device.address] ?: 0L
                    if (cycleQuietUntil > SystemClock.elapsedRealtime()) {
                        val leftMs = cycleQuietUntil - SystemClock.elapsedRealtime()
                        AppLog.i("NativeAA: leaving ${device.name ?: "unnamed"} (${device.address}) " +
                            "unpoked for another ${leftMs / 1000}s after cycling this unit's " +
                            "Bluetooth, so the phone can act on the connection it just saw.")
                        continue
                    }
                    radioCycleQuietUntil.remove(device.address)

                    val probeUntil = escalationProbeUntil[device.address] ?: 0L
                    if (probeUntil > SystemClock.elapsedRealtime()) {
                        val leftMs = probeUntil - SystemClock.elapsedRealtime()
                        AppLog.i("NativeAA: leaving ${device.name ?: "unnamed"} (${device.address}) " +
                            "unpoked for another ${leftMs / 1000}s so the hands-free link this unit " +
                            "took from it has a chance to come back.")
                        continue
                    }
                    escalationProbeUntil.remove(device.address)

                    // An escalated wake took this phone's hands-free slot and bought nothing. The
                    // ordinary loop would keep re-taking it, so the link would never come back.
                    if (HandsFreeWakeEscalationPolicy.escalationSpent(escalatedWakes, pokesSinceLastAccept)) {
                        if (escalationSpentLoggedFor != device.address) {
                            escalationSpentLoggedFor = device.address
                            AppLog.i("NativeAA: Leaving ${device.name ?: "unnamed"} (${device.address}) " +
                                "alone for now: this unit woke it over its hands-free link and it has " +
                                "ignored $pokesSinceLastAccept pokes since, so the link is given back " +
                                "rather than taken again. It is poked again on the next arming.")
                        }
                        continue
                    }

                    AppLog.i("NativeAA: Attempting active poke to device: ${device.name} (${device.address})...")
                    ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
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
                    // Named here rather than in the paragraph above, because it is the one cause of
                    // this signature the user can clear from our own settings screen.
                    NativeHandoffPolicy.silentPokeAdvice(settings.nativeAaCompleteHfpSlc)?.let {
                        AppLog.w("NativeAA: $it")
                    }
                }

                // Straight back to the hold poll rather than through the 15 s gap, so the
                // prompt's own deadline is what ends the wait.
                if (promptTookOver) continue

                // This loop never gives up, so nothing else would take the pill off "waking" when
                // a round ends unanswered. A phone that did answer has already moved it higher.
                ConnectionStageTracker.retreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAITING_FOR_PHONE)
                // The only gap [JoinRefusalPolicy] governs here. The deferral above is not a
                // refusal, and the driver-selection rounds happen with the user standing there
                // having just pressed something. A refusal's own gap is usually spent at the head
                // of the loop instead, since a refusal cancels the loop that earned it.
                delay(JoinRefusalPolicy.retryDelayMs(consecutiveJoinRefusals, POKE_RETRY_GAP_MS))
            }
        }
    }


    /** The WiFi button on the module route: there is no Android device to name, only the module. */
    fun wakeOverModule(): Boolean {
        val carrier = zbtCarrier ?: return false
        wakeStoodDown = false
        sessionEndedAt = 0L
        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
        AppLog.i("NativeAA: Manual poke requested — asking the Bluetooth module to connect Android Auto.")
        resetHandshakeBackoff()
        resetJoinRefusals()
        carrier.requestWake()
        return true
    }

    /**
     * Start a manual poke (wakeup) for a specific Bluetooth device.
     */
    fun manualPoke(address: String) {
        // Pressing the button is the way out of the stand-down and its settle, as well as of a
        // backoff: the user wants this phone woken now, whatever the last session ended on.
        // The user asking to try again is the way out of a backoff on either route.
        if (wakeOverModule()) return
        wakeStoodDown = false
        sessionEndedAt = 0L
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
            resetJoinRefusals()

            pokeJob?.cancel()
            // The manual job takes the slot the retry loop uses, so forget what the loop last poked
            // for. Left set, a redelivery of unchanged credentials during this poke reads as "the
            // loop is already running for these" and the loop is never started again.
            lastPokeTriggerCredentials = null
            manualPokeInFlight = true
            pokeJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ManualWakeup")) {
                try {
                    // Pre-flight: Ensure WiFi credentials (SSID/IP) are ready before connecting RFCOMM to phone.
                    if (credentials == null) {
                        AppLog.i("NativeAA: WiFi credentials not ready before manual poke. Requesting WiFi refresh...")
                        launcher.triggerWifiDirectRefresh()
                        var waitedMs = 0
                        while (credentials == null && waitedMs < 4000 && isActive() && isActive) {
                            delay(200)
                            waitedMs += 200
                        }
                        val whyNotWaited =
                            if (waitedMs == 0) pokeNotReadyReason()?.let { ", because $it" }.orEmpty() else ""
                        AppLog.i("NativeAA: Pre-poke credential wait completed. SSID=${credentials?.ssid}, IP=${credentials?.ip} (waited ${waitedMs}ms$whyNotWaited)")
                    }

                    // pokeJob.cancel() above cannot interrupt a blocking connect(), so the
                    // automatic poke it replaced may still be inside one aimed at this same phone.
                    if (!awaitPokeSlot(device)) return@launch

                    // One hold used to be the whole wake, and it ended before the accept gate
                    // reopened, so the phone the driver had just left won the race back in.
                    val wakeStartedAt = SystemClock.elapsedRealtime()
                    var roundsDialled = 0
                    var outcome = BluetoothWakePolicy.WakeOutcome.STOOD_DOWN
                    fun keepWaking() = NativeDriverSelectionPolicy.chosenWakeContinues(
                        outcome, roundsDialled, SystemClock.elapsedRealtime() - wakeStartedAt
                    )
                    while (keepWaking()) {
                        if (!isActive() || !isActive || isHandshakeInFlight() || isHandoffSettling()) break
                        // The escalation now fires on this path too, so its give-back has to hold
                        // here as well: re-poking inside the window is what it exists to prevent.
                        val probeLeftMs = (escalationProbeUntil[device.address] ?: 0L) - SystemClock.elapsedRealtime()
                        if (probeLeftMs > 0) {
                            AppLog.i("NativeAA: leaving ${device.name ?: "unnamed"} (${device.address}) " +
                                "unpoked for another ${probeLeftMs / 1000}s so the hands-free link this " +
                                "unit took from it has a chance to come back.")
                            delay(POKE_RETRY_GAP_MS)
                            continue
                        }
                        escalationProbeUntil.remove(device.address)
                        AppLog.i("NativeAA: Attempting manual poke to ${device.name}...")
                        // The pill shows this wake like the retry loop's: waking while a round
                        // runs, back to waiting after one the phone did not answer.
                        ConnectionStageTracker.report(ConnectionStage.WAKING_PHONE)
                        outcome = pokeDevice(device, holdMs = 20000)
                        AppLog.i("NativeAA: Manual poke to ${device.name} finished.")
                        if (outcome != BluetoothWakePolicy.WakeOutcome.ANSWERED) {
                            ConnectionStageTracker.retreat(ConnectionStage.WAKING_PHONE, ConnectionStage.WAITING_FOR_PHONE)
                        }
                        if (NativeDriverSelectionPolicy.wakeRoundSpent(outcome)) roundsDialled++
                        if (keepWaking()) delay(POKE_RETRY_GAP_MS)
                    }
                } finally {
                    manualPokeInFlight = false
                }
            }
        } catch (e: Exception) {
            manualPokeInFlight = false
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
    /**
     * Start the handshake over the head unit's external Bluetooth module.
     *
     * None of the Android Bluetooth setup applies here and all of it is skipped: no adapter, no
     * BLUETOOTH_CONNECT, no RFCOMM listeners, no secondary radios, no HFP responder. The phone is
     * bonded to a chip `android.bluetooth` does not expose, so a listener on the Android radio
     * would never be reached, which is the defect this route exists to route around.
     */
    /**
     * Fills the Bluetooth address in from the module when the user has not set one.
     *
     * Same rule as the boot-time seed: what the user typed is never overwritten, because a
     * hand-entered address is usually there because the detected one was wrong.
     */
    private fun seedBluetoothAddressFromModule(address: String) {
        val canonical = BluetoothHelper.normalizeMacAddress(address) ?: return
        val seeded = BluetoothAddressSeedPolicy.seed(settings.bluetoothAddress, canonical)
        if (seeded.isEmpty() || seeded == settings.bluetoothAddress) return
        settings.bluetoothAddress = seeded
        AppLog.i("NativeAA: [ZBT] the module named this unit's Bluetooth address ($seeded), so the " +
            "Bluetooth service can be announced; phone calls need it")
    }

    private fun startOverExternalModule() {
        isRunning = true
        notStartedReason = null
        aaListenersClosedForSession = false
        localRadioName = "external Bluetooth module"
        AppLog.i(
            "NativeAA: external Bluetooth module transport is on (${BluetoothHelper.externalBtEvidence}) " +
                "— the handshake will go over the module through the vendor daemon, not this unit's " +
                "own Bluetooth radio."
        )
        val carrier = ZbtAaCarrier(
            // The module's arrival is this route's "phone answered", or the pill never moves past waking.
            serve = { link ->
                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                handleHandshake(link)
            },
            isRunning = { isRunning },
            isFinishedForSession = { aaListenersClosedForSession },
            isSessionConnected = { commManager.isConnected },
            isSettling = { isHandoffSettling() },
            isHandshakeInFlight = { isHandshakeInFlight() },
            mayServeHandshake = { NativeHandoffPolicy.shouldServeHandshake(consecutiveHandshakeFailures) },
            onPhoneEvidence = { resetHandshakeBackoff() },
            // The module names its own address, and on these units nothing else could: the
            // adapter is masked and the vendor property is not always there. Without it no
            // Bluetooth service is announced and Android Auto keeps calls on the phone.
            onModuleAddress = { address -> seedBluetoothAddressFromModule(address) },
            retryDelayMs = {
                JoinRefusalPolicy.retryDelayMs(
                    consecutiveJoinRefusals,
                    ZbtAttemptPolicy.MIN_ATTEMPT_INTERVAL_MS
                )
            }
        )
        zbtCarrier = carrier
        scope.launch(Dispatchers.IO + CoroutineName("NativeAa-ZbtCarrier")) {
            try {
                carrier.run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.e("NativeAA: [ZBT] carrier stopped unexpectedly: ${e.message}", e)
            }
        }
    }

    private fun refuseWhileBackedOff(link: HandshakeLink): Boolean {
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
        closePhoneLink(link)
        return true
    }

    /** Clears the handshake backoff. Called wherever the user or the system asks for a fresh try. */
    private fun resetHandshakeBackoff() {
        consecutiveHandshakeFailures = 0
        loggedHandshakeBackoff = false
    }

    /**
     * Clears the join-refusal backoff.
     *
     * Deliberately not part of [resetHandshakeBackoff]: that one runs on every message the phone
     * sends, and a refusing phone sends plenty. Only a landed session, a manual poke or a re-arm
     * counts as evidence the refusals have stopped.
     */
    private fun resetJoinRefusals() {
        consecutiveJoinRefusals = 0
        lastJoinRefusalAtMs = 0L
    }

    /** Set by [WppTcpServer] on a dial it turned away, read by the landing that follows. */
    @Volatile
    private var dialRefusedSinceLastLanding = false

    /**
     * Retires the stale-endpoint record where this landing disproves it.
     *
     * Only a served dial used to, and a rejection that works means no dial ever arrives again, so
     * the banner stood forever on exactly the units the fix had repaired.
     */
    private fun retireStaleEndpointRecord() {
        val refused = dialRefusedSinceLastLanding
        dialRefusedSinceLastLanding = false
        if (!StaleEndpointRecordPolicy.retiredByHandshake(refused, launcher.strategy == NativeStrategy.HOTSPOT)) return
        ConnectionIssues.clear(context, ConnectionIssue.PHONE_HOLDS_STALE_ENDPOINT)
    }

    /**
     * Records a phone that answered everything and then said it could not join.
     *
     * Widening the gap is the whole remedy: the cause is outside the app, and retrying every few
     * seconds only flickers the phone between its two setup screens while it waits.
     */
    private fun countJoinRefusal() {
        consecutiveJoinRefusals++
        lastJoinRefusalAtMs = SystemClock.elapsedRealtime()
        if (JoinRefusalPolicy.isFirstWidening(consecutiveJoinRefusals)) {
            AppLog.w(
                "NativeAA: the phone has refused this network $consecutiveJoinRefusals times in a row, so " +
                    "retries are slowing down. It reaches us over Bluetooth and then cannot find the " +
                    "network we name. A hotspot switched on on the phone is the usual cause, because the " +
                    "phone's radio cannot host that and join us at the same time."
            )
        }
    }

    /**
     * Runs [block] only while [socket] is still the handshake this manager is serving.
     *
     * Every write a handshake makes to shared manager state goes through this. Losing ownership
     * does not stop a superseded handshake — where close() cannot interrupt a pending read it runs
     * on for minutes — and its late writes would clear the live session's settling stamp, cancel
     * its poke, close listeners it still needs, or wipe a backoff it had legitimately earned.
     */
    private inline fun ifOwner(link: HandshakeLink, block: () -> Unit) {
        if (activeHandshakeLink === link) block()
    }

    private suspend fun handleHandshake(link: HandshakeLink) = withContext(Dispatchers.IO) {
        // The phone reached us. Recorded here rather than at either accept site so both the
        // primary and the secondary-radio loops are covered by one statement.
        everAcceptedAaConnection = true
        acceptedAaConnectionThisArming = true
        // The verdict's re-measure clock is about armings that never connected; this one did.
        settings.nativeAaWakeArmingsWithoutSession = 0
        // The poke count is deliberately not cleared here. It answers the stale-group watchdog's
        // question, which is whether the network this group saved still reaches the phone, and an
        // RFCOMM accept says nothing about the network. onSessionEstablished() clears it.
        // The phone answered, so any stand-down clock is stale: the next one times its own wait.
        handsFreeStandDownSince.clear()

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
        val previousLink = activeHandshakeLink
        val previousJob = activeHandshakeJob
        // Ownership is claimed *before* the previous session is torn down, not after: cancelling
        // it makes its finally block run on another thread at a moment we do not control, and the
        // only thing keeping that block off this handshake's state is the ifOwner fence. Take
        // ownership first and the fence is already closed when the old one unwinds.
        activeHandshakeLink = link
        activeHandshakeJob = coroutineContext[Job]
        // Stamped after claiming ownership above, so a superseded handshake's cleanup — which
        // only fires when it still owns activeHandshakeLink — can't wipe this one's stamp.
        handshakeStartedAt = SystemClock.elapsedRealtime()
        if (previousLink != null && previousLink !== link) {
            AppLog.i("NativeAA: A new handshake arrived while one was still settling — closing the previous session.")
            handoffSettlingSince = 0L
            // Cancel *and* close, in that order: see activeHandshakeJob. Cancelling first means
            // the old coroutine cannot mistake the close for a phone-side drop and act on it.
            previousJob?.cancel()
            try { previousLink.close() } catch (_: Exception) {}
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
        val session = WppHandshakeSession()
        // Everything the phone sends, in order. Replaces the single bounded read this used to do:
        // types 6 and 7 arrive *after* the credentials go out, so a one-shot read could never see
        // them, and the phone is free to interject a ping at any point in between.
        val inbound = Channel<ProtobufMessage>(Channel.UNLIMITED)
        var readerJob: Job? = null
        var arbiterClaim: ConnectionArbiter.Claim? = null
        try {
            val peerName = link.peerName
            val peerAddress = link.peerAddress
            AppLog.i("NativeAA: Handling handshake for $peerName ($peerAddress) on local radio [${link.radioLabel ?: "?"}]")

            if (commManager.isConnected ||
                commManager.connectionState.value is CommManager.ConnectionState.Connecting) {
                AppLog.i("NativeAA: USB/other session already active. Aborting BT handshake so phone does not start a parallel wireless attempt.")
                abortedLocally = true
                closePhoneLink(link)
                return@withContext
            }
            arbiterClaim = ConnectionArbiter.claim(
                ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE,
                ConnectionPriorityPolicy.Owner.WIRELESS_STACK,
                "the Native AA handshake with $peerName"
            )
            if (arbiterClaim == null) {
                abortedLocally = true
                closePhoneLink(link)
                return@withContext
            }

            // The wake poke target only, and only when no phone is chosen. Writing the auto-start
            // list here turned Bluetooth auto-start on for a user who never asked, and undid a clear.
            // Only for a phone on this unit's own radio: an address behind an external module is
            // one nothing in android.bluetooth can dial.
            val chosenMacs = settings.nativePokeBtMacs
            if (link.persistPeerForAutoStart && peerAddress != null &&
                PokeTargetPolicy.adoptsHandshakedDevice(chosenMacs, notPhonesAmong(chosenMacs))) {
                AppLog.i("NativeAA: Saving $peerAddress ($peerName) as the wake poke device.")
                settings.nativePokeBtMacs = setOf(peerAddress)
            }

            val input = DataInputStream(link.input)
            val output = link.output

            // [BUG_FIX] There is no BluetoothSocket.setSoTimeout(), and the old workaround —
            // close the socket to unblock readFully() — only works where close() interrupts a
            // pending read. Where it does not, the handshake never unwinds and takes the wake poke
            // and the P2P join watchdog down with it for the rest of the session. Time out the
            // *wait* instead: read on a coroutine of its own and take messages from a channel,
            // which resumes on schedule whether or not the read ever returns. The reader itself is
            // still unreclaimable on such a stack; consecutiveHandshakeFailures bounds that.
            readerJob = scope.launch(Dispatchers.IO + CoroutineName("NativeAa-Reader-$peerAddress")) {
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
            // The withdrawal count the credentials were sent under, or -1 before Type 3.
            var sentUnderWithdrawals = -1
            // When the opening message last went out, for the transports that have to repeat it.
            var lastOpenerSentAt = 0L

            suspend fun runAction(action: WppAction, source: ProtobufMessage?) {
                when (action) {
                    WppAction.SendVersionRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiVersionRequest (Type 4) v${WppHandshakeSession.WPP_VERSION_MAJOR}.${WppHandshakeSession.WPP_VERSION_MINOR}")
                        sendWifiVersionRequest(output, transport)
                        lastOpenerSentAt = SystemClock.elapsedRealtime()
                        spokeToPhone = true
                    }
                    WppAction.SendStartRequest -> {
                        AppLog.i("NativeAA: [TX] Sending WifiStartRequest (Type 1)")
                        sendWifiStartRequest(output, credIp, 5288)
                        lastOpenerSentAt = SystemClock.elapsedRealtime()
                        spokeToPhone = true
                    }
                    WppAction.SendInfoResponse -> {
                        AppLog.i("NativeAA: Phone ready for WiFi association. Delivering credentials...")
                        AppLog.i("NativeAA: [TX] Sending WifiInfoResponse (Type 3) with full credentials in 1000ms...")
                        // The one site that really is sending credentials to the phone.
                        ConnectionStageTracker.report(ConnectionStage.SENDING_CREDENTIALS)
                        delay(1000) // [FIX] Increased delay to give phone more processing time
                        // The one blind spot the tick loop's own check cannot cover. A phone that
                        // rejoined the kept network during this pause is already projecting, and
                        // credentials now would only make it re-associate and drop that session.
                        // Not a failure, so the handshake backoff is not spent on it.
                        if (commManager.isConnected) {
                            AppLog.i("NativeAA: the phone's session landed while Type 3 was pending, so no credentials are sent.")
                            abortedLocally = true
                            return
                        }
                        // Read again here rather than trusting the snapshot this exchange started
                        // with. A group removed inside the pause above leaves the phone hunting an
                        // SSID that is gone, which it cannot recover from without a new handshake.
                        val withdrawalsAtSend = credentialsWithdrawals.get()
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
                        sendWifiSecurityResponse(
                            output, credSsid, credPsk, credBssid,
                            credentials?.identity ?: GroupIdentityStability.UNPROVEN
                        )
                        // Set after the write returns, not before the delay above: this marks that
                        // we put bytes on the channel, and a phone that opened the exchange itself
                        // can reach this having had nothing from us before it.
                        spokeToPhone = true
                        sentUnderWithdrawals = withdrawalsAtSend
                        AppLog.i("NativeAA: Handshake completed successfully on Bluetooth side.")
                        val remoteMac = link.peerAddress.orEmpty()
                        if (remoteMac.isNotEmpty()) {
                            settings.lastConnectedNativeMac = remoteMac
                        }
                        ifOwner(link) {
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
                        ifOwner(link) { handoffSettlingSince = SystemClock.elapsedRealtime() }
                    }
                    WppAction.CompleteSuccess -> {
                        AppLog.i("NativeAA: WiFi session landed. Handshake session ending, releasing Bluetooth connection.")
                        ifOwner(link) {
                            retireStaleEndpointRecord()
                            // A phone holding the endpoint never comes over Bluetooth, so one that did holds none.
                            retireAdvertisedEndpoint("a phone completed the Bluetooth handshake on it")
                            resetJoinRefusals()
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
                        if (action.joinRefused) ifOwner(link) { countJoinRefusal() }
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
                    WppAction.ResumePoke -> ifOwner(link) {
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
                        ifOwner(link) {
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
                // Asked at every stage, not only once the credentials are out: a phone that
                // rejoins the kept network on its own reaches 5288 mid-exchange, and Type 3 on
                // top of that makes it re-associate and drop the session it just made.
                // WppTcpServer asks the same per tick; Connecting is still the handoff only.
                val handoffLanding = session.stage == WppStage.SETTLING &&
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting
                if (commManager.isConnected || handoffLanding) {
                    feed(WppEvent.TcpSessionUp)
                    return
                }
                if (session.stage == WppStage.SETTLING && sentUnderWithdrawals >= 0 &&
                    credentialsWithdrawals.get() != sentUnderWithdrawals
                ) {
                    AppLog.w("NativeAA: the network the phone was sent was taken down while it was joining, so this handshake ends now and the phone is woken for the new one.")
                    feed(WppEvent.NetworkWithdrawn)
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

            // A message the daemon dropped before the phone's channel existed is invisible to
            // both sides, so on that route the opening message is repeated until anything answers.
            fun resendOpener(now: Long, send: () -> Unit, what: String) {
                if (!ZbtRetransmitPolicy.shouldResend(
                        enabled = link.retransmitsWhileSilent,
                        phoneHasAnswered = session.messagesReceived > 0,
                        lastSentAtMs = lastOpenerSentAt,
                        nowMs = now
                    )
                ) return
                AppLog.i("NativeAA: [TX] repeating $what - the phone has answered nothing yet.")
                lastOpenerSentAt = now
                // A dead socket must not throw out of a wait loop: the stage deadlines end this.
                runCatching { send() }
                    .onFailure { AppLog.w("NativeAA: [TX] the repeat of $what failed: ${it.message}") }
            }

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
                resendOpener(
                    now,
                    { sendWifiVersionRequest(output, transport) },
                    "WifiVersionRequest (Type 4)"
                )
                tick(500)
            }

            // Read once. The check and the use used to be separate reads of four separate fields,
            // so an invalidate between them threw on the `!!` and reported "Handshake error: null".
            val snapshot = credentials
            if (snapshot == null) {
                AppLog.e("NativeAA: Handshake failed - No WiFi credentials available after ${CREDENTIALS_WAIT_MS / 1000}s wait.")
                if (EarlyWakePolicy.stopAfterCredentialsFailure(pokeLoopStartedEmpty, credentialsPresent = false)) {
                    // Woken before the group existed and the group never came. Waking it again
                    // costs a 60 s wait and a hands-free hold each time for nothing; the
                    // credential delivery restarts the wake once there is a network to hand over.
                    AppLog.i("NativeAA: the early wake brought the phone back to no network; not waking it again until the WiFi group exists.")
                    earlyWakeSpent = true
                    pokeJob?.cancel()
                    pokeJob = null
                    pokeLoopStartedEmpty = false
                }
                abortedLocally = true
                feed(WppEvent.CredentialsUnavailable)
                return@withContext
            }

            credIp = snapshot.ip
            credSsid = snapshot.ssid
            credPsk = snapshot.psk
            credBssid = snapshot.bssid.uppercase()
            capturedCreds = NativeNetworkCredentials(credSsid, credPsk, credIp, credBssid)

            // Before the BSSID, because this one is certain rather than merely likely: an open
            // network is refused by every client, and sending it spends a wake poke, which takes
            // the phone's hands-free link and nothing gives it back.
            if (!NativeCredentialsPolicy.isUsablePassphrase(credPsk) &&
                NativeCredentialsPolicy.onEmptyPassphrase(transport) == UnusablePassphraseAction.ABORT
            ) {
                AppLog.e(
                    "NativeAA: these credentials for '$credSsid' carry no passphrase, and the phone " +
                        "refuses an open network. Not sending them. " +
                        if (transport == NativeTransport.HOTSPOT)
                            "Set 'Hotspot password (manual)' as well as the name under Wireless connection."
                        else
                            "The group came up without one, which is a fault in this unit's WiFi Direct stack."
                )
                if (transport == NativeTransport.HOTSPOT) {
                    ConnectionIssues.raise(context, ConnectionIssue.HOTSPOT_CONFIG_UNREADABLE)
                }
                abortedLocally = true
                return@withContext
            }

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
                if (session.stage == WppStage.AWAIT_INFO_REQUEST) {
                    resendOpener(
                        SystemClock.elapsedRealtime(),
                        { sendWifiStartRequest(output, credIp, 5288) },
                        "WifiStartRequest (Type 1)"
                    )
                }
                tick(250)
            }

        } catch (e: Exception) {
            AppLog.e("NativeAA: Handshake error: ${e.message}", e)
        } finally {
            // Only clear the stamps if this handshake still owns them — a superseding handshake
            // has already taken over and set its own.
            if (activeHandshakeLink === link) {
                activeHandshakeLink = null
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
                    //
                    // Except when nothing told us a phone was there: a blind attempt on the module
                    // route meets this predicate in an empty car, and the radio did nothing wrong.
                    // The backoff still counts it, because that is what bounds those attempts.
                    if (link.peerReportedPresent) {
                        AppLog.w("NativeAA: the phone connected over Bluetooth and answered nothing we sent. If this repeats, this unit's Bluetooth cannot carry Android Auto and USB or the Wireless Helper mode are the way round it.")
                        ConnectionIssues.raise(context, ConnectionIssue.BLUETOOTH_SENT_NO_DATA)
                    }
                }
            }
            // Best effort only, exactly as before: on a stack where close() does not interrupt a
            // pending read this cannot end the reader — a blocking JNI read has no suspension
            // point to cancel at — so its thread is stranded from here on.
            readerJob?.cancel()
            inbound.close()
            closePhoneLink(link)
            ConnectionArbiter.release(arbiterClaim, sessionFormed = commManager.isConnected)
            AppLog.i("NativeAA: BT Handshake link closed.")
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
                        // Fields 3 and 4 are two strings the phone declares and nothing here knows
                        // the meaning of. Printed only when present, so a capture can name them.
                        val extra = listOfNotNull(
                            v.deviceInfo.unknownString3.takeIf { v.deviceInfo.hasUnknownString3() && it.isNotEmpty() },
                            v.deviceInfo.unknownString4.takeIf { v.deviceInfo.hasUnknownString4() && it.isNotEmpty() },
                        ).joinToString(" ") { "unknown=$it" }
                        " device=${v.deviceInfo.deviceId} lifetime=${v.deviceInfo.connectivityLifetimeId}" +
                            if (extra.isNotEmpty()) " $extra" else ""
                    } else ""
                    // The phone's own answer to which band it wants (2.4-only / 5-only / dual). The
                    // one place it says so, and the only check on a channel we cannot read back.
                    val channelType =
                        if (v.hasSelectedWifiChannelType()) " channelType=${v.selectedWifiChannelType}" else ""
                    AppLog.i("NativeAA: [RX] WifiVersionResponse v${v.major}.${v.minor} status=${WppStatus.describe(if (v.hasStatus()) v.status else null)}$channelType$device")
                }
                WppMessageType.CONNECT_STATUS -> {
                    val s = Wireless.WifiConnectStatus.parseFrom(msg.payload)
                    // The hint is the phone's own words for a refusal, and the only one it sends.
                    val hint = if (s.hasErrorMessageHint()) " hint=\"${s.errorMessageHint}\"" else ""
                    AppLog.i("NativeAA: [RX] WifiConnectStatus status=${WppStatus.describe(if (s.hasStatus()) s.status else null)}$hint (SUCCESS = the phone got onto our network)")
                    ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)
                }
                // Ours to send and never to receive: the phone's own dispatcher answers one of
                // these with an exception rather than a reply. Named so a capture says so.
                WppMessageType.CONNECTION_REJECTION ->
                    AppLog.w("NativeAA: [RX] WifiConnectionRejection, which the phone should never send")
                WppMessageType.START_RESPONSE -> {
                    val r = Wireless.WifiStartResponse.parseFrom(msg.payload)
                    val port = if (r.hasPort()) ":${r.port}" else ""
                    AppLog.i("NativeAA: [RX] WifiStartResponse ip=${r.ipAddress}$port status=${WppStatus.describe(if (r.hasStatus()) r.status else null)}")
                    ConnectionStageTracker.report(ConnectionStage.PHONE_JOINING)
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
     * that is safe to say. Real head units open with this, and it is what carries the endpoint.
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
                    recordAdvertisedEndpoint(transport)
                }
        }
        val channelType = WppChannelTypePolicy.forHeadUnit(WifiBandCapability.supports5Ghz(context))
        val request = WppMessages.versionRequest(carInfo(), endpoint, channelType)
        sendProtobuf(output, request.toByteArray(), WppMessageType.VERSION_REQUEST)
    }

    /** Remembers the network an endpoint went out under, which is the one the phone will insist on. */
    private fun recordAdvertisedEndpoint(transport: NativeStrategy) {
        val creds = credentials ?: return
        if (transport == NativeStrategy.HOTSPOT) {
            val ap = SoftApEndpointStabilityPolicy.advertisement(creds.ssid, creds.psk, creds.bssid, creds.ip) ?: return
            if (settings.softApAdvertisedEndpoint != ap) settings.softApAdvertisedEndpoint = ap
            return
        }
        val pair = EndpointRetirementPolicy.recordsAdvertisement(transport, creds.ssid, creds.psk) ?: return
        if (settings.wifiDirectAdvertisedIdentity != pair) settings.wifiDirectAdvertisedIdentity = pair
    }

    /** The advertised pair when the group on the air is the one being retired, else null. */
    private fun retiringIdentity(): StoredP2pIdentity? {
        val creds = credentials ?: return null
        val advertised = settings.wifiDirectAdvertisedIdentity ?: return null
        return advertised.takeIf {
            EndpointRetirementPolicy.isRetiring(
                advertised, settings.wifiDirectStableIdentity, settings.wifiDirectGroupIdentity,
                Build.VERSION.SDK_INT >= P2pIdentityRotationPolicy.NAMED_CREATE_SDK,
                settings.wifiDirectRotationPending, creds.ssid, creds.psk,
            )
        }
    }

    /** Forgets the advertised pair once a phone has been rejected on it or came back over Bluetooth. */
    private fun retireAdvertisedEndpoint(how: String) {
        val retiring = retiringIdentity() ?: return
        settings.wifiDirectAdvertisedIdentity = null
        AppLog.i("NativeAA: the WPP endpoint advertised under ${retiring.networkName} is retired ($how); the next bring-up uses the current identity.")
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

            // isActive(), not isStarted(): the question is whether a phone sent back to Bluetooth
            // right now would find the listeners open, not whether they were ever brought up.
            override fun canRunRfcomm(): Boolean = isActive()

            override fun projectionSessionUp(): Boolean = commManager.isConnected

            override fun projectionEndpoint(): Pair<String, Int>? =
                this@NativeAaHandshakeManager.credentials?.ip?.takeIf { it.isNotBlank() }?.let { it to 5288 }

            override fun noteDialRefused() { dialRefusedSinceLastLanding = true }

            override fun retiringNetworkName(): String? = retiringIdentity()?.networkName

            override fun noteEndpointRetired() = retireAdvertisedEndpoint("the phone's dial was rejected")

            override fun noteEndpointAdvertised() = recordAdvertisedEndpoint(launcher.strategy)
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
     * [identity] decides the access-point type: a network the phone should not keep is announced
     * DYNAMIC so it never stores one whose address has moved.
     */
    private fun sendWifiSecurityResponse(
        output: OutputStream,
        ssid: String,
        key: String,
        bssid: String?,
        identity: GroupIdentityStability
    ) {
        val response = WppMessages.infoResponse(ssid, key, bssid, identity)
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
        // A phone is projecting, so the switch is over and the choice has been honoured. Left
        // standing, the stamps would refuse the next session's phone on the way in.
        pendingSelectionTargetMac = null
        selectionTargetSetAt = 0L
        clearDriverSwitch()
        clearGateRefusals(logSummary = true)
        // The phone is on the network, which is the only thing that retires the stale-group count.
        pokesSinceLastAccept = 0
        escalationSpentLoggedFor = null
        if (pokeJob?.isActive == true) {
            AppLog.i("NativeAA: session is up — cancelling the poke retry loop")
        }
        pokeJob?.cancel()
        pokeJob = null
        lastPokeTriggerCredentials = null
    }

    fun stop() {
        isRunning = false
        notStartedReason = "the wireless mode was stopped"
        standingInForHfp = false
        aaReopenJob?.cancel()
        aaReopenJob = null
        aaListenerLost = false
        aaReopenAttempts = 0
        unregisterBtStateReceiver()
        resetSelectionState()
        manualPokeInFlight = false
        ownSocketCloseAt.clear()
        // Closing the carrier's channel is what unblocks a pump or a reader parked in a socket read;
        // cancelling the scope alone cannot, since that read has no suspension point. Nulled as well
        // as closed: start() builds a fresh one, and a stale reference would take the next session's
        // wake requests to a dead channel.
        zbtCarrier?.close()
        zbtCarrier = null
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
        pokeConnectingTo = null
        // Cancel before dropping the reference, for the same reason a supersede does: the socket
        // this manager just closed does not necessarily end the coroutine reading from it.
        activeHandshakeJob?.cancel()
        activeHandshakeJob = null
        activeHandshakeLink = null
        // Only the per-attempt count resets: everAcceptedAaConnection is deliberately kept, so a
        // unit that has connected before is not warned just because the manager was re-armed.
        pokesSinceLastAccept = 0
        // An arming that stood a poke down over a hands-free link and never got a session is what
        // earns a DESTRUCTIVE verdict its re-measurement. One the phone never reached says nothing.
        if (standDownReachedThisArming && !acceptedAaConnectionThisArming) {
            settings.nativeAaWakeArmingsWithoutSession =
                settings.nativeAaWakeArmingsWithoutSession + 1
        }
        acceptedAaConnectionThisArming = false
        standDownReachedThisArming = false
        // The escalated-wake budget is per arming, so it comes back with the next start(). The
        // damage verdict is not: it is a measurement of this unit and outlives every arming.
        handsFreeStandDownSince.clear()
        escalatedWakes = 0
        lastEscalatedWakeAt = 0L
        escalationSpentLoggedFor = null
        wakeProbeJob?.cancel()
        wakeProbeJob = null
        escalationProbeUntil.clear()
        radioCycles = 0
        lastRadioCycleAt = 0L
        radioCycleJob?.cancel()
        radioCycleJob = null
        radioCycleQuietUntil.clear()
        // A mode change or a user exit is a fresh start, so the next start() serves handshakes
        // again rather than inheriting a backoff the user cannot see.
        resetHandshakeBackoff()
        resetJoinRefusals()
    }
}
