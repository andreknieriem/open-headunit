package com.andrerinas.openheadunit.connection.wifi.direct

import com.andrerinas.openheadunit.connection.wifi.P2pDiscoveryNetwork
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.NetworkInfo
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeHandoffPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionNetworkDetail
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.wifi.FiveGhzChannelPolicy
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherMode
import com.andrerinas.openheadunit.connection.wifi.modes.helper.HelperStrategy
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.SoftApBssidPolicy
import com.andrerinas.openheadunit.main.MainActivity
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.utils.InterfaceMacReader
import com.andrerinas.openheadunit.utils.SystemProperties
import java.io.File
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket



class WifiDirectManager(private val context: Context) : WifiP2pManager.ConnectionInfoListener, WifiP2pManager.GroupInfoListener {

    private companion object {
        /** How many two-second rounds to spend waiting for the WiFi radio before saying it will not come on. */
        private const val MAX_WIFI_ENABLE_ATTEMPTS = 5

        /** Where a head unit's baked-in WiFi country tends to live when telephony has none. */
        private val COUNTRY_PROPERTY_KEYS = listOf(
            "ro.boot.wificountrycode",
            "persist.vendor.wifi.country",
            "ro.wifi.country",
            "persist.sys.country",
        )

        private const val MAX_NATIVE_5GHZ_CREATE_RETRIES = 4
        private const val MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES = 2
        private const val MAX_NATIVE_STANDARD_CREATE_RETRIES = 3

        /**
         * How often to repeat that this unit will not create a group.
         *
         * The same interval, and for the same reason, as `SoftApCredentialsProvider`'s no-access-point
         * report: often enough that any window of a busy unit's log carries it, rare enough that a
         * user can dismiss the banner it raises.
         */
        private const val GROUP_REFUSAL_REPORT_INTERVAL_MS = 60_000L
        private const val NATIVE_GROUP_MODE_UNKNOWN = "unknown"
        private const val NATIVE_GROUP_MODE_5GHZ_REQUESTED = "5GHz requested"
        private const val NATIVE_GROUP_MODE_24GHZ_REQUESTED = "2.4GHz requested"
        private const val NATIVE_GROUP_MODE_STANDARD_FALLBACK = "standard fallback"
        private const val NATIVE_GROUP_MODE_STANDARD_LEGACY = "standard (no 5GHz API)"
        // Native AA join recovery: if no phone joins the quiet-host group within this window,
        // tear it down and recreate a fresh one (bounded), dropping the forced 5GHz band after
        // a couple of tries. 60s not 30s — a live BT reconnect was observed taking ~35s even
        // when working, so 30s left no margin.
        private const val NATIVE_JOIN_TIMEOUT_MS = 60000L
        private const val MAX_NATIVE_JOIN_RECREATES = 4
        private const val NATIVE_FORCE_STANDARD_AFTER = 2
    }

    @Volatile private var manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private var channel: WifiP2pManager.Channel? = null
    private var isGroupOwner = false
    private var isConnected = false
    // Fields for checkStuckRetryBurst() below: track how tightly consecutive
    // CONNECTION_CHANGED broadcasts repeat, to detect a stuck retry loop.
    private var lastConnChangedElapsedMs = 0L
    private var tightBurstCount = 0
    private val burstGapMs = 800L
    private val burstTriggerCount = 5
    @Volatile private var isGroupCreatingOrCreated = false

    // Fields for the P2P interface churn check in the state receiver below: how many
    // DISABLED/ENABLED edges arrived in the current window, and how many of those landed while
    // nothing of ours was outstanding. See P2pInterfaceChurnPolicy.
    private var churnWindowStartedAtMs = 0L
    private var churnEventsInWindow = 0
    private var churnForeignEventsInWindow = 0
    private var lastChurnReportAtMs = 0L

    /**
     * When we last asked the P2P framework for something that can reload the interface, so
     * [P2pStateChangePolicy] can tell the DISABLED/ENABLED pair that follows from the user really
     * toggling WiFi Direct. Stamped by [markP2pRequest] at every such call on the bring-up path;
     * a new one that does not stamp it puts the loop back.
     */
    @Volatile private var lastP2pRequestAtMs = 0L

    /** When a Native AA bring-up last started, so a duplicate one can be refused. */
    @Volatile private var lastNativeBringUpAtMs = 0L

    // Guards against two concurrent checkGroupAndCreate() runs racing on the same teardown
    // (makeVisible() can be invoked twice back to back for one UI action). Cleared by a
    // bounded safety timeout in case a call site misses its own reset.
    @Volatile private var checkGroupAndCreateInFlight = false
    // Whether a phone has actually joined the group (not just that a group exists).
    // discoveryRunnable below re-advertises while this is false, and restarts if a
    // joined client disconnects.
    private var isClientConnected = false
    private var serverDiscoveryScheduled = false
    private val discoveryRunnable = object : Runnable {
        override fun run() {
            serverDiscoveryScheduled = false
            if (!isClientConnected) {
                // Skip while a teardown/recreate is in flight (reuse path or the stuck-retry
                // self-heal) — the group is disappearing/reforming underneath us, so a
                // discoverPeers() call here is wasted at best.
                if (!checkGroupAndCreateInFlight) {
                    startDiscovery()
                }
                handler.postDelayed(this, 10000L) // Repeat every 10s to stay visible
                serverDiscoveryScheduled = serverPolling
            }
        }
    }
    private val handler = Handler(Looper.getMainLooper())
    private var localDeviceAddress: String? = null
    private var lastKnownBssid: String? = null

    /**
     * The interface [lastKnownBssid] was read from. A P2P interface's MAC changes with the group on
     * plenty of devices, so a cached address from a different interface describes a network that no
     * longer exists and no beacon carries.
     */
    private var lastKnownBssidIface: String? = null

    /** The last SSID the BSSID source dump was printed for; group info arrives several times each. */
    private var lastBssidDumpSsid: String? = null
    private var isReceiverRegistered = false
    private var discoveredInterface: String? = null
    var discoveryNetworkListener: ((P2pDiscoveryNetwork?) -> Unit)? = null
    @Volatile var discoveryNetwork: P2pDiscoveryNetwork? = null
        private set
    private var groupOwnerIpv4: String? = null
    private var serverPolling = false
    private val serverStatus = ServerP2pStatus()
    private var serverRenamePending = false
    private var localNameRevision = 0L
    private var serverGroupMissingSince: Long? = null
    private var serverGroupRetryAt = 0L
    var visibleBringUpAllowed: () -> Boolean = { true }

    private fun publishDiscoveryNetwork(network: P2pDiscoveryNetwork?) {
        if (network == discoveryNetwork) return
        discoveryNetwork = network
        discoveryNetworkListener?.invoke(network)
    }

    private val serverGroupPoll = object : Runnable {
        override fun run() {
            if (!serverPolling || !App.provide(context).settings.usesServerWifiDirect()) return
            requestServerGroupSnapshot()
            handler.removeCallbacks(this)
            handler.postDelayed(this, 2000)
        }
    }

    private fun publishServerBadge() {
        val previous = AapService.serverWifiDirectStatus.value
        val current = serverStatus.badge
        if (previous != current) {
            AppLog.i("Auto/P2P badge: group=${current.groupAvailable}, deviceName=${current.deviceName ?: "unknown"}")
            AapService.serverWifiDirectStatus.value = current
        }
    }

    private fun recordServerLocalName(name: String?, source: String) {
        if (name.isNullOrBlank()) return
        localNameRevision++
        serverStatus.localName(name)
        AppLog.i("Auto/P2P local device name from $source: $name")
        publishServerBadge()
    }

    @SuppressLint("MissingPermission")
    private fun requestServerGroupSnapshot() {
        if (!serverPolling || serverRenamePending || checkGroupAndCreateInFlight) return
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            clearServerRadioState()
            return
        }
        val gen = generation
        val request = serverStatus.request()
        val ch = channel ?: return
        manager?.requestConnectionInfo(ch) { info ->
            if (!serverPolling || gen != generation) return@requestConnectionInfo
            manager?.requestGroupInfo(ch) { group ->
                if (!serverPolling || gen != generation || serverRenamePending || checkGroupAndCreateInFlight) return@requestGroupInfo
                if (!wifi.isWifiEnabled) {
                    clearServerRadioState()
                    return@requestGroupInfo
                }
                val ownsGroup = group?.isGroupOwner == true && info?.isGroupOwner == true && info.groupFormed
                if (!serverStatus.group(request, ownsGroup, SystemClock.elapsedRealtime())) return@requestGroupInfo
                publishServerBadge()
                isConnected = ownsGroup
                isGroupOwner = ownsGroup
                groupOwnerIpv4 = info?.groupOwnerAddress?.hostAddress
                val hadClient = isClientConnected
                isClientConnected = ownsGroup && group?.clientList?.isNotEmpty() == true
                // Badge hysteresis must never keep an old network alive for the scanner.
                publishDiscoveryNetwork(if (group != null && ownsGroup)
                    P2pDiscoveryNetwork.fromGroup(group.`interface`, groupOwnerIpv4, isClientConnected) else null)
                if (group != null && ownsGroup) {
                    serverGroupMissingSince = null
                    ConnectionStageTracker.report(ConnectionStage.WAITING_FOR_PHONE)
                    val frequency = WifiDirectCompat.getGroupFrequency(group)
                    WifiBandCapability.reportSessionFrequency(frequency)
                    if (frequency > 0) ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(
                        frequency,
                        if (WifiP2pChannelPolicy.isClientUnfriendly(frequency))
                            ConnectionNetworkDetail.Note.CLIENT_UNFRIENDLY_CHANNEL
                        else ConnectionNetworkDetail.Note.NONE
                    ))
                } else {
                    val now = SystemClock.elapsedRealtime()
                    if (serverGroupMissingSince == null) serverGroupMissingSince = now
                    if (now - serverGroupMissingSince!! >= 10_000 && now >= serverGroupRetryAt &&
                        !checkGroupAndCreateInFlight && !App.provide(context).commManager.isBusy) {
                        serverGroupRetryAt = now + 20_000
                        makeVisible()
                    }
                }
                if (isClientConnected) {
                    handler.removeCallbacks(discoveryRunnable)
                    serverDiscoveryScheduled = false
                    if (!hadClient) manager?.stopPeerDiscovery(ch, null)
                } else if (ownsGroup && !serverDiscoveryScheduled) startDiscoveryLoop()
            }
        }
    }

    private fun clearServerRadioState() {
        serverStatus.clearGroup()
        publishServerBadge()
        publishDiscoveryNetwork(null)
        groupOwnerIpv4 = null
        isConnected = false
        isGroupOwner = false
        isClientConnected = false
        isGroupCreatingOrCreated = false
        handler.removeCallbacks(discoveryRunnable)
        serverDiscoveryScheduled = false
        ConnectionStageTracker.reportNetwork(null)
    }
    private var nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
    private var native5GhzBandMismatchRetries = 0

    /**
     * The band the current Native AA group was asked for. Only 5 GHz can be mismatched: a group
     * put on 2.4 GHz on purpose is on the band it was asked for, so it must not be remade.
     */
    private var nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED

    /**
     * True while a 5 GHz operating-channel restriction is in place on a pre-Q device. It lives in the
     * supplicant rather than here, so it has to be cleared deliberately - see [WifiP2pChannelCompat].
     */
    @Volatile
    private var legacyChannelRestrictionApplied = false

    /**
     * How far down [P2pOperatingChannelPolicy.attemptChannels] this bring-up has got.
     *
     * An index rather than the single "already fell back" flag it used to be, because the ladder
     * now names 2.4 GHz on its way down instead of going straight to letting the driver choose. A
     * unit that cannot host a group owner on the band it was given a frequency list for fails
     * outright, so each rung has to be offered in turn and the restriction cleared only once they
     * are spent.
     */
    private var legacyChannelAttempt = 0

    /**
     * True once this unit has let a `setWifiP2pChannels` request go unanswered.
     *
     * The request is the only pre-Q band lever, and on a driver that reloads the P2P interface to
     * apply it the pending listener is dropped, so [WifiP2pChannelCompat] has to time out instead.
     * Asking again costs that timeout on every bring-up and cannot produce a different answer, so
     * the rest of the session goes straight to creating the group on the driver's own channel.
     * Cleared by [stop] with the ladder, so a mode change asks once more.
     */
    private var legacyChannelRequestUnanswered = false
    /** The regulatory domain dump is a fact about the hardware, so once per process is enough. */
    private var countrySourceDumped = false

    /**
     * When the group refusal was last reported, so a unit refusing every attempt says so at a
     * readable rate rather than on each one.
     *
     * The retry ladder gives up several times a minute while the phone keeps re-dialling, and each
     * report both writes an ERROR line and re-stamps the standing record. Re-stamping is what brings
     * the main-screen banner back after a dismissal, so at that rate the user could not dismiss it
     * at all. Reset by [stop] rather than by a bring-up, so one refusal is reported per mode.
     */
    private var lastGroupRefusalReportAtMs = 0L

    /**
     * Set once a pinned 5 GHz channel has failed its whole retry budget on API 29+.
     *
     * A pinned frequency is forced all the way down to wpa_supplicant, which fails the group rather
     * than choosing elsewhere, so a channel this unit will not host costs the connection and not
     * just the channel. From here on the bring-up asks for the band instead. Cleared by [stop] and
     * not by [startNativeAaQuietHost], for the same reason [legacyChannelAttempt] is: the handshake
     * refreshes every ten seconds and each refresh lands back in that method.
     */
    @Volatile
    private var pinnedChannelAbandoned = false

    /**
     * The frequency this group was asked for, or 0 where the band was asked for instead.
     *
     * The request rather than the setting, because the setting can be changed mid-session and the
     * line that reports it sits beside the frequency the group actually came up on - which is the
     * one comparison this whole choice exists to let a reader make.
     */
    @Volatile
    private var nativeRequestedFrequency = 0

    /** The identity the current Native AA group was asked for, so the read-back can name a mismatch. */
    @Volatile
    private var nativeRequestedIdentity: P2pGroupIdentity? = null

    /**
     * When the Native AA createGroup was issued and has not yet answered with a group, or 0.
     * [refreshNativeCredentials] reads it so a refresh landing mid-create waits instead of asking
     * for a second group underneath the first.
     */
    @Volatile
    private var nativeCreateRequestedAtMs = 0L

    /**
     * Bring-ups spent asking a radio that is off to come on, so one that never will stops asking.
     *
     * Unbounded, the two-second retry below re-entered forever on a unit whose WiFi the platform
     * will not switch on, and said nothing after the first pass.
     */
    private var wifiEnableAttempts = 0

    /**
     * When a createGroup of ours was accepted and no group has arrived since, or 0.
     *
     * The framework holds that creation for two minutes and answers BUSY to every create and remove
     * in the meantime, so this is what separates "the platform is still finishing our own request"
     * from a radio that will not host a group at all.
     */
    @Volatile
    private var acceptedCreateWithoutGroupSinceMs = 0L

    /** The [acceptedCreateWithoutGroupSinceMs] a cancel has already been spent on, so one is spent per stuck create. */
    private var wedgeCancelSpentForStampMs = 0L

    /** What the create behind [acceptedCreateWithoutGroupSinceMs] asked for, so a cancel knows what to drop next. */
    private var stuckCreateVariant = P2pCreateWedgePolicy.Variant.BANDED

    /** Cancels spent this bring-up. Reset where [nativeRecreateCount] is. */
    private var stuckCreateCancels = 0

    /**
     * Bumped by every create, cancel and stop, so a group-info retry loop posted under an earlier
     * create cannot run its FATAL and reset the counter underneath the one now in flight.
     */
    private var groupInfoEpoch = 0

    /** Whether the platform is holding a create of ours that has not produced a group. */
    val isAcceptedCreatePending: Boolean
        get() = msSinceAcceptedCreate() != null

    /** Re-asks once a claimed create has had its grace, so a refresh with nothing behind it is not the end of the road. */
    private val nativeRefreshRecheck = Runnable { refreshNativeCredentials() }

    /**
     * Whether a group has been asked for and has not answered yet. Callers that decide something
     * about "the network" before the group can answer need this rather than [hasLiveGroup], which
     * is false for the whole create.
     */
    val isCreatingGroup: Boolean
        get() = NativeRefreshPolicy.createInFlight(nativeCreateRequestedAtMs, SystemClock.elapsedRealtime())

    /**
     * Claims the create window. The real createGroup() is several async hops from the call that
     * asks for one, and until the stamp is set a refresh landing in between sees no group and no
     * create, remakes one underneath this one, and the two fight over BUSY until the phone is
     * handed a network that has already been replaced. Every path that abandons a create releases
     * it again, and the create calls restamp it to an accurate value once they issue the request.
     */
    fun claimNativeCreateWindow(why: String) {
        nativeCreateRequestedAtMs = SystemClock.elapsedRealtime()
        // Standing the station down reloads the P2P interface just as our own group work does, and
        // it is claimed here before anything is asked of the framework. Without this stamp the
        // edges it provokes look like they came from another app.
        markP2pRequest()
        AppLog.i("WifiDirectManager: a Native AA group create is claimed ($why); a refresh in the next ${NativeRefreshPolicy.CREATE_GRACE_MS / 1000}s waits for it.")
    }

    /**
     * A create the platform accepted. It owns the P2P state machine from here until a group arrives.
     * Stamped on uptimeMillis because the framework's two-minute timeout is a Handler delay on that
     * clock, and elapsedRealtime runs ahead of it through a doze.
     */
    private fun noteAcceptedCreate(variant: P2pCreateWedgePolicy.Variant) {
        acceptedCreateWithoutGroupSinceMs = SystemClock.uptimeMillis()
        wedgeCancelSpentForStampMs = 0L
        stuckCreateVariant = variant
        groupInfoEpoch++
        groupInfoRetries = 0
    }

    /** How long the platform has been holding a create of ours with no group to show for it, or null. */
    private fun msSinceAcceptedCreate(): Long? = acceptedCreateWithoutGroupSinceMs
        .takeIf { it != 0L }
        ?.let { SystemClock.uptimeMillis() - it }

    /**
     * Whether this BUSY should be answered by cancelling the creation the platform is still holding.
     *
     * See [P2pCreateWedgePolicy]: inside that window every create and remove is refused, so the
     * ladder below cannot win and only the cancel can.
     */
    private fun shouldCancelStuckCreate(reason: Int): Boolean =
        P2pCreateWedgePolicy.stepAfterBusy(
            reason = reason,
            msSinceAcceptedCreate = msSinceAcceptedCreate(),
            cancelAlreadySpent = wedgeCancelSpentForStampMs == acceptedCreateWithoutGroupSinceMs,
        ) == P2pCreateWedgePolicy.Step.CANCEL_FIRST

    /**
     * Drop the stuck creation. [onCancelled] asks for the next variant; [onRefused] repeats the rung
     * that was refused, and the cancel may be tried again on the next BUSY.
     */
    private fun cancelStuckCreate(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        onCancelled: () -> Unit,
        onRefused: () -> Unit,
    ) {
        val heldMs = msSinceAcceptedCreate() ?: 0L
        wedgeCancelSpentForStampMs = acceptedCreateWithoutGroupSinceMs
        groupInfoEpoch++
        AppLog.w(
            "WifiDirectManager: a group this unit accepted ${heldMs}ms ago never formed, and it " +
                "refuses every new one until it gives up on that by itself " +
                "(${P2pCreateWedgePolicy.FRAMEWORK_CREATE_TIMEOUT_MS / 1000}s). Cancelling it instead of waiting."
        )
        markP2pRequest()
        var answered = false
        val unanswered = Runnable {
            if (answered) return@Runnable
            answered = true
            AppLog.w("WifiDirectManager: cancelling the stuck group creation went unanswered; asking again anyway.")
            wedgeCancelSpentForStampMs = 0L
            onRefused()
        }
        handler.postDelayed(unanswered, P2pCreateWedgePolicy.CANCEL_ANSWER_TIMEOUT_MS)
        mgr.cancelConnect(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (answered) return
                answered = true
                handler.removeCallbacks(unanswered)
                AppLog.i("WifiDirectManager: the stuck group creation was cancelled; asking for a group differently.")
                acceptedCreateWithoutGroupSinceMs = 0L
                // Re-claimed so a refresh landing in the 500 ms below waits rather than remaking.
                claimNativeCreateWindow("the stuck create was cancelled")
                handler.postDelayed({ onCancelled() }, 500L)
            }
            override fun onFailure(reason: Int) {
                if (answered) return
                answered = true
                handler.removeCallbacks(unanswered)
                AppLog.w("WifiDirectManager: cancelling the stuck group creation was refused (${getP2pErrorString(reason)}); retrying anyway.")
                wedgeCancelSpentForStampMs = 0L
                handler.postDelayed({ onRefused() }, 2000L)
            }
        })
    }

    /**
     * The create after a cancelled one. The same request goes back accepted and hanging, so each
     * cancel drops something it asked for: the band first, then the name. Measured on a unit where
     * the banded create was accepted four times in thirty minutes and never once formed a group.
     */
    private fun createAfterCancelledStuckCreate(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        stuckCreateCancels++
        when (P2pCreateWedgePolicy.nextAfterCancel(stuckCreateVariant, stuckCreateCancels)) {
            P2pCreateWedgePolicy.Variant.NAMED_NO_BAND -> {
                AppLog.w("WifiDirectManager: the create that never formed asked for a band, so the next one leaves the band to the platform.")
                standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            }
            P2pCreateWedgePolicy.Variant.FRAMEWORK_PROFILE -> {
                namedFallbackRefused = true
                AppLog.w("WifiDirectManager: the create that never formed named the group, so the next one asks for the platform's own profile.")
                standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            }
            P2pCreateWedgePolicy.Variant.BANDED -> createQuietGroup(0)
            null -> {
                AppLog.e(
                    "WifiDirectManager: this unit accepts a group request and never forms the group, " +
                        "on every kind of request it was offered ($stuckCreateCancels cancelled). " +
                        "Nothing more is asked for until something asks for a group again."
                )
                reportGroupRefusal("accepted but never formed")
                isGroupCreatingOrCreated = false
                releaseNativeCreateWindow("every create variant was accepted and never formed")
            }
        }
    }

    /**
     * Whether a create the platform has just accepted belongs to a session that is already over.
     *
     * [stop] cannot cancel a createGroup already handed to the framework, so its success lands
     * anyway and would otherwise claim group ownership and deliver credentials for a group nothing
     * is managing - measured as a phone told to join a network that was replaced 891ms later.
     *
     * The group it made goes with it, unless a later bring-up has started: that one owns the P2P
     * state now and removes what it finds before it creates, so removing here would take its group
     * instead of ours.
     */
    @SuppressLint("MissingPermission")
    private fun createAbandonedAfterStop(gen: Int, mgr: WifiP2pManager, ch: WifiP2pManager.Channel): Boolean {
        if (!supersededByStop(gen, "the Native AA group create")) return false
        if (isGroupCreatingOrCreated) {
            AppLog.i("WifiDirectManager: a later bring-up owns the group now, so the abandoned create leaves it alone.")
            return true
        }
        markP2pRequest()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                AppLog.i("WifiDirectManager: the group that create made was removed with it.")
            }
            override fun onFailure(reason: Int) {
                AppLog.w("WifiDirectManager: the group that create made could not be removed (${getP2pErrorString(reason)}).")
            }
        })
        return true
    }

    /** Releases a claim whose create was abandoned. Public because a launcher can abandon one too. */
    fun releaseNativeCreateWindow(why: String) {
        if (nativeCreateRequestedAtMs == 0L) return
        nativeCreateRequestedAtMs = 0L
        AppLog.i("WifiDirectManager: the claimed create window is released ($why).")
    }

    /**
     * Said before every teardown of the current group. A rung that removed a group without saying
     * so left the handshake still holding its name, and the phone was handed a network that no
     * longer existed and scanned for it until it gave up.
     */
    private fun invalidateNativeGroupCredentials(why: String) {
        lastKnownBssid = null
        lastKnownBssidIface = null
        forgetPerGroupKeys()
        credentialsEpoch++
        onNativeGroupInvalidated?.invoke()
        AppLog.i("WifiDirectManager: the group is being removed ($why), so its credentials are no longer handed out.")
    }

    /** The SSID the identity read-back was last printed for; group info arrives several times each. */
    private var lastIdentityReportSsid: String? = null

    /**
     * The SSID whose identity has been compared to the last group's, once a BSSID was readable.
     * Separate from the report key because the first callbacks for a group often carry no address
     * yet, and comparing a group against itself on the next callback would call it stable.
     */
    private var nativeIdentityAssessedSsid: String? = null

    /** The verdict for the current group, handed to the phone beside its credentials. */
    @Volatile
    private var nativeIdentityStability = GroupIdentityStability.UNPROVEN

    /**
     * Bumped by [stop]. Every P2P callback that continues into another framework call captures this
     * first and gives up if it has moved, because [stop] can cancel posted runnables but nothing can
     * cancel an `ActionListener` the framework is already holding - and those continuations create
     * groups. Without the fence, a user exit that lands mid-recovery removes the group and then a
     * continuation puts a new one up with no receiver, no watchdog and nobody expecting it, which is
     * exactly what the exit was for.
     */
    @Volatile
    private var generation = 0

    /** One token per checkGroupAndCreate run, so a stale safety timer cannot clear a later run's guard. */
    private var checkGroupAndCreateToken = 0

    /**
     * Bumped whenever the group these credentials describe stops being the current one.
     *
     * The delivery thread below waits up to 15s for an IP and then hands the SSID, passphrase and
     * BSSID it captured at spawn to the handshake. Nothing cancels it, and its IP fallback means it
     * always produces something - so a group torn down mid-wait still delivers, overwriting the live
     * group's credentials with a dead group's. The phone then joins a network that no longer exists
     * and sits on "Obtaining IP address" forever, having been told the truth about the wrong group.
     */
    @Volatile
    private var credentialsEpoch = 0

    /** The group the join watchdog is currently armed for, so repeat callbacks cannot push it out. */
    private var nativeJoinWatchdogSsid: String? = null

    /**
     * True when [stop] has run since [gen] was captured, meaning this continuation belongs to a
     * session that is over and must not create anything.
     */
    private fun supersededByStop(gen: Int, what: String): Boolean {
        if (gen == generation) return false
        AppLog.i("WifiDirectManager: $what abandoned - the manager was stopped while it was in flight.")
        return true
    }

    /** Says the frequency is unreadable once per group, not once per group-info callback. */
    private var lastFrequencyUnreadableSsid: String? = null

    /**
     * SSID of the last group reported as being on a channel most phones cannot join, so the several
     * [onGroupInfoAvailable] callbacks a single group produces are said once.
     *
     * The SSID and not the interface or the BSSID: Android generates a fresh DIRECT-xy-… per group,
     * it is populated on the very first callback, and it is never privacy-masked. `interface` is
     * null until an IP is up and the BSSID is often 02:00:00:00:00:00 here, so either would both
     * split one group across two identities and merge two groups into one.
     */
    private var lastUnfriendlyChannelSsid: String? = null
    private var lastCoexistenceKey: String? = null
    private var lastNativeGroupStatusMessage: String? = null

    /**
     * Forgets every "said once per group" key, so the next group says its lines again.
     *
     * Those keys are the SSID, which used to change on every create and so reset them for free.
     * A kept identity gives a recreated group the same SSID as the one it replaces, and without
     * this the join watchdog would not re-arm for it and its BSSID dump would never print. The
     * session latch is per group too: a replacement has not carried anything yet.
     */
    private fun forgetPerGroupKeys() {
        nativeGroupHostedSession = false
        lastBssidDumpSsid = null
        lastIdentityReportSsid = null
        nativeIdentityAssessedSsid = null
        nativeIdentityStability = GroupIdentityStability.UNPROVEN
        nativeJoinWatchdogSsid = null
        lastFrequencyUnreadableSsid = null
        lastUnfriendlyChannelSsid = null
        lastCoexistenceKey = null
    }

    // Native AA join recovery state. The watchdog fires if the phone never joins our quiet-host
    // group; nativeRecreateCount bounds how many times we recreate before giving up.
    private var nativeRecreateCount = 0

    /** What the last profile purge did, so the group's read-back line says whether it worked. */
    private var lastPersistentPurgeVerdict: String? = null

    /**
     * True once a projection session has run over the current group. The join watchdog recreates
     * a group no phone joined, and a recreate is what moves the group's address out from under
     * the profile the phone saved, so a group that has already worked is never put through it.
     */
    private var nativeGroupHostedSession = false

    /** Records that a projection session ran over this group. */
    fun noteSessionHosted() {
        if (nativeGroupHostedSession) return
        nativeGroupHostedSession = true
        AppLog.i("WifiDirectManager: this group has carried a session, so the join watchdog will not recreate it if the phone leaves.")
    }

    /**
     * Whether a P2P group we own is up right now, from the state CONNECTION_CHANGED keeps
     * current. Answered from memory because the caller has to decide on the spot; the
     * framework's own answer follows from [refreshNativeCredentials], which the caller asks for.
     */
    val hasLiveGroup: Boolean get() = isConnected && isGroupOwner

    private val nativeJoinWatchdog = Runnable {
        if (isNativeSessionConnected?.invoke() == true) {
            // Native joins are out-of-band over Bluetooth, not P2P invitation, so clientList (and
            // isClientConnected) can stay empty forever even on a fully working session.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a session is already connected — cancelling recovery, not tearing down a working connection.")
            cancelNativeJoinWatchdog()
            nativeRecreateCount = 0
            return@Runnable
        }
        if (isNativeHandshakeInFlight?.invoke() == true) {
            // A handshake is exchanging credentials right now, or the phone is still joining on
            // credentials we just handed it; recreating here would hand out a new SSID mid-flight.
            AppLog.i("WifiDirectManager: Native AA join watchdog fired but a Bluetooth handshake or handoff is in flight — deferring recovery.")
            armNativeJoinWatchdog()
            return@Runnable
        }
        if (nativeGroupHostedSession) {
            // A proven group is kept, not recreated - until the phone stops answering pokes, when
            // the network it saved is not working either. See ProvenGroupStalePolicy.
            val pokes = unansweredPokeCount?.invoke() ?: 0
            if (!ProvenGroupStalePolicy.isStale(
                    groupHasHostedSession = true,
                    sessionConnected = false,
                    handshakeInFlight = false,
                    unansweredPokes = pokes)) {
                armNativeJoinWatchdog()
                return@Runnable
            }
            AppLog.w("WifiDirectManager: Native AA — the phone has ignored $pokes wake pokes since this group last carried a session, so the network it saved is not reaching it either; recreating the group.")
            recoverNativeGroup("the phone stopped returning to a group it had joined", ignoreClientList = true)
            return@Runnable
        }
        if (isClientConnected) return@Runnable
        recoverNativeGroup("no phone joined within ${NATIVE_JOIN_TIMEOUT_MS / 1000}s")
    }

    private var onCredentialsReady: ((ssid: String, psk: String, ip: String, bssid: String, identity: GroupIdentityStability) -> Unit)? = null
    // Set by AapService: whether NativeAaHandshakeManager has a live handshake in progress *or*
    // a delivered handoff still settling (the phone associating/doing DHCP after Type 3), so the
    // join watchdog/self-heal never tears the group down mid-exchange or mid-join.
    private var isNativeHandshakeInFlight: (() -> Boolean)? = null
    // Set by AapService: whether a real AA session is connected - isClientConnected can't tell
    // that apart from nobody joining (see nativeJoinWatchdog above).
    private var isNativeSessionConnected: (() -> Boolean)? = null
    // Set by WifiLauncherNative: whether the phone has ever opened the Android Auto Bluetooth
    // channel on this unit. Null where nothing wired it, which reads as yes, so an unwired route
    // recovers exactly as it did before.
    private var isPhoneEverOpenedAaChannel: (() -> Boolean)? = null
    // Set by WifiLauncherNative: wake pokes sent since the phone last opened the Android Auto
    // channel. Null where nothing wired it, which reads as zero, so an unwired route never treats
    // a proven group as stale.
    private var unansweredPokeCount: (() -> Int)? = null
    // Set by AapService: called right before a native group is torn down, to invalidate any
    // not-yet-captured credentials in NativeAaHandshakeManager.
    private var onNativeGroupInvalidated: (() -> Unit)? = null

    fun setCredentialsListener(callback: (String, String, String, String, GroupIdentityStability) -> Unit) {
        this.onCredentialsReady = callback
    }

    fun setNativeHandshakeStateProvider(provider: () -> Boolean) {
        this.isNativeHandshakeInFlight = provider
    }

    fun setPhoneEverOpenedAaChannelProvider(provider: () -> Boolean) {
        this.isPhoneEverOpenedAaChannel = provider
    }

    fun setUnansweredPokeCountProvider(provider: () -> Int) {
        this.unansweredPokeCount = provider
    }

    fun setNativeSessionConnectedProvider(provider: () -> Boolean) {
        this.isNativeSessionConnected = provider
    }

    fun setNativeGroupInvalidatedListener(callback: () -> Unit) {
        this.onNativeGroupInvalidated = callback
    }



    private fun markP2pRequest() {
        lastP2pRequestAtMs = System.currentTimeMillis()
    }

    /**
     * Retire both group records: this unit just hosted a group, which disproves each of them.
     *
     * Shared by the two createGroup success paths because only the standard one used to clear, so a
     * unit that succeeded on a banded request kept a standing record and its banner for good.
     */
    private fun noteGroupFormed() {
        ConnectionIssues.clear(context, ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED)
        ConnectionIssues.clear(context, ConnectionIssue.WIFI_DIRECT_STACK_CYCLED)
        ConnectionIssues.clear(context, ConnectionIssue.WIFI_RADIO_OFF)
    }

    /**
     * Count this state change, and say so once a minute when something else is cycling the stack.
     *
     * Edges arriving while a request of ours could still be bouncing the interface are counted but
     * not attributed, so this can only ever accuse somebody else. The window is closed and judged
     * on the first edge after it expires rather than on a timer: there is no churn to report when
     * no broadcasts are arriving.
     */
    private fun noteP2pStateChange() {
        val now = System.currentTimeMillis()
        val elapsed = now - churnWindowStartedAtMs
        if (churnWindowStartedAtMs == 0L || elapsed >= P2pInterfaceChurnPolicy.WINDOW_MS || elapsed < 0L) {
            if (P2pInterfaceChurnPolicy.isForeignChurn(churnForeignEventsInWindow) &&
                P2pInterfaceChurnPolicy.shouldReport(now, lastChurnReportAtMs)
            ) {
                lastChurnReportAtMs = now
                AppLog.w(
                    "WifiDirectManager: this unit's WiFi Direct reported " +
                        "$churnForeignEventsInWindow off/on state changes in the last " +
                        "${P2pInterfaceChurnPolicy.WINDOW_MS / 1000}s with no request of ours " +
                        "outstanding, so another app on this unit is cycling it. No group can be " +
                        "created while that continues."
                )
                ConnectionIssues.raise(context, ConnectionIssue.WIFI_DIRECT_STACK_CYCLED)
            }
            churnWindowStartedAtMs = now
            churnEventsInWindow = 0
            churnForeignEventsInWindow = 0
        }
        churnEventsInWindow++
        if (P2pInterfaceChurnPolicy.countsAsForeign(now, lastP2pRequestAtMs)) {
            churnForeignEventsInWindow++
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiManager.WIFI_STATE_CHANGED_ACTION -> {
                    if (serverPolling && intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE, -1) in
                        listOf(WifiManager.WIFI_STATE_DISABLING, WifiManager.WIFI_STATE_DISABLED)) {
                        clearServerRadioState()
                    }
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    noteP2pStateChange()
                    if (P2pInterfaceChurnPolicy.shouldLogEachBroadcast(churnEventsInWindow)) {
                        AppLog.i("WifiDirectManager: WIFI_P2P_STATE_CHANGED_ACTION state=$state")
                    }
                    if (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED) {
                        val appSettings = App.provide(context).settings
                        val commManager = App.provide(context).commManager
                        val isConnectingOrConnected = commManager.isConnected ||
                            commManager.connectionState.value is CommManager.ConnectionState.Connecting

                        val busy = isConnected || isConnectingOrConnected || isGroupCreatingOrCreated
                        if (P2pStateChangePolicy.shouldStartBringUp(busy, isCreatingGroup, System.currentTimeMillis(), lastP2pRequestAtMs)) {
                            if (appSettings.usesVisibleWifiDirect()) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting WiFi Direct visibility")
                                makeVisible()
                            } else if (appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE) {
                                AppLog.i("WifiDirectManager: P2P enabled, auto-starting Native AA quiet host")
                                startNativeAaQuietHost()
                            }
                        }
                    } else if (P2pStateChangePolicy.shouldResetOnDisable(System.currentTimeMillis(), lastP2pRequestAtMs)) {
                        isGroupCreatingOrCreated = false
                        isConnected = false
                        isClientConnected = false
                        publishDiscoveryNetwork(null)
                        if (App.provide(context).settings.usesServerWifiDirect()) {
                            serverStatus.invalidateRequests()
                            requestServerGroupSnapshot()
                        }
                        cancelNativeJoinWatchdog()
                        nativeRecreateCount = 0
                    }
                }

                WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE, WifiP2pDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_DEVICE)
                    }
                    device?.let {
                        if (App.provide(context).settings.wifiConnectionMode != WifiLauncherMode.NATIVE) {
                            AppLog.i("WifiDirectManager: Local name: ${it.deviceName}, Address: ${it.deviceAddress}")
                        }
                        if (!App.provide(context).settings.usesServerWifiDirect()) {
                            AapService.wifiDirectName.value = it.deviceName
                        } else if (serverPolling) {
                            recordServerLocalName(it.deviceName, "device broadcast")
                        }
                        localDeviceAddress = it.deviceAddress
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        AppLog.i("WifiDirectManager: Connected. Requesting info...")
                        checkStuckRetryBurst()
                        // [FIX] Pre-fetch localDeviceAddress here so it's ready before
                        // onGroupInfoAvailable fires — reduces race condition window.
                        WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                            if (localDeviceAddress == null || localDeviceAddress == "00:00:00:00:00:00" || localDeviceAddress == "02:00:00:00:00:00") {
                                AppLog.d("WifiDirectManager: Pre-fetched localDeviceAddress on connect: $address")
                                localDeviceAddress = address
                            }
                        }
                        if (App.provide(context).settings.usesServerWifiDirect()) requestServerGroupSnapshot()
                        else manager?.requestConnectionInfo(channel, this@WifiDirectManager)
                        AapService.scanningState.value = false
                    } else {
                        isConnected = false
                        isClientConnected = false
                        publishDiscoveryNetwork(null)
                        if (App.provide(context).settings.usesServerWifiDirect()) {
                            // Invalidate older callbacks immediately; confirm group loss separately.
                            serverStatus.invalidateRequests()
                            requestServerGroupSnapshot()
                        }
                        lastNativeGroupStatusMessage = null
                        ConnectionStageTracker.reportNetwork(null)
                        isGroupCreatingOrCreated = false
                        cancelNativeJoinWatchdog()
                    }
                }
            }
        }
    }

    init {
        try {
            if (context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
                AppLog.i("WifiDirectManager: Device supports WiFi Direct. Initializing...")
                manager?.let { mgr ->
                    channel = mgr.initialize(context, context.mainLooper, null)

                    WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                        AppLog.i("WifiDirectManager: requestDeviceInfo success: $address")
                        localDeviceAddress = address
                    }

                    registerReceiverIfNeeded()
                } ?: run {
                    AppLog.e("WifiDirectManager: WIFI_P2P_SERVICE manager is NULL!")
                }
            } else {
                AppLog.e("WifiDirectManager: Device does NOT report FEATURE_WIFI_DIRECT!")
            }
        } catch (e: SecurityException) {
            AppLog.w("WifiDirectManager: WiFi Direct unavailable — permission denied: ${e.message}")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Unexpected error in init", e)
        }
    }

    // Self-heals a stuck PROV-DISC retry loop (no AAP session ever connects, so nothing
    // triggers checkGroupAndCreate()'s own teardown) by detecting how tightly
    // CONNECTION_CHANGED repeats: ~200-300ms apart when stuck vs 1.1-1.9s on a real connection.
    @SuppressLint("MissingPermission")
    private fun checkStuckRetryBurst() {
        val appSettings = App.provide(context).settings
        if (appSettings.usesServerWifiDirect() && App.provide(context).commManager.isBusy) return
        val isHelperP2p = appSettings.usesVisibleWifiDirect()
        val isNative = appSettings.wifiConnectionMode == WifiLauncherMode.NATIVE
        if ((!isHelperP2p && !isNative) || !isGroupOwner) {
            tightBurstCount = 0
            return
        }

        val now = SystemClock.elapsedRealtime()
        val gap = now - lastConnChangedElapsedMs
        lastConnChangedElapsedMs = now

        tightBurstCount = if (gap in 1 until burstGapMs) tightBurstCount + 1 else 1

        if (tightBurstCount >= burstTriggerCount) {
            AppLog.w("WifiDirectManager: Detected $tightBurstCount CONNECTION_CHANGED repeats <${burstGapMs}ms apart — stuck retry loop against an already-consumed group. Self-healing.")
            tightBurstCount = 0
            if (isNative) {
                if (isNativeHandshakeInFlight?.invoke() == true) {
                    AppLog.i("WifiDirectManager: Native AA stuck-retry-burst detected but a Bluetooth handshake is in flight — skipping recovery.")
                    return
                }
                // Native quiet-host has no discovery loop; recreate the group (bounded), the
                // same class of self-heal as the helper path.
                recoverNativeGroup("stuck PROV-DISC retry loop")
                return
            }
            val mgr = manager
            val ch = channel
            val gen = generation
            if (mgr != null && ch != null) {
                mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() { if (gen == generation) createNewGroup(0) }
                    override fun onFailure(reason: Int) {
                        if (gen != generation) return
                        AppLog.w("WifiDirectManager: removeGroup during stuck-loop self-heal failed: ${getP2pErrorString(reason)}")
                        createNewGroup(0)
                    }
                })
            }
        }
    }

    private fun registerReceiverIfNeeded() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            }
            ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            isReceiverRegistered = true
            AppLog.d("WifiDirectManager: BroadcastReceiver registered.")
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Failed to register receiver", e)
        }
    }

    @SuppressLint("MissingPermission")
    override fun onConnectionInfoAvailable(info: WifiP2pInfo) {
        val serverP2p = App.provide(context).settings.usesServerWifiDirect()
        if (serverP2p) {
            requestServerGroupSnapshot()
            return
        }
        if (info.groupFormed) {
            isConnected = true
            AapService.scanningState.value = false
            isGroupOwner = info.isGroupOwner

            val goIp = info.groupOwnerAddress?.hostAddress ?: "unknown"
            AppLog.i("WifiDirectManager: Group formed. Owner: $isGroupOwner, GO IP: $goIp")
            ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)

            if (isGroupOwner) {
                // [FIX] requestDeviceInfo is async — call requestGroupInfo only AFTER the callback
                // fires so that localDeviceAddress is guaranteed to be set before onGroupInfoAvailable
                // runs. This eliminates the race condition that caused empty BSSIDs on Android 12+.
                WifiDirectCompat.requestDeviceInfo(manager, channel) { address ->
                    AppLog.i("WifiDirectManager: Updated localDeviceAddress via requestDeviceInfo: $address")
                    localDeviceAddress = address
                    manager?.requestGroupInfo(channel, this@WifiDirectManager)
                }
                // Fallback: if requestDeviceInfo is not supported (< API 29), call directly
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                    manager?.requestGroupInfo(channel, this)
                }
            } else if (info.groupOwnerAddress != null) {
                Thread {
                    var socket: Socket? = null
                    try {
                        AppLog.i("WifiDirectManager: Pinging Phone (GO) at $goIp to announce tablet...")
                        socket = Socket()
                        socket.connect(InetSocketAddress(info.groupOwnerAddress, 5289), 2000)
                    } catch (e: Exception) {
                        AppLog.w("WifiDirectManager: Ping to GO failed: ${e.message}")
                    } finally {
                        try { socket?.close() } catch (e: Exception) {}
                    }
                }.start()
            }
        } else {
            AppLog.d("WifiDirectManager: onConnectionInfoAvailable: group not formed yet")
            isConnected = false
            isGroupOwner = false
        }
    }

    private var groupInfoRetries = 0

    @SuppressLint("MissingPermission")
    override fun onGroupInfoAvailable(group: WifiP2pGroup?) {
        val appSettings = App.provide(context).settings
        if (appSettings.usesServerWifiDirect()) {
            requestServerGroupSnapshot()
            return
        }
        if (group != null) {
            // [FIX] Check if Location Services (GPS) are enabled.
            // On Android 10+, BSSID is often masked if GPS is OFF.
            try {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val isGpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                val isNetworkEnabled = lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
                AppLog.i("WifiDirectManager: System Location Check: GPS=$isGpsEnabled, Network=$isNetworkEnabled")
                if (!isGpsEnabled && !isNetworkEnabled) {
                    AppLog.w("WifiDirectManager: WARNING - Location Services are DISABLED. BSSID will likely be masked (00:00...)!")
                }
            } catch (e: Exception) {
                AppLog.w("WifiDirectManager: Failed to check Location Services status: ${e.message}")
            }

            groupInfoRetries = 0
            // A group arrived, so nothing of ours is left half-created for the framework to hold.
            acceptedCreateWithoutGroupSinceMs = 0L
            wedgeCancelSpentForStampMs = 0L
            stuckCreateCancels = 0
            val ssid = group.networkName
            val psk = group.passphrase ?: ""
            val isOwner = group.isGroupOwner

            // [FIX] Track whether a phone client has actually joined our group.
            // If we are the Group Owner and the client list is empty, no phone has connected yet.
            // If the client list becomes non-empty, a phone joined — stop the discovery loop.
            // If the client list becomes empty again (phone disconnected), restart the loop.
            if (isOwner) {
                val clients = group.clientList
                val hadClient = isClientConnected
                isClientConnected = clients != null && clients.isNotEmpty()
                if (isClientConnected) {
                    // A phone joined — stop the native join watchdog and reset its budget.
                    cancelNativeJoinWatchdog()
                    nativeRecreateCount = 0
                }
                if (hadClient && !isClientConnected) {
                    // [BUG_FIX] Never rediscover on the Native AA path: it is a *quiet* host, the
                    // phone finds us by SSID from the credentials handed over Bluetooth, and
                    // discoverPeers() takes the group owner off-channel every 10 s. Starting that
                    // loop when a phone drops mid-DHCP leaves every retry stuck at "Obtaining IP
                    // address".
                    if (NativeHandoffPolicy.shouldRestartDiscovery(
                            nativeAaMode = isNativeAaMode(),
                            hadClient = hadClient,
                            hasClient = isClientConnected)) {
                        AppLog.i("WifiDirectManager: Client disconnected from P2P group. Restarting discovery loop.")
                        startDiscoveryLoop()
                    } else {
                        AppLog.i("WifiDirectManager: Client left the Native AA group; staying a quiet host instead of rediscovering.")
                    }
                    if (NativeHandoffPolicy.shouldRearmJoinWatchdogAfterClientLeft(
                            nativeAaMode = isNativeAaMode(),
                            groupHasHostedSession = nativeGroupHostedSession)) {
                        armNativeJoinWatchdog()
                    } else if (isNativeAaMode()) {
                        AppLog.i("WifiDirectManager: the phone left a group that has already carried a session, so it is kept as it is rather than recreated.")
                        // Kept, but watched: if it stops coming back the group is recreated anyway.
                        armNativeJoinWatchdog()
                    }
                }
            } else {
                isClientConnected = true
            }

            // [FIX] Robust interface detection. group.interface is often null on Android 11+ (hidden API)
            var iface = group.`interface`
            if (iface.isNullOrEmpty()) {
                iface = getInterfaceByIp("192.168.49.1")
                if (iface != null) {
                    AppLog.i("WifiDirectManager: Discovered interface name by IP 192.168.49.1: $iface")
                }
            }
            discoveredInterface = iface
            // [BUG_FIX] The override is checked for shape, not merely for being set. It used to be
            // taken verbatim whenever it was anything other than the unset sentinel "0", which meant
            // a mistyped address won the chain, did not match the masked-string test below, and so
            // suppressed all six fallbacks — and the failure then surfaced 30 s later at Type 3 time
            // as a message blaming location services. SoftApBssidPolicy has validated this on the
            // hotspot route since the same bug was found there; this is the other half.
            val rawOverride = appSettings.staticBSSID
            // choose() rather than isUsable(), for the normalisation: it accepts a hand-typed
            // address written with dashes or in lower case and hands back the colon-separated upper
            // case the phone is given. The hotspot route has read the override through this call
            // since it was written.
            val overrideBssid = SoftApBssidPolicy.choose(rawOverride, null, null)
            val isBssidSet = overrideBssid.isNotEmpty()

            logBssidSourceDump(group, iface, ssid, rawOverride)

            // Two sources ahead of the framework's own MAC, because both survive on a device where
            // getHardwareAddress() is masked: the group owner's BSSID where the vendor exposes it,
            // and the MAC the kernel encoded in the interface's IPv6 link-local address. Which one
            // answered is carried in bssidSource so the log can say so.
            var bssidSource = "static override"
            var bssid: String = overrideBssid
            if (!isBssidSet) {
                val ownerBssid = SoftApBssidPolicy.choose(null, getGroupOwnerBssid(group), null)
                val linkLocalBssid =
                    SoftApBssidPolicy.choose(null, InterfaceMacReader.fromIpv6LinkLocal(iface), null)
                when {
                    ownerBssid.isNotEmpty() -> {
                        bssid = ownerBssid
                        bssidSource = "getGroupOwnerBssid()"
                    }
                    linkLocalBssid.isNotEmpty() -> {
                        bssid = linkLocalBssid
                        bssidSource = "IPv6 link-local"
                        AppLog.i(
                            "WifiDirectManager: BSSID read from the IPv6 link-local address of " +
                                "${iface ?: "an access point interface"} (EUI-64): $bssid"
                        )
                    }
                    else -> {
                        bssid = getWifiDirectMac(iface)
                        bssidSource = "NetworkInterface.hardwareAddress"
                        AppLog.i(
                            "WifiDirectManager: ${iface ?: "no interface"} carries no EUI-64 IPv6 " +
                                "link-local address, so no MAC can be derived from it - this " +
                                "interface uses RFC 7217 stable-privacy addressing."
                        )
                    }
                }
            }
            if (isBssidSet) {
                AppLog.i("WifiDirectManager: Initial BSSID from App settings: $bssid")
            } else {
                if (!rawOverride.isNullOrEmpty() && rawOverride != "0") {
                    // Said out loud rather than silently ignored: the user typed something, and
                    // "your static BSSID is being ignored" is the only line that explains why the
                    // value they set is not the one in the credentials.
                    AppLog.w(
                        "WifiDirectManager: the static BSSID setting ('$rawOverride') is not a MAC " +
                            "address, so it is being ignored. Set it to six hex pairs " +
                            "(XX:XX:XX:XX:XX:XX) or clear it to detect one automatically."
                    )
                }
                AppLog.i("WifiDirectManager: Initial BSSID from $bssidSource: $bssid")
            }



            // [FIX] Robust BSSID detection for masked MACs (00:00 or 02:00)
            //
            // The tests below ask SoftApBssidPolicy rather than comparing against the two masking
            // placeholders: it checks the shape, so a source that answers with something that is
            // not an address at all - an empty string, a status word, a hostname - is rejected here
            // instead of winning the chain and being published verbatim.
            if (!SoftApBssidPolicy.isUsable(bssid)) {
                AppLog.i("WifiDirectManager: BSSID is masked. Starting fallbacks...")

                // Fallback 1: Use last known valid BSSID, but only from this same interface.
                if (SoftApBssidPolicy.isUsable(lastKnownBssid) && lastKnownBssidIface == iface) {
                    AppLog.i("WifiDirectManager: Fallback 1 - Using lastKnownBssid: $lastKnownBssid")
                    bssid = lastKnownBssid!!
                    bssidSource = "lastKnownBssid cache"
                }
                // Fallback 2: Use captured localDeviceAddress
                else if (SoftApBssidPolicy.isUsable(localDeviceAddress)) {
                    AppLog.i("WifiDirectManager: Fallback 2 - Using localDeviceAddress: $localDeviceAddress")
                    bssid = localDeviceAddress!!
                    bssidSource = "requestDeviceInfo"
                }
                // Fallback 3: Use group.owner.deviceAddress
                else {
                    val ownerAddr = group.owner?.deviceAddress
                    AppLog.i("WifiDirectManager: Fallback 3 - group.owner.deviceAddress: $ownerAddr")
                    if (SoftApBssidPolicy.isUsable(ownerAddr)) {
                        AppLog.i("WifiDirectManager: Fallback 3 - Selected group.owner.deviceAddress: $ownerAddr")
                        bssid = ownerAddr!!
                        bssidSource = "group.owner.deviceAddress"
                    } else {
                        AppLog.i("WifiDirectManager: Fallback 4 - Attempting shell/sysfs for $iface...")
                        val shellMac = getMacFromShell(iface)
                        if (SoftApBssidPolicy.isUsable(shellMac)) {
                            AppLog.i("WifiDirectManager: Fallback 4 - Selected shell/sysfs MAC: $shellMac")
                            bssid = shellMac!!
                            bssidSource = "sysfs / ip link"
                        } else {
                            // Fallback 5: Try Settings.Secure (Samsung/Pixel trick)
                            var resolved = false
                            try {
                                val secureMac = Settings.Secure.getString(context.contentResolver, "wifi_p2p_device_address")
                                if (SoftApBssidPolicy.isUsable(secureMac)) {
                                    AppLog.i("WifiDirectManager: Fallback 5 - Selected MAC from Settings.Secure: $secureMac")
                                    bssid = secureMac
                                    bssidSource = "Settings.Secure"
                                    resolved = true
                                }
                            } catch (e: Exception) {
                                AppLog.w("WifiDirectManager: Fallback 5 failed: ${e.message}")
                            }

                            // Fallback 6: Reflect over WifiP2pGroup/WifiP2pDevice hidden fields for
                            // any unmasked MAC the public getters didn't expose (some OEM privacy
                            // hardening masks NetworkInterface/deviceAddress but leaves other
                            // internal fields populated).
                            if (!resolved) {
                                val reflectedMac = getMacFromReflection(group)
                                if (SoftApBssidPolicy.isUsable(reflectedMac)) {
                                    AppLog.i("WifiDirectManager: Fallback 6 - Selected MAC via reflection: $reflectedMac")
                                    bssid = reflectedMac!!
                                    bssidSource = "field reflection"
                                } else {
                                    AppLog.w("WifiDirectManager: every source has been tried and none named an address, including the IPv6 link-local derivation, which needs no permission. The per-source dump above says what each one answered.")
                                }
                            }
                        }
                    }
                }
            }

            if (SoftApBssidPolicy.isUsable(bssid)) {
                // Normalised once, here, so every consumer and the cache see the same upper-case
                // colon form the hotspot route already hands over.
                bssid = SoftApBssidPolicy.choose(null, bssid, null)
                lastKnownBssid = bssid
                lastKnownBssidIface = iface
            }

            // Below API 29 there is nothing to read. WifiP2pGroup gained getFrequency() in Q, and
            // before that it carries no frequency at all - the supplicant callback is handed one and
            // discards it, so there is no field for reflection to find and the old attempt to guess
            // at "frequency"/"mFrequency" could only ever return 0. Reported once per group rather
            // than left as a bare 0, because every band decision downstream reads as "unknown" here
            // and a reader needs to know that is the platform's limit and not this unit's fault.
            var frequency = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                frequency = WifiDirectCompat.getGroupFrequency(group)
            } else if (ssid != lastFrequencyUnreadableSsid) {
                lastFrequencyUnreadableSsid = ssid
                AppLog.i(
                    "WifiDirectManager: this Android (API ${Build.VERSION.SDK_INT}) does not report a " +
                        "P2P group's frequency - WifiP2pGroup only carries it from API 29 - so the band " +
                        "below is unknown rather than missing. Read it from the phone's WiFi details, or " +
                        "from wpa_supplicant's own \"P2P: Set GO freq\" line."
                )
            }

            // The band the session actually runs on, for the narrow-band profile cap: a 5 GHz-capable
            // unit hosting a 2.4 GHz group is on the same narrow link a 2.4 GHz-only one is.
            WifiBandCapability.reportSessionFrequency(frequency)
            val band = if (frequency > 4000) "5GHz" else if (frequency > 0) "2.4GHz" else "unknown"
            val channelLabel = if (WifiP2pChannelPolicy.is24GHz(frequency)) ", ${WifiP2pChannelPolicy.describe(frequency)}" else ""
            // Names the request beside the result: a group that came up on 5745 after the user asked
            // for 5180 is the whole failure this setting exists for, and one line has to answer it.
            val requestedLabel =
                if (nativeRequestedFrequency > 0) ", $nativeRequestedFrequency MHz was asked for" else ""
            AppLog.i("WifiDirectManager: onGroupInfoAvailable: SSID: $ssid, BSSID: $bssid (source=$bssidSource), GO: $isOwner, IFACE: ${iface ?: "null"}, Freq: $frequency MHz ($band$channelLabel)$requestedLabel")

            if (isNativeAaMode() && isOwner) {
                // The group answered, so a refresh landing now may hand it out rather than wait.
                nativeCreateRequestedAtMs = 0L
                // Said once per group, and once more if the address only became readable later.
                // The comparison is made once per group, on the first callback with an address:
                // made again on the next callback it would compare the group to itself.
                val bssidUsable = SoftApBssidPolicy.isUsable(bssid)
                if (ssid != nativeIdentityAssessedSsid && (bssidUsable || ssid != lastIdentityReportSsid)) {
                    val appNamesGroup = Build.VERSION.SDK_INT >= P2pIdentityRotationPolicy.NAMED_CREATE_SDK
                    val verdict = GroupIdentityStabilityPolicy.assess(
                        keepIdentity = appSettings.wifiDirectStableIdentity,
                        requestedName = (nativeRequestedIdentity as? P2pGroupIdentity.Named)?.networkName,
                        ssid = ssid,
                        bssid = bssid,
                        bssidUsable = bssidUsable,
                        staticOverride = isBssidSet,
                        previous = appSettings.wifiDirectLastGroup,
                        appNamesGroup = appNamesGroup,
                        nameChangesSoFar = appSettings.wifiDirectGroupNameChanges,
                    )
                    if (bssidUsable) {
                        nativeIdentityAssessedSsid = ssid
                        verdict.remember?.let { appSettings.wifiDirectLastGroup = it }
                        appSettings.wifiDirectGroupNameChanges = verdict.nameChanges
                    }
                    nativeIdentityStability = verdict.stability
                    lastIdentityReportSsid = ssid
                    AppLog.i(
                        "WifiDirectManager: " + P2pGroupIdentityPolicy.describeReadBack(
                            nativeRequestedIdentity, ssid, psk, group.networkId) +
                            " bssid=$bssid stable=${GroupIdentityStabilityPolicy.label(verdict.stability)}" +
                            // Only below Q, where the count is the whole mechanism. Named here because
                            // settings.xml is the only other place it shows and may not be writable.
                            (if (appNamesGroup) "" else
                                " nameChanges=${verdict.nameChanges}/${GroupIdentityStabilityPolicy.NAME_CHANGES_BEFORE_MEASURED}") +
                            " (${verdict.reason}) source=$bssidSource" +
                            (lastPersistentPurgeVerdict?.let { " profilePurge=$it" } ?: "")
                    )
                }
            }

            // Runs before the channel report below, which is the order the Native AA branch used to
            // impose from inside itself: a group that is about to be torn down and remade on 5GHz
            // must not first tell the user to restart their WiFi. The retry keeps the SSID now, so
            // the report's per-SSID dedupe is reset by the recreate itself (forgetPerGroupKeys).
            if (isNativeAaMode() && isOwner) {
                if (frequency > 4000) {
                    native5GhzBandMismatchRetries = 0
                } else if (shouldRetryNativeGroupFor5Ghz(frequency)) {
                    native5GhzBandMismatchRetries++
                    AppLog.w("WifiDirectManager: Native AA group was requested as 5GHz but came up on $frequency MHz ($band). Recreating 5GHz group (mismatch retry $native5GhzBandMismatchRetries/$MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES).")
                    ConnectionStageTracker.reportNetwork(
                        ConnectionNetworkDetail(frequency, ConnectionNetworkDetail.Note.RETRYING_5GHZ)
                    )
                    removeGroupAndRetryNative5Ghz()
                    return
                }
            }

            if (isOwner) {
                logStationCoexistence(ssid, frequency)

                // A group above channel 11 is up and beaconing and still invisible to most phones:
                // a client in the FCC domain associates on channels 1-11 only and will not even
                // list the SSID in a scan, so the phone reports nothing worse than "can't find the
                // network". Report it and carry on — recreating the group does not help, because
                // the channel is picked when the WiFi radio comes up, not when the group is made:
                // measured on a unit where this failed as six recreates, 2467 MHz every time. Only
                // a WiFi restart, or a country code the driver will honour, moves it.
                //
                // Said once per group, not once per callback: requestGroupInfo() is issued from
                // several places, so this runs three or four times for one group.
                if (WifiP2pChannelPolicy.isClientUnfriendly(frequency)) {
                    if (ssid != lastUnfriendlyChannelSsid) {
                        lastUnfriendlyChannelSsid = ssid
                        AppLog.e("WifiDirectManager: WiFi Direct group came up on ${WifiP2pChannelPolicy.describe(frequency)} ($frequency MHz). Carrying on, but a phone limited to channels 1-11 will not find this network: it will scan and never see the SSID. Restarting this unit's WiFi, or giving it a WiFi country code, is what moves the group off channel 12/13.")
                    }
                    // Said on the pill for as long as the group lives, not in a toast that covered
                    // it. The Native branch below reports the same group and carries the note itself.
                    if (!isNativeAaMode()) {
                        ConnectionStageTracker.reportNetwork(
                            ConnectionNetworkDetail(frequency, ConnectionNetworkDetail.Note.CLIENT_UNFRIENDLY_CHANNEL)
                        )
                    }
                } else if (frequency > 0) {
                    lastUnfriendlyChannelSsid = null
                }
            }

            if (isNativeAaMode() && isOwner) {
                notifyNativeGroupStarted(ssid, frequency, band)
                // The group is up. If no phone joins within the window, recover (recreate fresh).
                armNativeJoinWatchdog(ssid)
            }

            if (ssid.isNotEmpty()) {
                // Wait for the IP address to be assigned to the interface
                val deliveryEpoch = credentialsEpoch
                val deliveryStability =
                    if (isNativeAaMode() && isOwner) nativeIdentityStability
                    else GroupIdentityStability.NOT_MEASURED
                val ipRetries = GroupIpResolutionPolicy.retriesAfterFirstRead(isOwner)
                Thread {
                    try {
                        var ip = getWifiDirectIp(iface)
                        var retries = 0
                        while (ip == null && retries < ipRetries) {
                            AppLog.d("WifiDirectManager: Waiting for IP on interface ${iface ?: "any p2p"} (Attempt ${retries + 1}/$ipRetries)...")
                            Thread.sleep(1000)
                            ip = getWifiDirectIp(iface)
                            retries++
                        }

                        // A group owner is 192.168.49.1 by platform, so waiting for the interface to
                        // say so only delays the credentials and the wake poke behind them.
                        val finalIp = GroupIpResolutionPolicy.resolve(ip, isOwner)
                        if (deliveryEpoch != credentialsEpoch) {
                            AppLog.i(
                                "WifiDirectManager: not delivering credentials for $ssid - that group was " +
                                    "replaced while this was waiting for an IP, and the phone must not be " +
                                    "sent a network that no longer exists."
                            )
                        } else if (finalIp != null) {
                            AppLog.i("WifiDirectManager: SUCCESS - Providing credentials to listener. SSID=$ssid, IP=$finalIp, BSSID=$bssid, identity stable=${GroupIdentityStabilityPolicy.label(deliveryStability)}")
                            // Our own listener, not the phone, and it fires three or four times
                            // per group — so it stays on the network step and re-reports as a no-op.
                            ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
                            onCredentialsReady?.invoke(ssid, psk, finalIp, bssid, deliveryStability)
                        } else {
                            AppLog.e("WifiDirectManager: FAILED to get valid IP for credentials delivery.")
                        }
                    } catch (e: Exception) {
                        AppLog.e("WifiDirectManager: Error in credential delivery thread", e)
                    }
                }.start()
            } else {
                // Nothing is delivered without a name, and this used to be the one exit from
                // onGroupInfoAvailable that said nothing at all: the symptom reached the log 30 s
                // later as the handshake's generic "No WiFi credentials available", pointing at the
                // credentials wait rather than at the group that never named itself.
                AppLog.e(
                    "WifiDirectManager: the P2P group came up without a network name, so there is " +
                        "nothing to hand the phone and no credentials will be sent." +
                        // Only the Native AA join watchdog armed above recovers from this. On the
                        // other paths nothing does, and a log that promised a recreate everywhere
                        // would send the reader looking for one that never comes.
                        if (isNativeAaMode() && isOwner) " The join watchdog will recreate it." else ""
                )
            }
        } else {
            if (groupInfoRetries < 20) {
                groupInfoRetries++
                AppLog.w("WifiDirectManager: Group info was null! Retrying in 1s (Attempt $groupInfoRetries/20)...")
                val epoch = groupInfoEpoch
                handler.postDelayed({
                    if (epoch != groupInfoEpoch) return@postDelayed
                    channel?.let { ch ->
                        manager?.requestGroupInfo(ch, this)
                    }
                }, 1000L)
            } else {
                AppLog.e("WifiDirectManager: FATAL: Group info remained null after 20 retries.")
                groupInfoRetries = 0
                val mgr = manager
                val ch = channel
                val stalled = P2pCreateWedgePolicy.stepAfterGroupInfoExhausted(
                    msSinceAcceptedCreate = msSinceAcceptedCreate(),
                    cancelAlreadySpent = wedgeCancelSpentForStampMs == acceptedCreateWithoutGroupSinceMs,
                ) == P2pCreateWedgePolicy.Step.CANCEL_FIRST
                if (stalled && mgr != null && ch != null) {
                    // The platform said yes and produced nothing: this is the stall, and no BUSY
                    // has to arrive from a colliding refresh for the cancel to be spent.
                    cancelStuckCreate(
                        mgr, ch,
                        onCancelled = { createAfterCancelledStuckCreate(mgr, ch) },
                        onRefused = {},
                    )
                }
            }
        }
    }

    /**
     * Reports whether this unit is also joined to an ordinary WiFi network while hosting the group.
     *
     * One radio serving a station link and a group owner at once has to divide its time between
     * them, and on a single-channel chipset that shows up as the projected video and audio going
     * dead together for a few hundred milliseconds at a time, over and over, with nothing wrong
     * anywhere in the app. It is invisible from a log otherwise, and it is not rare: a dashcam or
     * a phone hotspot the unit reconnects to on its own is enough.
     *
     * Frequencies make the diagnosis exact when both are known: the same channel is shared airtime,
     * different channels means the radio is also retuning between them. Several head units report
     * the group frequency as 0, and on those the comparison cannot be made at all - which is what
     * [StationCoexistencePolicy] is for. It describes and never prescribes: two units have now
     * been measured running clean in exactly the state the old line told them to change.
     *
     * Both arms print. The unjoined one used to return silently, which made the *good* arm of that
     * comparison the only one with a line in it and left a missing line meaning either "not joined"
     * or "the read threw". Whether the unit is joined is the single variable that separated a clean
     * session from one losing picture and sound every ten seconds on the unit that prompted this,
     * so a capture that cannot be sorted into an arm is a capture that cannot be used.
     *
     * Logged once per group and state rather than once per callback, because requestGroupInfo() is
     * issued several places, so this runs three or four times for one group.
     */
    private fun logStationCoexistence(ssid: String, groupFrequency: Int) {
        try {
            val wifiManager = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifiManager.connectionInfo ?: return
            // supplicantState, not networkId or SSID. Both of those are redacted to -1 and
            // "<unknown ssid>" whenever the caller cannot satisfy the location gate, which on a
            // head unit is routine (the service runs without the projection activity in front),
            // so keying on either would silently report "not associated" on the newer Android
            // versions where this diagnosis is worth having. supplicantState survives redaction.
            val associated = info.supplicantState == SupplicantState.COMPLETED

            // The key carries the association state as well as the group, so a station that drops
            // or joins part-way through one group says so once more rather than staying on
            // whatever it said first. Still one line per group per state.
            val key = if (associated) "$ssid|joined" else "$ssid|alone"
            if (key == lastCoexistenceKey) return
            lastCoexistenceKey = key

            val staFrequency = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.frequency
            } else 0
            val finding = if (associated) {
                StationCoexistencePolicy.describe(staFrequency, groupFrequency)
            } else {
                StationCoexistencePolicy.describeNotAssociated(groupFrequency)
            }
            val line = "WifiDirectManager: ${finding.message}"
            when (finding.level) {
                StationCoexistencePolicy.Level.WARN -> AppLog.w(line)
                StationCoexistencePolicy.Level.INFO -> AppLog.i(line)
            }
        } catch (e: Exception) {
            AppLog.d("WifiDirectManager: Could not read station state for coexistence check: ${e.message}")
        }
    }

    /**
     * Say that this unit will not create a group, at most once per [GROUP_REFUSAL_REPORT_INTERVAL_MS].
     *
     * The station state goes on the line because it is the one fact that makes the refusal readable,
     * and the nearest other mention of it in a reporter's log is a minute earlier in a different
     * component's output.
     */
    private fun reportGroupRefusal(reasonStr: String) {
        val now = SystemClock.elapsedRealtime()
        if (lastGroupRefusalReportAtMs != 0L &&
            now - lastGroupRefusalReportAtMs < GROUP_REFUSAL_REPORT_INTERVAL_MS
        ) return
        lastGroupRefusalReportAtMs = now
        AppLog.e(
            "WifiDirectManager: createQuietGroup failed completely! Reason: $reasonStr. " +
                describeStationForRefusal()
        )
        ConnectionIssues.raise(context, ConnectionIssue.WIFI_DIRECT_GROUP_REFUSED)
    }

    /**
     * What this unit's own WiFi was doing when the platform refused to create a group.
     *
     * Descriptive, like [StationCoexistencePolicy], and for the same reason: a radio already serving
     * a station link is the commonest shape of this refusal but has never been measured as its
     * cause, so this states what was true and leaves the conclusion to whoever reads the log.
     * [SupplicantState] rather than the SSID because the SSID is redacted without the location gate,
     * which a head unit routinely cannot satisfy.
     */
    private fun describeStationForRefusal(): String = try {
        val wifiManager = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wifiManager.connectionInfo
        when {
            info == null -> "This unit's own WiFi state could not be read."
            info.supplicantState != SupplicantState.COMPLETED ->
                "This unit's WiFi is not joined to any network."
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.frequency > 0 ->
                "This unit's WiFi is joined to another network on ${info.frequency} MHz."
            else -> "This unit's WiFi is joined to another network."
        }
    } catch (e: Exception) {
        "This unit's own WiFi state could not be read (${e.message})."
    }

    private fun getInterfaceByIp(targetIp: String): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr.hostAddress == targetIp) return iface.name
                }
            }
        } catch (e: Exception) {}
        return null
    }

    private fun getWifiDirectMac(ifaceName: String?): String {
        AppLog.d("WifiDirectManager: getWifiDirectMac for interface: ${ifaceName ?: "any"}")
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val mac = iface.hardwareAddress
                val macStr = if (mac != null) {
                    val sb = StringBuilder()
                    for (i in mac.indices) {
                        sb.append(String.format("%02X%s", mac[i], if (i < mac.size - 1) ":" else ""))
                    }
                    sb.toString()
                } else "null"

                AppLog.d("WifiDirectManager: Found interface: ${iface.name}, MAC: $macStr")

                // If we have a name, it must match.
                if (ifaceName != null && iface.name != ifaceName) continue

                // If we don't have a name, look for common P2P interface patterns
                if (ifaceName == null) {
                    val name = iface.name.lowercase()
                    if (!name.contains("p2p") && !name.contains("wlan") && !name.contains("ap")) continue
                }

                if (macStr != "null" && macStr != "00:00:00:00:00:00" && macStr != "02:00:00:00:00:00") {
                    AppLog.d("WifiDirectManager: Selected MAC for ${iface.name}: $macStr")
                    return macStr
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error scanning network interfaces", e)
        }
        AppLog.w("WifiDirectManager: No valid MAC found in NetworkInterface scan for ${ifaceName ?: "any"}")
        return "00:00:00:00:00:00"
    }

    private fun getWifiDirectIp(ifaceName: String?): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        // Prioritize explicitly requested interface
                        if (ifaceName != null && iface.name == ifaceName) return addr.hostAddress

                        // Fallback: search for 192.168.49.1 (Standard P2P GO IP)
                        if (addr.hostAddress == "192.168.49.1") return addr.hostAddress

                        // Fallback: search for any interface with "p2p" in name
                        if (ifaceName == null && iface.name.lowercase().contains("p2p")) return addr.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e("WifiDirectManager: Error getting local IP", e)
        }
        return null
    }

    @SuppressLint("MissingPermission")
    fun makeVisible() {
        // Radio broadcasts can arrive before the launcher's awaited hotspot teardown finishes.
        if (!visibleBringUpAllowed()) return
        if (App.provide(context).settings.usesServerWifiDirect()) {
            serverStatus.invalidateRequests()
            publishDiscoveryNetwork(null)
            serverPolling = true
            handler.removeCallbacks(serverGroupPoll)
            handler.postDelayed(serverGroupPoll, 2000)
        }
        registerReceiverIfNeeded()
        val mgr = manager ?: return
        val ch = channel ?: return

        // Ensure WiFi is enabled (Required for P2P)
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            AppLog.w("WifiDirectManager: WiFi is disabled. Cannot start P2P discovery.")
            ToastUtils.showToast(context, context.getString(R.string.wifi_disabled_info), Toast.LENGTH_LONG)
            isGroupCreatingOrCreated = false
            return
        }

        isGroupCreatingOrCreated = true

        if (App.provide(context).settings.usesServerWifiDirect()) {
            renameBeforeServerGroup(mgr, ch)
            return
        }

        // Reflection Hack to set name
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java, String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "OpenHU", object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.i("WifiDirectManager: Name set to OpenHU") }
                override fun onFailure(reason: Int) {}
            })
        } catch (e: Exception) {}

        // 1. Stop any ongoing discovery
        val gen = generation
        mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { if (gen == generation) checkGroupAndCreate() }
            override fun onFailure(reason: Int) { if (gen == generation) checkGroupAndCreate() }
        })
    }

    @SuppressLint("MissingPermission")
    private fun renameBeforeServerGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (serverRenamePending) return
        serverRenamePending = true
        val gen = generation
        val nameRevision = localNameRevision
        WifiDirectCompat.requestDeviceName(mgr, ch) { name ->
            if (serverPolling && gen == generation && nameRevision == localNameRevision) {
                recordServerLocalName(name, "device API")
            }
        }
        val gate = ServerP2pRenameGate()
        lateinit var timeout: Runnable
        fun proceed(reason: String) {
            if (gen != generation || !serverPolling || !gate.complete()) return
            serverRenamePending = false
            handler.removeCallbacks(timeout)
            AppLog.i("Auto/P2P: rename $reason; proceeding with group creation")
            mgr.stopPeerDiscovery(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (gen == generation && serverPolling) checkGroupAndCreate() }
                override fun onFailure(reason: Int) { if (gen == generation && serverPolling) checkGroupAndCreate() }
            })
        }
        timeout = Runnable { proceed("timed out") }
        handler.postDelayed(timeout, 2000)
        try {
            val method = mgr.javaClass.getMethod("setDeviceName", WifiP2pManager.Channel::class.java,
                String::class.java, WifiP2pManager.ActionListener::class.java)
            method.invoke(mgr, ch, "OpenHU", object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (gen != generation || !serverPolling) return
                    recordServerLocalName("OpenHU", "rename confirmation")
                    proceed("confirmed")
                }
                override fun onFailure(reason: Int) { proceed("refused ($reason)") }
            })
        } catch (e: Exception) {
            AppLog.w("Auto/P2P: device rename unavailable: ${e.message}")
            proceed("unavailable")
        }
    }

    @SuppressLint("MissingPermission")
    private fun checkGroupAndCreate() {
        if (checkGroupAndCreateInFlight) {
            AppLog.d("WifiDirectManager: checkGroupAndCreate already in flight, skipping duplicate call")
            return
        }
        checkGroupAndCreateInFlight = true
        markP2pRequest()
        // Keyed: an un-keyed timer from an earlier run fires 8s later and clears a guard this run
        // is relying on, which lets two teardown/create pairs run at once - the very race the flag
        // exists to prevent.
        val guardToken = ++checkGroupAndCreateToken
        val gen = generation
        handler.postDelayed({
            if (guardToken == checkGroupAndCreateToken) checkGroupAndCreateInFlight = false
        }, 8000L)

        isGroupOwner = false
        isConnected = false

        manager?.requestGroupInfo(channel) { group ->
            if (supersededByStop(gen, "group inspection")) return@requestGroupInfo
            if (group == null) {
                AppLog.i("No existing P2P group, creating new one")
                checkGroupAndCreateInFlight = false
                if (supersededByStop(gen, "group creation")) return@requestGroupInfo
                createNewGroup(0)
                return@requestGroupInfo
            }

            // Reusing an existing group desyncs the peer's WPS/PBC registrar into a tight
            // PROV-DISC retry storm (confirmed via live-device bisection against `ebab63a8`,
            // whose only change was skipping this teardown on reuse) — tear down and recreate
            // on every reuse rather than trying to detect which ones are broken.
            // No deletePersistentGroup() here: that call is refused from Android 11, and below API 29,
            // where it still works, it is reserved for the Native AA rename (P2pPersistentGroupPurge).
            // The Helper path only needs a clean registrar, which the teardown alone gives it.
            AppLog.i("WifiDirectManager: Existing P2P group found — removing and recreating fresh for a clean WPS/PBC registrar")
            // The next createGroup() call generates a brand-new GO interface with a new random
            // MAC — a cached BSSID from the group we're tearing down is now stale and must never
            // be delivered for the new one.
            lastKnownBssid = null
            lastKnownBssidIface = null
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    checkGroupAndCreateInFlight = false
                    if (supersededByStop(gen, "group recreate")) return
                    createNewGroup(0)
                }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: removeGroup before recreate failed: ${getP2pErrorString(reason)}")
                    checkGroupAndCreateInFlight = false
                    if (supersededByStop(gen, "group recreate")) return
                    createNewGroup(0)
                }
            })
        }
    }

    @SuppressLint("MissingPermission")
    private fun createNewGroup(retryCount: Int) {
        val mgr = manager ?: return
        val ch = channel ?: return
        val gen = generation
        val serverP2p = App.provide(context).settings.usesServerWifiDirect()

        if (isConnected || isGroupOwner) {
            AppLog.d("WifiDirectManager: Group already active/created (isConnected=$isConnected, isGroupOwner=$isGroupOwner). Skipping createGroup retry.")
            return
        }

        lastKnownBssid = null
        lastKnownBssidIface = null

        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (serverP2p && createAbandonedAfterStop(gen, mgr, ch)) return
                if (gen != generation) return
                AppLog.i("WifiDirectManager: P2P Group created (fresh this session).")
                isGroupOwner = true
                tightBurstCount = 0
                lastConnChangedElapsedMs = 0L
                startDiscoveryLoop()
            }
            override fun onFailure(reason: Int) {
                if (gen != generation) return
                if (reason == 2 && retryCount < 3) { // 2 = BUSY
                    AppLog.w("WifiDirectManager: Chip is BUSY, retrying in 2s...")
                    handler.postDelayed({ if (gen == generation) createNewGroup(retryCount + 1) }, 2000L)
                } else {
                    AppLog.e("WifiDirectManager: createGroup failed: $reason")
                    isGroupCreatingOrCreated = false
                }
            }
        })
    }

    private fun startDiscoveryLoop() {
        handler.removeCallbacks(discoveryRunnable)
        serverDiscoveryScheduled = serverPolling
        handler.post(discoveryRunnable)
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery() {
        val ch = channel
        if (ch != null) {
            val appSettings = App.provide(context).settings
            if (appSettings.usesVisibleWifiDirect()) {
                AapService.scanningState.value = true
            }
            manager?.discoverPeers(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    // INFO, not DEBUG: discoverPeers() puts the P2P radio into find mode, sweeping
                    // the social channels, and on a single-radio unit hosting a group that is
                    // seconds of silence for everything already on it. Two reporters' captures show
                    // exactly that shape on this loop's ten-second cadence, and neither could be
                    // checked against it - the only line the loop left was at a level nobody is
                    // ever asked to capture. A search running under a live session is the anomaly,
                    // so say which case this is.
                    val sessionLive = isNativeSessionConnected?.invoke() == true
                    AppLog.i(
                        "WifiDirectManager: Discovery active - peer search running%s",
                        if (sessionLive) " while an Android Auto session is connected" else ""
                    )
                    if (appSettings.usesVisibleWifiDirect()) {
                        handler.postDelayed({
                            if (!isClientConnected) {
                                AapService.scanningState.value = false
                            }
                        }, 2500L)
                    }
                }
                override fun onFailure(reason: Int) {
                    AppLog.w("WifiDirectManager: Discovery failed: $reason")
                    AapService.scanningState.value = false
                }
            })
        }
    }

    /**
     * Boomerang Hack: Briefly triggers system WiFi settings to wake up the radio.
     * Currently not used by default but kept in code for future use.
     */
    private fun triggerWifiSettings() {
        try {
            val intent = Intent().apply {
                component = ComponentName("com.android.settings", "com.android.settings.Settings\$WifiP2pSettingsActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {}
        }

        handler.postDelayed({
            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                context.startActivity(intent)
            } catch (e: Exception) {}
        }, 800L)
    }

    /**
     * Puts a new network name and passphrase on the air now instead of at the next connection.
     * Below API 29 the create reinvokes the platform's stored profile, so the profile is deleted
     * first; from 29 the create names the group itself and the recreate is the whole of it.
     */
    fun rotateNativeIdentityNow() {
        AppLog.i(
            "WifiDirectManager: a new WiFi Direct identity was asked for now (" +
                "${P2pIdentityRotationPolicy.mechanism(Build.VERSION.SDK_INT)}); recreating the group."
        )
        startNativeAaQuietHost()
    }

    @SuppressLint("MissingPermission")
    fun startNativeAaQuietHost() {
        // Two bring-ups fight over BUSY and the loser removes the winner's group; see
        // NativeBringUpReentryPolicy. The claim window covers the callers that hold one, this
        // covers the ones that cannot.
        val now = SystemClock.elapsedRealtime()
        if (NativeBringUpReentryPolicy.isDuplicate(now, lastNativeBringUpAtMs)) {
            AppLog.i(
                "WifiDirectManager: a Native AA bring-up started ${now - lastNativeBringUpAtMs}ms ago is " +
                    "still running, so this one is not started on top of it."
            )
            return
        }
        lastNativeBringUpAtMs = now
        registerReceiverIfNeeded()
        isGroupCreatingOrCreated = true
        markP2pRequest()
        var mgr = manager
        var ch = channel

        if (mgr == null || ch == null) {
            AppLog.w("WifiDirectManager: manager ($mgr) or channel ($ch) is null. Attempting re-init...")
            try {
                val newManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
                val newChannel = newManager?.initialize(context, context.mainLooper, null)
                if (newManager != null && newChannel != null) {
                    manager = newManager
                    channel = newChannel
                    mgr = newManager
                    ch = newChannel
                    AppLog.i("WifiDirectManager: Re-init successful and fields updated.")
                    registerReceiverIfNeeded()
                } else {
                    AppLog.e("WifiDirectManager: Re-init failed. Cannot start Quiet Host.")
                    isGroupCreatingOrCreated = false
                    releaseNativeCreateWindow("the P2P manager could not be re-initialised")
                    return
                }
            } catch (e: Exception) {
                AppLog.e("WifiDirectManager: Exception during re-init", e)
                isGroupCreatingOrCreated = false
                releaseNativeCreateWindow("the P2P re-init threw")
                return
            }
        }

        // Ensure WiFi is enabled (Required for P2P). Nothing is claimed above this point on purpose:
        // a create window taken before the radio is known to be on stayed standing through every
        // retry below, which made isCreatingGroup permanently true and vetoed the Bluetooth
        // auto-start rebuild that is the only thing that frees this radio again.
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifiManager.isWifiEnabled) {
            // Counted so the lines below are said once per bring-up: a credential refresh re-enters
            // this method every ten seconds while the handshake waits for one.
            val attempt = ++wifiEnableAttempts
            if (Build.VERSION.SDK_INT >= 29) {
                if (attempt == 1) {
                    AppLog.i("WifiDirectManager: WiFi is off and this Android does not let an app switch it on.")
                    showToast(context.getString(R.string.native_aa_requires_wifi))
                    ConnectionIssues.raise(context, ConnectionIssue.WIFI_RADIO_OFF)
                }
                isGroupCreatingOrCreated = false
                releaseNativeCreateWindow("WiFi is off and only the user can turn it on")
                return
            }
            if (attempt > MAX_WIFI_ENABLE_ATTEMPTS) {
                if (attempt == MAX_WIFI_ENABLE_ATTEMPTS + 1) {
                    AppLog.w(
                        "WifiDirectManager: WiFi is still off after $MAX_WIFI_ENABLE_ATTEMPTS attempts to " +
                            "switch it on, so the group cannot be created. Switch WiFi on for this unit."
                    )
                    showToast(context.getString(R.string.native_aa_requires_wifi))
                    ConnectionIssues.raise(context, ConnectionIssue.WIFI_RADIO_OFF)
                }
                isGroupCreatingOrCreated = false
                releaseNativeCreateWindow("WiFi is off and would not come on")
                return
            }
            AppLog.i("WifiDirectManager: WiFi is disabled but needed for Native AA. Attempting to enable (attempt $attempt of $MAX_WIFI_ENABLE_ATTEMPTS)...")
            try {
                @Suppress("DEPRECATION")
                wifiManager.isWifiEnabled = true
            } catch (e: Exception) {
                AppLog.w("WifiDirectManager: Could not ask the platform to enable WiFi: ${e.message}")
            }
            // Wait a bit for WiFi to wake up
            handler.postDelayed({
                if (isGroupCreatingOrCreated) {
                    isGroupCreatingOrCreated = false
                    startNativeAaQuietHost()
                }
            }, 2000L)
            return
        }
        wifiEnableAttempts = 0
        // The radio being on disproves the record outright, well before a group forms.
        ConnectionIssues.clear(context, ConnectionIssue.WIFI_RADIO_OFF)

        claimNativeCreateWindow("bringing the Native AA group up")
        AppLog.i("WifiDirectManager: startNativeAaQuietHost() requested. Removing old group if any...")
        // A real restart, not a stray callback: the group goes away and is built again, so the
        // pill is allowed to fall back to this step.
        ConnectionStageTracker.beginAttempt(ConnectionStage.PREPARING_NETWORK)
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        lastNativeGroupStatusMessage = null
        ConnectionStageTracker.reportNetwork(null)
        native5GhzBandMismatchRetries = 0
        nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED
        nativeRequestedFrequency = 0
        // legacyChannelAttempt is deliberately not reset here. The handshake calls
        // triggerWifiDirectRefresh() every ten seconds while it waits for credentials, and each one
        // lands back in this method - so resetting made rung 1 the only rung the ladder ever tried.
        // stop() clears it, which ties the ladder to the mode rather than to a refresh.
        forgetPerGroupKeys()
        nativeRecreateCount = 0
        stuckCreateCancels = 0
        cancelNativeJoinWatchdog()
        recreateNativeGroup(forceStandard = false)
    }

    /**
     * What the handshake asks for while it waits for credentials, and before every poke.
     *
     * Used to be [startNativeAaQuietHost], a full teardown and recreate, every ten seconds - which
     * handed the phone a new network name in the very window it was trying to join one, and tore
     * down the group the first poke's pre-flight found still forming. The rules are
     * [NativeRefreshPolicy]'s: a group that is up and ours has its credentials read again, one that
     * was just asked for is left to answer, and only no group at all is worth creating one.
     */
    @SuppressLint("MissingPermission")
    fun refreshNativeCredentials() {
        val mgr = manager
        val ch = channel
        if (mgr == null || ch == null) {
            startNativeAaQuietHost()
            return
        }
        val gen = generation
        markP2pRequest()
        mgr.requestGroupInfo(ch) { group ->
            if (supersededByStop(gen, "credential refresh")) return@requestGroupInfo
            val inFlightForMs = nativeCreateRequestedAtMs
                .takeIf { it != 0L }
                ?.let { SystemClock.elapsedRealtime() - it }
            val acceptedForMs = msSinceAcceptedCreate()
            when (NativeRefreshPolicy.decide(
                groupExists = group != null,
                isGroupOwner = group?.isGroupOwner == true,
                createInFlightForMs = inFlightForMs,
                acceptedCreatePendingForMs = acceptedForMs,
            )) {
                NativeRefreshPolicy.Action.REDELIVER -> {
                    AppLog.i(
                        "WifiDirectManager: refresh: the group ${group?.networkName} is up, so its " +
                            "credentials are read again rather than the group remade."
                    )
                    mgr.requestConnectionInfo(ch, this)
                    onGroupInfoAvailable(group)
                }
                NativeRefreshPolicy.Action.WAIT -> {
                    if (NativeRefreshPolicy.withinCreateGrace(inFlightForMs)) {
                        AppLog.i(
                            "WifiDirectManager: refresh: a group was asked for ${inFlightForMs}ms ago and " +
                                "has not answered yet, so nothing is remade underneath it."
                        )
                    } else {
                        AppLog.i(
                            "WifiDirectManager: refresh: a group was accepted ${acceptedForMs}ms ago and " +
                                "is still being read for, so nothing is remade underneath it."
                        )
                    }
                    // Asked again once the grace is up. The session-end re-arm asks for credentials
                    // exactly once, so a create that never answers would otherwise leave the mode
                    // idle with nothing to notice it.
                    val askAgainInMs = NativeRefreshPolicy.recheckDelayMs(inFlightForMs, acceptedForMs)
                    AppLog.i("WifiDirectManager: refresh: asking again in ${askAgainInMs}ms, once that create's grace is up.")
                    handler.removeCallbacks(nativeRefreshRecheck)
                    handler.postDelayed(nativeRefreshRecheck, askAgainInMs)
                }
                NativeRefreshPolicy.Action.RECREATE -> {
                    AppLog.i("WifiDirectManager: refresh: no group is up, so one is created.")
                    startNativeAaQuietHost()
                }
            }
        }
    }

    private fun delayedCreateQuietGroup(retryCount: Int) {
        handler.postDelayed({ createQuietGroup(retryCount) }, 500L)
    }

    @SuppressLint("MissingPermission")
    private fun createQuietGroup(retryCount: Int) {
        val mgr = manager ?: run { releaseNativeCreateWindow("the P2P manager went away before the create"); return }
        val ch = channel ?: run { releaseNativeCreateWindow("the P2P channel went away before the create"); return }

        // Read once per attempt rather than held in a field: the setting is written between runs on
        // a rig, and a group made after the write must be the one the write asked for.
        val appSettings = App.provide(context).settings
        val preference = P2pBandPreference.fromSetting(appSettings.wifiDirectBand)
        // Read per attempt like the setting above, and for the same reason: this is cheap, and a
        // value cached at construction would outlive a WiFi service that came up later.
        val supports5Ghz = WifiBandCapability.supports5Ghz(context)
        val band = NativeGroupBandPolicy.bandFor(preference, supports5Ghz)
        val bandLabel = NativeGroupBandPolicy.label(band)
        val chosenChannel = appSettings.fiveGhzChannel
        // Asking for the 5 GHz *band* is answered by picking a random one of eight frequencies,
        // four of them in UNII-3, which several regulatory domains forbid phones from joining - so
        // on those phones half of all bring-ups are invisible. A pinned channel replaces the band
        // request with a frequency, which the platform treats as forced. 0 means ask for the band.
        val requestedFrequency = if (pinnedChannelAbandoned) 0
            else NativeGroupBandPolicy.requestedFrequencyMhz(band, chosenChannel)

        AppLog.i("WifiDirectManager: Attempting createGroup for Native AA (Attempt $retryCount)...")
        ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
        // Said on every bring-up, including the default one: a line that only appears in the unusual
        // case is a line whose absence tells a reader nothing. Same for the radio's own answer,
        // which two open issues spent weeks guessing at.
        AppLog.i("WifiDirectManager: ${WifiBandCapability.describe(supports5Ghz)}.")
        AppLog.i("WifiDirectManager: Band preference is ${NativeGroupBandPolicy.describePreference(preference, supports5Ghz)}.")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                // The name and passphrase are ours to choose here, and by default they are kept
                // between creates and the group asked for as a persistent one, so the phone rejoins
                // a network it saved rather than being set up for a new one every session. The
                // rules are P2pGroupIdentityPolicy's. onGroupInfoAvailable() reads back what the
                // platform actually made and says whether it matches.
                val identity = chooseNativeGroupIdentity()
                val builder = WifiP2pConfig.Builder()
                    .setNetworkName(identity.networkName)
                    .setPassphrase(identity.passphrase)
                if (identity.persistent) builder.enablePersistentMode(true)
                // Exactly one of the two: build() throws IllegalStateException if both are set.
                if (requestedFrequency > 0) {
                    builder.setGroupOperatingFrequency(requestedFrequency)
                } else {
                    builder.setGroupOperatingBand(
                        if (band == NativeGroupBandPolicy.Band.GHZ_2_4) WifiP2pConfig.GROUP_OWNER_BAND_2GHZ
                        else WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
                    )
                }
                val config = builder.build()

                // Recorded here and not before the gate: this is the only branch that asks for a
                // band, so it is the only one whose answer can be mismatched. Below Q the request
                // does not exist and standardCreateGroup leaves the field UNSPECIFIED.
                nativeRequestedBand = band
                nativeRequestedFrequency = requestedFrequency
                nativeRequestedIdentity = identity
                AppLog.i("WifiDirectManager: Requesting Native AA P2P group on $bandLabel band.${if (preference != P2pBandPreference.AUTO) " Chosen by the user." else ""}")
                AppLog.i(
                    "WifiDirectManager: 5 GHz channel is ${FiveGhzChannelPolicy.describe(chosenChannel)}" +
                        if (requestedFrequency > 0) ", asked for as a fixed $requestedFrequency MHz."
                        else if (pinnedChannelAbandoned) ", already refused by this unit, so the band decides."
                        else ", so the driver picks within the band."
                )
                markP2pRequest()
                nativeCreateRequestedAtMs = SystemClock.elapsedRealtime()
                val gen = generation
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (createAbandonedAfterStop(gen, mgr, ch)) return
                        AppLog.i("WifiDirectManager: $bandLabel createGroup SUCCESS!")
                        ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
                        noteAcceptedCreate(P2pCreateWedgePolicy.Variant.BANDED)
                        noteGroupFormed()
                        // Only a create that carried the frequency disproves the record. A group
                        // formed on the driver's own pick is the failure it describes, not its cure.
                        if (requestedFrequency > 0) {
                            ConnectionIssues.clear(context, ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED)
                        }
                        nativeGroupCreationMode =
                            if (band == NativeGroupBandPolicy.Band.GHZ_2_4) NATIVE_GROUP_MODE_24GHZ_REQUESTED
                            else NATIVE_GROUP_MODE_5GHZ_REQUESTED
                        isGroupOwner = true
                        // Asked now rather than a second later: a group that cannot answer yet is
                        // already covered by the 20 x 1s retry in onGroupInfoAvailable, so the wait
                        // only ever cost a second it could not save.
                        mgr.requestConnectionInfo(ch, this@WifiDirectManager)
                        mgr.requestGroupInfo(ch, this@WifiDirectManager)
                    }
                    override fun onFailure(reason: Int) {
                        // Nothing to remove here, but the ladder below would create one: a failure
                        // that lands after a stop must not walk on to the next rung.
                        if (supersededByStop(gen, "the Native AA group create")) return
                        onQuietGroupFailed(mgr, ch, retryCount, preference, chosenChannel,
                            requestedFrequency, bandLabel, reason, getP2pErrorString(reason), null)
                    }
                })
                return
            } catch (t: Throwable) {
                onQuietGroupFailed(mgr, ch, retryCount, preference, chosenChannel,
                    requestedFrequency, bandLabel, -1, "crashed before any async result", t)
                return
            }
        }

        // Below Q there is no band request, so the driver picks the channel unless it is given a
        // frequency list. This has to happen before createGroup: the platform only accepts a channel
        // change while no group exists, and drops it silently afterwards.
        //
        // A ladder, walked one rung per failed bring-up: 5 GHz, then 2.4 GHz, then no restriction at
        // all. The rungs matter because the request is a disallowed-frequency list, so a unit that
        // cannot host a group owner on the band it names does not land on the other one - it forms
        // no group. standardCreateGroup() is what advances the index when that happens.
        // Before the first channel request, so a refusal below always has the domain above it.
        logWifiCountrySourceDump()
        val pinnedChannel = FiveGhzChannelPolicy.pinnedChannel(chosenChannel)
        val ladder = WifiP2pOperatingChannelPolicy.attemptChannels(
            sdkInt = Build.VERSION.SDK_INT,
            preference = preference,
            chosenChannel = pinnedChannel,
            supports5Ghz = supports5Ghz,
        )
        val ladderLabel = ladder.joinToString { channel ->
            "$channel (${WifiP2pOperatingChannelPolicy.frequencyMhzFor(channel)} MHz)"
        }
        val operatingChannel = ladder.getOrNull(legacyChannelAttempt)
            ?: WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED
        if (operatingChannel == WifiP2pOperatingChannelPolicy.CHANNEL_UNRESTRICTED) {
            // Every rung spent. Also where an empty ladder lands, which only an Android 10+ device
            // produces and which the branch above already returned for - kept as the same path
            // rather than a special case, because both mean "ask for nothing".
            AppLog.w(
                "WifiDirectManager: every operating channel this unit was offered " +
                    "(${ladderLabel.ifEmpty { "none" }}) has been tried, so the band goes back to " +
                    "being the driver's choice."
            )
            // Walked across the whole window and refused every time: the unit will not host a
            // group owner there at all, and the driver's own pick is what the setting exists to
            // escape.
            if (WifiP2pOperatingChannelPolicy.refusedEveryFiveGhzRung(
                    ladder, legacyChannelAttempt, pinnedChannel
                )
            ) {
                val fiveGhzRungs = ladder.filter { WifiP2pOperatingChannelPolicy.frequencyMhzFor(it) > 5000 }
                AppLog.w(
                    "WifiDirectManager: this unit refused a group owner on every 5 GHz channel it " +
                        "was offered (${fiveGhzRungs.joinToString()}), ${WifiCountryPolicy.describe(wifiCountrySources())}. " +
                        "The channel setting is not one it can honour, so the group goes up wherever " +
                        "its driver puts it, which a phone in a stricter country may not be able to " +
                        "see - the band is the lever left."
                )
                ConnectionIssues.raise(context, ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED)
            }
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
            return
        }

        val frequency = WifiP2pOperatingChannelPolicy.frequencyMhzFor(operatingChannel)
        if (legacyChannelRequestUnanswered) {
            AppLog.i(
                "WifiDirectManager: not asking for operating channel $operatingChannel " +
                    "($frequency MHz) again - this unit left the last request unanswered, so the " +
                    "band stays the driver's choice."
            )
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
            return
        }
        AppLog.i(
            "WifiDirectManager: no band request below Android 10, so asking for operating channel " +
                "$operatingChannel ($frequency MHz) instead - rung ${legacyChannelAttempt + 1} of " +
                "${ladder.size} ($ladderLabel). The group cannot be told which band to use here; " +
                "this restricts which frequencies it may pick."
        )
        markP2pRequest()
        WifiP2pChannelCompat.setOperatingChannel(mgr, ch, operatingChannel, handler) { applied, answered, detail ->
            legacyChannelRestrictionApplied = applied
            if (applied) {
                AppLog.i("WifiDirectManager: operating channel $operatingChannel ($frequency MHz) $detail.")
            } else if (answered) {
                AppLog.w(
                    "WifiDirectManager: this unit would not take an operating channel ($detail), so " +
                        "the group's band stays the driver's choice, as it was before."
                )
            } else {
                legacyChannelRequestUnanswered = true
                AppLog.w(
                    "WifiDirectManager: this unit never answered the operating channel request " +
                        "($detail), so the group's band stays the driver's choice and the rest of " +
                        "this session goes straight to creating the group."
                )
            }
            standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_LEGACY)
        }
    }

    /**
     * What a failed Native AA group request does next.
     *
     * The ordering is [NativeGroupBandPolicy.nextStepAfterFailure]'s; this only carries it out and
     * says so in the log. A crash before the async result lands here too, because a request the
     * platform rejects outright and one it accepts and then fails need the same ladder.
     */
    private fun onQuietGroupFailed(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        retryCount: Int,
        preference: P2pBandPreference,
        chosenChannel: Int,
        requestedFrequency: Int,
        bandLabel: String,
        reason: Int,
        reasonStr: String,
        crash: Throwable?,
    ) {
        // Ahead of the ladder: inside the platform's own create window nothing on it can succeed.
        if (shouldCancelStuckCreate(reason)) {
            cancelStuckCreate(
                mgr, ch,
                onCancelled = { createAfterCancelledStuckCreate(mgr, ch) },
                onRefused = { createQuietGroup(retryCount) },
            )
            return
        }
        val requestLabel =
            if (requestedFrequency > 0) "$bandLabel ${FiveGhzChannelPolicy.describe(chosenChannel)}"
            else bandLabel
        when (NativeGroupBandPolicy.nextStepAfterFailure(
            preference = preference,
            channelPinned = requestedFrequency > 0,
            retriesSoFar = retryCount,
            maxRetries = MAX_NATIVE_5GHZ_CREATE_RETRIES,
        )) {
            NativeGroupBandPolicy.NextStep.RETRY -> {
                val message = "WifiDirectManager: $requestLabel createGroup failed ($reasonStr), " +
                    "removing group and retrying in 2s " +
                    "(retry ${retryCount + 1}/$MAX_NATIVE_5GHZ_CREATE_RETRIES)..."
                if (crash != null) AppLog.e(message, crash) else AppLog.w(message)
                retryQuietGroup(mgr, ch, retryCount + 1, removeFirst = crash == null)
            }

            NativeGroupBandPolicy.NextStep.DROP_PINNED_CHANNEL -> {
                pinnedChannelAbandoned = true
                ConnectionIssues.raise(context, ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED)
                AppLog.w(
                    "WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr). This unit " +
                        "will not host a group on that channel, so the request goes back to the $bandLabel band " +
                        "and the driver picks the channel. If the phone still cannot see the head unit, that " +
                        "is why - the channel you chose is not the one it is on."
                )
                retryQuietGroup(mgr, ch, 0, removeFirst = crash == null)
            }

            NativeGroupBandPolicy.NextStep.STANDARD_FALLBACK -> {
                AppLog.w("WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr). Falling back to standard createGroup.")
                standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            }

            NativeGroupBandPolicy.NextStep.GIVE_UP -> {
                // "5 GHz only" means it. A group on 2.4 GHz can connect, look entirely healthy and
                // show nothing, which is harder to diagnose than no group, and the user asked not
                // to be given one.
                AppLog.e(
                    "WifiDirectManager: $requestLabel createGroup retries exhausted ($reasonStr) and the " +
                        "band is set to 5 GHz only, so no group is created. Set the WiFi Direct band " +
                        "to Auto if this unit cannot host one."
                )
                isGroupCreatingOrCreated = false
                releaseNativeCreateWindow("the band is 5 GHz only and no group could be made")
            }
        }
    }

    /**
     * Ask again in 2 s, clearing any group the platform made first.
     *
     * Not after a crash: the request never reached the framework, so there is nothing to remove and
     * asking would spend a bring-up on the rate instrument for nothing.
     */
    private fun retryQuietGroup(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        nextRetry: Int,
        removeFirst: Boolean,
    ) {
        if (!removeFirst) {
            handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L)
            return
        }
        invalidateNativeGroupCredentials("the create was refused and is being retried")
        markP2pRequest()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L) }
            override fun onFailure(r: Int) { handler.postDelayed({ createQuietGroup(nextRetry) }, 2000L) }
        })
    }

    /**
     * What the group's name is suffixed with: the P2P device name once the framework has reported
     * it, else the unit's own device name. A kept pair is drawn once and the first create after an
     * install runs before the P2P name has arrived, which is how a unit ended up called "HeadUnit"
     * for good.
     */
    private fun nativeGroupNameSuffixSource(): String? =
        AapService.wifiDirectName.value
            ?: try {
                Settings.Global.getString(context.contentResolver, "device_name")
            } catch (e: Exception) {
                null
            }

    /**
     * The name and passphrase to ask for. The rules are [P2pGroupIdentityPolicy]'s; this only reads
     * the kept pair, writes one back when the policy has just drawn it, and logs which it was.
     */
    private fun chooseNativeGroupIdentity(): P2pGroupIdentity.Named {
        val appSettings = App.provide(context).settings
        val choice = P2pGroupIdentityPolicy.decide(
            keepIdentity = appSettings.wifiDirectStableIdentity,
            stored = appSettings.wifiDirectGroupIdentity,
            deviceName = nativeGroupNameSuffixSource(),
        )
        choice.toStore?.let { appSettings.wifiDirectGroupIdentity = it }
        AppLog.i("WifiDirectManager: ${choice.reason}")
        return choice.identity
    }

    private fun getP2pErrorString(reason: Int): String {
        return when(reason) {
            0 -> "ERROR (Internal Error)"
            1 -> "P2P_UNSUPPORTED"
            2 -> "BUSY (System is busy, retry needed)"
            else -> "UNKNOWN ($reason)"
        }
    }

    /**
     * True once a named, band-free create has failed for a reason other than BUSY, so the rest of
     * this mode falls back to the platform's own profile. Cleared by [stop].
     */
    private var namedFallbackRefused = false

    @SuppressLint("MissingPermission")
    private fun standardCreateGroup(mgr: WifiP2pManager, ch: WifiP2pManager.Channel, retryCount: Int, groupMode: String) {
        markP2pRequest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !namedFallbackRefused) {
            // "Standard" means no band request, not no name. The two-argument overload brings up
            // whatever profile the platform stored last, which on a unit with an older one is a
            // different network from the kept identity; measured as the join watchdog's fallback
            // handing the phone a name from a previous install. So the identity is kept here too,
            // and only the band is left to the platform.
            try {
                val identity = chooseNativeGroupIdentity()
                val builder = WifiP2pConfig.Builder()
                    .setNetworkName(identity.networkName)
                    .setPassphrase(identity.passphrase)
                if (identity.persistent) builder.enablePersistentMode(true)
                val config = builder.build()
                nativeRequestedIdentity = identity
                nativeCreateRequestedAtMs = SystemClock.elapsedRealtime()
                AppLog.i("WifiDirectManager: standard createGroup as ${identity.networkName}, band left to the platform.")
                val gen = generation
                mgr.createGroup(ch, config, object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (createAbandonedAfterStop(gen, mgr, ch)) return
                        onStandardCreateSucceeded(mgr, ch, groupMode, P2pCreateWedgePolicy.Variant.NAMED_NO_BAND)
                    }
                    override fun onFailure(reason: Int) {
                        if (supersededByStop(gen, "the Native AA group create")) return
                        val reasonStr = getP2pErrorString(reason)
                        if (shouldCancelStuckCreate(reason)) {
                            cancelStuckCreate(
                                mgr, ch,
                                onCancelled = { createAfterCancelledStuckCreate(mgr, ch) },
                                onRefused = { standardCreateGroup(mgr, ch, retryCount, groupMode) },
                            )
                            return
                        }
                        if (reason == 2 && retryCount < MAX_NATIVE_STANDARD_CREATE_RETRIES) {
                            AppLog.w("WifiDirectManager: standard createGroup failed ($reasonStr), removing group and retrying standard in 2s...")
                            invalidateNativeGroupCredentials("the standard create was refused and is being retried")
                            markP2pRequest()
                            mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                                override fun onSuccess() { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                                override fun onFailure(r: Int) { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                            })
                        } else {
                            namedFallbackRefused = true
                            AppLog.w(
                                "WifiDirectManager: this unit refused a named group with no band " +
                                    "request ($reasonStr), so the platform's own profile is asked for instead."
                            )
                            standardCreateGroup(mgr, ch, 0, groupMode)
                        }
                    }
                })
                return
            } catch (t: Throwable) {
                namedFallbackRefused = true
                AppLog.e("WifiDirectManager: named standard createGroup crashed before any async result; asking for the platform's own profile instead.", t)
            }
        }
        // The two-argument overload asks for the platform's own stored profile, so the name and
        // passphrase are whatever it kept from the last group this unit owned.
        nativeRequestedIdentity = P2pGroupIdentity.FrameworkProfile
        nativeCreateRequestedAtMs = SystemClock.elapsedRealtime()
        val gen = generation
        mgr.createGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (createAbandonedAfterStop(gen, mgr, ch)) return
                onStandardCreateSucceeded(mgr, ch, groupMode, P2pCreateWedgePolicy.Variant.FRAMEWORK_PROFILE)
            }
            override fun onFailure(reason: Int) {
                if (supersededByStop(gen, "the Native AA group create")) return
                val reasonStr = getP2pErrorString(reason)
                if (shouldCancelStuckCreate(reason)) {
                    cancelStuckCreate(
                        mgr, ch,
                        onCancelled = { createAfterCancelledStuckCreate(mgr, ch) },
                        onRefused = { standardCreateGroup(mgr, ch, retryCount, groupMode) },
                    )
                    return
                }
                if (reason == 2 && retryCount < MAX_NATIVE_STANDARD_CREATE_RETRIES) {
                    AppLog.w("WifiDirectManager: standard createGroup failed ($reasonStr), removing group and retrying standard in 2s...")
                    invalidateNativeGroupCredentials("the standard create was refused and is being retried")
                    markP2pRequest()
                    mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
                        override fun onSuccess() { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                        override fun onFailure(r: Int) { handler.postDelayed({ standardCreateGroup(mgr, ch, retryCount + 1, groupMode) }, 2000L) }
                    })
                } else if (legacyChannelRestrictionApplied) {
                    // The restriction is a frequency list, so a unit whose P2P firmware cannot host a
                    // group owner on that band has nowhere legal to put one and fails outright rather
                    // than landing on the other. Step down the ladder rather than straight to no
                    // restriction: naming 2.4 GHz gives the unit somewhere to go, and clearing is
                    // what happens once every rung has been offered.
                    legacyChannelAttempt++
                    AppLog.w(
                        "WifiDirectManager: no group formed while an operating channel was requested " +
                            "($reasonStr) - this unit cannot host a group owner on that band. Trying " +
                            "the next channel it was offered."
                    )
                    markP2pRequest()
                    WifiP2pChannelCompat.clearOperatingChannel(mgr, ch, handler) { _, _, detail ->
                        legacyChannelRestrictionApplied = false
                        AppLog.i("WifiDirectManager: operating channel restriction cleared ($detail).")
                        createQuietGroup(0)
                    }
                } else {
                    if (P2pCreateWedgePolicy.isRefusalHonest(reason, msSinceAcceptedCreate())) {
                        reportGroupRefusal(reasonStr)
                    } else {
                        AppLog.w(
                            "WifiDirectManager: no group yet ($reasonStr), but this unit is still " +
                                "finishing a create of ours rather than refusing to host one. Waiting for it."
                        )
                    }
                    isGroupCreatingOrCreated = false
                    releaseNativeCreateWindow("the unit refused the group")
                }
            }
        })
    }

    private fun onStandardCreateSucceeded(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        groupMode: String,
        variant: P2pCreateWedgePolicy.Variant,
    ) {
        AppLog.i("WifiDirectManager: Standard createGroup SUCCESS!")
        ConnectionStageTracker.report(ConnectionStage.CREATING_NETWORK)
        noteAcceptedCreate(variant)
        noteGroupFormed()
        // Read before releaseLegacyChannelRestriction() clears the flag. A group formed while the
        // restriction stood is on the channel that was asked for, and only that disproves the
        // record - the unrestricted create below it is the failure the record describes.
        if (legacyChannelRestrictionApplied) {
            ConnectionIssues.clear(context, ConnectionIssue.FIVE_GHZ_CHANNEL_REFUSED)
        }
        nativeGroupCreationMode = groupMode
        // The platform chose the band here, so there is no mismatch to correct, and no
        // frequency was named either.
        nativeRequestedBand = NativeGroupBandPolicy.Band.UNSPECIFIED
        nativeRequestedFrequency = 0
        isGroupOwner = true
        // The restriction is a whitelist of one frequency and it applies to the whole P2P
        // interface, not just to group creation - while it stands, the 2.4 GHz social
        // channels are banned and discovery cannot run. The group keeps the channel it was
        // formed on, so the restriction has done its work and must come off now.
        releaseLegacyChannelRestriction(mgr, ch)
        handler.postDelayed({
            mgr.requestConnectionInfo(ch, this@WifiDirectManager)
            mgr.requestGroupInfo(ch, this@WifiDirectManager)
        }, 1000L)
    }

    /**
     * Gives the frequency list back once the group exists.
     *
     * Not tidiness: the restriction bans every frequency except the one it names, including the
     * 2.4 GHz social channels discovery runs on, and it is state in the supplicant that outlives
     * this app. A group that has already formed keeps its channel, so nothing is lost by clearing.
     */
    private fun releaseLegacyChannelRestriction(
        mgr: WifiP2pManager,
        ch: WifiP2pManager.Channel,
        force: Boolean = false,
    ) {
        // A request that timed out may still have reached the supplicant, so a teardown clears that
        // too. Not a bare force: clearing when nothing was ever asked for costs the compat object's
        // whole answer timeout on the drivers that never call back, for nothing.
        if (!legacyChannelRestrictionApplied && !(force && legacyChannelRequestUnanswered)) return
        legacyChannelRestrictionApplied = false
        WifiP2pChannelCompat.clearOperatingChannel(mgr, ch, handler) { applied, _, detail ->
            if (applied) {
                AppLog.i("WifiDirectManager: operating channel restriction released; discovery can use the social channels again.")
            } else {
                AppLog.w(
                    "WifiDirectManager: could not release the operating channel restriction ($detail). " +
                        "Peer discovery may stay crippled until WiFi is restarted."
                )
            }
        }
    }

    private fun isNativeAaMode(): Boolean {
        return App.provide(context).settings.wifiConnectionMode == WifiLauncherMode.NATIVE
    }

    private fun shouldRetryNativeGroupFor5Ghz(frequency: Int): Boolean =
        NativeGroupBandPolicy.shouldRetryFor5Ghz(
            requested = nativeRequestedBand,
            frequencyMhz = frequency,
            retriesSoFar = native5GhzBandMismatchRetries,
            maxRetries = MAX_NATIVE_5GHZ_BAND_MISMATCH_RETRIES,
        )

    @SuppressLint("MissingPermission")
    private fun removeGroupAndRetryNative5Ghz() {
        val mgr = manager ?: return
        val ch = channel ?: return
        invalidateNativeGroupCredentials("retrying the 5 GHz band")
        val gen = generation
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (supersededByStop(gen, "5GHz band-mismatch retry")) return
                delayedCreateQuietGroup(0)
            }
            override fun onFailure(reason: Int) {
                AppLog.w("WifiDirectManager: removeGroup before 5GHz band-mismatch retry failed: ${getP2pErrorString(reason)}")
                if (supersededByStop(gen, "5GHz band-mismatch retry")) return
                delayedCreateQuietGroup(0)
            }
        })
    }

    /**
     * Tear down any current P2P group and create a fresh native quiet-host group.
     * [forceStandard] skips the band request altogether, for phones that can't join a 5GHz group
     * owner. It asks for nothing rather than asking for 2.4 GHz, so the platform decides.
     *
     * Fresh group, same name: the teardown stays, but the create asks for the kept identity (see
     * [chooseNativeGroupIdentity]), so a recreate costs the phone nothing it saved. Below API 29 the
     * create can only reinvoke the platform's stored profile, so a rename has to delete it first;
     * deletePersistentGroup is refused from Android 11, which is well above that range.
     */
    @SuppressLint("MissingPermission")
    private fun recreateNativeGroup(forceStandard: Boolean) {
        val mgr = manager ?: return
        val ch = channel ?: return
        // Before the async removeGroup below, not after it: the manual poke re-inits the mode and
        // immediately asks for a credential refresh, which landed in this gap and started a second
        // create chain.
        claimNativeCreateWindow("recreating the group")
        invalidateNativeGroupCredentials("recreating the group")
        val gen = generation
        val appSettings = App.provide(context).settings
        val create = {
            if (forceStandard) standardCreateGroup(mgr, ch, 0, NATIVE_GROUP_MODE_STANDARD_FALLBACK)
            else delayedCreateQuietGroup(0)
        }
        val createFresh = {
            // The removeGroup below is the teardown a user exit relies on. If stop() ran while it was
            // in flight, recreating here puts a group back up that nothing is managing and that the
            // phone will keep trying to join - the opposite of what the exit asked for.
            if (supersededByStop(gen, "Native AA group recreate")) {
                Unit
            } else if (P2pIdentityRotationPolicy.purgeBeforeCreate(
                    Build.VERSION.SDK_INT,
                    appSettings.wifiDirectStableIdentity,
                    appSettings.wifiDirectRotationPending,
                )
            ) {
                // Consumed here, not after the create: a stop() landing mid-purge must not leave
                // the request behind to purge a group nobody asked to rename.
                appSettings.wifiDirectRotationPending = false
                P2pPersistentGroupPurge.purge(mgr, ch, localDeviceAddress, handler) { verdict ->
                    lastPersistentPurgeVerdict = verdict
                    AppLog.i("WifiDirectManager: persistent profile purge: $verdict")
                    if (!supersededByStop(gen, "Native AA group recreate")) create()
                }
            } else {
                appSettings.wifiDirectRotationPending = false
                lastPersistentPurgeVerdict = null
                create()
            }
        }
        markP2pRequest()
        mgr.removeGroup(ch, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { createFresh() }
            override fun onFailure(reason: Int) {
                AppLog.i("WifiDirectManager: Native AA removeGroup before recreate failed (reason=${getP2pErrorString(reason)}); expected if no group existed")
                createFresh()
            }
        })
    }

    /** Recover a native quiet-host group when the phone never joins: recreate a fresh one, and
     *  after a couple of tries drop the band request entirely and let the platform choose. Bounded
     *  by [MAX_NATIVE_JOIN_RECREATES], and stood down entirely by [NativeJoinRecoveryPolicy] while
     *  the phone has never opened the Bluetooth channel that hands it the credentials. */
    private fun recoverNativeGroup(reason: String, ignoreClientList: Boolean = false) {
        cancelNativeJoinWatchdog()
        // A phone that left at the supplicant layer can stay on the client list, which is exactly
        // the state the stale-group path exists to repair, so that caller opts out of this guard.
        if (!ignoreClientList && isClientConnected) return
        if (isNativeSessionConnected?.invoke() == true) {
            AppLog.i("WifiDirectManager: recoverNativeGroup() called but a session is already connected — not tearing down a working connection.")
            nativeRecreateCount = 0
            return
        }
        val dialledThisArming = isPhoneEverOpenedAaChannel?.invoke() ?: true
        when (NativeJoinRecoveryPolicy.step(dialledThisArming, nativeRecreateCount, MAX_NATIVE_JOIN_RECREATES)) {
            NativeJoinRecoveryPolicy.Step.HOLD_PHONE_NEVER_DIALLED -> {
                AppLog.i("WifiDirectManager: Native AA — the phone has not opened the Android Auto Bluetooth channel since this attempt started, so it was never handed these credentials; recreating the WiFi group would not be the repair. Leaving it up.")
                return
            }
            NativeJoinRecoveryPolicy.Step.GIVE_UP -> {
                AppLog.w("WifiDirectManager: Native AA — phone still not connected after $nativeRecreateCount recreations ($reason); giving up until the next start.")
                return
            }
            NativeJoinRecoveryPolicy.Step.RECREATE -> Unit
        }
        nativeRecreateCount++
        val forceStandard = nativeRecreateCount >= NATIVE_FORCE_STANDARD_AFTER
        // Not "forcing 2.4GHz", which this line claimed for as long as it has existed: forceStandard
        // calls the no-band createGroup, so what actually happens is that the platform chooses.
        AppLog.w("WifiDirectManager: Native AA recovery ($reason): recreate attempt $nativeRecreateCount/$MAX_NATIVE_JOIN_RECREATES${if (forceStandard) ", dropping the band request and letting this unit choose" else ""}.")
        recreateNativeGroup(forceStandard)
    }

    /** (Re)arm the native join watchdog; no-op unless we are a native-mode group owner with no
     *  client yet. */
    /**
     * Arms the join watchdog, at most once per group.
     *
     * [groupSsid] identifies the group this is being armed for. `onGroupInfoAvailable` fires three or
     * four times per group and once more for every CONNECTION_CHANGED, and re-arming pushed the
     * deadline out by the full timeout each time - so a phone retrying its join at any cadence faster
     * than the timeout starved the watchdog indefinitely and the bounded recovery it exists to run
     * never ran. Pass null where the re-arm is a real state change (a client leaving, WiFi coming
     * back), which resets the identity and lets the next group arm afresh.
     */
    private fun armNativeJoinWatchdog(groupSsid: String? = null) {
        if (groupSsid != null && groupSsid == nativeJoinWatchdogSsid) return
        nativeJoinWatchdogSsid = groupSsid
        handler.removeCallbacks(nativeJoinWatchdog)
        // A proven group arms too: its client list can still name a phone that has gone, and the
        // watchdog is what notices the phone stopped coming back to it.
        if (isNativeAaMode() && isGroupOwner && isNativeSessionConnected?.invoke() != true &&
            (!isClientConnected || nativeGroupHostedSession)) {
            handler.postDelayed(nativeJoinWatchdog, NATIVE_JOIN_TIMEOUT_MS)
        }
    }

    private fun cancelNativeJoinWatchdog() {
        handler.removeCallbacks(nativeJoinWatchdog)
    }

    private fun notifyNativeGroupStarted(ssid: String, frequency: Int, band: String) {
        val frequencyText = if (frequency > 0) "$frequency MHz" else "frequency unknown"
        val message = "Native AA WiFi Direct: $band ($frequencyText), $nativeGroupCreationMode"
        if (message == lastNativeGroupStatusMessage) return

        lastNativeGroupStatusMessage = message
        AppLog.i("WifiDirectManager: $message, SSID=$ssid")
        // The pill's third line, in place of a toast that covered the pill on every bring-up.
        val note =
            if (WifiP2pChannelPolicy.isClientUnfriendly(frequency)) ConnectionNetworkDetail.Note.CLIENT_UNFRIENDLY_CHANNEL
            else ConnectionNetworkDetail.Note.NONE
        ConnectionStageTracker.reportNetwork(ConnectionNetworkDetail(frequency, note))
    }

    private fun showToast(message: String) {
        handler.post {
            ToastUtils.showToast(context, message, Toast.LENGTH_LONG)
        }
    }

    private val macRegex = Regex("^([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")
    private val maskedMacs = setOf("00:00:00:00:00:00", "02:00:00:00:00:00")

    /**
     * The group owner's BSSID as the framework knows it. `getGroupOwnerBssid()` is not in the
     * public SDK but some vendor trees carry it, and where it exists it is a direct answer rather
     * than a derivation, so it is asked first.
     */
    private fun getGroupOwnerBssid(group: WifiP2pGroup): String? = try {
        group.javaClass.getMethod("getGroupOwnerBssid").invoke(group)?.toString()
    } catch (e: Throwable) {
        null
    }

    /**
     * Every source that might name this unit's regulatory domain, one line each.
     *
     * Once per process, at the first group bring-up, because it is a fact about the hardware and a
     * reader needs it in every log: which 5 GHz channels a group owner may use is the domain's answer,
     * not ours, and a refusal reads identically to a bug without it.
     */
    private fun logWifiCountrySourceDump() {
        if (countrySourceDumped) return
        countrySourceDumped = true
        val sources = wifiCountrySources()
        AppLog.i("WifiDirectManager: == WiFi regulatory domain source dump ==")
        for ((label, value) in sources) {
            AppLog.i("WifiDirectManager:   ${label.padEnd(34)} = ${value ?: "null"}")
        }
        AppLog.i("WifiDirectManager: == end, ${WifiCountryPolicy.describe(sources)} ==")
    }

    /**
     * Ordered as `WifiCountryCode.pickCountryCode()` orders them, telephony before the baked-in
     * default, so the dump can be read against what the framework itself would have chosen.
     */
    private fun wifiCountrySources(): Map<String, String?> {
        fun attempt(read: () -> String?) = try { read()?.trim()?.ifEmpty { null } } catch (e: Exception) { "err: ${e.message}" }
        val telephony = try {
            context.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        } catch (e: Exception) { null }
        val sources = linkedMapOf<String, String?>(
            "TelephonyManager.networkCountry" to attempt { telephony?.networkCountryIso },
            "TelephonyManager.simCountry" to attempt { telephony?.simCountryIso },
            "Settings.Global wifi_country_code" to attempt {
                Settings.Global.getString(context.contentResolver, "wifi_country_code")
            },
        )
        for (key in COUNTRY_PROPERTY_KEYS) {
            sources["property $key"] = SystemProperties.get(key, "").trim().ifEmpty { null }
        }
        // Authoritative where it answers, and refused on most units: it is @hide behind
        // CONNECTIVITY_INTERNAL from API 28. Attempted anyway for head units that run this app as
        // system, the same bet SoftApConfigCompat makes.
        sources["WifiManager.getCountryCode()"] = attempt {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wm?.javaClass?.getMethod("getCountryCode")?.invoke(wm) as? String
        }
        // Last, and labelled: this is what the user set the screen to, not what the radio obeys.
        sources["Locale.getDefault() (UI, not radio)"] = attempt {
            java.util.Locale.getDefault().country
        }
        return sources
    }

    /**
     * Every BSSID source and what each answered, printed once per group.
     *
     * With nine of them in the chain, "all fallbacks failed" tells a reporter's log nothing about
     * which hardware refused what. This is the block to ask for, and it turns a second test build
     * into a paste.
     */
    private fun logBssidSourceDump(
        group: WifiP2pGroup,
        iface: String?,
        ssid: String?,
        staticOverride: String?
    ) {
        if (ssid != null && ssid == lastBssidDumpSsid) return
        lastBssidDumpSsid = ssid
        fun report(label: String, value: String?) =
            AppLog.i("WifiDirectManager:   ${label.padEnd(32)} = ${value ?: "null"}")
        AppLog.i("WifiDirectManager: == BSSID source dump (iface=${iface ?: "unknown"}) ==")
        report("static override (Settings)", staticOverride)
        report("getGroupOwnerBssid()", getGroupOwnerBssid(group))
        report("IPv6 link-local ($iface)", InterfaceMacReader.fromIpv6LinkLocal(iface))
        report("IPv6 link-local (any p2p/ap)", InterfaceMacReader.fromIpv6LinkLocal(null))
        report("NetworkInterface.hardwareAddress", getWifiDirectMac(iface))
        report("lastKnownBssid ($lastKnownBssidIface)", lastKnownBssid)
        report("requestDeviceInfo", localDeviceAddress)
        report("group.owner.deviceAddress", group.owner?.deviceAddress)
        report("sysfs / ip link", getMacFromShell(iface))
        report("Settings.Secure p2p address", try {
            Settings.Secure.getString(context.contentResolver, "wifi_p2p_device_address")
        } catch (e: Exception) {
            "err: ${e.message}"
        })
        report("reflection over WifiP2pGroup", getMacFromReflection(group))
        AppLog.i("WifiDirectManager: == end BSSID source dump ==")
    }

    /**
     * Last-resort BSSID lookup: reflect over every declared field (including inherited ones) of
     * the WifiP2pGroup and its owner WifiP2pDevice, looking for any String that looks like a MAC
     * and isn't one of the known privacy-masked placeholders. Some OEM builds mask the public
     * NetworkInterface/deviceAddress getters but leave other internal fields populated with the
     * real value.
     */
    private fun getMacFromReflection(group: WifiP2pGroup): String? {
        val candidates = listOfNotNull(group, group.owner)
        for (obj in candidates) {
            var klass: Class<*>? = obj.javaClass
            while (klass != null) {
                for (field in klass.declaredFields) {
                    try {
                        field.isAccessible = true
                        val value = field.get(obj) as? String ?: continue
                        if (macRegex.matches(value) && value !in maskedMacs) {
                            AppLog.d("WifiDirectManager: getMacFromReflection found candidate in ${klass.simpleName}.${field.name}: $value")
                            return value
                        }
                    } catch (e: Exception) {
                        // Ignore inaccessible/incompatible fields and keep scanning.
                    }
                }
                klass = klass.superclass
            }
        }
        return null
    }

    private fun getMacFromShell(iface: String?): String? {
        // Fallback: If iface is null, try to find a p2p interface name
        val targetIface = iface ?: getInterfaceByIp("192.168.49.1") ?: discoveredInterface

        if (targetIface != null) {
            // Try reading directly from sysfs
            try {
                val file = File("/sys/class/net/$targetIface/address")
                if (file.exists()) {
                    val mac = file.readText().trim().lowercase()
                    if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                        AppLog.i("WifiDirectManager: MAC retrieved via sysfs ($targetIface): $mac")
                        return mac
                    }
                }
            } catch (e: Exception) {}

            // Try ip link
            try {
                val process = Runtime.getRuntime().exec("ip link show $targetIface")
                val reader = process.inputStream.bufferedReader()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val match = Regex("link/ether (([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2})").find(line ?: "")
                    if (match != null) {
                        val mac = match.groupValues[1].lowercase()
                        if (mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") return mac
                    }
                }
            } catch (e: Exception) {}
        }

        // LAST RESORT: Scan ALL interfaces in sysfs for anything that looks like P2P
        AppLog.i("WifiDirectManager: getMacFromShell: Target failed, scanning ALL interfaces in sysfs...")
        try {
            val netDir = File("/sys/class/net")
            val interfaces = netDir.listFiles()
            if (interfaces != null) {
                for (dir in interfaces) {
                    val name = dir.name.lowercase()
                    if (name.contains("p2p") || name.contains("wlan") || name.contains("ap")) {
                        val addrFile = File(dir, "address")
                        if (addrFile.exists()) {
                            val mac = addrFile.readText().trim().lowercase()
                            if (mac.isNotEmpty() && mac != "00:00:00:00:00:00" && mac != "02:00:00:00:00:00") {
                                AppLog.i("WifiDirectManager: Last resort MAC found on ${dir.name}: $mac")
                                return mac
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {}

        return null
    }

    fun stop() {
        AapService.wifiDirectName.value = null
        serverStatus.stop()
        publishServerBadge()
        serverRenamePending = false
        localNameRevision++
        serverPolling = false
        serverDiscoveryScheduled = false
        serverGroupMissingSince = null
        serverGroupRetryAt = 0L
        handler.removeCallbacks(serverGroupPoll)
        publishDiscoveryNetwork(null)
        groupOwnerIpv4 = null
        AppLog.i("WifiDirectManager: Stopping and cleaning up...")
        WifiBandCapability.reportSessionFrequency(0)
        generation++
        credentialsEpoch++
        isGroupCreatingOrCreated = false
        // Counted per bring-up, not per process: a mode the user re-arms is asking us to try again.
        wifiEnableAttempts = 0
        acceptedCreateWithoutGroupSinceMs = 0L
        wedgeCancelSpentForStampMs = 0L
        stuckCreateCancels = 0
        groupInfoEpoch++
        handler.removeCallbacksAndMessages(null)
        // Both of these guard an operation that is finished the moment we stop, and both used to be
        // cleared only by a posted runnable that the line above just cancelled - so a stop() landing
        // inside either window latched it true for the life of the process. A flag set in only one
        // direction is how a long-lived manager ends up unable to re-arm.
        checkGroupAndCreateInFlight = false
        // Same reason as the two above: it marks a create that stop() has just abandoned, and a
        // stale value would make the next refresh wait CREATE_GRACE_MS for a group that is not coming.
        releaseNativeCreateWindow("the mode is stopping")
        // A bring-up this stop just abandoned must not refuse the one that re-arms the mode.
        lastNativeBringUpAtMs = 0L
        isClientConnected = false
        nativeGroupCreationMode = NATIVE_GROUP_MODE_UNKNOWN
        native5GhzBandMismatchRetries = 0
        forgetPerGroupKeys()
        nativeRecreateCount = 0
        lastNativeGroupStatusMessage = null
        ConnectionStageTracker.reportNetwork(null)
        legacyChannelAttempt = 0
        lastGroupRefusalReportAtMs = 0L
        churnWindowStartedAtMs = 0L
        churnEventsInWindow = 0
        churnForeignEventsInWindow = 0
        lastChurnReportAtMs = 0L
        pinnedChannelAbandoned = false
        namedFallbackRefused = false
        // The verdict describes a group that is going away; it must not survive into the next start().
        lastPersistentPurgeVerdict = null
        // Before legacyChannelRequestUnanswered is reset, because that flag is what tells the
        // release a timed-out request may have left a restriction behind.
        manager?.let { mgr -> channel?.let { ch -> releaseLegacyChannelRestriction(mgr, ch, force = true) } }
        legacyChannelRequestUnanswered = false
        AapService.scanningState.value = false
        try { context.unregisterReceiver(receiver) } catch (e: Exception) {}
        // Follows the receiver rather than outliving it: registerReceiverIfNeeded() is a no-op while
        // this is true, so leaving it set means a restarted manager never hears CONNECTION_CHANGED
        // again - no client-connected state, and a join watchdog that fires on a group the phone has
        // successfully joined.
        isReceiverRegistered = false

        if (isGroupOwner) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() { AppLog.d("WifiDirectManager: Final group removal success") }
                override fun onFailure(reason: Int) { AppLog.d("WifiDirectManager: Final group removal failed: $reason") }
            })
        }

        isGroupOwner = false
        isConnected = false
    }
}
