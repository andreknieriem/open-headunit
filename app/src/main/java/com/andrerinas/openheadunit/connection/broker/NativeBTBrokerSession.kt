package com.andrerinas.openheadunit.connection.broker

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.andrerinas.openheadunit.utils.AppLog

/**
 * One BLE session from this head unit to the NativeBTBroker.
 * Wireless credentials are pushed only after a device has been chosen in settings.
 */
object NativeBTBrokerSession {
    enum class Phase { IDLE, CONNECTING, READY, FAILED, INCOMPLETE, PAIRING }

    data class Status(val phase: Phase, val detail: String = "")

    private val main = Handler(Looper.getMainLooper())
    private var client: NativeBTBrokerClient? = null
    private var appContext: Context? = null
    private var lastSnapshot: String? = null
    private var lastInfo: NativeBTBrokerProtocol.DisplayInfo? = null
    private var status = Status(Phase.IDLE)
    private var listener: ((Status) -> Unit)? = null
    private var frameSink: ((NativeBTBrokerProtocol.Frame) -> Unit)? = null
    private var retryUntilMs = 0L
    private var retryPosted = false
    private var fromRetry = false
    private var gaveUp = false
    private var projectionUp = false
    private var projectionRecordFailures = 0
    /** This down period already handed the broker one AA_ENABLE. */
    private var startSent = false
    /** The one start is waiting out the snapshot window, so a later offer does not send another. */
    private var dialArmed = false
    private var lastDialAtMs = 0L
    /** BLE is down and the retry window has not expired. The displayed phase stays as it was. */
    private var awaitingBroker = false
    /** 1 Hz CMD_GET_STATUS probe while [NativeBTBrokerStore.endAaOnDisconnect] is on. */
    private var watchdogPosted = false
    private var watchdogFailures = 0

    /** Runs once when the broker stays down for the whole retry window. */
    var onGiveUp: (() -> Unit)? = null

    /** Projection status could not be recorded ten times in a row. Android Auto should stop. */
    var onProjectionRecordFailed: (() -> Unit)? = null

    /** Native AA carrier that consumes RFCOMM bytes relayed over BLE. */
    fun setFrameSink(sink: ((NativeBTBrokerProtocol.Frame) -> Unit)?) {
        frameSink = sink
        client?.onFrame = sink
    }

    /** Phone-bound bytes for the BLINK-style handshake. */
    fun sendRfcomm(bytes: ByteArray): Boolean = client?.sendRfcomm(bytes) ?: false

    /** Allow the broker to indicate more DATA_RFCOMM_RX pieces. */
    fun grantCredit(extra: Int = 4) {
        client?.sendCredit(extra)
    }

    fun hasSavedBroker(context: Context): Boolean =
        NativeBTBrokerStore(context.applicationContext).deviceAddress != null

    /**
     * Open BLE and send AA_ENABLE so the broker answers HFP/RFCOMM even when the Wi-Fi Direct
     * group is not ready yet. Credentials still go out later through [offer].
     */
    fun armRelay(context: Context) {
        val app = context.applicationContext
        val address = NativeBTBrokerStore(app).deviceAddress ?: return
        appContext = app
        if (status.phase == Phase.CONNECTING || status.phase == Phase.READY || status.phase == Phase.PAIRING) {
            syncWatchdog()
            return
        }
        publish(Status(Phase.CONNECTING))
        val info = lastInfo ?: NativeBTBrokerProtocol.DisplayInfo(
            ipAddress = "",
            port = NativeBTBrokerProtocol.PROJECTION_PORT,
            ssid = "",
            key = "",
            bssid = "",
        )
        brokerClient(app).prepare(
            address,
            info,
            restartProjection = true,
            confirmProjection = false,
        ) { result ->
            if (result.isSuccess) {
                awaitingBroker = false
                stopRetry()
                gaveUp = false
                publish(Status(Phase.READY, address))
            } else {
                if (!noteLinkLost()) {
                    publish(Status(Phase.FAILED, result.exceptionOrNull()?.message ?: ""))
                }
            }
        }
        syncWatchdog()
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
            if (known != null && known.isComplete) {
                offer(app, known, force = true)
            } else {
                offerStart(app)
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
        val address = NativeBTBrokerStore(app).deviceAddress
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
        // A start that never brought the socket up may be asked again. Not before the phone has
        // had time to finish the one that is already on the way.
        if (startSent && !projectionUp && lastDialAtMs != 0L &&
            SystemClock.elapsedRealtime() - lastDialAtMs >= START_RETRY_MS
        ) {
            startSent = false
        }
        // Snapshots keep arriving for about a second after the group comes up. Wait that out,
        // then send one start with the latest of them. Another start while the phone is still
        // dialing is what drops the session that just came up.
        if (!projectionUp && !startSent && !dialArmed) {
            dialArmed = true
            main.postDelayed(sendDial, START_DELIVERY_MS)
        }
        val snapshot = listOf(address, info.ssid, info.bssid, info.ipAddress, info.port.toString()).joinToString("|")
        if (!projectionUp && (startSent || dialArmed) && !awaitingBroker &&
            snapshot == lastSnapshot &&
            (status.phase == Phase.READY || status.phase == Phase.CONNECTING)
        ) {
            AppLog.i("Broker: previous start is still on the way to the phone")
            return
        }
        if (snapshot == lastSnapshot && status.phase == Phase.CONNECTING) return
        if (!force && snapshot == lastSnapshot && status.phase == Phase.READY) return
        lastSnapshot = snapshot
        if (!fromRetry) gaveUp = false
        publish(Status(Phase.CONNECTING))
        push(app, info, dial = false)
    }

    /** One AA_ENABLE for this down period, using whichever snapshot arrived last. */
    private val sendDial: Runnable = Runnable {
        dialArmed = false
        val app = appContext ?: return@Runnable
        val info = lastInfo ?: return@Runnable
        if (projectionUp || startSent || !info.isComplete) return@Runnable
        startSent = true
        lastDialAtMs = SystemClock.elapsedRealtime()
        AppLog.i("Broker: one start for ${info.ssid} ${info.ipAddress}:${info.port}")
        push(app, info, dial = true)
    }

    private fun push(app: Context, info: NativeBTBrokerProtocol.DisplayInfo, dial: Boolean) {
        val address = NativeBTBrokerStore(app).deviceAddress ?: return
        AppLog.i("Broker: sending Wi-Fi snapshot ${info.ssid} ${info.ipAddress}:${info.port} to $address" +
            if (dial) "" else " without a new start")
        brokerClient(app).prepare(
            address,
            info.copy(generation = System.currentTimeMillis() / 1000L),
            restartProjection = dial,
            confirmProjection = projectionUp && !dial,
        ) { result ->
            if (result.isSuccess) {
                awaitingBroker = false
                stopRetry()
                gaveUp = false
                publish(Status(Phase.READY, address))
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
        val address = NativeBTBrokerStore(app).deviceAddress
        if (address == null) {
            publish(Status(Phase.IDLE))
            return
        }
        appContext = app
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
        if (NativeBTBrokerStore(app).deviceAddress == null) return
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
        if (changed && up) {
            projectionRecordFailures = 0
            main.removeCallbacks(sendDial)
            dialArmed = false
        }
        if (changed && !up) {
            projectionRecordFailures = 0
            startSent = false
            dialArmed = false
            main.removeCallbacks(sendDial)
        }
        if (changed) {
            AppLog.i("Broker: projection socket ${if (up) "up" else "down"}")
        }
        sendProjection(up)
        if (up && projectionUp) {
            main.postDelayed(projectionHeartbeat, PROJECTION_HEARTBEAT_MS)
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
            projectionRecordFailures = 0
            return
        }
        projectionRecordFailures += 1
        AppLog.w("Broker: projection status was not recorded ($projectionRecordFailures/$PROJECTION_RECORD_FAILURES)")
        if (projectionRecordFailures < PROJECTION_RECORD_FAILURES) return
        projectionRecordFailures = 0
        projectionUp = false
        main.removeCallbacks(projectionHeartbeat)
        AppLog.w("Broker: projection status failed $PROJECTION_RECORD_FAILURES times, stopping Android Auto")
        main.post { onProjectionRecordFailed?.invoke() }
    }

    /** Read the live Wi-Fi Direct group and send it when a broker is saved. */
    fun offerLiveGroup(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        if (NativeBTBrokerStore(app).deviceAddress == null) return
        WifiDirectDisplayInfo.read(app) { info -> offer(app, info, force) }
    }

    fun close() {
        stopRetry()
        stopWatchdog()
        gaveUp = false
        awaitingBroker = false
        client?.close()
        client = null
        projectionUp = false
        projectionRecordFailures = 0
        startSent = false
        dialArmed = false
        main.removeCallbacks(projectionHeartbeat)
        main.removeCallbacks(sendDial)
        lastDialAtMs = 0L
        lastSnapshot = null
        lastInfo = null
        publish(Status(Phase.IDLE))
    }

    /**
     * Re-reads [NativeBTBrokerStore.endAaOnDisconnect] and starts or stops the 1 Hz link probe.
     * Call after the setting changes while a broker session may already be up.
     */
    fun syncWatchdog(context: Context? = null) {
        if (context != null) appContext = context.applicationContext
        main.post { syncWatchdogLocked() }
    }

    private fun brokerClient(app: Context): NativeBTBrokerClient {
        val active = client ?: NativeBTBrokerClient(app).also { client = it }
        active.onLinkLost = { noteLinkLost() }
        active.onFrame = frameSink
        return active
    }

    /** @return true when the loss is inside the quiet retry window */
    private fun noteLinkLost(): Boolean {
        if (gaveUp) return false
        val app = appContext ?: return false
        if (NativeBTBrokerStore(app).deviceAddress == null) return false
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
        return NativeBTBrokerStore(app).endAaOnDisconnect
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
        AppLog.i("Broker: 1 Hz link probe on (endAaOnDisconnect)")
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
    private const val PROJECTION_HEARTBEAT_MS = 1_000L
    private const val PROJECTION_RECORD_FAILURES = 10
    private const val WATCHDOG_EVERY_MS = 1_000L
    private const val WATCHDOG_FAILURES = 2

    /**
     * The Wi-Fi group often finishes about a second after the first snapshot. The start waits
     * that long and then goes out once, with the latest snapshot.
     */
    private const val START_DELIVERY_MS = 1_000L
    /** Long enough that the phone can finish the first dial. A second start inside this drops it. */
    private const val START_RETRY_MS = 15_000L

    private fun publish(next: Status) {
        status = next
        main.post {
            listener?.invoke(next)
            syncWatchdogLocked()
        }
    }
}
