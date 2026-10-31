package lifecycle

import android.hardware.usb.UsbDevice
import kotlinx.coroutines.*
import java.util.concurrent.TimeUnit

/** The IO/SDK doubles count effects; assertions run only after the production frame returned. */
object UsbEffectsMain {
    private val rows = mutableListOf<String>()
    private var failed = 0

    private fun case(name: String, body: () -> Unit) {
        try { body(); rows += "{\"case\":\"$name\",\"passed\":true}"; println("PASS $name") }
        catch (failure: Throwable) {
            failed++
            rows += "{\"case\":\"$name\",\"passed\":false}"
            println("FAIL $name: ${failure.message}")
            failure.printStackTrace()
        }
    }

    private fun complete(queue: QueuedDispatcher, job: Job) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!job.isCompleted && System.nanoTime() < deadline) {
            queue.drain()
            Thread.sleep(1)
        }
        queue.drain()
        check(job.isCompleted) { "worker did not complete" }
    }

    private fun stop(c: CommManager, reopen: Boolean) {
        // REQUEST_STOP
        if (reopen) c.onAppRelaunched()
    }

    private suspend fun openUsb(c: CommManager) {
        val request = checkNotNull(c.admitUsbRequest(ConnectionPriorityPolicy.Tier.USB,
            "attached phone", null, c.usbRecheckNow()))
        try { check(c.connect(UsbDevice(), request)) } finally { c.endRequest(request) }
    }

    private fun handshake(mode: String) = runBlocking {
        val c = CommManager()
        val queue = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queue)
        var handshakes = 0
        var callbacks = 0
        var recoveries = 0
        try {
            // Real USB admission, publication and connection, before the service queues SSL.
            // USB_INITIAL_DISCONNECT
            c.cleanups()
            openUsb(c)
            check(c.state is ConnectionState.Connected)
            // CAPTURE_HANDSHAKE
            AapTransport.handshakeHook = { handshakes++ }
            AapTransport.forcedFailure = AapTransport.HandshakeFailure.SSL
            c.onUsbHandshakeEnded = { outcome ->
                callbacks++
                // OUTCOME_RECHECK
                if (c.ownsUsbRecheck(outcome.recoveryOwner) && c.isCurrent(recheck) &&
                    StaleAccessoryRecoveryPolicy.decide(outcome.failure, true, false, 0) == StaleAccessoryRecoveryPolicy.Step.RESWITCH) {
                    c.admitUsbRequest(ConnectionPriorityPolicy.Tier.USB, "callback recovery", null, recheck)?.let {
                        recoveries++
                        c.endRequest(it)
                    }
                }
            }
            val work = scope.launch {
                // START_HANDSHAKE
            }
            when (mode) {
                "stop" -> stop(c, false)
                "stop-open" -> stop(c, true)
                "home-exit" -> { /* USB_HOME_EXIT */ }
                "replacement" -> {
                    // Publication waits for the old physical USB owner to retire. Pump that
                    // fixture queue before awaiting B, keeping A's captured SSL work queued.
                    val replacement = launch(start = CoroutineStart.UNDISPATCHED) { openUsb(c) }
                    c.cleanups()
                    replacement.join()
                    check(c.state is ConnectionState.Connected)
                }
            }
            complete(queue, work)
            val expected = if (mode == "accepted") 1 else 0
            check(handshakes == expected) { "handshake effects=$handshakes expected=$expected" }
            check(callbacks == expected) { "handshake callbacks=$callbacks expected=$expected" }
            check(recoveries == expected) { "recovery admissions=$recoveries expected=$expected" }
        } finally {
            AapTransport.handshakeHook = null
            AapTransport.forcedFailure = null
            c.onUsbHandshakeEnded = null
            scope.cancel()
            c.close()
        }
    }

    private fun reading(mode: String) = runBlocking {
        val c = CommManager()
        val queue = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + queue)
        try {
            // ACCEPTED_HANDSHAKE
            check(c.state is ConnectionState.HandshakeComplete)
            // CAPTURE_READING
            val before = AapRead.creations
            val work = scope.launch {
                // START_READING
            }
            if (mode != "accepted") stop(c, mode == "stop-open")
            complete(queue, work)
            val expected = if (mode == "accepted") 1 else 0
            check(AapRead.creations - before == expected) {
                "read-loop effects=${AapRead.creations - before} expected=$expected"
            }
        } finally { scope.cancel(); c.close() }
    }

    private fun driverPermissionGap(mode: String) = runBlocking {
        val c = CommManager()
        val request = request(c)
        val usb = c.context.usb
        val handle = android.hardware.usb.UsbDeviceConnection()
        var opens = 0
        val admission = ConnectionAdmission { action ->
            if (c.isValid(request)) { action(); true } else false
        }
        // DRIVER_CONSTRUCTION
        try {
            usb.permissionHook = {
                usb.permissionHook = null
                when (mode) {
                    "stop" -> stop(c, false)
                    "stop-open" -> stop(c, true)
                    "replacement" -> request(c)
                }
            }
            usb.openHook = { opens++; handle }
            val connected = driver.connect()
            val expected = if (mode == "accepted") 1 else 0
            check(opens == expected && connected == (expected == 1)) {
                "USB open after permission boundary: opens=$opens connected=$connected expected=$expected"
            }
        } finally { driver.disconnect(); c.endRequest(request); c.close() }
    }

    private fun accessoryEffects(mode: String, reset: Boolean) {
        val c = CommManager()
        val request = request(c)
        val usb = c.context.usb
        val handle = android.hardware.usb.UsbDeviceConnection()
        val accessory = UsbAccessoryMode(usb)
        var opens = 0
        val admission = ConnectionAdmission { action ->
            if (c.isValid(request)) { action(); true } else false
        }
        UsbNative.resets = 0
        try {
            usb.openHook = {
                opens++
                if (mode == "after-open") stop(c, true)
                handle
            }
            if (mode == "stop") stop(c, false)
            if (mode == "stop-open") stop(c, true)
            // ACCESSORY_EFFECT
            val expectedOpen = if (mode == "accepted" || mode == "after-open") 1 else 0
            val effects = if (reset) UsbNative.resets else handle.controls.get()
            check(opens == expectedOpen && accepted == (mode == "accepted")) {
                "accessory entry: reset=$reset mode=$mode opens=$opens accepted=$accepted"
            }
            check(if (mode == "accepted") effects > 0 else effects == 0) {
                "accessory effects after withdrawal: reset=$reset mode=$mode effects=$effects"
            }
            check(handle.closes.get() == expectedOpen) { "the operation did not close exactly its own opened handle" }
        } finally { c.endRequest(request); c.close() }
    }

    private fun driverLocalCloseBarrier() {
        val c = CommManager()
        val request = request(c)
        val usb = c.context.usb
        val handle = android.hardware.usb.UsbDeviceConnection()
        val closing = java.util.concurrent.CountDownLatch(1)
        val finishClose = java.util.concurrent.CountDownLatch(1)
        val retired = java.util.concurrent.CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        val admission = ConnectionAdmission { action ->
            if (c.isValid(request)) { action(); true } else false
        }
        // DRIVER_CONSTRUCTION
        usb.openHook = { stop(c, false); handle }
        handle.closeHook = { closing.countDown(); check(finishClose.await(5, TimeUnit.SECONDS)) }
        val opener = Thread {
            try { runBlocking { driver.connect() } } catch (failure: Throwable) { errors.add(failure) }
        }
        val cleaner = Thread {
            try { driver.disconnect() } catch (failure: Throwable) { errors.add(failure) }
            finally { retired.countDown() }
        }
        try {
            opener.start()
            check(closing.await(3, TimeUnit.SECONDS)) { "revoked open did not reach local retirement" }
            cleaner.start()
            check(!retired.await(150, TimeUnit.MILLISECONDS)) {
                "disconnect returned while the retired open still owned a closing handle"
            }
            check(cleaner.state != Thread.State.BLOCKED) { "retirement waited under the driver monitor" }
        } finally {
            finishClose.countDown()
            opener.join(5000)
            if (cleaner.state != Thread.State.NEW) cleaner.join(5000)
            handle.closeHook = null
            driver.disconnect()
            c.endRequest(request)
            c.close()
        }
        check(!opener.isAlive && !cleaner.isAlive && errors.isEmpty()) { "local retirement did not finish: $errors" }
    }

    private fun request(c: CommManager) = checkNotNull(c.admitUsbRequest(
        ConnectionPriorityPolicy.Tier.USER, "manual switch", null, c.usbRecheckNow()))

    private fun staleWorker(locked: Boolean) {
        val c = if (locked) null else CommManager()
        val old = if (locked) null else request(checkNotNull(c))
        val oldClaim = old?.claim ?: fixtureClaim()
        stake(oldClaim)
        val current = if (locked) null else request(checkNotNull(c))
        val currentClaim = current?.claim ?: fixtureClaim()
        stake(currentClaim)
        var usbCalls = 0
        try {
            val outcome = UsbAttachSwitch.run(c, old, oldClaim.takeIf { locked }, "old phone") { usbCalls++; true }
            // A late completion may repeat cleanup after run()'s finally.
            release(oldClaim)
            val live = UsbSwitchClaim.isLive()
            val fallback = if (!live && c != null) request(c) else null
            check(outcome == UsbAttachSwitch.Outcome.REVOKED && usbCalls == 0)
            check(live) { "old worker cleared successor switch guard; fallback=${fallback != null}" }
            check(ConnectionArbiter.holds(currentClaim)) { "fallback replaced successor claim" }
        } finally {
            release(currentClaim)
            if (current != null) c?.endRequest(current)
            c?.close()
            ConnectionArbiter.reset()
        }
    }

    private fun lateCompletion() {
        val c = CommManager()
        val oldMain = QueuedDispatcher()
        val newMain = QueuedDispatcher()
        val io = QueuedDispatcher()
        val oldScope = CoroutineScope(SupervisorJob() + oldMain)
        val newScope = CoroutineScope(SupervisorJob() + newMain)
        var oldCalls = 0
        var newCalls = 0
        try {
            val old = checkNotNull(UsbAttachSwitch.launchManual(oldScope, c, "A", io,
                switch = { oldCalls++; true }, onOutcome = {}))
            oldMain.drain() // A is admitted and waiting to enter IO.
            val current = checkNotNull(UsbAttachSwitch.launchManual(newScope, c, "B", io,
                switch = { newCalls++; true }, onOutcome = {}))
            check(UsbSwitchClaim.isLive())
            old.cancel()
            io.drain(); oldMain.drain()
            check(old.isCompleted)
            val kept = UsbSwitchClaim.isLive()
            newMain.drain(); io.drain(); newMain.drain()
            check(current.isCompleted && oldCalls == 0 && newCalls == 1)
            check(kept) { "old job completion released B before B's first USB effect" }
            check(!UsbSwitchClaim.isLive()) { "B did not release its own guard" }
        } finally { oldScope.cancel(); newScope.cancel(); c.close() }
    }

    private fun countedByOwner() {
        val old = fixtureClaim()
        stake(old)
        val current = fixtureClaim()
        stake(current)
        val before = UsbSwitchClaim.switches()
        try {
            note(old)
            check(UsbSwitchClaim.switches() == before) { "old worker counted a switch in B's interval" }
            note(current); note(current)
            check(UsbSwitchClaim.switches() == before + 1) { "same owner counted twice" }
            release(old)
            check(UsbSwitchClaim.isLive())
        } finally { release(current); ConnectionArbiter.reset() }
    }

    private fun acceptedSwitch(locked: Boolean) {
        val c = if (locked) null else CommManager()
        val request = c?.let(::request)
        val owner = request?.claim ?: fixtureClaim()
        stake(owner)
        val before = UsbSwitchClaim.switches()
        var effects = 0
        try {
            val outcome = UsbAttachSwitch.run(c, request, owner.takeIf { locked }, "phone") { effects++; true }
            check(outcome == UsbAttachSwitch.Outcome.SWITCHED && effects == 1)
            check(!UsbSwitchClaim.isLive() && UsbSwitchClaim.switches() == before + 1)
            release(owner)
            check(!UsbSwitchClaim.isLive())
        } finally { release(owner); c?.close(); ConnectionArbiter.reset() }
    }

    // SWITCH_ADAPTERS

    @JvmStatic fun main(args: Array<String>) {
        if (args.contentEquals(arrayOf("libusb"))) {
            for (mode in listOf("stop", "stop-open", "replacement", "accepted"))
                case("USB.libusb-permission.$mode") { driverPermissionGap(mode) }
            case("USB.libusb-local-close-barrier") { driverLocalCloseBarrier() }
            println("RESULTS=[${rows.joinToString(",")}]")
            check(failed == 0) { "$failed Libusb authority regression cases failed" }
            return
        }
        for (mode in listOf("stop", "stop-open", "home-exit", "replacement", "accepted"))
            case("F3.handshake.$mode") { handshake(mode) }
        for (mode in listOf("stop", "stop-open", "accepted"))
            case("F3.reading.$mode") { reading(mode) }
        for (mode in listOf("stop", "stop-open", "replacement", "accepted"))
            case("USB.driver-permission.$mode") { driverPermissionGap(mode) }
        case("USB.driver-local-close-barrier") { driverLocalCloseBarrier() }
        for (reset in listOf(false, true)) for (mode in listOf("stop", "stop-open", "after-open", "accepted"))
            case("USB.${if (reset) "reset" else "aoa"}.$mode") { accessoryEffects(mode, reset) }
        case("F6.old-worker-finally") { staleWorker(false) }
        case("F6.locked-old-worker-finally") { staleWorker(true) }
        case("F6.old-job-completion") { lateCompletion() }
        case("F6.switch-count-owner") { countedByOwner() }
        case("F6.accepted-switch") { acceptedSwitch(false) }
        case("F6.locked-accepted-switch") { acceptedSwitch(true) }
        println("RESULTS=[${rows.joinToString(",")}]")
        check(failed == 0) { "$failed USB authority regression cases failed" }
    }
}
