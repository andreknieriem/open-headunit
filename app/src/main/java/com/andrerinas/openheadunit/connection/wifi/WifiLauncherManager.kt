package com.andrerinas.openheadunit.connection.wifi

import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.connection.wifi.direct.NativeBringUpReentryPolicy
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings

open class WifiLauncherManager(val service: AapService) {

    val sharedServices: WifiLauncherSharedServices = WifiLauncherSharedServices(service)
    var active: WifiLauncher? = null
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
     */
    fun setActiveFromSettings(
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
    ) {
        val settings = App.provide(service).settings

        setActive(settings.wifiConnectionMode, force, noInfoToasts, userRequested)
    }

    fun setActive(
        mode: WifiLauncherMode,
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
    ) {
        setActive(mode.factory(this), force, noInfoToasts, userRequested)
    }

    fun setActive(
        newLauncher: WifiLauncher,
        force: Boolean = false,
        noInfoToasts: Boolean = true,
        userRequested: Boolean = false,
    ) {
        if (newLauncher.manager != this)
            throw IllegalArgumentException("newLauncher.manager is different instance")
        if (active == newLauncher)
            throw IllegalArgumentException("newLauncher is already active")

        // Informational rather than debug: this refusal is why a documented re-arm could do
        // nothing at all, and a reporter's log is at INFO.
        if (WifiLauncherRestartPolicy.refusesRestart(
                activeIsStarted = activeIsStarted,
                sameConfiguration = active?.hasSameStartConfiguration(newLauncher) ?: false,
                force = force,
            )
        ) {
            AppLog.i("WifiLauncher: WiFi Mode ${newLauncher.mode}.mode with same start-configuration is already initialized.")
            return
        }

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
        // A USB or user connection attempt in flight outranks the background stack; it is re-armed
        // when that attempt ends. A bring-up the user asked for ends that attempt instead.
        if (ConnectionArbiter.refusesBackground(userRequested)) return
        if (userRequested) ConnectionArbiter.yieldToUser("a wireless bring-up the user asked for")
        liftUserCancel("the user asked for a wireless connection")

        // A forced re-arm gets past the restart policy by design, and the teardown below then wipes
        // the claim WifiDirectManager holds, so two of them raced and the loser's group replaced a
        // group the phone was already joining. Only a same-configuration re-arm is refused; a
        // setting the user just changed still restarts the mode.
        val now = SystemClock.elapsedRealtime()
        if (newLauncher.mode == WifiLauncherMode.NATIVE &&
            active?.hasSameStartConfiguration(newLauncher) == true &&
            NativeBringUpReentryPolicy.isDuplicate(now, lastNativeRearmAtMs)
        ) {
            AppLog.i(
                "WifiLauncher: a Native AA bring-up was re-armed ${now - lastNativeRearmAtMs}ms ago, " +
                    "so this one is not started on top of it."
            )
            return
        }
        if (newLauncher.mode == WifiLauncherMode.NATIVE) lastNativeRearmAtMs = now

        AppLog.i("WifiLauncher: Initializing WiFi Mode: ${newLauncher.mode}")
        // The stack is coming up, so the status pill appears here and is cleared in stop().
        // Manual mode has no background discovery or server waiting for connections, so it must not arm the pill.
        if (newLauncher.mode == WifiLauncherMode.MANUAL) {
            ConnectionStageTracker.clear()
        } else {
            ConnectionStageTracker.beginAttempt(ConnectionStage.ARMED)
        }

        // stop old launcher
        active?.stop(WifiLauncherStopSequence.ANY)

        // replace it with new one
        active = newLauncher
        sharedServices.update(newLauncher)
        active?.start(noInfoToasts)
        activeIsStarted = true
    }

    /**
     * Tears down the active launcher and, at [WifiLauncherStopSequence.LAST], everything shared.
     *
     * Not gated on there being an active launcher. Self Mode binds the wireless server directly,
     * without arming a mode, so an early return here left the port bound after the session ended
     * and after onDestroy. Each of the shared stops already handles being called with nothing
     * running.
     */
    fun stop(seq: WifiLauncherStopSequence = WifiLauncherStopSequence.ANY) {
        active?.stop(seq)
        activeIsStarted = false
        // A stop asked for on its own ends the mode, so the re-arm that follows it is not a
        // duplicate of anything. Only setActive()'s own internal teardown keeps the stamp.
        lastNativeRearmAtMs = 0L

        if (seq.handledAt(WifiLauncherStopSequence.LAST)) {
            sharedServices.stopAll()
            active = null
            ConnectionStageTracker.clear()
        }
    }

    /** The status pill's X: a full stop that no automatic re-arm undoes. */
    fun stopForUser() {
        cancelledByUser = true
        stop(WifiLauncherStopSequence.LAST)
    }

    /** An explicit ask for wireless. No-op unless the X is holding. */
    fun liftUserCancel(reason: String) {
        if (!cancelledByUser) return
        AppLog.i("WifiLauncher: $reason, so the stop from the status pill is lifted.")
        cancelledByUser = false
    }

    /**
     * Starts a sweep on the discovery instance that is already there.
     *
     * Deliberately does not stop the running scan first. stop() is cooperative, so the pair started
     * a second sweep beside the first, and two sweeps probing the head unit server's port at once
     * is how it ends up bound to a connection nobody owns. startScan() is a no-op while a healthy
     * scan is in flight and says so in its return value.
     *
     * @return true if a sweep started, false if one was already in flight, and null if there is no
     *   discovery loop to kick at all. The last two are different situations and only one of them
     *   is worth reacting to, so they are not folded together.
     */
    fun forceStartDiscoveryScan(): Boolean? {
        val discovery = sharedServices.localDiscovery ?: return null

        return discovery.startScan()
    }

    fun startDiscovery(oneShot: Boolean = false) {
        // Allow discovery for Strategy 0 (NSD), 3 (Phone Hotspot) and 4 (Headunit Hotspot)
        if (active == null || active?.hasLocalDiscovery() == false)
            return

        sharedServices.startLocalDiscovery(oneShot)
    }

    fun restartDiscovery() {
        active?.restartDiscovery()
    }
}
