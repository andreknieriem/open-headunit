package com.andrerinas.openheadunit.connection.wifi

import android.os.SystemClock
import com.andrerinas.openheadunit.connection.ConnectionOperation
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.wifi.direct.NativeBringUpReentryPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ScreenPower
import com.andrerinas.openheadunit.utils.Settings

open class WifiLauncherManager(val service: AapService) {

    private val cleanupGeneration = WirelessCleanupGeneration()

    /** Capture before a hop; cleanup admission compares this exact owner under lifecycle. */
    internal fun cleanupTicket(): Any = App.provide(service).commManager.withAppConnectionLifecycleLock {
        cleanupGeneration.capture()
    }
    internal fun isCleanupCurrent(ticket: Any): Boolean = App.provide(service).commManager.withAppConnectionLifecycleLock {
        cleanupGeneration.isCurrent(ticket)
    }
    internal fun cleanupTicketFor(state: CommManager.ConnectionState.Disconnected): Any? =
        App.provide(service).commManager.withAppConnectionLifecycleLock {
            cleanupGeneration.captureFor(state, state.endedAttempt, state.hadPhysicalConnection)
        }
    internal fun noteRearm() = App.provide(service).commManager.withAppConnectionLifecycleLock {
        // A Start can run before the queued Disconnected observer. Record the terminal
        // object already published when this arm begins, not just the observer's later epoch.
        // Self Mode is also admitted while another attempt is still Connecting; that attempt's
        // terminal does not exist yet, so record the attempt itself. An Error can replace an
        // ended attempt's terminal before teardown republishes it; record that attempt too.
        // Read all atomically: the attempt's IO thread can publish its terminal while this runs.
        val (terminal, attempt, ended) = App.provide(service).commManager.rearmSnapshot()
        cleanupGeneration.advance(terminal, attempt, ended)
    }

    val sharedServices: WifiLauncherSharedServices = WifiLauncherSharedServices(service)
    var active: WifiLauncher? = null
        private set

    /** The immutable authority [active] was armed under, including its original Save. */
    var armedOperation: ConnectionOperation? = null
        private set


    /**
     * Whether [active] is running. stop() leaves the launcher in place at every sequence but LAST,
     * so this is what separates "this mode is already up" from "stopped, and still here".
     */
    var activeIsStarted: Boolean = false
        private set

    /**
     * When the Native mode was last re-armed, so a second re-arm on its heels can be refused.
     *
     * WifiDirectManager holds the same stamp, but its stop() clears it and setActive() always
     * stops before it starts - so two forced re-arms inside the window could never see each other
     * there, and the second replaced a group the phone had already been told to join.
     */
    private var lastNativeRearmAtMs = 0L

    /**
     * Whether the status pill's X is still holding the stack down. Set by [stopForUser], lifted
     * only by a bring-up the user asks for, and dies with the service rather than persisting.
     */
    var cancelledByUser: Boolean = false
        private set


    val isActive: Boolean get() = active != null

    val activeMode: WifiLauncherMode? get() = active?.mode

    /**
     * @param userRequested the user asked for a wireless connection by hand, which lifts the
     *   connection-mode refusal below. Not [force], which several automatic paths already pass.
     * @param operation the input's original ticket and Save. A later settle, wait or hold carries
     *   this authority unchanged; Stop or Save cancellation prevents it from arming the stack.
     */
    fun setActiveFromSettings(
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
        operation: ConnectionOperation,
    ) {
        val settings = App.provide(service).settings

        setActive(settings.wifiConnectionMode, force, noInfoToasts, userRequested, operation)
    }

    fun setActive(
        mode: WifiLauncherMode,
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
        operation: ConnectionOperation,
    ) {
        setActive(mode.factory(this), force, noInfoToasts, userRequested, operation)
    }

    fun setActive(
        newLauncher: WifiLauncher,
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
        operation: ConnectionOperation,
    ) {
        if (newLauncher.manager != this)
            throw IllegalArgumentException("newLauncher.manager is different instance")
        if (active == newLauncher)
            throw IllegalArgumentException("newLauncher is already active")
        if (!App.provide(service).commManager.isCurrent(operation)) {
            AppLog.i("WifiLauncher: a wireless bring-up asked for before Stop is not armed")
            return
        }

        // Fast no-op; admission repeats this decision atomically before publishing the arm.
        if (WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = activeIsStarted && armedOperation?.let { App.provide(service).commManager.isCurrent(it) } == true,
                sameConfiguration = active?.hasSameStartConfiguration(newLauncher) ?: false,
                force = force,
            )
        ) return

        // Every automatic entry point lands here, including the Bluetooth auto-start that fires
        // when the phone comes into range — which on a looping unit would walk straight back into
        // the crash the guard was set to avoid. Explicit user actions release the pause first, so
        // this only ever blocks a start nobody asked for.
        if (Settings.isWirelessPausedByBootLoop(service)) {
            AppLog.w("AapService: Wireless bring-up requested, but it is paused by the boot-loop guard. Open the app to re-enable it.")
            return
        }

        // A wired session is live and we took the wireless stack down for it. Every automatic entry
        // point lands here, including the Bluetooth auto-start a poke can raise on the unit itself,
        // so without this the stack walks straight back up underneath a session that has no use for
        // it. AapService.rearmWirelessAfterWiredSession() is what lets it back in.
        val commManager = App.provide(service).commManager
        if (UsbSessionQuiescePolicy.shouldRefuseBringUp(
                quiescedForThisSession = service.wirelessQuiescedForWiredSession,
                sessionIsLive = commManager.isConnected,
                sessionIsWireless = commManager.isWirelessSession
            )
        ) {
            AppLog.i("AapService: wireless bring-up requested while a USB session is live — not arming it")
            return
        }

        // The user connects by cable or Self Mode only, so nothing automatic creates a network or
        // pokes a phone. The WiFi button passes, which is what keeps the manual route working.
        if (WirelessSelectionPolicy.refusesBringUp(
                wirelessSelected = App.provide(service).settings.showsWifi(),
                userRequested = userRequested,
            )
        ) {
            AppLog.i("WifiLauncher: wireless bring-up requested, but WiFi is not one of the chosen " +
                "connection modes. Not arming it; the WiFi button still works.")
            return
        }

        // The settings screen took the stack down so a wake poke cannot raise the projection over
        // it. This is the backstop for the automatic paths; a Save behind the screen is held by
        // AapService and re-armed when the screen closes.
        if (service.wirelessPausedForSettings) {
            AppLog.i("AapService: wireless bring-up requested while the settings screen is open. " +
                "Not arming it until the screen closes.")
            return
        }

        // The status pill's X. Every automatic re-arm lands here too, the Bluetooth auto-start
        // included, and its ACL is often the cancelled bring-up's own. Only the user lifts it.
        if (WirelessCancelPolicy.refusesBringUp(cancelledByUser, userRequested)) {
            AppLog.i("WifiLauncher: wireless bring-up requested, but the user stopped it from the " +
                "status pill. Not arming it; the WiFi button still works.")
            return
        }
        // A relaunch during the unit's sleep armed the radios within seconds. The wake replays it.
        val screen = ScreenPower.isInteractive(service)
        val asleep = WirelessSleepHold.isAsleep(screen)
        if (WirelessSleepHold.refusesBringUp(asleep, userRequested, newLauncher.mode == WifiLauncherMode.MANUAL)) {
            // The wake owns the complete command, including any Save that authorized it.
            val firstHold = commManager.withAppConnectionLifecycleLock {
                if (!commManager.isCurrent(operation)) false
                else WirelessSleepHold.hold(force, operation)
            }
            if (firstHold) {
                AppLog.i("WifiLauncher: wireless bring-up held while the screen is off. " +
                    "The screen coming on re-arms it.")
            }
            return
        }
        // A USB or user connection attempt in flight outranks the background stack; it is re-armed
        // when that attempt ends, under this bring-up's own ticket. A bring-up the user asked for
        // ends that attempt instead.
        if (ConnectionArbiter.refusesBackground(userRequested)) {
            service.noteWirelessOwed(operation.ticket)
            return
        }
        if (userRequested && !ConnectionArbiter.yieldToUser(
                "a wireless bring-up the user asked for",
                ConnectionAdmission { action ->
                    commManager.withAppConnectionLifecycleLock {
                        if (!commManager.isCurrent(operation)) false else { action(); true }
                    }
                },
            )) return

        // Admission and publication are one lifecycle decision. SDK teardown/start below can
        // complete after Stop, but it cannot publish an old arm over a successor. In particular,
        // a launcher left in place by Stop must not make a fresh Open look already started.
        val previous = commManager.withAppConnectionLifecycleLock {
            if (!commManager.isCurrent(operation)) return@withAppConnectionLifecycleLock null
            val previousIsCurrent = armedOperation?.let { commManager.isCurrent(it) } == true
            if (WifiLauncherRestartPolicy.refusesRestart(
                    activeIsStarted = activeIsStarted && previousIsCurrent,
                    sameConfiguration = active?.hasSameStartConfiguration(newLauncher) ?: false,
                    force = force,
                )
            ) {
                AppLog.i("WifiLauncher: WiFi Mode ${newLauncher.mode}.mode with same start-configuration is already initialized.")
                return@withAppConnectionLifecycleLock null
            }
            val now = SystemClock.elapsedRealtime()
            if (newLauncher.mode == WifiLauncherMode.NATIVE && previousIsCurrent &&
                active?.hasSameStartConfiguration(newLauncher) == true &&
                NativeBringUpReentryPolicy.isDuplicate(now, lastNativeRearmAtMs)
            ) {
                AppLog.i("WifiLauncher: the current Native AA bring-up was just re-armed; keeping its group.")
                return@withAppConnectionLifecycleLock null
            }
            if (newLauncher.mode == WifiLauncherMode.NATIVE) lastNativeRearmAtMs = now
            val outgoing = active
            active = newLauncher
            armedOperation = operation
            activeIsStarted = false
            if (userRequested) cancelledByUser = false
            noteRearm()
            // Pair distinguishes an admitted first arm (whose outgoing launcher is null)
            // from a rejected admission.
            Pair(outgoing, operation)
        } ?: return

        previous.first?.stop(WifiLauncherStopSequence.ANY)
        if (active !== newLauncher || armedOperation !== operation || !commManager.isCurrent(operation)) return
        if (newLauncher.mode != WifiLauncherMode.NATIVE) service.closeRetainedHct()

        AppLog.i("WifiLauncher: Initializing WiFi Mode: ${newLauncher.mode}")
        // Manual mode has no background discovery or server waiting for connections.
        if (newLauncher.mode == WifiLauncherMode.MANUAL) ConnectionStageTracker.clear()
        else ConnectionStageTracker.beginAttempt(ConnectionStage.ARMED)
        WirelessSleepHold.started(userRequested)
        sharedServices.update(newLauncher, operation)
        newLauncher.start(noInfoToasts, operation)
        commManager.withAppConnectionLifecycleLock {
            if (active === newLauncher && armedOperation === operation && commManager.isCurrent(operation))
                activeIsStarted = true
        }
    }

    /**
     * Tears down the active launcher and, at [WifiLauncherStopSequence.LAST], everything shared.
     *
     * Not gated on there being an active launcher. Self Mode binds the wireless server directly,
     * without arming a mode, so an early return here left the port bound after the session ended
     * and after onDestroy. Each of the shared stops already handles being called with nothing
     * running.
     */
    fun stop(owner: Any, seq: WifiLauncherStopSequence = WifiLauncherStopSequence.ANY, keepNativeGroup: Boolean = false) {
        val cleanup = App.provide(service).commManager.withAppConnectionLifecycleLock {
            if (!cleanupGeneration.isCurrent(owner)) null else detachCurrent(seq, keepNativeGroup)
        } ?: return
        cleanup()
    }

    /** Called under the lifecycle lock; external teardown uses only the objects detached here. */
    private fun detachCurrent(seq: WifiLauncherStopSequence, keepNativeGroup: Boolean): () -> Unit {
        val launcher = active
        activeIsStarted = false
        WirelessSleepHold.stopped()
        lastNativeRearmAtMs = 0L
        val sharedCleanup = if (seq.handledAt(WifiLauncherStopSequence.LAST)) {
            active = null
            armedOperation = null
            ConnectionStageTracker.clear()
            sharedServices.detachAll(keepNativeGroup)
        } else null
        return {
            launcher?.stop(seq)
            sharedCleanup?.invoke()
        }
    }

    /** A deferred command retires its exact current owners in the same decision as validation. */
    internal fun detachForOperation(operation: ConnectionOperation): (() -> Unit)? {
        val comm = App.provide(service).commManager
        return comm.withAppConnectionLifecycleLock {
            if (!comm.isCurrent(operation)) null
            else detachCurrent(WifiLauncherStopSequence.ANY, keepNativeGroup = false)
        }
    }

    /** Claim callbacks may still hold the caller's lifecycle lock; return only captured cleanup. */
    internal fun detachForClaim(owner: ConnectionArbiter.Claim): (() -> Unit)? =
        App.provide(service).commManager.withAppConnectionLifecycleLock {
            if (!activeIsStarted) return@withAppConnectionLifecycleLock null
            var cleanup: (() -> Unit)? = null
            ConnectionArbiter.runIfHeld(owner) {
                cleanup = detachCurrent(WifiLauncherStopSequence.ANY, keepNativeGroup = false)
            }
            cleanup
        }

    fun stopForOperation(operation: ConnectionOperation): Boolean {
        val cleanup = detachForOperation(operation) ?: return false
        cleanup()
        return true
    }

    /** The status pill's X: a full stop that no automatic re-arm undoes. */
    fun stopForUser(owner: Any) {
        val releaseHct = service.captureParkedHctRelease()
        val cleanup = App.provide(service).commManager.withAppConnectionLifecycleLock {
            if (!cleanupGeneration.isCurrent(owner)) null else {
                cancelledByUser = true
                detachCurrent(WifiLauncherStopSequence.LAST, keepNativeGroup = false)
            }
        } ?: return
        releaseHct?.invoke()
        cleanup()
    }

    /** An explicit ask retires old cleanup and lifts the status pill's X hold, if any. */
    fun liftUserCancel(reason: String) {
        // Even an already-running launcher can be explicitly chosen while old Exit awaits IO.
        val lifted = App.provide(service).commManager.withAppConnectionLifecycleLock {
            noteRearm()
            cancelledByUser.also { cancelledByUser = false }
        }
        if (!lifted) return
        AppLog.i("WifiLauncher: $reason, so the stop from the status pill is lifted.")
    }

    /**
     * Starts a sweep on the discovery instance that is already there.
     *
     * Deliberately does not stop the running scan first. stop() is cooperative, so the pair started
     * a second sweep beside the first, and two sweeps probing the head unit server's port at once
     * is how it ends up bound to a connection nobody owns. startScan() is a no-op while a healthy
     * scan is in flight and says so in its return value.
     *
     * @param operation the original authority for this sweep; withdrawn work does not start.
     * @return true if a sweep started, false if one was already in flight, and null if there is no
     *   discovery loop to kick at all. The last two are different situations and only one of them
     *   is worth reacting to, so they are not folded together.
     */
    fun forceStartDiscoveryScan(operation: ConnectionOperation): Boolean? {
        val discovery = sharedServices.localDiscovery ?: return null

        return discovery.startScan(operation = operation)
    }

    /**
     * [operation] carries the original ticket and Save through scans and their handoffs.
     */
    fun startDiscovery(oneShot: Boolean = false, operation: ConnectionOperation) {
        if (!App.provide(service).commManager.isCurrent(operation)) return
        if (active == null || active?.hasLocalDiscovery() == false) return
        sharedServices.startLocalDiscovery(oneShot, operation)
    }

    fun restartDiscovery(operation: ConnectionOperation) {
        if (!App.provide(service).commManager.isCurrent(operation)) return
        active?.restartDiscovery(operation)
    }
}
