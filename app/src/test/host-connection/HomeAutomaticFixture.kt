package homeautomatic

import android.content.Context as PlatformContext
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.app.AppForegroundPolicy
import com.andrerinas.openheadunit.connection.AppExitPolicy
import lifecycle.CommManager
import lifecycle.ConnectionState
import lifecycle.ServiceBootstrap
import lifecycle.SocketProjectionConnection
import lifecycle.UsbRecheck
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume

// The runner inserts complete production producers/helpers at the named markers. Android,
// lifecycle scheduling and UI effects below are boundaries; ownership/connection guards are real.
@OptIn(InternalCoroutinesApi::class)
class HomeClock : CoroutineDispatcher(), Delay {
    private data class Timer(val at: Long, val task: Runnable)
    private val tasks = ConcurrentLinkedQueue<Runnable>()
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
    fun drain() { while (true) (tasks.poll() ?: return).run() }
    fun advanceBy(ms: Long) {
        now += ms
        val due = timers.filter { it.at <= now }.sortedBy { it.at }
        timers.removeAll(due.toSet())
        due.forEach { it.task.run() }
        drain()
    }
    val waiting get() = timers.isNotEmpty()
}

class Settings {
    var autoStartOnScreenOn = false
    var autoStartOnBoot = false
    var autoStartSelfMode = false
    var autoConnectLastSession = false
    var autoConnectSingleUsbDevice = false
    var autoConnectDelaySeconds = 0
    var autoConnectPriorityOrder = listOf(AUTO_CONNECT_LAST_SESSION, AUTO_CONNECT_SELF_MODE, AUTO_CONNECT_SINGLE_USB)
    var hasAcceptedDisclaimer = true
    var wifiConnectionMode = WifiLauncherMode.AUTO
    var lastConnectionType = ""
    var lastConnectionIp = "192.0.2.122"
    var lastConnectionUsbDevice = "usb-fixture"
    companion object {
        const val AUTO_CONNECT_LAST_SESSION = "last"
        const val AUTO_CONNECT_SELF_MODE = "self"
        const val AUTO_CONNECT_SINGLE_USB = "usb"
        const val CONNECTION_TYPE_WIFI = "wifi"
        const val CONNECTION_TYPE_USB = "usb"
        const val CONNECTION_TYPE_NEARBY = "nearby"
    }
}
enum class WifiLauncherMode { AUTO, NATIVE, OTHER }
enum class SelfLaunchPath { LEGACY, HEADUNIT_SERVER }
object SelfLaunchRoutePolicy {
    // SELF_ROUTE_POLICY
}
object AutoConnectAttemptPolicy {
    // SINGLE_USB_OVERLAY_POLICY
}
object HomePrimaryPolicy { enum class Connection { WIFI, USB, SELF } }
object HomeUiHelper {
    fun lastConnection(settings: Settings) = when (settings.lastConnectionType) {
        Settings.CONNECTION_TYPE_WIFI -> HomePrimaryPolicy.Connection.WIFI
        Settings.CONNECTION_TYPE_USB -> HomePrimaryPolicy.Connection.USB
        else -> null
    }
}
class ConnectivityManager { var activeNetwork: Any? = Any() }
class Context(val commManager: CommManager, val settings: Settings) {
    val android = PlatformContext()
    val connectivity = ConnectivityManager()
    var path = SelfLaunchPath.HEADUNIT_SERVER
    var vpnConsent: Intent? = null
    fun getSystemService(name: String): Any = connectivity
    companion object { const val CONNECTIVITY_SERVICE = "connectivity" }
}
fun Intent(context: Context, target: Class<*>) = Intent(context.android, target)
object App { fun provide(context: Context) = context }
object ContextCompat {
    val serviceStarts get() = androidx.core.content.ContextCompat.serviceStarts
    fun startForegroundService(context: Context, intent: Intent) =
        androidx.core.content.ContextCompat.startForegroundService(context.android, intent)
}
class AapService {
    companion object {
        const val ACTION_CHECK_USB = "check-usb"
        const val ACTION_CONNECT_SOCKET = "connect-socket"
        const val ACTION_START_SELF_MODE = "start-self"
        const val EXTRA_SELF_MODE_VPN = "self-vpn"
        // Extract EXTRA_OPERATION_TICKET and its builder; automatic valid-Run admission is
        // exercised in the real Home producer, while the service consumes the unchanged epoch.
        // OPERATION_CONSTANT
        // OPERATION_INTENT
        // A USB relay carries the scan its input minted; the service consumes its ticket.
        fun usbCheckIntent(context: Context, recheck: UsbRecheck, action: String? = ACTION_CHECK_USB,
            userRequested: Boolean = false): Intent = operationIntent(context, recheck.ticket).setAction(action)
            .putExtra("relayed_usb_recheck", "${recheck.ticket}.${recheck.epoch}")
    }
}
object UsbDeviceCompat {
    fun usbManager(context: Context): UsbManager? = context.android.usb
    fun getUniqueName(device: UsbDevice) = "usb-fixture"
    fun isConnectable(context: Context, device: UsbDevice) = true
}
object SelfLauncherManager { fun installedPath(context: Context) = context.path }
object VpnControl {
    fun isVpnAvailable() = true
    fun consentIntent(context: Context) = context.vpnConsent
}
object Build { object VERSION { const val SDK_INT = 23 }; object VERSION_CODES { const val M = 23 } }
object AppLog { fun i(message: String) {}; fun w(message: String) {} }
object Toast { const val LENGTH_SHORT = 0; const val LENGTH_LONG = 1 }
object ToastUtils { fun showToast(context: Context, text: Any, length: Int, force: Boolean = false) {} }
object R { object string { const val auto_connecting_to = 1; const val auto_connecting_usb = 2; const val failed_start_android_auto = 3 } }
object Lifecycle {
    enum class State { STARTED; fun isAtLeast(other: State) = true }
}
class ViewOwner(val lifecycleScope: CoroutineScope) {
    val lifecycle = objectLifecycle()
    class objectLifecycle { val currentState = Lifecycle.State.STARTED }
}
class MainActivity {
    enum class ConnectionUiMode { OVERLAY }
    val overlays = mutableListOf<String>()
    fun beginAutoConnect(reason: String, mode: ConnectionUiMode) { overlays.add(reason) }
}
class Bundle {
    private val values = mutableMapOf<String, Any>()
    fun putInt(key: String, value: Int) { values[key] = value }
    fun getInt(key: String, default: Int) = values[key] as? Int ?: default
    fun putBoolean(key: String, value: Boolean) { values[key] = value }
    fun getBoolean(key: String, default: Boolean) = values[key] as? Boolean ?: default
    fun putLong(key: String, value: Long) { values[key] = value }
    fun getLong(key: String) = values[key] as Long
    fun containsKey(key: String) = values.containsKey(key)
}
open class Fragment {
    open fun onCreate(savedInstanceState: Bundle?) {}
    open fun onSaveInstanceState(outState: Bundle) {}
}
object ActivityResultContracts { class StartActivityForResult }
class ActivityResult(val resultCode: Int)
class PermissionLauncher(private val callback: (ActivityResult) -> Unit) {
    var launches = 0
    fun launch(intent: Intent) { launches++ }
    fun deliver() { callback(ActivityResult(RESULT_OK)) }
}
const val RESULT_OK = -1

class HomeAutomaticProducer(
    val commManager: CommManager,
    settings: Settings,
    val clock: HomeClock,
    val ioDispatcher: HomeClock,
    val context: Context = Context(commManager, settings)
) : Fragment() {
    private val scope = CoroutineScope(SupervisorJob() + clock)
    val lifecycleScope get() = scope
    val viewLifecycleOwner = ViewOwner(scope)
    val activity = MainActivity()
    var view: Any? = Any()
    var isAdded = true
    private var reconnectCountdown: Job? = null
    private var reconnectTarget: HomePrimaryPolicy.Connection? = null
    private var reconnectSecondsLeft = 0
    private var autoConnectCancelled = false
    private var hasAttemptedAutoConnect = false
    private var hasAttemptedSingleUsbAutoConnect = false
    private var hasAutoStarted = false
    private var forceSelfModeTicket: Long? = null
    private var pendingWidgetId = -1
    private var pendingWidgetPage: Int? = null
    private var pendingSelfModeVpn = false
    private var pendingSelfModeTicket: Long? = null
    private val STATE_PENDING_WIDGET_ID = "home_pending_widget_id"
    private val STATE_PENDING_WIDGET_PAGE = "home_pending_widget_page"
    private val STATE_PENDING_SELF_VPN = "home_pending_self_vpn"
    private val STATE_PENDING_SELF_TICKET = "home_pending_self_ticket"
    fun requireContext() = context
    fun requireActivity(): Any = activity
    fun getString(id: Int, vararg args: Any) = "resource-$id"
    fun renderHome() {}
    fun registerForActivityResult(contract: ActivityResultContracts.StartActivityForResult,
                                  callback: (ActivityResult) -> Unit) = PermissionLauncher(callback)
    // HOME_VPN_CALLBACK
    // HOME_CREATE_STATE
    // HOME_SAVE_STATE
    fun created() {
        // HOME_AUTO_BLOCKS
    }
    // HOME_COUNTDOWN
    // HOME_AUTO_TARGET
    // HOME_LAST_SESSION
    // HOME_SINGLE_USB
    // HOME_SELF_START
    // HOME_SELF_INTERNAL
    fun startManualSelf() { startSelfMode() }
    fun consentGranted() { vpnPermissionLauncher.deliver() }
    fun permissionRequests() = vpnPermissionLauncher.launches
    fun close() { scope.cancel() }
}

private class HomeCase(configure: Settings.() -> Unit = {}) {
    val manager = CommManager()
    val settings = Settings().apply(configure)
    val clock = HomeClock()
    val io = HomeClock()
    val home = HomeAutomaticProducer(manager, settings, clock, io)
    var physicalOpens = 0
    init {
        manager.disconnect(honorKillOnDisconnect = false)
        manager.cleanups()
        ContextCompat.serviceStarts.clear()
        manager.onAppRelaunched()
        SocketProjectionConnection.connectionHook = { physicalOpens++; true }
    }
    fun completeStop(): AppExitPolicy.Request {
        val stop = manager.requestExplicitAppExit(killProcess = false)
        check(manager.commitAppExit(stop) === stop)
        check(manager.completeAppExit(stop).effect == AppExitPolicy.Effect.STOPPED)
        return stop
    }
    fun interrupt(stop: Boolean): Long {
        if (stop) manager.requestExplicitAppExit(killProcess = false)
        return manager.onAppRelaunched()
    }
    fun consume(): Int = ContextCompat.serviceStarts.toList().count { intent ->
        val service = ServiceBootstrap(manager)
        service.onStartCommand(intent, 0, 1)
        service.initializedForStart
    }
    fun close() {
        home.close()
        SocketProjectionConnection.connectionHook = null
        manager.close()
        ContextCompat.serviceStarts.clear()
    }
}

/** Both onViewCreated blocks and actual helpers, through real manager and service consumers. */
fun homeAutomaticOwnership() {
    val previousService = com.andrerinas.openheadunit.aap.AapService.instance
    val previousStarts = ContextCompat.serviceStarts.toList()
    com.andrerinas.openheadunit.aap.AapService.instance = null
    try {
        // Exact F01-R1: retained logical Activity accepts no Open; even USB=0 used to start service.
        for (screenOn in listOf(false, true)) {
            val c = HomeCase {
                autoStartOnScreenOn = screenOn
                autoStartOnBoot = !screenOn
                autoConnectSingleUsbDevice = true
                hasAcceptedDisclaimer = true
                autoConnectDelaySeconds = 0
            }
            try {
                val ui = AppForegroundPolicy()
                val main = AppForegroundPolicy.LogicalActivity()
                ui.created(main, AppForegroundPolicy.Kind.OPEN)
                ui.foregroundStarted(c.manager.appOwnershipRevision(), entryActivity = true)
                val stop = c.completeStop()
                ui.fullyStopped(changingConfigurations = true)
                check(ui.created(main, AppForegroundPolicy.Kind.OPEN) == null)
                c.home.created()
                c.clock.drain(); c.io.drain()
                val initialized = c.consume()
                check(ContextCompat.serviceStarts.isEmpty() && initialized == 0) {
                    "completed Stop/config originated automatic service work with USB=0: " +
                        "starts=${ContextCompat.serviceStarts.size}, initialized=$initialized"
                }
                check(c.physicalOpens == 0 && c.manager.ownsAppExit(stop.id))
            } finally { c.close() }
        }

        // Countdown belongs to the original logical operation, for every automatic producer.
        for (route in listOf("single-usb", "last-usb", "last-ip", "self")) {
            for (stop in listOf(false, true)) {
                val c = HomeCase {
                    autoConnectDelaySeconds = 1
                    when (route) {
                        "single-usb" -> autoConnectSingleUsbDevice = true
                        "self" -> autoStartSelfMode = true
                        else -> {
                            autoConnectLastSession = true
                            lastConnectionType = if (route == "last-ip") Settings.CONNECTION_TYPE_WIFI else Settings.CONNECTION_TYPE_USB
                        }
                    }
                }
                try {
                    c.home.context.android.usb.deviceList["phone"] = UsbDevice()
                    c.home.created(); c.clock.drain()
                    check(c.clock.waiting && ContextCompat.serviceStarts.isEmpty())
                    val opened = c.interrupt(stop)
                    c.clock.advanceBy(1000); c.io.drain()
                    if (stop) {
                        check(ContextCompat.serviceStarts.isEmpty() && c.physicalOpens == 0)
                        check(c.home.activity.overlays.isEmpty() && c.manager.isAppRunOwner(opened))
                    } else {
                        check(ContextCompat.serviceStarts.size == 1 && c.consume() == 1) { "compatible Open lost $route" }
                        check(c.home.activity.overlays.size == 1)
                        if (route == "last-ip") check(c.physicalOpens == 1 && c.manager.isConnected)
                    }
                } finally { c.close() }
            }
        }

        // Delay the helper's real IO entry *before* public connect can capture anything. Also
        // hold the automatic Intent until B/C, exercising receipt-time service validation.
        for (stop in listOf(false, true)) {
            val c = HomeCase {
                autoConnectLastSession = true
                lastConnectionType = Settings.CONNECTION_TYPE_WIFI
            }
            try {
                c.home.created(); c.clock.drain()
                check(ContextCompat.serviceStarts.size == 1 && c.physicalOpens == 0)
                val opened = c.interrupt(stop)
                c.io.drain()
                check(c.consume() == if (stop) 0 else 1)
                check(c.physicalOpens == if (stop) 0 else 1)
                if (stop) check(c.manager.isAppRunOwner(opened) && c.manager.state is ConnectionState.Disconnected)
            } finally { c.close() }
        }

        // USB and Self service submissions are checked again at actual bootstrap receipt.
        for (route in listOf("single-usb", "last-usb", "self")) {
            for (stop in listOf(false, true)) {
                val c = HomeCase {
                    when (route) {
                        "single-usb" -> autoConnectSingleUsbDevice = true
                        "self" -> autoStartSelfMode = true
                        else -> {
                            autoConnectLastSession = true
                            lastConnectionType = Settings.CONNECTION_TYPE_USB
                        }
                    }
                }
                try {
                    c.home.context.android.usb.deviceList["phone"] = UsbDevice()
                    c.home.created(); c.clock.drain()
                    check(ContextCompat.serviceStarts.size == 1)
                    val opened = c.interrupt(stop)
                    check(c.consume() == if (stop) 0 else 1)
                    if (stop) check(c.manager.isAppRunOwner(opened) && c.physicalOpens == 0)
                } finally { c.close() }
            }
        }

        // First block's owned restoration relay must also lose to Stop but survive a fresh Open
        // submitted by a genuinely new Home after that Stop.
        run {
            val c = HomeCase { autoStartOnScreenOn = true }
            try {
                c.home.created(); c.clock.drain()
                check(ContextCompat.serviceStarts.size == 1)
                c.interrupt(stop = true)
                check(c.consume() == 0)
                ContextCompat.serviceStarts.clear()
                val fresh = HomeAutomaticProducer(c.manager, c.settings, c.clock, c.io)
                try { fresh.created(); c.clock.drain(); check(c.consume() == 1) }
                finally { fresh.close() }
            } finally { c.close() }
        }

        // Automatic Self's extra asynchronous entry is the VPN consent callback, including a
        // Fragment recreation while the system dialog is up. Unknown callbacks cannot be fresh.
        for (stop in listOf(false, true)) {
            val c = HomeCase { autoStartSelfMode = true }
            try {
                c.home.context.path = SelfLaunchPath.LEGACY
                c.home.context.connectivity.activeNetwork = null
                c.home.context.vpnConsent = Intent("vpn-consent")
                c.home.created(); c.clock.drain()
                check(c.home.permissionRequests() == 1 && ContextCompat.serviceStarts.isEmpty())
                val state = Bundle()
                c.home.onSaveInstanceState(state)
                val replacement = HomeAutomaticProducer(c.manager, c.settings, c.clock, c.io, c.home.context)
                try {
                    replacement.onCreate(state)
                    val opened = c.interrupt(stop)
                    replacement.consentGranted()
                    check(c.consume() == if (stop) 0 else 1)
                    if (stop) check(ContextCompat.serviceStarts.isEmpty() && c.manager.isAppRunOwner(opened))
                } finally { replacement.close() }
            } finally { c.close() }
        }

        // Granting an earlier manual VPN dialog is a continuation, not a new Self input.
        for (stop in listOf(false, true)) {
            val c = HomeCase()
            try {
                c.home.context.path = SelfLaunchPath.LEGACY
                c.home.context.connectivity.activeNetwork = null
                c.home.context.vpnConsent = Intent("vpn-consent")
                c.home.startManualSelf()
                check(c.home.permissionRequests() == 1)
                val opened = c.interrupt(stop)
                c.home.consentGranted()
                check(c.consume() == if (stop) 0 else 1) {
                    "old manual VPN consent revived Self after Stop"
                }
                if (stop) check(ContextCompat.serviceStarts.isEmpty() && c.manager.isAppRunOwner(opened))
            } finally { c.close() }
        }

        // Fresh manual input retains its original semantics, not the cancelled automatic ticket.
        run {
            val c = HomeCase()
            try {
                c.home.consentGranted()
                check(ContextCompat.serviceStarts.isEmpty())
                c.completeStop()
                c.home.startManualSelf()
                check(ContextCompat.serviceStarts.single().action == AapService.ACTION_START_SELF_MODE)
                check(c.consume() == 1)
            } finally { c.close() }
        }
        println("PASS actual Home automatic producers/helpers: completed Stop config USB=0; countdown and IP/VPN relays retain Stop boundary; compatible Open and fresh inputs remain valid")
    } finally {
        com.andrerinas.openheadunit.aap.AapService.instance = previousService
        ContextCompat.serviceStarts.clear()
        ContextCompat.serviceStarts.addAll(previousStarts)
    }
}
