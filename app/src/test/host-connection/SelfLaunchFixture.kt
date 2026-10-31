package selflaunch

import com.andrerinas.openheadunit.connection.bluetooth.xyauto.XyAutoMediaSession

import com.andrerinas.openheadunit.connection.wifi.scan.WifiScanControl
import com.andrerinas.openheadunit.connection.wifi.modes.WifiLauncherNative
import com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.NativeStrategy

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

// This queue holds Main entry and resumed network waits independently of cancellation.
@OptIn(InternalCoroutinesApi::class)
class QueuedMain : MainCoroutineDispatcher(), Delay {
    override val immediate get() = this
    val tasks = ConcurrentLinkedQueue<Runnable>()
    private data class Timer(val at: Long, val run: Runnable)
    private val timers = mutableListOf<Timer>()
    private var now = 0L
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        val timer = Timer(now + timeMillis, Runnable { continuation.resume(Unit) })
        timers.add(timer)
        continuation.invokeOnCancellation { timers.remove(timer) }
    }
    override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
        val timer = Timer(now + timeMillis, block)
        timers.add(timer)
        return object : DisposableHandle { override fun dispose() { timers.remove(timer) } }
    }
    fun advanceBy(ms: Long) {
        now += ms
        val due = timers.filter { it.at <= now }.sortedBy { it.at }
        timers.removeAll(due.toSet())
        due.forEach { it.run.run() }
    }
    fun runOne(): Boolean { val next = tasks.poll() ?: return false; next.run(); return true }
    fun drain() { while (runOne()) {} }
    fun awaitTask() { if (tasks.isEmpty()) advanceBy(checkNotNull(timers.minOfOrNull { it.at }) - now) }
}
enum class ByeByeReason { USER_SELECTION }
// Only the peer matters to Self; production derives it from the socket's address.
class SocketProjectionConnection(val isLoopbackPeer: Boolean)
object HeadUnitScreenConfig { fun unlockResolution() {} }
class FixtureTransport { var wasUserExit = false; fun retirePresentation() {} }
class FixtureSettings { val killOnDisconnect = false }
// The arbiter as the extracted request authority reads it: a claim holds until it ends.
object ConnectionPriorityPolicy { enum class Owner { USB, WIRELESS_STACK, MANUAL } }
object ConnectionArbiter {
    class Claim(val owner: ConnectionPriorityPolicy.Owner = ConnectionPriorityPolicy.Owner.MANUAL) {
        val ended = CompletableDeferred<Unit>()
    }
    fun holds(claim: Claim) = !claim.ended.isCompleted
    fun release(claim: Claim, sessionFormed: Boolean) { claim.ended.complete(Unit) }
}
// The extracted attempt can hold an owed USB scan; nothing here asks the bus.
class UsbRecheck
class CommManager {
    val attemptUserRequested = false
    private var stopEpoch = 0L
    private var accepting = true
    fun appConnectionTicket() = stopEpoch
    fun acceptsAppConnectionTicket(ticket: Long) = accepting && ticket == stopEpoch
    fun acceptsAppRunTicket(ticket: Long) = acceptsAppConnectionTicket(ticket)
    // requestExplicitAppExit: the Stop revokes the ticket and retires the request it invalidated.
    fun stopApp() = synchronized(transportLifecycleLock) { stopEpoch++; accepting = false; retireInvalidRequest() }
    fun openApp() { accepting = true }
    // The extracted members ask the exit policy; the fixture's tickets answer for it.
    private val appExitPolicy = object {
        val stopEpoch get() = this@CommManager.stopEpoch
        fun acceptsConnection(ticket: Long) = acceptsAppConnectionTicket(ticket)
    }
    var socketConnects = 0
    var joins = 0
    // The Headunit Server's answer to a dial: it accepts, or it refuses before any physical connection.
    var serverRunning = true
    // The direct launcher's dial publishes, registers and opens a real attempt. An attempt still
    // forming, or the valid request for the same endpoint, is joined, not preempted: connect
    // returns at once with nothing arrived, as production's admission JOIN and connectIp's
    // Connecting guard do. Validity is production's own predicate, extracted below.
    suspend fun connect(ip: String, port: Int, expectedState: ConnectionState.Disconnected? = null,
                        ticket: Long = appConnectionTicket()) {
        if (!acceptsAppConnectionTicket(ticket)) return
        if (synchronized(transportLifecycleLock) {
                (attemptInFlight() && !physicalConnectionReached) || validRequest()?.endpoint == "$ip:$port"
            }) { joins++; return }
        socketConnects++
        if (serverRunning) arrive(loopback = ip == "127.0.0.1")
        else { publishAttempt(); register(loopback = ip == "127.0.0.1"); end() }
    }
    // Production's request authority: its record, its one validity predicate and its retirement.
    // CONNECTION_REQUEST
    private var connectionRequest: ConnectionRequest? = null
    // IS_VALID
    // VALID_REQUEST
    // RETIRE_REQUEST
    // admitRequest: a request admitted with its ticket, claim and Save, before it can publish
    // (behind a previous session's cleanup). A request it displaces resolves.
    fun claimRequest(endpoint: String, save: ConnectionState.Disconnected? = null) = synchronized(transportLifecycleLock) {
        connectionRequest?.resolved?.complete(Unit)
        connectionRequest = ConnectionRequest(stopEpoch, ConnectionArbiter.Claim(), endpoint, save, null)
    }
    // endRequest without an attempt (refused or cancelled): the record clears and its claim goes.
    fun endRequest() = synchronized(transportLifecycleLock) {
        connectionRequest?.let {
            connectionRequest = null
            it.resolved.complete(Unit)
            ConnectionArbiter.release(it.claim, sessionFormed = false)
        }
    }
    // admitUsbRequest: a logical USB request holds its claim, unpublished, between its tries.
    fun claimUsbRequest() = synchronized(transportLifecycleLock) {
        connectionRequest?.resolved?.complete(Unit)
        connectionRequest = ConnectionRequest(stopEpoch, ConnectionArbiter.Claim(ConnectionPriorityPolicy.Owner.USB), null, null, null)
    }
    // Another route's claim replaced the request's, outside this manager's lock (a USB attach).
    fun loseClaim() { connectionRequest?.claim?.let { ConnectionArbiter.release(it, sessionFormed = false) } }
    var isProjectionVideoFocusSuppressed = false
    val videoFocus = mutableListOf<com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent>()
    fun send(event: com.andrerinas.openheadunit.aap.protocol.messages.VideoFocusEvent) {
        videoFocus.add(event)
    }
    enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART, PROJECTION_UNRAISED }
    sealed class ConnectionState {
        // STATE
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        object StartingTransport : ConnectionState()
        object HandshakeComplete : ConnectionState()
        object TransportStarted : ConnectionState()
        data class Error(val message: String) : ConnectionState()
    }
    private val transportLifecycleLock = Any()
    private var settingsUsbRestartInFlight: ConnectionState.Disconnected? = null
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    // Writable for observer-only cases; lifecycle cases publish through the producer below.
    val connectionState get() = _connectionState
    val isConnected: Boolean get() = _connectionState.value.let {
        it === ConnectionState.Connected || it === ConnectionState.StartingTransport ||
            it === ConnectionState.HandshakeComplete || it === ConnectionState.TransportStarted
    }
    val isUsbSession = false
    var isWirelessSession = false
    var acceptedWirelessSession: Any? = null
    val acceptedWirelessAudioSession get() = acceptedWirelessSession
    var reports = 0
    var metadata = "old"
    // CANCEL
    // Production lifecycle state read and written by the extracted members below.
    private var disconnectRequested = false
    private var connectionAttempt: Attempt? = null
    private var lastEndedAttempt: Any? = null
    private var physicalConnectionReached = false
    private var physicalArrivals = 0L
    private var _connection: SocketProjectionConnection? = null
    private var _transport: FixtureTransport? = null
    private var usbRecoveryOwner: Any = Any()
    private var usbSaveOwner: ConnectionState.Disconnected? = null
    private var outgoingEndpoint: Pair<String, Int>? = null
    private var _disconnectJob: Job? = null
    private val settings = FixtureSettings()
    // Connection IO is queued too: a settled attempt's waiter resumes only when it is drained.
    val io = QueuedMain()
    private val connectionIoDispatcher: CoroutineDispatcher get() = io
    // doDisconnect runs here, after the terminal it follows, until a case or a publication runs it.
    private val teardownQueue = QueuedMain()
    private val _scope = CoroutineScope(SupervisorJob() + teardownQueue)
    private fun withdrawUsbRechecks() {}
    // The extracted request validity asks about an owed USB scan; Self requests carry none.
    private fun recheckCurrent(recheck: UsbRecheck) = true
    private fun scheduleAppExit(userStop: Boolean) {}
    // ATTEMPT
    // IN_FLIGHT
    // BEGIN_ATTEMPT
    // REACH_PHYSICAL
    // RECORD_TERMINAL
    // LAUNCH_OUTCOME
    // LAUNCH_MARK_CLASS
    // LAUNCH_MARK
    // ARRIVED_SINCE
    // Runs as the decision is about to take the lock: IO may publish after the caller's own checks.
    var beforeReport: (() -> Unit)? = null
    // REPORT_FAILURE
    // AWAIT_SETTLED
    // LOOPBACK
    // LIVE_LOOPBACK
    // REACHED_SSL
    // DISCONNECT
    private fun doDisconnect(sendByeBye: Boolean = true, byeByeReason: ByeByeReason = ByeByeReason.USER_SELECTION,
                             reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED) { teardown() }
    // The X action gates on teardown; production joins the disconnect job.
    var teardownGate: CompletableDeferred<Unit>? = null
    suspend fun awaitDisconnectComplete() { teardownGate?.await() ?: _disconnectJob?.join() }

    // Producer. Publication and arrival run connectIp's lock sections in its order with the
    // production primitives above; terminals go through the actual disconnect(); teardown is
    // doDisconnect's lock section. run.py runs those publishers end to end; here they drive Self.
    fun publishAttempt() {
        // publishConnectionAttempt joins an unfinished teardown before it publishes.
        finishTeardown()
        synchronized(transportLifecycleLock) {
            // publishRequest: the valid request becomes this attempt; its record stays.
            val request = validRequest()
            val token = Attempt()
            beginAttempt(token)
            physicalConnectionReached = false
            disconnectRequested = false
            request?.attempt = token
            _connectionState.value = ConnectionState.Connecting
            _connection = null
            request?.resolved?.complete(Unit)
        }
    }
    fun register(loopback: Boolean) = synchronized(transportLifecycleLock) { _connection = SocketProjectionConnection(loopback) }
    fun reachConnection() = synchronized(transportLifecycleLock) {
        reachPhysicalConnection()
        _connectionState.value = ConnectionState.Connected
    }
    fun arrive(loopback: Boolean = true) { publishAttempt(); register(loopback); reachConnection() }
    // A later live phase of the current attempt, as its handshake publishes it.
    fun advance(state: ConnectionState) = synchronized(transportLifecycleLock) { _connectionState.value = state }
    // startHandshake's failure: its Error and the disconnect that follows, in one lock section.
    fun failHandshake() = synchronized(transportLifecycleLock) {
        _connectionState.value = ConnectionState.Error("Handshake failed")
        disconnect(sendByeBye = false, isUserExit = false)
    }
    fun end(reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED): ConnectionState.Disconnected {
        disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false, reason = reason)
        return checkNotNull(_connectionState.value as? ConnectionState.Disconnected)
    }
    // connectUsb's refused-permission diagnostic, under its admission rule; run.py runs the real one.
    fun refusedUsb() = synchronized(transportLifecycleLock) {
        if (!acceptsAppConnectionTicket(appConnectionTicket()) || attemptInFlight()) return@synchronized
        _connectionState.value = ConnectionState.Error("USB permission not granted for device")
    }
    // The queued doDisconnect runs now: physical teardown finishes behind its terminal.
    fun finishTeardown() = teardownQueue.drain()
    private fun teardown() = synchronized(transportLifecycleLock) {
        disconnectRequested = true
        val endedAttempt = connectionAttempt
        connectionAttempt = null
        if (_connectionState.value !is ConnectionState.Disconnected) {
            _transport?.retirePresentation()
            recordTerminal(endedAttempt)
            _connectionState.value = ConnectionState.Disconnected(wasLoopbackSession = isLoopbackSession, hadPhysicalConnection = physicalConnectionReached, endedAttempt = endedAttempt)
        }
        endedAttempt?.settled?.complete(Unit)
        _connection = null
    }
}
class SelfLauncherManager(private val service: Service, private val wifiLauncherManager: WifiLauncherManager) {
    // IS_ACTIVE
    // LAUNCH_PENDING
    private var launchInFlight = false
    private var selfModeVpnWatchdog: Job? = null
    private var launchJob: Job? = null
    private var launchTicket: Long? = null
    private var launchPending = false
    private var launchMark: CommManager.LaunchMark? = null
    private var launchTimeoutJob: Job? = null
    private var settingsLaunchOwner: CommManager.ConnectionState.Disconnected? = null
    private fun installedPath(service: Service) = if (service.modern) SelfLaunchPath.HEADUNIT_SERVER else SelfLaunchPath.LEGACY
    private val VPN_TIMEOUT_MS = 120_000L
    // ADOPT_VPN
    fun stopDummyVpnWatchdog() { selfModeVpnWatchdog?.cancel(); selfModeVpnWatchdog = null }
    fun handleNeverConnect() { service.resolvePrompts++ }
    fun currentLaunch() = launchJob
    fun isLaunchInFlight() = launchInFlight
    fun openAaSettings() { service.settingsPrompts++ }
    fun inFlight() = launchInFlight
    // START
    // LAUNCH_OUTCOME_WAIT
    // ESTABLISHED
    // SETTLE_ANSWERED
    // RETIRE_FULFILLED
    // STOP_CURRENT
    // STOP
    // DETACH_LAUNCH
}
class SelfLauncherServices internal constructor(val aap: Service, val wifiLauncherManager: WifiLauncherManager,
                           val settingsRestart: CommManager.ConnectionState.Disconnected?,
                           val operationTicket: Long,
                           private val arrivalMark: CommManager.LaunchMark) {
    val connectivityManager get() = aap.network
    val fakeNetwork = Any()
    val fakeWifiInfo = Any()
    // ALLOW_LAUNCH
    // ARRIVED_SINCE_LAUNCH
}
open class SelfLauncher(val manager: SelfLauncherManager, val services: SelfLauncherServices) {
    val commManager get() = services.aap.commManager
    open val name = "fixture"
    open suspend fun run(): Boolean { services.aap.fallbacks++; return false }
}
class SelfLauncherLegacy(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services) {
    // LEGACY_RUN
    // LEGACY_WAIT
}
class SelfLauncherV17_4(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services) {
    override suspend fun run(): Boolean {
        if (services.aap.useRealDirect) return ActualSelfLauncherV17_4(manager, services).run()
        services.aap.directConnects++
        services.aap.directLaunchHook?.invoke()
        return true
    }
}
class ActualSelfLauncherV17_4(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services) {
    // MODERN_RUN
}
class SelfLauncherBroadcast(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)
class SelfLauncherBTDiscovery(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)

// Android effects are observable at the same calls made by production methods.
object App { fun provide(service: Service) = service }
object AppLog { fun i(s: String) {}; fun w(s: String) {}; fun w(s: String, e: Throwable) {}; fun w(tag: String, s: String) {}; fun e(s: String) {} }
object VpnControl {
    fun isVpnAvailable() = true
    fun isPrepared(service: Service) = service.vpnPrepared
    fun startVpn(service: Service, excludeSelf: Boolean) {
        service.vpnExcludeSelfRequests.add(excludeSelf)
        service.vpnCommands.add(service.vpnRequests.issue())
    }
}
class ConnectivityManager { var activeNetwork: Any? = null }
object Context { const val CONNECTIVITY_SERVICE = "connectivity" }
object Build { object VERSION { var SDK_INT = 23 }; object VERSION_CODES { const val M = 23 } }
const val AA_PACKAGE = "fixture.gearhead"
class Intent(var action: String? = null) {
    constructor(context: Service, target: Class<*>) : this()
    private val extras = mutableMapOf<String, Any>()
    fun setPackage(name: String) {}
    fun setClassName(pkg: String, name: String) {}
    fun addFlags(flags: Int) {}
    fun putExtra(key: String, value: Any): Intent = apply { extras[key] = value }
    fun getBooleanExtra(key: String, default: Boolean) = extras[key] as? Boolean ?: default
    fun getLongExtra(key: String, default: Long) = extras[key] as? Long ?: default
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 1 }
}
object ContextCompat {
    fun startForegroundService(service: Service, intent: Intent) { service.homeCommands.add(intent) }
}
object Toast { const val LENGTH_LONG = 1 }
object ToastUtils { fun showToast(service: Service, message: String, length: Int) { service.toasts++ } }
object HomeFragment { var forceSelfModeLaunch = false }
class Main(private val service: Service) {
    fun requestSelf() = forceSelfModeLaunch()
    // MAIN_COMMAND
}
class Home(private val service: Service) {
    private val commManager get() = service.commManager
    private fun requireContext() = service
    // A manual Home launch captures its ticket at input, as startSelfMode's default does.
    fun requestSelf(needsVpn: Boolean) = startSelfModeInternal(needsVpn, commManager.appConnectionTicket())
    // HOME_COMMAND
}
class FixtureMedia { var isActive = false; fun setMetadata(value: Any?) {} }
class WifiLauncherManual(manager: WifiLauncherManager)
class WifiLauncherManager {
    private val generation = WirelessCleanupGeneration()
    fun cleanupTicket() = generation.capture()
    fun isCleanupCurrent(ticket: Any) = generation.isCurrent(ticket)
    var cancelledByUser = false
    fun noteRearm() { cancelledByUser = false; generation.advance() }
    var active: Any? = null
    var listenerStarts = 0
    var stops = 0
    val sharedServices get() = this
    fun startWirelessServer(launcher: Any) { listenerStarts++ }
    fun stopForUser() { stops++ }
    fun stop() { stops++ }
}
class UsbLauncherManager { var projectionHandshakeFailures = 0; fun onHandshakeFailed() {}; fun isSwitchingToProjection() = false; fun stopForUser() {} }
enum class ConnectionStage { IDLE, USB_ATTACHED, USB_SWITCHING }
object ConnectionStageTracker {
    val stage = MutableStateFlow(ConnectionStage.IDLE)
    fun clear() {}
}
object SessionStateIntent {
    const val STATE_CONNECTING = 1; const val STATE_CONNECTED = 2; const val STATE_PROJECTING = 3; const val STATE_DISCONNECTED = 4
    const val REASON_SETTINGS_RESTART = 4; const val REASON_USER_EXIT = 1; const val REASON_LINK_LOST = 2; const val REASON_PHONE_LEFT = 3
}
object StationStandDown { fun onSessionLive(service: Any, held: Long?) {} }
object SystemClock { fun elapsedRealtime() = 0L }
class Service(private var hasEverConnected: Boolean = true, val commManager: CommManager = CommManager()) : AutoCloseable {
    // Runs as the observer starts handling a state: IO may publish after the collector took it.
    var beforeObserve: (() -> Unit)? = null
    private inner class ReconnectTimer { fun onStateChanged() { beforeObserve?.let { beforeObserve = null; it() } } }
    private val automaticReconnect = ReconnectTimer()
    private val usbReconnect = ReconnectTimer()
    val main = QueuedMain()
    val serviceScope = CoroutineScope(SupervisorJob() + main)
    val io get() = commManager.io
    val wifiLauncherManager = WifiLauncherManager()
    val usbLauncherManager = UsbLauncherManager()
    val manager = SelfLauncherManager(this, wifiLauncherManager)
    val selfLauncherManager get() = manager
    private var projectingSinceMs = 0L
    private var projectionRaisesThisSession = 0
    private var unprojectedEndsInARow = 0
    private var sessionEndedByHand = false
    private val packageName = "fixture"
    private val ACTION_REQUEST_NIGHT_MODE_UPDATE = "night"
    var isDestroying = false
    var connectedCallbacks = 0
    var ordinaryDisconnects = 0
    fun startObserver() { observeConnectionState() }
    private fun onConnected() { connectedCallbacks++ }
    // The actual session-end VPN decision and Self end branch. Production reaches the end branch
    // only when no newer arm took the cleanup ticket; wireless-cleanup.py runs that selection.
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        val superseded = commManager.connectionState.value !== state
        // VPN_RELEASE
        if (state.isSettingsRestart) return
        applySelfEndPolicy(state)
        if (!state.wasLoopbackSession) ordinaryDisconnects++
    }
    private fun emitSessionState(state: Int, reason: Int = 0) {}
    private fun quiesceWirelessForWiredSession() {}
    private fun wifiLockHeldForMs(): Long? = null
    private fun cancelProjectionRaiseDeadline() {}
    private fun armProjectionRaiseDeadline(value: Any) {}
    private fun launchAapProjectionActivity() = Unit
    private fun sendBroadcast(intent: Intent) {}
    // XYAUTO
    private fun maybeAutoResumePlaybackOnReconnect() {}
    // SELF_END_POLICY
    // OBSERVER
    val network = ConnectivityManager()
    var modern = false
    var activities = 0
    var directConnects = 0
    var directLaunchHook: (suspend () -> Unit)? = null
    var useRealDirect = false
    var settingsPrompts = 0
    var toasts = 0
    var fallbacks = 0
    val homeCommands = mutableListOf<Intent>()
    internal val vpnRequests = DummyVpnStartRequests()
    val vpnCommands = mutableListOf<String>()
    var tunnel = false
    fun deliverVpn() { vpnCommands.toList().forEach { vpnRequests.runIfCurrent(it) { tunnel = true } }; vpnCommands.clear() }
    private fun safeMediaSessionCall(action: (FixtureMedia) -> Unit) { action(FixtureMedia()) }
    private fun updateMediaSessionState(connected: Boolean) {}
    fun deliverHomeCommand() {
        val intent = homeCommands.removeAt(0)
        val operationTicket = intent.getLongExtra(EXTRA_OPERATION_TICKET, commManager.appConnectionTicket())
        if (!commManager.acceptsAppConnectionTicket(operationTicket)) return
        when (intent.action) {
            // START_ACTION
        }
    }
    var dummyVpnOwner: DummyVpnPolicy.Owner? = null
    var vpnPrepared = true
    val vpnExcludeSelfRequests = mutableListOf<Boolean>()
    var vpnReleases = 0
    var vpnStops = 0
    var liveVpnReleases = 0
    var resolvePrompts = 0
    var usbCheckPendingForSettings: UsbRecheck? = null
    var bluetoothLaunchPendingForSettings = false
    var wirelessRearmPendingForSettings = false
    fun getSystemService(name: String): Any = network
    fun startActivity(intent: Intent) { activities++ }
    fun stopDummyVpn(reason: DummyVpnPolicy.Reason) {
        if (reason == DummyVpnPolicy.Reason.SELF_MODE_SESSION_LIVE) liveVpnReleases++ else vpnStops++
        if (DummyVpnPolicy.shouldStop(dummyVpnOwner, reason)) {
            vpnReleases++
            vpnRequests.stop { tunnel = false }
            dummyVpnOwner = null
            manager.stopDummyVpnWatchdog()
        }
    }
    fun cancelAction(intent: Intent? = null): Int {
        when (ACTION_CANCEL_WIRELESS) {
            // CANCEL_ACTION
        }
        return START_STICKY
    }
    fun save(): CommManager.ConnectionState.Disconnected {
        val saved = CommManager.ConnectionState.Disconnected(
            reason = CommManager.DisconnectReason.SETTINGS_RESTART,
            settingsRestartUntilMs = Long.MAX_VALUE)
        commManager.connectionState.value = saved
        return saved
    }
    override fun close() { serviceScope.cancel(); main.drain() }
    companion object {
        const val EXTRA_OPERATION_TICKET = "accepted_operation_ticket"
        fun operationIntent(context: Service, ticket: Long) =
            Intent(context, Service::class.java).putExtra(EXTRA_OPERATION_TICKET, ticket)
        const val ACTION_START_SELF_MODE = "start-self"
        const val EXTRA_SELF_MODE_VPN = "needs-vpn"
        const val ACTION_CANCEL_WIRELESS = "cancel"
        const val EXTRA_USB_ATTEMPT = "usb"
        const val START_STICKY = 1
    }
}

fun main() {
    val legacyDeadline = SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS
    fun Service.settle() { main.drain(); io.drain(); main.drain(); io.drain(); main.drain() }
    // Capture at Self admission, not when Main or IO eventually starts.
    for (stop in listOf(false, true)) for (pause in listOf("MAIN", "IO", "NETWORK")) Service().use { s ->
        s.modern = pause != "NETWORK"
        s.useRealDirect = true
        s.manager.start()
        if (pause != "MAIN") s.main.drain()
        if (stop) s.commManager.stopApp()
        s.commManager.openApp()
        if (pause == "NETWORK") {
            s.network.activeNetwork = Any()
            s.main.advanceBy(100)
        }
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.commManager.socketConnects == if (s.modern && !stop) 1 else 0)
        check(s.activities == if (!s.modern && !stop) 1 else 0)
        check(s.settingsPrompts == 0 && s.toasts == 0 && s.commManager.reports == 0) { "obsolete Self launch showed failure UI" }
        if (stop) {
            // New explicit Self input still works after the rejected old launch completes.
            s.modern = true
            s.manager.start(); s.main.drain(); s.io.drain(); s.main.drain()
            check(s.commManager.socketConnects == 1)
        }
    }
    // Fresh input overlaps the obsolete owner, before Main/IO/network cancellation
    // cleanup has run. An Open without Stop must still coalesce into that same owner.
    for (stop in listOf(false, true)) for (pause in listOf("MAIN", "IO", "NETWORK")) Service().use { s ->
        s.modern = pause != "NETWORK"
        s.useRealDirect = true
        val originalTicket = s.commManager.appConnectionTicket()
        s.manager.start(needsDummyVpn = true, ticket = originalTicket)
        val original = checkNotNull(s.manager.currentLaunch())
        s.deliverVpn()
        check(s.tunnel && s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE)
        if (pause != "MAIN") s.main.drain()
        val delayedIo = if (pause == "IO") checkNotNull(s.io.tasks.poll()) else null
        check(s.manager.inFlight() && original.isActive)
        if (stop) s.commManager.stopApp()
        s.commManager.openApp()
        val freshTicket = s.commManager.appConnectionTicket()
        if (stop) s.modern = true
        s.manager.start(needsDummyVpn = true, ticket = freshTicket)
        val replacement = checkNotNull(s.manager.currentLaunch())
        check((replacement === original) == !stop) { "$pause fresh input coalesced into a revoked launch" }
        check(original.isCancelled == stop)
        check(s.manager.isActive && s.manager.inFlight() && replacement.isActive)
        // Neither another same-ticket request nor a late obsolete input can replace t1.
        s.manager.start(ticket = freshTicket)
        if (stop) s.manager.start(ticket = originalTicket)
        check(s.manager.currentLaunch() === replacement)
        if (pause == "NETWORK") {
            s.network.activeNetwork = Any()
            s.main.advanceBy(100)
        }
        s.main.drain()
        check(s.manager.currentLaunch() === replacement && s.manager.isActive)
        if (s.modern) check(s.manager.inFlight()) { "old completion cleared the replacement's gate" }
        if (!stop && delayedIo != null) s.io.tasks.add(delayedIo)
        s.io.drain(); s.main.drain()
        if (stop && delayedIo != null) {
            // Let t1's real modern run connect before t0's queued IO and completion.
            check(s.commManager.isConnected && s.commManager.socketConnects == 1)
            delayedIo.run()
            s.main.drain()
        }
        check(s.manager.currentLaunch() === replacement && !replacement.isCancelled)
        check(s.commManager.socketConnects == if (s.modern) 1 else 0)
        check(s.activities == if (s.modern) 0 else 1)
        check(!s.manager.inFlight() && s.manager.isActive)
        // A live arrival releases the launch's tun, as projection does once a phone is live;
        // a launch still waiting for its phone keeps it.
        check(s.tunnel == !s.modern && s.dummyVpnOwner == (if (s.modern) null else DummyVpnPolicy.Owner.SELF_MODE)) {
            "$pause tun ownership after the replacement's own outcome (modern=${s.modern})"
        }
        check(s.vpnStops == 0 && s.wifiLauncherManager.stops == 0) { "obsolete owner stopped replacement resources" }
        if (!s.modern) {
            // The compatible legacy launch still owns its late-arriving phone.
            s.commManager.arrive(loopback = true)
            s.manager.onConnectionEstablished()
        }
        s.main.advanceBy(legacyDeadline)
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.manager.currentLaunch() === replacement && !replacement.isCancelled)
        check(s.settingsPrompts == 0 && s.toasts == 0 && s.resolvePrompts == 0 && s.commManager.reports == 0) {
            "obsolete Self launch showed failure UI"
        }
        check(s.commManager.socketConnects == if (s.modern) 1 else 0)
        check(s.manager.isActive && s.tunnel == false)
    }
    // A completed launch whose loopback session is still up is not stale work. Stop B ->
    // Open C -> fresh Self input must coalesce into that session without clearing Self mode,
    // which the call-screen raise policy consumes.
    for (stop in listOf(false, true)) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.commManager.isConnected && s.commManager.socketConnects == 1)
        s.manager.onConnectionEstablished()
        check(s.manager.isActive && !s.manager.inFlight())
        if (stop) s.commManager.stopApp()
        s.commManager.openApp()
        s.manager.start(ticket = s.commManager.appConnectionTicket())
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.manager.isActive) { "fresh Self input cleared the established Self session (stop=$stop)" }
        check(s.commManager.isConnected && s.commManager.socketConnects == 1)
        check(s.vpnStops == 0 && s.wifiLauncherManager.stops == 0)
    }
    // The launch's IO has published Connected but its Main resume is still queued when Stop B ->
    // Open C -> fresh Self input arrive. The obsolete launch then cancels itself on its old ticket;
    // that late cleanup must not clear Self mode on the loopback session it already produced.
    for (stop in listOf(false, true)) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        s.manager.start()
        val original = checkNotNull(s.manager.currentLaunch())
        s.main.drain()
        s.io.drain() // Connect publishes Connected; the launcher's Main resume stays queued.
        check(s.commManager.isConnected && s.commManager.socketConnects == 1)
        s.manager.onConnectionEstablished()
        if (stop) s.commManager.stopApp()
        s.commManager.openApp()
        s.manager.start(ticket = s.commManager.appConnectionTicket())
        s.main.drain(); s.io.drain(); s.main.drain()
        check(original.isCancelled == stop)
        check(s.manager.isActive) { "late obsolete launch cleanup cleared live Self mode (stop=$stop)" }
        check(s.commManager.isConnected && s.commManager.socketConnects == 1)
        check(s.vpnStops == 0 && s.wifiLauncherManager.stops == 0)
    }
    // A completed launch keeps its Job after its session went live. That session's end retires
    // it, while a launch accepted after the end is not that session's and keeps its deadline.
    for (newer in listOf(false, true)) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        val completed = checkNotNull(s.manager.currentLaunch())
        check(s.commManager.isConnected && !s.manager.inFlight() && s.manager.isActive)
        val ended = s.commManager.end()
        check(!s.manager.isActive) { "an ended loopback session left Self mode behind" }
        if (newer) { s.modern = false; s.manager.start() }
        val current = s.manager.currentLaunch()
        s.applySelfEndPolicy(ended)
        if (newer) {
            check(s.manager.currentLaunch() === current && checkNotNull(current).isActive && s.manager.isLaunchPending) {
                "the ended session's policy retired a launch accepted after it"
            }
            check(s.wifiLauncherManager.stops == 0) { "the ended session stopped the server a newer launch waits on" }
        } else {
            check(s.manager.currentLaunch() == null && completed.isCompleted) { "a completed launch outlived its session's end" }
            check(s.wifiLauncherManager.stops == 1)
        }
    }
    Service().use { s ->
        Main(s).requestSelf(); s.deliverHomeCommand(); s.main.drain()
        val originalJob = s.manager.currentLaunch()
        check(s.manager.inFlight() && s.wifiLauncherManager.listenerStarts == 1)
        check(s.vpnCommands.isEmpty() && s.activities == 0)
        Home(s).requestSelf(true); s.deliverHomeCommand()
        check(s.manager.currentLaunch() === originalJob)
        check(s.vpnExcludeSelfRequests == listOf(false))
        s.deliverVpn(); check(s.tunnel)
        s.network.activeNetwork = Any()
        s.main.advanceBy(100); s.main.drain()
        check(s.activities == 1 && s.wifiLauncherManager.listenerStarts == 1)
    }
    Service().use { s ->
        s.dummyVpnOwner = DummyVpnPolicy.Owner.SESSION
        VpnControl.startVpn(s, true)
        val oldRequest = s.vpnCommands.single()
        Home(s).requestSelf(true)
        check(s.vpnCommands.size == 1) { "Home must not start VPN before admission" }
        // Current old terminal cleanup wins before the service receives the Self command.
        s.stopDummyVpn(DummyVpnPolicy.Reason.SESSION_ENDED)
        s.deliverHomeCommand()
        check(s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE)
        check(!s.vpnRequests.runIfCurrent(oldRequest) { error("old request survived cleanup") })
        s.deliverVpn()
        check(s.tunnel && s.vpnExcludeSelfRequests == listOf(true, false))
    }
    for (needsVpn in listOf(false, true)) Service().use { s ->
        s.commManager.arrive(loopback = false)
        Home(s).requestSelf(needsVpn); s.deliverHomeCommand()
        check(s.vpnCommands.isEmpty()) { "rejected launch requested VPN" }
    }
    Service().use { s ->
        s.modern = true
        Home(s).requestSelf(false); s.deliverHomeCommand()
        check(s.vpnCommands.isEmpty()) { "online/5277 launch gained an unrequested VPN" }
    }
    Service().use { s ->
        s.dummyVpnOwner = DummyVpnPolicy.Owner.SESSION
        s.manager.start()
        // Old terminal cleanup may already have preserved this newly accepted Self launch.
        // Cancelling before its queued body runs must still release the transferred VPN.
        check(s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE)
        s.manager.stop()
        s.main.drain()
        check(s.dummyVpnOwner == null && s.vpnReleases == 1)
        check(s.activities == 0 && s.wifiLauncherManager.listenerStarts == 0)
    }
    for (prepared in listOf(true, false)) Service().use { s ->
        // The old Native session owns a VPN when a new Self launch overtakes its observer.
        s.modern = true
        s.vpnPrepared = prepared
        s.dummyVpnOwner = DummyVpnPolicy.Owner.SESSION
        s.manager.start()
        s.main.drain()
        check(s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE)
        check(s.vpnExcludeSelfRequests == if (prepared) listOf(false) else emptyList<Boolean>())
        s.main.advanceBy(120_000L)
        s.main.drain()
        check(s.dummyVpnOwner == null && s.vpnReleases == 1) { "Self timeout left old SESSION VPN alive" }
    }
    // The launch's loopback session can connect and end before Main observes any of it. Its
    // deadline must not report "never connected", nor publish over that terminal; a launch
    // whose only attempt failed, or that saw nothing at all, still fails once.
    for (modern in listOf(true, false)) for (state in listOf("loopback-ended", "unchanged", "unconnected-terminal")) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.manager.isActive && !s.manager.inFlight() && s.commManager.reports == 0) {
            "launch not waiting for its deadline (modern=$modern active=${s.manager.isActive} inFlight=${s.manager.inFlight()} reports=${s.commManager.reports})"
        }
        val ended = when (state) {
            "loopback-ended" -> { s.commManager.arrive(loopback = true); s.commManager.end() }
            "unconnected-terminal" -> { s.commManager.publishAttempt(); s.commManager.register(loopback = true); s.commManager.end() }
            else -> null
        }
        s.main.advanceBy(legacyDeadline)
        s.settle()
        val reportsExpected = if (state == "loopback-ended") 0 else 1
        check(s.commManager.reports == reportsExpected && s.resolvePrompts == reportsExpected) {
            "Self deadline after its ended loopback session misjudged (modern=$modern state=$state reports=${s.commManager.reports})"
        }
        check(!s.manager.isActive && !s.manager.isLaunchPending)
        if (ended != null) check(s.commManager.connectionState.value is CommManager.ConnectionState.Disconnected) {
            "the deadline hid a terminal published after its launch (state=$state)"
        }
        if (state == "loopback-ended") check(s.manager.currentLaunch() == null && s.dummyVpnOwner == null) {
            "an answered launch whose session is over kept its job or tun"
        }
    }
    // A connection can be published after the deadline's own checks but before its decision takes
    // the lock. A live arrival is not a failure; one still forming decides by its outcome, which
    // the attempt itself reports even if StateFlow conflates it or an Error hides its terminal.
    val hiddenArrivals = listOf("connecting-ended-hidden", "connecting-failed-hidden")
    for (observer in listOf(false, true)) for (modern in listOf(true, false))
    for (arrival in listOf("connected", "connecting-connected", "connecting-failed") + hiddenArrivals) Service(hasEverConnected = !observer).use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        if (observer) { s.startObserver(); s.main.drain() }
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        val failed = arrival.startsWith("connecting-failed")
        s.commManager.beforeReport = {
            s.commManager.publishAttempt()
            s.commManager.register(loopback = !failed)
            if (arrival == "connected") s.commManager.reachConnection()
        }
        s.main.advanceBy(legacyDeadline)
        s.settle()
        if (arrival != "connected") {
            check(s.manager.isActive && s.resolvePrompts == 0 && s.commManager.reports == 0) {
                "Self failed while its arrival was still Connecting (modern=$modern arrival=$arrival)"
            }
            when (arrival) {
                "connecting-connected" -> s.commManager.reachConnection()
                "connecting-failed" -> s.commManager.end()
                "connecting-ended-hidden" -> {
                    // Arrival, its end and a refused USB request's Error, all before Main resumes.
                    s.commManager.reachConnection(); s.commManager.end(); s.commManager.refusedUsb()
                }
                "connecting-failed-hidden" -> { s.commManager.end(); s.commManager.refusedUsb() }
            }
            s.settle()
        }
        val live = arrival == "connected" || arrival == "connecting-connected"
        check(s.resolvePrompts == (if (failed) 1 else 0) && s.commManager.reports == (if (failed) 1 else 0)) {
            "late connection after the deadline's checks misjudged (observer=$observer modern=$modern arrival=$arrival prompts=${s.resolvePrompts} reports=${s.commManager.reports})"
        }
        check(s.manager.isActive == live && !s.manager.isLaunchPending) {
            "Self mode after its arrival's outcome (observer=$observer modern=$modern arrival=$arrival active=${s.manager.isActive})"
        }
        if (arrival == "connecting-ended-hidden") check(s.manager.currentLaunch() == null && s.dummyVpnOwner == null) {
            "hidden loopback end left its launch or tun (observer=$observer modern=$modern)"
        }
        if (live) {
            s.manager.onConnectionEstablished()
            check(s.manager.isActive && s.commManager.connectionState.value === CommManager.ConnectionState.Connected)
        }
    }
    // Root: Connecting(A) -> Connected(A) -> ended(A) -> Connecting(B) can reach a StateFlow waiter
    // as one unchanged Connecting. The deadline waits on attempt A itself, so A's arrival settles
    // the launch while B is still forming; no presentation wait can strand it behind B.
    for (modern in listOf(true, false)) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        s.commManager.publishAttempt(); s.commManager.register(loopback = true) // A forms.
        s.main.advanceBy(legacyDeadline); s.main.drain() // The deadline now waits on A.
        check(s.manager.isLaunchPending && s.resolvePrompts == 0)
        s.commManager.reachConnection(); s.commManager.end()
        s.commManager.publishAttempt(); s.commManager.register(loopback = false) // B forms after A's teardown, and stays.
        check(s.commManager.connectionState.value === CommManager.ConnectionState.Connecting)
        s.settle()
        check(!s.manager.isLaunchPending && !s.manager.isActive && s.resolvePrompts == 0 && s.commManager.reports == 0) {
            "the deadline stayed stranded behind B after A answered it (modern=$modern active=${s.manager.isActive})"
        }
        check(s.manager.currentLaunch() == null && s.dummyVpnOwner == null)
    }
    // Stop is accepted while the deadline waits on a forming attempt, and Open follows before the
    // service relays Stop's cancellation. When that attempt then fails, the revoked launch neither
    // publishes its failure nor shows failure UI; fresh input after Open still launches.
    for (modern in listOf(true, false)) for (outcome in listOf("fails", "arrives")) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        s.commManager.publishAttempt(); s.commManager.register(loopback = true)
        s.main.advanceBy(legacyDeadline); s.main.drain() // The deadline now waits on that attempt.
        check(s.manager.isLaunchPending)
        s.commManager.stopApp(); s.commManager.openApp()
        check(!s.manager.isLaunchPending && !s.manager.isActive) { "Stop left the revoked launch pending" }
        if (outcome == "fails") s.commManager.end() else s.commManager.reachConnection()
        s.settle()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0) {
            "a launch Stop revoked reported after its wait (modern=$modern outcome=$outcome)"
        }
        check(s.commManager.connectionState.value !is CommManager.ConnectionState.Error)
        check(s.manager.currentLaunch() == null) { "the revoked launch kept its bookkeeping" }
        s.useRealDirect = true
        s.modern = true
        s.manager.start(ticket = s.commManager.appConnectionTicket())
        s.main.drain(); s.io.drain(); s.main.drain()
        if (outcome == "fails") check(s.commManager.socketConnects == 1 && s.manager.isActive) {
            "fresh input after Open did not launch (modern=$modern)"
        }
    }
    // The 17.4+ dial joins a loopback attempt already forming: connect returns at once with nothing
    // arrived, and the launcher reports that. Only the manager's decision after that attempt settles
    // may show the Headunit Server guidance: once if the failure stands, never if the attempt
    // reached its server, another route answered, or Stop revoked the launch while it waited.
    for (outcome in listOf("arrives", "other-arrives", "fails", "revoked")) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        s.commManager.publishAttempt(); s.commManager.register(loopback = outcome != "other-arrives")
        s.manager.start()
        s.settle()
        check(s.commManager.joins == 1 && s.commManager.socketConnects == 0) { "the dial did not join the forming attempt ($outcome)" }
        check(s.toasts == 0 && s.settingsPrompts == 0 && s.commManager.reports == 0) {
            "Headunit Server guidance before the joined attempt's outcome ($outcome toasts=${s.toasts} settings=${s.settingsPrompts})"
        }
        check(s.manager.isLaunchPending && !s.manager.inFlight()) { "the launch stopped waiting on the joined attempt ($outcome)" }
        when (outcome) {
            "arrives", "other-arrives" -> s.commManager.reachConnection()
            "fails" -> s.commManager.end()
            "revoked" -> { s.commManager.stopApp(); s.commManager.openApp(); s.commManager.end() }
        }
        s.settle()
        s.main.advanceBy(legacyDeadline); s.settle()
        val shown = if (outcome == "fails") 1 else 0
        check(s.toasts == shown && s.settingsPrompts == shown && s.commManager.reports == shown) {
            "Headunit Server guidance after the joined attempt's outcome ($outcome toasts=${s.toasts} settings=${s.settingsPrompts} reports=${s.commManager.reports})"
        }
        check(!s.manager.isLaunchPending && s.manager.isActive == (outcome == "arrives")) {
            "Self mode after the joined attempt's outcome ($outcome active=${s.manager.isActive})"
        }
        if (outcome == "other-arrives" || outcome == "revoked") check(s.manager.currentLaunch() == null) {
            "the settled launch kept its bookkeeping ($outcome)"
        }
    }
    // The control: the Headunit Server refuses this launch's own dial. The failure stands and the
    // guidance shows exactly once; a later launch that reaches the server shows none.
    Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        s.commManager.serverRunning = false
        s.manager.start()
        s.settle()
        s.main.advanceBy(legacyDeadline); s.settle()
        check(s.commManager.socketConnects == 1 && s.commManager.joins == 0)
        check(s.toasts == 1 && s.settingsPrompts == 1 && s.commManager.reports == 1) {
            "a refused dial's guidance (toasts=${s.toasts} settings=${s.settingsPrompts} reports=${s.commManager.reports})"
        }
        check(!s.manager.isLaunchPending && !s.manager.isActive)
        s.commManager.serverRunning = true
        s.manager.start()
        s.settle()
        check(s.commManager.socketConnects == 2 && s.manager.isActive && s.commManager.isConnected)
        check(s.toasts == 1 && s.settingsPrompts == 1 && s.commManager.reports == 1) { "a reached server showed guidance" }
    }
    // Decision 1 with USB requests in the shared request slot: an admitted, unpublished USB
    // request is no JOIN target and no wait. The Headunit Server refuses this launch's own dial
    // meanwhile; that failure stands as it does with no request, not after the USB request's
    // retries.
    Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        val c = s.commManager
        c.serverRunning = false
        c.claimUsbRequest()
        s.manager.start()
        s.settle()
        s.main.advanceBy(legacyDeadline); s.settle()
        check(c.joins == 0 && c.socketConnects == 1) {
            "the dial joined an admitted USB request (joins=${c.joins} dials=${c.socketConnects})"
        }
        check(!s.manager.isLaunchPending && s.toasts == 1 && s.settingsPrompts == 1 && c.reports == 1) {
            "a refused dial waited on an admitted USB request (pending=${s.manager.isLaunchPending} toasts=${s.toasts} reports=${c.reports})"
        }
    }
    // A user request to the Headunit Server holds its claim and record but waits behind the
    // previous session's cleanup, with no attempt yet. The 17.4+ dial JOINs it and returns at once.
    // The guidance may follow only that request's outcome: none while it waits, none when it
    // arrives, once when it fails after or without publishing. The control publishes it first.
    for (outcome in listOf("arrives", "fails-open", "ends-unpublished", "published-first")) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        val c = s.commManager
        // The previous session ended; its physical cleanup is still queued behind its terminal.
        c.arrive(loopback = false); c.end()
        c.claimRequest("127.0.0.1:5277")
        if (outcome == "published-first") c.publishAttempt()
        s.manager.start()
        s.settle()
        check(c.joins == 1 && c.socketConnects == 0) { "the dial did not join the claimed request ($outcome)" }
        check(s.toasts == 0 && s.settingsPrompts == 0 && c.reports == 0) {
            "Headunit Server guidance while the JOINed request was still waiting to publish ($outcome toasts=${s.toasts} settings=${s.settingsPrompts})"
        }
        check(s.manager.isLaunchPending && !s.manager.inFlight()) { "the launch stopped waiting on the JOINed request ($outcome)" }
        when (outcome) {
            "arrives" -> { c.publishAttempt(); c.register(loopback = true); c.reachConnection() }
            "published-first" -> { c.register(loopback = true); c.reachConnection() }
            "fails-open" -> { c.publishAttempt(); c.register(loopback = true); c.end(); c.endRequest() }
            "ends-unpublished" -> { c.finishTeardown(); c.endRequest() }
        }
        s.settle()
        s.main.advanceBy(legacyDeadline); s.settle()
        val shown = if (outcome == "fails-open" || outcome == "ends-unpublished") 1 else 0
        check(s.toasts == shown && s.settingsPrompts == shown && c.reports == shown) {
            "Headunit Server guidance after the JOINed request's outcome ($outcome toasts=${s.toasts} settings=${s.settingsPrompts} reports=${c.reports})"
        }
        check(!s.manager.isLaunchPending && s.manager.isActive == (shown == 0)) {
            "Self mode after the JOINed request's outcome ($outcome active=${s.manager.isActive})"
        }
    }
    // A request whose authority is gone is no JOIN target and no wait: one Stop revoked (then the
    // app reopened), or one whose Save the manual launch itself revoked. The launch's own 17.4+
    // dial reaches the server and that arrival answers it, with no guidance.
    for (revoked in listOf("stop", "save")) Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        val c = s.commManager
        c.arrive(loopback = false); c.end()
        if (revoked == "stop") {
            c.claimRequest("127.0.0.1:5277")
            c.stopApp(); c.openApp()
        } else {
            c.claimRequest("127.0.0.1:5277", save = s.save())
        }
        s.manager.start()
        s.settle()
        s.main.advanceBy(legacyDeadline); s.settle()
        check(c.joins == 0 && c.socketConnects == 1) {
            "the launch joined a request whose authority was revoked ($revoked joins=${c.joins} dials=${c.socketConnects})"
        }
        check(s.toasts == 0 && s.settingsPrompts == 0 && c.reports == 0) {
            "Headunit Server guidance for a launch a revoked request swallowed ($revoked toasts=${s.toasts} settings=${s.settingsPrompts})"
        }
        check(!s.manager.isLaunchPending && s.manager.isActive) { "Self mode after the launch's own arrival ($revoked active=${s.manager.isActive})" }
    }
    // The joined request loses its claim to another route without ending. The launch's wait ends
    // with that loss, not with the request's producer: nothing of its own arrived, so the failure
    // stands, once.
    Service().use { s ->
        s.modern = true
        s.useRealDirect = true
        val c = s.commManager
        c.arrive(loopback = false); c.end()
        c.claimRequest("127.0.0.1:5277")
        s.manager.start()
        s.settle()
        check(c.joins == 1 && s.manager.isLaunchPending && s.toasts == 0) { "the dial did not wait on the joined request" }
        c.loseClaim()
        s.settle()
        check(!s.manager.isLaunchPending && s.toasts == 1 && s.settingsPrompts == 1 && c.reports == 1) {
            "the launch kept waiting on a request that lost its claim (pending=${s.manager.isLaunchPending} toasts=${s.toasts})"
        }
    }
    // The launch's loopback session can end and an Error hide its terminal before the deadline
    // runs. The deadline must not report the launch as never connected, and it retires the launch
    // and its tun itself: nothing will observe that session.
    for (modern in listOf(true, false)) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        check(s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE)
        s.commManager.arrive(loopback = true); s.commManager.end(); s.commManager.refusedUsb()
        check(s.commManager.connectionState.value is CommManager.ConnectionState.Error)
        s.main.advanceBy(legacyDeadline)
        s.settle()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0) {
            "Self deadline reported a launch whose session ended behind an Error (modern=$modern prompts=${s.resolvePrompts})"
        }
        check(!s.manager.isActive && s.manager.currentLaunch() == null && s.dummyVpnOwner == null) {
            "hidden loopback end left Self state for the next session's end policy (modern=$modern active=${s.manager.isActive})"
        }
    }
    // The service observer sees a hidden loopback end too: no deadline may remain, the deadline may
    // have waited out a forming connection, or the observer may only ever see the Error. No Self
    // state may reach the next end policy, and the next non-Self failed open takes its own branch.
    for (modern in listOf(true, false)) for (order in listOf("live-observed", "deadline-deferred", "terminal-unobserved")) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        if (order != "terminal-unobserved") { s.startObserver(); s.main.drain() }
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        when (order) {
            "live-observed" -> {
                s.commManager.arrive(loopback = true); s.main.drain() // Observer settles the launch.
                s.commManager.end(); s.commManager.refusedUsb(); s.main.drain()
            }
            "deadline-deferred" -> {
                s.commManager.arrive(loopback = true); s.commManager.end(); s.commManager.refusedUsb()
                s.commManager.publishAttempt(); s.commManager.register(loopback = false) // A non-Self attempt forms.
                s.main.drain()
                s.main.advanceBy(legacyDeadline); s.settle()
                check(s.resolvePrompts == 0 && s.commManager.reports == 0)
            }
            "terminal-unobserved" -> {
                s.commManager.arrive(loopback = true); s.commManager.end()
                s.main.advanceBy(legacyDeadline); s.settle()
                s.commManager.refusedUsb()
                s.startObserver(); s.main.drain() // The observer only ever sees the Error.
            }
        }
        check(!s.manager.isActive && !s.manager.isLaunchPending && s.resolvePrompts == 0) {
            "hidden loopback end left Self state for the next end policy (modern=$modern order=$order active=${s.manager.isActive})"
        }
        // A non-Self attempt then fails to open (the one already forming, if any).
        val stops = s.wifiLauncherManager.stops
        val ordinary = s.ordinaryDisconnects
        if (order != "deadline-deferred") { s.commManager.publishAttempt(); s.commManager.register(loopback = false) }
        s.commManager.end()
        s.main.drain()
        check(s.ordinaryDisconnects == ordinary + 1 && s.wifiLauncherManager.stops == stops) {
            "a non-Self failed open took the Self branch after a hidden end (modern=$modern order=$order)"
        }
        s.main.advanceBy(130_000L); s.settle()
        check(!s.manager.isActive && s.resolvePrompts == 0)
    }
    // The observer can take the Self session's own terminal and then IO replaces it with an
    // Error before that terminal's handling: the end policy still retires the answered launch,
    // and nothing is left for the next non-Self failed open.
    for (modern in listOf(true, false)) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.startObserver(); s.main.drain()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        s.commManager.arrive(loopback = true)
        s.main.drain()
        s.beforeObserve = { s.commManager.refusedUsb() }
        s.commManager.end()
        s.main.drain()
        check(s.commManager.connectionState.value is CommManager.ConnectionState.Error)
        check(!s.manager.isActive && s.manager.currentLaunch() == null && s.resolvePrompts == 0) {
            "terminal taken then hidden left Self state (modern=$modern active=${s.manager.isActive})"
        }
        val stops = s.wifiLauncherManager.stops
        val ordinary = s.ordinaryDisconnects
        s.commManager.publishAttempt(); s.commManager.register(loopback = false); s.commManager.end()
        s.main.drain()
        check(s.ordinaryDisconnects == ordinary + 1 && s.wifiLauncherManager.stops == stops) {
            "the next non-Self failed open ran the Self end policy (modern=$modern)"
        }
    }
    // A newer loopback session B owns Self mode, even when it arrives without manager.start(),
    // as a manual loopback IP does. An earlier hidden end cannot retire it while B forms, goes
    // live, or after a refused request reports during B's attempt, including before B's
    // connection object is registered.
    for (modern in listOf(true, false)) for (order in listOf("forming-at-deadline", "live-observed-error", "live-unobserved-error", "unregistered-error")) Service().use { s ->
        s.modern = modern
        s.network.activeNetwork = Any()
        s.startObserver(); s.main.drain()
        s.manager.start()
        s.main.drain(); s.io.drain(); s.main.drain()
        s.commManager.arrive(loopback = true); s.commManager.end(); s.commManager.refusedUsb() // A ends hidden.
        s.commManager.finishTeardown() // ...and teardown repeats its terminal behind the Error.
        s.main.drain()
        s.commManager.publishAttempt() // Loopback B forms.
        when (order) {
            "forming-at-deadline" -> {
                s.commManager.register(loopback = true)
                s.main.advanceBy(legacyDeadline); s.settle()
                s.commManager.reachConnection()
            }
            "live-observed-error" -> {
                s.commManager.register(loopback = true); s.commManager.reachConnection(); s.main.drain()
                s.commManager.refusedUsb()
            }
            "live-unobserved-error" -> {
                s.commManager.register(loopback = true); s.commManager.reachConnection()
                s.commManager.refusedUsb()
            }
            "unregistered-error" -> {
                s.commManager.refusedUsb() // B is published; its connection is not registered yet.
                s.commManager.register(loopback = true); s.commManager.reachConnection()
            }
        }
        s.main.advanceBy(legacyDeadline); s.settle()
        check(s.manager.isActive && s.resolvePrompts == 0) {
            "an earlier hidden end or a refused request retired the newer loopback session's Self mode (modern=$modern order=$order)"
        }
        check(s.commManager.connectionState.value === CommManager.ConnectionState.Connected) {
            "a refused request replaced B's state (modern=$modern order=$order state=${s.commManager.connectionState.value})"
        }
    }
    // Teardown republishes the old session's terminal after B was accepted in the Error that
    // replaced it. B never connects: its deadline must still fail it, and must not hide that
    // repeated terminal from its observer.
    for (modern in listOf(true, false)) Service().use { s ->
        s.modern = modern
        s.commManager.arrive(loopback = true); s.commManager.end(); s.commManager.refusedUsb()
        s.network.activeNetwork = Any()
        s.manager.start() // B, accepted in the Error.
        s.main.drain(); s.io.drain(); s.main.drain()
        s.commManager.finishTeardown()
        val repeated = s.commManager.connectionState.value as CommManager.ConnectionState.Disconnected
        check(repeated.wasLoopbackSession && repeated.hadPhysicalConnection) { "teardown did not repeat the old terminal" }
        s.main.advanceBy(legacyDeadline)
        s.settle()
        check(!s.manager.isActive && s.resolvePrompts == 1 && s.commManager.reports == 1) {
            "old repeated terminal suppressed the new launch's deadline (modern=$modern active=${s.manager.isActive} prompts=${s.resolvePrompts})"
        }
        check(s.commManager.connectionState.value === repeated)
    }
    // The same repeat, observed: its end policy cannot retire B, release B's tun or stop the
    // server B waits on, whether B was accepted before the repeat or after it was handled.
    for (beforeRepeat in listOf(true, false)) Service().use { s ->
        s.startObserver(); s.main.drain()
        s.commManager.arrive(loopback = true); s.main.drain()
        s.commManager.end(); s.commManager.refusedUsb(); s.main.drain()
        if (beforeRepeat) s.manager.start(needsDummyVpn = true)
        s.commManager.finishTeardown(); s.main.drain() // A's repeated terminal reaches the observer.
        if (!beforeRepeat) s.manager.start(needsDummyVpn = true)
        val launch = checkNotNull(s.manager.currentLaunch())
        s.deliverVpn()
        s.main.drain()
        check(launch.isActive && s.manager.isLaunchPending && s.dummyVpnOwner == DummyVpnPolicy.Owner.SELF_MODE && s.tunnel) {
            "A's repeated terminal retired B or its tun (beforeRepeat=$beforeRepeat)"
        }
        check(s.wifiLauncherManager.listenerStarts == 1 && s.wifiLauncherManager.stops == if (beforeRepeat) 0 else 1)
        s.network.activeNetwork = Any()
        s.main.advanceBy(100); s.main.drain()
        check(s.activities == 1)
        s.commManager.arrive(loopback = true); s.main.drain()
        check(s.manager.isActive && !s.manager.isLaunchPending && s.resolvePrompts == 0)
    }
    Service().use { s ->
        s.modern = true
        s.dummyVpnOwner = DummyVpnPolicy.Owner.SESSION
        s.manager.start()
        s.main.drain()
        s.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_SESSION_LIVE)
        check(s.dummyVpnOwner == null && s.vpnReleases == 1)
    }
    val originalSdk = Build.VERSION.SDK_INT
    try {
        for (sdk in listOf(23, 33)) {
            Build.VERSION.SDK_INT = sdk
            WifiScanControl.calls.clear()
            XyAutoMediaSession.calls.clear()
            Service(false).use { s ->
                // A local socket is still Self even when a Native launcher remains selected.
                s.commManager.isWirelessSession = true
                s.commManager.acceptedWirelessSession = Any()
                s.wifiLauncherManager.active = WifiLauncherNative(NativeStrategy.HOTSPOT)
                s.startObserver(); s.main.drain()
                s.commManager.arrive(loopback = true)
                s.commManager.advance(CommManager.ConnectionState.TransportStarted)
                s.main.drain()
                check(XyAutoMediaSession.calls.all { it.session == null }) { "Self loopback acquired XYAuto policy" }
                check(WifiScanControl.calls.size == if (sdk >= 26) 2 else 0)
                check(WifiScanControl.calls.all { it == WifiScanControl.Call(s) }) {
                    "Self loopback acquired wireless scan control"
                }
            }
        }
    } finally {
        Build.VERSION.SDK_INT = originalSdk
        WifiScanControl.calls.clear()
    }
    println("PASS Self observer excludes loopback scan control on Android 13 and skips it below Android 8")

    // A manual launch can connect before its launcher returns or after its deadline is armed.
    // Save at t=8 must not be timed out by that launch's t=10 deadline in either order, whether
    // or not any observer saw the session live.
    for (beforeReturn in listOf(false, true)) for (conflated in listOf(false, true)) Service().use { s ->
        s.modern = true
        var saved: CommManager.ConnectionState.Disconnected? = null
        fun arriveAndSave() {
            s.commManager.arrive(loopback = true)
            if (!conflated) s.manager.onConnectionEstablished()
            saved = s.commManager.end(CommManager.DisconnectReason.SETTINGS_RESTART)
        }
        if (beforeReturn) s.directLaunchHook = { arriveAndSave() }
        s.manager.start()
        s.main.drain()
        if (!beforeReturn) {
            s.main.advanceBy(8_000); s.main.drain()
            arriveAndSave(); s.main.drain()
        }
        s.main.advanceBy(SelfLaunchTimeoutPolicy.HEADUNIT_SERVER_DEADLINE_MS)
        s.settle()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0) { "manual deadline timed out Save (beforeReturn=$beforeReturn conflated=$conflated)" }
        check(s.commManager.connectionState.value === saved && checkNotNull(saved).wasLoopbackSession) {
            "Save's terminal lost its Self route"
        }
        check(s.manager.currentLaunch()?.children?.none() ?: true)
    }
    println("PASS manual Self deadline stays retired across Save, including completion before timer publication")

    // A different route winning a manual Self launch must retire Self's audio policy too.
    Service().use { s ->
        s.modern = true
        s.manager.start(); s.main.drain()
        s.commManager.arrive(loopback = false)
        s.manager.onConnectionEstablished(); s.main.drain()
        check(!s.manager.isActive && s.manager.currentLaunch() == null && s.vpnStops == 0)
        s.commManager.end()
        s.main.advanceBy(SelfLaunchTimeoutPolicy.HEADUNIT_SERVER_DEADLINE_MS); s.settle()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
    }
    // Process-scoped manager survives an Exit and a fresh service subscription. Observer-only:
    // states are written directly, as a previous service's sessions left them.
    val sharedManager = CommManager()
    Service(false, sharedManager).use { old ->
        old.startObserver(); old.main.drain()
        sharedManager.connectionState.value = CommManager.ConnectionState.Disconnected(
            isUserExit = true, hadPhysicalConnection = true)
        old.main.drain()
        check(old.ordinaryDisconnects == 1)
    }
    for (newEndBeforeCollector in listOf(false, true)) {
        Service(false, sharedManager).use { fresh ->
            fresh.startObserver()
            if (!newEndBeforeCollector) {
                fresh.main.drain()
                check(fresh.ordinaryDisconnects == 0) { "old Exit replayed into new service" }
            }
            sharedManager.connectionState.value = CommManager.ConnectionState.Connecting
            sharedManager.connectionState.value = CommManager.ConnectionState.Disconnected(hadPhysicalConnection = true)
            fresh.main.drain()
            check(fresh.ordinaryDisconnects == 1) { "new end before collector startup was skipped" }
        }
    }

    // The first terminal snapshot must be sufficient even when StateFlow skips all live states.
    // Observer-only: each published value is a distinct terminal or live phase.
    for (phase in listOf(null, CommManager.ConnectionState.Connected,
            CommManager.ConnectionState.StartingTransport, CommManager.ConnectionState.HandshakeComplete,
            CommManager.ConnectionState.TransportStarted)) {
        Service(false).use { s ->
            s.startObserver(); s.main.drain()
            check(s.ordinaryDisconnects == 0) // Initial replay is not an ended session.
            s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
            s.main.drain()
            check(s.ordinaryDisconnects == 0) // A failed physical open is not a first session.
            if (phase != null) { s.commManager.connectionState.value = phase; s.main.drain() }
            val ended = CommManager.ConnectionState.Disconnected(hadPhysicalConnection = true)
            s.commManager.connectionState.value = ended; s.main.drain()
            check(s.ordinaryDisconnects == 1) { "lost first disconnect after $phase" }
            s.commManager.connectionState.value = ended; s.main.drain()
            check(s.ordinaryDisconnects == 1) // Replaying the same instance adds no callback.
            s.commManager.connectionState.value = CommManager.ConnectionState.Connecting
            s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected(hadPhysicalConnection = true)
            s.main.drain()
            check(s.ordinaryDisconnects == 2) // Another fast session has its own terminal identity.
        }
    }

    // Cancel before Main has started either the legacy or direct launcher.
    for (modern in listOf(false, true)) Service().use { s ->
        s.modern = modern
        val saved = s.save()
        s.manager.start(saved)
        val job = checkNotNull(s.manager.currentLaunch())
        s.cancelAction()
        s.main.drain()
        check(job.isCancelled)
        check(s.wifiLauncherManager.listenerStarts == 0 && s.activities == 0 && s.directConnects == 0)
        check(s.dummyVpnOwner == null && !s.manager.isActive && !s.manager.inFlight())
        check(!saved.holdsSettingsWake(0))
    }
    // Cancel while the actual legacy launcher is suspended in its network wait.
    Service().use { s ->
        s.manager.start(s.save())
        s.main.drain()
        check(s.wifiLauncherManager.listenerStarts == 1 && s.activities == 0)
        val job = checkNotNull(s.manager.currentLaunch())
        s.cancelAction()
        s.network.activeNetwork = Any()
        s.main.drain()
        check(job.isCancelled && s.activities == 0 && !s.manager.isActive)
    }
    // The actual legacy delay resumes after a different connection publishes its state.
    for (replacement in listOf("connecting", "connected")) {
        Service().use { s ->
            s.manager.start(s.save())
            s.main.drain()
            check(s.wifiLauncherManager.listenerStarts == 1 && s.activities == 0)
            val old = checkNotNull(s.manager.currentLaunch())
            if (replacement == "connected") s.commManager.arrive(loopback = false) else s.commManager.publishAttempt()
            val published = s.commManager.connectionState.value
            s.commManager.metadata = "new USB"
            s.network.activeNetwork = Any()
            s.main.awaitTask() // Resume the real delay, not an explicit cancellation.
            s.main.drain()
            check(old.isCancelled && s.activities == 0 && s.fallbacks == 0)
            check(s.commManager.connectionState.value === published && s.commManager.metadata == "new USB")
            check(!s.manager.isActive && !s.manager.inFlight())
            check(s.vpnStops == 0 && s.commManager.reports == 0 && s.resolvePrompts == 0)
        }
    }
    // An old ownership-loss cleanup queued on Main must not clear a replacement Self launch.
    Service().use { s ->
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.publishAttempt()
        s.network.activeNetwork = Any()
        s.main.awaitTask()
        check(s.main.runOne()) // Legacy guard cancels old Job; its completion cleanup is still queued.
        s.manager.stop(wasConnected = true)
        s.commManager.end()
        s.manager.start()
        val replacement = checkNotNull(s.manager.currentLaunch())
        s.main.drain()
        check(s.manager.currentLaunch() === replacement && replacement.isActive && s.manager.isActive)
        check(s.activities == 1 && s.fallbacks == 0 && s.vpnStops == 0)
    }
    // Permission can already be revoked when a queued start finally runs.
    Service().use { s ->
        val saved = s.save()
        s.manager.start(saved)
        s.commManager.arrive(loopback = false)
        s.main.drain()
        check(s.activities == 0 && s.wifiLauncherManager.listenerStarts == 0)
        check(!s.manager.isActive && s.vpnStops == 0)
    }
    // A successfully sent legacy intent keeps its timeout bound to Save cancellation.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        check(s.activities == 1 && !s.manager.inFlight())
        val job = checkNotNull(s.manager.currentLaunch())
        check(job.isActive && job.children.count() == 1)
        s.cancelAction()
        s.main.drain()
        check(job.isCancelled && job.children.none())
        check(s.commManager.reports == 0 && s.resolvePrompts == 0 && !s.manager.isActive)
    }
    // Once the intent has been sent, a different connection can cross the old deadline.
    for (replacement in listOf("connecting", "connected")) {
        Service().use { s ->
            s.network.activeNetwork = Any()
            s.manager.start(s.save())
            s.main.drain()
            check(s.activities == 1)
            val old = checkNotNull(s.manager.currentLaunch())
            if (replacement == "connected") s.commManager.arrive(loopback = false) else s.commManager.publishAttempt()
            val published = s.commManager.connectionState.value
            s.commManager.metadata = "replacement IP"
            s.main.advanceBy(legacyDeadline)
            s.main.drain()
            check(old.isCancelled && !s.manager.isActive && !s.manager.inFlight())
            check(s.commManager.connectionState.value === published && s.commManager.metadata == "replacement IP")
            check(s.vpnStops == 0 && s.commManager.reports == 0 && s.resolvePrompts == 0)
            s.commManager.end()
            check(!s.manager.isActive) // The service will take its ordinary reconnect branch.
        }
    }
    // A successful loopback connection keeps Self session state when Save's deadline ends.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.arrive(loopback = true)
        s.main.advanceBy(legacyDeadline)
        s.main.drain()
        check(s.manager.isActive && s.commManager.isConnected && s.vpnStops == 0)
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
    }
    // The service's Connected callback retires the old Save before an early new disconnect.
    for (loopback in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.arrive(loopback)
        s.manager.onConnectionEstablished()
        s.main.drain()
        check(s.manager.isActive == loopback && s.vpnStops == 0)
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
        if (!loopback) {
            s.commManager.end()
            check(!s.manager.isActive) // No stale Self branch before the old deadline either.
        }
        s.main.advanceBy(legacyDeadline)
        s.main.drain()
        check(s.manager.isActive == loopback && s.resolvePrompts == 0)
    }
    // Real service collection is held while the producer advances past Connected. This models
    // the Activity/IO handshake publication at the StateFlow boundary, without Android UI doubles
    // pretending to execute the whole Activity. The host lifecycle runner covers the real handshake.
    for (observed in listOf(CommManager.ConnectionState.Connected, CommManager.ConnectionState.StartingTransport,
        CommManager.ConnectionState.HandshakeComplete, CommManager.ConnectionState.TransportStarted)) {
        for (loopback in listOf(false, true)) Service().use { s ->
            s.network.activeNetwork = Any()
            s.manager.start(s.save())
            s.main.drain()
            s.startObserver()
            s.main.drain()
            val baseline = s.ordinaryDisconnects
            s.commManager.arrive(loopback)
            if (observed !== CommManager.ConnectionState.Connected) s.commManager.advance(observed)
            s.main.drain() // Only the latest phase reaches the actual extracted observer.
            check(s.connectedCallbacks == if (observed === CommManager.ConnectionState.Connected) 1 else 0)
            check(s.manager.isActive == loopback && s.vpnStops == 0)
            if (!loopback) {
                s.commManager.end()
                s.main.drain()
                check(s.ordinaryDisconnects == baseline + 1)
            }
            s.main.advanceBy(legacyDeadline)
            s.main.drain()
            check(s.resolvePrompts == 0 && s.commManager.reports == 0)
        }
    }
    // A late live-state notification must not cancel a currently Connecting Save job: forming is
    // not an arrival, and Save's own check, not that notification, decides its launch.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        val job = checkNotNull(s.manager.currentLaunch())
        s.commManager.publishAttempt()
        s.manager.onConnectionEstablished()
        check(job.isActive && s.manager.currentLaunch() === job)
    }
    // Terminal state and the old deadline can both be queued before Main runs. Their order
    // must not change which session's end policy is selected after socket cleanup.
    for (deadlineFirst in listOf(false, true)) for (loopback in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.startObserver()
        s.main.drain()
        val before = s.ordinaryDisconnects
        if (deadlineFirst) s.main.advanceBy(legacyDeadline)
        s.commManager.arrive(loopback)
        s.commManager.advance(CommManager.ConnectionState.StartingTransport)
        s.commManager.failHandshake()
        s.commManager.finishTeardown() // Physical cleanup finished before the collector.
        if (!deadlineFirst) s.main.advanceBy(legacyDeadline)
        s.main.drain()
        check(s.connectedCallbacks == 0 && !s.manager.isActive) {
            "terminal policy changed: deadlineFirst=$deadlineFirst, loopback=$loopback"
        }
        check(s.ordinaryDisconnects == before + if (loopback) 0 else 1)
        check(s.manager.currentLaunch() == null && s.wifiLauncherManager.stops == if (loopback) 1 else 0)
        check(s.resolvePrompts == 0 && s.commManager.reports == 0)
    }
    // IO can publish the replacement after obsolete deadline cleanup but before Main collects
    // the retired terminal. A manual IP connection does not create a new Self launch Job.
    for (liveObserved in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.startObserver()
        s.main.drain()
        val before = s.ordinaryDisconnects
        s.main.advanceBy(legacyDeadline)
        s.commManager.publishAttempt(); s.commManager.register(loopback = true)
        val retired = s.commManager.end()
        check(s.main.runOne()) // Deadline, leaving the terminal collector queued.
        s.commManager.arrive(loopback = false)
        if (liveObserved) s.main.drain()
        s.commManager.end()
        s.main.drain()
        check(!s.manager.isActive && s.ordinaryDisconnects == before + 1) {
            "retired Self policy leaked into next IP session: liveObserved=$liveObserved"
        }
        check(retired.wasLoopbackSession && s.manager.currentLaunch() == null)
        check(s.resolvePrompts == 0 && s.wifiLauncherManager.stops == 0)
    }
    // Explicit stop consumes pending launch; a new manual launch owns its own pending state.
    for (manual in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.main.advanceBy(legacyDeadline)
        s.commManager.publishAttempt(); s.commManager.register(loopback = !manual)
        s.commManager.end()
        check(s.main.runOne())
        if (manual) s.manager.start() else s.manager.stop(wasConnected = true)
        val replacement = s.manager.currentLaunch()
        s.main.drain()
        check(s.manager.isActive == manual && s.manager.currentLaunch() === replacement)
        if (manual) check(checkNotNull(replacement).isActive)
    }
    // Entry or cancellation cleanup can also precede terminal observation. The obsolete launch
    // retires without dispatching an Activity or stopping its VPN, and the terminal keeps the
    // retired route for its own end policy.
    for (cancelled in listOf(false, true)) for (loopback in listOf(false, true)) Service().use { s ->
        val saved = s.save()
        s.manager.start(saved)
        s.commManager.publishAttempt(); s.commManager.register(loopback)
        val ended = s.commManager.end()
        if (cancelled) saved.cancelSettingsRestart()
        s.main.drain()
        check(!s.manager.isActive && s.manager.currentLaunch() == null)
        check(s.activities == 0 && s.vpnStops == 0 && s.resolvePrompts == 0)
        val ordinary = s.ordinaryDisconnects
        s.startObserver(); s.main.drain()
        check(ended.wasLoopbackSession == loopback && s.ordinaryDisconnects == ordinary + if (loopback) 0 else 1)
    }
    // A late end policy for an older session cannot retire a newer manual or Save launch.
    for (manual in listOf(false, true)) Service().use { s ->
        s.commManager.arrive(loopback = true)
        val old = s.commManager.end()
        s.commManager.finishTeardown()
        val current = s.save()
        if (manual) s.manager.start() else s.manager.start(current)
        val job = checkNotNull(s.manager.currentLaunch())
        s.applySelfEndPolicy(old)
        check(s.manager.currentLaunch() === job && job.isActive && s.manager.isActive && s.wifiLauncherManager.stops == 0)
    }
    // A second Save after an unobserved loopback session keeps the legacy relaunch route: Save's
    // terminal carries it, whatever any observer saw.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.arrive(loopback = true)
        val next = s.commManager.end(CommManager.DisconnectReason.SETTINGS_RESTART)
        check(next.isSettingsRestart && next.wasLoopbackSession && next.acceptsSettingsRestart(s.commManager.connectionState.value))
        s.manager.start(next)
        s.main.drain()
        check(s.manager.isActive && s.manager.isLaunchPending && s.activities == 2)
    }
    // A genuinely unanswered current Save still runs the existing failure UI at its deadline.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.main.advanceBy(legacyDeadline)
        s.main.drain()
        check(!s.manager.isActive && s.commManager.reports == 1 && s.resolvePrompts == 1)
    }
    // Completion of an old cancelled launch must not retire a new manual launch.
    Service().use { s ->
        s.manager.start(s.save())
        val old = checkNotNull(s.manager.currentLaunch())
        s.manager.stop()
        s.network.activeNetwork = Any()
        s.manager.start()
        val replacement = checkNotNull(s.manager.currentLaunch())
        s.main.drain()
        check(old.isCancelled && replacement.isActive)
        check(s.manager.currentLaunch() === replacement && s.manager.isActive)
        check(s.activities == 1 && s.wifiLauncherManager.listenerStarts == 1)
    }
    // Revocation before binding cancels a lazy job without letting it enter.
    Service().use { s ->
        val saved = s.save()
        saved.cancelSettingsRestart()
        var entered = false
        val job = s.serviceScope.launch(start = CoroutineStart.LAZY) { entered = true }
        saved.trackSettingsLaunch(job)
        job.start()
        s.main.drain()
        check(job.isCancelled && !entered)
        s.manager.start(saved)
        check(s.manager.currentLaunch() == null && !s.manager.isActive)
    }
    // Actual Self start publishes its cleanup ownership before the queued launcher runs.
    Service().use { s ->
        val old = s.wifiLauncherManager.cleanupTicket()
        s.manager.start()
        check(!s.wifiLauncherManager.isCleanupCurrent(old))
    }
    // The actual service X action must not stop a same-instance re-arm after its await.
    for (rearmed in listOf(false, true)) Service().use { s ->
        s.commManager.publishAttempt()
        s.commManager.teardownGate = CompletableDeferred()
        s.cancelAction()
        s.main.drain()
        check(s.wifiLauncherManager.stops == 0)
        if (rearmed) s.wifiLauncherManager.noteRearm()
        s.commManager.teardownGate!!.complete(Unit)
        s.main.drain()
        check(s.wifiLauncherManager.stops == if (rearmed) 0 else 1)
    }
    Service().use { s ->
        s.commManager.publishAttempt()
        s.commManager.teardownGate = CompletableDeferred()
        s.cancelAction()
        s.main.drain()
        // Automatic socket progress is not an explicit replacement for the user's X.
        s.commManager.arrive(loopback = false)
        s.commManager.teardownGate!!.complete(Unit)
        s.main.drain()
        check(s.wifiLauncherManager.stops == 1)
    }
    // The physical live state can be conflated away while a manual legacy launch waits.
    // Execute the production service Self-end branch, not a flag-only test double.
    for (physical in listOf(false, true)) Service().use { s ->
        s.commManager.publishAttempt(); s.commManager.register(loopback = true)
        s.manager.start(); s.main.drain()
        val first = checkNotNull(s.manager.currentLaunch())
        if (physical) s.commManager.reachConnection()
        val ended = s.commManager.end()
        s.applySelfEndPolicy(ended)
        check(first.isCancelled == physical)
        check(s.wifiLauncherManager.stops == 0) { "in-flight wireless decision changed" }
        if (physical) {
            s.manager.start(); s.main.drain()
            s.network.activeNetwork = Any()
            s.main.advanceBy(100); s.main.drain()
            check(s.activities == 1) { "retired Self launch ran alongside its replacement" }
        } else {
            // A failed dial leaves the launch its own deadline: it still fails, once.
            check(s.manager.isLaunchPending)
            s.network.activeNetwork = Any()
            s.main.advanceBy(100); s.main.drain()
            s.main.advanceBy(legacyDeadline); s.settle()
            check(s.resolvePrompts == 1 && s.commManager.reports == 1 && !s.manager.isLaunchPending)
        }
    }
    println("PASS: real Self queued entry, legacy network wait, ownership takeover, stale permission, owned deadline and timeout takeover, conflated live/terminal delivery, attempt-settled outcomes, replacement launch and bind-after-cancel")
}
