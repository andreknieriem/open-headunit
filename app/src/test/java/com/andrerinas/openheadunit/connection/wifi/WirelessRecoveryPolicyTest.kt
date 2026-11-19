package com.andrerinas.openheadunit.connection.wifi

import com.andrerinas.openheadunit.aap.AutomaticReconnect
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.UnresponsivePeerPolicy
import com.andrerinas.openheadunit.connection.projection.SocketProjectionConnection
import com.andrerinas.openheadunit.connection.wifi.WirelessRecoveryPolicy.Stage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.mockito.Mockito.*
import java.net.Socket

class WirelessRecoveryPolicyTest {

    @Test
    fun `escalates through FAST_RETRY LAUNCHER_REARM STACK_RECOVERY and bounded BACKOFF`() {
        val tracker = WirelessRecoveryPolicy.Tracker()
        val generation = WirelessCleanupGeneration()

        val expectedStages = listOf(
            1 to (Stage.FAST_RETRY to 2_000L),
            2 to (Stage.FAST_RETRY to 4_000L),
            3 to (Stage.LAUNCHER_REARM to 8_000L),
            4 to (Stage.LAUNCHER_REARM to 16_000L),
            5 to (Stage.STACK_RECOVERY to 30_000L),
            6 to (Stage.BACKOFF to 60_000L),
            7 to (Stage.BACKOFF to 60_000L),
        )

        for ((expectedAttempt, stageAndDelay) in expectedStages) {
            val state = ConnectionState.Disconnected(
                isClean = false,
                failureCause = WirelessRecoveryPolicy.FAILURE_TCP_CONNECT,
            )
            val ticket = generation.captureFor(state)
            val decision = tracker.onDisconnected(
                endedState = state,
                ticket = ticket,
                failureCause = state.failureCause,
            )
            assertNotNull(decision)
            assertEquals(expectedAttempt, decision!!.attempt)
            assertEquals(stageAndDelay.first, decision.stage)
            assertEquals(stageAndDelay.second, decision.delayMs)
            assertEquals(WirelessRecoveryPolicy.FAILURE_TCP_CONNECT, decision.failureCause)
        }
    }

    @Test
    fun `resets attempt counter and stage on HandshakeComplete TransportStarted or reachedSsl`() {
        val tracker = WirelessRecoveryPolicy.Tracker()
        val generation = WirelessCleanupGeneration()

        repeat(4) {
            val s = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
            tracker.onDisconnected(s, generation.captureFor(s), failureCause = s.failureCause)
        }
        assertEquals(4, tracker.consecutiveFailures)
        assertEquals(Stage.LAUNCHER_REARM, tracker.currentStage)

        tracker.onHandshakeComplete()
        assertEquals(0, tracker.consecutiveFailures)
        assertEquals(Stage.FAST_RETRY, tracker.currentStage)

        repeat(3) {
            val s = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_TCP_CONNECT)
            tracker.onDisconnected(s, generation.captureFor(s), failureCause = s.failureCause)
        }
        assertEquals(3, tracker.consecutiveFailures)

        tracker.onTransportStarted()
        assertEquals(0, tracker.consecutiveFailures)
        assertEquals(Stage.FAST_RETRY, tracker.currentStage)

        // Conflated StateFlow case: intermediate live states were skipped, but Disconnected reports reachedSsl = true.
        repeat(4) {
            val s = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
            tracker.onDisconnected(s, generation.captureFor(s), failureCause = s.failureCause)
        }
        assertEquals(4, tracker.consecutiveFailures)

        val droppedAfterSsl = ConnectionState.Disconnected(
            isClean = false,
            reachedSsl = true,
            failureCause = WirelessRecoveryPolicy.FAILURE_LINK_LOST,
        )
        val decision = tracker.onDisconnected(
            endedState = droppedAfterSsl,
            ticket = generation.captureFor(droppedAfterSsl),
            reachedSsl = droppedAfterSsl.reachedSsl,
            failureCause = droppedAfterSsl.failureCause,
        )
        assertNotNull(decision)
        assertEquals(1, decision!!.attempt)
        assertEquals(Stage.FAST_RETRY, decision.stage)
        assertEquals(2_000L, decision.delayMs)
    }

    @Test
    fun `immediately suppresses and resets on userExit holdsRecovery settingsRestart cancelledByUser or pausedForSettings`() {
        val generation = WirelessCleanupGeneration()

        val suppressionCases = listOf(
            { tracker: WirelessRecoveryPolicy.Tracker, s: ConnectionState.Disconnected ->
                tracker.onDisconnected(s, generation.captureFor(s), isUserExit = true)
            },
            { tracker: WirelessRecoveryPolicy.Tracker, s: ConnectionState.Disconnected ->
                tracker.onDisconnected(s, generation.captureFor(s), holdsRecovery = true)
            },
            { tracker: WirelessRecoveryPolicy.Tracker, s: ConnectionState.Disconnected ->
                tracker.onDisconnected(s, generation.captureFor(s), isSettingsRestart = true)
            },
            { tracker: WirelessRecoveryPolicy.Tracker, s: ConnectionState.Disconnected ->
                tracker.onDisconnected(s, generation.captureFor(s), cancelledByUser = true)
            },
            { tracker: WirelessRecoveryPolicy.Tracker, s: ConnectionState.Disconnected ->
                tracker.onDisconnected(s, generation.captureFor(s), wirelessPausedForSettings = true)
            },
        )

        for (suppress in suppressionCases) {
            val tracker = WirelessRecoveryPolicy.Tracker()
            val pre = ConnectionState.Disconnected(isClean = false)
            assertNotNull(tracker.onDisconnected(pre, generation.captureFor(pre)))
            assertEquals(1, tracker.consecutiveFailures)

            val suppressedState = ConnectionState.Disconnected(isClean = false)
            assertNull(suppress(tracker, suppressedState))
            assertEquals(0, tracker.consecutiveFailures)
            assertNull(tracker.activeTicket)
        }
    }

    @Test
    fun `suppresses stale ticket when WirelessCleanupGeneration advances or state changes`() = runBlocking {
        val tracker = WirelessRecoveryPolicy.Tracker()
        val generation = WirelessCleanupGeneration()
        val initial = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_LINK_LOST)
        var currentState: ConnectionState = initial
        val release = CompletableDeferred<Unit>()
        var executed = 0

        val retry = AutomaticReconnect(this, { currentState }, { false }, { release.await() })
        val ticket = generation.captureFor(initial)!!
        val decision = tracker.onDisconnected(
            endedState = initial,
            ticket = ticket,
            failureCause = initial.failureCause,
        )!!

        retry.schedule(
            endedSession = initial,
            delayMs = decision.delayMs,
            ownsCheck = {
                tracker.canExecute(
                    decision = decision,
                    currentState = currentState,
                    isTicketCurrent = generation::isCurrent,
                )
            },
        ) {
            executed++
        }
        yield()

        // Explicit re-arm advances the cleanup generation while currentState is still the same Disconnected instance.
        generation.advance(initial)
        assertFalse(tracker.canExecute(decision, currentState, generation::isCurrent))

        release.complete(Unit)
        yield()
        assertEquals(0, executed)
    }

    @Test
    fun `peer silent failure honours UnresponsivePeerPolicy backoff floor`() {
        val tracker = WirelessRecoveryPolicy.Tracker()
        val generation = WirelessCleanupGeneration()
        val state = ConnectionState.Disconnected(
            isClean = false,
            failureCause = WirelessRecoveryPolicy.FAILURE_PEER_SILENT,
        )
        val decision = tracker.onDisconnected(
            endedState = state,
            ticket = generation.captureFor(state),
            failureCause = state.failureCause,
            silentPeerFailures = UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF,
        )
        assertNotNull(decision)
        assertEquals(UnresponsivePeerPolicy.BACKOFF_RESCAN_MS, decision!!.delayMs)
    }

    @Test
    fun `Disconnected exposes both failureCause and connectError symmetrically`() {
        val byFailureCause = ConnectionState.Disconnected(failureCause = "peer_silent")
        assertEquals("peer_silent", byFailureCause.failureCause)
        assertEquals("peer_silent", byFailureCause.connectError)

        val byConnectError = ConnectionState.Disconnected(connectError = "connect_failed")
        assertEquals("connect_failed", byConnectError.failureCause)
        assertEquals("connect_failed", byConnectError.connectError)
    }

    @Test
    fun `SocketProjectionConnection prefers TRANSPORT_WIFI network matching target subnet and closes unconnected socket on disconnect`() {
        val stationNet = "wlan0_net"
        val p2pNet = "p2p0_net"
        val linkMap = mapOf(
            stationNet to listOf("192.168.1.42" to 24),
            p2pNet to listOf("192.168.49.1" to 24),
        )

        val selectedP2p = SocketProjectionConnection.selectWifiNetwork(
            targetIp = "192.168.49.105",
            wifiNetworks = listOf(stationNet, p2pNet),
            linkAddressesOf = { linkMap[it] ?: emptyList() },
        )
        assertEquals(p2pNet, selectedP2p)

        val selectedFallback = SocketProjectionConnection.selectWifiNetwork(
            targetIp = "10.0.0.5",
            wifiNetworks = listOf(stationNet, p2pNet),
            linkAddressesOf = { linkMap[it] ?: emptyList() },
        )
        assertEquals(stationNet, selectedFallback)

        val unconnectedSocket = mock(Socket::class.java)
        `when`(unconnectedSocket.isConnected).thenReturn(false)
        `when`(unconnectedSocket.inetAddress).thenReturn(java.net.InetAddress.getLoopbackAddress())
        val conn = SocketProjectionConnection(unconnectedSocket, mock(android.content.Context::class.java))
        conn.disconnect()
        verify(unconnectedSocket).close()
    }
}
