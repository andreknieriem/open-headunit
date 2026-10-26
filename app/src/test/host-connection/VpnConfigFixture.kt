package vpnconfig
object Build { object VERSION { var SDK_INT = 16 }; object VERSION_CODES { const val LOLLIPOP = 21 } }
object AppLog { fun i(s: String) {}; fun w(s: String) {}; fun e(s: String, e: Exception) {} }
object PackageManager { class NameNotFoundException : Exception() }
class Descriptor { var closed = false; fun close() { closed = true } }
class Service {
    var vpnInterface: Descriptor? = null
    var establishedExcludingSelf: Boolean? = null
    val packageName = "ohu"
    var excluded = 0; var established = 0; var stops = 0; var denyExclusion = false
    fun stopSelf() { stops++ }
    inner class Builder {
        fun addAddress(a: String, n: Int) = this
        fun addRoute(a: String, n: Int) = this
        fun setSession(a: String) = this
        fun addDisallowedApplication(a: String) {
            if (denyExclusion) throw PackageManager.NameNotFoundException()
            excluded++
        }
        fun establish(): Descriptor { established++; return Descriptor() }
    }
    fun start(excludeSelf: Boolean) = startVpn(excludeSelf)
    // CONFIG
}
fun main() {
    for (sdk in listOf(16, 18, 19, 20, 21, 23, 26, 30, 31, 33, 34, 35, 36)) {
        Build.VERSION.SDK_INT = sdk
        val service = Service()
        service.start(false) // Legacy Self remains available on every supported version.
        val offline = service.vpnInterface!!
        service.start(true)
        if (sdk < 21) {
            check(service.vpnInterface === offline && !offline.closed)
            check(service.established == 1 && service.establishedExcludingSelf == false)
        } else {
            check(service.excluded == 1 && service.established == 2 && offline.closed)
            check(service.establishedExcludingSelf == true)
            service.start(true)
            check(service.established == 2)
            service.start(false)
            check(service.established == 3 && service.establishedExcludingSelf == false)
            val denied = Service().also { it.denyExclusion = true }
            denied.start(true)
            check(denied.established == 0 && denied.vpnInterface == null && denied.stops == 1)
        }
    }
    println("PASS VPN configuration: API 16-36, legacy Self, session exclusion, refusal and replacement")
}
