package com.andrerinas.openheadunit.connection.self

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.utils.DummyVpnPolicy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBTDiscovery
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherBroadcast
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherLegacy
import com.andrerinas.openheadunit.connection.self.launchers.SelfLauncherV17_4
import com.andrerinas.openheadunit.connection.wifi.WifiLauncherManager
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ToastUtils
import com.andrerinas.openheadunit.utils.VpnControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SelfLauncherManager(
    private val service: AapService,
    private val wifiLauncherManager: WifiLauncherManager
) {

    /**
     * Self Mode: a launch still waiting for its phone, or the loopback session that is live now.
     *
     * Derived, never stored. A session's Self mode belongs to that session, which CommManager
     * knows from its attempt; a stored flag could only be cleared by observing the session's end
     * on a conflated flow, and every end it missed left Self policy on the next route.
     */
    val isActive: Boolean
        get() = isLaunchPending || App.provide(service).commManager.isLiveLoopbackSession

    /**
     * A launch accepted and still unanswered: its outcome is undecided, Stop has not revoked its
     * ticket, no physical connection has arrived since its acceptance, and the Save that requested
     * it, if any, still stands. An arrival answers it whether or not any observer saw that
     * connection or its end; Stop revokes it before its cancellation reaches this manager.
     */
    val isLaunchPending: Boolean
        get() {
            val mark = launchMark
            if (!launchPending || mark == null) return false
            val commManager = App.provide(service).commManager
            val save = settingsLaunchOwner
            return commManager.acceptsAppConnectionTicket(mark.ticket) && !commManager.arrivedSince(mark) &&
                (save == null || save.acceptsSettingsRestart(commManager.connectionState.value))
        }

    /**
     * True from the moment a launch is accepted until its launchers have finished running.
     *
     * Cleared by [stop] as well as by the launch itself, because this manager is a long-lived
     * object that gets stopped and re-armed within one service, and a flag set in one direction
     * only would strand Self Mode after any stop that landed mid-launch.
     */
    @Volatile
    private var launchInFlight: Boolean = false

    /**
     * Takes down a Self Mode VPN whose phone never arrived.
     *
     * [stopWirelessServer] used to do this by accident. Without it a user who starts Self Mode and
     * walks away leaves a tun that routes 0.0.0.0/0 into a descriptor nobody reads, and the unit
     * has no IPv4 until the service dies.
     */
    private var selfModeVpnWatchdog: Job? = null
    // The accepted launch's identity. It outlives the launch's outcome: Save's fallback and
    // obsolete cancellation recognise their own launch by it.
    private var launchJob: Job? = null
    private var launchTicket: Long? = null
    // Whether the current launch still awaits its outcome: an arrival, a standing failure or a stop.
    private var launchPending = false
    // CommManager's mark at acceptance. The first physical arrival after it answers this launch:
    // fulfils it over loopback, supersedes it over any other route.
    private var launchMark: CommManager.LaunchMark? = null
    private var launchTimeoutJob: Job? = null
    // The Save whose relaunch this is; null for a manual launch.
    private var settingsLaunchOwner: CommManager.ConnectionState.Disconnected? = null

    /**
     * "Self Mode" connects the device to itself over the loopback interface.
     *
     * Starts [com.andrerinas.openheadunit.connection.wifi.server.WirelessServer] on port 5288, then launches the Google AA Wireless Setup
     * Activity pointing at `127.0.0.1:5288`. This causes the AA Wireless app to treat
     * the device as both the head unit and the phone, enabling a loopback session.
     *
     * [createFakeNetwork] and [createFakeWifiInfo] produce the Parcelable extras the
     * AA Wireless activity requires; they are constructed reflectively because the
     * relevant Android classes have no public constructors.
     */
    fun openAaSettings() {
        val intent = Intent().apply {
            setClassName(
                AA_PACKAGE,
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity"
            )
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            service.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent("android.settings.APPLICATION_DETAILS_SETTINGS").apply {
                    data = Uri.parse("package:com.google.android.projection.gearhead")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                service.startActivity(fallbackIntent)
            } catch (e2: Exception) {
                AppLog.e("SelfMode: Failed to open AA settings: ${e2.message}")
            }
        }
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun start(
        settingsRestart: CommManager.ConnectionState.Disconnected? = null,
        needsDummyVpn: Boolean = false,
        ticket: Long = App.provide(service).commManager.appConnectionTicket(),
    ) {
        val commManager = App.provide(service).commManager
        if (!commManager.acceptsAppConnectionTicket(ticket)) return

        // Stop can revoke the old launch before its service relay or cancellation cleanup
        // reaches Main. Fresh input owns a new ticket and must not coalesce into that job.
        // Retire only its bookkeeping; the retained VPN/network may already belong to this run.
        // A connected session is not pending work: its Self mode is the session's own, and the
        // coalescing check below ignores this request as already connected.
        if (launchTicket != null && launchTicket != ticket && !commManager.isConnected) {
            stopIfCurrent(launchJob, preserveVpn = true)
        }

        // auto-start-self-mode and an explicit ACTION_START_SELF_MODE both land here, and running
        // two launches at once costs a session rather than a retry. See SelfLaunchCoalescePolicy.
        if (!SelfLaunchCoalescePolicy.shouldStart(launchInFlight, commManager.isConnected)) {
            // MainActivity can begin the launch before Home finishes its offline-VPN
            // decision/consent. Merge that requirement into the still-running Self job;
            // do not start a second launcher or alter an already connected session.
            if (needsDummyVpn && launchPending && launchInFlight && !commManager.isConnected &&
                launchJob?.isActive == true) adoptDummyVpn(needsDummyVpn = true)
            AppLog.i(
                "SelfMode: a launch is already " +
                    (if (commManager.isConnected) "connected" else "in flight") +
                    "; ignoring this request rather than starting a second one"
            )
            return
        }

        // Manual Self Mode owns the next connection even before Gearhead dials port 5288.
        // Save's own relaunch retains its permission; a later user launch revokes it.
        if (settingsRestart == null) commManager.cancelPendingSettingsRestart()
        else if (!settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) return

        // Acceptance precedes the queued Main job and its server acquisition. An older
        // terminal observer must not take this new launch's wireless arm in that scheduling gap.
        wifiLauncherManager.noteRearm()
        // A new launch owns its own deadline; the previous session cannot time it out.
        launchTimeoutJob?.cancel()
        settingsLaunchOwner = settingsRestart
        launchPending = true
        launchInFlight = true
        launchTicket = ticket
        // Dated by arrivals, not by the state observed now: the first physical connection after
        // this point answers the launch, even if no observer ever sees it or its end.
        val mark = commManager.launchMark(ticket)
        launchMark = mark
        // Own the retained VPN before queued work can be cancelled. Otherwise a cancel
        // between acceptance and Main dispatch would still see the old SESSION owner.
        adoptDummyVpn(needsDummyVpn)
        // Publish and bind the job before it can run. Revoking Save then cancels both a queued
        // launch and a launcher suspended while waiting for the network, without touching a new job.
        val job = service.serviceScope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
            if (!commManager.acceptsAppConnectionTicket(ticket)) {
                stopIfCurrent(coroutineContext[Job], preserveVpn = true)
                return@launch
            }
            if (settingsRestart != null && !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) {
                stopIfCurrent(coroutineContext[Job], preserveVpn = commManager.connectionState.value !== settingsRestart)
                return@launch
            }
            // prepare launchers
            val services = SelfLauncherServices(service, wifiLauncherManager, settingsRestart, ticket, mark)
            val launchers: Array<SelfLauncher>

            val path = installedPath(service)

            if (path == SelfLaunchPath.HEADUNIT_SERVER) {
                AppLog.i("SelfMode: AA 17.4+ detected. Connecting directly to Headunit Server on 127.0.0.1:5277...")
                launchers = arrayOf(
                    SelfLauncherV17_4(this@SelfLauncherManager, services)
                )

            } else {
                AppLog.i("SelfMode: AA < 17.4 detected. Starting WirelessServer on 5288 and running legacy triggers...")
                launchers = arrayOf(
                    SelfLauncherLegacy(this@SelfLauncherManager, services),
                    SelfLauncherBroadcast(this@SelfLauncherManager, services), // fallback #1
                    SelfLauncherBTDiscovery(this@SelfLauncherManager, services), // fallback #2
                )
            }

            // run them
            var anySucceeded = false

            try {
                for (launcher in launchers) {
                    services.ensureLaunchAllowed()
                    try {
                        if (!launcher.run())
                            AppLog.w("SelfMode: Launch of '${launcher.name}' failed")
                        else {
                            AppLog.w("SelfMode: Launch of '${launcher.name}' had no issues")
                            anySucceeded = true
                            break
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        AppLog.w("SelfMode: Launch of '${launcher.name}' had caused an error", e)
                    }
                }
            } finally {
                // The launchers have had their turn; what follows is waiting for the phone, which
                // another request is entitled to retry.
                if (launchJob === coroutineContext[Job]) launchInFlight = false
            }

            val owner = coroutineContext[Job]
            // all failed :(
            if (!anySucceeded) {
                AppLog.e("SelfMode: All launchers failed")
                // Report without disconnecting: another launch method may still connect to the
                // server we opened. Reporting hides the "connecting" overlay while leaving that
                // server and its network available for the late arrival. A connection that arrived
                // since acceptance, or one still forming that may be this launch's own arrival,
                // owns the outcome instead. Measured on the 17.4+ route, where a duplicate launch's
                // failure killed the socket the other one had just connected, and where connect
                // returns at once when it joins an attempt already forming.
                val outcome = launchOutcome("No launch method succeeded", mark, settingsRestart)
                if (launchJob !== owner) return@launch
                when (outcome) {
                    is CommManager.LaunchOutcome.Failed -> {
                        // Decided in this Main turn with the ticket and Save checks, after every
                        // attempt that could answer it settled: the server really did not answer.
                        launchPending = false
                        if (path == SelfLaunchPath.HEADUNIT_SERVER) {
                            AppLog.w("SelfMode: Headunit Server (127.0.0.1:5277) is NOT running.")
                            ToastUtils.showToast(
                                service,
                                "Android Auto 17.4+ detected: Please start 'Headunit Server' in Android Auto Developer Settings!",
                                Toast.LENGTH_LONG
                            )
                            openAaSettings()
                        }
                    }
                    is CommManager.LaunchOutcome.Answered -> {
                        AppLog.w("SelfMode: launchers failed but a connection arrived; leaving it alone")
                        settleAnswered()
                    }
                    // Stop or a superseding state revoked it, possibly while it waited: no UI.
                    else -> stopIfCurrent(owner, preserveVpn = true)
                }
                return@launch
            }

            // Report a launch that has not connected yet, without taking anything down: the
            // wireless server and the dummy VPN are what the phone still has to arrive on. See
            // SelfLaunchTimeoutPolicy.
            // A connection can arrive while a launcher is suspended, before the deadline exists,
            // and its live state may never be observed. It already answered this launch; a
            // deadline armed now would be stale, including after Save ended that session.
            if (launchJob !== owner) return@launch
            if (commManager.arrivedSince(mark)) {
                settleAnswered()
                return@launch
            }
            val deadlineMs = SelfLaunchTimeoutPolicy.deadlineMs(path)
            // A child keeps this attempt alive through its response deadline. Save cancellation
            // must also suppress timeout UI after a successful legacy launch intent was sent.
            launchTimeoutJob = launch timeout@{
                delay(deadlineMs)

                if (launchJob !== owner) return@timeout
                if (!commManager.acceptsAppConnectionTicket(ticket)) {
                    stopIfCurrent(owner, preserveVpn = true)
                    return@timeout
                }
                if (settingsRestart != null &&
                    !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) {
                    // Save may already have connected, or another route may own the session.
                    // A child cancellation alone does not retire the parent launch. An arrival
                    // answered it; otherwise retire only its bookkeeping and leave the network to
                    // whatever replaced Save. A live loopback session keeps its Self mode regardless.
                    if (commManager.arrivedSince(mark)) settleAnswered() else stopIfCurrent(owner, preserveVpn = true)
                    return@timeout
                }
                if (!launchPending) return@timeout
                val outcome = launchOutcome("No launch method succeeded (timeout)", mark, settingsRestart)
                if (launchJob !== owner) return@timeout
                when (outcome) {
                    is CommManager.LaunchOutcome.Failed -> {
                        // Decided in this Main turn with the ticket and Save checks. Stop is
                        // accepted on Main too, so none can land before the failure UI.
                        launchPending = false
                        AppLog.e("SelfMode: nothing connected within ${deadlineMs}ms of the launch")
                        handleNeverConnect()
                    }
                    // A connection that arrived since acceptance, or that formed meanwhile and
                    // reached its server, answered this launch: no never-connected UI for it.
                    is CommManager.LaunchOutcome.Answered -> settleAnswered()
                    // Stop or a superseding state revoked it while it waited; its cancellation
                    // relay may still be queued. Retire only the bookkeeping.
                    else -> stopIfCurrent(owner, preserveVpn = true)
                }
            }
        }
        launchJob = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) service.serviceScope.launch(Dispatchers.Main.immediate) {
                // Completion may arrive from IO. Main owns the launch flags and VPN bookkeeping;
                // recheck identity there so delayed cleanup cannot stop a newer manual launch.
                stopIfCurrent(job, preserveVpn = settingsRestart != null &&
                    commManager.connectionState.value !== settingsRestart)
            }
        }
        settingsRestart?.trackSettingsLaunch(job)
        job.start()
    }

    /**
     * Reports this launch's failure if it stands, decided by CommManager from its attempts, its
     * Stop ticket and its Save rather than from a state observed now. An attempt still forming
     * may be this launch's own late arrival, so its outcome is awaited and the question asked
     * again; every wait ends with that attempt settling, so the loop ends. Never Forming.
     */
    private suspend fun launchOutcome(
        msg: String,
        mark: CommManager.LaunchMark,
        settingsRestart: CommManager.ConnectionState.Disconnected?,
    ): CommManager.LaunchOutcome {
        val commManager = App.provide(service).commManager
        while (true) {
            val outcome = commManager.reportLaunchFailure(msg, mark, settingsRestart)
            if (outcome !is CommManager.LaunchOutcome.Forming) return outcome
            commManager.awaitSettled(outcome)
        }
    }

    /**
     * Prepares the VPN requested by Home after launch admission, adopts any retained tunnel,
     * and arms the watchdog that cleans it up if no phone ever arrives. Home owns consent.
     */
    private fun adoptDummyVpn(needsDummyVpn: Boolean) {
        val commManager = App.provide(service).commManager

        // Nothing to adopt where the flavor has no VPN - see VpnControl.
        if (!VpnControl.isVpnAvailable()) return
        // Home carries its offline-VPN requirement with the service command instead of
        // issuing a separate startService request before this admission. Old disconnect
        // cleanup can run between the two UI/service calls; only the accepted launch
        // may issue the token that must survive it. Online/5277 and Save default to false.
        // A new Self launch can precede the previous session's terminal observer. Take over
        // its retained VPN along with the new launch. SESSION excludes
        // OHU from the routes; Self Mode needs activeNetwork for OHU too, so request that
        // configuration before assigning its watchdog. DummyVpnService reuses an equal config.
        if ((needsDummyVpn || service.dummyVpnOwner == DummyVpnPolicy.Owner.SESSION) && VpnControl.isPrepared(service)) {
            VpnControl.startVpn(service, excludeSelf = false)
        }
        service.dummyVpnOwner = DummyVpnPolicy.Owner.SELF_MODE
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = service.serviceScope.launch {
            delay(VPN_TIMEOUT_MS)
            if (!commManager.isConnected) {
                AppLog.w(
                    "SelfMode",
                    "AapService: Self Mode brought the dummy VPN up ${VPN_TIMEOUT_MS}ms " +
                        "ago and no phone arrived. Taking it down so this unit gets its network back."
                )
                service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_NEVER_CONNECTED)
            }
        }
    }

    /**
     * An observed live state settles the launch a physical arrival answered. Only an arrival since
     * acceptance counts: an older session's live state, delivered late, is not this launch's.
     */
    internal fun onConnectionEstablished() {
        val mark = launchMark
        if (!launchPending || mark == null || !App.provide(service).commManager.arrivedSince(mark)) return
        settleAnswered()
    }

    /**
     * A physical arrival answered the current launch, observed or not. Settles it once, from
     * whichever of its outcome checks or the observer gets there first.
     */
    private fun settleAnswered() {
        launchPending = false
        // A connection fulfils manual launches too. Their old deadline must not survive into a
        // later Save and report failure against its disconnected interval.
        launchTimeoutJob?.cancel()
        launchTimeoutJob = null
        // Projection releases the launch's tun once a phone is live (#1073), but the observer may
        // never see that state, or see it only after the session ended behind a later Error.
        // Only a Self Mode owner is released: a session tun is not this launch's.
        service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_SESSION_LIVE)
        // The requested Self session is live: Self mode is that session's now and it keeps this
        // launch's identity. Any other arrival, or one already over, leaves the launch nothing to
        // do; retire it, launchers and all.
        if (!App.provide(service).commManager.isLiveLoopbackSession) stop(wasConnected = true)
    }

    /** Token used by a settings fallback so it cannot retire a newer manual launch. */
    internal fun currentLaunch(): Job? = launchJob

    /**
     * Retires a launch a physical arrival has answered, for the end of the session it produced.
     * A launch still waiting for its phone keeps its deadline and its fallback.
     */
    internal fun retireFulfilledLaunch() {
        val mark = launchMark
        if (launchJob == null || mark == null || !App.provide(service).commManager.arrivedSince(mark)) return
        stop(wasConnected = true)
    }

    internal fun stopIfCurrent(expected: Job?, preserveVpn: Boolean = false) {
        if (launchJob !== expected) return
        // This launch's bookkeeping only. A live loopback session keeps its own Self mode, and
        // a superseding connection, even one still Connecting, its network resources.
        stop(wasConnected = preserveVpn || App.provide(service).commManager.isConnected)
    }

    /** Whether the launchers are still running, for a disconnect deciding what it is looking at. */
    fun isLaunchInFlight(): Boolean = launchInFlight

    fun stopDummyVpnWatchdog() {
        selfModeVpnWatchdog?.cancel()
        selfModeVpnWatchdog = null
    }

    fun handleNeverConnect() {
        AppLog.w("SelfMode: Failed, timed out!")

        SelfLaunchResolveHelper(service).run()
    }

    /**
     * Retires the current launch and cleans up any leftover resources.
     *
     * Call this when the user cancels Self Mode explicitly, or when the service is destroyed
     * while a launch is still pending, so stale launch and watchdog state cannot block a later
     * restart. A live loopback session's own Self mode is not this manager's to end.
     *
     * @param wasConnected when `true` a projection session did reach the handshake, so the
     *        dummy VPN is treated as an ordinary session teardown; when `false` no phone ever
     *        arrived and the VPN is taken down via the Self-Mode-never-connected path.
     */
    fun stop(wasConnected: Boolean = false) {
        detachLaunch()
        if (!launchPending && !launchInFlight && selfModeVpnWatchdog == null) return

        AppLog.i("SelfMode: stopping Self Mode (wasConnected=$wasConnected, launchPending=$launchPending, launchInFlight=$launchInFlight)")
        launchPending = false
        launchInFlight = false
        stopDummyVpnWatchdog()

        // Stop the VPN only when we own it and the session did not already release it.
        // SESSION_ENDED always releases anything we own, so it is the safe choice when a
        // connection already happened; SELF_MODE_NEVER_CONNECTED is the precise reason when
        // we are tearing down a Self Mode that never reached the handshake.
        if (!wasConnected) {
            service.stopDummyVpn(DummyVpnPolicy.Reason.SELF_MODE_NEVER_CONNECTED)
        }
    }

    /** Detach before cancellation, so even immediate completion cleanup cannot own these fields. */
    private fun detachLaunch() {
        val job = launchJob
        launchJob = null
        launchTicket = null
        launchMark = null
        settingsLaunchOwner = null
        job?.cancel()
        launchTimeoutJob?.cancel()
        launchTimeoutJob = null
    }


    companion object {
        /**
         * How long a Self Mode dummy VPN may stay up with no phone before it is taken down.
         *
         * stopWirelessServer() used to do this cleanup by accident, on the next mode change.
         */
        private const val VPN_TIMEOUT_MS = 120_000L

        const val AA_PACKAGE = "com.google.android.projection.gearhead"

        /** Which route the installed Android Auto takes; an unreadable version is LEGACY. */
        fun installedPath(context: Context): SelfLaunchPath {
            val vName = try {
                val name = context.packageManager.getPackageInfo(AA_PACKAGE, 0).versionName
                AppLog.i("SelfMode: Installed AA version: $name")
                name
            } catch (e: Exception) {
                AppLog.w("SelfMode: Failed to query AA version: ${e.message}")
                null
            }
            return SelfLaunchRoutePolicy.pathFor(vName)
        }
    }
}
