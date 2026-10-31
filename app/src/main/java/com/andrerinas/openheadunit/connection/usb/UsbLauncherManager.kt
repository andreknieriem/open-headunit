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
import com.andrerinas.openheadunit.connection.UsbRecoveryAdmission
import com.andrerinas.openheadunit.connection.ConnectionAdmission
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.UsbRecheck
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.connection.ConnectionStageTracker
import com.andrerinas.openheadunit.main.SettingsActivity
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.ConnectionIssue
import com.andrerinas.openheadunit.utils.ConnectionIssues
import com.andrerinas.openheadunit.utils.ToastUtils
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
        // The owed scan this replay asks for; its own validity carries the Stop ticket.
        val recheck: UsbRecheck,
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
            val request = attemptRequest
            attemptRequest = null
            request to pendingCheck
        }
        releaseAttempt(ended.first, ended.second)
    }

    // Outside attemptLock: ending a request takes transportLifecycleLock.
    private fun releaseAttempt(request: CommManager.ConnectionRequest?, pending: PendingCheck?) {
        if (request != null) App.provide(service).commManager.endRequest(request)
        if (pending == null) return
        service.serviceScope.launch {
            if (service.isStopping) return@launch
            val queued = synchronized(attemptLock) {
                if (pendingCheck !== pending || isSwitchingToProjection.get()) return@launch
                pendingCheck = null
                pending
            }
            val manager = App.provide(service).commManager
            // Exit, Save and a completed handshake revoke the old recovery interval even if
            // their StateFlow events were skipped. Never let a queued manual flag undo Exit.
            if (cancelledByUser || !manager.ownsUsbRecheck(queued.recoveryOwner) ||
                !manager.isCurrent(queued.recheck)) return@launch
            var restart = queued.settingsRestart
            if (restart != null && (!restart.acceptsSettingsRestart(manager.connectionState.value) ||
                    SystemClock.elapsedRealtime() >= restart.settingsRestartUntilMs)) {
                // A scan that only inherited an expired Save falls back to ordinary admission.
                if (!queued.inheritedSave || restart.isSettingsRestartCancelled) return@launch
                restart = null
            }
            // A switch the activity is still running already sent ACC_REQ_START.
            val accessoryOnly = queued.accessoryOnly || isActivitySwitchInFlight()
            // AOA changes UsbDevice identity and permission. Replay the scan, not the old
            // connect(device), so the current device list and permission path are used again.
            AppLog.i("UsbLauncher: replaying the USB scan queued during the attempt (accessoryOnly=$accessoryOnly)")
            checkAlreadyConnected(queued.force, queued.userRequested, restart, accessoryOnly, recheck = queued.recheck)
        }
    }

    private fun deferCheckWhileSwitching(force: Boolean, userRequested: Boolean,
        settingsRestart: CommManager.ConnectionState.Disconnected?, recoveryOwner: Any,
        accessoryOnly: Boolean, recheck: UsbRecheck): Boolean =
        synchronized(attemptLock) {
            if (!isSwitchingToProjection.get()) {
                pendingCheck = null // A fresh scan consumes any older queued completion scan.
                return@synchronized false
            }
            val previous = pendingCheck?.takeIf { it.recoveryOwner === recoveryOwner }
            val earlierExplicit = previous?.settingsRestart != null && !previous.inheritedSave
            val attemptSave = attemptRequest?.save
            pendingCheck = PendingCheck(force || previous?.force == true,
                userRequested || previous?.userRequested == true,
                settingsRestart ?: attemptSave ?: previous?.settingsRestart, recoveryOwner,
                accessoryOnly && previous?.accessoryOnly != false, recheck,
                inheritedSave = settingsRestart == null && attemptSave != null && !earlierExplicit)
            true
        }

    /**
     * The one logical USB request this launcher runs: admitted once by [beginAttempt], carried by
     * its job through every permission wait, retry and recovery step, and ended by that job's
     * completion. Guarded by attemptLock; never validated while attemptLock is held, because
     * validity takes transportLifecycleLock (lifecycle -> attemptLock is the only order).
     */
    private var attemptRequest: CommManager.ConnectionRequest? = null

    /**
     * Admits and marks a USB attempt only while the owed scan [recheck] it runs for is current and
     * its Save, if any, still owns the state. Runs where its entry already ran, on Main.
     */
    fun beginAttempt(tier: Tier, what: String, settingsRestart: CommManager.ConnectionState.Disconnected?,
        recheck: UsbRecheck): Boolean {
        val manager = App.provide(service).commManager
        discardCancelledAttempt(manager)
        if (settingsRestart != null && SystemClock.elapsedRealtime() >= settingsRestart.settingsRestartUntilMs) return false
        val request = manager.admitUsbRequest(tier, what, settingsRestart, recheck) ?: return false
        synchronized(attemptLock) {
            attemptRequest = request
            accessorySwitchRequested = false
            switchesAtBegin = UsbSwitchClaim.switches()
            isSwitchingToProjection.set(true)
        }
        return true
    }

    /** A request Stop revoked may still be queued on IO. A fresh USB input need not wait for it. */
    private fun discardCancelledAttempt(manager: CommManager) {
        val request = synchronized(attemptLock) { attemptRequest } ?: return
        // The slot's Stop boundary: while a try is being torn down its request is briefly invalid
        // until that try returns it to unpublished, so the request's own ticket decides here.
        if (manager.acceptsAppConnectionTicket(request.ticket)) return
        val job = synchronized(attemptLock) {
            if (attemptRequest !== request) return
            val owned = attemptJob
            attemptRequest = null
            attemptJob = null
            attemptFinished = null
            accessorySwitchRequested = false
            pendingCheck = null
            isSwitchingToProjection.set(false)
            owned
        }
        job?.cancel()
        manager.endRequest(request)
    }

    internal fun switchAdmission(request: CommManager.ConnectionRequest): ConnectionAdmission {
        val commManager = App.provide(service).commManager
        return ConnectionAdmission { effect -> commManager.runIfValid(request, effect) }
    }

    /** ACC_REQ_START succeeded, but the old normal-mode device may remain on the bus briefly. */
    internal suspend fun noteAccessorySwitchRequested() {
        val job = kotlinx.coroutines.currentCoroutineContext()[Job]
        synchronized(attemptLock) {
            if (attemptJob === job) accessorySwitchRequested = true
        }
    }

    /** A higher attempt took over: end this one without the status pill's X semantics. */
    fun preemptAttempt(loser: ConnectionArbiter.Claim) {
        // Keep the owner until completion releases its busy flag and request. A suspended
        // job finishes cancellation later; replacing its identity here would skip cleanup.
        val job = synchronized(attemptLock) {
            if (attemptRequest?.claim !== loser) null else {
                pendingCheck = null
                attemptJob
            }
        }
        job?.cancel()
    }

    /** The status pill's X during a USB attempt: no automatic USB connection until one is asked for. */
    @Volatile var cancelledByUser = false
        private set

    /** The attempt this manager launched last, so the X can end its retry loop too. */
    internal var attemptJob: Job? = null
    private var attemptFinished: CompletableDeferred<Unit>? = null

    fun stopForUser() {
        val job = synchronized(attemptLock) {
            cancelledByUser = true
            pendingCheck = null
            attemptJob
        }
        // Keep the owner until completion releases its busy flag and request.
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
        ContextCompat.registerReceiver(service, receiver, UsbReceiver.createPermissionResultFilter(),
            ContextCompat.RECEIVER_NOT_EXPORTED)
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

    /**
     * A permission dialog the launcher raised outlives its coroutine. It keeps its Save and the
     * owed scan it asks for, under a token its PendingIntent carries back: the reply continues
     * exactly that scan, and never mints a new one. Dialogs stack, so every dialog keeps its own
     * entry until its own reply; an entry whose scan was withdrawn answers nothing and is dropped
     * when the next dialog is raised, so the map holds only dialogs that can still act.
     */
    internal data class PermissionAttempt(
        val deviceName: String,
        val settingsRestart: CommManager.ConnectionState.Disconnected?,
        val recheck: UsbRecheck,
    )
    private val permissionOwners = java.util.concurrent.ConcurrentHashMap<String, PermissionAttempt>()

    /**
     * The scan a permission reply continues: always the [recheck] its dialog was raised for, never
     * one minted at the reply. A reply carrying a launcher [token] continues exactly that request's
     * entry (with its Save), or nothing once that entry is gone. A reply without one answers a
     * dialog raised outside the launcher (the attach screen, the USB list, the home screen) with
     * the scan its producer minted when it raised the dialog, and no Save: its input carried none.
     */
    internal fun takePermissionAttempt(device: UsbDevice, recheck: UsbRecheck, token: String?): PermissionAttempt? {
        if (token == null) return PermissionAttempt(device.deviceName, null, recheck)
        val owner = permissionOwners.remove(token) ?: return null
        return owner.takeIf {
            it.deviceName == device.deviceName && it.recheck.ticket == recheck.ticket && it.recheck.epoch == recheck.epoch
        }
    }

    private fun requestPermission(device: UsbDevice, settingsRestart: CommManager.ConnectionState.Disconnected?,
        recheck: UsbRecheck) {
        val commManager = App.provide(service).commManager
        if (!commManager.isCurrent(recheck)) return
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return
        val token = java.util.UUID.randomUUID().toString()
        permissionOwners.entries.removeAll { !commManager.isCurrent(it.value.recheck) }
        permissionOwners[token] = PermissionAttempt(device.deviceName, settingsRestart, recheck)
        val permissionIntent = UsbReceiver.createPermissionPendingIntent(service, recheck, token)

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
        synchronized(attemptLock) { attemptRequest?.save === saved && attemptJob != null }

    /** At the recovery deadline, await only this Save's already-admitted physical work. */
    internal suspend fun awaitSettingsAttempt(saved: CommManager.ConnectionState.Disconnected) {
        while (true) {
            val finished = synchronized(attemptLock) {
                if (attemptRequest?.save !== saved) return
                attemptFinished ?: return
            }
            // Job.join() may return as soon as completion begins, before an IO completion
            // handler frees the slot. Save's fallback must wait for that handoff as well.
            finished.await()
        }
    }

    internal fun pendingSettingsRestart() = App.provide(service).commManager.pendingUsbSettingsRestart()

    private data class RecoveryObservation(
        val deviceName: String, val vendorId: Int, val productId: Int,
        val bus: StaleAccessoryRecoveryPolicy.BusObservation,
        @Volatile var target: UsbRecoveryTarget? = null,
    )
    private val recoveryObservation = AtomicReference<RecoveryObservation?>(null)

    internal fun noteRecoveryDeviceDetached(device: UsbDevice) {
        val observation = recoveryObservation.get() ?: return
        observation.target?.let { target ->
            target.noteDetach(device.deviceName, device.vendorId, device.productId)
            if (!target.canFollowEnumeration && !target.canRetryUnchangedWithoutIdentity) observation.bus.noteDetach()
        }
        // An unrelated peripheral does not imply re-enumeration. A relevant companion's
        // detach does invalidate identity confidence, including between snapshot checks.
        if (device.deviceName == observation.deviceName && device.vendorId == observation.vendorId &&
            device.productId == observation.productId) observation.bus.noteDetach()
    }

    internal fun describeRecoveryDevice(device: UsbDevice): UsbRecoveryDevice = UsbRecoveryDevice.capture(device)

    internal fun recoveryNowMs(): Long = System.nanoTime() / 1_000_000L

    internal fun recoveryPermissionIntent(token: String): android.app.PendingIntent =
        UsbReceiver.createRecoveryPermissionPendingIntent(service, token)

    private data class RecoveryPermission(val token: String, val path: String,
        val result: CompletableDeferred<Boolean> = CompletableDeferred())
    private val recoveryPermission = AtomicReference<RecoveryPermission?>(null)

    internal fun onRecoveryPermission(token: String, granted: Boolean, device: UsbDevice) {
        val pending = recoveryPermission.get() ?: return
        if (pending.token == token && pending.path == device.deviceName) pending.result.complete(granted)
    }

    private enum class RecoveryPermissionResult { GRANTED, REJECTED, LOST_CANDIDATE }

    /** Permission results only wake the owning recovery; they never launch a general scan. */
    private suspend fun awaitRecoveryPermission(manager: UsbManager, device: UsbDevice,
        deadline: Long, usbRequest: CommManager.ConnectionRequest, stillOwned: () -> Boolean): RecoveryPermissionResult {
        if (manager.hasPermission(device)) return RecoveryPermissionResult.GRANTED
        val request = RecoveryPermission(java.util.UUID.randomUUID().toString(), device.deviceName)
        if (!App.provide(service).commManager.runIfValid(usbRequest) { recoveryPermission.set(request) }) {
            return RecoveryPermissionResult.LOST_CANDIDATE
        }
        var intent: android.app.PendingIntent? = null
        try {
            while (SettingsActivity.isVisible && recoveryNowMs() < deadline && stillOwned()) delay(100)
            if (SettingsActivity.isVisible || recoveryNowMs() >= deadline || !stillOwned()) return RecoveryPermissionResult.LOST_CANDIDATE
            intent = recoveryPermissionIntent(request.token)
            manager.requestPermission(device, intent)
            while (recoveryNowMs() < deadline && stillOwned()) {
                if (request.result.isCompleted) {
                    if (!request.result.await()) return RecoveryPermissionResult.REJECTED
                    return if (manager.hasPermission(device)) RecoveryPermissionResult.GRANTED
                        else RecoveryPermissionResult.LOST_CANDIDATE
                }
                if (manager.hasPermission(device)) return RecoveryPermissionResult.GRANTED // Manifest auto-grant without a reply.
                delay(100)
            }
            // A denial delivered before candidate invalidation still ends this recovery.
            if (request.result.isCompleted && !request.result.await()) return RecoveryPermissionResult.REJECTED
            return RecoveryPermissionResult.LOST_CANDIDATE
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            AppLog.w("UsbLauncher: recovery permission request failed: ${error.message}")
            return RecoveryPermissionResult.REJECTED
        } finally {
            recoveryPermission.compareAndSet(request, null)
            intent?.cancel()
        }
    }

    /**
     * Poll for only the failed accessory, including when a ROM omits attach notifications.
     * The recovery's own request remains valid through selection and permission; a newer one wins.
     */
    private suspend fun reconnectRecoveryTarget(manager: UsbManager, target: UsbRecoveryTarget,
        owner: Any, usbRequest: CommManager.ConnectionRequest, deadline: Long) {
        if (!target.canFollowEnumeration) {
            AppLog.i("UsbLauncher: recovery target has missing or ambiguous identity; not selecting another accessory")
            return
        }
        val comm = App.provide(service).commManager
        val episodeObservation = recoveryObservation.get()
        fun owned() = !cancelledByUser && !service.isStopping &&
            comm.ownsUsbRecheck(owner) && comm.isValid(usbRequest)
        fun select(): UsbDevice? {
            val list = manager.deviceList.values.toList()
            // Binder/descriptor reads must not hold the identity monitor used by detach.
            val permissions = list.associate { it.deviceName to manager.hasPermission(it) }
            val chosen = target.select(list.map(::describeRecoveryDevice)) { permissions[it] == true } ?: return null
            return list.singleOrNull { it.deviceName == chosen.path }?.takeIf {
                UsbDeviceCompat.isInAccessoryMode(it) && UsbDeviceCompat.isConnectable(service, it)
            }
        }
        while (recoveryNowMs() < deadline && owned()) {
            if (SettingsActivity.isVisible) { delay(100); continue }
            val candidate = select()
            if (candidate == null) { delay(100); continue }
            var identity = describeRecoveryDevice(candidate)
            target.retainKnownFields(identity)
            val bus = StaleAccessoryRecoveryPolicy.BusObservation()
            val observation = RecoveryObservation(candidate.deviceName, candidate.vendorId, candidate.productId, bus, target)
            // Arm before permission and re-read after arming, so no cached selection can cross
            // a detach. A late detach for a reused path is conservatively treated as invalidation.
            if (!comm.runIfValid(usbRequest) { recoveryObservation.set(observation) }) return
            try {
                fun current(): UsbDevice? = select()?.takeIf { identity.agreesWith(describeRecoveryDevice(it)) }
                if (current() == null) continue
                when (awaitRecoveryPermission(manager, candidate, deadline, usbRequest) {
                    owned() && !bus.hasDetached && current() != null
                }) {
                    RecoveryPermissionResult.REJECTED -> return // Do not retry a user's denial.
                    RecoveryPermissionResult.LOST_CANDIDATE -> {
                        // A disappearing enumeration may return. Conflicting known identity
                        // at its address is not permission to adopt a different device.
                        val atPath = manager.deviceList[candidate.deviceName]
                        if (atPath != null && identity.conflictsWith(describeRecoveryDevice(atPath))) return
                        continue
                    }
                    RecoveryPermissionResult.GRANTED -> Unit
                }
                // Permission may reveal a serial hidden on Android 10+. Pin that additional
                // evidence too, so a later same-path replacement cannot discard it again.
                val fresh = current()
                if (fresh == null) {
                    val atPath = manager.deviceList[candidate.deviceName]
                    if (atPath != null && identity.conflictsWith(describeRecoveryDevice(atPath))) return
                    continue
                }
                identity = describeRecoveryDevice(fresh)
                target.retainKnownFields(identity)
                while (SettingsActivity.isVisible && owned() && recoveryNowMs() < deadline) delay(100)
                if (SettingsActivity.isVisible || recoveryNowMs() >= deadline) return
                if (!owned()) return
                if (bus.hasDetached || !manager.hasPermission(fresh)) continue
                val identityAdmission = target.guard(bus)
                val admission = object : UsbRecoveryAdmission {
                    override fun resolve(manager: UsbManager): UsbDevice? {
                        val device = current()?.takeIf { manager.hasPermission(it) }
                        if (device == null) bus.noteDetach()
                        return device
                    }
                    override fun run(action: () -> Unit): Boolean {
                        if (cancelledByUser || service.isStopping || SettingsActivity.isVisible ||
                            recoveryNowMs() >= deadline) return false
                        // CommManager checks the recovery owner and the request's own validity
                        // atomically with each publication.
                        return identityAdmission.run(action)
                    }
                }
                AppLog.i("UsbLauncher: targeted recovery found ${fresh.deviceName}; reconnecting the matching accessory")
                comm.reconnectUsb(fresh, owner, usbRequest, admission)
                return
            } finally {
                // Keep tracking companion detach events between candidate attempts. The
                // enclosing recovery owns this observer and removes it when the job ends.
                recoveryObservation.compareAndSet(observation, episodeObservation)
            }
        }
        AppLog.i("UsbLauncher: targeted recovery ended without one identifiable accessory")
    }

    private var staleSteps = 0
    private var staleLastStepAtMs = 0L
    private var staleGaveUpOn: String? = null

    /**
     * A USB handshake ended. A failure on a phone still in accessory mode starts the bounded
     * recovery ladder; a success resets it. The outcome carries the original request's owed scan,
     * captured before the handshake was queued; no completion may mint a replacement scan.
     */
    fun onUsbHandshakeEnded(outcome: CommManager.UsbHandshakeOutcome) {
        val commManager = App.provide(service).commManager
        val recheck = outcome.recheck
        if (!commManager.isCurrent(recheck) || !commManager.ownsUsbRecheck(outcome.recoveryOwner)) return
        val failure = outcome.failure
        if (failure == AapTransport.HandshakeFailure.NONE) {
            staleGaveUpOn = null
            staleSteps = 0
            staleLastStepAtMs = 0L
            ConnectionIssues.clear(service, ConnectionIssue.STALE_USB_ACCESSORY)
            return
        }
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return
        // The failure may reach Main after detach or another attachment. Never reset the
        // first accessory on the bus just because the failed device has already disappeared.
        val failedIdentity = outcome.identity ?: describeRecoveryDevice(outcome.device)
        val device = usbManager.deviceList[outcome.device.deviceName]?.takeIf {
            it.vendorId == outcome.device.vendorId && it.productId == outcome.device.productId &&
                UsbDeviceCompat.isInAccessoryMode(it) && UsbDeviceCompat.isConnectable(service, it) &&
                failedIdentity.agreesWith(describeRecoveryDevice(it))
        }
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
                if (!beginAttempt(Tier.USB, "USB recovery of $name", null, recheck)) {
                    AppLog.i("UsbLauncher: stale accessory recovery for $name refused: another attempt holds the arbiter")
                    return
                }
                staleSteps = steps + 1
                staleLastStepAtMs = now
                AppLog.i("UsbLauncher: stale accessory $name: handshake failed ($failure), " +
                    "step $staleSteps of ${StaleAccessoryRecoveryPolicy.MAX_STEPS}: $step")
                runRecoveryStep(usbManager, device, name, step, outcome.recoveryOwner, failedIdentity)
            }
        }
    }

    /** One job, so its completion frees the slot and claim and replays the queued scan. */
    private fun runRecoveryStep(
        usbManager: UsbManager,
        device: UsbDevice,
        name: String,
        step: StaleAccessoryRecoveryPolicy.Step,
        recoveryOwner: Any,
        failedIdentity: UsbRecoveryDevice,
    ) {
        val app = App.provide(service)
        val commManager = app.commManager
        val usbMode = UsbAccessoryMode(usbManager)
        // Exit/Save or a newer connection revokes this owner. Do not admit a fresh request
        // after waiting: that would make work from the cancelled recovery valid again.
        launchAttempt(Dispatchers.IO) { usbRequest ->
            commManager.awaitDisconnectComplete()
            currentCoroutineContext().ensureActive()
            if (cancelledByUser || service.isStopping || !commManager.ownsUsbRecheck(recoveryOwner) ||
                !commManager.isValid(usbRequest)) return@launchAttempt
            val busObservation = StaleAccessoryRecoveryPolicy.BusObservation()
            val observationOwner = RecoveryObservation(device.deviceName, device.vendorId, device.productId, busObservation)
            // Arm before the blocking USB request, not after it returns: firmware can
            // detach and reattach entirely inside that call or between two list polls.
            if (!commManager.runIfValid(usbRequest) {
                recoveryObservation.set(observationOwner)
            }) return@launchAttempt
            try {
                ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
                val before = usbManager.deviceList
                val current = before[device.deviceName] ?: return@launchAttempt
                if (current.vendorId != device.vendorId || current.productId != device.productId ||
                    !UsbDeviceCompat.isInAccessoryMode(current) || !UsbDeviceCompat.isConnectable(service, current)) return@launchAttempt
                if (busObservation.hasDetached ||
                    !failedIdentity.agreesWith(describeRecoveryDevice(current))) return@launchAttempt
                val target = UsbRecoveryTarget(failedIdentity, before.values.map(::describeRecoveryDevice),
                    before.values.filter { UsbDeviceCompat.isConnectable(service, it) }.map { it.deviceName }.toSet())
                observationOwner.target = target
                val issued = if (step == StaleAccessoryRecoveryPolicy.Step.RESWITCH) {
                    usbMode.connectAndSwitch(current, app.settings.useLibusb, switchAdmission(usbRequest)).also { if (it) noteAccessorySwitchRequested() }
                } else {
                    usbMode.resetDevice(current, switchAdmission(usbRequest))
                }
                val startedAt = recoveryNowMs()
                val deadline = startedAt + StaleAccessoryRecoveryPolicy.DETACH_WAIT_MS + StaleAccessoryRecoveryPolicy.REATTACH_WAIT_MS
                AppLog.i("UsbLauncher: stale accessory $name: $step returned $issued; observing the USB bus")
                // A failed request can still be followed by a delayed detach. Keep the same
                // bounded observation window for both results, rather than reopening stale USB.
                var observation = StaleAccessoryRecoveryPolicy.Observation.WAITING
                while (observation == StaleAccessoryRecoveryPolicy.Observation.WAITING) {
                    delay(100)
                    if (cancelledByUser || service.isStopping || !commManager.ownsUsbRecheck(recoveryOwner) ||
                        !commManager.isValid(usbRequest)) return@launchAttempt
                    val list = usbManager.deviceList
                    target.observe(list.values.map(::describeRecoveryDevice))
                    val original = list[device.deviceName]
                    val originalPresent = original != null && original.vendorId == device.vendorId &&
                        original.productId == device.productId
                    val reattached = list.any { (key, d) ->
                        (key !in before || key == device.deviceName) && UsbDeviceCompat.isConnectable(service, d)
                    }
                    val elapsed = recoveryNowMs() - startedAt
                    observation = busObservation.update(
                        originalPresent, reattached, elapsed
                    )
                }
                currentCoroutineContext().ensureActive()
                if (cancelledByUser || service.isStopping || !commManager.ownsUsbRecheck(recoveryOwner) ||
                    !commManager.isValid(usbRequest)) return@launchAttempt
                val elapsed = recoveryNowMs() - startedAt
                when (observation) {
                    StaleAccessoryRecoveryPolicy.Observation.REENUMERATED ->
                        AppLog.i("UsbLauncher: stale accessory $name: $step observed detach and a connectable bus entry after ${elapsed}ms")
                    StaleAccessoryRecoveryPolicy.Observation.NO_CHANGE ->
                        AppLog.i("UsbLauncher: stale accessory $name: no re-enumeration within ${elapsed}ms " +
                            "of $step; trying the handshake once more")
                    else ->
                        AppLog.i("UsbLauncher: stale accessory $name: the phone left the bus after $step " +
                            "and did not come back")
                }
                when (StaleAccessoryRecoveryPolicy.afterObservation(observation)) {
                    // The fallback resolves only the captured target. Ordinary attach requests
                    // stay queued independently and are not converted into recovery authority.
                    StaleAccessoryRecoveryPolicy.FollowUp.RECONNECT_ON_ATTACH ->
                        reconnectRecoveryTarget(usbManager, target, recoveryOwner, usbRequest, deadline)
                    StaleAccessoryRecoveryPolicy.FollowUp.RETRY_HANDSHAKE -> {
                        // The last observation and the open are separate operations. Re-read the
                        // bus and permissions; never reuse the pre-reset UsbDevice. Re-enumerated
                        // devices go through the target resolver and its own permission request.
                        val fresh = usbManager.deviceList[device.deviceName]
                        if (target.canFollowEnumeration) {
                            reconnectRecoveryTarget(usbManager, target, recoveryOwner, usbRequest, deadline)
                        } else if (target.canRetryUnchangedWithoutIdentity && !busObservation.hasDetached && fresh != null && fresh.vendorId == device.vendorId && fresh.productId == device.productId &&
                            UsbDeviceCompat.isInAccessoryMode(fresh) && UsbDeviceCompat.isConnectable(service, fresh) &&
                            usbManager.hasPermission(fresh) && !SettingsActivity.isVisible) {
                            commManager.reconnectUsb(fresh, recoveryOwner, usbRequest, target.guard(busObservation))
                        } else {
                            reconnectRecoveryTarget(usbManager, target, recoveryOwner, usbRequest, deadline)
                        }
                    }
                    StaleAccessoryRecoveryPolicy.FollowUp.STOP -> Unit
                }
            } finally {
                // A cancelled step must not remove a newer step's observation window.
                recoveryObservation.compareAndSet(observationOwner, null)
            }
        }
    }

    /**
     * Save may wait for transport retirement; an X pressed meanwhile remains authoritative.
     * [recheck] was minted when the service received Save's end, not when the retry runs.
     */
    fun restartForSettings(state: CommManager.ConnectionState.Disconnected, recheck: UsbRecheck) {
        if (!cancelledByUser) checkAlreadyConnected(force = true, settingsRestart = state, recheck = recheck)
    }

    /** A scan sent to the service, such as the attach screen's hand-off. An open Save still owns it. */
    fun checkOnRequest(userRequested: Boolean, recheck: UsbRecheck) = checkAlreadyConnected(
        force = true,
        userRequested = userRequested,
        settingsRestart = if (userRequested) null else pendingSettingsRestart(),
        recheck = recheck,
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
     * @param recheck The owed scan this call asks for, and its only authority. An input that
     *                happens now mints it now ([CommManager.usbRecheckNow]); work deferred from an
     *                input (a delayed fallback, a give-back, a queued replay, a settings hold, a
     *                permission reply) carries the one its input minted, so a user exit, Stop or
     *                held end since then withdraws it. Nothing here mints another.
     */
    fun checkAlreadyConnected(
        force: Boolean = false,
        userRequested: Boolean = false,
        settingsRestart: CommManager.ConnectionState.Disconnected? = null,
        accessoryOnly: Boolean = false,
        recheck: UsbRecheck,
    ) {
        val settings = App.provide(service).settings
        val commManager = App.provide(service).commManager
        if (!commManager.isCurrent(recheck)) return
        discardCancelledAttempt(commManager)
        if (settingsRestart != null && !settingsRestart.acceptsSettingsRestart(commManager.connectionState.value)) return
        val lastSession = settings.autoConnectLastSession
        val singleUsb = settings.autoConnectSingleUsbDevice
        val usbAutoStart = settings.autoStartOnUsb

        if (!force && !lastSession && !singleUsb && !usbAutoStart) return
        // A new button press may lift an earlier X even while cancelled work is unwinding.
        // A later X clears the pending request again before it can replay.
        if (userRequested) liftUserCancel("a USB connection was asked for")
        if (commManager.isConnected) {
            commManager.deferUsbCheckForConnectionAttempt(recheck)
            return
        }
        if (deferCheckWhileSwitching(force, userRequested, settingsRestart, commManager.usbRecheckOwner(), accessoryOnly, recheck)) return
        if (commManager.deferUsbCheckForConnectionAttempt(recheck)) return

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
                service.holdUsbCheckForSettings(recheck)
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
                if (!beginAttempt(tier, "USB $deviceName", settingsRestart, recheck)) return
                ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                launchAttempt { request ->
                    if (awaitAccessoryPermission(usbManager, device, deviceName, request)) {
                        connectWithRetry(device, request)
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
                        if (!beginAttempt(tier, "USB switch of ${deviceCompat.uniqueName}", settingsRestart, recheck)) return
                        ConnectionStageTracker.beginAttempt(ConnectionStage.USB_ATTACHED)
                        ConnectionStageTracker.report(ConnectionStage.USB_SWITCHING)
                        val usbMode = UsbAccessoryMode(usbManager)
                        launchAttempt(Dispatchers.IO) { request ->
                            if (usbMode.connectAndSwitch(device, settings.useLibusb, switchAdmission(request))) {
                                noteAccessorySwitchRequested()
                                AppLog.i("Successfully requested switch to accessory mode for ${deviceCompat.uniqueName}")
                            } else {
                                AppLog.w("connectAndSwitch failed for ${deviceCompat.uniqueName}")
                            }
                        }
                        return
                    } else {
                        AppLog.i("Found known USB device but no permission: ${deviceCompat.uniqueName}, requesting...")
                        requestPermission(device, settingsRestart, recheck)
                        return
                    }
                }
            }
        }

        // USB auto-start mode: attempt AOA switch for any single non-accessory device
        if (usbAutoStart) {
            val nonAccessoryDevices = deviceList.filter { !UsbDeviceCompat.isInAccessoryMode(it) }
            if (nonAccessoryDevices.size == 1) {
                performSingleConnect(nonAccessoryDevices[0], tier, settingsRestart, recheck)
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
                performSingleConnect(candidates[0], tier, settingsRestart, recheck)
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
                val isGoogleOrDongle = UsbAttachPolicy.isGoogleOrDongleVendor(
                    vendorId = it.vendorId,
                    productName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) it.productName else null,
                    manufacturerName = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) it.manufacturerName else null,
                )
                UsbAttachPolicy.shouldAttemptAoaSwitch(
                    isGoogleVendor = isGoogleOrDongle,
                    autoStartOnUsb = usbAutoStart,
                    allowListConfigured = allowList.isNotEmpty(),
                    deviceAllowed = settings.isConnectingDevice(UsbDeviceCompat(it)),
                )
            }
            if (candidates.size == 1) {
                AppLog.i("Fallback: force=true and found single normal-mode Android device ${UsbDeviceCompat(candidates[0]).uniqueName}. Switching to accessory mode.")
                performSingleConnect(candidates[0], tier, settingsRestart, recheck)
            } else if (candidates.isNotEmpty()) {
                AppLog.i("Fallback: force=true but ${candidates.size} candidate USB devices are attached; not guessing which is the phone")
            }
        }
    }

    /**
     * Runs [block] as the job of the request [beginAttempt] just admitted, handing it that request.
     * The body starts only while the request is still valid and inside its Save window. The job's
     * completion ends the request by identity whatever it reached, outside attemptLock; only the
     * slot's own job frees the slot and replays its queued scan.
     */
    internal fun launchAttempt(
        context: CoroutineContext = EmptyCoroutineContext,
        block: suspend CoroutineScope.(CommManager.ConnectionRequest) -> Unit,
    ) {
        val request = synchronized(attemptLock) { checkNotNull(attemptRequest) }
        val save = request.save
        val job = service.serviceScope.launch(context, start = CoroutineStart.LAZY) {
            val manager = App.provide(service).commManager
            if (manager.isValid(request) && (save == null || SystemClock.elapsedRealtime() < save.settingsRestartUntilMs)) block(request)
        }
        val finished = CompletableDeferred<Unit>()
        synchronized(attemptLock) {
            attemptJob = job
            attemptFinished = finished
        }
        job.invokeOnCompletion {
            try {
                // Cancellation may run before the body starts. An old completion must not clear a
                // replacement USB attempt's busy flag, but its own request always ends here.
                var ownsSlot = false
                val pending = synchronized(attemptLock) {
                    if (attemptJob !== job || attemptRequest !== request) return@synchronized null
                    ownsSlot = true
                    attemptJob = null
                    attemptFinished = null
                    attemptRequest = null
                    isSwitchingToProjection.set(false)
                    if (accessorySwitchRequested || UsbSwitchClaim.switches() != switchesAtBegin) {
                        pendingCheck = pendingCheck?.copy(accessoryOnly = true)
                    }
                    accessorySwitchRequested = false
                    pendingCheck
                }
                if (ownsSlot) endUsbAttemptStage()
                releaseAttempt(request, pending)
            } finally { finished.complete(Unit) }
        }
        save?.trackSettingsLaunch(job)
        job.start()
    }

    private fun performSingleConnect(device: UsbDevice, tier: Tier, settingsRestart: CommManager.ConnectionState.Disconnected?,
        recheck: UsbRecheck) {
        val settings = App.provide(service).settings
        val usbManager = UsbDeviceCompat.usbManager(service) ?: return

        if (usbManager.hasPermission(device)) {
            val deviceName = UsbDeviceCompat(device).uniqueName
            AppLog.i("Single USB auto-connect: connecting to $deviceName")
            if (!beginAttempt(tier, "USB switch of $deviceName", settingsRestart, recheck)) return
            val usbMode = UsbAccessoryMode(usbManager)
            launchAttempt(Dispatchers.IO) { request ->
                if (usbMode.connectAndSwitch(device, settings.useLibusb, switchAdmission(request))) {
                    noteAccessorySwitchRequested()
                    AppLog.i("Successfully requested switch to accessory mode for single USB device. Waiting for re-enumeration...")
                } else {
                    AppLog.w("Single USB auto-connect: connectAndSwitch failed for $deviceName")
                }
            }
        } else {
            AppLog.i("Single USB auto-connect: device found but no permission, requesting...")
            requestPermission(device, settingsRestart, recheck)
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
        request: CommManager.ConnectionRequest,
    ): Boolean {
        if (usbManager.hasPermission(device)) return true
        val commManager = App.provide(service).commManager
        if (!commManager.isValid(request)) return false

        val startedAt = System.currentTimeMillis()
        while (UsbAccessoryHandoffPolicy.shouldKeepPollingForPermission(System.currentTimeMillis() - startedAt)) {
            delay(UsbAccessoryHandoffPolicy.PERMISSION_POLL_INTERVAL_MS)
            if (!commManager.isValid(request)) return false
            if (usbManager.hasPermission(device)) {
                AppLog.i("Accessory-mode permission arrived after ${System.currentTimeMillis() - startedAt}ms: $deviceName")
                ConnectionStageTracker.report(ConnectionStage.PHONE_ANSWERED)
                return true
            }
        }

        AppLog.i("Accessory-mode device has no permission (re-enumerated); requesting permission: $deviceName")
        requestPermission(device, request.save, checkNotNull(request.recheck))
        return false
    }

    /**
     * Attempts a USB connection up to [maxRetries] times with a 1.5 s delay between attempts.
     *
     * USB accessories occasionally fail on the first attach (the device hasn't fully
     * enumerated yet), so retrying is necessary for reliability.
     * Every try continues the one admitted [request]: a request a newer one displaced, or that
     * Stop or Save revoked, stops at its next step and never claims again.
     */
    internal suspend fun connectWithRetry(
        device: UsbDevice,
        request: CommManager.ConnectionRequest,
        maxRetries: Int = 3,
    ) {
        val commManager = App.provide(service).commManager
        val settingsRestart = request.save
        var retryCount = 0
        var success = false

        while (retryCount <= maxRetries && !success) {
            if (!commManager.isValid(request)) return
            if (retryCount > 0) {
                AppLog.i("Retrying USB connection (attempt ${retryCount + 1}/$maxRetries)...")
                delay(1500)
                if (!commManager.isValid(request)) return
            }
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
                if (!awaitAccessoryPermission(usbManager, fresh, UsbDeviceCompat.getUniqueName(fresh), request)) return
                if (SystemClock.elapsedRealtime() >= settingsRestart.settingsRestartUntilMs ||
                    !commManager.isValid(request)) return
                fresh
            }
            // This request's own arrival, not the shared state: SSL may already have moved it on.
            success = commManager.connect(currentDevice, request)
            retryCount++
        }
    }


    companion object {

        /** Delay before AapService tries to handle a normal-mode USB attach as a fallback
         *  when UsbAttachedActivity doesn't fire (common on Chinese MediaTek headunits). */
        const val ATTACH_FALLBACK_DELAY_MS = 2000L
    }
}
