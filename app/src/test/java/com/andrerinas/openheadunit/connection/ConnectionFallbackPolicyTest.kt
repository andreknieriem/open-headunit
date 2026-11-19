package com.andrerinas.openheadunit.connection

import com.andrerinas.openheadunit.aap.AutomaticReconnect
import com.andrerinas.openheadunit.connection.CommManager.ConnectionState
import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.CandidateMode
import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.Preconditions
import com.andrerinas.openheadunit.connection.ConnectionFallbackPolicy.SkipReason
import com.andrerinas.openheadunit.connection.wifi.WirelessCleanupGeneration
import com.andrerinas.openheadunit.connection.wifi.WirelessRecoveryPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class ConnectionFallbackPolicyTest {

    @Test
    fun `evaluates priority order with lastSuccessfulMode promoted first and deduplicated`() {
        val defaultChain = ConnectionFallbackPolicy.evaluateChain(
            Preconditions(
                lastSuccessfulMode = null,
                manualIp = "192.168.43.1",
                usbConnected = true,
            )
        )
        assertEquals(
            listOf(
                CandidateMode.NATIVE_WIFI_DIRECT,
                CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
                CandidateMode.MANUAL_IP,
                CandidateMode.USB,
            ),
            defaultChain.eligibleModes,
        )
        assertFalse(defaultChain.lastSuccessfulPromoted)

        val promotedChain = ConnectionFallbackPolicy.evaluateChain(
            Preconditions(
                lastSuccessfulMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
                manualIp = "192.168.43.1",
                usbConnected = true,
            )
        )
        assertEquals(
            listOf(
                CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
                CandidateMode.NATIVE_WIFI_DIRECT,
                CandidateMode.MANUAL_IP,
                CandidateMode.USB,
            ),
            promotedChain.eligibleModes,
        )
        assertTrue(promotedChain.lastSuccessfulPromoted)
    }

    @Test
    fun `skips ineligible modes and records exact SkipReasons including disconnected USB and unconfigured Manual IP`() {
        // Last successful mode was USB, but USB is now unplugged and Manual IP is blank.
        val evaluation = ConnectionFallbackPolicy.evaluateChain(
            Preconditions(
                lastSuccessfulMode = CandidateMode.USB,
                manualIp = "",
                usbConnected = false,
            )
        )
        assertEquals(
            listOf(
                CandidateMode.NATIVE_WIFI_DIRECT,
                CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
            ),
            evaluation.eligibleModes,
        )
        assertFalse(evaluation.lastSuccessfulPromoted)
        assertEquals(SkipReason.USB_NOT_CONNECTED, evaluation.skippedModes[CandidateMode.USB])
        assertEquals(SkipReason.MANUAL_IP_UNCONFIGURED, evaluation.skippedModes[CandidateMode.MANUAL_IP])
    }

    @Test
    fun `escalates sequentially through eligible modes after per-mode attempt budget is spent`() {
        val persistedModes = mutableListOf<CandidateMode>()
        val coordinator = ConnectionModeCoordinator(
            initialLastSuccessfulMode = null,
            onPersistLastSuccessfulMode = { persistedModes.add(it) },
        )
        val generation = WirelessCleanupGeneration()
        val preconditions = Preconditions(
            fallbackEnabled = true,
            manualIp = "192.168.43.1",
            usbConnected = true,
            maxAttemptsPerMode = 2,
        )

        assertEquals(CandidateMode.NATIVE_WIFI_DIRECT, coordinator.selectInitialMode(preconditions))

        // Failure 1 in NATIVE_WIFI_DIRECT -> stays on NATIVE_WIFI_DIRECT (attempt 1 of 2)
        val s1 = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
        val step1 = coordinator.onDisconnected(
            endedState = s1,
            ticket = generation.captureFor(s1),
            preconditions = preconditions,
            failureCause = s1.failureCause,
        )!!
        assertEquals(CandidateMode.NATIVE_WIFI_DIRECT, step1.targetMode)
        assertFalse(step1.modeChanged)
        assertEquals(1, step1.attemptInMode)

        // Failure 2 in NATIVE_WIFI_DIRECT -> budget spent, transitions to SHARED_WIFI_HEADUNIT_SERVER
        val s2 = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
        val step2 = coordinator.onDisconnected(
            endedState = s2,
            ticket = generation.captureFor(s2),
            preconditions = preconditions,
            failureCause = s2.failureCause,
        )!!
        assertEquals(CandidateMode.NATIVE_WIFI_DIRECT, step2.fromMode)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, step2.targetMode)
        assertTrue(step2.modeChanged)
        assertEquals(1, step2.attemptInMode)

        // Failure 3 (2nd in SHARED_WIFI_HEADUNIT_SERVER) -> transitions to MANUAL_IP
        val s3 = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_TCP_CONNECT)
        val step3 = coordinator.onDisconnected(
            endedState = s3,
            ticket = generation.captureFor(s3),
            preconditions = preconditions,
            failureCause = s3.failureCause,
        )!!
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, step3.fromMode)
        assertEquals(CandidateMode.MANUAL_IP, step3.targetMode)
        assertTrue(step3.modeChanged)

        // Failure 4 (2nd in MANUAL_IP) -> transitions to USB (since usbConnected = true)
        val s4 = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_TCP_CONNECT)
        val step4 = coordinator.onDisconnected(
            endedState = s4,
            ticket = generation.captureFor(s4),
            preconditions = preconditions,
            failureCause = s4.failureCause,
        )!!
        assertEquals(CandidateMode.MANUAL_IP, step4.fromMode)
        assertEquals(CandidateMode.USB, step4.targetMode)
        assertTrue(step4.modeChanged)
    }

    @Test
    fun `escalates early from Shared Wi-Fi or Manual IP when peer is silent beyond threshold`() {
        val coordinator = ConnectionModeCoordinator(initialLastSuccessfulMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER)
        val generation = WirelessCleanupGeneration()
        val preconditions = Preconditions(
            fallbackEnabled = true,
            lastSuccessfulMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
            manualIp = "192.168.43.1",
            usbConnected = false,
            maxAttemptsPerMode = 3,
        )

        val silentDisconnect = ConnectionState.Disconnected(
            isClean = false,
            failureCause = WirelessRecoveryPolicy.FAILURE_PEER_SILENT,
        )
        val step = coordinator.onDisconnected(
            endedState = silentDisconnect,
            ticket = generation.captureFor(silentDisconnect),
            preconditions = preconditions,
            failedMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
            failureCause = silentDisconnect.failureCause,
            silentPeerFailures = UnresponsivePeerPolicy.SILENT_FAILURES_BEFORE_BACKOFF,
        )!!

        // Even though maxAttemptsPerMode is 3, silent peer threshold immediately escalates to NATIVE_WIFI_DIRECT.
        assertTrue(step.modeChanged)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, step.fromMode)
        assertEquals(CandidateMode.NATIVE_WIFI_DIRECT, step.targetMode)
    }

    @Test
    fun `promotes lastSuccessfulMode on HandshakeComplete TransportStarted or reachedSsl and resets cycle`() {
        var persisted: CandidateMode? = null
        val coordinator = ConnectionModeCoordinator(
            initialLastSuccessfulMode = CandidateMode.NATIVE_WIFI_DIRECT,
            onPersistLastSuccessfulMode = { persisted = it },
        )
        val generation = WirelessCleanupGeneration()
        val preconditions = Preconditions(
            fallbackEnabled = true,
            manualIp = "192.168.43.1",
            usbConnected = false,
            maxAttemptsPerMode = 2,
        )

        // Fail twice on NATIVE_WIFI_DIRECT so coordinator falls back to SHARED_WIFI_HEADUNIT_SERVER.
        repeat(2) {
            val s = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
            coordinator.onDisconnected(s, generation.captureFor(s), preconditions, failureCause = s.failureCause)
        }
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, coordinator.currentMode)
        assertEquals(2, coordinator.totalFailuresInCycle)

        // Session now reaches HandshakeComplete on SHARED_WIFI_HEADUNIT_SERVER.
        coordinator.onHandshakeComplete(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, coordinator.lastSuccessfulMode)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, persisted)
        assertEquals(0, coordinator.totalFailuresInCycle)
        assertEquals(0, coordinator.attemptsFor(CandidateMode.NATIVE_WIFI_DIRECT))

        // Mid-session drop with reachedSsl = true on MANUAL_IP promotes MANUAL_IP and retries it first.
        val droppedLive = ConnectionState.Disconnected(
            isClean = false,
            reachedSsl = true,
            failureCause = WirelessRecoveryPolicy.FAILURE_LINK_LOST,
        )
        val stepAfterLiveDrop = coordinator.onDisconnected(
            endedState = droppedLive,
            ticket = generation.captureFor(droppedLive),
            preconditions = preconditions,
            failedMode = CandidateMode.MANUAL_IP,
            reachedSsl = true,
            failureCause = droppedLive.failureCause,
        )!!
        assertEquals(CandidateMode.MANUAL_IP, coordinator.lastSuccessfulMode)
        assertEquals(CandidateMode.MANUAL_IP, persisted)
        assertEquals(CandidateMode.MANUAL_IP, stepAfterLiveDrop.targetMode)
        assertFalse(stepAfterLiveDrop.modeChanged)
    }

    @Test
    fun `fences stale timers and prevents concurrent execution when busy or generation advances`() = runBlocking {
        val coordinator = ConnectionModeCoordinator(initialLastSuccessfulMode = CandidateMode.NATIVE_WIFI_DIRECT)
        val generation = WirelessCleanupGeneration()
        val preconditions = Preconditions(fallbackEnabled = true, usbConnected = false)

        val s1 = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_HANDSHAKE)
        var currentState: ConnectionState = s1
        var isBusy = false
        val release = CompletableDeferred<Unit>()
        var executedCount = 0

        val retry = AutomaticReconnect(this, { currentState }, { false }, { release.await() })
        val ticket = generation.captureFor(s1)!!
        val step = coordinator.onDisconnected(
            endedState = s1,
            ticket = ticket,
            preconditions = preconditions,
            failureCause = s1.failureCause,
        )!!

        // While commManager.isBusy is true, canExecute and executeSerialized must refuse execution.
        isBusy = true
        assertFalse(coordinator.canExecute(step, currentState, generation::isCurrent, isBusy = isBusy))
        assertFalse(
            coordinator.executeSerialized(step, currentState, generation::isCurrent, isBusy = isBusy) {
                executedCount++
            }
        )

        // Re-entrancy guard: while an execution is in flight, a second concurrent call is rejected.
        isBusy = false
        var nestedAccepted = true
        val outerAccepted = coordinator.executeSerialized(step, currentState, generation::isCurrent, isBusy = false) {
            executedCount++
            nestedAccepted = coordinator.executeSerialized(step, currentState, generation::isCurrent, isBusy = false) {
                executedCount++
            }
        }
        assertTrue(outerAccepted)
        assertFalse(nestedAccepted)
        assertEquals(1, executedCount)

        // Schedule via AutomaticReconnect, then advance WirelessCleanupGeneration before timer fires.
        retry.schedule(
            endedSession = s1,
            delayMs = step.delayMs,
            ownsCheck = {
                coordinator.canExecute(step, currentState, generation::isCurrent, isBusy = isBusy)
            },
        ) {
            executedCount++
        }
        yield()
        generation.advance(s1)
        release.complete(Unit)
        yield()
        assertEquals(1, executedCount)
    }

    @Test
    fun `diagnosticsSnapshot reports current mode last successful mode skip reasons and per-mode failures`() {
        val coordinator = ConnectionModeCoordinator(initialLastSuccessfulMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER)
        val generation = WirelessCleanupGeneration()
        val preconditions = Preconditions(
            fallbackEnabled = true,
            manualIp = "",
            usbConnected = false,
            maxAttemptsPerMode = 2,
        )

        val s = ConnectionState.Disconnected(isClean = false, failureCause = WirelessRecoveryPolicy.FAILURE_TCP_CONNECT)
        coordinator.onDisconnected(
            endedState = s,
            ticket = generation.captureFor(s),
            preconditions = preconditions,
            failedMode = CandidateMode.SHARED_WIFI_HEADUNIT_SERVER,
            failureCause = s.failureCause,
        )

        val snapshot = coordinator.diagnosticsSnapshot(preconditions)
        assertTrue(snapshot.fallbackEnabled)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, snapshot.lastSuccessfulMode)
        assertEquals(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, snapshot.currentMode)
        assertEquals(1, snapshot.totalFailuresInCycle)
        assertEquals(
            listOf(CandidateMode.SHARED_WIFI_HEADUNIT_SERVER, CandidateMode.NATIVE_WIFI_DIRECT),
            snapshot.eligibleModes,
        )
        assertEquals(SkipReason.MANUAL_IP_UNCONFIGURED, snapshot.skippedModes[CandidateMode.MANUAL_IP])
        assertEquals(SkipReason.USB_NOT_CONNECTED, snapshot.skippedModes[CandidateMode.USB])

        val sharedStatus = snapshot.modeStatuses.first { it.mode == CandidateMode.SHARED_WIFI_HEADUNIT_SERVER }
        assertTrue(sharedStatus.eligible)
        assertEquals(1, sharedStatus.attemptsInCycle)
        assertEquals(WirelessRecoveryPolicy.FAILURE_TCP_CONNECT, sharedStatus.lastFailureCause)
    }
}
