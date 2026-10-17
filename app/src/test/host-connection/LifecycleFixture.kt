package com.andrerinas.openheadunit.decoder.audio

import android.content.Context
import android.content.Intent
import android.app.Application
import android.media.AudioManager
import android.os.*
import com.andrerinas.openheadunit.aap.AapAudio
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private enum class DisconnectReason { CONNECTION_ENDED, SETTINGS_RESTART, PROJECTION_UNRAISED }
private sealed class ConnectionState {
    object Connecting : ConnectionState()
    object Connected : ConnectionState()
    object StartingTransport : ConnectionState()
    object HandshakeComplete : ConnectionState()
    object TransportStarted : ConnectionState()
    data class Error(val message: String) : ConnectionState()
    // PRODUCTION DISCONNECTED_STATE

}
private object DummyVpnPolicy { enum class Reason { SELF_MODE_SESSION_LIVE } }
private object HeadUnitScreenConfig { fun unlockResolution() {} }
private class QueuedCleanup : CoroutineDispatcher() {
    val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.add(block) }
}
private class AapTransport(
    decoder: AudioDecoder, video: Any, manager: AudioManager, settings: Settings, notification: Any,
    context: Context, externalSsl: Any, onAaMediaMetadata: (Any) -> Unit, onAaPlaybackStatus: (Any) -> Unit
) {
    val aapAudio = AapAudio(decoder, manager, settings)
    var onQuit: ((Boolean) -> Unit)? = null
    var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null
    var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    var wasUserExit = false
    init { constructionHook?.invoke() }
    companion object { var constructionHook: (() -> Unit)? = null }
}

/** Only the network, UI side effects and scheduling are doubled; injected decisions are production. */
private class ConnectionFixture(val settings: Settings = Settings()) {
    private val transportLifecycleLock = Any()
    private var usbRecoveryOwner: Any = Any()
    private var settingsUsbRestartInFlight: ConnectionState.Disconnected? = null
    private var usbSaveOwner: ConnectionState.Disconnected? = null
    private fun dropOwedScans() {}
    private var disconnectRequested = false
    private var physicalConnectionReached = true
    private var outgoingEndpoint: Pair<String, Int>? = null
    private val isLoopbackSession = false
    private val audioDecoder = AudioDecoder()
    private val videoDecoder = Unit
    private val _backgroundNotification = Unit
    private val aapSslContext = Unit
    val context = Context()
    private val queue = QueuedCleanup()
    private val _scope = CoroutineScope(SupervisorJob() + queue)
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Connected)
    private var _connection: Any? = Any()
    private var _transport: AapTransport? = null
    private var _disconnectJob: Job? = null
    private var onAaMediaMetadata: ((Any) -> Unit)? = null
    private var onAaPlaybackStatus: ((Any) -> Unit)? = null
    private var onAudioFocusStateChanged: ((Boolean) -> Unit)? = null
    private var onUpdateUiConfigReplyReceived: (() -> Unit)? = null
    val disconnected get() = _connectionState.value is ConnectionState.Disconnected
    val audio get() = checkNotNull(_transport).aapAudio
    fun quitCallback() = checkNotNull(_transport?.onQuit)
    fun publish() = runBlocking {
        withContext(Dispatchers.Default) {
            val conn = _connection ?: return@withContext
            // PRODUCTION PUBLICATION
        }
    }
    fun lateHandshake() = _transport?.let { source ->
        withLiveTransport(source, ConnectionState.StartingTransport) {
            _connectionState.value = ConnectionState.HandshakeComplete
        }
    }
    // PRODUCTION ADVANCE
    // PRODUCTION APPLY
    // PRODUCTION DISCONNECT
    // PRODUCTION CANCEL_SETTINGS
    // PRODUCTION REACHED_SSL
    // PRODUCTION QUIT
    private fun doDisconnect(sendByeBye: Boolean,
        byeByeReason: com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason =
            com.andrerinas.openheadunit.aap.protocol.proto.Control.ByeByeReason.USER_SELECTION,
        reason: DisconnectReason = DisconnectReason.CONNECTION_ENDED) {}
    fun nextConnection() {
        _transport?.aapAudio?.releaseAllFocus(); Handler.runAll()
        _transport = null; disconnectRequested = false; _connection = Any(); _connectionState.value = ConnectionState.Connected
    }
    fun close() { _transport?.aapAudio?.releaseAllFocus(); audioDecoder.stop(); Handler.runAll(); _scope.cancel() }
}

private object SessionStateIntent {
    const val STATE_CONNECTING=1; const val STATE_CONNECTED=2; const val STATE_PROJECTING=3; const val STATE_DISCONNECTED=4
    const val REASON_SETTINGS_RESTART=4; const val REASON_USER_EXIT=1; const val REASON_LINK_LOST=2; const val REASON_PHONE_LEFT=3
}
private object StationStandDown { fun onSessionLive(context: Any, held: Long?) {} }
private class ObserverFixture {
    private fun wifiLockHeldForMs(): Long? = null
    private class Connection {
        val connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val attemptUserRequested = false
    }
    private class Usb { var projectionHandshakeFailures=0; fun onHandshakeFailed() {} }
    private val commManager = Connection()
    private class Self { fun onConnectionEstablished() {}; fun onConnectionEnded(state: ConnectionState.Disconnected) {} }
    private val selfLauncherManager = Self()
    private class ReconnectTimer { fun onStateChanged() {} }
    private val automaticReconnect = ReconnectTimer()
    private val usbReconnect = ReconnectTimer()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val usbLauncherManager = Usb()
    private var hasEverConnected = false
    private var projectingSinceMs = 0L
    private var projectionRaisesThisSession = 0
    private var unprojectedEndsInARow = 0
    private var sessionEndedByHand = false
    private val packageName = "test"
    private val ACTION_REQUEST_NIGHT_MODE_UPDATE = "night"
    var resourcesHeld = false
    private fun onConnected() { resourcesHeld = true }
    private fun onDisconnected(state: ConnectionState.Disconnected) { resourcesHeld = false }
    private fun emitSessionState(state: Int, reason: Int = 0) {}
    private fun quiesceWirelessForWiredSession() {}
    private fun cancelProjectionRaiseDeadline() {}
    private fun armProjectionRaiseDeadline(value: Any) {}
    private fun launchAapProjectionActivity() = Unit
    private fun sendBroadcast(intent: Intent) {}
    private fun maybeAutoResumePlaybackOnReconnect() {}
    private fun stopDummyVpn(reason: DummyVpnPolicy.Reason) {}
    fun start() { observeConnectionState() }
    fun connected() { commManager.connectionState.value = ConnectionState.Connected }
    fun disconnected() { commManager.connectionState.value = ConnectionState.Disconnected() }
    fun close() { serviceScope.cancel() }
    // PRODUCTION OBSERVER
}

internal fun audioLifecycleBoundaryRegression() {
    Handler.reset()
    val observer = ObserverFixture()
    try {
        observer.start(); check(!observer.resourcesHeld)
        observer.connected(); check(observer.resourcesHeld)
        observer.disconnected(); check(!observer.resourcesHeld) { "first handshake resources survived disconnect" }
    } finally { observer.close() }
    println("PASS first Connected -> Disconnected cleans connection resources without a TransportStarted event")

    val fixture = ConnectionFixture()
    val captured = CountDownLatch(1); val publish = CountDownLatch(1)
    val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
    AapTransport.constructionHook = { captured.countDown(); check(publish.await(3, TimeUnit.SECONDS)) }
    val creator = Thread { try { fixture.publish() } catch (t: Throwable) { errors.add(t) } }
    val applying = Thread { try { fixture.applyAudioSettings() } catch (t: Throwable) { errors.add(t) } }
    try {
        creator.start(); check(captured.await(2, TimeUnit.SECONDS))
        fixture.settings.useAacAudio = true
        applying.start()
        val until = System.nanoTime() + 2_000_000_000L
        while (applying.state != Thread.State.BLOCKED && applying.isAlive && System.nanoTime() < until) Thread.sleep(1)
        check(applying.state == Thread.State.BLOCKED) { "settings apply escaped transport publication" }
        publish.countDown(); creator.join(3000); applying.join(3000)
        check(!creator.isAlive && !applying.isAlive && errors.isEmpty()) { errors.toString() }
        check(fixture.disconnected) { "saved AAC change was lost before publication" }
    } finally {
        publish.countDown(); creator.join(3000); applying.join(3000)
        AapTransport.constructionHook = null; fixture.close(); Handler.reset()
    }
    println("PASS settings save between snapshot capture and publication is serialized and requests renegotiation")

    val before = ConnectionFixture()
    try {
        before.settings.useAacAudio = true; before.applyAudioSettings(); before.publish()
        check(!before.disconnected && !before.audio.needsSessionRestart())
    } finally { before.close(); Handler.reset() }
    println("PASS settings saved before construction are captured by the next transport")

    val mixed = ConnectionFixture()
    try {
        mixed.publish()
        mixed.settings.usePcmGuidance = true
        mixed.applyAudioSettings()
        check(mixed.disconnected) { "PCM guidance save only restarted local output" }
        mixed.nextConnection(); mixed.publish()
        check(mixed.audio.sessionConfig.pcmGuidance && !mixed.audio.needsSessionRestart())
    } finally { mixed.close(); Handler.reset() }
    println("PASS PCM guidance save renegotiates the projection and the next session captures the choice")

    val highRate = ConnectionFixture()
    try {
        highRate.settings.usePcmGuidance = true
        highRate.publish()
        highRate.settings.prefer48kGuidance = true
        highRate.applyAudioSettings()
        check(highRate.disconnected) { "guidance rate change only restarted local output" }
        highRate.nextConnection(); highRate.publish()
        check(highRate.audio.sessionConfig.prefer48kGuidance && !highRate.audio.needsSessionRestart())
    } finally { highRate.close(); Handler.reset() }
    println("PASS guidance sample rate save renegotiates projection and is captured by the next session")

    for (honor in listOf(false, true)) {
        com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy = false
        val closing = ConnectionFixture(Settings().apply { killOnDisconnect = true })
        try {
            closing.publish(); val oldQuit = closing.quitCallback()
            closing.disconnect(isUserExit = false, honorKillOnDisconnect = honor)
            check(closing.lateHandshake() == false)
            oldQuit(false); oldQuit(false)
            check(closing.context.broadcasts.size == if (honor) 1 else 0)
            check(Handler.delayed.size == if (honor) 1 else 0)
            while (Handler.delayed.isNotEmpty()) Handler.delayed.poll().run()
            check(com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy == honor)
            closing.nextConnection(); closing.publish(); val fresh = closing.audio
            oldQuit(false)
            check(!closing.disconnected && closing.audio === fresh)
            check(closing.context.broadcasts.size == if (honor) 1 else 0)
        } finally { closing.close(); Handler.reset() }
    }
    val natural = ConnectionFixture(Settings().apply { killOnDisconnect = true })
    try {
        natural.publish(); val quit = natural.quitCallback(); quit(false); quit(false)
        check(natural.disconnected && natural.context.broadcasts.size == 1 && Handler.delayed.size == 1)
    } finally { natural.close(); Handler.reset() }
    com.andrerinas.openheadunit.aap.AapService.killProcessOnDestroy = false
    println("PASS first disconnect owns app-exit policy; duplicate and retired quit callbacks cannot override it")
}
