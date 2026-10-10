package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.connection.AutoConnectHoldPolicy
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Master class for USB-related connection functionality.
 */
class UsbLauncherManager(val service: AapService) {

    var isRegistered = false
    private lateinit var receiver: UsbReceiver
    private var noUsbServiceLogged = false

    /**
     * Guards against duplicate [UsbAccessoryMode.connectAndSwitch] calls AND duplicate
     * [connectWithRetry] calls for devices already in accessory mode.
     *
     * Set to `true` synchronously on the main thread before launching any background
     * USB connect/switch coroutine. Checked in [checkAlreadyConnected] to prevent
     * multiple concurrent connection attempts on the same device.
     * Cleared when the owning attempt completes. A failed open inside its retry loop
     * does not free the slot; a disconnect may clear it when no launcher job owns it.
     */
    private val isSwitchingToProjection = AtomicBoolean(false)

    private val attemptLock = Any()
    private data class PendingCheck(
        val force: Boolean,
        val userRequested: Boolean,
        val settingsRestart: CommManager.ConnectionState.Disconnected?,
        val recoveryOwner: Any,
        val accessoryOnly: Boolean,
        // The Save came from the running attempt, not from this scan's own request.
        val inheritedSave: Boolean = false,
    )
    private var pendingCheck: PendingCheck? = null
    private var accessorySwitchRequested = false
    private var switchesAtBegin = 0L

    fun isSwitchingToProjection() = this.isSwitchingToProjection.get()

    /**
     * True while `UsbAttachedActivity` is running its own AOA switch, so the attach fallback does
     * not start a second one. Deliberately not folded into [isSwitchingToProjection]: that would
     * also silence the accessory-attach connect and the detach re-sync, which are this path's
     * recovery rather than its competition.
     */
    fun isActivitySwitchInFlight() = UsbSwitchClaim.isLive()

    /** Clears an idle launcher slot; a running job releases its own slot on completion. */
    fun setSwitchingToProjection(value: Boolean) {
        val ended = synchronized(attemptLock) {
            if (value) {
                isSwitchingToProjection.set(true)
                return
            }
            // A Disconnected event can be one failed open inside a still-running retry loop.
            // Only that loop's completion may free its slot or drain a re-enumeration check.
            if (attemptJob != null) return
            isSwitchingToProjection.set(false)
            val claim = attemptClaim
            attemptClaim = null
            claim to pendingCheck
        }
        releaseAttempt(ended.first, ended.second)
    }

    private fun releaseAttempt(claim: ConnectionArbiter.Claim?, pending: PendingCheck?) {
        ConnectionArbiter.release(claim, sessionFormed = false)
        if (pending == null) return
        service.serviceScope.launch {
            if (service.isStopping) return@launch
            val request = synchronized(attemptLock) {
                if (pendingCheck !== pending || isSwitchingToProjection.get()) return@launch
                pendingCheck = null
                pending
            }
            val manager = App.provide(service).commManager
            // Exit, Save and a completed handshake revoke the old recovery interval even if
            // their StateFlow events were skipped. Never let a queued manual flag undo Exit.
            if (cancelledByUser || !manager.ownsUsbRecheck(request.recoveryOwner)) return@launch
            var restart = request.settingsRestart
            if (restart != null && (!restart.acceptsSettingsRestart(manager.connectionState.value) ||
                    SystemClock.elapsedRealtime() >= restart.settingsRestartUntilMs)) {
                // A scan that only inherited an expired Save falls back to ordinary admission.
                if (!request.inheritedSave || restart.isSettingsRestartCancelled) return@launch
                restart = null
            }
            // A switch the activity is still running already sent ACC_REQ_START.
            val accessoryOnly = request.accessoryOnly || isActivitySwitchInFlight()
            // AOA changes UsbDevice identity and permission. Replay the scan, not the old
            // connect(device), so the current device list and permission path are used again.
            AppLog.i("UsbLauncher: replaying the USB scan queued during the attempt (accessoryOnly=$accessoryOnly)")
            checkAlreadyConnected(request.force, request.userRequested, restart, accessoryOnly)
        }
    }

    private fun deferCheckWhileSwitching(force: Boolean, userRequested: Boolean,
        settingsRestart: CommManager.ConnectionState.Disconnected?, recoveryOwner: Any,
        accessoryOnly: Boolean): Boolean =
        synchronized(attemptLock) {
            if (!isSwitchingToProjection.get()) {
                pendingCheck = null // A fresh scan consumes any older queued completion scan.
                return@synchronized false
            }
            val previous = pendingCheck?.takeIf { it.recoveryOwner === recoveryOwner }
            val earlierExplicit = previous?.settingsRestart != null && !previous.inheritedSave
            pendingCheck = PendingCheck(force || previous?.force == true,
                userRequested || previous?.userRequested == true,
                settingsRestart ?: attemptSettingsOwner ?: previous?.settingsRestart, recoveryOwner,
                accessoryOnly && previous?.accessoryOnly != false,
                inheritedSave = settingsRestart == null && attemptSettingsOwner != null && !earlierExplicit)
            true
        }

    @Volatile private var attemptClaim: ConnectionArbiter.Claim? = null

    /** Claims the arbiter and marks an attempt in flight; false means a higher attempt holds it. */
    fun beginAttempt(tier: Tier, what: String, settingsRestart: CommManager.ConnectionState.Disconnected? = null): Boolean {
        if (settingsRestart != null && SystemClock.elapsedRealtime() >= settingsRestart.settingsRestartUntilMs) return false
        val claim = if (settingsRestart == null) ConnectionArbiter.claim(tier, Owner.USB, what)
            else App.provide(service).commManager.claimUsb(tier, what, settingsRestart)
        if (claim == null) return false
        synchronized(attemptLock) {
            attemptClaim = claim
            attemptSettingsOwner = settingsRestart
            accessorySwitchRequested = false
            switchesAtBegin = UsbSwitchClaim.switches()
            isSwitchingToProjection.set(true)
        }
        return true
    }

    /** ACC_REQ_START succeeded, but the old normal-mode device may remain on the bus briefly. */
    internal suspend fun noteAccessorySwitchRequested() {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        synchronized(attemptLock) {
            if (attemptJob === job) accessorySwitchRequested = true
        }
    }

    /** A higher attempt took over: end this one without the status pill's X semantics. */
    fun preemptAttempt() {
        // Keep the owner until completion releases its busy flag and claim. A suspended
        // job finishes cancellation later; replacing its identity here would skip cleanup.
        val job = synchronized(attemptLock) { pendingCheck = null; attemptJob }
        job?.cancel()
    }

    /** The status pill's X during a USB attempt: no automatic USB connection until one is asked for. */
    @Volatile var cancelledByUser = false
        private set

    /** The attempt this manager launched last, so the X can end its retry loop too. */
    internal var attemptJob: Job? = null
    private var attemptSettingsOwner: CommManager.ConnectionState.Disconnected? = null
    private var attemptFinished: CompletableDeferred<Unit>? = null

    fun stopForUser() {
        val job = synchronized(attemptLock) {
            cancelledByUser = true
            pendingCheck = null
            attemptJob
        }
        // Keep the owner until completion releases its busy flag and claim.
        job?.cancel()
    }

    /** An explicit ask for USB. No-op unless the X is holding. */
    fun liftUserCancel(reason: String) {
        if (!cancelledByUser) return
        AppLog.i("UsbLauncher: $reason, so the stop from the status pill is lifted.")
        cancelledByUser = false
    }

    fun register() {
        if (isRegistered)
            return

        isRegistered = true
        receiver = UsbReceiver(UsbLauncherListener(this))

        ContextCompat.registerReceiver(
            service, receiver,
            UsbReceiver.createFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    fun unregister() {
        if (!isRegistered)
            return

        isRegistered = false

        try { service.unregisterReceiver(receiver) } catch (_: Exception) {}
    }

    /**
     * A USB attempt ended with no session. The wireless stack used to clear the pill on its way up
     * or down, and on a cable-only unit it never runs, so the last USB step would stay on screen.
     */
    private fun endUsbAttemptStage() {
        if (App.provide(service).commManager.isConnected) return
        ConnectionStageTracker.clear()
    }

    // Permission dialogs outlive their launch coroutine. Keep Save's original owner until
    // the reply so a cancelled Save cannot return as an unrestricted automatic connection.
    private val permissionOwners = java.util.concurrent.ConcurrentHashMap<String, CommManager.ConnectionState.Disconnected>()

    internal fun takePermissionOwner(device: UsbDevice): CommManager.ConnectionState.Disconnected? =
        permissionOwners.remove(device.deviceName) ?: pendingSettingsRestart()

    private fun requestPermission(device: UsbDevice, settingsRestart: CommManager.ConnectionState.Disconnected? = null) {
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(service)
        if (settingsRestart == null) permissionOwners.remove(device.deviceName)
        else permissionOwners[device.deviceName] = settingsRestart

        AppLog.i("Requesting USB permission for ${UsbDeviceCompat(device).uniqueName}")
        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)

        try {
            ToastUtils.showToast(service, service.getString(R.string.requesting_usb_permission), Toast.LENGTH_SHORT)
            usbManager.requestPermission(device, permissionIntent)
        } catch (e: Exception) {
            AppLog.e("Failed to request USB permission: ${e.message}. This device might not support USB permission dialogs.", e)
            ToastUtils.showToast(service, service.getString(R.string.error_usb_permission_failed), Toast.LENGTH_LONG)
        }
    }

    internal fun hasSettingsAttempt(saved: CommManager.ConnectionState.Disconnected): Boolean =
        synchronized(attemptLock) { attemptSettingsOwner === saved && attemptJob != null }

    /** At the recovery deadline, await only this Save's already-admitted physical work. */
    internal suspend fun awaitSettingsAttempt(saved: CommManager.ConnectionState.Disconnected) {
        while (true) {
            val finished = synchronized(attemptLock) {
                if (attemptSettingsOwner !== saved) return
                attemptFinished ?: return
            }
            // Job.join() may return as soon as completion begins, before an IO completion
            // handler frees the slot. Save's fallback must wait for that handoff as well.
            finished.await()
        }
    }

    internal fun pendingSettingsRestart() = App.provide(service).commManager.pendingUsbSettingsRestart()

    private var staleSteps = 0
    private var staleLastStepAtMs = 0L
    private var staleGaveUpOn: String? = null

    /**
     * A USB handshake ended. A failure on a phone still in accessory mode starts the bounded
     * recovery ladder; a success resets it.
     */
    fun onUsbHandshakeEnded(failure: AapTransport.HandshakeFailure) {
        if (failure == AapTransport.HandshakeFailure.NONE) {
            staleGaveUpOn = null
            staleSteps = 0
            staleLastStepAtMs = 0L
            ConnectionIssues.clear(service, ConnectionIssue.STALE_USB_ACCESSORY)
            return
        }
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return
        val device = usbManager.deviceList.values.firstOrNull { UsbDeviceCompat.isInAccessoryMode(it) }
        val now = SystemClock.elapsedRealtime()
        val steps = StaleAccessoryRecoveryPolicy.stepsInEpisode(
            staleSteps, now - staleLastStepAtMs, staleGaveUpOn, device?.deviceName
        )
        if (steps == 0 && staleGaveUpOn != null) {
            AppLog.i("UsbLauncher: the accessory that recovery gave up on has left the bus; recovery can run again")
            staleGaveUpOn = null
            // The next plug-in starts a new episode; old step counts would give it up at once.
            staleSteps = 0
            staleLastStepAtMs = 0L
        }
        val step = StaleAccessoryRecoveryPolicy.decide(failure, device != null, cancelledByUser, steps)
        if (step == StaleAccessoryRecoveryPolicy.Step.GIVE_UP) staleGaveUpOn = device?.deviceName
        val name = device?.let { UsbDeviceCompat(it).uniqueName } ?: "(none)"
        when (step) {
            StaleAccessoryRecoveryPolicy.Step.NONE -> {
                if (cancelledByUser) {
                    AppLog.i("UsbLauncher: stale accessory recovery for $name refused: the status pill's X holds USB")
                } else if (device != null) {
                    AppLog.i("UsbLauncher: handshake failed ($failure) on accessory $name; " +
                        "not a stale-accessory failure, no recovery")
                }
            }
            StaleAccessoryRecoveryPolicy.Step.GIVE_UP -> {
                AppLog.i("UsbLauncher: stale accessory $name: recovery used both steps; a replug is needed")
                // The 3 s reconnect check fails here again and again; a moved stamp would defeat a dismissal.
                ConnectionIssues.raiseOnce(service, ConnectionIssue.STALE_USB_ACCESSORY)
            }
            else -> {
                if (device == null) return
                if (isSwitchingToProjection()) {
                    AppLog.i("UsbLauncher: stale accessory recovery for $name refused: the USB attempt slot is busy")
                    return
                }
                if (!beginAttempt(Tier.USB, "USB recovery of $name")) {
                    AppLog.i("UsbLauncher: stale accessory recovery for $name refused: another attempt holds the arbiter")
                    return
                }
                staleSteps = steps + 1
                staleLastStepAtMs = now
                AppLog.i("UsbLauncher: stale accessory $name: handshake failed ($failure), " +
                    "step $staleSteps of ${StaleAccessoryRecoveryPolicy.MAX_STEPS}: $step")
                runRecoveryStep(usbManager, device, name, step)
            }
        }
    }

    /** One job, so its completion frees the slot and claim and replays the queued scan. */
    private fun runRecoveryStep(
        usbManager: UsbManager,
        device: UsbDevice,
        name: String,
        step: StaleAccessoryRecoveryPolicy.Step,
    ) {
        val app = App.provide(service)
        val commManager = app.commManager
        val usbMode = UsbAccessoryMode(usbManager)
        launchAttempt(null, Dispatchers.IO) {
            commManager.awaitDisconnectComplete()
            ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
            val before = usbManager.deviceList.keys.toSet()
            val issued = if (step == StaleAccessoryRecoveryPolicy.Step.RESWITCH) {
                usbMode.connectAndSwitch(device, app.settings.useLibusb).also { if (it) noteAccessorySwitchRequested() }
            } else {
                usbMode.resetDevice(device)
            }
            val startedAt = System.nanoTime() / 1_000_000L
            var observation = if (issued) StaleAccessoryRecoveryPolicy.Observation.WAITING
                else StaleAccessoryRecoveryPolicy.Observation.NO_CHANGE
            while (observation == StaleAccessoryRecoveryPolicy.Observation.WAITING) {
                delay(100)
                val list = usbManager.deviceList
                val reattached = list.any { (key, d) ->
                    key !in before && UsbDeviceCompat.isConnectable(service, d)
                }
                val elapsed = System.nanoTime() / 1_000_000L - startedAt
                observation = StaleAccessoryRecoveryPolicy.observe(
                    !list.containsKey(device.deviceName), reattached, elapsed
                )
            }
            val elapsed = System.nanoTime() / 1_000_000L - startedAt
            when (observation) {
                StaleAccessoryRecoveryPolicy.Observation.REENUMERATED ->
                    AppLog.i("UsbLauncher: stale accessory $name: $step re-enumerated the phone in ${elapsed}ms")
                StaleAccessoryRecoveryPolicy.Observation.NO_CHANGE ->
                    AppLog.i("UsbLauncher: stale accessory $name: no re-enumeration within ${elapsed}ms " +
                        "of $step; trying the handshake once more")
                else ->
                    AppLog.i("UsbLauncher: stale accessory $name: the phone left the bus after $step " +
                        "and did not come back")
            }
            when (StaleAccessoryRecoveryPolicy.afterObservation(observation)) {
                // The slot is busy, so this queues; the completion replays it once.
                StaleAccessoryRecoveryPolicy.FollowUp.RECONNECT_ON_ATTACH ->
                    deferCheckWhileSwitching(force = true, userRequested = false, settingsRestart = null,
                        recoveryOwner = commManager.usbRecheckOwner(), accessoryOnly = false)
                StaleAccessoryRecoveryPolicy.FollowUp.RETRY_HANDSHAKE ->
                    connectWithRetry(device, maxRetries = 0)
                StaleAccessoryRecoveryPolicy.FollowUp.STOP -> Unit
            }
        }
    }

    /** Save may wait for transport retirement; an X pressed meanwhile remains authoritative. */
    fun restartForSettings(state: CommManager.ConnectionState.Disconnected) {
        if (!cancelledByUser) checkAlreadyConnected(force = true, settingsRestart = state)
    }

    /** A scan sent to the service, such as the attach screen's hand-off. An open Save still owns it. */
    fun checkOnRequest(userRequested: Boolean) = checkAlreadyConnected(
        force = true,
        userRequested = userRequested,
        settingsRestart = if (userRequested) null else pendingSettingsRestart(),
    )

    /**
     * Scans currently connected USB devices and connects to any that are already in
     * Android Open Accessory (AOA) mode, or attempts to switch a known device into AOA mode.
     *
     * @param force When `true`, bypasses the [autoConnectLastSession] guard. Use `true` when
     *              called in response to an actual USB attach event or from [UsbAttachedActivity],
     *              because the user has explicitly plugged in a device. Use `false` (default)
     *              for the startup scan in [onCreate].
     * @param userRequested The user asked for this by hand, which lifts the status pill's X.
     * @param accessoryOnly A completed AOA switch already sent ACC_REQ_START. Its deferred scan
     *                      may connect the new accessory, but must not switch the old device again.
     */
    fun checkAlreadyConnected(
        force: Boolean = false,
        userRequested: Boolean = false,
        settingsRestart: CommManager.ConnectionState.Disconnected? = null,
        accessoryOnly: Boolean = false,
    ) {
        val settings = App.provide(service).settings
        val commManager = App.provide(service).commManager
        if (settingsRestart != null && !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) return
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        // A new button press may lift an earlier X even while cancelled work is unwinding.
        // A later X clears the pending request again before it can replay.
        if (userRequested) liftUserCancel("a USB connection was asked for")
        if (commManager.isConnected) {
            commManager.deferUsbCheckForConnectionAttempt()
            return
        }
        if (deferCheckWhileSwitching(force, userRequested, settingsRestart, commManager.usbRecheckOwner(), accessoryOnly)) return
        if (commManager.deferUsbCheckForConnectionAttempt()) return

        val tier = if (userRequested) Tier.USER else Tier.USB
        when (AutoConnectHoldPolicy.decide(
            settingsVisible = SettingsActivity.isVisible,
            sessionLive = false, // Returned above.
            cancelledByUser = cancelledByUser,
            userRequested = userRequested,
            // Save already requested this reconnection. Keep the ordinary cancel check and
            // foreground policy; only the settings-screen hold is lifted for this scan.
            settingsRestart = settingsRestart != null,
        )) {
            AutoConnectHoldPolicy.Verdict.HOLD_FOR_SETTINGS -> {
                AppLog.i("UsbLauncher: USB auto-connect held while the settings screen is open; " +
                    "checking again when it closes.")
                service.holdUsbCheckForSettings()
                return
            }
            AutoConnectHoldPolicy.Verdict.CANCELLED_BY_USER -> {
                AppLog.i("UsbLauncher: USB auto-connect refused: the status pill's X holds it " +
                    "until the USB button.")
                return
            }
            AutoConnectHoldPolicy.Verdict.PROCEED -> Unit
        }

        val usbManager = UsbDeviceCompat.usbManager(service) ?: run {
            if (!noUsbServiceLogged) AppLog.i("UsbLauncher: this unit has no USB service; USB connections are unavailable")
            noUsbServiceLogged = true
            return
        }
        UsbDeviceDiagnostics.logDeviceList(service, usbManager, "service scan (force=$force)")
        val deviceList = usbManager.deviceList.values.filter { UsbDeviceCompat.isConnectable(service, it) }

        // Check for devices already in accessory mode first.
        // After AOA switch the device re-enumerates and appears as a new USB device — we must
        // request permission for this new device before openDevice(), or SecurityException occurs.
        for (device in deviceList) {
            if (UsbDeviceCompat.isInAccessoryMode(device)) {
                val deviceName = UsbDeviceCompat(device).uniqueName
                AppLog.i("Found device already in accessory mode: $deviceName")
                if (!beginAttempt(tier, "USB $deviceName", settingsRestart)) return
                ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                launchAttempt(settingsRestart) {
                    if (awaitAccessoryPermission(usbManager, device, deviceName, settingsRestart)) {
                        connectWithRetry(device, tier = tier, settingsRestart = settingsRestart)
                    }
                }
                return
            }
        }

        // ACC_REQ_START can return before USB detach/attach updates deviceList. A successful
        // switch's pending scan may connect an accessory already visible, but must wait for the
        // attach event if only the old normal-mode device remains. No second switch is sent.
        if (accessoryOnly) return

        // Last-session mode: reconnect to a known/allowed device
        if (lastSession) {
            for (device in deviceList) {
                val deviceCompat = UsbDeviceCompat(device)
                if (settings.isConnectingDevice(deviceCompat)) {
                    if (usbManager.hasPermission(device)) {
                        AppLog.i("Found known USB device with permission: ${deviceCompat.uniqueName}. Switching to accessory mode.")
                        if (!beginAttempt(tier, "USB switch of ${deviceCompat.uniqueName}", settingsRestart)) return
                        ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
                        val usbMode = UsbAccessoryMode(usbManager)
                        launchAttempt(settingsRestart, Dispatchers.IO) {
                            if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                                noteAccessorySwitchRequested()
                                AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                            } else {
                                AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestPermission(device, settingsRestart)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleConnect(nonAccessoryDevices[0], tier, settingsRestart)
                return
            }
        }

        // Single-USB mode: connect if there's exactly one candidate device.
        // If the user has marked specific devices as "Allowed" in the USB list,
        // only count those — so non-AA peripherals (dashcams, USB audio, etc.)
        // don't prevent auto-connect. Falls back to counting all devices when
        // no devices have been explicitly allowed (fresh install).
        if (singleUsb) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowed = settings.allowedDevices
            val candidates = if (allowed.isNotEmpty()) {
                nonAccessoryDevices.filter { allowed.contains(UsbDeviceCompat(it).uniqueName) }
            } else {
                nonAccessoryDevices
            }
            if (allowed.isNotEmpty() && candidates.size != nonAccessoryDevices.size) {
                AppLog.i("Single USB auto-connect: ${nonAccessoryDevices.size} USB device(s) present, ${candidates.size} allowed")
            }
            if (candidates.size == 1) {
                performSingleConnect(candidates[0], tier, settingsRestart)
                return
            }
        }

        // Fallback: if force=true and exactly one Android phone is attached in normal mode,
        // switch it to accessory mode. This handles cases where UsbAttachedActivity didn't fire.
        //
        // [BUG_FIX] This used to require vendor id 0x18D1, so it fired for a Pixel and for nothing
        // else; every other make reached the end of this function having done nothing and could
        // only be connected by hand. deviceList is already isAndroidDevice()-filtered.
        if (force) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            val allowList = settings.allowedDevices
            val candidates = nonAccessoryDevices.filter {
                UsbAttachPolicy.shouldAttemptAoaSwitch(
                    isGoogleVendor = it.vendorId == 0x18D1,
                    autoStartOnUsb = usbAutoStart,
                    allowListConfigured = allowList.isNotEmpty(),
                    deviceAllowed = settings.isConnectingDevice(UsbDeviceCompat(it)),
                )
            }
            if (candidates.size == 1) {
                AppLog.i("Fallback: force=true and found single normal-mode Android device ${UsbDeviceCompat(candidates[0]).uniqueName}. Switching to accessory mode.")
                performSingleConnect(candidates[0], tier, settingsRestart)
            } else if (candidates.isNotEmpty()) {
                AppLog.i("Fallback: force=true but ${candidates.size} candidate USB devices are attached; not guessing which is the phone")
            }
        }
    }

    /** Bind before dispatch so revoking Save also cancels a queued permission wait or retry. */
    internal fun launchAttempt(
        settingsRestart: CommManager.ConnectionState.Disconnected?,
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.() -> Unit,
    ) {
        val claim = attemptClaim
        val job = service.serviceScope.launch(context, start = CoroutineStart.LAZY) {
            val manager = App.provide(service).commManager
            if (settingsRestart == null || (settingsRestart.acceptsSettingsRestart(manager.connectionState.value) &&
                SystemClock.elapsedRealtime() < settingsRestart.settingsRestartUntilMs)) block()
        }
        val finished = CompletableDeferred<Unit>()
        synchronized(attemptLock) {
            attemptSettingsOwner = settingsRestart
            attemptJob = job
            attemptFinished = finished
        }
        job.invokeOnCompletion {
            try {
                // Cancellation may run before the body starts. Release only this attempt's claim;
                // an old completion must not clear a replacement USB attempt's busy flag.
                val pending = synchronized(attemptLock) {
                    if (attemptJob !== job || attemptClaim !== claim) return@invokeOnCompletion
                    attemptJob = null
                    attemptSettingsOwner = null
                    attemptFinished = null
                    attemptClaim = null
                    isSwitchingToProjection.set(false)
                    if (accessorySwitchRequested || UsbSwitchClaim.switches() != switchesAtBegin) {
                        pendingCheck = pendingCheck?.copy(accessoryOnly = true)
                    }
                    accessorySwitchRequested = false
                    pendingCheck
                }
                endUsbAttemptStage()
                releaseAttempt(claim, pending)
            } finally { finished.complete(Unit) }
        }
        settingsRestart?.trackSettingsLaunch(job)
        job.start()
    }

    private fun performSingleConnect(device: UsbDevice, tier: Tier, settingsRestart: CommManager.ConnectionState.Disconnected? = null) {
        val settings = App.provide(service).settings
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return

        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            if (!beginAttempt(tier, "USB switch of $deviceName", settingsRestart)) return
            val usbMode = UsbAccessoryMode(usbManager)
            launchAttempt(settingsRestart, Dispatchers.IO) {
                if (usbMode.connectAndSwitch(device, settings.useLibusb)) {
                    noteAccessorySwitchRequested()
                    AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                } else {
                    AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestPermission(device, settingsRestart)
        }
    }

    /**
     * Wait briefly for the manifest auto-grant on a freshly re-enumerated 0x2D00 before falling
     * back to a dialog. The grant arrives with the activity the system launches for the attach, so
     * the service's own receiver reaches this a few hundred ms early and used to raise a prompt the
     * dongle did not stay in accessory mode long enough to answer.
     */
    private suspend fun awaitAccessoryPermission(
        usbManager: UsbManager,
        device: UsbDevice,
        deviceName: String,
        settingsRestart: CommManager.ConnectionState.Disconnected?,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true

        val startedAt = System.currentTimeMillis()
        while (UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(System.currentTimeMillis() - startedAt)) {
            delay(UsbAccessoryHandoffPolicy.PERMISSION_POLL_INTERVAL_MS)
            if (usbManager.hasPermission(device)) {
                AppLog.i("Accessory-mode permission arrived after ${System.currentTimeMillis() - startedAt}ms: $deviceName")
                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                return true
            }
        }

        AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
        requestPermission(device, settingsRestart)
        return false
    }

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     */
    suspend fun connectWithRetry(
        device: UsbDevice,
        maxRetries: Int = 3,
        tier: Tier = Tier.USB,
        settingsRestart: CommManager.ConnectionState.Disconnected? = null,
    ) {
        val commManager = App.provide(service).commManager
        var retryCount = 0
        var success = false

        while (retryCount <= maxRetries && !success) {
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                // A USB reattach during the delay could have already started a new connection;
                // bail out to avoid two parallel retry loops competing on the same device.
                if (commManager.isConnected ||
                    commManager.connectionState.value is CommManager.ConnectionState.Connecting) return
            }
            if (settingsRestart != null && !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) return
            val currentDevice = if (settingsRestart == null) device else {
                if (SystemClock.elapsedRealtime() >= settingsRestart.settingsRestartUntilMs) return
                val usbManager = UsbDeviceCompat.usbManager(service) ?: return
                // AOA re-enumeration changes UsbDevice and permission even for the same phone.
                // Prefer the same bus entry; accept a unique identity match, never guess between
                // two identical devices. If it vanished, the next attach owns the retry.
                val matches = usbManager.deviceList.values.filter {
                    UsbDeviceCompat.isInAccessoryMode(it) && UsbDeviceCompat.isConnectable(service, it) &&
                        UsbDeviceCompat.getUniqueName(it) == UsbDeviceCompat.getUniqueName(device)
                }
                val fresh = matches.firstOrNull { it.deviceName == device.deviceName }
                    ?: matches.singleOrNull() ?: return
                if (!awaitAccessoryPermission(usbManager, fresh, UsbDeviceCompat.getUniqueName(fresh), settingsRestart)) return
                if (SystemClock.elapsedRealtime() >= settingsRestart.settingsRestartUntilMs ||
                    !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) return
                fresh
            }
            commManager.connect(currentDevice, tier, expectedState = settingsRestart)
            success = commManager.connectionState.value is CommManager.ConnectionState.Connected
            retryCount++
        }
    }


    companion object {

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        const val ATTACH_FALLBACK_DELAY_MS = 2000L
    }
}
