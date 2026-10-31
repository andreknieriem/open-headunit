package sleepreplay

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.ArrayDeque
import java.util.PriorityQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

// Deterministic Android Main/radio boundaries; production methods are inserted below.
@OptIn(InternalCoroutinesApi::class)
class MainQueue : CoroutineDispatcher(), Delay {
    val tasks = ArrayDeque<Runnable>()
    data class Timer(val at: Long, val action: () -> Unit)
    private val timers = PriorityQueue<Timer>(compareBy { it.at })
    var now = 0L
    private val clockBase = SystemClock.now
    override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
        timers.add(Timer(now + timeMillis) { if (continuation.isActive) continuation.resume(Unit) })
    }
    fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    fun advanceBy(ms: Long) {
        drain()
        val until = now + ms
        while (timers.peek()?.at?.let { it <= until } == true) {
            val timer = timers.remove(); now = timer.at; SystemClock.now = clockBase + now; timer.action(); drain()
        }
        now = until
        SystemClock.now = clockBase + now
        drain()
    }
}
enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART, PROJECTION_UNRAISED }
class UsbRecheck
class CommManager {
    sealed class ConnectionState {
        // SAVE_CLASS
        object Connecting : ConnectionState()
    }
    private val transportLifecycleLock = Any()
    internal val appExitPolicy = AppExitPolicy()
    val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected(reason = DisconnectReason.SETTINGS_RESTART))
    val connectionState = _connectionState
    var settingsUsbRestartInFlight: ConnectionState.Disconnected? = null
    val isConnected = false
    val isWirelessSession = true
    fun appConnectionTicket() = appExitPolicy.stopEpoch
    fun <T> withAppConnectionLifecycleLock(action: () -> T): T = synchronized(transportLifecycleLock) { action() }
    fun retireInvalidRequest() {}
    // CM_CURRENT
    // CM_CANCEL_SAVE
    fun stopAndOpen() { appExitPolicy.request(false, true, false); appExitPolicy.supersede() }
}
object Looper {
    private val main = Any()
    fun myLooper() = main
    fun getMainLooper() = main
}
object SystemClock { var now = 100_000L; fun elapsedRealtime() = now }
object ScreenPower { var interactive = false; fun isInteractive(context: Context): Boolean = interactive }
object Build { object VERSION { var SDK_INT = 28 }; object VERSION_CODES { const val Q = 29 } }
class Handler {
    val tasks = ArrayDeque<Runnable>()
    fun post(action: Runnable) { tasks.addLast(action) }
    fun postDelayed(action: Runnable, ms: Long) { tasks.addLast(action) }
}
object AppLog {
    fun i(message: String) {}
    fun d(message: String) {}
    fun w(message: String) {}
    fun e(message: String, failure: Throwable? = null) {}
}
class Settings {
    val wifiConnectionMode = WifiLauncherMode.NATIVE
    fun showsWifi() = true
    companion object { fun isWirelessPausedByBootLoop(context: Context) = false }
}
open class Context(val commManager: CommManager) {
    val settings = Settings()
    val wifi = WifiManager()
    val applicationContext get() = this
    val mainLooper get() = Looper.getMainLooper()
    fun getSystemService(name: String): Any = if (name == WIFI_SERVICE) wifi else WifiP2pManager()
    fun getString(id: Int) = "string-$id"
    companion object { const val WIFI_SERVICE = "wifi"; const val WIFI_P2P_SERVICE = "p2p" }
}
object App { fun provide(context: Context) = context }
class WifiManager { var isWifiEnabled = false }
class WifiP2pGroup(val isGroupOwner: Boolean = true, val networkName: String = "group")
class Info
class WifiP2pManager {
    class Channel
    val groups = ArrayDeque<(WifiP2pGroup?) -> Unit>()
    var creates = 0
    val createdOperations = mutableListOf<ConnectionOperation>()
    fun initialize(context: Context, looper: Any, callback: Any?): Channel = Channel()
    fun requestGroupInfo(channel: Channel?, callback: (WifiP2pGroup?) -> Unit) { groups.addLast(callback) }
    fun requestConnectionInfo(channel: Channel?, callback: (Info) -> Unit) {}
    fun answer(group: WifiP2pGroup?) { groups.removeFirst()(group) }
}
object RetainedNativeGroup {
    var owner: Any? = null
    fun isOwner(value: Any) = owner === value
    fun activate(value: Any) = true
    fun deferUntilReady(owner: Any, action: () -> Unit) = false
}
object R { object string { const val native_aa_requires_wifi = 1 } }
enum class ConnectionIssue { WIFI_RADIO_OFF }
object ConnectionIssues { fun raise(context: Context, issue: ConnectionIssue) {} }
enum class ConnectionStage { ARMED }
object ConnectionStageTracker {
    fun clear() {}
    fun beginAttempt(stage: ConnectionStage) {}
    fun networkAbandoned() {}
}
class Direct(val context: AapService, private val operation: ConnectionOperation) {
    init { context.directs.add(this) }
    val commManager get() = context.commManager
    private val groupOwnership = Any().also { RetainedNativeGroup.owner = it }
    private val handler = Handler()
    val platform = WifiP2pManager()
    private var manager: WifiP2pManager? = platform
    private var channel: WifiP2pManager.Channel? = WifiP2pManager.Channel()
    private var stopped = false
    var generation = 0
    private var isGroupOwner = false
    private var nativeCreateRequestedAtMs = 0L
    var isGroupCreatingOrCreated = false
    private var lastNativeBringUpAtMs = 0L
    private var wifiEnableAttempts = 0
    private var isClientConnected = false
    private val isNativeSessionConnected: (() -> Boolean)? = { false }
    private val isPhoneEverOpenedAaChannel: (() -> Boolean)? = { true }
    private var nativeRecreateCount = 0
    companion object {
        const val MAX_WIFI_ENABLE_ATTEMPTS = 5
        const val MAX_NATIVE_JOIN_RECREATES = 4
        const val NATIVE_FORCE_STANDARD_AFTER = 2
    }
    fun msSinceAcceptedCreate(): Long? = null
    fun markP2pRequest() {}
    fun registerReceiverIfNeeded(operation: ConnectionOperation) {}
    fun releaseNativeCreateWindow(reason: String, operation: ConnectionOperation) {}
    fun scheduleNativeRefreshRecheck(delay: Long, operation: ConnectionOperation) {}
    fun handleConnectionInfo(info: Info, operation: ConnectionOperation) {}
    fun handleGroupInfo(group: WifiP2pGroup?, operation: ConnectionOperation) {}
    fun showToast(message: String) {}
    fun cancelNativeJoinWatchdog() {}
    fun armNativeJoinWatchdog(operation: ConnectionOperation) {}
    fun recreateNativeGroup(forceStandard: Boolean, operation: ConnectionOperation) {
        platform.creates++; platform.createdOperations.add(operation)
    }
    fun recover(operation: ConnectionOperation) = recoverNativeGroup("test no-join", operation = operation)
    // Models the framework resource owner retirement; resumed production code performs the guard.
    fun retire() { stopped = true; generation++ }
    // DIRECT_CURRENT
    // DIRECT_DEFER
    // DIRECT_SUPERSEDED
    // DIRECT_ACTIVATE
    // DIRECT_REFRESH
    // DIRECT_START
    // DIRECT_HOLD
    // DIRECT_RECOVER
}
enum class WifiLauncherMode {
    MANUAL, AUTO, HELPER, NATIVE;
    fun factory(manager: WifiLauncherManager): WifiLauncher = WifiLauncherNative(manager)
}
enum class WifiLauncherStopSequence { ANY }
open class WifiLauncher(val manager: WifiLauncherManager) {
    val mode = WifiLauncherMode.NATIVE
    fun hasSameStartConfiguration(other: WifiLauncher) = true
    fun stop(seq: WifiLauncherStopSequence) {}
    open fun start(noInfoToasts: Boolean, operation: ConnectionOperation) {}
}
class WifiLauncherNative(manager: WifiLauncherManager) : WifiLauncher(manager) {
    val handshakeManager = Handshake()
    fun networkComingUp() = false
    class Handshake { fun isAttemptInFlight() = false }
    override fun start(noInfoToasts: Boolean, operation: ConnectionOperation) {
        manager.startedOperations.add(operation)
        val direct = Direct(manager.service, operation)
        manager.startedDirects.add(direct)
        direct.startNativeAaQuietHost(operation)
    }
}
fun interface ConnectionAdmission { fun enter(action: () -> Unit): Boolean }
object ConnectionArbiter {
    fun refusesBackground(userRequested: Boolean) = false
    fun yieldToUser(reason: String, admission: ConnectionAdmission) = admission.enter {}
}
class SharedServices { fun update(launcher: WifiLauncher, operation: ConnectionOperation) {} }
class WifiLauncherManager(val service: AapService) {
    var active: WifiLauncher? = WifiLauncherNative(this)
    var armedOperation: ConnectionOperation? = ConnectionOperation(service.commManager.appConnectionTicket(), null)
    var activeIsStarted = true
    var lastNativeRearmAtMs = 0L
    var cancelledByUser = false
    val sharedServices = SharedServices()
    val startedOperations = mutableListOf<ConnectionOperation>()
    val startedDirects = mutableListOf<Direct>()
    fun noteRearm() {}
    fun cleanupTicket(): Any = this
    fun stop(owner: Any) { service.directs.forEach { it.retire() }; activeIsStarted = false }
    // SET_ACTIVE_FROM_SETTINGS
    // SET_ACTIVE_MODE
    // SET_ACTIVE
}
object SettingsActivity { var isForeground = false }
class AapService(commManager: CommManager) : Context(commManager) {
    val directs = mutableListOf<Direct>()
    val dispatcher = MainQueue()
    val serviceScope = CoroutineScope(dispatcher + SupervisorJob())
    val wifiLauncherManager = WifiLauncherManager(this)
    val wirelessQuiescedForWiredSession = false
    var wirelessPausedForSettings = false
    private var settingsQrHold = false
    private var sleepReplayJob: Job? = null
    private var settingsRearmJob: Job? = null
    // HELD_FIELDS
    // NOTE_SLEEP
    fun noteWirelessOwed(ticket: Long) {}
    fun releaseParkedHct() {}
    fun closeRetainedHct() {}
    // SETTINGS_HOLD
    // SETTINGS_CHANGED
    // REPLAY
    // REPLAY_FORCE
    // REPLAY_CONNECTING
    fun wake() { ScreenPower.interactive = true; wifi.isWifiEnabled = true; replaySleepHold("test wake"); dispatcher.drain() }
    fun homeExit() = commManager.cancelPendingSettingsRestart()
    fun finishDelay() = dispatcher.advanceBy(1500)
    val creates get() = directs.sumOf { it.platform.creates }
    val createdOperations get() = directs.flatMap { it.platform.createdOperations }
    fun close() { serviceScope.cancel(); dispatcher.drain() }
}

fun main(args: Array<String>) {
    val results = mutableListOf<Pair<String, String>>()
    fun case(name: String, action: (AapService, CommManager.ConnectionState.Disconnected, ConnectionOperation) -> Unit) {
        WirelessSleepHold.clear(); ScreenPower.interactive = false; SystemClock.now = 100_000L; SettingsActivity.isForeground = false
        val manager = CommManager(); val service = AapService(manager)
        val save = manager.connectionState.value as CommManager.ConnectionState.Disconnected
        val command = ConnectionOperation(manager.appConnectionTicket(), save)
        try { action(service, save, command); results.add(name to "PASS"); println("PASS $name") }
        catch (failure: Throwable) { results.add(name to "FAIL: ${failure.message}"); println("FAIL $name: ${failure.message}") }
        finally { service.close() }
    }
    fun holdFromDirect(service: AapService, command: ConnectionOperation) {
        val direct = Direct(service, checkNotNull(service.wifiLauncherManager.armedOperation))
        direct.refreshNativeCredentials(command)
        check(direct.platform.groups.size == 1) { "real refresh did not query group" }
        direct.platform.answer(null)
        check(direct.platform.creates == 0 && !direct.isGroupCreatingOrCreated) { "sleep did not hold group start" }
    }
    case("sleep-Save-cancelled-before-wake-no-create") { service, save, command ->
        holdFromDirect(service, command); service.homeExit(); check(save.isSettingsRestartCancelled)
        service.wake(); service.finishDelay()
        check(service.creates == 0) { "cancelled Save created ${service.creates} group(s) on wake" }
    }
    case("sleep-Save-cancelled-during-wake-settle-no-create") { service, save, command ->
        holdFromDirect(service, command); service.wake(); service.homeExit(); check(save.isSettingsRestartCancelled)
        check(service.commManager.appConnectionTicket() == command.ticket) { "Home Exit unexpectedly changed epoch" }
        service.finishDelay()
        check(service.creates == 0) { "cancelled Save created ${service.creates} group(s) after settle" }
    }
    case("sleep-valid-Save-keeps-original-operation") { service, _, command ->
        val arm = service.wifiLauncherManager.armedOperation
        holdFromDirect(service, command); service.wake(); service.finishDelay()
        check(service.creates == 1) { "valid Save created ${service.creates} groups" }
        check(service.createdOperations.single() === command) { "valid Save borrowed arm authority" }
        check(service.wifiLauncherManager.armedOperation === arm) { "Save replaced the ordinary arm" }
        service.commManager._connectionState.value = CommManager.ConnectionState.Connecting
        check(!service.commManager.isCurrent(command)) { "arrival did not consume Save" }
        check(service.commManager.isCurrent(checkNotNull(arm))) { "consumed Save disabled the long-lived arm" }
    }
    case("sleep-ordinary-arm-still-creates-once") { service, _, _ ->
        holdFromDirect(service, checkNotNull(service.wifiLauncherManager.armedOperation)); service.wake(); service.finishDelay()
        check(service.creates == 1)
    }
    case("sleep-Stop-Open-revokes-original-hold") { service, _, command ->
        holdFromDirect(service, command); service.commManager.stopAndOpen(); service.wake(); service.finishDelay()
        check(service.creates == 0) { "Stop-revoked hold created group" }
    }
    case("sleep-Save-settings-rehold-cancelled-before-close") { service, save, command ->
        holdFromDirect(service, command); service.wirelessPausedForSettings = true; service.wake()
        service.homeExit(); check(save.isSettingsRestartCancelled)
        service.onSettingsScreenChanged(inForeground = false); service.finishDelay()
        check(service.creates == 0) { "settings re-hold revived cancelled Save" }
    }
    case("sleep-Save-settings-rehold-live-control") { service, _, command ->
        val arm = service.wifiLauncherManager.armedOperation
        holdFromDirect(service, command); service.wirelessPausedForSettings = true; service.wake()
        service.onSettingsScreenChanged(inForeground = false); service.finishDelay()
        check(service.creates == 1)
        check(service.createdOperations.single() === command) { "settings re-hold replaced Save" }
        check(service.wifiLauncherManager.armedOperation === arm)
    }
    case("sleep-queued-replay-cannot-consume-new-hold") { service, _, command ->
        holdFromDirect(service, command); service.wake(); service.homeExit()
        val freshSave = CommManager.ConnectionState.Disconnected(reason = DisconnectReason.SETTINGS_RESTART)
        service.commManager._connectionState.value = freshSave
        val fresh = ConnectionOperation(command.ticket, freshSave)
        ScreenPower.interactive = false; service.wifi.isWifiEnabled = false
        holdFromDirect(service, fresh)
        service.finishDelay()
        check(service.creates == 0) { "old queued replay used its successor's authority" }
        service.wake(); service.finishDelay()
        check(service.creates == 1) { "fresh held command created ${service.creates} groups" }
        check(service.createdOperations.single() === fresh) { "fresh hold lost its original operation" }
    }
    case("sleep-old-Direct-owner-cannot-reclaim-successor") { service, _, command ->
        holdFromDirect(service, command); service.wake()
        Direct(service, checkNotNull(service.wifiLauncherManager.armedOperation))
        service.finishDelay()
        check(service.creates == 0) { "retired Direct hold rebuilt successor group" }
    }
    case("sleep-settings-stop-retires-original-Direct-resume") { service, _, command ->
        holdFromDirect(service, command); service.onSettingsScreenChanged(inForeground = true)
        service.wake(); service.onSettingsScreenChanged(inForeground = false); service.finishDelay()
        check(service.creates == 0) { "settings close let retired Direct reclaim its group" }
    }
    case("sleep-recovery-Save-cancelled-before-wake-no-create") { service, _, command ->
        val direct = Direct(service, checkNotNull(service.wifiLauncherManager.armedOperation))
        direct.recover(command); check(service.creates == 0)
        service.homeExit(); service.wake(); service.finishDelay()
        check(service.creates == 0) { "cancelled recovery Save recreated group" }
    }
    case("sleep-recovery-live-Save-resumes-original-Direct") { service, _, command ->
        val arm = service.wifiLauncherManager.armedOperation
        val direct = Direct(service, checkNotNull(arm))
        direct.recover(command); check(service.creates == 0)
        service.wake(); service.finishDelay()
        check(service.creates == 1 && direct.platform.creates == 1) { "recovery replaced original Direct" }
        check(service.createdOperations.single() === command)
        check(service.wifiLauncherManager.armedOperation === arm)
    }
    val payload = results.joinToString(",\n", "[\n", "\n]\n") { (name, result) ->
        "  {\"case\":\"$name\",\"result\":\"${result.replace("\"", "'")}\"}"
    }
    java.io.File(args.single()).writeText(payload)
    val failed = results.count { it.second != "PASS" }
    println("RESULT ${results.size - failed}/${results.size} PASS, $failed FAIL")
    check(failed == 0) { "$failed actual sleep replay assertions failed" }
}
