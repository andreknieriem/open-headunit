package com.andrerinas.openheadunit.connection.usb

import android.content.Context
import android.hardware.usb.UsbManager
import android.os.SystemClock
import androidx.appcompat.app.AppCompatDelegate
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import org.mockito.kotlin.any as anyValue
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq as eqValue
import kotlin.coroutines.CoroutineContext

class UsbDeferredRecheckTest {
    private class Queue : CoroutineDispatcher() {
        private val tasks = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.add(block) }
        fun step() { checkNotNull(tasks.poll()).run() }
        fun drain() { while (tasks.isNotEmpty()) step() }
    }

    private class Fixture {
        val queue = Queue()
        val scope = CoroutineScope(SupervisorJob() + queue)
        val service = mock(AapService::class.java)
        val comm = mock(CommManager::class.java, CALLS_REAL_METHODS)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        val settings = mock(Settings::class.java)
        val usb = mock(UsbManager::class.java)
        val launcher: UsbLauncherManager
        var now = 0L
        init {
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            `when`(service.serviceScope).thenReturn(scope)
            `when`(service.applicationContext).thenReturn(app)
            `when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.packageManager).thenReturn(mock(android.content.pm.PackageManager::class.java))
            `when`(service.getSharedPreferences(anyString(), anyInt())).thenReturn(mock(android.content.SharedPreferences::class.java))
            `when`(usb.deviceList).thenReturn(hashMapOf())
            `when`(settings.allowedDevices).thenReturn(emptySet())
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
            `when`(component.commManager).thenReturn(comm)
            `when`(component.settings).thenReturn(settings)
            field("_connectionState", states)
            field("connectionState", states)
            field("transportLifecycleLock", Any())
            field("appExitPolicy", com.andrerinas.openheadunit.connection.AppExitPolicy())
            field("usbRecoveryOwner", Any())
            field("settings", settings)
            launcher = spy(UsbLauncherManager(service))
        }
        fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(comm, value)
        fun begin(saved: ConnectionState.Disconnected? = null): CompletableDeferred<Unit> {
            assertTrue(launcher.beginAttempt(Tier.USB, "re-enumeration", saved, recheck()))
            val done = CompletableDeferred<Unit>()
            launcher.launchAttempt { done.await() }
            queue.drain()
            return done
        }
        fun recheck() = comm.usbRecheck(comm.appConnectionTicket())
        fun request() = UsbLauncherManager::class.java.getDeclaredField("attemptRequest")
            .apply { isAccessible = true }.get(launcher) as CommManager.ConnectionRequest
        fun preempt(claim: ConnectionArbiter.Claim) = launcher.preemptAttempt(claim)
        /** Counts forced, non-manual, Save-less scans, whether a fresh input or a replayed owed scan. */
        fun verifyScans(mode: org.mockito.verification.VerificationMode, accessoryOnly: Boolean) =
            verify(launcher, mode).checkAlreadyConnected(eqValue(true), eqValue(false), isNull(),
                eqValue(accessoryOnly), anyValue())
        fun verifyNoScan() = verify(launcher, never()).checkAlreadyConnected(anyBoolean(), anyBoolean(),
            any(), anyBoolean(), anyValue())
        fun check(force: Boolean = true, manual: Boolean = false, saved: ConnectionState.Disconnected? = null) =
            launcher.checkAlreadyConnected(force, manual, saved, recheck = recheck())
        fun assertScans(count: Int) {
            // The diagnostic and selection read the current bus. No cached UsbDevice is replayed.
            verify(service, times(count)).getSystemService(Context.USB_SERVICE)
            verify(usb, times(count * 2)).deviceList
        }
    }

    @Test fun repeatedRequestsDuringAStaleDeviceLoopProduceOneFreshScanAfterError() = fixture { f ->
        val done = f.begin()
        f.states.value = ConnectionState.Connecting
        f.check()
        f.states.value = ConnectionState.Disconnected()
        f.launcher.setSwitchingToProjection(false) // service observes a failed open inside the loop
        assertTrue(f.launcher.isSwitchingToProjection())
        f.states.value = ConnectionState.Error("permission lost after re-enumeration")
        repeat(3) { f.check() }
        f.assertScans(0)
        done.complete(Unit)
        f.queue.drain()
        assertFalse(f.launcher.isSwitchingToProjection())
        f.assertScans(1)
        f.queue.drain()
        f.assertScans(1) // Completion does not create a self-sustaining retry loop.
    }

    @Test fun reenumeratedDeviceIsReadFromTheCurrentBusAndPassedToANewAttempt() = fixture { f ->
        val old = mock(android.hardware.usb.UsbDevice::class.java)
        val fresh = mock(android.hardware.usb.UsbDevice::class.java)
        `when`(fresh.deviceName).thenReturn("/dev/bus/usb/001/003")
        `when`(fresh.vendorId).thenReturn(0x18d1)
        `when`(fresh.productId).thenReturn(0x2d00)
        `when`(f.usb.deviceList).thenReturn(hashMapOf("old" to old))
        val done = f.begin()
        f.check()
        `when`(f.usb.deviceList).thenReturn(hashMapOf("new" to fresh))
        `when`(f.usb.hasPermission(fresh)).thenReturn(true)
        var connected: android.hardware.usb.UsbDevice? = null
        runBlocking {
            doAnswer { call ->
                connected = call.getArgument(0)
                f.states.value = ConnectionState.Connected
                Unit
            }.`when`(f.launcher).connectWithRetry(eqValue(fresh), anyValue(), eqValue(3))
        }
        done.complete(Unit)
        f.queue.drain()
        assertSame(fresh, connected)
        verify(f.usb, never()).hasPermission(old)
    }

    @Test fun successfulSwitchReplaysOnlyAccessoryDevicesWhileFailedSwitchCanTryAgain() {
        for (outcome in listOf("normal-still-listed", "accessory-ready", "switch-failed")) fixture { f ->
            val normal = mock(android.hardware.usb.UsbDevice::class.java)
            val adb = mock(android.hardware.usb.UsbInterface::class.java)
            `when`(normal.deviceName).thenReturn("/dev/bus/usb/001/002")
            `when`(normal.vendorId).thenReturn(0x18d1)
            `when`(normal.productId).thenReturn(0x4ee7)
            `when`(normal.interfaceCount).thenReturn(1)
            `when`(normal.getInterface(0)).thenReturn(adb)
            `when`(adb.interfaceClass).thenReturn(0xff)
            `when`(adb.interfaceSubclass).thenReturn(0x42)
            `when`(adb.interfaceProtocol).thenReturn(1)
            val accessory = mock(android.hardware.usb.UsbDevice::class.java)
            `when`(accessory.deviceName).thenReturn("/dev/bus/usb/001/003")
            `when`(accessory.vendorId).thenReturn(0x18d1)
            `when`(accessory.productId).thenReturn(0x2d00)
            `when`(f.usb.deviceList).thenReturn(hashMapOf("phone" to normal))
            `when`(f.usb.hasPermission(normal)).thenReturn(true)
            `when`(f.usb.hasPermission(accessory)).thenReturn(true)
            `when`(f.settings.autoStartOnUsb).thenReturn(true)
            val entered = java.util.concurrent.CountDownLatch(1)
            val finish = java.util.concurrent.CountDownLatch(1)
            runBlocking {
                doAnswer { f.states.value = ConnectionState.Connected; Unit }.`when`(f.launcher)
                    .connectWithRetry(eqValue(accessory), anyValue(), eqValue(3))
            }
            mockConstruction(UsbAccessoryMode::class.java) { mode, _ ->
                `when`(mode.connectAndSwitch(eqValue(normal), eqValue(false), anyValue())).thenAnswer {
                    entered.countDown()
                    check(finish.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    outcome != "switch-failed"
                }
            }.use { switches ->
                f.check() // Real performSingleConnect + launchAttempt + successful-switch marker.
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                val switching = checkNotNull(f.launcher.attemptJob)
                val settled = java.util.concurrent.CountDownLatch(1)
                switching.invokeOnCompletion { settled.countDown() }
                f.check()
                if (outcome == "accessory-ready")
                    `when`(f.usb.deviceList).thenReturn(hashMapOf("phone" to accessory))
                finish.countDown()
                runBlocking { withTimeout(5000) { switching.join() } }
                assertTrue(settled.await(5, java.util.concurrent.TimeUnit.SECONDS))
                f.queue.drain()
                f.launcher.attemptJob?.let { runBlocking { withTimeout(5000) { it.join() } } }
                f.queue.drain()
                assertEquals(outcome, if (outcome == "switch-failed") 2 else 1, switches.constructed().size)
                f.verifyScans(times(if (outcome != "switch-failed") 1 else 0), accessoryOnly = true)
                runBlocking {
                    verify(f.launcher, times(if (outcome == "accessory-ready") 1 else 0))
                        .connectWithRetry(eqValue(accessory), anyValue(), eqValue(3))
                }
            }
        }
    }

    @Test fun aNewButtonPressAfterXCanRequestOneDeferredScan() = fixture { f ->
        f.begin()
        f.launcher.stopForUser()
        assertTrue(f.launcher.cancelledByUser)
        f.check(manual = true)
        f.queue.drain()
        assertFalse(f.launcher.cancelledByUser)
        f.assertScans(1)
    }

    @Test fun completionWithoutARequestedRecheckDoesNotRescan() = fixture { f ->
        val done = f.begin()
        done.complete(Unit)
        f.queue.drain()
        f.assertScans(0)
    }

    @Test fun disabledAutomaticScanDoesNotBecomeForcedAfterCompletion() = fixture { f ->
        val done = f.begin()
        f.check(force = false)
        done.complete(Unit)
        f.queue.drain()
        f.assertScans(0)
    }

    @Test fun stopReplacementSessionAndServiceShutdownInvalidateAQueuedManualRecheck() {
        for (ending in listOf("stop", "preempt", "exit", "session", "save", "destroy", "stopping")) fixture { f ->
            val done = f.begin()
            val request = f.request()
            f.check(manual = true)
            if (ending == "preempt") {
                // Preemption belongs to the running attempt, before completion clears its slot.
                // A delayed callback for an ended claim must not cancel a later queued input.
                val job = checkNotNull(f.launcher.attemptJob)
                assertTrue(f.comm.isValid(request))
                assertTrue(job.isActive)
                f.preempt(request.claim)
                assertTrue(job.isCancelled)
            }
            done.complete(Unit)
            f.queue.step() // Other endings now have the completed owner's rescan queued on the service dispatcher.
            when (ending) {
                "stop" -> f.launcher.stopForUser()
                "exit" -> f.comm.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(f.comm.appConnectionTicket(), null), honorKillOnDisconnect = false)
                "save" -> f.comm.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(f.comm.appConnectionTicket(), null), isUserExit = false, reason = CommManager.DisconnectReason.SETTINGS_RESTART)
                "session" -> f.field("usbRecoveryOwner", Any()) // producer rotates before successful handshake publication
                "destroy" -> f.scope.cancel()
                "stopping" -> `when`(f.service.isStopping).thenReturn(true)
            }
            f.queue.drain()
            f.assertScans(0)
            if (ending == "stop") assertTrue(f.launcher.cancelledByUser)
        }
    }

    @Test fun staleCompletionCannotDrainOrReleaseANewerAttempt() = fixture { f ->
        val old = f.begin()
        val claim = f.request().claim
        f.check()
        f.preempt(claim)
        val next = f.begin()
        f.check()
        old.complete(Unit)
        f.queue.drain()
        assertTrue(f.launcher.isSwitchingToProjection())
        f.assertScans(0)
        next.complete(Unit)
        f.queue.drain()
        f.assertScans(1)
    }

    @Test fun delayedPreemptionKeepsTheReplacementJobAndItsPendingScan() = fixture { f ->
        f.begin()
        val old = f.request()
        f.preempt(old.claim)
        f.queue.drain()
        val done = f.begin()
        val current = checkNotNull(f.launcher.attemptJob)
        f.check()
        f.launcher.preemptAttempt(old.claim)
        assertTrue(current.isActive)
        done.complete(Unit)
        f.queue.drain()
        f.assertScans(1)
    }

    @Test fun aNewAttemptInheritsThePendingScanWithoutTheOldCompletionReleasingIt() = fixture { f ->
        val old = f.begin()
        f.check()
        val next = f.begin()
        old.complete(Unit)
        f.queue.drain()
        assertTrue(f.launcher.isSwitchingToProjection())
        f.assertScans(0)
        next.complete(Unit)
        f.queue.drain()
        f.assertScans(1)
    }

    @Test fun completionBeforeRequestRunsAnOrdinaryScanOnlyOnce() = fixture { f ->
        val done = f.begin()
        done.complete(Unit)
        f.queue.drain()
        f.check()
        f.queue.drain()
        f.assertScans(1)
    }

    @Test fun ioCompletionRacingAScanNeitherLosesNorDuplicatesIt() {
        repeat(30) { fixture { f ->
            val ready = CompletableDeferred<Unit>()
            val done = CompletableDeferred<Unit>()
            assertTrue(f.launcher.beginAttempt(Tier.USB, "IO switch", null, f.recheck()))
            f.launcher.launchAttempt(Dispatchers.IO) { ready.complete(Unit); done.await() }
            val job = checkNotNull(f.launcher.attemptJob)
            val completed = java.util.concurrent.CountDownLatch(1)
            job.invokeOnCompletion { completed.countDown() }
            runBlocking { withTimeout(5000) { ready.await() } }
            val start = java.util.concurrent.CountDownLatch(1)
            val completion = Thread { start.await(); done.complete(Unit) }
            completion.start()
            start.countDown()
            f.check()
            completion.join(5000)
            assertFalse(completion.isAlive)
            runBlocking { withTimeout(5000) { job.join() } }
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            f.queue.drain()
            f.assertScans(1)
        } }
    }

    @Test fun saveWaitsForSlotReleaseEvenWhenTheJobAlreadyReportsCompleted() = fixture { f ->
        val saved = ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
            settingsRestartUntilMs = Long.MAX_VALUE)
        f.states.value = saved
        assertTrue(f.launcher.beginAttempt(Tier.USB, "Save switch", saved, f.recheck()))
        val ready = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        // Save's admission clock is an Android static mock on the test thread, so the body starts
        // there (Unconfined). It resumes and completes on the completer thread below, which
        // isolates completion ordering against a lock the test thread holds.
        f.launcher.launchAttempt(Dispatchers.Unconfined) { ready.complete(Unit); done.await() }
        val job = checkNotNull(f.launcher.attemptJob)
        val completed = java.util.concurrent.CountDownLatch(1)
        job.invokeOnCompletion { completed.countDown() }
        runBlocking { withTimeout(5000) { ready.await() } }
        val lock = checkNotNull(UsbLauncherManager::class.java.getDeclaredField("attemptLock")
            .apply { isAccessible = true }.get(f.launcher))
        var returned = false
        val completer = Thread { done.complete(Unit) }
        synchronized(lock) {
            completer.start()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!job.isCompleted && System.nanoTime() < deadline) Thread.yield()
            assertTrue(job.isCompleted)
            assertTrue(f.launcher.hasSettingsAttempt(saved))
            f.scope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.launcher.awaitSettingsAttempt(saved)
                returned = true
            }
            assertFalse("Save must not resume automatic scanning before slot release", returned)
        }
        assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
        completer.join(5000)
        f.queue.drain()
        assertTrue(returned)
        assertFalse(f.launcher.hasSettingsAttempt(saved))
    }

    @Test fun queuedScanKeepsSavesOriginalOwnerAndCannotEscapeCancellation() {
        for (outcome in listOf("active", "cancelled", "expired")) fixture { f ->
            val saved = ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = 1000L)
            f.states.value = saved
            val done = f.begin(saved)
            f.check() // Even an ordinary attach belongs to the Save attempt already in flight.
            done.complete(Unit)
            f.queue.step()
            if (outcome == "cancelled") f.comm.cancelPendingSettingsRestart()
            if (outcome == "expired") f.now = 1000L
            f.queue.drain()
            // An expired Save that the scan only inherited falls back to ordinary admission.
            f.assertScans(if (outcome == "cancelled") 0 else 1)
            // The scan itself, plus the replay without the Save once it has expired.
            f.verifyScans(times(if (outcome == "expired") 2 else 1), accessoryOnly = false)
        }
    }

    @Test fun anExpiredInheritedSaveFallsBackToOrdinaryAdmission() {
        for (outcome in listOf("inherited", "explicit")) fixture { f ->
            val saved = ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART,
                settingsRestartUntilMs = 1000L)
            f.states.value = saved
            val done = f.begin(saved)
            f.check(saved = if (outcome == "explicit") saved else null)
            done.complete(Unit)
            f.queue.step()
            f.now = 1000L
            f.queue.drain()
            f.assertScans(if (outcome == "inherited") 1 else 0)
        }
    }

    @Test fun anActivitySwitchDuringAnAttemptLeavesTheReplayAccessoryOnly() = fixture { f ->
        val done = f.begin()
        val claim = checkNotNull(ConnectionArbiter.claim(Tier.USB,
            com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner.USB, "attach activity switch"))
        UsbSwitchClaim.stake(claim)
        UsbSwitchClaim.noteSwitched(claim) // The distinct activity owner sent ACC_REQ_START.
        UsbSwitchClaim.release(claim)
        ConnectionArbiter.release(claim, sessionFormed = false)
        f.check()                    // The 2 s attach fallback queues behind the attempt.
        done.complete(Unit)
        f.queue.drain()
        f.verifyScans(times(1), accessoryOnly = false) // The fallback itself.
        f.verifyScans(times(1), accessoryOnly = true)
    }

    @Test fun noActivitySwitchLeavesTheReplayAFullScan() = fixture { f ->
        val done = f.begin()
        f.check()
        done.complete(Unit)
        f.queue.drain()
        f.verifyScans(times(2), accessoryOnly = false)
        f.verifyScans(never(), accessoryOnly = true)
    }

    @Test fun aReplayDuringALiveActivitySwitchIsAccessoryOnly() = fixture { f ->
        val done = f.begin()
        f.check()
        val claim = checkNotNull(ConnectionArbiter.claim(Tier.USB,
            com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner.USB, "attach activity switch"))
        UsbSwitchClaim.stake(claim)
        try {
            done.complete(Unit)
            f.queue.drain()
            f.verifyScans(times(1), accessoryOnly = true)
        } finally {
            UsbSwitchClaim.release(claim)
            ConnectionArbiter.release(claim, sessionFormed = false)
        }
    }

    private class RecoveryBus(f: Fixture) {
        val stale = accessory("/dev/bus/usb/001/002")
        val fresh = accessory("/dev/bus/usb/001/003")
        @Volatile var devices: Map<String, android.hardware.usb.UsbDevice> = mapOf(stale.deviceName to stale)
        init {
            `when`(f.usb.deviceList).thenAnswer { HashMap(devices) }
            `when`(f.usb.hasPermission(stale)).thenReturn(true)
            `when`(f.usb.hasPermission(fresh)).thenReturn(true)
        }
        val gate = java.util.concurrent.CountDownLatch(1)
        fun reenumerate() { devices = mapOf(fresh.deviceName to fresh) }
        private fun accessory(name: String) = mock(android.hardware.usb.UsbDevice::class.java).also {
            `when`(it.deviceName).thenReturn(name)
            `when`(it.vendorId).thenReturn(0x18d1)
            `when`(it.productId).thenReturn(0x2d00)
        }
    }

    /** Ends a USB handshake and returns the deferred that completes after the step's slot release and replay. */
    private fun endHandshake(f: Fixture, bus: RecoveryBus): CompletableDeferred<Unit> {
        f.launcher.onUsbHandshakeEnded(CommManager.UsbHandshakeOutcome(
            com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL, bus.stale, f.comm.usbRecheckOwner(), f.recheck()))
        @Suppress("UNCHECKED_CAST")
        val finished = UsbLauncherManager::class.java.getDeclaredField("attemptFinished")
            .apply { isAccessible = true }.get(f.launcher) as CompletableDeferred<Unit>
        bus.gate.countDown()
        return finished
    }

    private fun settled(f: Fixture, finished: CompletableDeferred<Unit>) {
        runBlocking { withTimeout(10_000) { finished.await() } }
        f.queue.drain()
    }

    private fun stubRetry(f: Fixture, bus: RecoveryBus, onCall: () -> Unit = {}) = runBlocking {
        doAnswer { onCall(); Unit }.`when`(f.comm).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue())
        doAnswer { Unit }.`when`(f.launcher).connectWithRetry(eqValue(bus.fresh), anyValue(), eqValue(3))
        doAnswer { Unit }.`when`(f.launcher).connectWithRetry(eqValue(bus.stale), anyValue(), eqValue(3))
    }

    private fun switching(bus: RecoveryBus, issued: Boolean, entered: java.util.concurrent.CountDownLatch? = null,
        reenumerates: Boolean = false, duringRequest: () -> Unit = {}) = mockConstruction(UsbAccessoryMode::class.java) { mode, _ ->
        `when`(mode.connectAndSwitch(eqValue(bus.stale), eqValue(false), anyValue())).thenAnswer {
            check(bus.gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
            entered?.countDown()
            duringRequest()
            if (reenumerates) bus.reenumerate()
            issued
        }
    }

    private fun identifyRecovery(f: Fixture, serial: (android.hardware.usb.UsbDevice) -> String? = { "target" }) {
        doAnswer { call ->
            val d = call.getArgument<android.hardware.usb.UsbDevice>(0)
            UsbRecoveryDevice(d.deviceName, d.vendorId, d.productId, "Maker", "Box", serial(d))
        }.`when`(f.launcher).describeRecoveryDevice(anyValue())
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        doAnswer { clock.addAndGet(1000) }.`when`(f.launcher).recoveryNowMs()
    }

    @Test fun missingAttachNotificationStillReconnectsTheMatchingNewAddress() = fixture { f ->
        val bus = RecoveryBus(f)
        identifyRecovery(f)
        stubRetry(f, bus)
        runBlocking { doAnswer { Unit }.`when`(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            runBlocking { verify(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun targetedFallbackDoesNotChooseADifferentSerial() = fixture { f ->
        val bus = RecoveryBus(f)
        identifyRecovery(f) { if (it === bus.stale) "target" else "other" }
        stubRetry(f, bus)
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }

    @Test fun permissionBelongsToTargetedRecoveryAndSerialIsCheckedAfterGrant() = fixture { f ->
        val bus = RecoveryBus(f)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        `when`(f.usb.hasPermission(bus.fresh)).thenAnswer { granted.get() }
        identifyRecovery(f) { if (it === bus.fresh && !granted.get()) null else "target" }
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            granted.set(true)
            UsbLauncherListener(f.launcher).onUsbRecoveryPermission(token, true, bus.fresh)
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        runBlocking { doAnswer { Unit }.`when`(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(f.usb).requestPermission(bus.fresh, intent)
            verify(intent).cancel()
            runBlocking { verify(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
            clearInvocations(f.comm)
            UsbLauncherListener(f.launcher).onUsbRecoveryPermission(token, true, bus.fresh)
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }

    @Test fun exitWhileRecoveryPermissionIsPendingPreventsLateGrantFromConnecting() = fixture { f ->
        val bus = RecoveryBus(f)
        `when`(f.usb.hasPermission(bus.fresh)).thenReturn(false)
        identifyRecovery(f)
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            f.launcher.stopForUser()
            UsbLauncherListener(f.launcher).onUsbRecoveryPermission(token, true, bus.fresh)
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(intent).cancel()
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }


    @Test fun permissionRevealingAnotherSerialDoesNotConnect() = fixture { f ->
        val bus = RecoveryBus(f)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        `when`(f.usb.hasPermission(bus.fresh)).thenAnswer { granted.get() }
        identifyRecovery(f) { if (it === bus.stale) "target" else if (granted.get()) "other" else null }
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            granted.set(true)
            f.launcher.onRecoveryPermission(token, true, bus.fresh)
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(f.usb).requestPermission(bus.fresh, intent)
            verify(intent).cancel()
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }


    @Test fun detachDuringPermissionRequiresANewRequestBeforeTheNextEnumerationConnects() = fixture { f ->
        val bus = RecoveryBus(f)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        `when`(f.usb.hasPermission(bus.fresh)).thenAnswer { granted.get() }
        identifyRecovery(f)
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        val tokens = mutableListOf<String>()
        doAnswer { tokens.add(it.getArgument(0)); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            if (tokens.size == 1) {
                f.launcher.noteRecoveryDeviceDetached(bus.fresh)
                f.launcher.onRecoveryPermission(tokens[0], true, bus.fresh)
            } else {
                // The old response must not satisfy the new request. Only a fresh grant
                // for this enumeration can complete the new candidate's permission wait.
                f.launcher.onRecoveryPermission(tokens[0], true, bus.fresh)
                granted.set(true)
                f.launcher.onRecoveryPermission(tokens[1], true, bus.fresh)
            }
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        runBlocking { doAnswer { Unit }.`when`(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            assertEquals(2, tokens.size)
            assertNotEquals(tokens[0], tokens[1])
            verify(intent, times(2)).cancel()
            runBlocking { verify(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
        }
    }


    @Test fun denialFollowedByDetachDoesNotRequestPermissionAgain() = fixture { f ->
        val bus = RecoveryBus(f)
        `when`(f.usb.hasPermission(bus.fresh)).thenReturn(false)
        identifyRecovery(f)
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            f.launcher.onRecoveryPermission(token, false, bus.fresh)
            f.launcher.noteRecoveryDeviceDetached(bus.fresh)
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(f.usb).requestPermission(bus.fresh, intent)
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }


    @Test fun companionDetachBetweenCandidateAttemptsRemainsObserved() = fixture { f ->
        val bus = RecoveryBus(f)
        val companion = mock(android.hardware.usb.UsbDevice::class.java)
        `when`(companion.deviceName).thenReturn("/dev/bus/usb/001/004")
        `when`(companion.vendorId).thenReturn(0x18d1)
        `when`(companion.productId).thenReturn(0x2d00)
        `when`(f.usb.hasPermission(companion)).thenReturn(true)
        bus.devices = mapOf(bus.stale.deviceName to bus.stale, companion.deviceName to companion)
        identifyRecovery(f) { if (it === companion) "other" else "target" }
        stubRetry(f, bus)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        `when`(f.usb.hasPermission(bus.fresh)).thenAnswer { granted.get() }
        val betweenCandidates = java.util.concurrent.atomic.AtomicBoolean(false)
        val clock = java.util.concurrent.atomic.AtomicLong(0)
        doAnswer {
            if (betweenCandidates.getAndSet(false)) f.launcher.noteRecoveryDeviceDetached(companion)
            clock.addAndGet(500)
        }.`when`(f.launcher).recoveryNowMs()
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        var requests = 0
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer { betweenCandidates.set(true); null }.`when`(intent).cancel()
        doAnswer {
            requests++
            if (requests == 1) f.launcher.noteRecoveryDeviceDetached(bus.fresh)
            else {
                granted.set(true)
                f.launcher.onRecoveryPermission(token, true, bus.fresh)
            }
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        switching(bus, issued = true, duringRequest = {
            bus.devices = mapOf(bus.fresh.deviceName to bus.fresh, companion.deviceName to companion)
        }).use {
            settled(f, endHandshake(f, bus))
            assertEquals(1, requests)
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
        }
    }


    @Test fun samePathSerialBecomingUnreadableAfterGrantReturnsToPermission() = fixture { f ->
        val bus = RecoveryBus(f)
        val next = mock(android.hardware.usb.UsbDevice::class.java)
        val path = bus.fresh.deviceName
        `when`(next.deviceName).thenReturn(path)
        `when`(next.vendorId).thenReturn(0x18d1)
        `when`(next.productId).thenReturn(0x2d00)
        val granted = java.util.concurrent.atomic.AtomicBoolean(false)
        identifyRecovery(f) { if (it === next && !granted.get()) null else "target" }
        stubRetry(f, bus)
        var permissionReads = 0
        `when`(f.usb.hasPermission(bus.fresh)).thenAnswer {
            permissionReads++
            if (permissionReads == 3) {
                // The permission fast path succeeds for the old object, then the next
                // snapshot sees a new enumeration whose serial needs permission again.
                f.launcher.noteRecoveryDeviceDetached(bus.fresh)
                bus.devices = mapOf(path to next)
            }
            true
        }
        `when`(f.usb.hasPermission(next)).thenAnswer { granted.get() }
        val intent = mock(android.app.PendingIntent::class.java)
        var token = ""
        doAnswer { token = it.getArgument(0); intent }.`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            granted.set(true)
            f.launcher.onRecoveryPermission(token, true, next)
            null
        }.`when`(f.usb).requestPermission(eqValue(next), eqValue(intent))
        runBlocking { doAnswer { Unit }.`when`(f.comm).reconnectUsb(eqValue(next), anyValue(), anyValue(), anyValue()) }
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(f.usb).requestPermission(next, intent)
            runBlocking {
                verify(f.comm).reconnectUsb(eqValue(next), anyValue(), anyValue(), anyValue())
                verify(f.comm, never()).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue())
            }
        }
    }

    @Test fun unknownPermissionTokenTimesOutWithoutLaunchingAnotherScan() = fixture { f ->
        val bus = RecoveryBus(f)
        `when`(f.usb.hasPermission(bus.fresh)).thenReturn(false)
        identifyRecovery(f)
        stubRetry(f, bus)
        val intent = mock(android.app.PendingIntent::class.java)
        doReturn(intent).`when`(f.launcher).recoveryPermissionIntent(anyString())
        doAnswer {
            f.launcher.onRecoveryPermission("old-request", true, bus.fresh)
            null
        }.`when`(f.usb).requestPermission(eqValue(bus.fresh), eqValue(intent))
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            verify(intent).cancel()
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun aRecoveryStepFreesTheSlotWhenItEnds() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = false).use {
            settled(f, endHandshake(f, bus))
            assertFalse(f.launcher.isSwitchingToProjection())
            assertNull(f.launcher.attemptJob)
        }
    }

    @Test fun aScanQueuedDuringARecoveryStepReplaysOnce() = fixture { f ->
        val bus = RecoveryBus(f)
        val inRetry = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        stubRetry(f, bus) {
            inRetry.countDown()
            check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
        }
        switching(bus, issued = false).use {
            val finished = endHandshake(f, bus)
            assertTrue(inRetry.await(10, java.util.concurrent.TimeUnit.SECONDS))
            f.check()
            clearInvocations(f.service, f.usb)
            release.countDown()
            settled(f, finished)
            f.assertScans(1)
        }
    }

    @Test fun detachAfterRetryDispatchInvalidatesAdmissionAndPreservesAttachReplay() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        val inRetry = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val admission = java.util.concurrent.atomic.AtomicReference<com.andrerinas.openheadunit.connection.ConnectionAdmission>()
        runBlocking {
            doAnswer { call ->
                admission.set(call.getArgument(3))
                inRetry.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                Unit
            }.`when`(f.comm).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue())
        }
        switching(bus, issued = false).use {
            val finished = endHandshake(f, bus)
            try {
                assertTrue(inRetry.await(10, java.util.concurrent.TimeUnit.SECONDS))
                val listener = UsbLauncherListener(f.launcher)
                listener.onUsbDetach(bus.stale)
                listener.onUsbAttach(bus.stale) // Same path and descriptors, but a new attachment.
                assertFalse(admission.get().run { fail("Detached enumeration cannot publish") })
                clearInvocations(f.service, f.usb)
            } finally { release.countDown() }
            settled(f, finished)
            f.assertScans(1)
        }
    }

    @Test fun aRecoveryRetryTakesNoSecondAttempt() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = false).use {
            settled(f, endHandshake(f, bus))
            // Read the mocked device before recording matchers: a mock call between matchers is invalid.
            val description = "USB recovery of ${UsbDeviceCompat(bus.stale).uniqueName}"
            verify(f.launcher, times(1)).beginAttempt(eqValue(Tier.USB), eqValue(description), isNull(), anyValue())
            runBlocking { verify(f.comm, times(1)).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue()) }
        }
    }

    @Test fun aSuccessfulReswitchReplaysAccessoryOnly() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = true, reenumerates = true).use {
            val finished = endHandshake(f, bus)
            f.check() // Independent attach notification received while the switch owns the slot.
            settled(f, finished)
            f.queue.drain()
            f.verifyScans(times(1), accessoryOnly = true)
            f.verifyScans(times(1), accessoryOnly = false)
        }
    }

    @Test fun aFailedReswitchStillWaitsForDelayedReenumeration() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        val entered = java.util.concurrent.CountDownLatch(1)
        switching(bus, issued = false, entered = entered).use {
            val finished = endHandshake(f, bus)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            Thread.sleep(250) // Device finishes switching after the host reports failure.
            runBlocking { verify(f.comm, never()).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue()) }
            bus.reenumerate()
            settled(f, finished)
            runBlocking { verify(f.comm, never()).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun detachDeliveredDuringControlTransferSurvivesSamePathReturnBeforeFirstPoll() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        val listener = UsbLauncherListener(f.launcher)
        switching(bus, issued = false, duringRequest = {
            // The bus list always appears unchanged to polling. The real listener delivers
            // the only evidence of a complete detach/reattach inside the blocking request.
            listener.onUsbDetach(bus.stale)
        }).use {
            settled(f, endHandshake(f, bus))
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun anotherAccessoriesDetachDoesNotInvalidateTheRecoveryTarget() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = false, duringRequest = {
            UsbLauncherListener(f.launcher).onUsbDetach(bus.fresh)
        }).use {
            settled(f, endHandshake(f, bus))
            runBlocking { verify(f.comm).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue()) }
        }
    }

    @Test fun recoveryDoesNotAcquireANewOwnerAfterSaveOrExit() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        val entered = java.util.concurrent.CountDownLatch(1)
        switching(bus, issued = false, entered = entered).use {
            val finished = endHandshake(f, bus)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            f.field("usbRecoveryOwner", Any())
            bus.reenumerate()
            settled(f, finished)
            runBlocking { verify(f.comm, never()).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun unchangedRecoveryOpensTheCurrentDeviceObjectRatherThanTheCapturedOne() = fixture { f ->
        val bus = RecoveryBus(f)
        val name = bus.stale.deviceName
        `when`(bus.fresh.deviceName).thenReturn(name)
        runBlocking { doAnswer { Unit }.`when`(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue()) }
        val entered = java.util.concurrent.CountDownLatch(1)
        switching(bus, issued = false, entered = entered).use {
            val finished = endHandshake(f, bus)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            bus.devices = mapOf(bus.stale.deviceName to bus.fresh)
            settled(f, finished)
            runBlocking {
                verify(f.comm).reconnectUsb(eqValue(bus.fresh), anyValue(), anyValue(), anyValue())
                verify(f.comm, never()).reconnectUsb(eqValue(bus.stale), anyValue(), anyValue(), anyValue())
            }
        }
    }

    @Test fun disappearanceAtFinalLookupDoesNotScanForAnotherAccessory() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) {
                if (msg.contains("trying the handshake once more")) bus.reenumerate()
            }
        }
        switching(bus, issued = false).use {
            settled(f, endHandshake(f, bus))
            runBlocking { verify(f.comm, never()).reconnectUsb(anyValue(), anyValue(), anyValue(), anyValue()) }
            f.verifyNoScan()
        }
    }

    @Test fun theStatusPillXEndsARecoveryStep() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        val entered = java.util.concurrent.CountDownLatch(1)
        switching(bus, issued = true, entered = entered).use {
            val finished = endHandshake(f, bus)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            f.launcher.stopForUser()
            settled(f, finished)
            assertFalse(f.launcher.isSwitchingToProjection())
            assertNull(f.launcher.attemptJob)
            val observation = UsbLauncherManager::class.java.getDeclaredField("recoveryObservation")
                .apply { isAccessible = true }.get(f.launcher) as java.util.concurrent.atomic.AtomicReference<*>
            assertNull("Cancellation must close the recovery observation window", observation.get())
            f.verifyNoScan()
        }
    }

    @Test fun aStepIsRefusedWhileTheSlotIsBusy() = fixture { f ->
        val bus = RecoveryBus(f)
        f.begin()
        switching(bus, issued = false).use { steps ->
            f.launcher.onUsbHandshakeEnded(CommManager.UsbHandshakeOutcome(
            com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL, bus.stale, f.comm.usbRecheckOwner(), f.recheck()))
            assertEquals(0, steps.constructed().size)
            val count = UsbLauncherManager::class.java.getDeclaredField("staleSteps")
                .apply { isAccessible = true }.getInt(f.launcher)
            assertEquals(0, count)
        }
    }

    @Test fun delayedFailureDoesNotResetAnotherAccessoryOrUseANewerOwner() = fixture { f ->
        val bus = RecoveryBus(f)
        val outcome = CommManager.UsbHandshakeOutcome(
            com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL, bus.stale, f.comm.usbRecheckOwner(), f.recheck())
        switching(bus, issued = false).use { commands ->
            bus.reenumerate() // The reported enumeration has gone; another accessory is present.
            f.launcher.onUsbHandshakeEnded(outcome)
            assertEquals(0, commands.constructed().size)
            bus.devices = mapOf(bus.stale.deviceName to bus.stale)
            f.field("usbRecoveryOwner", Any()) // Exit/Save before Main handles the callback.
            f.launcher.onUsbHandshakeEnded(outcome)
            assertEquals(0, commands.constructed().size)
        }
    }


    @Test fun delayedFailureDoesNotResetSameAddressWithConflictingIdentity() = fixture { f ->
        val bus = RecoveryBus(f)
        identifyRecovery(f) { if (it === bus.stale) "target" else "other" }
        val reusedPath = bus.stale.deviceName
        `when`(bus.fresh.deviceName).thenReturn(reusedPath)
        bus.reenumerate()
        mockConstruction(UsbAccessoryMode::class.java).use { commands ->
            f.launcher.onUsbHandshakeEnded(CommManager.UsbHandshakeOutcome(
                com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL,
                bus.stale, f.comm.usbRecheckOwner(), f.recheck()))
            assertEquals(0, commands.constructed().size)
        }
    }


    @Test fun capturedHandshakeIdentitySurvivesLostSerialAccessBeforeCallback() = fixture { f ->
        val bus = RecoveryBus(f)
        identifyRecovery(f) { if (it === bus.stale) null else "other" }
        val original = UsbRecoveryDevice(bus.stale.deviceName, bus.stale.vendorId,
            bus.stale.productId, "Maker", "Box", "target")
        val reusedPath = bus.stale.deviceName
        `when`(bus.fresh.deviceName).thenReturn(reusedPath)
        bus.reenumerate()
        mockConstruction(UsbAccessoryMode::class.java).use { commands ->
            f.launcher.onUsbHandshakeEnded(CommManager.UsbHandshakeOutcome(
                com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL,
                bus.stale, f.comm.usbRecheckOwner(), f.recheck(), original))
            assertEquals(0, commands.constructed().size)
        }
    }

    private fun fixture(block: (Fixture) -> Unit) {
        mockStatic(SystemClock::class.java).use { clock ->
        mockStatic(AppCompatDelegate::class.java).use {
            val oldLogger = AppLog.LOGGER
            val oldClock = ConnectionArbiter.clock
            AppLog.LOGGER = object : AppLog.Logger {
                override fun println(priority: Int, tag: String, msg: String) = Unit
            }
            ConnectionArbiter.reset()
            ConnectionArbiter.clock = { 1000L }
            val f = Fixture()
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { f.now }
            try { block(f) } finally {
                f.scope.cancel()
                f.queue.drain()
                ConnectionArbiter.reset()
                ConnectionArbiter.clock = oldClock
                AppLog.LOGGER = oldLogger
            }
        }
        }
    }
}
