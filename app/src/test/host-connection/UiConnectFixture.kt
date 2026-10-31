package uiconnect

import android.content.Context as PlatformContext
import android.content.Intent as PlatformIntent
import lifecycle.CommManager
import lifecycle.ConnectionOperation
import lifecycle.ConnectionArbiter
import lifecycle.ConnectionPriorityPolicy
import lifecycle.ConnectionState
import lifecycle.HeldServerSocket
import lifecycle.QueuedDispatcher
import lifecycle.ServiceBootstrap
import lifecycle.UsbRecheck
import kotlinx.coroutines.*
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue

// The host runner inserts complete production members at these markers. Only Android/UI,
// scheduling and unrelated effect dependencies are boundaries; connection ownership is real.
object Dispatchers { var IO = QueuedDispatcher() }
open class Context(val commManager: CommManager) {
    val settings = Settings()
    val android = PlatformContext()
    val connectivity = ConnectivityManager()
    fun getSystemService(name: String): Any = connectivity
    companion object { const val CONNECTIVITY_SERVICE = "connectivity" }
}
object App { fun provide(context: Context) = context }
class Settings {
    var killOnDisconnect = false
    var exporterLogLevel = "info"
    var nightMode = NightMode.AUTO
    val wifiConnectionMode = Unit
    fun showsSelf() = false
    fun showsWifi() = false
    enum class NightMode { DAY, NIGHT, AUTO }
}
class Data(val scheme: String, val host: String, private val query: Map<String, String> = emptyMap()) {
    fun getQueryParameter(name: String) = query[name]
}
class Intent {
    var action: String? = null
    var data: Data? = null
    private val extras = mutableMapOf<String, Any>()
    constructor(action: String) { this.action = action }
    constructor(context: Context, target: Class<*>)
    fun putExtra(name: String, value: Any) = apply { extras[name] = value }
    fun getStringExtra(name: String) = extras[name] as? String
    fun getBooleanExtra(name: String, fallback: Boolean) = extras[name] as? Boolean ?: fallback
    fun getLongExtra(name: String, fallback: Long) = extras[name] as? Long ?: fallback
    fun removeExtra(name: String) { extras.remove(name) }
    fun hasExtra(name: String) = extras.containsKey(name)
    fun setAction(action: String?) = apply { this.action = action }
    fun platform() = PlatformIntent(action ?: "").also { output ->
        extras.forEach { (name, value) -> if (value is Long) output.putExtra(name, value) }
    }
    companion object { const val ACTION_VIEW = "view" }
}
object ContextCompat {
    val starts = mutableListOf<Intent>()
    var startHook: (() -> Unit)? = null
    fun startForegroundService(context: Context, intent: Intent) {
        starts.add(intent)
        startHook?.also { startHook = null }?.invoke()
    }
}
class AapService {
    fun wirelessCancelledByUser() = false
    fun nativeAttemptInFlight() = false
    companion object {
        var instance: AapService? = null
        const val ACTION_START_SELF_MODE = "start-self"
        const val EXTRA_SELF_MODE_VPN = "self-vpn"
        const val ACTION_CONNECT_SOCKET = "connect-socket"
        const val ACTION_CHECK_USB = "check-usb"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_STOP_SERVICE = ServiceBootstrap.ACTION_STOP_SERVICE
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "night-update"
        const val EXTRA_USER_REQUESTED = "user-requested"
        // USB relays carry their original owed scan.
        fun usbCheckIntent(context: Context, recheck: UsbRecheck, action: String? = ACTION_CHECK_USB,
            userRequested: Boolean = false) = operationIntent(context, recheck.ticket).setAction(action)
            .putExtra("relayed_usb_recheck", "${recheck.ticket}.${recheck.epoch}")
        // UI_SERVICE_CONSTANTS
        // UI_OPERATION_INTENT
        // UI_ACCEPTED_STOP_INTENT
        // UI_STOP_INTENT
    }
}
/** The home's automatic start; [view] stands for whether a home view is up when MainActivity acts. */
object HomeFragment { var pendingDriverSelectionTicket: Long? = null; var view: Any? = null }
object BtAutoStartRearmPolicy {
    var selfMode = false
    fun launchesSelfMode(selfSelected: Boolean, wirelessSelected: Boolean, mode: Any,
        sessionUp: Boolean, nativeAttemptInFlight: Boolean?) = selfMode
}
class Fragments { val fragments: List<Any> = listOf(HomeFragment) }
class NavHost { val childFragmentManager = Fragments() }
class SupportFragments { fun findFragmentById(id: Int): NavHost? = NavHost() }
class ConnectivityManager { val activeNetwork: Any? = Any() }
object Build { object VERSION { const val SDK_INT = 35 }; object VERSION_CODES { const val M = 23 } }
object SelfLauncherManager { fun installedPath(context: Context) = 1 }
object SelfLaunchRoutePolicy { fun needsDummyVpn(path: Int, offline: Boolean, vpnAvailable: Boolean) = false }
object VpnControl { fun isVpnAvailable() = false; fun consentIntent(context: Context): Intent? = null }
class MainActivity(manager: CommManager, val lifecycleScope: CoroutineScope) : Context(manager) {
    private class ForcedSelfConsent(val ticket: Long, var cancelled: Boolean = false)
    private var pendingForcedSelf: ForcedSelfConsent? = null
    private var queuedForcedSelfTicket: Long? = null
    private val forcedSelfVpnPermission = object { fun launch(intent: Intent) {} }
    enum class ConnectionUiMode { OVERLAY, PILL }
    val events = ConcurrentLinkedQueue<() -> Unit>()
    var overlays = 0
    fun beginAutoConnect(source: String, mode: ConnectionUiMode) { overlays++ }
    fun runOnUiThread(block: () -> Unit) { events.add(block) }
    fun drainUi() { while (true) (events.poll() ?: return).invoke() }
    fun finishAffinity() {}
    val supportFragmentManager = SupportFragments()
    companion object {
        const val EXTRA_SHOW_DRIVER_SELECTOR = "show-selector"
        const val EXTRA_LAUNCH_SOURCE = "launch-source"
        const val LAUNCH_SOURCE_BLUETOOTH = "bluetooth"
        const val EXTRA_CONNECTION_TICKET = "connection-ticket"
    }
    // UI_FORCE_SELF_MODE
    // UI_FORCED_SELF_DISPATCH
    // UI_CANCEL_PENDING_SELF
    // UI_HANDLE_LAUNCH_INTENT
}
object android { object R { object id { const val button2 = 2 } } }
object R {
    object integer { const val key_data = 1 }
    object string { const val found_connecting = 1 }
    object id { const val main_content = 3 }
}
class View(val id: Int, val value: String) { fun getTag(key: Int): Any = value }
class AddressAdapter(private val context: Context, private val scope: CoroutineScope) {
    val addressList = mutableListOf<String>()
    fun addNewAddress(ip: InetAddress) { addressList.add(ip.hostAddress) }
    fun removeAddress(ip: String) { addressList.remove(ip) }
    // UI_ADDRESS_ON_CLICK
}
class Dialog { var isShowing = true; fun dismiss() { isShowing = false } }
class Discovery(private val manager: CommManager) {
    var operation: ConnectionOperation? = null
    private var running = false
    val ticket get() = operation?.ticket ?: -1L
    fun startScan(oneShot: Boolean, operation: ConnectionOperation): Boolean {
        if (running && this.operation?.ticket == operation.ticket) return false
        this.operation = operation
        running = true
        return true
    }
    fun stop() { running = false }
}
class ScanFragment(val activity: MainActivity, val lifecycleScope: CoroutineScope) {
    val context: Context? get() = activity
    val adapter = AddressAdapter(activity, lifecycleScope)
    val networkDiscovery = Discovery(activity.commManager)
    var scanDialog: Dialog? = null
    private var scanOperation: ConnectionOperation? = null
    fun showScanDialog() { scanDialog = Dialog() }
    fun requireContext(): Context = activity
    fun getString(id: Int, vararg args: Any): String = "$id"
    // UI_START_SCAN
    // UI_CANCEL_SCAN
    // UI_SERVICE_FOUND
}
object AppLog { fun i(text: String) {}; fun w(text: String) {}; fun e(text: String, error: Throwable) {} }
object Toast { const val LENGTH_SHORT = 0 }
object ToastUtils { fun showToast(context: Context?, text: String, duration: Int) {} }
class JSONObject {
    private val values = mutableMapOf<String, Any?>()
    fun put(name: String, value: Any?) = apply { values[name] = value }
    fun string(name: String) = values[name] as? String
}
object AutomationCommandPolicy {
    // UI_AUTOMATION_EFFECTS
}
object SettingsBackupManager {
    class Reset(val resetKeys: Int)
    fun resetFromContext(context: Context) = Reset(0)
}
object LogExporter { fun startCapture(context: Context, level: String) {}; fun stopCapture() {} }
object AutomationEffectRunner {
    // UI_AUTOMATION_RUN
    // UI_AUTOMATION_START_SERVICE
    // UI_AUTOMATION_NIGHT_MODE
    private fun importSettings(context: Context, effect: AutomationCommandPolicy.Effect.ImportSettings, reply: JSONObject) {}
    private fun exportSettings(context: Context, effect: AutomationCommandPolicy.Effect.ExportSettings, reply: JSONObject) = true
    private fun exportLog(context: Context, settings: Settings, effect: AutomationCommandPolicy.Effect.ExportLog, reply: JSONObject) = true
    private fun logLevelOf(level: String) = level
    private fun putState(context: Context, reply: JSONObject) {}
}
private class ProbeSocket : Socket() { override fun isConnected() = true }
private class Claims(private val manager: CommManager) : ConnectionArbiter.Actions {
    var preempts = 0
    var standDowns = 0
    override fun preempt(loser: ConnectionArbiter.Claim) {
        preempts++
        manager.disconnectPreempted(loser)
        manager.cleanups()
    }
    override fun standDownWireless(by: ConnectionArbiter.Claim): Boolean { standDowns++; return false }
    override fun giveBack(wireless: Boolean, usb: UsbRecheck?) {}
    override fun schedule(delayMs: Long, block: () -> Unit) {}
}
private fun CommManager.inlineConnectionIo() {
    CommManager::class.java.getDeclaredField("connectionIoDispatcher")
        .apply { isAccessible = true }.set(this, kotlinx.coroutines.Dispatchers.Unconfined)
}
private class Input(val manager: CommManager) {
    val job = SupervisorJob()
    val scope = CoroutineScope(job + Dispatchers.IO)
    val main = MainActivity(manager, scope)
    val list = AddressAdapter(main, scope)
    val scan = ScanFragment(main, scope)
    var probe: ProbeSocket? = null
    fun accept(route: String) {
        when (route) {
            "main" -> main.handleLaunchIntent(Intent(Intent.ACTION_VIEW).apply {
                data = Data("headunit", "connect", mapOf("ip" to "192.0.2.151"))
            })
            "list" -> list.onClick(View(android.R.id.button2, "192.0.2.152"))
            "automation" -> check(AutomationEffectRunner.run(main, listOf(
                AutomationCommandPolicy.Effect.StartService(AapService.ACTION_CONNECT_SOCKET),
                AutomationCommandPolicy.Effect.ConnectSocket("192.0.2.153", 5277)), JSONObject()))
            else -> {
                scan.startScan()
                // NetworkDiscovery holds every 5277 probe before it reports it to the scan UI.
                if (route == "scan-socket") probe = ProbeSocket().also { HeldServerSocket.hold("192.0.2.154:5277", it) }
                scan.onServiceFound("192.0.2.154", 5277, probe, checkNotNull(scan.networkDiscovery.operation))
                main.drainUi()
            }
        }
    }
    fun close() { job.cancel(); Dispatchers.IO.drain(); probe?.close(); HeldServerSocket.discardAll() }
}

/** Real logical producers -> real public connect overloads/arbiter and real service ingress. */
fun uiConnectCallerOwnership() = runBlocking {
    for (route in listOf("main", "list", "automation", "scan-ip", "scan-socket")) {
        for (stop in listOf(false, true)) for (successor in listOf(false, true)) {
            Dispatchers.IO = QueuedDispatcher()
            ContextCompat.starts.clear()
            val manager = CommManager()
            manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), honorKillOnDisconnect = false)
            manager.cleanups()
            manager.inlineConnectionIo()
            val claims = Claims(manager)
            ConnectionArbiter.actions = claims
            val input = Input(manager)
            val liveSocket = Socket()
            try {
                input.accept(route)
                check(Dispatchers.IO.tasks.isNotEmpty()) { "$route did not pause before public connect" }
                if (stop) checkNotNull(manager.requestExplicitAppExit(ticket = manager.appConnectionTicket(), killProcess = false))
                val opened = manager.onAppRelaunched()
                if (successor) manager.connect(liveSocket, ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE, ticket = manager.appConnectionTicket())
                val live = manager.network()
                val revision = manager.appOwnershipRevision()
                Dispatchers.IO.drain()
                if (stop) {
                    check(claims.preempts == 0 && claims.standDowns == 0) { "$route disturbed the next Run" }
                    check(manager.appOwnershipRevision() == revision)
                    if (successor) check(manager.network() === live && live?.closed == false)
                    else check(manager.state is ConnectionState.Disconnected && manager.isAppRunOwner(opened))
                    input.probe?.let { check(it.isClosed) }
                } else {
                    check(manager.state is ConnectionState.Connected && manager.network() != null)
                    check(claims.standDowns == 1)
                    if (successor) check(claims.preempts == 1 && live?.closed == true && manager.network() !== live)
                }
                // Replay the actual generated relay only after B/C. Ownership must be decided by
                // the real onStartCommand prefix, not a fixture comparison of extra values.
                val relay = ContextCompat.starts.single()
                val service = ServiceBootstrap(manager)
                service.onStartCommand(relay.platform(), 0, 1)
                check(service.initializedForStart == !stop) { "$route relay bootstrapped after Stop" }
                check(service.stops == if (stop) 1 else 0)
            } finally { input.close(); liveSocket.close(); manager.close() }
        }
    }

    // A scan result itself may still be waiting for Main when Stop arrives. A genuinely new
    // manual scan after Stop has a different ticket and remains allowed (no valid-Run ban).
    Dispatchers.IO = QueuedDispatcher()
    val manager = CommManager()
    manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), honorKillOnDisconnect = false); manager.cleanups(); manager.inlineConnectionIo()
    val input = Input(manager)
    val stale = ProbeSocket()
    try {
        input.scan.startScan()
        val oldScan = checkNotNull(input.scan.networkDiscovery.operation)
        HeldServerSocket.hold("192.0.2.155:5277", stale)
        input.scan.onServiceFound("192.0.2.155", 5277, stale, oldScan)
        checkNotNull(manager.requestExplicitAppExit(ticket = manager.appConnectionTicket(), killProcess = false))
        val opened = manager.onAppRelaunched()
        input.main.drainUi(); Dispatchers.IO.drain()
        check(stale.isClosed && manager.network() == null && manager.isAppRunOwner(opened))
        check(input.main.overlays == 0)
        input.scan.startScan()
        input.scan.onServiceFound("192.0.2.156", 5277, null, checkNotNull(input.scan.networkDiscovery.operation))
        input.main.drainUi(); Dispatchers.IO.drain()
        check(manager.state is ConnectionState.Connected && input.main.overlays == 1)
    } finally { stale.close(); input.close(); manager.close() }

    // A discovery socket queued in a cancelled view scope is still owned by its producer.
    Dispatchers.IO = QueuedDispatcher()
    val cancelledManager = CommManager()
    val cancelled = Input(cancelledManager)
    try {
        cancelled.accept("scan-socket")
        cancelled.job.cancel(); Dispatchers.IO.drain()
        check(checkNotNull(cancelled.probe).isClosed)
    } finally { cancelled.close(); cancelledManager.close() }

    // Fresh explicit input after Stop needs no Open prerequisite, for every public producer.
    for (route in listOf("main", "list", "automation", "scan-ip", "scan-socket")) {
        Dispatchers.IO = QueuedDispatcher()
        val freshManager = CommManager()
        freshManager.disconnect(ConnectionOperation(freshManager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        freshManager.cleanups(); freshManager.inlineConnectionIo()
        val fresh = Input(freshManager)
        try {
            checkNotNull(freshManager.requestExplicitAppExit(ticket = freshManager.appConnectionTicket(), killProcess = false))
            fresh.accept(route)
            Dispatchers.IO.drain()
            check(freshManager.state is ConnectionState.Connected)
        } finally { fresh.close(); freshManager.close() }
    }

    // A callback already posted to Main keeps its original scan object. A new manual scan
    // in the same Stop epoch must keep its own dialog and must not connect the old candidate.
    run {
        Dispatchers.IO = QueuedDispatcher()
        val manager = CommManager()
        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        manager.cleanups()
        val input = Input(manager)
        val socket = ProbeSocket()
        try {
            input.scan.startScan()
            val oldScan = checkNotNull(input.scan.networkDiscovery.operation)
            HeldServerSocket.hold("192.0.2.159:5277", socket)
            input.scan.onServiceFound("192.0.2.159", 5277, socket, oldScan)
            input.scan.cancelScan()
            input.scan.startScan()
            val replacementDialog = input.scan.scanDialog
            val replacementScan = input.scan.networkDiscovery.operation
            input.scan.startScan() // An active scan folds the request without losing its owner.
            check(input.scan.networkDiscovery.operation === replacementScan && input.scan.scanDialog === replacementDialog)
            input.main.drainUi()
            check(input.scan.scanDialog === replacementDialog && replacementDialog?.isShowing == true)
            check(socket.isClosed && Dispatchers.IO.tasks.isEmpty() && input.main.overlays == 0)
        } finally { input.close(); manager.close(); socket.close() }
    }
    println("PASS manual scan callback keeps exact operation identity across same-ticket replacement")

    // Rejected discovery candidates close their probe without revoking the accepted Stop.
    Dispatchers.IO = QueuedDispatcher()
    ContextCompat.starts.clear()
    val rejectedManager = CommManager()
    val rejected = Input(rejectedManager)
    try {
        rejected.scan.startScan()
        val operation = checkNotNull(rejected.scan.networkDiscovery.operation)
        val stop = checkNotNull(rejectedManager.requestExplicitAppExit(ticket = rejectedManager.appConnectionTicket(), killProcess = false))
        for (port in listOf(5289, 5277)) {
            val socket = ProbeSocket()
            if (port == 5277) HeldServerSocket.hold("192.0.2.157:5277", socket)
            rejected.scan.onServiceFound("192.0.2.157", port, socket, operation)
            rejected.main.drainUi(); Dispatchers.IO.drain()
            check(socket.isClosed && rejectedManager.ownsAppExit(stop.id))
        }
        check(ContextCompat.starts.isEmpty() && rejected.main.overlays == 0)
    } finally { rejected.close(); rejectedManager.close() }

    // The command's first service effect can return only after a newer Stop/Open. Its second
    // effect still belongs to the original command, rather than capturing per-effect tickets.
    for (stop in listOf(false, true)) {
        Dispatchers.IO = QueuedDispatcher()
        val effectManager = CommManager()
        effectManager.disconnect(ConnectionOperation(effectManager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        effectManager.cleanups(); effectManager.inlineConnectionIo()
        val effectInput = Input(effectManager)
        try {
            ContextCompat.startHook = {
                if (stop) checkNotNull(effectManager.requestExplicitAppExit(ticket = effectManager.appConnectionTicket(), killProcess = false))
                effectManager.onAppRelaunched()
            }
            effectInput.accept("automation")
            Dispatchers.IO.drain()
            check((effectManager.state is ConnectionState.Connected) == !stop)
        } finally { ContextCompat.startHook = null; effectInput.close(); effectManager.close() }
    }

    // Stop relays keep their already accepted owner; they must never be recaptured on delivery.
    ContextCompat.starts.clear()
    val stoppedManager = CommManager()
    val context = Context(stoppedManager)
    try {
        val stop = checkNotNull(stoppedManager.requestExplicitAppExit(ticket = stoppedManager.appConnectionTicket(), killProcess = false))
        check(AutomationEffectRunner.run(context, listOf(AutomationCommandPolicy.Effect.StopApp(stop.id)), JSONObject()))
        val relay = ContextCompat.starts.single()
        stoppedManager.onAppRelaunched()
        val service = ServiceBootstrap(stoppedManager)
        service.onStartCommand(relay.platform(), 0, 1)
        check(!service.initializedForStart && service.stops == 1)
    } finally { stoppedManager.close() }

    // P2-B(b). The user's scan reports its held socket and its hand-off waits for IO while a
    // concurrent IP request to the same endpoint adopts that socket and connects, then completes
    // SSL. The scan hand-off does not own the socket: it ends without taking the session over or
    // closing either the connection or its raw socket. The control lets it run before SSL.
    for (afterSsl in listOf(true, false)) {
        Dispatchers.IO = QueuedDispatcher()
        ContextCompat.starts.clear()
        val scanManager = CommManager()
        scanManager.disconnect(ConnectionOperation(scanManager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        scanManager.cleanups(); scanManager.inlineConnectionIo()
        val scanInput = Input(scanManager)
        val shared = ProbeSocket()
        try {
            HeldServerSocket.hold("192.0.2.158:5277", shared)
            scanInput.scan.startScan()
            scanInput.scan.onServiceFound("192.0.2.158", 5277, shared, checkNotNull(scanInput.scan.networkDiscovery.operation))
            scanInput.main.drainUi()
            check(Dispatchers.IO.tasks.isNotEmpty()) { "the scan hand-off did not wait for IO" }
            scanManager.connect("192.0.2.158", 5277, ConnectionPriorityPolicy.Tier.USER, ticket = scanManager.appConnectionTicket())
            val live = checkNotNull(scanManager.network()) { "the IP request did not connect" }
            if (afterSsl) scanManager.startHandshake(checkNotNull(scanManager.captureHandshake()))
            val stateBefore = scanManager.state
            Dispatchers.IO.drain()
            // Recorded after every production frame has returned.
            val same = scanManager.network() === live
            println("scan hand-off of an adopted socket afterSsl=$afterSsl: same=$same ownerClosed=${live.closed} rawClosed=${shared.isClosed}")
            check(same && !live.closed && !shared.isClosed && scanManager.state === stateBefore) {
                "the scan hand-off took over a socket another request adopted (afterSsl=$afterSsl same=$same ownerClosed=${live.closed} rawClosed=${shared.isClosed})"
            }
        } finally { shared.close(); scanInput.close(); scanManager.close() }
    }
    // A Bluetooth arrival is relayed to MainActivity with the Stop ticket it was received under
    // (AutoStartReceiver, or the service's held arrival). Stop is accepted before the activity gets
    // it: no pill or Self Mode command. The control raises the pill and one direct command;
    // there is no home handoff to replay, and the real service prefix accepts its Self command.
    for (stop in listOf(true, false)) {
        Dispatchers.IO = QueuedDispatcher()
        ContextCompat.starts.clear()
        val manager = CommManager()
        manager.disconnect(ConnectionOperation(manager.appConnectionTicket(), null), honorKillOnDisconnect = false)
        manager.cleanups()
        val input = Input(manager)
        BtAutoStartRearmPolicy.selfMode = true
        HomeFragment.pendingDriverSelectionTicket = null
        try {
            val arrival = manager.appConnectionTicket()
            val relay = Intent("bluetooth-arrival")
                .putExtra(MainActivity.EXTRA_LAUNCH_SOURCE, MainActivity.LAUNCH_SOURCE_BLUETOOTH)
                .putExtra(MainActivity.EXTRA_CONNECTION_TICKET, arrival)
            if (stop) checkNotNull(manager.requestExplicitAppExit(ticket = manager.appConnectionTicket(), killProcess = false))
            input.main.handleLaunchIntent(relay)
            // Recorded after every production frame has returned.
            val selfStarts = ContextCompat.starts.filter { it.action == AapService.ACTION_START_SELF_MODE }
            if (stop) {
                check(input.main.overlays == 0 && selfStarts.isEmpty() && HomeFragment.pendingDriverSelectionTicket == null) {
                    "a Bluetooth arrival relayed before Stop still acted (pill=${input.main.overlays} selfStarts=${selfStarts.size})"
                }
            } else {
                check(input.main.overlays == 1 && selfStarts.size == 1 && HomeFragment.pendingDriverSelectionTicket == null) {
                    "the Bluetooth arrival did not start Self Mode"
                }
                val service = ServiceBootstrap(manager)
                service.onStartCommand(selfStarts.single().platform(), 0, 1)
                check(service.initializedForStart)
            }
        } finally {
            BtAutoStartRearmPolicy.selfMode = false; HomeFragment.pendingDriverSelectionTicket = null
            input.close(); manager.close()
        }
    }
    println("PASS Bluetooth arrival relayed to the home screen: Stop before delivery acts on nothing; control starts Self once")
    println("PASS UI connect producers: queued main/list/automation/scan inputs, live successor protection, compatible Open, fresh manual input, raw-socket cancellation, adopted scan socket and real relay rejection")
}
