package hctowner

import kotlinx.coroutines.*
import java.util.concurrent.CopyOnWriteArrayList

class Notifications : CoroutineDispatcher() {
    val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
    fun drain() { while (true) (tasks.poll() ?: return).run() }
}
class AapService {
    var isStopping = false
    var wirelessPausedForSettings = false
    val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    var wirelessRearmRequest: Any? = null
    val wirelessRearmPendingForSettings: Boolean get() = wirelessRearmRequest != null
    val commManager = object : CancelRestart {}
    val wifiLauncherManager = RecordingLauncher()
    var releaseParked: () -> Unit = {}
    var captureParked: () -> (() -> Unit)? = { releaseParked }
    fun releaseParkedHct() = releaseParked()
    fun captureParkedHctRelease() = captureParked()
    val appliedSettings = mutableListOf<Boolean>()
    companion object {
        fun applyWirelessSettings(context: AapService, startWireless: Boolean, ticket: Long) {
            context.appliedSettings.add(startWireless)
            if (!startWireless) context.stopWirelessForCommand(fromSettings = true, ticket = ticket)
        }
    }
    // STOP_WIRELESS
}
interface CancelRestart {
    fun cancelPendingSettingsRestart() = Unit
    fun appConnectionTicket() = 0L
    fun acceptsAppConnectionTicket(ticket: Long) = ticket == 0L
    fun <T> withAppConnectionLifecycleLock(action: () -> T): T = action()
}
class ConnectionOperation(val ticket: Long, val save: Any?)
fun interface ConnectionAdmission { fun run(action: () -> Unit): Boolean }
object App { fun provide(context: AapService) = context }
class RecordingLauncher {
    var onStop: () -> Unit = {}
    fun stop() = onStop()
    fun detachForOperation(operation: ConnectionOperation): (() -> Unit)? = onStop
    fun stopForOperation(operation: ConnectionOperation): Boolean { stop(); return true }
}
class Settings {
    enum class ConnectionMode { USB, WIFI, SELF }
    var connectionModes = setOf(ConnectionMode.USB, ConnectionMode.WIFI)
    var wifiConnectionMode = WifiLauncherMode.NATIVE
    fun showsWifi() = connectionModes.isEmpty() || ConnectionMode.WIFI in connectionModes
}
enum class WifiLauncherMode { NATIVE, MANUAL }
object WirelessRearmPolicy { fun requiresRearm(before: Boolean, after: Boolean) = before != after }
object R {
    object style { const val DarkAlertDialog = 0 }
    object string { const val connection_kind_usb = 0; const val connection_kind_wifi = 1; const val self_mode = 2; const val connection_mode = 3; const val cancel = 4 }
}
object android { object R { object string { const val ok = 1 } } }
class MaterialAlertDialogBuilder(context: AapService, style: Int) {
    companion object { lateinit var latest: MaterialAlertDialogBuilder }
    init { latest = this }
    lateinit var toggle: (Any?, Int, Boolean) -> Unit
    lateinit var accept: (MaterialAlertDialogBuilder, Int) -> Unit
    fun setTitle(id: Int) = this
    fun setMultiChoiceItems(labels: Array<String>, checked: BooleanArray, action: (Any?, Int, Boolean) -> Unit) = apply { toggle = action }
    fun setPositiveButton(id: Int, action: (MaterialAlertDialogBuilder, Int) -> Unit) = apply { accept = action }
    fun setNegativeButton(id: Int, action: Any?) = this
    fun show() = Unit
    fun dismiss() = Unit
}
class SettingsScreen(val service: AapService) {
    val settings = Settings()
    fun requireContext() = service
    fun getString(id: Int) = id.toString()
    fun updateSettingsList() = Unit
    fun wirelessRearmConfig() = settings.showsWifi()
    // CONNECTION_DIALOG
}
object Wireless { data class WifiVersionRequest(val tag: Int) }
interface HandshakeLink
interface ExternalModuleCarrier { val sendsWake: Boolean; fun requestWake(userAsked: Boolean, admission: ConnectionAdmission); fun close() }
object AppLog { fun i(s: String) {}; fun w(s: String) {} }
object BluetoothPeerInventory { fun normalizeAddress(s: String): String? = s.takeIf { it.length == 17 } }
object HctWakePolicy { enum class Result { UNUSED } }
class HctAaCarrier(
    service: AapService, val openingRequest: () -> Wireless.WifiVersionRequest,
    val serve: suspend (HandshakeLink) -> Unit, val mayServe: () -> Boolean,
    val acceptPeer: (String) -> Boolean, val onPhoneEvidence: () -> Unit,
    val onUnavailable: (HctWakePolicy.Result?, () -> Boolean) -> Unit,
    val admission: ConnectionAdmission,
) {
    companion object { val all = CopyOnWriteArrayList<HctAaCarrier>() }
    val requested = mutableListOf<String>()
    val ended = CompletableDeferred<Unit>()
    init { all.add(this) }
    suspend fun run() { ended.await() }
    val replacementGrants = mutableListOf<Boolean>()
    fun requestWakeFor(mac: String, allowReplacePending: Boolean, admission: ConnectionAdmission, selected: () -> Unit): Boolean {
        if (!this.admission.run {} || !admission.run {}) return false
        requested.add(mac); replacementGrants.add(allowReplacePending); selected(); return true
    }
    fun close() { ended.complete(Unit) }
}
class HctRetainedChannel(link: HandshakeLink, scope: CoroutineScope) {
    companion object { lateinit var latest: HctRetainedChannel }
    init { latest = this }
    val peer = "11:22:33:44:55:66"
    var isOpen = true
    var isParked = false
    var canRetain = true
    val ended = CompletableDeferred<Unit>()
    fun lease(onReserved: () -> Unit = {}): HandshakeLink? {
        if (!isOpen) return null
        onReserved()
        isParked = false
        return object : HandshakeLink {}
    }
    suspend fun run() { ended.await() }
    fun park() { isParked = true }
    fun close() { isOpen = false; ended.complete(Unit) }
}
fun HandshakeLink.close() {}

fun main() = runBlocking {
    val service = AapService()
    val notifications = Notifications()
    val owner = HctWppOwner(service, notifications)
    var notices = 0
    var selected = 0
    var busy = false
    var prompt = false
    var authorityEpoch = 0
    val commandAdmission = ConnectionAdmission { action -> action(); true }
    val served = CopyOnWriteArrayList<Int>()
    fun attach(tag: Int): HctWppOwner.Client {
        val capturedEpoch = authorityEpoch
        return owner.attach({ Wireless.WifiVersionRequest(tag) },
            { served.add(tag); awaitCancellation() }, { true }, { true }, { notices++ }, { notices++ },
            admission = ConnectionAdmission { action -> if (capturedEpoch != authorityEpoch) false else { action(); true } },
            maySelect = { !busy })
    }
    val first = attach(1)
    // Deliberately do not run Client.run: Main selection can precede its IO coroutine.
    check(first.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission) { selected++ })
    val physical = HctAaCarrier.all.single()
    check(selected == 1 && physical.requested.size == 1)
    check(physical.replacementGrants == listOf(false))
    val runner = launch { first.run() }
    yield()
    check(HctAaCarrier.all.size == 1 && physical.requested.size == 1)
    val serving = launch { physical.serve(object : HandshakeLink {}) }
    withTimeout(2_000) { while (served.size < 1) delay(1) }
    owner.releaseParked() // Opening settings during active projection cannot disconnect it.
    check(HctRetainedChannel.latest.isOpen && !HctRetainedChannel.latest.isParked)
    owner.park()
    val firstParkedRelease = checkNotNull(owner.captureParkedRelease())
    first.close()
    check(HctRetainedChannel.latest.isOpen)
    val second = attach(2)
    firstParkedRelease()
    check(HctRetainedChannel.latest.isOpen && HctRetainedChannel.latest.isParked)
    val beforeReuseRelease = checkNotNull(owner.captureParkedRelease())
    check(physical.openingRequest().tag == 2) // No closure retaining the old launcher.
    busy = true
    check(!second.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission) { selected++ })
    check(selected == 1 && HctRetainedChannel.latest.isParked)
    busy = false
    check(second.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission) { selected++ })
    check(selected == 2)
    withTimeout(2_000) { while (served.size < 2) delay(1) }
    check(HctAaCarrier.all.size == 1 && physical.requested.size == 1)
    owner.park()
    beforeReuseRelease()
    check(HctRetainedChannel.latest.isOpen && HctRetainedChannel.latest.isParked)
    check(second.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission))
    first.close() // Retired client cannot close its replacement's physical owner.
    check(HctRetainedChannel.latest.isOpen)
    physical.onUnavailable(null) { true } // Queued for client 2; replacement must invalidate it.
    owner.close()
    val third = attach(3)
    check(third.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission, allowReplacePending = true))
    check(HctAaCarrier.all.last().replacementGrants == listOf(true))
    physical.onUnavailable(null) { true } // A retired worker resumes after the replacement was installed.
    physical.onPhoneEvidence()
    notifications.drain()
    check(notices == 0)
    var currentFailure = true
    HctAaCarrier.all.last().onUnavailable(null) { currentFailure }
    currentFailure = false
    notifications.drain()
    check(notices == 0) // Same client, but a new AA event superseded the queued failure.
    HctAaCarrier.all.last().onUnavailable(null) { true }
    notifications.drain()
    check(notices == 1)
    val replacementServing = launch { HctAaCarrier.all.last().serve(object : HandshakeLink {}) }
    withTimeout(2_000) { while (3 !in served) delay(1) }
    owner.park(); third.close()
    check(HctRetainedChannel.latest.isOpen && HctRetainedChannel.latest.isParked)
    // The launcher is already detached when settings/explicit Wi-Fi cancellation arrives.
    service.wirelessPausedForSettings = true
    owner.releaseParked()
    check(!HctRetainedChannel.latest.isOpen)
    owner.releaseParked(); third.close() // Idempotent, with no retained client to find.
    owner.close(); replacementServing.join(); serving.join(); runner.join()

    // Exercise the actual dialog callback, not a direct call to the owner cleanup helper.
    val fourth = attach(4)
    check(fourth.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission))
    val dialogServing = launch { HctAaCarrier.all.last().serve(object : HandshakeLink {}) }
    withTimeout(2_000) { while (4 !in served) delay(1) }
    service.wirelessPausedForSettings = false
    service.releaseParked = { owner.releaseParked() }
    service.captureParked = { owner.captureParkedRelease() }
    service.wifiLauncherManager.onStop = { fourth.close() }
    val screen = SettingsScreen(service)
    screen.showConnectionModeDialog() // Merely opening it preserves the live projection.
    check(HctRetainedChannel.latest.isOpen && service.appliedSettings.isEmpty())
    MaterialAlertDialogBuilder.latest.toggle(null, 1, false) // USB stays selected.
    MaterialAlertDialogBuilder.latest.accept(MaterialAlertDialogBuilder.latest, 0)
    check(service.appliedSettings == listOf(false))
    check(!HctRetainedChannel.latest.isOpen)
    owner.park(); fourth.close() // Later Exit cannot resurrect a channel closed by settings.
    check(!HctRetainedChannel.latest.isOpen)
    dialogServing.join()
    service.appliedSettings.clear()
    screen.showConnectionModeDialog()
    MaterialAlertDialogBuilder.latest.accept(MaterialAlertDialogBuilder.latest, 0)
    check(service.appliedSettings.isEmpty()) // An unchanged choice does not cancel a manual start.
    screen.showConnectionModeDialog()
    MaterialAlertDialogBuilder.latest.toggle(null, 0, false) // Empty means all modes, including Wi-Fi.
    MaterialAlertDialogBuilder.latest.accept(MaterialAlertDialogBuilder.latest, 0)
    check(service.appliedSettings == listOf(true))
    service.appliedSettings.clear()
    check(screen.settings.connectionModes.isEmpty())
    screen.showConnectionModeDialog()
    MaterialAlertDialogBuilder.latest.accept(MaterialAlertDialogBuilder.latest, 0)
    check(screen.settings.connectionModes.isEmpty() && service.appliedSettings.isEmpty())
    owner.close()
    val queued = attach(5)
    val beforeQueued = HctAaCarrier.all.size
    authorityEpoch++
    queued.run()
    check(!queued.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission))
    check(HctAaCarrier.all.size == beforeQueued)
    val current = attach(6)
    check(!current.requestWakeFor("11:22:33:44:55:66", admission = ConnectionAdmission { false }))
    check(HctAaCarrier.all.size == beforeQueued)
    check(current.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission))
    val currentPhysical = HctAaCarrier.all.last()
    queued.close()
    check(!currentPhysical.ended.isCompleted)
    val beforeNotice = notices
    currentPhysical.onPhoneEvidence()
    authorityEpoch++
    notifications.drain()
    check(notices == beforeNotice)
    val finalClient = attach(7)
    check(finalClient.requestWakeFor("11:22:33:44:55:66", admission = commandAdmission))
    check(HctAaCarrier.all.last() !== currentPhysical && currentPhysical.ended.isCompleted)
    owner.close(); service.serviceScope.cancel()
    println("PASS HCT owner: selection before run, single carrier, retained reuse, current opening callback, stale client close and old parked cleanup after same-channel reuse")
    println("PASS HCT original admission: queued run/wake and notice cannot inherit replacement authority; fresh attach owns a new carrier")
}
