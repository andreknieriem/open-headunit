package cleanup
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext

class Queued : CoroutineDispatcher() {
    val queue = ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: CoroutineContext, block: Runnable) { queue.add(block) }
    fun drain() { while (true) (queue.poll() ?: return).run() }
}
class CommManager {
    sealed class ConnectionState {
        class Disconnected(val isUserExit: Boolean = true, val isClean: Boolean = false,
            val isSettingsRestart: Boolean = false, val holdsRecovery: Boolean = false,
            val hadPhysicalConnection: Boolean = true, val endedAttempt: Any? = null,
            val wasLoopbackSession: Boolean = false) : ConnectionState() {
            val settingsRetryOwner: Disconnected? = null
            fun acceptsSettingsRestart(other: Disconnected) = false
        }
        object Connecting : ConnectionState()
        object Connected : ConnectionState()
        class Error(val message: String) : ConnectionState()
    }
    val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
    // Production reads both under transportLifecycleLock; run.py exercises that method itself.
    var attempt: Any? = null
    var lastEndedAttempt: Any? = null
    fun rearmSnapshot(): Triple<ConnectionState.Disconnected?, Any?, Any?> {
        val terminal = connectionState.value as? ConnectionState.Disconnected
        return Triple(terminal, if (terminal == null) attempt else null, lastEndedAttempt)
    }
    val isConnected get() = connectionState.value is ConnectionState.Connected
    var gate = CompletableDeferred<Unit>()
    suspend fun awaitDisconnectComplete() { gate.await() }
    fun hasPendingDisconnect() = !gate.isCompleted
    var onDisconnect: (ConnectionState.Disconnected) -> Unit = {}
    var taskFinishAttempts = 0
    fun disconnect(sendByeBye: Boolean, honorKillOnDisconnect: Boolean) {
        val ended = ConnectionState.Disconnected(isUserExit = false)
        connectionState.value = ended
        onDisconnect(ended)
    }
    fun finishCurrentAppExitTasks() { taskFinishAttempts++ }
}
object SystemClock { fun elapsedRealtime() = 100L }
object android { object os { val SystemClock = cleanup.SystemClock } }
object AppLog { fun i(s: String) {}; fun w(s: String) {} }
object ConnectionStageTracker { var ends=0; fun endAttempt() { ends++ }; fun notePhoneLeft(t: Long) {} }
object GpsLocationService { fun intent(s: Service) = Unit }
object DummyVpnPolicy { enum class Reason { SESSION_ENDED } }
object ConnectionArbiter { fun sessionEnded(wirelessAlreadyRearmed: Boolean, userExit: Boolean) {} }
object SessionEndGroupPolicy {
    enum class Action { STOP, KEEP_AND_REARM, NONE }
    fun decide(activeModeIsNative: Boolean,isUserExit: Boolean,rearmedAfterWiredSession: Boolean) =
        if (activeModeIsNative && isUserExit && !rearmedAfterWiredSession) Action.STOP else Action.NONE
    fun wakesPhoneAfterSessionEnd(clean: Boolean, hand: Boolean) = !clean && !hand
}
enum class WifiLauncherMode { NATIVE }
enum class WifiLauncherStopSequence { LAST, BEFORE_HOTSPOT_DISABLE }
enum class HotspotExitAction { NONE, DISABLE, WARN_LEFT_UP }
object UserExitHotspotPolicy { fun onUserExit(a: Any,b: Any,c: Any,d: Any,e: Any) = HotspotExitAction.NONE }
object HotspotManager { fun restart(s: Service) = true }
class Settings {
    val wifiConnectionMode=WifiLauncherMode.NATIVE; val helperConnectionStrategy=0; val nativeApStrategy=0
    val autoEnableHotspot=false; var hotspotTeardownProvenUnsafe=false
}
class WifiLauncherNative {
    val handshakeManager: WifiLauncherNative? = null
    val strategy="Direct"
    fun hasWifiDirect() = true
    fun noteSessionEnded(b: Boolean) {}
    fun rearmAfterSessionEnd(wakePhone: Boolean) {}
}
class WifiManager(val service: Service) {
    private val cleanupGeneration=WirelessCleanupGeneration()
    var active: WifiLauncherNative? = WifiLauncherNative()
    val activeMode=WifiLauncherMode.NATIVE
    var cancelledByUser=false
    var stops=0
    val isActive get() = active != null
    private val nativeOwner = Any()
    private val nativeGroup = NativeGroupCleanupCoordinator().apply {
        check(!deferUntilReady(nativeOwner) {})
        check(activate(nativeOwner))
    }
    val sharedServices=this
    val wifiDirectManager: WifiManager?=null
    fun cleanupTicket()=cleanupGeneration.capture()
    fun isCleanupCurrent(ticket: Any)=cleanupGeneration.isCurrent(ticket)
    // LAUNCHER_ARM
    fun stop(seq: WifiLauncherStopSequence?=null) {
        stops++
        if (active != null) {
            val removal = NativeGroupCleanupCoordinator.Removal { completed ->
                service.networkRemovals.add(service.commManager.gate.isCompleted)
                completed(null)
            }
            nativeGroup.retain(nativeOwner, removal)
            check(nativeGroup.remove(nativeOwner, removal) {})
        }
        active=null
    }
    fun restartDiscovery() {}
}
class UnitManager { fun stop() {}; fun onSessionEnded() {}; fun setSwitchingToProjection(b: Boolean) {} }
class MediaSession { var isActive=true }
class ReconnectPlayback { var records=0; fun onDisconnected(b: Boolean?, now: Long, deliberate: Boolean) { records++ } }
class MediaNotification { fun cancel() {} }
object App { fun provide(s: Service)=s }
class VideoDecoder { var stops=0; fun stop(reason: String) { stops++ } }
// The callback reads only whether a Self launch still waits for its phone. That is derived from
// CommManager's arrivals; SelfLaunchFixture runs the real derivation. Here a case states it.
class SelfManager {
    var isLaunchPending=false
    fun stop(wasConnected: Boolean) { isLaunchPending=false }
}
class Service {
    var hctParks=0
    private fun parkRetainedHct(onlyCompleted: Boolean) { hctParks++ }
    val main=Queued()
    val serviceScope=CoroutineScope(SupervisorJob()+main)
    val commManager=CommManager()
    val selfLauncherManager=SelfManager()
    val wifiLauncherManager=WifiManager(this)
    val settings=Settings()
    val videoDecoder=VideoDecoder()
    var sessionEndedByHand=false; var pendingBtStandDown: Any?=null; var wirelessQuiescedForWiredSession=false
    var exitTeardownDone: CompletableDeferred<Unit>?=null
    var exitTeardownState: CommManager.ConnectionState.Disconnected?=null
    var isDestroying=false; var userExitedAA=false; var userExitCooldownUntil=0L
    val USER_EXIT_COOLDOWN_MS=5000L
    val usbLauncherManager=UnitManager(); val nightModeManager: UnitManager?=UnitManager(); val carKeysManager=UnitManager()
    var autoResumePlaybackJob: Job?=null; var mediaMetadataDecodeJob: Job?=null
    var lastAaMediaMetadata: Any?=null; var lastAaPlaybackPositionMs=0L; var lastAaPlaybackIsPlaying: Boolean?=null
    var cachedAaAlbumArtBitmap: Any?=null
    val reconnectPlayback=ReconnectPlayback(); val mediaNotification=MediaNotification()
    var gpsStops=0; var wifiReleases=0; var vpnStops=0; var reconnects=0; var deadlineCancels=0
    fun cancelProjectionRaiseDeadline() { deadlineCancels++ }
    fun cancelAllBtAutoDisconnects() {}
    fun releaseWifiLock() { wifiReleases++ }
    fun stopDummyVpn(r: DummyVpnPolicy.Reason) { vpnStops++ }
    fun stopService(intent: Unit) { gpsStops++ }
    fun updateNotification() {}
    fun applyPlaceholderMediaMetadata() {}
    fun safeMediaSessionCall(action: (MediaSession)->Unit) { action(MediaSession()) }
    fun updateMediaSessionState(active: Boolean) {}
    fun scheduleReconnectIfNeeded(state: CommManager.ConnectionState.Disconnected) { reconnects++ }
    suspend fun restartForAudioSettings(state: CommManager.ConnectionState.Disconnected) {}
    fun rearmWirelessAfterWiredSession(quiesced: Boolean)=false
    var serviceStops=0
    var foregroundStops=0
    val networkRemovals=mutableListOf<Boolean>()
    init { commManager.onDisconnect = { state -> serviceScope.launch { notifyEnd(state) } } }
    fun stopForeground(remove: Boolean) { foregroundStops++ }
    fun stopSelf() {
        serviceStops++
        // Android destruction reaches native launcher stop before CommManager.destroy.
        wifiLauncherManager.stop(WifiLauncherStopSequence.LAST)
        serviceScope.cancel()
    }
    fun requestStop() { stopForAcceptedAppExit() }
    // SERVICE_STOP
    fun notifyEnd(state: CommManager.ConnectionState.Disconnected) { onDisconnected(state) }
    // CALLBACK
}
fun main() {
    // Explicit arm before observer: session resources still retire, new attempt state survives.
    Service().apply {
        val ended=commManager.connectionState.value as CommManager.ConnectionState.Disconnected
        wifiLauncherManager.noteRearm()
        notifyEnd(ended); commManager.gate.complete(Unit); main.drain()
        check(gpsStops==1 && wifiReleases==1 && hctParks==0)
        check(vpnStops==1 && deadlineCancels==1 && reconnects==0)
        check(!userExitedAA && userExitCooldownUntil==0L && wifiLauncherManager.stops==0)
        serviceScope.cancel()
    }
    Service().apply {
        val ended=commManager.connectionState.value as CommManager.ConnectionState.Disconnected
        wifiLauncherManager.noteRearm(); selfLauncherManager.isLaunchPending=true
        exitTeardownState=ended; exitTeardownDone=CompletableDeferred()
        notifyEnd(ended); main.drain()
        check(vpnStops==0 && !exitTeardownDone!!.isCompleted)
        commManager.gate.complete(Unit); main.drain()
        check(exitTeardownDone!!.isCompleted)
        serviceScope.cancel()
    }
    // Explicit arm after observer but before teardown: old asynchronous stop is discarded.
    Service().apply {
        val ended=commManager.connectionState.value as CommManager.ConnectionState.Disconnected
        notifyEnd(ended); main.drain(); wifiLauncherManager.noteRearm()
        commManager.gate.complete(Unit); main.drain()
        check(wifiLauncherManager.stops==0)
        serviceScope.cancel()
    }
    // A separate terminal event still performs normal policy and completes only its own waiter.
    Service().apply {
        wifiLauncherManager.noteRearm()
        val ended=CommManager.ConnectionState.Disconnected()
        commManager.connectionState.value=ended
        exitTeardownState=ended; exitTeardownDone=CompletableDeferred()
        notifyEnd(ended); commManager.gate.complete(Unit); main.drain()
        check(wifiLauncherManager.stops==1 && gpsStops==1 && userExitedAA && hctParks==1)
        check(exitTeardownDone!!.isCompleted)
        serviceScope.cancel()
    }
    // An old queued terminal must neither touch a new attempt's policy nor complete its Stop
    // waiter. The new state's onConnected follows on this same collector, so the old session's
    // own resources are released now rather than leaked until the new session ends.
    Service().apply {
        val old=commManager.connectionState.value as CommManager.ConnectionState.Disconnected
        exitTeardownState=CommManager.ConnectionState.Disconnected(); exitTeardownDone=CompletableDeferred()
        commManager.connectionState.value=CommManager.ConnectionState.Connected
        val stageEnds=ConnectionStageTracker.ends
        notifyEnd(old); main.drain()
        check(gpsStops==1 && wifiReleases==1 && deadlineCancels==1 && reconnectPlayback.records==1)
        check(vpnStops==0 && reconnects==0 && userExitCooldownUntil==0L && ConnectionStageTracker.ends==stageEnds)
        check(!exitTeardownDone!!.isCompleted)
        commManager.gate.complete(Unit); main.drain()
        check(!exitTeardownDone!!.isCompleted && wifiLauncherManager.stops==0 && videoDecoder.stops==0)
        serviceScope.cancel()
    }
    // Self is admitted while an earlier attempt is still Connecting (isConnected is false). That
    // attempt's failure is created after the arm and must not end the new launch: no VPN stop,
    // reconnect policy, cooldown, stage end or deferred stop.
    for (userExit in listOf(false, true)) Service().apply {
        val attempt=Any()
        commManager.attempt=attempt; commManager.connectionState.value=CommManager.ConnectionState.Connecting
        selfLauncherManager.isLaunchPending=true; wifiLauncherManager.noteRearm()
        val failed=CommManager.ConnectionState.Disconnected(isUserExit=userExit, hadPhysicalConnection=false, endedAttempt=attempt)
        commManager.attempt=null; commManager.connectionState.value=failed
        val stageEnds=ConnectionStageTracker.ends
        notifyEnd(failed); commManager.gate.complete(Unit); main.drain()
        check(vpnStops==0 && reconnects==0 && selfLauncherManager.isLaunchPending) { "old Connecting end cleared new Self" }
        check(!userExitedAA && userExitCooldownUntil==0L && ConnectionStageTracker.ends==stageEnds)
        check(wifiLauncherManager.stops==0 && videoDecoder.stops==0)
        check(gpsStops==1 && wifiReleases==1)
        // Self's own later attempt is not fenced and ends with the ordinary policy. Its physical
        // arrival answered the launch, so the launch no longer holds the VPN.
        val own=Any()
        commManager.attempt=own; commManager.connectionState.value=CommManager.ConnectionState.Connecting
        selfLauncherManager.isLaunchPending=false
        val selfEnd=CommManager.ConnectionState.Disconnected(isUserExit=false, endedAttempt=own)
        commManager.attempt=null; commManager.connectionState.value=selfEnd
        notifyEnd(selfEnd); main.drain()
        check(vpnStops==1 && reconnects==1)
        serviceScope.cancel()
    }
    // The same Connecting attempt reaching a physical connection after the arm superseded it,
    // and with that arrival answered the launch.
    Service().apply {
        val attempt=Any()
        commManager.attempt=attempt; commManager.connectionState.value=CommManager.ConnectionState.Connecting
        wifiLauncherManager.noteRearm()
        val ended=CommManager.ConnectionState.Disconnected(isUserExit=false, hadPhysicalConnection=true, endedAttempt=attempt)
        commManager.attempt=null; commManager.connectionState.value=ended
        notifyEnd(ended); commManager.gate.complete(Unit); main.drain()
        check(vpnStops==1 && reconnects==1)
        serviceScope.cancel()
    }
    // Bluetooth stand-down belongs to the ended session. A socket that only publishes a newer
    // state, before or during teardown, does not withdraw it; a launcher arm since then does.
    for (supersededAtCallback in listOf(true, false)) for (armed in listOf(false, true)) Service().apply {
        val ended=CommManager.ConnectionState.Disconnected(isUserExit=true)
        commManager.connectionState.value=ended
        pendingBtStandDown=wifiLauncherManager.cleanupTicket()
        if (supersededAtCallback) commManager.connectionState.value=CommManager.ConnectionState.Connecting
        notifyEnd(ended); main.drain()
        check(pendingBtStandDown==null && gpsStops==1 && wifiReleases==1 && reconnectPlayback.records==1)
        check(wifiLauncherManager.stops==0) // Not before CommManager finishes ByeBye/close.
        if (!supersededAtCallback) commManager.connectionState.value=CommManager.ConnectionState.Connecting
        if (armed) {
            wifiLauncherManager.noteRearm()
            userExitedAA = false // Explicit Start clears the exit hold before re-arming.
        }
        commManager.gate.complete(Unit); main.drain()
        check(wifiLauncherManager.stops==(if (armed) 0 else 1) && userExitedAA==!armed) {
            "stand-down supersededAtCallback=$supersededAtCallback armed=$armed"
        }
        serviceScope.cancel()
    }
    // The original BT terminal may never reach this callback (first failed open or
    // StateFlow conflation). Explicit rearm revokes that pending intent before any new end.
    for (rearmed in listOf(false, true)) Service().apply {
        commManager.connectionState.value=CommManager.ConnectionState.Connecting
        pendingBtStandDown=wifiLauncherManager.cleanupTicket()
        val skipped=CommManager.ConnectionState.Disconnected(hadPhysicalConnection=false)
        commManager.connectionState.value=skipped
        if (rearmed) wifiLauncherManager.noteRearm()
        commManager.connectionState.value=CommManager.ConnectionState.Connected
        val ended=CommManager.ConnectionState.Disconnected(isUserExit=false)
        commManager.connectionState.value=ended
        notifyEnd(ended); commManager.gate.complete(Unit); main.drain()
        check(pendingBtStandDown==null)
        check(wifiLauncherManager.stops==(if (rearmed) 0 else 1))
        check(userExitedAA==!rearmed)
        serviceScope.cancel()
    }
    // A terminal can be observed before a fresh service Stop creates any exit waiter.
    // Closing the physical transport, not that logical terminal, must admit native removal.
    for (rearmedWhileWaiting in listOf(false, true)) Service().apply {
        try {
            val ended=commManager.connectionState.value as CommManager.ConnectionState.Disconnected
            notifyEnd(ended); main.drain()
            check(exitTeardownDone==null)
            requestStop(); main.drain()
            println("STOP_RETIREMENT pending: stops=$serviceStops removeClosed=$networkRemovals")
            check(serviceStops==0 && networkRemovals.isEmpty()) {
                "native removal preceded actual transport close after Disconnected"
            }
            if (rearmedWhileWaiting) wifiLauncherManager.noteRearm()
            commManager.gate.complete(Unit); main.drain()
            check(serviceStops==1 && networkRemovals.isNotEmpty() && networkRemovals.all { it }) {
                "Stop failed to join completed retirement without a new observer latch"
            }
        } finally { serviceScope.cancel() }
    }
    Service().apply {
        try {
            commManager.connectionState.value=CommManager.ConnectionState.Connected
            requestStop(); main.drain()
            check(serviceStops==0 && networkRemovals.isEmpty())
            commManager.gate.complete(Unit); main.drain()
            check(serviceStops==1 && networkRemovals.isNotEmpty() && networkRemovals.all { it })
        } finally { serviceScope.cancel() }
    }
    Service().apply {
        commManager.gate.complete(Unit)
        requestStop()
        check(serviceStops==1 && networkRemovals.isNotEmpty() && networkRemovals.all { it })
    }
    // An ordinary wireless arm can take the cleanup ticket before a loopback terminal is observed.
    // The callback keeps no Self state to end: only the VPN is decided here, and it is released
    // unless a Self launch still waits for its phone. A launch the session answered, or none, lets
    // it go; one accepted after the terminal, or after its first publication when teardown
    // republishes it behind an Error, keeps it. The callback never retires a Self launch itself.
    val repeated = "accepted-before-repeated-terminal"
    for (launch in listOf("none", "completed-earlier", "accepted-after-terminal", repeated)) Service().apply {
        try {
            val attempt=Any()
            val ended=CommManager.ConnectionState.Disconnected(isUserExit=false, wasLoopbackSession=true, endedAttempt=attempt)
            val newer = launch == "accepted-after-terminal" || launch == repeated
            selfLauncherManager.isLaunchPending=newer
            commManager.connectionState.value=ended
            wifiLauncherManager.noteRearm()
            notifyEnd(ended); commManager.gate.complete(Unit); main.drain()
            check(vpnStops==if (newer) 0 else 1) {
                "loopback terminal after a wireless arm misassigned the VPN (launch=$launch)"
            }
            check(reconnects==0 && selfLauncherManager.isLaunchPending==newer)
        } finally { serviceScope.cancel() }
    }
    // B's Legacy arm can run in the Error before teardown republishes the ended attempt's
    // terminal. That repeat had already ended at the arm: it must not run B's VPN or reconnect
    // policy, physical or not.
    Service().apply {
        try {
            val attempt=Any()
            commManager.attempt=attempt; commManager.lastEndedAttempt=attempt
            val error=CommManager.ConnectionState.Error("USB permission not granted for device")
            commManager.connectionState.value=error
            selfLauncherManager.isLaunchPending=true
            wifiLauncherManager.noteRearm()
            val repeated=CommManager.ConnectionState.Disconnected(isUserExit=false, wasLoopbackSession=true, endedAttempt=attempt)
            commManager.attempt=null; commManager.connectionState.value=repeated
            notifyEnd(repeated); commManager.gate.complete(Unit); main.drain()
            check(selfLauncherManager.isLaunchPending && vpnStops==0 && reconnects==0) {
                "repeated terminal ran the newer arm's policy (vpnStops=$vpnStops reconnects=$reconnects)"
            }
        } finally { serviceScope.cancel() }
    }
    println("PASS a Self arm in the Error over an ended attempt's terminal is not retired by that terminal's repeat")
    println("PASS loopback terminal after an ordinary wireless arm releases the VPN unless a Self launch still waits for its phone, including a completed launch; a launch accepted after it, or after its first publication, keeps it")
    println("PASS service Stop joins existing physical retirement before native removal; connected, completed and rearmed controls")
    println("PASS actual disconnect callback: stale policy vs session resources, rearm across await, separate terminal and captured Stop waiter")
    println("PASS actual disconnect callback: Connecting attempt older than Self arm, superseded resource release, Bluetooth stand-down ownership")
}
