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
    }
    private val transportLifecycleLock = Any()
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    val connectionState get() = _connectionState
    val isConnected get() = _connectionState.value === ConnectionState.Connected
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
    private var settingsLaunchOwner: CommManager.ConnectionState.Disconnected? = null
    private var selfModeVpnWatchdog: Job? = null
    private fun isAaVersion174OrHigher() = service.modern
    private fun adoptDummyVpn() { service.vpnAdoptions++ }
    fun stopDummyVpnWatchdog() { selfModeVpnWatchdog?.cancel(); selfModeVpnWatchdog = null }
    fun handleNeverConnect() { service.resolvePrompts++ }
    fun currentLaunch() = launchJob
    fun inFlight() = launchInFlight
    // START
    // STOP
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
    override suspend fun run(): Boolean { services.aap.directConnects++; return true }
}
class SelfLauncherBroadcast(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)
class SelfLauncherBTDiscovery(manager: SelfLauncherManager, services: SelfLauncherServices) : SelfLauncher(manager, services)

// Android effects are observable at the same calls made by production methods.
object App { fun provide(service: Service) = service }
object AppLog { fun i(s: String) {}; fun w(s: String) {}; fun w(s: String, e: Throwable) {}; fun e(s: String) {} }
object DummyVpnPolicy { enum class Reason { SELF_MODE_NEVER_CONNECTED } }
class ConnectivityManager { var activeNetwork: Any? = null }
object Context { const val CONNECTIVITY_SERVICE = "connectivity" }
object Build { object VERSION { const val SDK_INT = 23 }; object VERSION_CODES { const val M = 23 } }
const val AA_PACKAGE = "fixture.gearhead"
class Intent {
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
class UsbLauncherManager { fun isSwitchingToProjection() = false; fun stopForUser() {} }
enum class ConnectionStage { IDLE, USB_ATTACHED, USB_SWITCHING }
object ConnectionStageTracker {
    val stage = MutableStateFlow(ConnectionStage.IDLE)
    fun clear() {}
}
class Service : AutoCloseable {
    val main = QueuedMain()
    val serviceScope = CoroutineScope(SupervisorJob() + main)
    val commManager = CommManager()
    val wifiLauncherManager = WifiLauncherManager()
    val usbLauncherManager = UsbLauncherManager()
    val manager = SelfLauncherManager(this, wifiLauncherManager)
    val network = ConnectivityManager()
    var modern = false
    var activities = 0
    var directConnects = 0
    var fallbacks = 0
    var vpnAdoptions = 0
    var vpnStops = 0
    var resolvePrompts = 0
    var usbCheckPendingForSettings = false
    var bluetoothLaunchPendingForSettings = false
    var wirelessRearmPendingForSettings = false
    fun getSystemService(name: String): Any = network
    fun startActivity(intent: Intent) { activities++ }
    fun stopDummyVpn(reason: DummyVpnPolicy.Reason) { vpnStops++ }
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
    println("PASS: real Self queued entry, legacy network wait, ownership takeover, stale permission, owned deadline and timeout takeover, replacement launch and bind-after-cancel")
}
