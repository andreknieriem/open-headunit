package com.andrerinas.openheadunit.connection.broker

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.utils.AppLog

/**
 * One BLE session from this head unit to the NativeBTBroker.
 * Wireless credentials are pushed only after a device has been chosen in settings.
 */
object NativeBTBrokerSession {
    /** BLE scanner / GATT path used by this broker requires Marshmallow. */
    const val MIN_SDK = Build.VERSION_CODES.M

    enum class Phase { IDLE, CONNECTING, READY, FAILED, INCOMPLETE, PAIRING }

    data class Status(val phase: Phase, val detail: String = "")

    private val main = Handler(Looper.getMainLooper())
    private var client: NativeBTBrokerClient? = null
    private var appContext: Context? = null
    private var lastSnapshot: String? = null
    private var lastInfo: NativeBTBrokerProtocol.DisplayInfo? = null
    /** Last [WifiDirectDisplayInfo.read] for a non-force [offerLiveGroup]. */
    private var lastLiveGroupReadMs = 0L
    private var status = Status(Phase.IDLE)
    private var listener: ((Status) -> Unit)? = null
    private var frameSink: ((NativeBTBrokerProtocol.Frame) -> Unit)? = null
    private var transportErrorHandler: ((String) -> Unit)? = null
    private var retryUntilMs = 0L
    private var retryPosted = false
    private var fromRetry = false
    private var gaveUp = false
    private var projectionUp = false
    /** Last time PROJECTION_UP was recorded on the broker. 0 while projection is down. */
    private var lastProjectionOkAtMs = 0L
    /** This down period already handed the broker one AA_ENABLE. */
    private var startSent = false
    /**
     * When false, [offer] must not send AA_ENABLE / wake HFP.
     * Cleared only by an intentional AA start ([armRelay], [offerStart], [requestAaWake]).
     * Projection ending must not re-arm HFP by itself.
     */
    private var allowAutoDial = true
    /** The one start is waiting out the snapshot window, so a later offer does not send another. */
    private var dialArmed = false
    /** One debug line per in-flight start — carrier polls must not refill bug-report buffers. */
    private var loggedStartInFlight = false
    private var lastDialAtMs = 0L
    /** Last intentional [requestAaWake] / complete dial — separate from [lastDialAtMs] snapshot dials. */
    private var lastWakeAtMs = 0L
    /** Last wake already had a complete Wi-Fi snapshot (SSID/IP/key/BSSID). */
    private var lastWakeHadNetwork = false
    /** Phone Android Auto RFCOMM is up on the broker (EVT_RFCOMM_OPEN … CLOSE). */
    @Volatile private var phoneAaRfcommLive = false
    /** BLE is down and the retry window has not expired. The displayed phase stays as it was. */
    private var awaitingBroker = false
    /** 2 s CMD_GET_STATUS probe while [NativeBTBrokerStore.endAaOnDisconnect] is on. */
    private var watchdogPosted = false
    private var watchdogFailures = 0

    /** Runs once when the broker stays down for the whole retry window. */
    var onGiveUp: (() -> Unit)? = null

    /**
     * PROJECTION_UP could not be recorded for [PROJECTION_FAIL_TIMEOUT_MS].
     * Android Auto projection should stop.
     */
    var onProjectionRecordFailed: (() -> Unit)? = null

    /** Native AA carrier that consumes RFCOMM bytes relayed over BLE. */
    fun setFrameSink(sink: ((NativeBTBrokerProtocol.Frame) -> Unit)?) {
        frameSink = sink
        // Keep the session dispatcher so pairing confirmation still updates phase.
        client?.onFrame = ::dispatchFrame
    }

    /**
     * GATT write path lost RFCOMM bytes. The carrier should end the handshake stream.
     * Cleared together with [setFrameSink].
     */
    fun setTransportErrorHandler(handler: ((String) -> Unit)?) {
        transportErrorHandler = handler
        client?.onTransportError = handler
    }

    /**
     * While [Phase.PAIRING], a phone bond / HFP link from the broker means pairing succeeded —
     * show Connected (READY). Always forward to the Native AA carrier sink when set.
     */
    private fun dispatchFrame(frame: NativeBTBrokerProtocol.Frame) {
        when (frame.type) {
            NativeBTBrokerProtocol.EVT_RFCOMM_OPEN -> phoneAaRfcommLive = true
            NativeBTBrokerProtocol.EVT_RFCOMM_CLOSE -> phoneAaRfcommLive = false
            else -> Unit
        }
        if (status.phase == Phase.PAIRING && isPhonePairConfirmed(frame)) {
            val address = appContext?.let { activeBrokerAddress(it) } ?: status.detail
            AppLog.i("Broker: phone pairing confirmed — link ready")
            publish(Status(Phase.READY, address))
        }
        frameSink?.invoke(frame)
    }

    /** EVT_PHONE_LINK, or EVT_STATUS with the paired flag set. */
    private fun isPhonePairConfirmed(frame: NativeBTBrokerProtocol.Frame): Boolean {
        return when (frame.type) {
            NativeBTBrokerProtocol.EVT_PHONE_LINK -> frame.payload.size >= 6
            NativeBTBrokerProtocol.EVT_STATUS ->
                frame.payload.size >= 2 && (frame.payload[1].toInt() and 0x01) != 0
            else -> false
        }
    }

    /** Phone-bound bytes for the BLINK-style handshake. */
    fun sendRfcomm(bytes: ByteArray): Boolean = client?.sendRfcomm(bytes) ?: false

    /** Allow the broker to indicate more DATA_RFCOMM_RX pieces. */
    fun grantCredit(extra: Int = 4) {
        client?.sendCredit(extra)
    }

    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= MIN_SDK

    fun hasSavedBroker(context: Context): Boolean =
        isSupported() && activeBrokerAddress(context.applicationContext) != null

    /** Saved BLE address only when the master switch is on. */
    private fun activeBrokerAddress(app: Context): String? {
        val store = NativeBTBrokerStore(app)
        if (!store.enabled) return null
        return store.deviceAddress
    }

    /**
     * Open BLE, send HFP_ON (wake) and AA_ENABLE (RFCOMM relay). Credentials still go
     * out later through [offer]. [notifyProjection] (up) sends HFP_OFF.
     *
     * When BLE is already up this is a no-op keep-alive — use [requestAaWake] for another HFP wake.
     */
    fun armRelay(context: Context) {
        val app = context.applicationContext
        val address = activeBrokerAddress(app) ?: return
        appContext = app
        if (status.phase == Phase.CONNECTING || status.phase == Phase.READY || status.phase == Phase.PAIRING) {
            return
        }
        allowAutoDial = true
        startSent = false
        loggedStartInFlight = false
        publish(Status(Phase.CONNECTING))
        pushAaEnable(app, address, lastInfo ?: NativeBTBrokerProtocol.emptyDisplayInfo())
    }

    /**
     * Intentional AA / Wi-Fi start: HFP_ON then AA_ENABLE even when BLE is already READY.
     * Call this for every bring-up; [armRelay] alone skips wake once the link is up.
     *
     * Credential refresh used to call this many times per second and flap the phone's AA RFCOMM.
     * Skip while projection/RFCOMM is already up. Cooldown applies between wakes; the first
     * complete-network wake after an early (no-SSID) wake is always allowed.
     */
    fun requestAaWake(context: Context? = null) {
        val app = context?.applicationContext ?: appContext ?: return
        val address = activeBrokerAddress(app) ?: return
        appContext = app
        if (projectionUp || syncProjectionFromComm(app)) return
        val info = lastInfo ?: NativeBTBrokerProtocol.emptyDisplayInfo()
        val complete = info.isComplete
        wakeBlockedReason(complete)?.let { reason ->
            AppLog.d("Broker: AA wake skipped — $reason")
            return
        }
        allowAutoDial = true
        noteWakeAttempt(completeNetwork = complete, consumeStartBudget = complete)
        AppLog.i(
            "Broker: intentional AA wake (HFP) to $address" +
                if (complete) "" else " (early, waiting for Wi-Fi snapshot)"
        )
        if (status.phase != Phase.READY && status.phase != Phase.CONNECTING) {
            publish(Status(Phase.CONNECTING))
        }
        pushAaEnable(app, address, info)
    }

    /**
     * @return skip reason, or null when a wake/dial may proceed.
     * First complete wake after an early wake bypasses cooldown ([upgrading]).
     */
    private fun wakeBlockedReason(completeNetwork: Boolean): String? {
        if (phoneAaRfcommLive) return "phone AA RFCOMM already open"
        if (status.phase == Phase.PAIRING) return "phone pairing window"
        val upgrading = completeNetwork && !lastWakeHadNetwork
        if (upgrading) return null
        val now = SystemClock.elapsedRealtime()
        if (lastWakeAtMs != 0L && now - lastWakeAtMs < WAKE_COOLDOWN_MS) {
            return "cooldown ${(WAKE_COOLDOWN_MS - (now - lastWakeAtMs) + 999) / 1000}s left"
        }
        return null
    }

    private fun noteWakeAttempt(completeNetwork: Boolean, consumeStartBudget: Boolean) {
        val now = SystemClock.elapsedRealtime()
        loggedStartInFlight = false
        lastDialAtMs = now
        lastWakeAtMs = now
        lastWakeHadNetwork = completeNetwork
        if (consumeStartBudget) {
            startSent = true
            main.removeCallbacks(sendDial)
            dialArmed = false
        }
    }

    private fun markBrokerReady(address: String) {
        awaitingBroker = false
        stopRetry()
        gaveUp = false
        publish(Status(Phase.READY, address))
    }

    private fun pushAaEnable(
        app: Context,
        address: String,
        info: NativeBTBrokerProtocol.DisplayInfo,
    ) {
        brokerClient(app).prepare(
            address,
            info.copy(generation = System.currentTimeMillis() / 1000L),
            restartProjection = true,
            confirmProjection = false,
        ) { result ->
            if (result.isSuccess) {
                markBrokerReady(address)
            } else {
                if (!noteLinkLost()) {
                    publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    private val retry = Runnable {
        retryPosted = false
        val app = appContext ?: return@Runnable
        if (!awaitingBroker && (status.phase == Phase.READY || status.phase == Phase.PAIRING)) return@Runnable
        if (SystemClock.elapsedRealtime() >= retryUntilMs) {
            giveUp()
            return@Runnable
        }
        val known = lastInfo
        fromRetry = true
        try {
            // BLE recovery only — do not re-enable AA_ENABLE/HFP via offerStart after a finished projection.
            if (known != null && known.isComplete) {
                offer(app, known, force = true)
            } else {
                offerLiveGroup(app, force = true)
            }
        } finally {
            fromRetry = false
        }
        scheduleRetry()
    }

    fun current(): Status = status

    fun setListener(onStatus: ((Status) -> Unit)?) {
        listener = onStatus
        onStatus?.invoke(status)
    }

    fun offer(context: Context, info: NativeBTBrokerProtocol.DisplayInfo, force: Boolean = false) {
        val app = context.applicationContext
        val address = activeBrokerAddress(app)
        if (address == null) {
            publish(Status(Phase.IDLE))
            return
        }
        if (!info.isComplete) {
            if (status.phase != Phase.READY && status.phase != Phase.CONNECTING) {
                publish(Status(Phase.INCOMPLETE))
            }
            return
        }
        lastInfo = info
        appContext = app
        // While AA is up, only the PROJECTION_UP heartbeat talks to the broker.
        // Re-running HELLO/SET_NETWORK/PROJECTION_UP every snapshot floods the link.
        if (projectionUp || syncProjectionFromComm(app)) {
            return
        }
        // A start that never brought the socket up may be asked again. Not before the phone has
        // had time to finish the one that is already on the way. Never after a finished projection —
        // that would wake HFP without an intentional AA start.
        if (allowAutoDial && startSent && !projectionUp && lastDialAtMs != 0L &&
            SystemClock.elapsedRealtime() - lastDialAtMs >= START_RETRY_MS
        ) {
            startSent = false
            loggedStartInFlight = false
        }
        // Snapshots keep arriving for about a second after the group comes up. Wait that out,
        // then send one start with the latest of them. Another start while the phone is still
        // dialing is what drops the session that just came up.
        if (allowAutoDial && !projectionUp && !startSent && !dialArmed) {
            dialArmed = true
            main.postDelayed(sendDial, START_DELIVERY_MS)
        }
        val snapshot = listOf(address, info.ssid, info.bssid, info.ipAddress, info.port.toString()).joinToString("|")
        if (!projectionUp && (startSent || dialArmed) && !awaitingBroker &&
            snapshot == lastSnapshot &&
            (status.phase == Phase.READY || status.phase == Phase.CONNECTING)
        ) {
            if (!loggedStartInFlight) {
                loggedStartInFlight = true
                AppLog.d("Broker: previous start is still on the way to the phone")
            }
            return
        }
        if (snapshot == lastSnapshot && status.phase == Phase.CONNECTING) return
        if (!force && snapshot == lastSnapshot && status.phase == Phase.READY) return
        lastSnapshot = snapshot
        loggedStartInFlight = false
        if (!fromRetry) gaveUp = false
        publish(Status(Phase.CONNECTING))
        push(app, info, dial = false)
    }

    /** One AA_ENABLE for this down period, using whichever snapshot arrived last. */
    private val sendDial: Runnable = Runnable {
        dialArmed = false
        val app = appContext ?: return@Runnable
        val info = lastInfo ?: return@Runnable
        if (!allowAutoDial || syncProjectionFromComm(app) || projectionUp || startSent || !info.isComplete) {
            return@Runnable
        }
        wakeBlockedReason(completeNetwork = true)?.let { reason ->
            AppLog.d("Broker: snapshot dial skipped — $reason")
            return@Runnable
        }
        noteWakeAttempt(completeNetwork = true, consumeStartBudget = true)
        AppLog.i("Broker: one start for ${info.ssid} ${info.ipAddress}:${info.port}")
        push(app, info, dial = true)
    }

    /**
     * If Android Auto TCP is already up, mark projection and tell the broker so HFP stays off.
     * @return true when the session is live and no AA_ENABLE should be sent
     */
    private fun syncProjectionFromComm(app: Context): Boolean {
        val live = try {
            App.provide(app).commManager.isConnected
        } catch (_: Throwable) {
            false
        }
        if (!live) return false
        if (!projectionUp) {
            AppLog.i("Broker: projection already live — notifying broker and skipping AA_ENABLE/HFP wake")
            notifyProjection(true)
        }
        return true
    }

    private fun push(app: Context, info: NativeBTBrokerProtocol.DisplayInfo, dial: Boolean) {
        val address = activeBrokerAddress(app) ?: return
        AppLog.i("Broker: sending Wi-Fi snapshot ${info.ssid} ${info.ipAddress}:${info.port} to $address" +
            if (dial) "" else " without a new start")
        brokerClient(app).prepare(
            address,
            info.copy(generation = System.currentTimeMillis() / 1000L),
            restartProjection = dial,
            confirmProjection = projectionUp && !dial,
        ) { result ->
            if (result.isSuccess) {
                markBrokerReady(address)
            } else {
                lastSnapshot = null
                if (!noteLinkLost()) {
                    publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
                }
            }
        }
    }

    /** Ask the broker to drop phone bonds and answer a new phone's Classic inquiry for 120 seconds. */
    fun openPairing(context: Context) {
        val app = context.applicationContext
        val address = activeBrokerAddress(app)
        if (address == null) {
            publish(Status(Phase.IDLE))
            return
        }
        appContext = app
        // Pairing needs inbound HFP. Drop PROJECTION_UP so the broker accepts CMD_HFP_ON.
        if (projectionUp) {
            AppLog.i("Broker: standing down projection for new-phone pairing")
            notifyProjection(false)
        }
        publish(Status(Phase.CONNECTING))
        AppLog.i("Broker: opening new-phone pairing window on $address")
        brokerClient(app).allowPhonePairing(address) { result ->
            if (result.isSuccess) {
                publish(Status(Phase.PAIRING, address))
            } else {
                publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
            }
        }
    }

    /**
     * The phone is being waited on or woken. Send the last complete display snapshot again so the
     * broker can start Android Auto with the address and network that are on screen now.
     */
    fun offerStart(context: Context) {
        val app = context.applicationContext
        if (activeBrokerAddress(app) == null) return
        allowAutoDial = true
        startSent = false
        loggedStartInFlight = false
        val known = lastInfo
        if (known != null) {
            offer(app, known, force = true)
            return
        }
        AppLog.i("Broker: phone wait/wake has no snapshot yet, reading the live group")
        offerLiveGroup(app, force = true)
    }

    /** The phone opened or closed the projection socket. While it stays up, repeat the notice so the broker can drop the state when these stop. */
    fun notifyProjection(up: Boolean) {
        val changed = projectionUp != up
        projectionUp = up
        main.removeCallbacks(projectionHeartbeat)
        main.removeCallbacks(projectionFailWatch)
        if (changed && up) {
            lastProjectionOkAtMs = SystemClock.elapsedRealtime()
            main.removeCallbacks(sendDial)
            dialArmed = false
            loggedStartInFlight = false
            allowAutoDial = false
            startSent = true
        }
        if (changed && !up) {
            lastProjectionOkAtMs = 0L
            // Session ended — do not auto HFP_ON / AA_ENABLE from the next Wi-Fi snapshot.
            allowAutoDial = false
            startSent = true
            dialArmed = false
            main.removeCallbacks(sendDial)
            client?.setHfp(false)
        }
        if (changed) {
            AppLog.i("Broker: projection socket ${if (up) "up" else "down"}")
        }
        sendProjection(up)
        if (changed && up) {
            client?.setHfp(false)
        }
        if (up && projectionUp) {
            main.postDelayed(projectionHeartbeat, PROJECTION_HEARTBEAT_MS)
            armProjectionFailWatch()
        }
    }

    private val projectionHeartbeat: Runnable = Runnable {
        if (!projectionUp) return@Runnable
        sendProjection(true)
        if (projectionUp) main.postDelayed(projectionHeartbeat, PROJECTION_HEARTBEAT_MS)
    }

    private fun sendProjection(up: Boolean) {
        val link = client
        if (link == null) {
            if (up) noteProjectionRecord(false)
            return
        }
        link.notifyProjection(up) { ok -> if (up) noteProjectionRecord(ok) }
    }

    private fun noteProjectionRecord(ok: Boolean) {
        if (!projectionUp) return
        if (ok) {
            lastProjectionOkAtMs = SystemClock.elapsedRealtime()
            armProjectionFailWatch()
            return
        }
        val silentMs = SystemClock.elapsedRealtime() - lastProjectionOkAtMs
        AppLog.w(
            "Broker: PROJECTION_UP was not recorded " +
                "(${silentMs}ms without success, limit ${PROJECTION_FAIL_TIMEOUT_MS}ms)"
        )
        if (silentMs >= PROJECTION_FAIL_TIMEOUT_MS) {
            stopProjectionDueToRecordFailure()
        } else {
            armProjectionFailWatch()
        }
    }

    /** Fires if PROJECTION_UP stays unrecorded (write fail or hang) for the timeout. */
    private val projectionFailWatch: Runnable = Runnable {
        if (!projectionUp || lastProjectionOkAtMs == 0L) return@Runnable
        val silentMs = SystemClock.elapsedRealtime() - lastProjectionOkAtMs
        if (silentMs >= PROJECTION_FAIL_TIMEOUT_MS) {
            AppLog.w(
                "Broker: PROJECTION_UP silent for ${silentMs}ms " +
                    "(limit ${PROJECTION_FAIL_TIMEOUT_MS}ms), stopping Android Auto"
            )
            stopProjectionDueToRecordFailure()
        } else {
            armProjectionFailWatch()
        }
    }

    private fun armProjectionFailWatch() {
        main.removeCallbacks(projectionFailWatch)
        if (!projectionUp || lastProjectionOkAtMs == 0L) return
        val remaining =
            (PROJECTION_FAIL_TIMEOUT_MS - (SystemClock.elapsedRealtime() - lastProjectionOkAtMs))
                .coerceAtLeast(0L)
        main.postDelayed(projectionFailWatch, remaining)
    }

    private fun stopProjectionDueToRecordFailure() {
        if (!projectionUp) return
        projectionUp = false
        lastProjectionOkAtMs = 0L
        main.removeCallbacks(projectionHeartbeat)
        main.removeCallbacks(projectionFailWatch)
        AppLog.w("Broker: PROJECTION_UP failed for ${PROJECTION_FAIL_TIMEOUT_MS / 1000}s, stopping Android Auto")
        main.post { onProjectionRecordFailed?.invoke() }
    }

    /**
     * Read the live Wi-Fi Direct group and send it when a broker is saved.
     * Non-force calls are throttled — the carrier polls every 200ms for BLE keep-alive,
     * and re-reading P2P that often freezes the phone.
     */
    fun offerLiveGroup(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (activeBrokerAddress(app) == null) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLiveGroupReadMs < LIVE_GROUP_READ_MIN_MS) {
            // Same cached snapshot — skip while a start is already armed or on the way.
            val info = lastInfo?.takeIf { it.isComplete } ?: return
            if (startSent || dialArmed) return
            offer(app, info, force = false)
            return
        }
        lastLiveGroupReadMs = now
        WifiDirectDisplayInfo.read(app) { info -> offer(app, info, force) }
    }

    /**
     * User cancelled wireless connect, or the Native AA mode stopped before projection.
     * Drops HFP immediately and blocks auto re-dial until the next intentional start.
     */
    fun releaseHfp(why: String = "stand-down") {
        allowAutoDial = false
        startSent = true
        dialArmed = false
        loggedStartInFlight = false
        main.removeCallbacks(sendDial)
        val link = client ?: return
        AppLog.i("Broker: releasing HFP ($why)")
        link.setHfp(false)
    }

    fun close() {
        stopRetry()
        stopWatchdog()
        gaveUp = false
        awaitingBroker = false
        client?.setHfp(false)
        client?.close()
        client = null
        projectionUp = false
        lastProjectionOkAtMs = 0L
        startSent = false
        allowAutoDial = true
        dialArmed = false
        loggedStartInFlight = false
        main.removeCallbacks(projectionHeartbeat)
        main.removeCallbacks(projectionFailWatch)
        main.removeCallbacks(sendDial)
        lastDialAtMs = 0L
        lastWakeAtMs = 0L
        lastWakeHadNetwork = false
        phoneAaRfcommLive = false
        lastSnapshot = null
        lastInfo = null
        lastLiveGroupReadMs = 0L
        publish(Status(Phase.IDLE))
    }

    /**
     * Re-reads [NativeBTBrokerStore.endAaOnDisconnect] and starts or stops the 2 s link probe.
     * Call after the setting changes while a broker session may already be up.
     */
    fun syncWatchdog(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        main.post { syncWatchdogLocked() }
    }

    private fun brokerClient(app: Context): NativeBTBrokerClient {
        val active = client ?: NativeBTBrokerClient(app).also { client = it }
        active.onLinkLost = { noteLinkLost() }
        active.onFrame = ::dispatchFrame
        active.onTransportError = transportErrorHandler
        return active
    }

    /** @return true when the loss is inside the quiet retry window */
    private fun noteLinkLost(): Boolean {
        if (gaveUp) return false
        val app = appContext ?: return false
        if (activeBrokerAddress(app) == null) return false
        awaitingBroker = true
        val now = SystemClock.elapsedRealtime()
        if (retryUntilMs != 0L && now >= retryUntilMs) {
            giveUp()
            return true
        }
        if (retryUntilMs == 0L) {
            retryUntilMs = now + RETRY_WINDOW_MS
            AppLog.w("Broker: link lost, retrying for ${RETRY_WINDOW_MS / 1000}s")
        }
        scheduleRetry()
        return true
    }

    private fun scheduleRetry() {
        if (retryPosted || retryUntilMs == 0L) return
        retryPosted = true
        main.postDelayed(retry, RETRY_EVERY_MS)
    }

    private fun stopRetry() {
        retryUntilMs = 0L
        retryPosted = false
        main.removeCallbacks(retry)
    }

    private fun giveUp() {
        if (gaveUp) return
        gaveUp = true
        awaitingBroker = false
        stopRetry()
        stopWatchdog()
        AppLog.w("Broker: still down after ${RETRY_WINDOW_MS / 1000}s, stopping the phone session")
        publish(Status(Phase.FAILED, "broker link lost"))
        main.post { onGiveUp?.invoke() }
    }

    private fun endAaOnDisconnectEnabled(): Boolean {
        val app = appContext ?: return false
        val store = NativeBTBrokerStore(app)
        return store.enabled && store.endAaOnDisconnect
    }

    private fun syncWatchdogLocked() {
        val live = client != null &&
            (status.phase == Phase.READY || status.phase == Phase.CONNECTING || status.phase == Phase.PAIRING)
        if (endAaOnDisconnectEnabled() && live) {
            startWatchdog()
        } else {
            stopWatchdog()
        }
    }

    private fun startWatchdog() {
        if (watchdogPosted) return
        watchdogPosted = true
        watchdogFailures = 0
        AppLog.i("Broker: 2 s link probe on (endAaOnDisconnect)")
        main.post(linkWatchdog)
    }

    private fun stopWatchdog() {
        watchdogPosted = false
        watchdogFailures = 0
        main.removeCallbacks(linkWatchdog)
    }

    private val linkWatchdog: Runnable = Runnable {
        watchdogPosted = false
        if (!endAaOnDisconnectEnabled()) return@Runnable
        val link = client
        if (link == null ||
            (status.phase != Phase.READY && status.phase != Phase.CONNECTING && status.phase != Phase.PAIRING)
        ) {
            return@Runnable
        }
        link.ping { ok ->
            if (ok) {
                watchdogFailures = 0
            } else {
                watchdogFailures += 1
                AppLog.w("Broker: link probe failed ($watchdogFailures/$WATCHDOG_FAILURES)")
                if (watchdogFailures >= WATCHDOG_FAILURES) {
                    AppLog.w("Broker: link probe lost, ending the phone session")
                    giveUp()
                    return@ping
                }
            }
            if (!gaveUp && endAaOnDisconnectEnabled() && client != null) {
                watchdogPosted = true
                main.postDelayed(linkWatchdog, WATCHDOG_EVERY_MS)
            }
        }
    }

    private const val RETRY_WINDOW_MS = 10_000L
    private const val RETRY_EVERY_MS = 2_000L
    /** Shorter than the broker's 4s silence limit, so a missed write does not look like the socket closed. */
    private const val PROJECTION_HEARTBEAT_MS = 2_000L
    /** No successful PROJECTION_UP write for this long ends the Android Auto session. */
    private const val PROJECTION_FAIL_TIMEOUT_MS = 10_000L
    private const val WATCHDOG_EVERY_MS = 2_000L
    private const val WATCHDOG_FAILURES = 2

    /**
     * The Wi-Fi group often finishes about a second after the first snapshot. The start waits
     * that long and then goes out once, with the latest snapshot.
     */
    private const val START_DELIVERY_MS = 1_000L
    /** Long enough that the phone can finish the first dial. A second start inside this drops it. */
    private const val START_RETRY_MS = 15_000L
    /** Min gap between complete-network HFP wakes — credential refresh must not flap RFCOMM. */
    private const val WAKE_COOLDOWN_MS = 3_000L
    /** Carrier ensureLink ticks at 200ms; P2P group reads stay much slower. */
    private const val LIVE_GROUP_READ_MIN_MS = 5_000L

    private fun publish(next: Status) {
        status = next
        main.post {
            listener?.invoke(next)
            syncWatchdogLocked()
        }
    }
}
