package com.andrerinas.openheadunit.connection

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.SystemClock
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.AppComponent
import com.andrerinas.openheadunit.aap.AapService
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Owner
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.usb.UsbAccessoryMode
import com.andrerinas.openheadunit.connection.usb.UsbLauncherListener
import com.andrerinas.openheadunit.connection.usb.UsbLauncherManager
import com.andrerinas.openheadunit.connection.usb.UsbReceiver
import com.andrerinas.openheadunit.utils.AppLog
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.any
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockConstruction
import org.mockito.Mockito.mockStatic
import org.mockito.Mockito.mockingDetails
import org.mockito.Mockito.`when`
import kotlin.coroutines.CoroutineContext

/**
 * An owed USB scan through its real producers: the launcher's scan defers it behind a live attempt
 * or the arbiter refuses it, CommManager's retirement or the arbiter's settle hands it to the
 * service's give-back, and that give-back's delayed scan runs it. A user exit and an end that holds
 * automatic reconnection withdraw it wherever it waits.
 */
class UsbRecheckAuthorityTest {
    private class Queued : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    }

    /**
     * The service scope: nothing it is handed runs until a test advances it, and its delays run on
     * a virtual clock. [advance] runs only the work those delays resume; whatever that work starts
     * in turn (an admitted attempt's body) stays queued, as the attempt never runs here.
     */
    @OptIn(kotlinx.coroutines.InternalCoroutinesApi::class)
    private class Timed : CoroutineDispatcher(), kotlinx.coroutines.Delay {
        private val tasks = ArrayDeque<Runnable>()
        private val timers = mutableListOf<Pair<Long, kotlinx.coroutines.CancellableContinuation<Unit>>>()
        private var now = 0L
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: kotlinx.coroutines.CancellableContinuation<Unit>) {
            timers += (now + timeMillis) to continuation
        }
        private fun runQueued() = repeat(tasks.size) { tasks.removeFirst().run() }
        fun advance(ms: Long) {
            runQueued() // Start what was launched, up to its delay.
            now += ms
            val due = timers.filter { it.first <= now }
            timers.removeAll(due)
            due.forEach { (_, continuation) -> continuation.resumeWith(Result.success(Unit)) }
            runQueued()
        }
    }

    /**
     * [normalMode]: the attached phone is in normal mode (an ADB interface) and has no permission
     * yet, so an auto-start scan raises the launcher's permission dialog instead of switching.
     */
    private class Harness(state: ConnectionState, normalMode: Boolean = false) {
        val actions: ConnectionArbiter.Actions = mock(ConnectionArbiter.Actions::class.java)
        val manager: CommManager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val states = MutableStateFlow(state)
        private val teardown = Queued()
        val service: AapService = mock(AapService::class.java)
        private val usb = mock(UsbManager::class.java)
        val device: UsbDevice = mock(UsbDevice::class.java)
        @Volatile var permitted = !normalMode
        val launcher: UsbLauncherManager
        val serviceTasks = Timed()

        init {
            fun field(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            val settings = mock(Settings::class.java)
            `when`(settings.allowedDevices).thenReturn(emptySet())
            `when`(settings.autoStartOnUsb).thenReturn(true)
            field("context", service)
            field("_connectionState", states)
            field("connectionState", states)
            field("usbRecoveryOwner", Any())
            field("settings", settings)
            field("_scope", CoroutineScope(SupervisorJob() + teardown))
            val app = mock(App::class.java)
            val component = mock(AppComponent::class.java)
            App::class.java.getDeclaredField("component\$delegate").apply { isAccessible = true }.set(app, lazyOf(component))
            `when`(component.commManager).thenReturn(manager)
            `when`(component.settings).thenReturn(settings)
            `when`(service.applicationContext).thenReturn(app)
            // The attempt a scan starts never runs here; only whether one is admitted is observed.
            `when`(service.serviceScope).thenReturn(CoroutineScope(SupervisorJob() + serviceTasks))
            `when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.packageManager).thenReturn(mock(android.content.pm.PackageManager::class.java))
            `when`(service.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mock(android.content.SharedPreferences::class.java))
            `when`(service.getString(anyInt())).thenReturn("")
            // One attached phone: in accessory mode with permission, or in normal mode without.
            val path = "/dev/bus/usb/001/002"
            `when`(device.deviceName).thenReturn(path)
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(if (normalMode) 0x4ee7 else 0x2d00)
            val iface = mock(UsbInterface::class.java)
            val input = mock(UsbEndpoint::class.java)
            val output = mock(UsbEndpoint::class.java)
            `when`(device.interfaceCount).thenReturn(1)
            `when`(device.getInterface(0)).thenReturn(iface)
            if (normalMode) {
                `when`(iface.interfaceClass).thenReturn(0xFF)
                `when`(iface.interfaceSubclass).thenReturn(0x42)
                `when`(iface.interfaceProtocol).thenReturn(0x01)
            }
            `when`(iface.endpointCount).thenReturn(2)
            `when`(iface.getEndpoint(0)).thenReturn(input)
            `when`(iface.getEndpoint(1)).thenReturn(output)
            `when`(input.direction).thenReturn(UsbConstants.USB_DIR_IN)
            `when`(output.direction).thenReturn(UsbConstants.USB_DIR_OUT)
            `when`(input.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(output.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(usb.deviceList).thenReturn(hashMapOf(path to device))
            `when`(usb.hasPermission(device)).thenAnswer { permitted }
            launcher = UsbLauncherManager(service)
        }

        /** Publishes [claim]'s live attempt in the given non-terminal state, as connect() does. */
        fun liveAttempt(claim: ConnectionArbiter.Claim) {
            CommManager::class.java.getDeclaredField("connectionAttempt").apply { isAccessible = true }
                .set(manager, CommManager.Attempt(CommManager.ConnectionRequest(manager.appConnectionTicket(), claim, null, null, null)))
        }

        /** The launcher's production scan entry, as an attach or wake asks for it. */
        fun scan() = launcher.checkAlreadyConnected(force = true, recheck = manager.usbRecheckNow())

        /** Runs queued teardown, where retirement hands its owed scan on. */
        fun settle() { while (teardown.tasks.isNotEmpty()) teardown.tasks.removeFirst().run() }

        /** The USB half of every give-back the service was handed, in order. */
        fun usbGiveBacks(): List<Any> = mockingDetails(actions).invocations
            .filter { it.method.name == "giveBack" }
            .mapNotNull { it.arguments[1]?.takeIf { usb -> usb != false } }

        /** AapService.giveBack's USB branch, run once its settle has passed. */
        fun deliver(usb: Any) {
            launcher.checkAlreadyConnected(force = true, recheck = usb as UsbRecheck)
        }

        /** Whether a USB attempt was admitted: the slot a scan's attempt marks busy. */
        fun usbAttemptStarted() = launcher.isSwitchingToProjection()

        /** A user exit that keeps the service, or Stop followed by Open; anything else is neither. */
        fun end(end: String) {
            when (end) {
                "exit" -> manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
                "stop" -> { manager.requestExplicitAppExit(manager.appConnectionTicket(), killProcess = false); manager.onAppRelaunched() }
            }
        }
    }

    private fun scenario(state: ConnectionState, normalMode: Boolean = false, body: (Harness) -> Unit) {
        val savedLogger = AppLog.LOGGER
        val savedClock = ConnectionArbiter.clock
        val savedActions = ConnectionArbiter.actions
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        try {
            mockStatic(SystemClock::class.java).use {
            mockStatic(android.os.Looper::class.java).use {
            mockStatic(androidx.appcompat.app.AppCompatDelegate::class.java).use {
                val h = Harness(state, normalMode)
                ConnectionArbiter.actions = h.actions
                body(h)
            }
            }
            }
        } finally {
            ConnectionArbiter.reset()
            ConnectionArbiter.clock = savedClock
            ConnectionArbiter.actions = savedActions
            AppLog.LOGGER = savedLogger
        }
    }

    // A USB scan deferred behind attempt A is handed to the service's give-back when A ends. A
    // user exit during that give-back's settle withdraws it: once delivered it starts nothing. The
    // control, with no exit, starts a USB attempt.
    @Test fun aUserExitWithdrawsAUsbCheckAlreadyHandedToTheGiveBack() {
        for (exit in listOf(true, false)) scenario(ConnectionState.Connecting) { h ->
            val claim = checkNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "A"))
            h.liveAttempt(claim)
            h.scan()
            assertFalse(h.usbAttemptStarted())
            h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            h.settle()
            ConnectionArbiter.release(claim, sessionFormed = false)
            val handed = h.usbGiveBacks()
            assertEquals("A's end did not hand its deferred USB check on", 1, handed.size)
            if (exit) h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
            h.deliver(handed.single())
            // Recorded after every production frame has returned.
            val started = h.usbAttemptStarted()
            if (exit) assertFalse("a USB check scheduled before a user Exit still started a USB attempt after it", started)
            else assertTrue("the delivered USB check started no USB attempt", started)
        }
    }

    // Automatic attempt A forms its session with a USB scan deferred behind its handshake. The
    // session then ends holding automatic reconnection (PROJECTION_UNRAISED): no USB check may be
    // handed on. The control, an ordinary end, hands exactly one on.
    @Test fun anEndThatHoldsAutomaticReconnectionHandsNoUsbCheckOn() {
        for (end in listOf(CommManager.DisconnectReason.PROJECTION_UNRAISED, CommManager.DisconnectReason.CONNECTION_ENDED)) {
            scenario(ConnectionState.StartingTransport) { h ->
                h.liveAttempt(checkNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "A")))
                h.scan()
                h.states.value = ConnectionState.HandshakeComplete
                h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false, reason = end)
                h.settle()
                // Recorded after every production frame has returned.
                val usbGiveBacks = h.usbGiveBacks().size
                if (end == CommManager.DisconnectReason.PROJECTION_UNRAISED) {
                    assertEquals("an end that holds automatic reconnection still gave a USB check back (end=$end usbGiveBacks=$usbGiveBacks)",
                        0, usbGiveBacks)
                } else {
                    assertEquals("an ordinary end did not give its deferred USB check back (usbGiveBacks=$usbGiveBacks)", 1, usbGiveBacks)
                }
            }
        }
    }

    // A user's connect holds the arbiter, so the scan's USB attempt is refused and owed back. The
    // user's attempt ends without a session and the arbiter hands the scan to the give-back; a
    // user exit during its settle withdraws it. The control, with no exit, starts a USB attempt.
    @Test fun aUserExitWithdrawsARefusedUsbScanAlreadyHandedToTheGiveBack() {
        for (exit in listOf(true, false)) scenario(ConnectionState.Disconnected()) { h ->
            val user = checkNotNull(ConnectionArbiter.claim(Tier.USER, Owner.MANUAL, "192.0.2.1:5277"))
            h.scan()
            assertFalse("the scan was not refused while the user's attempt held the arbiter", h.usbAttemptStarted())
            ConnectionArbiter.release(user, sessionFormed = false)
            val handed = h.usbGiveBacks()
            assertEquals("the refused USB scan was not given back", 1, handed.size)
            if (exit) h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
            h.deliver(handed.single())
            // Recorded after every production frame has returned.
            val started = h.usbAttemptStarted()
            if (exit) assertFalse("a refused USB scan given back before a user Exit still started a USB attempt after it", started)
            else assertTrue("the delivered USB scan started no USB attempt", started)
        }
    }

    /**
     * The platform's side of the launcher's permission dialogs: each dialog's PendingIntent, with
     * the reply address set on the Intent it was built from, and a reply delivered to it through
     * the real receiver and listener. Switches are counted at [UsbAccessoryMode] construction.
     */
    private class Dialogs(private val h: Harness, private val broadcasts: List<android.content.Intent>,
        private val switchModes: org.mockito.MockedConstruction<UsbAccessoryMode>) {
        /** The reply addresses of every dialog raised so far, oldest first. */
        fun raised(): List<android.net.Uri> = broadcasts.map { intent ->
            mockingDetails(intent).invocations.last { it.method.name == "setData" }.arguments[0] as android.net.Uri
        }
        fun answer(dialog: android.net.Uri, granted: Boolean) {
            if (granted) h.permitted = true
            val reply = mock(android.content.Intent::class.java)
            `when`(reply.action).thenReturn(UsbReceiver.ACTION_USB_DEVICE_PERMISSION)
            `when`(reply.data).thenReturn(dialog)
            @Suppress("DEPRECATION")
            `when`(reply.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)).thenReturn(h.device)
            `when`(reply.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)).thenReturn(granted)
            UsbReceiver(UsbLauncherListener(h.launcher)).onReceive(h.service, reply)
        }
        fun switches() = switchModes.constructed().size
    }

    private fun withPermissionDialogs(h: Harness, body: (Dialogs) -> Unit) {
        val broadcasts = mutableListOf<android.content.Intent>()
        mockStatic(android.net.Uri::class.java).use { uri ->
        mockStatic(android.app.PendingIntent::class.java).use { pending ->
        mockConstruction(android.content.Intent::class.java).use {
        mockConstruction(UsbAccessoryMode::class.java).use { switches ->
            // A reply address keeps whatever the producer put in it, as the platform does.
            uri.`when`<android.net.Uri> { android.net.Uri.fromParts(anyString(), anyString(), any()) }.thenAnswer { call ->
                val reply = mock(android.net.Uri::class.java)
                `when`(reply.scheme).thenReturn(call.getArgument<String>(0))
                `when`(reply.schemeSpecificPart).thenReturn(call.getArgument<String>(1))
                `when`(reply.fragment).thenReturn(call.getArgument<String?>(2))
                reply
            }
            pending.`when`<android.app.PendingIntent> {
                android.app.PendingIntent.getBroadcast(any(), anyInt(), any(), anyInt())
            }.thenAnswer { call ->
                broadcasts += call.getArgument<android.content.Intent>(2)
                mock(android.app.PendingIntent::class.java)
            }
            body(Dialogs(h, broadcasts, switches))
        }
        }
        }
        }
    }

    // A USB check deferred behind attempt A is handed on when A ends. Delivered, it finds the phone
    // in normal mode without permission and raises the launcher's permission dialog. A user exit
    // then withdraws that scan: the late grant must not start the switch. Controls: with no exit
    // the grant starts it; after Stop and Open (a new ticket) it starts nothing.
    @Test fun aLatePermissionGrantNeverRevivesAWithdrawnUsbScan() {
        for (end in listOf("exit", "none", "stop")) scenario(ConnectionState.Connecting, normalMode = true) { h ->
            withPermissionDialogs(h) { dialogs ->
                val claim = checkNotNull(ConnectionArbiter.claim(Tier.WIRELESS_HANDSHAKE, Owner.WIRELESS_STACK, "A"))
                h.liveAttempt(claim)
                h.scan()
                h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
                h.settle()
                ConnectionArbiter.release(claim, sessionFormed = false)
                val handed = h.usbGiveBacks()
                assertEquals("A's end did not hand its deferred USB check on", 1, handed.size)
                h.deliver(handed.single())
                val dialog = checkNotNull(dialogs.raised().singleOrNull()) { "the delivered USB check raised no permission dialog" }
                when (end) {
                    "exit" -> h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
                    "stop" -> { h.manager.requestExplicitAppExit(h.manager.appConnectionTicket(), killProcess = false); h.manager.onAppRelaunched() }
                }
                // The user grants the dialog late.
                dialogs.answer(dialog, granted = true)
                // Recorded after every production frame has returned.
                val switched = dialogs.switches()
                when (end) {
                    "exit" -> assertEquals("a withdrawn USB scan's late permission grant still started the switch (end=$end switches=$switched)", 0, switched)
                    "none" -> assertEquals("the granted USB scan did not start the switch (switches=$switched)", 1, switched)
                    else -> assertEquals("a grant from before Stop started the switch (switches=$switched)", 0, switched)
                }
            }
        }
    }

    // Two scans raise two stacked permission dialogs for the same phone before the user answers.
    // The user dismisses the newer one and grants the older one: the older dialog continues its
    // own scan and starts the switch. The control withdraws both scans with a user exit before the
    // grant: the older dialog then starts nothing.
    @Test fun theOlderOfTwoStackedPermissionDialogsContinuesItsOwnScan() {
        for (exit in listOf(false, true)) scenario(ConnectionState.Disconnected(), normalMode = true) { h ->
            withPermissionDialogs(h) { dialogs ->
                h.scan()
                h.scan()
                val raised = dialogs.raised()
                assertEquals("two scans did not raise two stacked dialogs", 2, raised.size)
                if (exit) h.manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(h.manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
                dialogs.answer(raised[1], granted = false)
                dialogs.answer(raised[0], granted = true)
                // Recorded after every production frame has returned.
                val switched = dialogs.switches()
                if (exit) assertEquals("a withdrawn scan's older dialog started the switch (switches=$switched)", 0, switched)
                else assertEquals("a grant on the older of two stacked permission dialogs started nothing (switches=$switched)", 1, switched)
            }
        }
    }

    // A normal-mode phone attaches and the service schedules its fallback check, in case the
    // attach screen never runs. A user exit that keeps the service lands inside that delay and
    // withdraws the scan the attach asked for: the fallback starts no switch. Controls: with no
    // exit it starts exactly one; after Stop and Open (a new ticket) it starts none.
    @Test fun aUserExitWithdrawsTheAttachFallbackScheduledBeforeIt() {
        for (end in listOf("exit", "none", "stop")) scenario(ConnectionState.Disconnected(), normalMode = true) { h ->
            h.permitted = true
            mockConstruction(UsbAccessoryMode::class.java).use { switches ->
                UsbLauncherListener(h.launcher).onUsbAttach(h.device)
                h.end(end)
                h.serviceTasks.advance(UsbLauncherManager.ATTACH_FALLBACK_DELAY_MS)
                // Recorded after every production frame has returned.
                val switched = switches.constructed().size
                when (end) {
                    "exit" -> assertEquals("an attach fallback scheduled before a user Exit still started a switch after it (switches=$switched)", 0, switched)
                    "none" -> assertEquals("the attach fallback did not start the switch (switches=$switched)", 1, switched)
                    else -> assertEquals("an attach fallback from before Stop started a switch (switches=$switched)", 0, switched)
                }
            }
        }
    }

    // The accessory detaches and the service schedules its re-sync after a cooldown. A user exit
    // that keeps the service lands inside it: the re-sync admits no USB attempt. Controls: with no
    // exit it admits exactly one; after Stop and Open it admits none.
    @Test fun aUserExitWithdrawsTheAccessoryDetachResyncScheduledBeforeIt() {
        for (end in listOf("exit", "none", "stop")) scenario(ConnectionState.Disconnected()) { h ->
            UsbLauncherListener(h.launcher).onUsbAccessoryDetach()
            h.end(end)
            h.serviceTasks.advance(1500)
            // Recorded after every production frame has returned.
            val started = h.usbAttemptStarted()
            when (end) {
                "exit" -> assertFalse("an accessory-detach re-sync scheduled before a user Exit still started a USB attempt after it", started)
                "none" -> assertTrue("the accessory-detach re-sync started no USB attempt", started)
                else -> assertFalse("an accessory-detach re-sync from before Stop started a USB attempt", started)
            }
        }
    }

    // The USB list and the home screen raise their own permission dialog for a tapped phone; its
    // reply reaches the service's listener with no launcher token. A user exit before the answer
    // withdraws the scan the tap minted: the grant starts no switch. Controls: with no exit the
    // grant starts one; after Stop and Open it starts none.
    @Test fun aDialogRaisedOutsideTheLauncherAnswersWithTheScanMintedWhenItWasRaised() {
        for (end in listOf("exit", "none", "stop")) scenario(ConnectionState.Disconnected(), normalMode = true) { h ->
            withPermissionDialogs(h) { dialogs ->
                // What UsbListFragment and HomeFragment build when the user taps the phone.
                UsbReceiver.createPermissionPendingIntent(h.service, h.manager.usbRecheckNow())
                val dialog = checkNotNull(dialogs.raised().singleOrNull()) { "the tap raised no permission dialog" }
                h.end(end)
                dialogs.answer(dialog, granted = true)
                // Recorded after every production frame has returned.
                val switched = dialogs.switches()
                when (end) {
                    "exit" -> assertEquals("a dialog raised before a user Exit still started the switch when granted after it (switches=$switched)", 0, switched)
                    "none" -> assertEquals("the granted dialog did not start the switch (switches=$switched)", 1, switched)
                    else -> assertEquals("a dialog raised before Stop started the switch (switches=$switched)", 0, switched)
                }
            }
        }
    }
}
