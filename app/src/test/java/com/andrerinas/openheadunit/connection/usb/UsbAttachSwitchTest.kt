package com.andrerinas.openheadunit.connection.usb

import com.andrerinas.openheadunit.connection.CommManager
import com.andrerinas.openheadunit.connection.ConnectionArbiter
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy
import com.andrerinas.openheadunit.connection.ConnectionPriorityPolicy.Tier
import com.andrerinas.openheadunit.connection.withAppExitDispatcherForTest
import com.andrerinas.openheadunit.utils.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock

/**
 * The attach screen's switch worker, as its thread runs it, against the real CommManager: the USB
 * request admitted on Main may be revoked before the worker starts, and then no USB call is made.
 */
class UsbAttachSwitchTest {
    private val clock = ConnectionArbiter.clock
    private val logger = AppLog.LOGGER

    @Before fun prepare() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        AppLog.LOGGER = object : AppLog.Logger { override fun println(priority: Int, tag: String, msg: String) = Unit }
    }

    @After fun restore() {
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = clock
        AppLog.LOGGER = logger
    }

    // The request is admitted, then revoked before the worker's first run: a user exit (the
    // service stays), Stop, or a newer USB admission. The worker must not switch, and its request
    // still ends. The control, revoked by nothing, switches once.
    @Test fun aRevokedAttachSwitchMakesNoUsbCall() {
        for (revoked in listOf("exit", "stop", "newer", "none")) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val states = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
            fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            field("_connectionState", states)
            field("connectionState", states)
            field("usbRecoveryOwner", Any())
            // Stop asks whether the app still owns its exit, which reads Settings.
            field("settings", mock(com.andrerinas.openheadunit.utils.Settings::class.java))
            fun admit() = checkNotNull(manager.admitUsbRequest(Tier.USB, "USB switch of phone", null,
                manager.usbRecheck(manager.appConnectionTicket())))
            val request = admit()
            UsbSwitchClaim.stake(request.claim)
            when (revoked) {
                "exit" -> manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
                // Stop's retained-group hand-off asserts Main; with Looper mocked, as the suite's
                // other Stop cases run it, both loopers read the same.
                "stop" -> org.mockito.Mockito.mockStatic(android.os.Looper::class.java).use {
                    manager.requestExplicitAppExit(manager.appConnectionTicket(), killProcess = false); manager.onAppRelaunched()
                }
                "newer" -> admit()
            }
            var switches = 0
            val outcome = UsbAttachSwitch.run(manager, request, null, "phone") { switches++; true }
            // Recorded after every production frame has returned.
            if (revoked == "none") {
                assertEquals("the valid attach switch did not switch", 1, switches)
                assertEquals(UsbAttachSwitch.Outcome.SWITCHED, outcome)
            } else {
                assertEquals("a revoked attach switch still made its USB call (revoked=$revoked switches=$switches)", 0, switches)
                assertEquals(UsbAttachSwitch.Outcome.REVOKED, outcome)
            }
            assertFalse("the switch's request kept its claim", ConnectionArbiter.holds(request.claim))
            ConnectionArbiter.reset()
        }
    }

    // Before first unlock the switch's authority is a bare arbiter claim taken on Main. The worker's
    // first run is delayed past unlock, and meanwhile the user picks wireless or a newer manual
    // connect takes the arbiter. The worker must not switch, and must leave the newer claim alone.
    // The control, whose claim still holds, switches once and ends its claim.
    @Test fun aLockedSwitchWhoseClaimWasLostMakesNoUsbCall() {
        for (lost in listOf("yield", "manual", "none")) {
            val claim = checkNotNull(ConnectionArbiter.claim(Tier.USB, ConnectionPriorityPolicy.Owner.USB, "USB switch of phone"))
            UsbSwitchClaim.stake(claim)
            val newer = when (lost) {
                "yield" -> { ConnectionArbiter.yieldToUser("Wi-Fi by hand", com.andrerinas.openheadunit.connection.ConnectionAdmission.UNRESTRICTED); null }
                "manual" -> checkNotNull(ConnectionArbiter.claim(Tier.USER, ConnectionPriorityPolicy.Owner.MANUAL, "192.0.2.1:5277"))
                else -> null
            }
            var switches = 0
            val outcome = UsbAttachSwitch.run(null, null, claim, "phone") { switches++; true }
            // Recorded after every production frame has returned.
            if (lost == "none") {
                assertEquals("the held locked switch did not switch", 1, switches)
                assertEquals(UsbAttachSwitch.Outcome.SWITCHED, outcome)
            } else {
                assertEquals("a locked switch whose claim was lost still made its USB call (lost=$lost switches=$switches)", 0, switches)
                assertEquals(UsbAttachSwitch.Outcome.REVOKED, outcome)
            }
            assertFalse("the locked switch kept its claim", ConnectionArbiter.holds(claim))
            if (newer != null) assertTrue("the old switch released the newer claim", ConnectionArbiter.holds(newer))
            ConnectionArbiter.reset()
        }
    }

    private class Queued : kotlinx.coroutines.CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) { tasks.addLast(block) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }

    // The home screen's USB button or a USB list row, on a phone that already granted permission:
    // the tap admits its request and queues the switch on IO. Before the worker runs, the overlay's
    // Cancel (the service's ACTION_DISCONNECT, a user exit) or a newer manual connect revokes it, or
    // the home view goes away and cancels the job. No USB call follows, and the request ends either
    // way; the newer claim is left alone. The control, revoked by nothing, switches once.
    @Test fun aManualUsbTapRevokedBeforeItsWorkerRunsMakesNoUsbCall() {
        for (revoked in listOf("cancel", "manual", "view-gone", "none")) {
            val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
            val states = MutableStateFlow<CommManager.ConnectionState>(CommManager.ConnectionState.Disconnected())
            fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
                .apply { isAccessible = true }.set(manager, value)
            field("_connectionState", states)
            field("connectionState", states)
            field("usbRecoveryOwner", Any())
            field("settings", mock(com.andrerinas.openheadunit.utils.Settings::class.java))
            val main = Queued()
            val io = Queued()
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + main)
            var switches = 0
            var outcome: UsbAttachSwitch.Outcome? = null
            val job = checkNotNull(UsbAttachSwitch.launchManual(scope, manager, "phone", io,
                switch = { switches++; true }) { outcome = it }) { "the tap was refused" }
            main.drain() // The job reaches its IO hop; the worker itself is still queued.
            var newer: ConnectionArbiter.Claim? = null
            when (revoked) {
                "cancel" -> manager.disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(manager.appConnectionTicket(), null), sendByeBye = false, isUserExit = true, honorKillOnDisconnect = false)
                "manual" -> newer = checkNotNull(ConnectionArbiter.claim(Tier.USER, ConnectionPriorityPolicy.Owner.MANUAL, "192.0.2.1:5277"))
                "view-gone" -> scope.coroutineContext[kotlinx.coroutines.Job]!!.cancel()
            }
            io.drain(); main.drain()
            // Recorded after every production frame has returned.
            if (revoked == "none") {
                assertEquals("the manual switch did not switch", 1, switches)
                assertEquals(UsbAttachSwitch.Outcome.SWITCHED, outcome)
            } else {
                assertEquals("a manual USB switch revoked before its worker ran still made its USB call (revoked=$revoked switches=$switches)", 0, switches)
            }
            assertTrue("the manual switch's job did not finish", job.isCompleted)
            // Whatever holds the arbiter now is the newer claim or nothing: the tap's request ended.
            val holder = ConnectionArbiter::class.java.getDeclaredField("holder").apply { isAccessible = true }.get(ConnectionArbiter)
            assertTrue("the manual switch kept its claim (revoked=$revoked)", holder == null || holder === newer)
            if (newer != null) assertTrue("the old switch released the newer claim", ConnectionArbiter.holds(newer))
            ConnectionArbiter.reset()
        }
    }
}
