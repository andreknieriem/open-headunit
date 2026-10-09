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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*

class UsbHandshakeRecheckTest {
    @Test fun usbScanWaitsForEveryPreHandshakeStageAndReturnsAfterFailure() {
        for (stage in listOf(ConnectionState.Connecting, ConnectionState.Connected, ConnectionState.StartingTransport)) {
            scenario(stage) { scan, claim, returned, settle ->
                scan()
                assertTrue(returned.isEmpty())
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
                ConnectionArbiter.release(claim, sessionFormed = true)
                settle()
                assertTrue(returned.isEmpty())
                ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = userExit)
                assertEquals(if (userExit) emptyList() else listOf(false to true), returned)
            }
        }
    }

    @Test fun disabledAutomaticUsbScanDoesNotBecomeAForcedRetry() {
        scenario(ConnectionState.Connected, force = false) { scan, claim, returned, settle ->
            scan()
            ConnectionArbiter.release(claim, sessionFormed = false)
            settle()
            assertTrue(returned.isEmpty())
        }
    }

    @Test fun usbAttemptDoesNotOweASecondScanToItself() {
        scenario(ConnectionState.StartingTransport, claimOwner = Owner.USB) { scan, claim, returned, settle ->
            scan()
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
                ConnectionArbiter.sessionEnded(wirelessAlreadyRearmed = false, userExit = false)
                assertEquals(listOf(false to true), returned)
            }
        }
    }

    private fun scenario(
        state: ConnectionState,
        finishBeforeScan: ConnectionState? = null,
        force: Boolean = true,
        claimOwner: Owner = Owner.WIRELESS_STACK,
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
            override fun standDownWireless(by: ConnectionArbiter.Claim) = false
            override fun giveBack(wireless: Boolean, usb: Boolean) { returned += wireless to usb }
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
                field("_connectionState", states)
                field("connectionState", states)
                App::class.java.getDeclaredField("component\$delegate")
                    .apply { isAccessible = true }.set(app, lazyOf(component))
                `when`(service.applicationContext).thenReturn(app)
                `when`(component.commManager).thenReturn(manager)
                `when`(component.settings).thenReturn(mock(Settings::class.java))
                val launcher = UsbLauncherManager(service)
                val claim = checkNotNull(ConnectionArbiter.claim(if (claimOwner == Owner.USB) Tier.USB else Tier.WIRELESS_HANDSHAKE, claimOwner, "handshake"))
                val release = CommManager::class.java.getDeclaredMethod("releaseClaim", ConnectionArbiter.Claim::class.java)
                    .apply { isAccessible = true }
                if (finishBeforeScan != null) {
                    launcher.checkAlreadyConnected(force = true)
                    states.value = finishBeforeScan
                    release.invoke(manager, claim)
                } else if (state is ConnectionState.Connected || state is ConnectionState.StartingTransport) {
                    // connect() can reach finally after the observer has advanced to SSL.
                    release.invoke(manager, claim)
                    assertTrue("The pending handshake must still hold its claim", ConnectionArbiter.holds(claim))
                }
                // Exercise the production scan entry point, including its isConnected guard.
                body({ launcher.checkAlreadyConnected(force = force) }, claim, returned,
                    { while (timers.isNotEmpty()) timers.removeFirst().invoke() })
            }
        } finally {
            ConnectionArbiter.reset()
            ConnectionArbiter.clock = savedClock
            ConnectionArbiter.actions = savedActions
            AppLog.LOGGER = savedLogger
        }
    }
}
