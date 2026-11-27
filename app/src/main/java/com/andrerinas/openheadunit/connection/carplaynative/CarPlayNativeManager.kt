package com.andrerinas.openheadunit.connection.carplaynative

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.carplaybox.BoxAudioSink
import com.andrerinas.openheadunit.connection.carplaybox.BoxVideoSink
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Action
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Event
import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Phase
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayServer
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayStorage
import com.andrerinas.openheadunit.connection.carplaynative.airplay.AirPlayTouchReport
import com.andrerinas.openheadunit.connection.carplaynative.airplay.Ipv6Text
import com.andrerinas.openheadunit.connection.carplaynative.airplay.PeerGate
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Control
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Identification
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Link
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Messages
import com.andrerinas.openheadunit.connection.carplaynative.iap2.Iap2Pump
import com.andrerinas.openheadunit.connection.carplaynative.usb.BlockingDuplexByteStream
import com.andrerinas.openheadunit.connection.carplaynative.usb.EthernetLink
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneModeAnswer
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbLink
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPipe
import com.andrerinas.openheadunit.connection.carplaynative.usb.IphoneUsbPolicy
import com.andrerinas.openheadunit.connection.carplaynative.usb.MuxConnector
import com.andrerinas.openheadunit.connection.carplaynative.usb.TunProbePacket
import com.andrerinas.openheadunit.connection.carplaynative.usb.UsbmuxHost
import com.andrerinas.openheadunit.utils.AppLog
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** An iPhone on the bus, as the session sees it. The Android adapter wraps a `UsbDevice`. */
interface IphoneDevice {
    /** The `/dev/bus/usb` path. It names the device in the arbiter and tells two instances apart. */
    val id: String
    val vendorId: Int
    val productId: Int
    val productName: String?

    /** The short name for the log. */
    val label: String
}

class NcmOpened(val link: EthernetLink, val hostMac: ByteArray?)

/** An iPhone with its CarPlay configuration set and its usbmux interface claimed. [close] undoes both. */
interface OpenedIphone : AutoCloseable {
    val pipe: IphoneUsbPipe
    val configurationId: Int

    /** The bulk OUT and IN addresses of usbmux. */
    val usbmuxEndpoints: Pair<Int, Int>

    /** The interface number of the CDC NCM control interface, for the iAP2 identification. */
    val ncmControlInterface: Int

    fun openNcm(): NcmOpened
}

/** Every USB call the session makes. The tests fake it, the Android adapter is `AndroidIphoneUsbPort`. */
interface IphoneUsbPort {
    fun hasPermission(device: IphoneDevice): Boolean

    /** True while the device is still on the bus. */
    fun isPresent(device: IphoneDevice): Boolean
    fun configurationCount(device: IphoneDevice): Int
    fun askPermission(device: IphoneDevice)
    fun requestMode(device: IphoneDevice): IphoneModeAnswer

    /** Null when the system will not tell. Read after the permission is held. */
    fun serial(device: IphoneDevice): String?

    @Throws(IOException::class)
    fun open(device: IphoneDevice): OpenedIphone
}

/** The carkit service stream, and whether it came from a record the iPhone had already accepted. */
class Carkit(val stream: BlockingDuplexByteStream, val usedSavedRecord: Boolean)

fun interface CarkitOpener {
    @Throws(IOException::class)
    fun open(connector: MuxConnector, serial: String, label: String, isCancelled: () -> Boolean): Carkit
}

/** The arbiter's CarPlay session flag. The tests record the order of the calls. */
interface BoxGate {
    fun begin(what: String, aaSessionLive: Boolean): Boolean
    fun end()
}

object ArbiterBoxGate : BoxGate {
    override fun begin(what: String, aaSessionLive: Boolean) = ConnectionArbiter.beginBoxSession(what, aaSessionLive)
    override fun end() = ConnectionArbiter.endBoxSession()
}

/** What the manager reads each time an iPhone attaches, so a setting changed mid-session counts at the next attach. */
class CarPlayNativeInputs(
    val enabled: Boolean,
    val sdk: Int,
    val tunAvailable: Boolean,
    /** The signer, or null when none is set up. */
    val signer: CarPlaySigner?,
    val aaSessionLive: Boolean,
)

/**
 * Runs native CarPlay sessions; [CarPlayNativeSession] holds every rule and deadline, this class the threads and sockets.
 * One session at a time: an attach during a close waits for it. One closer thread closes in order: AirPlay, the bridge,
 * NCM, iAP2 and carkit, usbmux, the usbmux link (it restores the iPhone's configuration), the tunnel, the sinks, the flag.
 */
class CarPlayNativeManager(
    private val usb: IphoneUsbPort,
    private val tunnel: TunnelControl,
    private val carkit: CarkitOpener,
    private val storage: AirPlayStorage,
    private val video: BoxVideoSink,
    private val audio: BoxAudioSink,
    private val gate: BoxGate,
    private val inputs: () -> CarPlayNativeInputs,
    private val product: CarPlayNativeProduct,
    private val displaySize: () -> Pair<Int, Int>,
    private val densityDpi: () -> Int,
    /** The tunnel address as a bindable address, or null when no interface holds it. Blocking. */
    private val bindAddress: () -> InetAddress?,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val tickMs: Long = 250L,
    /** Which hosts may open the AirPlay connection: the iPhone side of the NCM link. The tests open it to the loopback. */
    private val peerGate: (peer: InetAddress, local: InetAddress) -> Boolean = PeerGate::isNcmPeer,
) {
    // Guards the reducer state and every per-attempt field below. Never held while a session closes or a step runs.
    private val reducerLock = Any()
    private var state = CarPlayNativeSession.State()
    // A new attempt starts at each reset; an event, a tick or a phase write of an older attempt is dropped.
    private var attempt = 0L
    // True from the reducer's end of an attempt until its session has closed. An attach then waits in queuedAttach.
    private var closing = false
    private var queuedAttach: QueuedAttach? = null
    @Volatile private var device: IphoneDevice? = null
    @Volatile private var session: Session? = null
    @Volatile private var live = false
    private var ticker: Thread? = null
    private var tickerAttempt = -1L

    // One closer thread: closes run in order, never on the caller's thread, and the thread ends when idle.
    private val closer = ThreadPoolExecutor(0, 1, 1L, TimeUnit.SECONDS, LinkedBlockingQueue()) { task ->
        Thread(task, "carplay-native-close").apply { isDaemon = true }
    }

    private val phaseFlow = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> get() = phaseFlow
    private val endsFlow = MutableStateFlow(0)

    /** Goes up by one each time an attempt's close ends. Unlike [phase], two ends can never merge into one value. */
    val ends: StateFlow<Int> get() = endsFlow
    private val videoSizeFlow = MutableStateFlow<Pair<Int, Int>?>(null)

    /** The size of the picture. Touch is given in pixels of it. */
    val videoSize: StateFlow<Pair<Int, Int>?> get() = videoSizeFlow

    val isLive: Boolean get() = live

    // Guarded by reducerLock. Set by a user exit; a real unplug clears it, the phone's own re-enumeration does not.
    private var latch = CarPlayNativeSession.ExitLatch()

    /** True after a user exit, so a closed session does not reopen while the iPhone stays plugged in. */
    val userExited: Boolean get() = synchronized(reducerLock) { latch.held }

    /**
     * An iPhone attached, or its permission arrived. False for a device that is no iPhone and when the setting is off,
     * so the caller keeps today's handling of that device. True when this class took it, even to refuse it.
     */
    fun onAttach(device: IphoneDevice): Boolean {
        val i = inputs()
        val isIphone = IphoneUsbPolicy.isIphone(device.vendorId, device.productId, device.productName)
        val permitted = isIphone && i.enabled && usb.hasPermission(device)
        val exited = synchronized(reducerLock) {
            if (isIphone) latch = latch.onAttach(device.id, clock())
            latch.held
        }
        val verdict = IphoneUsbPolicy.attach(
            isIphone = isIphone, enabled = i.enabled, sdk = i.sdk, tunAvailable = i.tunAvailable, signerAvailable = i.signer != null,
            sessionLive = live, userExited = exited, aaSessionLive = i.aaSessionLive,
            hasPermission = permitted,
            configurationCount = if (isIphone && i.enabled) usb.configurationCount(device) else 0,
        )
        if (verdict == IphoneUsbPolicy.Attach.NotAnIphone || verdict == IphoneUsbPolicy.Attach.Disabled) return false
        val waits = synchronized(reducerLock) {
            if (closing) {
                queuedAttach = QueuedAttach(device, permitted)
                true
            } else {
                if (state.phase is Phase.Ended) resetLocked()
                if (!state.begun) this.device = device
                false
            }
        }
        if (waits) {
            AppLog.i("CarPlayNative: iPhone ${device.label} attached while the last session closes; it waits for the close")
            return true
        }
        AppLog.i("CarPlayNative: iPhone ${device.label} attached ($verdict)")
        feed(Event.Attached(verdict))
        return true
    }

    fun onPermissionResult(device: IphoneDevice, granted: Boolean) {
        if (granted) {
            onAttach(device)
        } else {
            AppLog.w("CarPlayNative: USB permission denied for ${device.id}")
            feed(Event.PermissionDenied)
        }
    }

    /** An iPhone left the bus. A real unplug of the latched iPhone clears the user-exit latch. */
    fun onDetach(device: IphoneDevice) {
        synchronized(reducerLock) {
            latch = latch.onDetach(device.id, clock())
            if (queuedAttach?.device?.id == device.id) queuedAttach = null
        }
        if (device.id == this.device?.id) feed(Event.Detached)
    }

    /** The one external end path: the activity's exit and the service's end come here. A session ends once, whatever calls this. */
    fun stop(reason: String) {
        val idle = synchronized(reducerLock) {
            // A user exit during a close still counts: the latch is set and a waiting attach is dropped.
            if (reason == USER_EXIT && closing) {
                latch = latch.exit(device?.id)
                queuedAttach = null
            }
            state.phase == Phase.Idle || state.phase is Phase.Ended
        }
        if (!idle) feed(if (reason == USER_EXIT) Event.UserExit else Event.Fail(reason))
    }

    fun onSurfaceReady(width: Int, height: Int) {
        AppLog.i("CarPlayNative: surface ready ${width}x$height")
        session?.airplay?.requestKeyframe("new surface")
    }

    /** [contacts] are in pixels of the picture ([videoSize]). False when no session shows a picture yet. */
    fun sendTouch(contacts: List<AirPlayTouchReport.Contact>): Boolean {
        val size = videoSizeFlow.value ?: return false
        return session?.airplay?.sendTouch(contacts, size.first, size.second) == true
    }

    /** Puts every field a finished attempt changed back, as a new attempt needs it. The user-exit latch stays. */
    private fun resetLocked() {
        attempt++
        state = CarPlayNativeSession.State()
        device = null
        videoSizeFlow.value = null
        phaseFlow.value = Phase.Idle
    }

    /** [from] is the attempt the event belongs to; an event of an older attempt is dropped. Null means the current one. */
    private fun feed(event: Event, from: Long? = null) {
        val actions: List<Action>
        val current: Long
        synchronized(reducerLock) {
            if (from != null && from != attempt) return
            current = attempt
            val wasEnded = state.phase is Phase.Ended
            val result = CarPlayNativeSession.reduce(state, event, clock())
            state = result.state
            actions = result.actions
            live = result.state.begun
            // Set with the end itself, so no attach can reset the state before the session has closed.
            if (!wasEnded && result.state.phase is Phase.Ended) closing = true
            if (result.state.phase !is Phase.Ended) phaseFlow.value = result.state.phase
            if (result.state.phase != Phase.Idle && result.state.phase !is Phase.Ended) ensureTickerLocked()
        }
        for (action in actions) run(action, current)
    }

    private fun ensureTickerLocked() {
        if (ticker?.isAlive == true && tickerAttempt == attempt) return
        val mine = attempt
        tickerAttempt = mine
        ticker = Thread({
            try {
                while (true) {
                    Thread.sleep(tickMs)
                    val over = synchronized(reducerLock) { attempt != mine || state.phase is Phase.Ended || state.phase == Phase.Idle }
                    if (over) return@Thread
                    feed(Event.Tick, mine)
                }
            } catch (_: InterruptedException) {
                // finish() interrupts the ticker of its own attempt.
            }
        }, "carplay-native-tick").apply { isDaemon = true; start() }
    }

    private fun run(action: Action, attempt: Long) {
        when (action) {
            Action.AskPermission -> device?.let { target ->
                try { usb.askPermission(target) } catch (e: RuntimeException) { AppLog.w("CarPlayNative: the permission request failed (${e.javaClass.simpleName})") }
            }
            Action.RequestMode -> device?.let { target -> Thread({ requestMode(target, attempt) }, "carplay-native-mode").apply { isDaemon = true; start() } }
            Action.BeginSession -> beginSession(attempt)
            Action.ResendStart -> session?.pump?.resendStart()
            is Action.End -> finish(action.reason, attempt)
            is Action.Refused -> CarPlayNativeAvailability.line(action.verdict, inputs().sdk)?.let { AppLog.i(it) }
            is Action.Log -> AppLog.i(action.line)
        }
    }

    private fun requestMode(target: IphoneDevice, attempt: Long) {
        val answer = try {
            usb.requestMode(target)
        } catch (e: IOException) {
            AppLog.w("CarPlayNative: the CarPlay mode request failed (${e.javaClass.simpleName})")
            feed(Event.ModeRefused, attempt)
            return
        }
        when (answer) {
            IphoneModeAnswer.Accepted -> feed(Event.ModeAccepted, attempt)
            is IphoneModeAnswer.Refused -> { AppLog.w(answer.line); feed(Event.ModeRefused, attempt) }
        }
    }

    private fun beginSession(attempt: Long) {
        val target = device
        if (target == null) { feed(Event.Fail("no iPhone to open"), attempt); return }
        val opened = Session(target, attempt)
        // The attempt may have ended between the action and here; finish() then found no session, so none may start.
        val current = synchronized(reducerLock) {
            (this.attempt == attempt && state.phase !is Phase.Ended).also { if (it) session = opened }
        }
        if (current) Thread({ opened.run() }, "carplay-native-session").apply { isDaemon = true; start() }
    }

    /**
     * The one end path of the manager. It takes the session and stops the ticker of its own attempt at once, then the
     * closer thread closes the session, in order, and publishes [Phase.Ended] for that attempt.
     */
    private fun finish(reason: String, attempt: Long) {
        val ended = synchronized(reducerLock) {
            live = false
            if (reason == USER_EXIT) latch = latch.exit(device?.id)
            if (tickerAttempt == attempt) {
                ticker?.let { if (it !== Thread.currentThread()) it.interrupt() }
                ticker = null
            }
            session?.takeIf { it.attempt == attempt }?.also { session = null }
        }
        if (ended == null) { closed(reason, attempt, null); return }
        closer.execute { try { ended.close() } finally { closed(reason, attempt, ended) } }
    }

    /** Runs once the session of [attempt] has closed. Only then may a waiting attach start the next attempt. */
    private fun closed(reason: String, attempt: Long, ended: Session?) {
        if (ended?.started == true) AppLog.i("CarPlayNative: link lost (${reason.removePrefix(PREFIX)})")
        val queued = synchronized(reducerLock) {
            if (attempt == this.attempt) phaseFlow.value = Phase.Ended(reason)
            latch = latch.closed(clock())
            endsFlow.value = endsFlow.value + 1
            closing = false
            queuedAttach.also { queuedAttach = null }
        }
        if (queued == null) return
        // The device may have left, or lost its permission, while the close ran.
        val (waiting, permitted) = queued
        if (!usb.isPresent(waiting) || (permitted && !usb.hasPermission(waiting))) {
            AppLog.i("CarPlayNative: the waiting attach of ${waiting.label} is dropped; the iPhone left or lost its permission")
            return
        }
        onAttach(waiting)
    }

    /** An attach that came during a close, and whether it held the permission then. */
    private data class QueuedAttach(val device: IphoneDevice, val permitted: Boolean)

    private class Cancelled : RuntimeException()

    /**
     * One native session's resources. Every field is set under [lock]; one set after [close] is closed at once.
     * The gate and the sinks begin under [lock] after a [closed] check, so [close] either sees them begun and ends them,
     * or they never begin.
     */
    private inner class Session(val device: IphoneDevice, val attempt: Long) {
        private val lock = Any()
        private var closed = false
        private var gateBegun = false
        private var sinksOpen = false
        @Volatile var started = false
        @Volatile var airplay: AirPlayServer? = null
        @Volatile var pump: Iap2Pump? = null
        private var opened: OpenedIphone? = null
        private var usbLink: IphoneUsbLink? = null
        private var host: UsbmuxHost? = null
        private var ncmLink: EthernetLink? = null
        private var tun: TunPort? = null
        private var bridge: Ipv6TunBridge? = null
        private var carkitStream: BlockingDuplexByteStream? = null
        private var tunnelRequested = false

        val isCancelled: Boolean get() = synchronized(lock) { closed }

        /** An event of a session that is no longer the current one is dropped, so a late thread cannot end the next session. */
        fun post(event: Event) { if (session === this) feed(event, attempt) }

        fun run() {
            try {
                steps()
            } catch (_: Cancelled) {
                // stop() closed the session while a step ran.
            } catch (e: IOException) {
                post(Event.Fail(e.message?.removePrefix(PREFIX) ?: "I/O failed (${e.javaClass.simpleName})"))
            } catch (e: RuntimeException) {
                post(Event.Fail("failed (${e.javaClass.simpleName}: ${e.message})"))
            }
        }

        private fun steps() {
            val now = inputs()
            val signer = now.signer ?: return refuse("refused, no MFi signer is set up")
            if (tunnel.isDummyVpnUp()) return refuse("refused, the dummy VPN holds the VPN slot")
            if (!tunnel.isPrepared()) return refuse("VPN consent missing")
            // Another owner (the tunnel check) holds the tunnel; this session must not start or stop it.
            if (tunnel.isCarPlayTunRunning()) return refuse("refused, a CarPlay tunnel is already up (the tunnel check holds it)")
            val begun = synchronized(lock) {
                if (closed) throw Cancelled()
                gate.begin("native ${device.id}", now.aaSessionLive).also { gateBegun = it }
            }
            if (!begun) return refuse("refused, the connection arbiter holds the bus")
            started = true
            AppLog.i("CarPlayNative: session start on ${device.label}")
            synchronized(lock) {
                if (closed) throw Cancelled()
                video.hookKeyframeRequests { reason -> airplay?.requestKeyframe(reason) }
                audio.open()
                sinksOpen = true
            }

            val serial = usb.serial(device) ?: UNKNOWN_SERIAL
            val iphone = adopt(usb.open(device), { it.close() }) { opened = it }
            AppLog.i(
                "CarPlayNative: configuration ${iphone.configurationId} set; usbmux on " +
                    "0x%02x/0x%02x".format(iphone.usbmuxEndpoints.first, iphone.usbmuxEndpoints.second),
            )
            lateinit var mux: UsbmuxHost
            lateinit var link: IphoneUsbLink
            link = IphoneUsbLink(iphone.pipe, clock, { mux.onBytes(it) }, { reason -> mux.onLinkEnded(reason); post(Event.Fail(reason.removePrefix(PREFIX))) })
            mux = UsbmuxHost({ link.write(it) }, clock)
            adopt(link, { it.close() }) { usbLink = it }
            adopt(mux, { it.close() }) { host = it }
            link.start()
            mux.begin()

            val ncm = iphone.openNcm()
            adopt(ncm.link, { it.close() }) { ncmLink = it }
            val mac = HostMacPolicy.resolve(ncm.hostMac)
            val macText = HostMacPolicy.text(mac.mac)
            if (!mac.fromDescriptor) AppLog.i("CarPlayNative: NCM host MAC not in the descriptor; using $macText")
            val port = openTunnel()
            val ipv6 = Ipv6TunBridge(
                ncm.link, port, mac.mac,
                onFirstInbound = { AppLog.i("CarPlayNative: USB network up"); post(Event.FirstNcmFrame) },
                onError = { post(Event.Fail(it)) },
            )
            adopt(ipv6, { it.close() }) { bridge = it }
            ipv6.start()

            val identity = storage.identity()
            val address = bindAddress() ?: throw IOException("the tunnel address is not on any interface")
            val (width, height) = displaySize()
            val server = AirPlayServer(
                address, CarPlayNativeInfo.config(product, macText, width, height, densityDpi()), identity, storage.pairings(),
                signer, video, audio, AirPlayEvents(this), clock, { peer -> peerGate(peer, address) },
            )
            adopt(server, { it.close() }) { airplay = it }
            val airplayPort = server.start()
            post(Event.Opened)

            val service = carkit.open(mux, serial, device.label, { isCancelled })
            adopt(service.stream, { it.close() }) { carkitStream = it }
            AppLog.i(if (service.usedSavedRecord) "CarPlayNative: using the saved pair record" else "CarPlayNative: paired")
            AppLog.i("CarPlayNative: carkit service open")
            post(Event.CarkitOpen)

            val identification = Iap2Identification.Config(
                product.name, product.modelIdentifier, product.manufacturer, identity.pairingId,
                product.firmwareVersion, product.hardwareVersion, iphone.ncmControlInterface,
            )
            val start = Iap2Messages.StartSession(
                Ipv6Text.format(TunProbePacket.TUN_ADDRESS), airplayPort, macText, hex(identity.publicKey), CarPlayNativeInfo.SOURCE_VERSION,
            )
            val running = Iap2Pump(service.stream, Iap2Link(), Iap2Control(signer, identification, start), clock, Iap2Events(this))
            adopt(running, { it.stop() }) { pump = it }
            running.start()
        }

        private fun openTunnel(): TunPort {
            val ready = CountDownLatch(1)
            var port: TunPort? = null
            var failure: String? = null
            synchronized(lock) { tunnelRequested = true }
            tunnel.start({ port = it; ready.countDown() }, { failure = it; ready.countDown() })
            val deadline = clock() + CarPlayTunnelCheck.ESTABLISH_TIMEOUT_MS
            while (!ready.await(POLL_MS, TimeUnit.MILLISECONDS)) {
                if (isCancelled) throw Cancelled()
                if (clock() >= deadline) throw IOException("the tunnel did not start in ${CarPlayTunnelCheck.ESTABLISH_TIMEOUT_MS / 1000} s")
            }
            failure?.let { throw IOException("the tunnel failed ($it)") }
            return adopt(port ?: throw IOException("the tunnel gave no descriptor"), { it.close() }) { tun = it }
        }

        /** A refusal before the session starts logs its own line and ends the attempt. */
        private fun refuse(line: String) {
            AppLog.i("CarPlayNative: $line")
            post(Event.Fail(line))
        }

        private fun <T : Any> adopt(item: T, undo: (T) -> Unit, set: (T) -> Unit): T {
            val late = synchronized(lock) { if (closed) true else { set(item); false } }
            if (late) {
                runCatching { undo(item) }
                throw Cancelled()
            }
            return item
        }

        fun close() {
            val (endGate, closeSinks) = synchronized(lock) {
                if (closed) return
                closed = true
                gateBegun to sinksOpen
            }
            try {
                step("airplay") { airplay?.close() }
                step("bridge") { bridge?.close() ?: tun?.close() }
                step("ncm") { ncmLink?.close() }
                step("iap2") { pump?.stop(); carkitStream?.close() }
                step("usbmux host") { host?.close() }
                step("iap2 thread") { pump?.join(JOIN_MS) }
                step("usbmux link") { usbLink?.close() }
                step("usb") { opened?.close() }
                step("tunnel") { if (tunnelRequested) tunnel.stop() }
                if (closeSinks) step("sinks") { video.unhookKeyframeRequests(); audio.close(); video.stop("CarPlay native ended") }
            } finally {
                // The arbiter flag is given back last, on every path, and only by the session that set it.
                if (endGate) step("arbiter") { gate.end() }
            }
        }

        private fun step(name: String, block: () -> Unit) {
            try { block() } catch (e: Exception) { AppLog.w("CarPlayNative: closing $name failed (${e.javaClass.simpleName})") }
        }
    }

    private inner class AirPlayEvents(private val owner: Session) : AirPlayServer.Listener {
        override fun onControlConnection(peer: String) = owner.post(Event.AirPlayConnected)
        override fun onVideoSize(width: Int, height: Int) { if (session === owner) videoSizeFlow.value = width to height }
        override fun onStreaming() = owner.post(Event.Streaming)
        override fun onEnded(reason: String) = owner.post(Event.Fail("AirPlay: $reason"))
        override fun onEndedBeforeSetup(reason: String) = owner.post(Event.AirPlayEndedBeforeSetup)
    }

    private inner class Iap2Events(private val owner: Session) : Iap2Pump.Events {
        override fun onIdentified() = owner.post(Event.IdentificationAccepted)
        override fun onAuthenticated() = owner.post(Event.Authenticated)
        override fun onStartSent(count: Int) = owner.post(Event.StartSent(count))
        override fun onFailed(reason: String) = owner.post(Event.Fail(reason.removePrefix(PREFIX)))
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val USER_EXIT = "user exit"
        private const val PREFIX = "CarPlayNative: "
        private const val UNKNOWN_SERIAL = "unknown"
        private const val POLL_MS = 25L
        private const val JOIN_MS = 2000L
    }
}
