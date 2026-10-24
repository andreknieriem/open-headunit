package com.andrerinas.openheadunit.connection.wifi.modes.nativeaa.zbt

import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito.mockStatic

class ZbtDiscoveryTest {
    private val claims = mutableListOf<ZbtDaemonReachability.CarrierClaim>()
    private fun claim() = ZbtDaemonReachability.claimCarrier().also { claims.add(it) }

    @Before
    fun reset() {
        ZbtDaemonReachability.forget()
        claims.forEach { it.close() }
        claims.clear()
    }

    @After
    fun cleanup() = reset()

    @Test
    fun `routing snapshots pair each procedure with its own answer`() {
        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            // A busy listening port can satisfy the established path, never strict identity.
            ZbtDaemonReachability.record(true, nowMs = 1_000L)
            assertFalse(ZbtDaemonReachability.resolve(
                nowMs = { 1_000L }, requireIdentity = true,
                identityDial = { ZbtIdentityProbe.Outcome.UNCONFIRMED }
            ))
            val legacy = ZbtDiscovery.measurement(ZbtDiscoveryPolicy.Decision(
                ZbtDiscoveryPolicy.Mode.REACHABILITY, "rw.zlink.bt.type=extra"))
            val candidate = ZbtDiscovery.measurement(ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF"))
            assertEquals(true, legacy.snapshot().reachable)
            assertEquals(ZbtDiscoveryPolicy.Mode.REACHABILITY, legacy.snapshot().decision.mode)
            assertEquals(false, candidate.snapshot().reachable)
            assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, candidate.snapshot().decision.mode)
            assertFalse(legacy.fallsBackToAndroid)
            assertTrue(candidate.fallsBackToAndroid)
        }
    }

    @Test
    fun `an async measurement keeps its procedure when later arming adopts a marker`() {
        mockStatic(SystemClock::class.java).use { clock ->
            clock.`when`<Long> { SystemClock.elapsedRealtime() }.thenReturn(1_000L)
            var marker: String? = null
            val latch = ZbtDiscoveryPolicy.Latch {
                ZbtDiscoveryPolicy.detect({ marker }, {
                    it in listOf("/system/bin/fscsdkbw123", "/dev/goc_serial")
                })
            }
            val inFlight = ZbtDiscovery.measurement(latch.decision())
            marker = "rw.zj.bt.type=extra"
            latch.reconsiderForArming(carrierOwnsSlot = false)
            val next = ZbtDiscovery.measurement(latch.decision())
            ZbtDaemonReachability.record(true, nowMs = 1_000L)
            assertNull(inFlight.snapshot().reachable)
            assertFalse(inFlight.settled)
            assertEquals("HCT Bluetooth bridge with fscsdkbw123", inFlight.snapshot().decision.evidence)
            assertEquals(true, next.snapshot().reachable)
            assertTrue(next.settled)
            assertEquals(marker, next.snapshot().decision.evidence)
        }
    }

    @Test
    fun `candidate measurements wait for a live or retiring carrier without changing mode`() {
        val measurement = ZbtDiscovery.measurement(ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.IDENTITY, "QF"))
        assertFalse(measurement.waitsForCarrier)
        val owner = claim().also { it.setLive(true) }
        assertTrue(measurement.waitsForCarrier)
        owner.setLive(false)
        assertTrue(measurement.waitsForCarrier)
        owner.close()
        assertFalse(measurement.waitsForCarrier)
        assertEquals(ZbtDiscoveryPolicy.Mode.IDENTITY, measurement.decision.mode)
    }

    @Test
    fun `ordinary hardware ignores cached module answers and does not start a probe`() {
        ZbtDaemonReachability.record(true, nowMs = 1_000L)
        val measurement = ZbtDiscovery.measurement(ZbtDiscoveryPolicy.Decision(ZbtDiscoveryPolicy.Mode.NONE, null))
        assertNull(measurement.snapshot().reachable)
        assertFalse(measurement.resolve(keepTrying = { false }))
        assertTrue(measurement.fallsBackToAndroid)
    }
}
