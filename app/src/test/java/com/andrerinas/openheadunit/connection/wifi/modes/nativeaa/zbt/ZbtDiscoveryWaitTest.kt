package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.mockito.Mockito.mockStatic

class ZbtDiscoveryWaitTest {
    private val claims = mutableListOf<ZbtDaemonReachability.CarrierClaim>()
    private fun claim() = ZbtDaemonReachability.claimCarrier().also { claims.add(it) }

    private lateinit var clock: org.mockito.MockedStatic<SystemClock>
    private var elapsedMs = 1_000L
    private val decision = ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF")

    @Before fun setup() {
        clock = mockStatic(SystemClock::class.java)
        clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenAnswer { elapsedMs }
        reset()
    }
    @After fun cleanup() { reset(); clock.close() }
    private fun reset() {
        ZbtDaemonReachability.forget()
        claims.forEach { it.close() }
        claims.clear()
        ZbtDiscovery.beginArming()
    }

    @Test fun stuckLiveOrReopeningCarrierTimesOutWithoutProbingOrPoisoningIdentity() = runBlocking {
        for (live in listOf(false, true)) {
            reset()
            val owner = claim().also { it.setLive(live) }
            val measurement = ZbtDiscovery.measurement(decision)
            val startedAt = elapsedMs
            val result = measurement.awaitResolution(
                keepTrying = { true }, pause = { elapsedMs += it },
                probe = { fail("must not dial through a carrier") },
            )
            assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
            assertEquals(10_000L, elapsedMs - startedAt)
            ZbtDiscovery.deferIdentityForArming(decision)
            assertEquals(false, measurement.snapshot().reachable)
            assertNull(measurement.cached())
            // Repeated routing/UI reads stay on fallback even if the old carrier finally exits.
            owner.close()
            assertEquals(false, measurement.snapshot().reachable)
            ZbtDiscovery.beginArming()
            assertNull(measurement.snapshot().reachable)
        }
    }

    @Test fun carrierReleaseBeforeDeadlineAllowsIdentityAndNeverUsesLegacyCache() = runBlocking {
        val owner = claim()
        ZbtDaemonReachability.record(true)
        val measurement = ZbtDiscovery.measurement(decision)
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true },
            pause = { elapsedMs += it; owner.close() },
            probe = {
                probes++
                ZbtDaemonReachability.resolve(requireIdentity = true,
                    identityDial = { ZbtIdentityProbe.Outcome.IDENTIFIED })
            },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(1, probes)
        assertEquals(true, measurement.snapshot().reachable)
    }

    @Test fun repeatedOwnershipRacesShareOneDeadline() = runBlocking {
        val measurement = ZbtDiscovery.measurement(decision)
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { elapsedMs += it },
            // The carrier can win between waitsForCarrier and resolve. No answer is cached.
            probe = { probes++ },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
        assertEquals(40, probes)
        assertNull(measurement.cached())
    }

    @Test fun cancelledArmingDoesNotProbeOrChooseFallback() = runBlocking {
        val owner = claim()
        var active = true
        val measurement = ZbtDiscovery.measurement(decision)
        val result = measurement.awaitResolution(
            keepTrying = { active }, pause = { active = false },
            probe = { fail("cancelled discovery probed") },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.CANCELLED, result)
        assertNull(measurement.snapshot().reachable)
    }

    @Test fun establishedMeasurementRetainsItsExistingRetryWindow() = runBlocking {
        val measurement = ZbtDiscovery.measurement(
            ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { fail("settled legacy check polled again") },
            probe = { elapsedMs += 30_000; ZbtDaemonReachability.record(false) },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(false, measurement.snapshot().reachable)
    }
    @Test fun legacyDiscoveryDoesNotRearmOnObserverOccupancy() = runBlocking {
        val measurement = ZbtDiscovery.measurement(
            ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
        var probes = 0
        var waits = 0
        val result = measurement.awaitResolution(
            keepTrying = { true },
            pause = { waits++; elapsedMs += it },
            probe = { if (++probes == 2) ZbtDaemonReachability.record(true) },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(2, probes)
        assertEquals(1, waits)
    }

    @Test fun legacyCarrierWithUnknownCacheWaitsOnceAndEndsBusy() = runBlocking {
        for (live in listOf(false, true)) {
            reset()
            val owner = claim().also { it.setLive(live) }
            val measurement = ZbtDiscovery.measurement(
                ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
            val started = elapsedMs
            var waits = 0
            val result = measurement.awaitResolution(
                keepTrying = { true }, pause = { waits++; elapsedMs += it },
                probe = { fail("must not measure beside the retiring carrier") },
            )
            assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
            assertEquals(10_000L, elapsedMs - started)
            assertEquals(40, waits)
            assertNull(measurement.cached())
            assertFalse(measurement.settled)
            owner.close()
        }
    }

    @Test fun legacyCarrierReleaseAllowsMeasurementWithinTheSameArming() = runBlocking {
        val owner = claim()
        val measurement = ZbtDiscovery.measurement(
            ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { elapsedMs += it; owner.close() },
            probe = { probes++; ZbtDaemonReachability.resolve(dial = { true }) },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.SETTLED, result)
        assertEquals(1, probes)
        assertEquals(true, measurement.cached())
    }

    @Test fun unresolvedLegacyObserverAlsoHasOneOuterDeadline() = runBlocking {
        val measurement = ZbtDiscovery.measurement(
            ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.REACHABILITY, "external"))
        val started = elapsedMs
        var probes = 0
        val result = measurement.awaitResolution(
            keepTrying = { true }, pause = { elapsedMs += it },
            probe = { probes++ },
        )
        assertEquals(ZbtDiscovery.Measurement.Completion.CARRIER_BUSY, result)
        assertEquals(10_000L, elapsedMs - started)
        assertEquals(40, probes)
        assertNull(measurement.cached())
    }

}
