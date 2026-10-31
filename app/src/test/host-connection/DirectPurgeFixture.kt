package directpurge

import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import com.andrerinas.openheadunit.connection.wifi.direct.P2pIdentityRotationPolicy
import com.andrerinas.openheadunit.connection.wifi.direct.P2pPersistentGroupPurge
import com.andrerinas.openheadunit.utils.AppLog

class Save(@Volatile var pending: Boolean = true)
class ConnectionOperation(val ticket: Long, val save: Save?)
class CommManager {
    @Volatile var ticket = 1L
    fun isCurrent(operation: ConnectionOperation) = operation.ticket == ticket && operation.save?.pending != false
}
class Settings(var wifiDirectStableIdentity: Boolean = true, var wifiDirectRotationPending: Boolean = true)
class Context(val settings: Settings)
object App { fun provide(context: Context) = context }

class Direct(val commManager: CommManager) {
    private val operation = ConnectionOperation(commManager.ticket, null)
    val commandSave = Save()
    val command = ConnectionOperation(commManager.ticket, commandSave)
    var generation = 0
    private var stopped = false
    var nativeGroupWasRead = true
    val platform = WifiP2pManager()
    private val manager: WifiP2pManager? = platform
    private val channel: WifiP2pManager.Channel? = WifiP2pManager.Channel()
    val handler = Handler()
    val context = Context(Settings())
    val localDeviceAddress: String? = null
    var lastPersistentPurgeVerdict: String? = null
    var retirementOwed = false
    var creates = 0
    var removals = 0
    var removeAnswer: (() -> Unit)? = null
    var delayRemove = false
    fun deferForGroupRemoval(operation: ConnectionOperation, continuation: () -> Unit) = false
    fun claimNativeCreateWindow(why: String, operation: ConnectionOperation) {}
    fun invalidateNativeGroupCredentials(why: String, operation: ConnectionOperation) {}
    fun endpointRetirementOwed() = retirementOwed
    fun markP2pRequest() {}
    fun getP2pErrorString(reason: Int) = reason.toString()
    fun standardCreateGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel, retries: Int, mode: String, operation: ConnectionOperation) { creates++ }
    fun delayedCreateQuietGroup(retries: Int, operation: ConnectionOperation) { creates++ }
    fun removeGroup(manager: WifiP2pManager, channel: WifiP2pManager.Channel, listener: WifiP2pManager.ActionListener) {
        removals++
        if (delayRemove) removeAnswer = { listener.onSuccess() } else listener.onSuccess()
    }
    // CURRENT
    // SUPERSEDED
    // RECREATE
    fun start() { recreateNativeGroup(true, command) }
    companion object { const val NATIVE_GROUP_MODE_STANDARD_FALLBACK = "standard fallback" }
}

fun main(args: Array<String>) {
    val results = mutableListOf<Pair<String, String>>()
    fun test(name: String, body: (CommManager, Direct) -> Unit) {
        Build.VERSION.SDK_INT = 28
        try {
            val comm = CommManager()
            val direct = Direct(comm)
            body(comm, direct)
            results.add(name to "PASS")
            println("PASS $name")
        } catch (failure: Throwable) {
            results.add(name to "FAIL: ${failure.message}")
            println("FAIL $name: ${failure.message}")
        }
    }
    test("Direct-purge-command-withdrawn-before-profiles") { _, direct ->
        direct.start()
        direct.commandSave.pending = false
        direct.platform.emit()
        check(direct.platform.deleted.isEmpty()) { "A withdrawn command must not delete stored profiles" }
        check(direct.creates == 0)
    }
    test("Direct-purge-Stop-before-profiles-without-manager-stop") { comm, direct ->
        direct.start()
        comm.ticket++
        direct.platform.emit()
        check(direct.platform.deleted.isEmpty()) { "A withdrawn manager arm must not delete stored profiles" }
        check(direct.creates == 0)
    }
    test("Direct-purge-command-withdrawn-during-delete") { _, direct ->
        direct.platform.onDelete = { _, listener -> direct.commandSave.pending = false; listener.onSuccess() }
        direct.start()
        direct.platform.emit()
        check(direct.platform.deleted == listOf(0)) { "A withdrawn command must retire the remaining delete batch: ${direct.platform.deleted}" }
        check(direct.creates == 0)
    }
    test("Direct-purge-cross-thread-Stop-during-delete") { comm, direct ->
        var returned = false
        direct.platform.onDelete = { _, listener ->
            val stop = Thread { comm.ticket++ }.apply { isDaemon = true; start() }
            stop.join(3000)
            returned = !stop.isAlive
            listener.onSuccess()
        }
        direct.start()
        direct.platform.emit()
        check(returned) { "Stop could not return while a platform deletion was in progress" }
        check(direct.platform.deleted == listOf(0)) { "Stop must retire the remaining delete batch: ${direct.platform.deleted}" }
        check(direct.creates == 0)
    }
    test("Direct-purge-valid-command-deletes-once-and-creates-once") { _, direct ->
        direct.start()
        direct.platform.emit()
        direct.platform.emit()
        direct.handler.timeout()
        check(direct.platform.deleted == listOf(0, 7))
        check(direct.creates == 1)
    }
    test("Direct-purge-command-withdrawn-before-remove-answer") { _, direct ->
        direct.delayRemove = true
        direct.start()
        direct.commandSave.pending = false
        checkNotNull(direct.removeAnswer).invoke()
        check(direct.platform.requests == 0 && direct.creates == 0)
    }
    test("Direct-purge-command-withdrawn-before-timeout") { _, direct ->
        direct.start()
        direct.commandSave.pending = false
        direct.handler.timeout()
        check(direct.creates == 0 && direct.platform.deleted.isEmpty())
    }
    test("Direct-purge-new-manager-generation-rejects-old-profiles") { _, direct ->
        direct.start()
        direct.generation++
        direct.platform.emit()
        check(direct.platform.deleted.isEmpty() && direct.creates == 0)
    }
    test("Direct-purge-advertised-endpoint-preserved-until-retirement") { _, direct ->
        direct.retirementOwed = true
        direct.start()
        check(direct.platform.requests == 0 && direct.creates == 1)
        check(direct.context.settings.wifiDirectRotationPending)
    }
    test("Direct-purge-named-create-does-not-delete-stored-profiles") { _, direct ->
        Build.VERSION.SDK_INT = 30
        direct.start()
        check(direct.platform.requests == 0 && direct.creates == 1)
    }
    val payload = results.joinToString(",\n", "[\n", "\n]\n") { (name, result) ->
        "  {\"case\":\"$name\",\"result\":\"${result.replace("\"", "'")}\"}"
    }
    java.io.File(args.single()).writeText(payload)
    val failed = results.count { it.second != "PASS" }
    println("RESULT ${results.size - failed}/${results.size} PASS, $failed FAIL")
    check(failed == 0) { "$failed actual Direct purge assertions failed" }
}
