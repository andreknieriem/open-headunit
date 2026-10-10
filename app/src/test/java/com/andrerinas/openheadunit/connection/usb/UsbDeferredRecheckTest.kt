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
            field("usbRecoveryOwner", Any())
            launcher = spy(UsbLauncherManager(service))
        }
        fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(comm, value)
        fun begin(saved: ConnectionState.Disconnected? = null): CompletableDeferred<Unit> {
            assertTrue(launcher.beginAttempt(Tier.USB, "re-enumeration", saved))
            val done = CompletableDeferred<Unit>()
            launcher.launchAttempt(saved) { done.await() }
            queue.drain()
            return done
        }
        fun check(force: Boolean = true, manual: Boolean = false, saved: ConnectionState.Disconnected? = null) =
            launcher.checkAlreadyConnected(force, manual, saved)
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
            }.`when`(f.launcher).connectWithRetry(fresh, 3, Tier.USB, null)
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
                    .connectWithRetry(accessory, 3, Tier.USB, null)
            }
            mockConstruction(UsbAccessoryMode::class.java) { mode, _ ->
                `when`(mode.connectAndSwitch(normal, false)).thenAnswer {
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
                verify(f.launcher, times(if (outcome != "switch-failed") 1 else 0))
                    .checkAlreadyConnected(true, false, null, true)
                runBlocking {
                    verify(f.launcher, times(if (outcome == "accessory-ready") 1 else 0))
                        .connectWithRetry(accessory, 3, Tier.USB, null)
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
            f.check(manual = true)
            done.complete(Unit)
            f.queue.step() // Complete the owner, leaving its rescan queued on the service dispatcher.
            when (ending) {
                "stop" -> f.launcher.stopForUser()
                "preempt" -> f.launcher.preemptAttempt()
                "exit" -> f.comm.disconnect(honorKillOnDisconnect = false)
                "save" -> f.comm.disconnect(isUserExit = false, reason = CommManager.DisconnectReason.SETTINGS_RESTART)
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
        f.check()
        f.launcher.preemptAttempt()
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
            assertTrue(f.launcher.beginAttempt(Tier.USB, "IO switch"))
            f.launcher.launchAttempt(null, Dispatchers.IO) { ready.complete(Unit); done.await() }
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
        assertTrue(f.launcher.beginAttempt(Tier.USB, "Save switch", saved))
        val ready = CompletableDeferred<Unit>()
        val done = CompletableDeferred<Unit>()
        // Save's admission clock is an Android static mock on the test thread. Bind the
        // owner after launching so this case isolates completion ordering on a real IO thread.
        f.launcher.launchAttempt(null, Dispatchers.IO) { ready.complete(Unit); done.await() }
        UsbLauncherManager::class.java.getDeclaredField("attemptSettingsOwner")
            .apply { isAccessible = true }.set(f.launcher, saved)
        val job = checkNotNull(f.launcher.attemptJob)
        val completed = java.util.concurrent.CountDownLatch(1)
        job.invokeOnCompletion { completed.countDown() }
        runBlocking { withTimeout(5000) { ready.await() } }
        val lock = checkNotNull(UsbLauncherManager::class.java.getDeclaredField("attemptLock")
            .apply { isAccessible = true }.get(f.launcher))
        var returned = false
        synchronized(lock) {
            done.complete(Unit)
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
            verify(f.launcher, times(if (outcome == "expired") 2 else 1)).checkAlreadyConnected(true, false, null, false)
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
        UsbSwitchClaim.noteSwitched() // UsbAttachedActivity sent ACC_REQ_START and released its claim.
        f.check()                    // The 2 s attach fallback queues behind the attempt.
        done.complete(Unit)
        f.queue.drain()
        verify(f.launcher, times(1)).checkAlreadyConnected(true, false, null, false) // The fallback itself.
        verify(f.launcher, times(1)).checkAlreadyConnected(true, false, null, true)
    }

    @Test fun noActivitySwitchLeavesTheReplayAFullScan() = fixture { f ->
        val done = f.begin()
        f.check()
        done.complete(Unit)
        f.queue.drain()
        verify(f.launcher, times(2)).checkAlreadyConnected(true, false, null, false)
        verify(f.launcher, never()).checkAlreadyConnected(true, false, null, true)
    }

    @Test fun aReplayDuringALiveActivitySwitchIsAccessoryOnly() = fixture { f ->
        val done = f.begin()
        f.check()
        UsbSwitchClaim.stake()
        try {
            done.complete(Unit)
            f.queue.drain()
            verify(f.launcher, times(1)).checkAlreadyConnected(true, false, null, true)
        } finally { UsbSwitchClaim.release() }
    }

    private class RecoveryBus(f: Fixture) {
        val stale = accessory("/dev/bus/usb/001/002")
        val fresh = accessory("/dev/bus/usb/001/003")
        var devices: Map<String, android.hardware.usb.UsbDevice> = mapOf(stale.deviceName to stale)
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
        f.launcher.onUsbHandshakeEnded(com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL)
        @Suppress("UNCHECKED_CAST")
        val finished = UsbLauncherManager::class.java.getDeclaredField("attemptFinished")
            .apply { isAccessible = true }.get(f.launcher) as CompletableDeferred<Unit>
        bus.gate.countDown()
        return finished
    }

    private fun settled(f: Fixture, finished: CompletableDeferred<Unit>) {
        runBlocking { withTimeout(5000) { finished.await() } }
        f.queue.drain()
    }

    private fun stubRetry(f: Fixture, bus: RecoveryBus, onCall: () -> Unit = {}) = runBlocking {
        doAnswer { onCall(); Unit }.`when`(f.launcher).connectWithRetry(bus.stale, 0, Tier.USB, null)
        doAnswer { Unit }.`when`(f.launcher).connectWithRetry(bus.fresh, 3, Tier.USB, null)
        doAnswer { Unit }.`when`(f.launcher).connectWithRetry(bus.stale, 3, Tier.USB, null)
    }

    private fun switching(bus: RecoveryBus, issued: Boolean, entered: java.util.concurrent.CountDownLatch? = null,
        reenumerates: Boolean = false) = mockConstruction(UsbAccessoryMode::class.java) { mode, _ ->
        `when`(mode.connectAndSwitch(bus.stale, false)).thenAnswer {
            check(bus.gate.await(5, java.util.concurrent.TimeUnit.SECONDS))
            entered?.countDown()
            if (reenumerates) bus.reenumerate()
            issued
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
            assertTrue(inRetry.await(5, java.util.concurrent.TimeUnit.SECONDS))
            f.check()
            clearInvocations(f.service, f.usb)
            release.countDown()
            settled(f, finished)
            f.assertScans(1)
        }
    }

    @Test fun aRecoveryRetryTakesNoSecondAttempt() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = false).use {
            settled(f, endHandshake(f, bus))
            verify(f.launcher, times(1)).beginAttempt(Tier.USB, "USB recovery of ${UsbDeviceCompat(bus.stale).uniqueName}", null)
            runBlocking { verify(f.launcher, times(1)).connectWithRetry(bus.stale, 0, Tier.USB, null) }
        }
    }

    @Test fun aSuccessfulReswitchReplaysAccessoryOnly() = fixture { f ->
        val bus = RecoveryBus(f)
        stubRetry(f, bus)
        switching(bus, issued = true, reenumerates = true).use {
            settled(f, endHandshake(f, bus))
            f.queue.drain()
            verify(f.launcher, times(1)).checkAlreadyConnected(true, false, null, true)
            verify(f.launcher, never()).checkAlreadyConnected(true, false, null, false)
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
            verify(f.launcher, never()).checkAlreadyConnected(anyBoolean(), anyBoolean(), any(), anyBoolean())
        }
    }

    @Test fun aStepIsRefusedWhileTheSlotIsBusy() = fixture { f ->
        val bus = RecoveryBus(f)
        f.begin()
        switching(bus, issued = false).use { steps ->
            f.launcher.onUsbHandshakeEnded(com.andrerinas.openheadunit.aap.AapTransport.HandshakeFailure.SSL)
            assertEquals(0, steps.constructed().size)
            val count = UsbLauncherManager::class.java.getDeclaredField("staleSteps")
                .apply { isAccessible = true }.getInt(f.launcher)
            assertEquals(0, count)
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
