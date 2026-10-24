package vpnstart

class Intent(context: Context, target: Class<*>) {
    private val extras = mutableMapOf<String, Any>()
    fun putExtra(name: String, value: Any) = apply { extras[name] = value }
    fun getStringExtra(name: String) = extras[name] as? String
    fun getBooleanExtra(name: String, default: Boolean) = extras[name] as? Boolean ?: default
}
class Context {
    val queued = mutableListOf<Intent>()
    fun startService(intent: Intent) { queued.add(intent) }
    // Android may keep the bound instance and already delivered start commands alive.
    fun stopService(intent: Intent) = true
}
object AppLog { fun i(text: String) {}; fun w(text: String) {} }
object VpnControl {
    internal val startRequests = DummyVpnStartRequests()
    // START
    // STOP
}
class DummyVpnService {
    private fun startVpn(excludeSelf: Boolean) { tunnel = true; establishes++ }
    // COMMAND
    companion object {
        const val EXTRA_EXCLUDE_SELF = "exclude_self"
        const val EXTRA_START_REQUEST = "vpn_start_request"
        const val START_NOT_STICKY = 2
        var tunnel = false
        var establishes = 0
        fun closeRunningTun(): Boolean = tunnel.also { tunnel = false }
    }
}
fun main() {
    val context = Context()
    val bound = DummyVpnService()
    VpnControl.startVpn(context, excludeSelf = true)
    bound.onStartCommand(context.queued.removeAt(0), 0, 1)
    check(DummyVpnService.tunnel)
    VpnControl.startVpn(context, excludeSelf = false)
    val delayed = context.queued.removeAt(0)
    VpnControl.stopVpn(context)
    bound.onStartCommand(delayed, 0, 2)
    check(!DummyVpnService.tunnel && DummyVpnService.establishes == 1)
    // An obsolete request also cannot replace a newer accepted configuration.
    VpnControl.startVpn(context, excludeSelf = false)
    bound.onStartCommand(context.queued.removeAt(0), 0, 3)
    bound.onStartCommand(delayed, 0, 4)
    check(DummyVpnService.tunnel && DummyVpnService.establishes == 2)
    VpnControl.stopVpn(context)
    bound.onStartCommand(null, 0, 5)
    check(!DummyVpnService.tunnel)
    println("PASS actual VPN command delivery: stop rejects delayed reconfiguration and stale commands cannot replace a fresh start")
}
