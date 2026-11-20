package directcommand

import java.util.ArrayDeque

class Save(var pending: Boolean = true)
class ConnectionOperation(val ticket: Long, val save: Save?)
class CommManager {
    var ticket = 1L
    fun isCurrent(operation: ConnectionOperation) = operation.ticket == ticket && operation.save?.pending != false
    fun <T> withAppConnectionLifecycleLock(action: () -> T): T = synchronized(this) { action() }
}
object Looper { var main = true; private val mainThread = Any(); fun myLooper(): Any? = if (main) mainThread else null; fun getMainLooper() = mainThread }
object SystemClock { fun elapsedRealtime() = 100_000L }
object AppLog { fun i(message: String) {}; fun w(message: String) {}; fun d(message: String) {} }
class Handler {
    val pending = ArrayDeque<Runnable>()
    fun post(action: Runnable) { pending.addLast(action) }
    fun postDelayed(action: Runnable, delay: Long) { pending.addLast(action) }
    fun removeCallbacks(action: Runnable) { pending.removeIf { it === action } }
    fun removeCallbacksAndMessages(token: Any?) { pending.clear() }
    fun drain() { var remaining = 30; while (pending.isNotEmpty()) { check(remaining-- > 0); pending.removeFirst().run() } }
}
class WifiP2pGroup(val isGroupOwner: Boolean = true, val networkName: String = "group")
class Info
class WifiP2pManager {
    class Channel
    interface ActionListener { fun onSuccess(); fun onFailure(reason: Int) }
    val groups = ArrayDeque<(WifiP2pGroup?) -> Unit>()
    var requests = 0
    var connectionReads = 0
    fun requestGroupInfo(channel: Channel?, callback: (WifiP2pGroup?) -> Unit) { requests++; groups.add(callback) }
    fun requestConnectionInfo(channel: Channel?, callback: (Info) -> Unit) { connectionReads++ }
    fun requestConnectionInfo(channel: Channel?, callback: Direct) { connectionReads++ }
    fun answer(group: WifiP2pGroup?) { groups.removeFirst()(group) }
}
object RetainedNativeGroup {
    var blocked = false
    var owner: Any? = null
    var pending: (() -> Unit)? = null
    fun deferUntilReady(owner: Any, action: () -> Unit): Boolean { if (!blocked) return false; pending = action; return true }
    fun isOwner(owner: Any) = this.owner === owner
    fun cancelWaiting(owner: Any) {}
    fun retain(owner: Any, manager: WifiP2pManager, channel: WifiP2pManager.Channel) {}
    fun activate(owner: Any) = true
    fun resume() { blocked = false; pending.also { pending = null }?.invoke() }
}
class Direct(val commManager: CommManager) {
    private val operation = ConnectionOperation(commManager.ticket, null)
    private val groupOwnership = Any().also { RetainedNativeGroup.owner = it }
    var generation = 0
    var stopped = false
    var isGroupOwner = false
    var nativeCreateRequestedAtMs = 0L
    var isGroupCreatingOrCreated = false
    var checkGroupAndCreateInFlight = false
    val handler = Handler()
    val platform = WifiP2pManager()
    private val manager: WifiP2pManager? = platform
    private val channel: WifiP2pManager.Channel? = WifiP2pManager.Channel()
    var creates = 0
    var deliveries = 0
    var deliveredOperation: ConnectionOperation? = null
    fun msSinceAcceptedCreate(): Long? = null
    fun markP2pRequest() {}
    fun startNativeAaQuietHost() { creates++ }
    fun startNativeAaQuietHost(operation: ConnectionOperation) { creates++ }
    fun makeVisible() { creates++ }
    fun makeVisible(operation: ConnectionOperation) { creates++ }
    fun onGroupInfoAvailable(group: WifiP2pGroup?) { deliveries++ }
    fun handleGroupInfo(group: WifiP2pGroup?, operation: ConnectionOperation) { deliveries++; deliveredOperation = operation }
    fun handleConnectionInfo(info: Info, operation: ConnectionOperation) {}
    // CURRENT
    // DEFER
    // SUPERSEDED
    // ACTIVATE
    // RECHECK
    // NATIVE
    // HELPER
    fun ask(operation: ConnectionOperation) { // ASK_NATIVE
    }
    fun askHelper(operation: ConnectionOperation) { // ASK_HELPER
    }
}
object WifiBandCapability { var frequency = 0; fun reportSessionFrequency(value: Int) { frequency = value } }
object ConnectionStageTracker { var network: String? = null; fun reportNetwork(value: String?) { network = value } }
class Flag(var value: Boolean)
object AapService { val scanningState = Flag(false) }
object SoftApEndpointStabilityPolicy { const val UNDECIDED_UNTIL_BRING_UP = true }
class Context { var removals = 0; fun unregisterReceiver(receiver: Any) { removals++ } }
class StopDirect {
    val commManager = CommManager()
    val operation = ConnectionOperation(commManager.ticket, null)
    val groupOwnership = Any()
    val createWindowLock = Any()
    var nativeCreateOwner: ConnectionOperation? = operation
    var generation = 0; var stopped = false; var credentialsEpoch = 0; var isGroupCreatingOrCreated = false
    var wifiEnableAttempts = 0; var acceptedCreateWithoutGroupSinceMs = 0L; var wedgeCancelSpentForStampMs = 0L
    var stuckCreateCancels = 0; var groupInfoEpoch = 0; val handler = Handler(); var checkGroupAndCreateInFlight = false
    var lastNativeBringUpAtMs = 0L; var isClientConnected = false; var nativeGroupCreationMode = ""; var native5GhzBandMismatchRetries = 0
    var nativeAdoptDecisionPending = false; var nativeRecreateCount = 0; var lastNativeGroupStatusMessage: String? = null
    var legacyChannelAttempt = 0; var lastGroupRefusalReportAtMs = 0L; var churnWindowStartedAtMs = 0L
    var churnEventsInWindow = 0; var churnForeignEventsInWindow = 0; var lastChurnReportAtMs = 0L
    var pinnedChannelAbandoned = false; var namedFallbackRefused = false; var lastPersistentPurgeVerdict: Any? = null
    var legacyChannelRequestUnanswered = true; var isReceiverRegistered = true; var isGroupOwner = false
    var adoptQueryInFlight = false; var isConnected = false
    val context = Context(); val receiver = Any()
    val manager: WifiP2pManager? = WifiP2pManager(); val channel: WifiP2pManager.Channel? = WifiP2pManager.Channel()
    var channelClears = 0
    fun releaseNativeCreateWindow(why: String) { nativeCreateOwner = null }
    fun releaseNativeCreateWindow(why: String, operation: ConnectionOperation) { if (nativeCreateOwner === operation) nativeCreateOwner = null }
    fun forgetPerGroupKeys() {}
    fun releaseLegacyChannelRestriction(manager: WifiP2pManager, channel: WifiP2pManager.Channel, force: Boolean) { channelClears++ }
    fun removeGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) { listener.onSuccess() }
    companion object { const val NATIVE_GROUP_MODE_UNKNOWN = "unknown" }
    // STOP_BODY
}
fun main(args: Array<String>) {
    val results = mutableListOf<Pair<String,String>>()
    fun test(name: String, body: (CommManager,Direct,Save,ConnectionOperation) -> Unit) {
        Looper.main = true; RetainedNativeGroup.blocked = false; RetainedNativeGroup.pending = null; RetainedNativeGroup.owner = null
        try {
            val comm = CommManager(); val direct = Direct(comm); val save = Save(); val command = ConnectionOperation(comm.ticket,save)
            body(comm,direct,save,command); results.add(name to "PASS"); println("PASS $name")
        } catch (failure: Throwable) { results.add(name to "FAIL: ${failure.message}"); println("FAIL $name: ${failure.message}") }
    }
    test("Direct-Save-withdrawn-before-entry") { _,d,s,o -> s.pending=false; d.ask(o); check(d.platform.requests==0) { "withdrawn Save queried group" } }
    test("Direct-Save-withdrawn-before-create-callback") { _,d,s,o -> d.ask(o); s.pending=false; d.platform.answer(null); check(d.creates==0) { "old Save recreated group" } }
    test("Direct-Save-withdrawn-before-credential-callback") { _,d,s,o -> d.ask(o); s.pending=false; d.platform.answer(WifiP2pGroup()); check(d.deliveries==0 && d.platform.connectionReads==0) { "old Save redelivered credentials" } }
    test("Direct-Save-withdrawn-during-main-hop") { _,d,s,o -> Looper.main=false; d.ask(o); s.pending=false; Looper.main=true; d.handler.drain(); check(d.platform.requests==0) { "old queued refresh read group" } }
    test("Direct-Save-withdrawn-before-recheck") { _,d,s,o -> d.nativeCreateRequestedAtMs=99_990L; d.ask(o); d.platform.answer(null); s.pending=false; d.handler.drain(); check(d.platform.requests==1) { "old grace timer queried group again" } }
    test("Direct-Save-withdrawn-behind-removal") { _,d,s,o -> RetainedNativeGroup.blocked=true; d.ask(o); s.pending=false; RetainedNativeGroup.resume(); check(d.platform.requests==0) { "old removal continuation queried group" } }
    test("Direct-Helper-Save-withdrawn-before-callback") { _,d,s,o -> d.askHelper(o); s.pending=false; d.platform.answer(null); check(d.creates==0) { "old Helper Save recreated group" } }
    test("Direct-valid-Save-creates-once") { _,d,_,o -> d.ask(o); d.platform.answer(null); check(d.creates==1 && d.platform.requests==1) }
    test("Direct-valid-Save-redelivers-existing-group") { _,d,_,o -> d.ask(o); d.platform.answer(WifiP2pGroup()); check(d.creates==0 && d.deliveries==1 && d.platform.connectionReads==1) }
    test("Direct-valid-Helper-Save-preserves-existing-group") { _,d,_,o -> d.askHelper(o); d.platform.answer(WifiP2pGroup()); check(d.creates==0) }
    test("Direct-original-arm-Stop-revokes-command") { comm,d,_,o -> d.ask(o); comm.ticket++; d.platform.answer(null); check(d.creates==0) { "old arm recreated after Stop" } }
    for (priorOwner in listOf(false,true)) test(if(priorOwner) "Direct-old-owner-stop-keeps-successor-metadata" else "Direct-rejected-candidate-stop-keeps-successor-metadata") { _,_,_,_ ->
        val old=StopDirect(); val current=StopDirect()
        if(priorOwner) RetainedNativeGroup.owner=old.groupOwnership
        RetainedNativeGroup.owner=current.groupOwnership
        WifiBandCapability.frequency=5805; ConnectionStageTracker.network="successor"; AapService.scanningState.value=true
        old.stop()
        check(WifiBandCapability.frequency==5805 && ConnectionStageTracker.network=="successor" && AapService.scanningState.value) { "old candidate cleared successor metadata" }
        check(old.context.removals==1 && old.channelClears==0) { "cleanup did not stay with the original resource" }
    }
    test("Direct-owning-stop-clears-own-metadata") { _,_,_,_ ->
        val current=StopDirect(); RetainedNativeGroup.owner=current.groupOwnership
        WifiBandCapability.frequency=5805; ConnectionStageTracker.network="current"; AapService.scanningState.value=true
        current.stop()
        check(WifiBandCapability.frequency==0 && ConnectionStageTracker.network==null && !AapService.scanningState.value)
        check(current.context.removals==1 && current.channelClears==1)
    }
    val payload=results.joinToString(",\n", "[\n", "\n]\n") { (name,result) -> "  {\"case\":\"$name\",\"result\":\"${result.replace("\"","'")}\"}" }
    java.io.File(args.single()).writeText(payload)
    val failed=results.count { it.second!="PASS" }; println("RESULT ${results.size-failed}/${results.size} PASS, $failed FAIL")
    check(failed==0) { "$failed actual Direct command assertions failed" }
}
