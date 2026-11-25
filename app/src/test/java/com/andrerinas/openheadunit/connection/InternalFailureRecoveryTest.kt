package com.andrerinas.openheadunit.connection

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.andrerinas.openheadunit.aap.AapTransport
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.projection.LibusbProjectionConnection
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.projection.StandardUsbProjectionConnection
import com.andrerinas.openheadunit.utils.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.io.IOException
import java.net.Socket
import kotlin.coroutines.Continuation
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn

class InternalFailureRecoveryTest {
    private class Fixture : AutoCloseable {
        val manager = mock(CommManager::class.java, CALLS_REAL_METHODS).withAppExitDispatcherForTest()
        val context = mock(Context::class.java)
        val settings = mock(Settings::class.java)
        val states = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected())
        private val queued = ArrayDeque<Runnable>()
        private val scope = CoroutineScope(SupervisorJob() + object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { queued.addLast(block) }
        })
        val owner = Any()
        init {
            field("transportLifecycleLock", Any())
            field("appExitPolicy", AppExitPolicy())
            field("_connectionState", states)
            field("_scope", scope)
            field("usbRecoveryOwner", owner)
            field("context", context)
            field("settings", settings)
        }
        fun field(name: String, value: Any) = CommManager::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.set(manager, value)
        val queuedCleanups get() = queued.size
        fun assertRecoverable() {
            val ended = states.value as ConnectionState.Disconnected
            assertFalse("Internal failures must not latch user exit", ended.isUserExit)
            assertTrue("USB recovery must remain owned", manager.ownsUsbRecheck(owner))
            assertEquals("Exactly one cleanup is queued", 1, queued.size)
            verify(context, never()).sendBroadcast(any(android.content.Intent::class.java))
        }
        override fun close() { scope.cancel() }
    }

    @Test fun thrownSocketOpenFailuresHaveTheSamePolicyAsFalseReturns() = runBlocking {
        for (accepted in listOf(false, true)) for (throws in listOf(false, true)) {
            Fixture().use { f ->
                // Even this option must not close the service for an attempt that never opened.
                `when`(f.settings.killOnDisconnect).thenReturn(true)
                mockConstruction(SocketProjectionConnection::class.java) { connection, _ ->
                    runBlocking { `when`(connection.connect()).thenAnswer {
                        if (throws) throw IOException("open failed") else false
                    } }
                }.use {
                    if (accepted) invoke(f.manager, "connectSocket",
                        arrayOf(Socket::class.java, Pair::class.java, ConnectionState.Disconnected::class.java, Long::class.javaPrimitiveType!!),
                        mock(Socket::class.java), null, null, 0L)
                    else invoke(f.manager, "connectIp",
                        arrayOf(String::class.java, Int::class.javaPrimitiveType!!, ConnectionState.Disconnected::class.java, Long::class.javaPrimitiveType!!),
                        "127.0.0.1", 5277, null, 0L)
                    f.assertRecoverable()
                }
            }
        }
    }

    @Test fun thrownUsbOpenFailuresPreserveRecoveryForBothDrivers() = runBlocking {
        for (libusb in listOf(false, true)) {
            Fixture().use { f ->
                val device = mock(UsbDevice::class.java)
                val usb = mock(UsbManager::class.java)
                `when`(f.context.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
                `when`(usb.hasPermission(device)).thenReturn(true)
                `when`(f.settings.useLibusb).thenReturn(libusb)
                `when`(f.settings.killOnDisconnect).thenReturn(true)
                val driver = if (libusb) LibusbProjectionConnection::class.java else StandardUsbProjectionConnection::class.java
                mockConstruction(driver) { connection, _ ->
                    runBlocking { `when`(connection.connect()).thenAnswer { throw IOException("USB open failed") } }
                }.use {
                    invoke(f.manager, "connectUsb", arrayOf(UsbDevice::class.java, Long::class.javaPrimitiveType!!, ConnectionState.Disconnected::class.java,
                        Any::class.java, ConnectionArbiter.Claim::class.java, ConnectionAdmission::class.java,
                        ConnectionArbiter.Claim::class.java),
                        device, 0L, null, null, null, ConnectionAdmission.UNRESTRICTED, null)
                    f.assertRecoverable()
                }
            }
        }
    }

    @Test fun readingStartupFailureDoesNotBecomeUserExit() = runBlocking {
        Fixture().use { f ->
            val transport = mock(AapTransport::class.java)
            f.field("_transport", transport)
            f.states.value = ConnectionState.HandshakeComplete
            // A failure while acquiring startup audio resources reaches the same protected catch
            // as startReading(). It must not mark this transport as a deliberate user exit.
            `when`(transport.aapAudio).thenAnswer { throw IllegalStateException("audio startup failed") }
            f.manager.startReading()
            f.assertRecoverable()
            verify(transport, never()).wasUserExit = true
        }
    }

    @Test fun internalFailureRetainsRecoveryButExplicitExitRevokesIt() = runBlocking {
        Fixture().use { f ->
            // An actual internal failure: the IP open fails and disconnects without a user exit.
            connectIp(f, opens = false)
            f.assertRecoverable()
            f.manager.disconnect(honorKillOnDisconnect = false)
            assertFalse("An explicit stop still cancels a queued recheck", f.manager.ownsUsbRecheck(f.owner))
        }
    }

    @Test fun timeoutAfterRetirementDoesNotRevokeThePendingUsbCheck() = runBlocking {
        Fixture().use { f ->
            f.field("disconnectRequested", true)
            val mark = f.manager.launchMark(f.manager.appConnectionTicket())
            val outcome = f.manager.reportLaunchFailure("launch timeout after retirement", mark)
            assertSame(CommManager.LaunchOutcome.Failed, outcome)
            assertTrue(f.states.value is ConnectionState.Error)
            assertTrue(f.manager.ownsUsbRecheck(f.owner))
            f.manager.disconnect(honorKillOnDisconnect = false)
            assertFalse(f.manager.ownsUsbRecheck(f.owner))
        }
    }

    /** The actual IP publication path, with the connection's open and construction under test control. */
    private suspend fun connectIp(f: Fixture, opens: Boolean, loopback: Boolean = false, duringAttempt: () -> Unit = {}) {
        mockConstruction(SocketProjectionConnection::class.java) { connection, _ ->
            // Construction runs after the attempt is published and before its connection is registered.
            duringAttempt()
            runBlocking { `when`(connection.connect()).thenReturn(opens) }
            `when`(connection.isLoopbackPeer).thenReturn(loopback)
        }.use {
            invoke(f.manager, "connectIp",
                arrayOf(String::class.java, Int::class.javaPrimitiveType!!, ConnectionState.Disconnected::class.java, Long::class.javaPrimitiveType!!),
                "127.0.0.1", 5277, null, f.manager.appConnectionTicket())
        }
    }

    /**
     * The actual refused-USB path: a request whose permission check failed reports a diagnostic.
     * [permissionAnswer] runs inside the permission check, after the request passed its own
     * Connecting guard; the request may run on another thread.
     */
    private fun refusedUsb(f: Fixture, permissionAnswer: () -> Unit = {}): () -> Unit {
        val device = mock(UsbDevice::class.java)
        val usb = mock(UsbManager::class.java)
        `when`(f.context.getSystemService(Context.USB_SERVICE)).thenReturn(usb)
        `when`(usb.hasPermission(device)).thenAnswer { permissionAnswer(); false }
        return {
            runBlocking {
                invoke(f.manager, "connectUsb", arrayOf(UsbDevice::class.java, Long::class.javaPrimitiveType!!, ConnectionState.Disconnected::class.java,
                    Any::class.java, ConnectionArbiter.Claim::class.java, ConnectionAdmission::class.java,
                    ConnectionArbiter.Claim::class.java),
                    device, f.manager.appConnectionTicket(), null, null, null, ConnectionAdmission.UNRESTRICTED, null)
            }
        }
    }

    @Test fun launchFailureStandsOnlyWhileNothingArrivedOrIsInFlight() = runBlocking {
        // Nothing arrived and nothing owns the state: the failure stands, reported without a teardown.
        Fixture().use { f ->
            val outcome = f.manager.reportLaunchFailure("launch timeout", f.manager.launchMark(f.manager.appConnectionTicket()))
            assertSame(CommManager.LaunchOutcome.Failed, outcome)
            assertEquals(ConnectionState.Error("launch timeout"), f.states.value)
            assertTrue("A report is not a disconnect", f.manager.ownsUsbRecheck(f.owner))
            assertEquals("A report queues no cleanup", 0, f.queuedCleanups)
        }
        // A Save report only logs: Save's token must survive for its bounded fallback.
        Fixture().use { f ->
            val saved = ConnectionState.Disconnected(reason = CommManager.DisconnectReason.SETTINGS_RESTART)
            f.states.value = saved
            assertSame(CommManager.LaunchOutcome.Failed, f.manager.reportLaunchFailure("Save timeout", f.manager.launchMark(f.manager.appConnectionTicket()), saved))
            assertSame(saved, f.states.value)
        }
        // Stop revokes a launch accepted before it, even while that launch waited: nothing is
        // reported, whether its attempt then failed or arrived.
        for (arrives in listOf(false, true)) Fixture().use { f ->
            val mark = f.manager.launchMark(f.manager.appConnectionTicket())
            val exitPolicy = CommManager::class.java.getDeclaredField("appExitPolicy").apply { isAccessible = true }
                .get(f.manager) as AppExitPolicy
            synchronized(CommManager::class.java.getDeclaredField("transportLifecycleLock").apply { isAccessible = true }.get(f.manager)) {
                exitPolicy.request(killProcess = false, userStop = true, requiresKillOnDisconnect = false)
            }
            if (arrives) connectIp(f, opens = true)
            val before = f.states.value
            assertSame(CommManager.LaunchOutcome.Revoked, f.manager.reportLaunchFailure("launch timeout", mark))
            assertSame("A revoked launch published its failure", before, f.states.value)
        }
        // A live connection from before the launch owns the outcome; nothing replaces its state.
        Fixture().use { f ->
            connectIp(f, opens = true)
            assertSame(ConnectionState.Connected, f.states.value)
            val outcome = f.manager.reportLaunchFailure("launch timeout", f.manager.launchMark(f.manager.appConnectionTicket()))
            assertSame(CommManager.LaunchOutcome.Answered, outcome)
            assertSame(ConnectionState.Connected, f.states.value)
        }
    }

    @Test fun anArrivalAnswersALaunchEvenAfterAnErrorHidItsSession() = runBlocking {
        Fixture().use { f ->
            val refuse = refusedUsb(f)
            val mark = f.manager.launchMark(f.manager.appConnectionTicket())
            connectIp(f, opens = true, loopback = true)
            f.manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            val ended = f.states.value as ConnectionState.Disconnected
            assertTrue(ended.wasLoopbackSession && ended.hadPhysicalConnection)
            // Nothing owns the state now, so the refused USB request's diagnostic may replace it.
            refuse()
            assertEquals(ConnectionState.Error("USB permission not granted for device"), f.states.value)
            // The launch was answered all the same: no never-connected failure over that Error.
            assertSame(CommManager.LaunchOutcome.Answered, f.manager.reportLaunchFailure("launch timeout", mark))
            assertEquals(ConnectionState.Error("USB permission not granted for device"), f.states.value)
            assertFalse(f.manager.isLiveLoopbackSession)
        }
    }

    @Test fun anAttemptOwnsTheStateBeforeItsConnectionIsRegistered() = runBlocking {
        for (opens in listOf(true, false)) Fixture().use { f ->
            // A USB request passes its Connecting guard while nothing is in flight, then its
            // permission check returns false only after attempt B was published.
            val usbChecking = java.util.concurrent.CountDownLatch(1)
            val bPublished = java.util.concurrent.CountDownLatch(1)
            val refuse = refusedUsb(f) {
                usbChecking.countDown()
                assertTrue(bPublished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            }
            val failures = java.util.concurrent.CopyOnWriteArrayList<String>()
            f.field("onSessionFailure", { reason: String -> failures.add(reason); Unit })
            val usbRequest = Thread { refuse() }.apply { start() }
            assertTrue(usbChecking.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val mark = f.manager.launchMark(f.manager.appConnectionTicket())
            // Construction runs inside connectIp's own exception handling: record, check afterwards.
            var published: ConnectionState? = null
            var refusalDone = false
            var afterRefusal: ConnectionState? = null
            var failuresAfterRefusal = -1
            var forming: CommManager.LaunchOutcome? = null
            var settledAtDecision: Boolean? = null
            var afterDecision: ConnectionState? = null
            connectIp(f, opens) {
                // B is published; its connection object is not registered yet.
                published = f.states.value
                bPublished.countDown()
                usbRequest.join(5_000)
                refusalDone = !usbRequest.isAlive
                afterRefusal = f.states.value
                failuresAfterRefusal = failures.size
                // Nor can a launch failure stand: the attempt may be the launch's own late arrival.
                forming = f.manager.reportLaunchFailure("launch timeout", mark)
                settledAtDecision = (forming as? CommManager.LaunchOutcome.Forming)?.attempt?.settled?.isCompleted
                afterDecision = f.states.value
            }
            assertSame(ConnectionState.Connecting, published)
            assertTrue(refusalDone)
            assertSame("A refused request's diagnostic replaced an attempt in flight", ConnectionState.Connecting, afterRefusal)
            assertEquals(0, failuresAfterRefusal)
            assertEquals(false, settledAtDecision)
            assertSame(ConnectionState.Connecting, afterDecision)
            // The attempt settled as it reached its connection or ended: the waiter decides again.
            val waiting = forming as CommManager.LaunchOutcome.Forming
            assertTrue(waiting.attempt.settled.isCompleted)
            f.manager.awaitSettled(waiting)
            val outcome = f.manager.reportLaunchFailure("launch timeout", mark)
            if (opens) {
                assertSame(CommManager.LaunchOutcome.Answered, outcome)
                assertSame(ConnectionState.Connected, f.states.value)
            } else {
                // The failure stands, but B's own terminal, published since the launch, stays
                // for its observer instead of being hidden behind the launch's Error.
                assertSame(CommManager.LaunchOutcome.Failed, outcome)
                val ended = f.states.value as ConnectionState.Disconnected
                assertFalse(ended.hadPhysicalConnection)
            }
        }
    }

    @Test fun liveLoopbackSessionIsReadFromItsAttemptNotFromState() = runBlocking {
        Fixture().use { f ->
            val refuse = refusedUsb(f)
            connectIp(f, opens = true, loopback = true)
            assertTrue(f.manager.isLiveLoopbackSession)
            // A refused request's diagnostic cannot relabel the live session as failed.
            refuse()
            assertSame(ConnectionState.Connected, f.states.value)
            assertTrue(f.manager.isLiveLoopbackSession)
            f.manager.disconnect(sendByeBye = false, isUserExit = false, honorKillOnDisconnect = false)
            assertFalse("A retiring session is not live", f.manager.isLiveLoopbackSession)
        }
        // An ordinary socket session is live but not Self.
        Fixture().use { f ->
            connectIp(f, opens = true, loopback = false)
            assertFalse(f.manager.isLiveLoopbackSession)
        }
    }

    private suspend fun invoke(manager: CommManager, name: String, types: Array<Class<*>>, vararg args: Any?) {
        val method = CommManager::class.java.getDeclaredMethod(name, *types, Continuation::class.java)
            .apply { isAccessible = true }
        suspendCoroutineUninterceptedOrReturn<Unit> { continuation -> method.invoke(manager, *args, continuation) }
    }
}
