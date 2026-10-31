package com.andrerinas.openheadunit.connection

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
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import kotlin.coroutines.CoroutineContext

class UsbHandshakeRecheckTest {
    // The manager's cleanup scope: queued, so a retirement runs only when a case settles.
    private class Queued : CoroutineDispatcher() {
        val tasks = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks.addLast(block) }
    }

    private var manager: CommManager? = null

    /** The live attempt ends without a session: its teardown runs on the next settle. */
    private fun endAttempt(userExit: Boolean = false) {
        checkNotNull(manager).disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(checkNotNull(manager).appConnectionTicket(), null), sendByeBye = false, isUserExit = userExit, honorKillOnDisconnect = false)
    }

    @Test fun usbScanWaitsForEveryPreHandshakeStageAndReturnsAfterFailure() {
        for (stage in listOf(ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.StartingTransport)) {
            scenario(stage) { scan, claim, returned, settle ->
                scan()
                settle()
                assertTrue(returned.isEmpty())
                endAttempt()
                ConnectionArbiter.release(claim, sessionFormed = false)
                settle()
                assertEquals("stage=$stage", listOf(false to true), returned)
                settle()
                assertEquals(1, returned.size)
            }
        }
    }

    @Test fun successfulHandshakeKeepsUsbParkedUntilSessionEndsUnlessUserExits() {
        for (userExit in listOf(false, true)) {
            scenario(ConnectionState.StartingTransport) { scan, claim, returned, settle ->
                scan()
                val states = checkNotNull(manager).connectionState as MutableStateFlow<ConnectionState>
                states.value = ConnectionState.HandshakeComplete
                ConnectionArbiter.release(claim, sessionFormed = true)
                settle()
                assertTrue(returned.isEmpty())
                endAttempt(userExit)
                settle()
                assertEquals(if (userExit) emptyList() else listOf(false to true), returned)
            }
        }
    }

    @Test fun disabledAutomaticUsbScanDoesNotBecomeAForcedRetry() {
        scenario(ConnectionState.Connected, force = false) { scan, claim, returned, settle ->
            scan()
            endAttempt()
            ConnectionArbiter.release(claim, sessionFormed = false)
            settle()
            assertTrue(returned.isEmpty())
        }
    }

    @Test fun usbAttemptDoesNotOweASecondScanToItself() {
        scenario(ConnectionState.StartingTransport, claimOwner = Owner.USB) { scan, claim, returned, settle ->
            scan()
            endAttempt()
            ConnectionArbiter.release(claim, sessionFormed = false)
            settle()
            assertTrue(returned.isEmpty())
        }
    }

    @Test fun completedHandshakeDoesNotAcquireANewUsbDebt() {
        for (stage in listOf(ConnectionState.HandshakeComplete, ConnectionState.TransportStarted)) {
            scenario(stage) { scan, claim, returned, settle ->
                scan()
                ConnectionArbiter.release(claim, sessionFormed = true)
                endAttempt()
                ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
                settle()
                assertTrue("stage=$stage", returned.isEmpty())
            }
        }
    }

    @Test fun handshakeFinishingBeforeConnectFinallyKeepsTheDebtUntilSessionEnd() {
        for (stage in listOf(ConnectionState.HandshakeComplete, ConnectionState.TransportStarted)) {
            scenario(ConnectionState.StartingTransport, finishBeforeScan = stage) { _, _, returned, settle ->
                settle()
                assertTrue("An already formed session must not trigger a competing scan", returned.isEmpty())
                endAttempt()
                settle()
                assertEquals(listOf(false to true), returned)
            }
        }
    }

    @Test fun aUserExitDuringSslGivesNoUsbScanBack() {
        scenario(ConnectionState.StartingTransport) { scan, _, returned, settle ->
            scan()
            endAttempt(userExit = true)
            CommManager::class.java.getDeclaredMethod("settleSessionClaim", Boolean::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(manager, false)
            settle()
            assertTrue(returned.isEmpty())
        }
    }

    @Test fun theXOnAUsbAttemptStillGivesTheStoodDownWirelessBack() {
        for (stage in listOf(ConnectionState.Disconnected(), ConnectionState.Connecting)) {
            scenario(stage, claimOwner = Owner.USB, standsDown = true) { _, claim, returned, settle ->
                checkNotNull(manager).disconnect(com.andrerinas.openheadunit.connection.ConnectionOperation(checkNotNull(manager).appConnectionTicket(), null))
                ConnectionArbiter.release(claim, sessionFormed = false)
                settle()
                assertEquals("stage=$stage", listOf(true to false), returned)
            }
        }
    }

    private fun scenario(
        state: ConnectionState,
        finishBeforeScan: ConnectionState? = null,
        force: Boolean = true,
        claimOwner: Owner = Owner.WIRELESS_STACK,
        standsDown: Boolean = false,
        body: (() -> Unit, ConnectionArbiter.Claim, MutableList<Pair<Boolean, Boolean>>, () -> Unit) -> Unit,
    ) {
        val savedLogger = AppLog.LOGGER
        val savedClock = ConnectionArbiter.clock
        val savedActions = ConnectionArbiter.actions
        val returned = mutableListOf<Pair<Boolean, Boolean>>()
        val timers = ArrayDeque<() -> Unit>()
        AppLog.LOGGER = object : AppLog.Logger {
            override fun println(priority: Int, tag: String, msg: String) = Unit
        }
        ConnectionArbiter.reset()
        ConnectionArbiter.clock = { 1000L }
        ConnectionArbiter.actions = object : ConnectionArbiter.Actions {
            override fun preempt(loser: ConnectionArbiter.Claim) = Unit
            override fun standDownWireless(by: ConnectionArbiter.Claim) = standsDown
            override fun giveBack(wireless: Boolean, usb: UsbRecheck?) { returned += wireless to (usb != null) }
            override fun schedule(delayMs: Long, block: () -> Unit) { timers.addLast(block) }
        }
        try {
            mockStatic(SystemClock::class.java).use {
                val app = mock(App::class.java)
                val component = mock(AppComponent::class.java)
                val service = mock(AapService::class.java)
                val manager = mock(CommManager::class.java, CALLS_REAL_METHODS)
                val states = MutableStateFlow(state)
                fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
                    .apply { isAccessible = true }.set(manager, value)
                field("transportLifecycleLock", Any())
                field("appExitPolicy", AppExitPolicy())
                field("_connectionState", states)
                field("connectionState", states)
                field("usbRecoveryOwner", Any())
                val teardown = Queued()
                field("_scope", CoroutineScope(SupervisorJob() + teardown))
                field("settings", mock(Settings::class.java))
                this@UsbHandshakeRecheckTest.manager = manager
                App::class.java.getDeclaredField("component\$delegate")
                    .apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(service.applicationContext).thenReturn(app)
                `when`(component.commManager).thenReturn(manager)
                `when`(component.settings).thenReturn(mock(Settings::class.java))
                val launcher = UsbLauncherManager(service)
                val claim = checkNotNull(ConnectionArbiter.claim(if (claimOwner == Owner.USB) Tier.USB else Tier.WIRELESS_HANDSHAKE, claimOwner, "handshake"))
                // Any non-terminal state is owned by the live attempt that claim published.
                if (state !is ConnectionState.Disconnected && state !is ConnectionState.Error) {
                    field("connectionAttempt", CommManager.Attempt(CommManager.ConnectionRequest(manager.appConnectionTicket(), claim, null, null, null)))
                }
                val release = CommManager::class.java.getDeclaredMethod("releaseClaim", ConnectionArbiter.Claim::class.java)
                    .apply { isAccessible = true }
                if (finishBeforeScan != null) {
                    launcher.checkAlreadyConnected(force = true, recheck = manager.usbRecheckNow())
                    states.value = finishBeforeScan
                    release.invoke(manager, claim)
                } else if (state is ConnectionState.Connected || state is ConnectionState.StartingTransport) {
                    // connect() can reach finally after the observer has advanced to SSL.
                    release.invoke(manager, claim)
                    assertTrue("The pending handshake must still hold its claim", ConnectionArbiter.holds(claim))
                }
                // Exercise the production scan entry point, including its isConnected guard.
                body({ launcher.checkAlreadyConnected(force = force, recheck = manager.usbRecheckNow()) }, claim, returned, {
                    while (teardown.tasks.isNotEmpty() || timers.isNotEmpty()) {
                        teardown.tasks.removeFirstOrNull()?.run() ?: timers.removeFirst().invoke()
                    }
                })
            }
        } finally {
            ConnectionArbiter.reset()
            ConnectionArbiter.clock = savedClock
            ConnectionArbiter.actions = savedActions
            AppLog.LOGGER = savedLogger
        }
    }
}
