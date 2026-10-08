package selflaunch

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.CancellationException
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
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
class CommManager {
    enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART }
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
    val connectionState get() = _connectionState
    val isConnected get() = _connectionState.value.let {
        it === ConnectionState.Connected || it === ConnectionState.StartingTransport ||
            it === ConnectionState.HandshakeComplete || it === ConnectionState.TransportStarted
    }
    val isUsbSession = false
    var isLoopbackSession = false
    var reports = 0
    var metadata = "old"
    // CANCEL
    fun reportError(message: String, state: ConnectionState.Disconnected? = null) { reports++ }
    fun emitError(message: String) { reports++ }
    fun disconnect() { cancelPendingSettingsRestart() }
    suspend fun awaitDisconnectComplete() {}
}
class SelfLauncherManager(private val service: Service, private val wifiLauncherManager: WifiLauncherManager) {
    var isActive = false
    private var launchInFlight = false
    private var launchJob: Job? = null
    private var launchTimeoutJob: Job? = null
    private var launchConnected = false
    private var settingsLaunchOwner: CommManager.ConnectionState.Disconnected? = null
    private var retiredSettingsTerminal: CommManager.ConnectionState.Disconnected? = null
    private var selfModeVpnWatchdog: Job? = null
    private fun installedPath(service: Service) = if (service.modern) SelfLaunchPath.HEADUNIT_SERVER else SelfLaunchPath.LEGACY
    private fun adoptDummyVpn() { service.vpnAdoptions++ }
    fun stopDummyVpnWatchdog() { selfModeVpnWatchdog?.cancel(); selfModeVpnWatchdog = null }
    fun handleNeverConnect() { service.resolvePrompts++ }
    fun currentLaunch() = launchJob
    fun inFlight() = launchInFlight
    // START
    // STOP
    // ENDED
    // ESTABLISHED
    // STOP_CURRENT
}
class SelfLauncherServices(val aap: Service, val wifiLauncherManager: WifiLauncherManager,
                           val settingsRestart: CommManager.ConnectionState.Disconnected?) {
    val connectivityManager get() = aap.network
    val fakeNetwork = Any()
    val fakeWifiInfo = Any()
    // ALLOW_LAUNCH
}
open class SelfLauncher(val manager: SelfLauncherManager, val services: SelfLauncherServices) {
    open val name = "fixture"
    open suspend fun run(): Boolean { services.aap.fallbacks++; return false }
}
class SelfLauncherLegacy(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services) {
    // LEGACY_RUN
    // LEGACY_WAIT
}
class SelfLauncherV17_4(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services) {
    override suspend fun run(): Boolean { services.aap.directConnects++; services.aap.directLaunchHook?.invoke(); return true }
}
class SelfLauncherBroadcast(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)
class SelfLauncherBTDiscovery(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)

// Android effects are observable at the same calls made by production methods.
object App { fun provide(service: Service) = service }
object AppLog { fun i(s: String) {}; fun w(s: String) {}; fun w(s: String, e: Throwable) {}; fun e(s: String) {} }
object DummyVpnPolicy { enum class Reason { SELF_MODE_NEVER_CONNECTED, SELF_MODE_SESSION_LIVE } }
class ConnectivityManager { var activeNetwork: Any? = null }
object Context { const val CONNECTIVITY_SERVICE = "connectivity" }
object Build { object VERSION { const val SDK_INT = 23 }; object VERSION_CODES { const val M = 23 } }
const val AA_PACKAGE = "fixture.gearhead"
class Intent(val action: String? = null) {
    fun setPackage(name: String) {}
    fun setClassName(pkg: String, name: String) {}
    fun addFlags(flags: Int) {}
    fun putExtra(key: String, value: Any) {}
    fun getBooleanExtra(key: String, default: Boolean) = default
    companion object { const val FLAG_ACTIVITY_NEW_TASK = 1 }
}
class WifiLauncherManual(manager: WifiLauncherManager)
class WifiLauncherManager {
    var active: WifiLauncherManual? = null
    var listenerStarts = 0
    var stops = 0
    val sharedServices get() = this
    fun startWirelessServer(launcher: WifiLauncherManual) { listenerStarts++ }
    fun stopForUser() { stops++ }
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
    val main = QueuedMain()
    val serviceScope = CoroutineScope(SupervisorJob() + main)
    val wifiLauncherManager = WifiLauncherManager()
    val usbLauncherManager = UsbLauncherManager()
    val manager = SelfLauncherManager(this, wifiLauncherManager)
    val selfLauncherManager get() = manager
    private var projectingSinceMs = 0L
    private var projectionRaisesThisSession = 0
    private val packageName = "fixture"
    private val ACTION_REQUEST_NIGHT_MODE_UPDATE = "night"
    var connectedCallbacks = 0
    var ordinaryDisconnects = 0
    fun startObserver() { observeConnectionState() }
    private fun onConnected() { connectedCallbacks++ }
    private fun onDisconnected(state: CommManager.ConnectionState.Disconnected) {
        if (!manager.isActive) ordinaryDisconnects++
    }
    private fun emitSessionState(state: Int, reason: Int = 0) {}
    private fun quiesceWirelessForWiredSession() {}
    private fun wifiLockHeldForMs(): Long? = null
    private fun cancelProjectionRaiseDeadline() {}
    private fun armProjectionRaiseDeadline(value: Any) {}
    private fun launchAapProjectionActivity() = Unit
    private fun sendBroadcast(intent: Intent) {}
    private fun maybeAutoResumePlaybackOnReconnect() {}
    // OBSERVER
    val network = ConnectivityManager()
    var modern = false
    var activities = 0
    var directConnects = 0
    var directLaunchHook: (suspend () -> Unit)? = null
    var fallbacks = 0
    var vpnAdoptions = 0
    var vpnStops = 0
    var liveVpnReleases = 0
    var resolvePrompts = 0
    var usbCheckPendingForSettings = false
    var bluetoothLaunchPendingForSettings = false
    var wirelessRearmPendingForSettings = false
    fun getSystemService(name: String): Any = network
    fun startActivity(intent: Intent) { activities++ }
    fun stopDummyVpn(reason: DummyVpnPolicy.Reason) {
        if (reason == DummyVpnPolicy.Reason.SELF_MODE_SESSION_LIVE) liveVpnReleases++ else vpnStops++
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
        const val ACTION_CANCEL_WIRELESS = "cancel"
        const val EXTRA_USB_ATTEMPT = "usb"
        const val START_STICKY = 1
    }
}

fun main() {
    // A manual launch can connect before its launcher returns or after its deadline is
    // armed. Save at t=8 must not be timed out by that launch's t=10 deadline in either order.
    for (beforeReturn in listOf(false, true)) for (conflated in listOf(false, true)) Service().use { s ->
        s.modern = true
        fun arriveAndSave() {
            s.commManager.isLoopbackSession = true
            s.commManager.connectionState.value = CommManager.ConnectionState.Connected
            if (!conflated) s.manager.onConnectionEstablished()
            val saved = CommManager.ConnectionState.Disconnected(
                reason = CommManager.DisconnectReason.SETTINGS_RESTART, wasLoopbackSession = true,
                settingsRestartUntilMs = Long.MAX_VALUE)
            s.commManager.connectionState.value = saved
            s.commManager.isLoopbackSession = false
            s.manager.onConnectionEnded(saved)
        }
        if (beforeReturn) s.directLaunchHook = { arriveAndSave() }
        s.manager.start()
        s.main.drain()
        if (!beforeReturn) {
            s.main.advanceBy(8_000); s.main.drain()
            arriveAndSave(); s.main.drain()
        }
        s.main.advanceBy(SelfLaunchTimeoutPolicy.HEADUNIT_SERVER_DEADLINE_MS)
        s.main.drain()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0 && s.manager.isActive)
        check(checkNotNull(s.manager.currentLaunch()).children.none())
    }
    println("PASS manual Self deadline stays retired across Save, including completion before timer publication")

    // A different route winning a manual Self launch must retire Self's audio policy too.
    Service().use { s ->
        s.modern = true
        s.manager.start(); s.main.drain()
        s.commManager.isLoopbackSession = false
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
        s.manager.onConnectionEstablished(); s.main.drain()
        check(!s.manager.isActive && s.manager.currentLaunch() == null && s.vpnStops == 0)
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
        s.main.advanceBy(SelfLaunchTimeoutPolicy.HEADUNIT_SERVER_DEADLINE_MS); s.main.drain()
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
    }
    // Process-scoped manager survives an Exit and a fresh service subscription.
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
        check(s.vpnAdoptions == 0 && !s.manager.isActive && !s.manager.inFlight())
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
    for (replacement in listOf(CommManager.ConnectionState.Connecting, CommManager.ConnectionState.Connected)) {
        Service().use { s ->
            s.manager.start(s.save())
            s.main.drain()
            check(s.wifiLauncherManager.listenerStarts == 1 && s.activities == 0)
            val old = checkNotNull(s.manager.currentLaunch())
            s.commManager.connectionState.value = replacement
            s.commManager.metadata = "new USB"
            s.network.activeNetwork = Any()
            s.main.awaitTask() // Resume the real delay, not an explicit cancellation.
            s.main.drain()
            check(old.isCancelled && s.activities == 0 && s.fallbacks == 0)
            check(s.commManager.connectionState.value === replacement && s.commManager.metadata == "new USB")
            check(!s.manager.isActive && !s.manager.inFlight())
            check(s.vpnStops == 0 && s.commManager.reports == 0 && s.resolvePrompts == 0)
        }
    }
    // An old ownership-loss cleanup queued on Main must not clear a replacement Self launch.
    Service().use { s ->
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.connectionState.value = CommManager.ConnectionState.Connecting
        s.network.activeNetwork = Any()
        s.main.awaitTask()
        check(s.main.runOne()) // Legacy guard cancels old Job; its completion cleanup is still queued.
        s.manager.stop(wasConnected = true)
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
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
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
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
    for (replacement in listOf(CommManager.ConnectionState.Connecting, CommManager.ConnectionState.Connected)) {
        Service().use { s ->
            s.network.activeNetwork = Any()
            s.manager.start(s.save())
            s.main.drain()
            check(s.activities == 1)
            val old = checkNotNull(s.manager.currentLaunch())
            s.commManager.connectionState.value = replacement
            s.commManager.metadata = "replacement IP"
            s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
            s.main.drain()
            check(old.isCancelled && !s.manager.isActive && !s.manager.inFlight())
            check(s.commManager.connectionState.value === replacement && s.commManager.metadata == "replacement IP")
            check(s.vpnStops == 0 && s.commManager.reports == 0 && s.resolvePrompts == 0)
            s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
            check(!s.manager.isActive) // The service will take its ordinary reconnect branch.
        }
    }
    // A successful loopback connection keeps Self session state when Save's deadline ends.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.isLoopbackSession = true
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
        s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
        s.main.drain()
        check(s.manager.isActive && s.commManager.isConnected && s.vpnStops == 0)
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
    }
    // The service's Connected callback retires the old Save before an early new disconnect.
    for (loopback in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.commManager.isLoopbackSession = loopback
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
        s.manager.onConnectionEstablished()
        s.main.drain()
        check(s.manager.isActive == loopback && s.vpnStops == 0)
        check(s.commManager.reports == 0 && s.resolvePrompts == 0)
        if (!loopback) {
            s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
            check(!s.manager.isActive) // No stale Self branch before the old deadline either.
        }
        s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
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
            s.commManager.isLoopbackSession = loopback
            s.commManager.connectionState.value = CommManager.ConnectionState.Connected
            s.commManager.connectionState.value = observed
            s.main.drain() // Only the latest phase reaches the actual extracted observer.
            check(s.connectedCallbacks == if (observed === CommManager.ConnectionState.Connected) 1 else 0)
            check(s.manager.isActive == loopback && s.vpnStops == 0)
            if (!loopback) {
                s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
                s.main.drain()
                check(s.ordinaryDisconnects == baseline + 1)
            }
            s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
            s.main.drain()
            check(s.resolvePrompts == 0 && s.commManager.reports == 0)
        }
    }
    // A late live-state notification must not cancel a currently Connecting Save job.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        val job = checkNotNull(s.manager.currentLaunch())
        s.commManager.connectionState.value = CommManager.ConnectionState.Connecting
        s.manager.onConnectionEstablished()
        check(job.isActive && s.manager.isActive)
    }
    // Terminal state and the old deadline can both be queued before Main runs. Their order
    // must not change which session's disconnect policy is selected after socket cleanup.
    for (deadlineFirst in listOf(false, true)) for (loopback in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.startObserver()
        s.main.drain()
        val before = s.ordinaryDisconnects
        if (deadlineFirst) s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
        s.commManager.isLoopbackSession = loopback
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
        s.commManager.connectionState.value = CommManager.ConnectionState.StartingTransport
        s.commManager.connectionState.value = CommManager.ConnectionState.Error("handshake failed")
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected(wasLoopbackSession = loopback)
        s.commManager.isLoopbackSession = false // Physical cleanup finished before the collector.
        if (!deadlineFirst) s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
        s.main.drain()
        check(s.connectedCallbacks == 0 && s.manager.isActive == loopback) {
            "terminal policy changed: deadlineFirst=$deadlineFirst, loopback=$loopback"
        }
        check(s.ordinaryDisconnects == before + if (loopback) 0 else 1)
        check(s.vpnStops == 0 && s.manager.currentLaunch() == null)
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
        s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
        val retired = CommManager.ConnectionState.Disconnected(wasLoopbackSession = true)
        s.commManager.connectionState.value = retired
        s.commManager.isLoopbackSession = false
        check(s.main.runOne()) // Deadline, leaving the terminal collector queued.
        s.commManager.connectionState.value = CommManager.ConnectionState.Connecting
        s.commManager.connectionState.value = CommManager.ConnectionState.Connected
        if (liveObserved) s.main.drain()
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
        s.main.drain()
        check(!s.manager.isActive && s.ordinaryDisconnects == before + 1) {
            "retired Self policy leaked into next IP session: liveObserved=$liveObserved"
        }
        s.manager.onConnectionEnded(retired) // Superseded terminal callback stays inert.
        check(!s.manager.isActive && s.manager.currentLaunch() == null)
        check(s.resolvePrompts == 0 && s.vpnStops == 0)
    }
    // Explicit stop consumes pending policy; a new manual launch owns its own active flag.
    for (manual in listOf(false, true)) Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
        val retired = CommManager.ConnectionState.Disconnected(wasLoopbackSession = !manual)
        s.commManager.connectionState.value = retired
        check(s.main.runOne())
        if (manual) s.manager.start() else s.manager.stop(wasConnected = true)
        val replacement = s.manager.currentLaunch()
        s.manager.onConnectionEnded(retired)
        s.main.drain()
        check(s.manager.isActive == manual && s.manager.currentLaunch() === replacement)
        if (manual) check(checkNotNull(replacement).isActive)
    }
    // Entry or cancellation cleanup can also precede terminal observation. Both must retain
    // the retired route without dispatching an obsolete Activity or stopping its VPN.
    for (cancelled in listOf(false, true)) for (loopback in listOf(false, true)) Service().use { s ->
        val saved = s.save()
        s.manager.start(saved)
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected(wasLoopbackSession = loopback)
        if (cancelled) saved.cancelSettingsRestart()
        s.startObserver()
        s.main.drain()
        check(s.manager.isActive == loopback && s.manager.currentLaunch() == null)
        check(s.activities == 0 && s.vpnStops == 0 && s.resolvePrompts == 0)
    }
    // A superseded terminal notification cannot retire a newer manual or Save launch.
    for (manual in listOf(false, true)) Service().use { s ->
        val old = s.save()
        s.manager.start(old)
        s.manager.stop(wasConnected = true)
        s.commManager.connectionState.value = CommManager.ConnectionState.Disconnected()
        val current = s.save()
        if (manual) s.manager.start() else s.manager.start(current)
        val job = checkNotNull(s.manager.currentLaunch())
        s.manager.onConnectionEnded(old)
        check(s.manager.currentLaunch() === job && job.isActive && s.manager.isActive)
    }
    // A second Save after an unobserved loopback session keeps the legacy relaunch route.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        val next = CommManager.ConnectionState.Disconnected(
            reason = CommManager.DisconnectReason.SETTINGS_RESTART, wasLoopbackSession = true)
        s.commManager.connectionState.value = next
        s.manager.onConnectionEnded(next)
        check(s.manager.isActive && s.manager.currentLaunch() == null)
        s.manager.start(next)
        s.main.drain()
        check(s.manager.isActive && s.activities == 2)
    }
    // A genuinely unanswered current Save still runs the existing failure UI at its deadline.
    Service().use { s ->
        s.network.activeNetwork = Any()
        s.manager.start(s.save())
        s.main.drain()
        s.main.advanceBy(SelfLaunchTimeoutPolicy.LEGACY_DEADLINE_MS)
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
    println("PASS: real Self queued entry, legacy network wait, ownership takeover, stale permission, owned deadline and timeout takeover, conflated live/terminal delivery, replacement launch and bind-after-cancel")
}
