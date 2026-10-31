package uiconnect

import android.content.Context as PlatformContext
import android.content.Intent as PlatformIntent
import lifecycle.CommManager
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
    fun setAction(action: String) = apply { this.action = action }
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
        const val ACTION_CONNECT_SOCKET = "connect-socket"
        const val ACTION_CHECK_USB = "check-usb"
        const val ACTION_DISCONNECT = "disconnect"
        const val ACTION_STOP_SERVICE = ServiceBootstrap.ACTION_STOP_SERVICE
        const val ACTION_REQUEST_NIGHT_MODE_UPDATE = "night-update"
        const val EXTRA_USER_REQUESTED = "user-requested"
        // UI_SERVICE_CONSTANTS
        // UI_OPERATION_INTENT
        // UI_ACCEPTED_STOP_INTENT
        // UI_STOP_INTENT
    }
}
object HomeFragment { var forceSelfModeLaunch = false; var requestDriverSelection = false }
object BtAutoStartRearmPolicy {
    fun launchesSelfMode(selfSelected: Boolean, wirelessSelected: Boolean, mode: Any,
        sessionUp: Boolean, nativeAttemptInFlight: Boolean?) = false
}
class MainActivity(manager: CommManager, val lifecycleScope: CoroutineScope) : Context(manager) {
    enum class ConnectionUiMode { OVERLAY, PILL }
    val events = ConcurrentLinkedQueue<() -> Unit>()
    var overlays = 0
    fun beginAutoConnect(source: String, mode: ConnectionUiMode) { overlays++ }
    fun runOnUiThread(block: () -> Unit) { events.add(block) }
    fun drainUi() { while (true) (events.poll() ?: return).invoke() }
    fun finishAffinity() {}
    companion object {
        const val EXTRA_SHOW_DRIVER_SELECTOR = "show-selector"
        const val EXTRA_LAUNCH_SOURCE = "launch-source"
        const val LAUNCH_SOURCE_BLUETOOTH = "bluetooth"
    }
    // UI_FORCE_SELF_MODE
    // UI_HANDLE_LAUNCH_INTENT
}
object android { object R { object id { const val button2 = 2 } } }
object R {
    object integer { const val key_data = 1 }
    object string { const val found_connecting = 1 }
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
    var ticket = -1L
    fun startScan(oneShot: Boolean, ticket: Long = manager.appConnectionTicket()) { this.ticket = ticket }
}
class ScanFragment(val activity: MainActivity, val lifecycleScope: CoroutineScope) {
    val context: Context? get() = activity
    val adapter = AddressAdapter(activity, lifecycleScope)
    val networkDiscovery = Discovery(activity.commManager)
    var scanDialog: Dialog? = null
    fun showScanDialog() { scanDialog = Dialog() }
    fun requireContext(): Context = activity
    fun getString(id: Int, vararg args: Any): String = "$id"
    // UI_START_SCAN
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
        manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
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
                scan.onServiceFound("192.0.2.154", 5277, probe, scan.networkDiscovery.ticket)
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
            manager.disconnect(honorKillOnDisconnect = false)
            manager.cleanups()
            manager.inlineConnectionIo()
            val claims = Claims(manager)
            ConnectionArbiter.actions = claims
            val input = Input(manager)
            val liveSocket = Socket()
            try {
                input.accept(route)
                check(Dispatchers.IO.tasks.isNotEmpty()) { "$route did not pause before public connect" }
                if (stop) manager.requestExplicitAppExit(killProcess = false)
                val opened = manager.onAppRelaunched()
                if (successor) manager.connect(liveSocket, ConnectionPriorityPolicy.Tier.WIRELESS_HANDSHAKE)
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
    manager.disconnect(honorKillOnDisconnect = false); manager.cleanups(); manager.inlineConnectionIo()
    val input = Input(manager)
    val stale = ProbeSocket()
    try {
        input.scan.startScan()
        val oldTicket = input.scan.networkDiscovery.ticket
        HeldServerSocket.hold("192.0.2.155:5277", stale)
        input.scan.onServiceFound("192.0.2.155", 5277, stale, oldTicket)
        manager.requestExplicitAppExit(killProcess = false)
        val opened = manager.onAppRelaunched()
        input.main.drainUi(); Dispatchers.IO.drain()
        check(stale.isClosed && manager.network() == null && manager.isAppRunOwner(opened))
        check(input.main.overlays == 0)
        input.scan.startScan()
        input.scan.onServiceFound("192.0.2.156", 5277, null, input.scan.networkDiscovery.ticket)
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
        freshManager.disconnect(honorKillOnDisconnect = false)
        freshManager.cleanups(); freshManager.inlineConnectionIo()
        val fresh = Input(freshManager)
        try {
            freshManager.requestExplicitAppExit(killProcess = false)
            fresh.accept(route)
            Dispatchers.IO.drain()
            check(freshManager.state is ConnectionState.Connected)
        } finally { fresh.close(); freshManager.close() }
    }

    // Rejected discovery candidates close their probe without revoking the accepted Stop.
    Dispatchers.IO = QueuedDispatcher()
    ContextCompat.starts.clear()
    val rejectedManager = CommManager()
    val rejected = Input(rejectedManager)
    try {
        val stop = rejectedManager.requestExplicitAppExit(killProcess = false)
        val ticket = rejectedManager.appConnectionTicket()
        for (port in listOf(5289, 5277)) {
            val socket = ProbeSocket()
            if (port == 5277) HeldServerSocket.hold("192.0.2.157:5277", socket)
            rejected.scan.onServiceFound("192.0.2.157", port, socket, ticket)
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
        effectManager.disconnect(honorKillOnDisconnect = false)
        effectManager.cleanups(); effectManager.inlineConnectionIo()
        val effectInput = Input(effectManager)
        try {
            ContextCompat.startHook = {
                if (stop) effectManager.requestExplicitAppExit(killProcess = false)
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
        val stop = stoppedManager.requestExplicitAppExit(killProcess = false)
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
        scanManager.disconnect(honorKillOnDisconnect = false)
        scanManager.cleanups(); scanManager.inlineConnectionIo()
        val scanInput = Input(scanManager)
        val shared = ProbeSocket()
        try {
            HeldServerSocket.hold("192.0.2.158:5277", shared)
            scanInput.scan.startScan()
            scanInput.scan.onServiceFound("192.0.2.158", 5277, shared, scanInput.scan.networkDiscovery.ticket)
            scanInput.main.drainUi()
            check(Dispatchers.IO.tasks.isNotEmpty()) { "the scan hand-off did not wait for IO" }
            scanManager.connect("192.0.2.158", 5277, ConnectionPriorityPolicy.Tier.USER)
            val live = checkNotNull(scanManager.network()) { "the IP request did not connect" }
            if (afterSsl) scanManager.startHandshake()
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
    println("PASS UI connect producers: queued main/list/automation/scan inputs, live successor protection, compatible Open, fresh manual input, raw-socket cancellation, adopted scan socket and real relay rejection")
}
