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
import com.andrerinas.openheadunit.connection.usb.UsbLauncherManager
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
import org.mockito.Mockito.anyInt
import org.mockito.Mockito.anyString
import org.mockito.Mockito.mock
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

    private class Harness(state: ConnectionState) {
        val actions: ConnectionArbiter.Actions = mock(ConnectionArbiter.Actions::class.java)
        val manager: CommManager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val states = MutableStateFlow(state)
        private val teardown = Queued()
        private val service = mock(AapService::class.java)
        private val usb = mock(UsbManager::class.java)
        val launcher: UsbLauncherManager

        init {
            fun field(name: String, value: Any?) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            val settings = mock(Settings::class.java)
            `when`(settings.allowedDevices).thenReturn(emptySet())
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
            `when`(service.serviceScope).thenReturn(CoroutineScope(SupervisorJob() + Queued()))
            `when`(service.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
            `when`(service.packageManager).thenReturn(mock(android.content.pm.PackageManager::class.java))
            `when`(service.getSharedPreferences(anyString(), anyInt()))
                .thenReturn(mock(android.content.SharedPreferences::class.java))
            // One attached phone already in accessory mode, with permission.
            val device = mock(UsbDevice::class.java)
            val path = "/dev/bus/usb/001/002"
            `when`(device.deviceName).thenReturn(path)
            `when`(device.vendorId).thenReturn(0x18d1)
            `when`(device.productId).thenReturn(0x2d00)
            val iface = mock(UsbInterface::class.java)
            val input = mock(UsbEndpoint::class.java)
            val output = mock(UsbEndpoint::class.java)
            `when`(device.interfaceCount).thenReturn(1)
            `when`(device.getInterface(0)).thenReturn(iface)
            `when`(iface.endpointCount).thenReturn(2)
            `when`(iface.getEndpoint(0)).thenReturn(input)
            `when`(iface.getEndpoint(1)).thenReturn(output)
            `when`(input.direction).thenReturn(UsbConstants.USB_DIR_IN)
            `when`(output.direction).thenReturn(UsbConstants.USB_DIR_OUT)
            `when`(input.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(output.type).thenReturn(UsbConstants.USB_ENDPOINT_XFER_BULK)
            `when`(usb.deviceList).thenReturn(hashMapOf(path to device))
            `when`(usb.hasPermission(device)).thenReturn(true)
            launcher = UsbLauncherManager(service)
        }

        /** Publishes [claim]'s live attempt in the given non-terminal state, as connect() does. */
        fun liveAttempt(claim: ConnectionArbiter.Claim) {
            CommManager::class.java.getDeclaredField("connectionAttempt").apply { isAccessible = true }
                .set(manager, CommManager.Attempt(claim))
        }

        /** The launcher's production scan entry, as an attach or wake asks for it. */
        fun scan() = launcher.checkAlreadyConnected(force = true)

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
    }

    private fun scenario(state: ConnectionState, body: (Harness) -> Unit) {
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
                val h = Harness(state)
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
            h.manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            h.settle()
            ConnectionArbiter.release(claim, sessionFormed = false)
            val handed = h.usbGiveBacks()
            assertEquals("A's end did not hand its deferred USB check on", 1, handed.size)
            if (exit) h.manager.disconnect(sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
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
                h.manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false, reason = end)
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
            if (exit) h.manager.disconnect(sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
            h.deliver(handed.single())
            // Recorded after every production frame has returned.
            val started = h.usbAttemptStarted()
            if (exit) assertFalse("a refused USB scan given back before a user Exit still started a USB attempt after it", started)
            else assertTrue("the delivered USB scan started no USB attempt", started)
        }
    }
}
