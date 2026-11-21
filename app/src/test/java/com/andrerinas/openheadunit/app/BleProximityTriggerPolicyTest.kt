package com.andrerinas.openheadunit.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleProximityTriggerPolicyTest {

    private val idleEnv = BleProximityTriggerPolicy.Environment(
        sessionBusy = false,
        settingsVisible = false,
        userExitActive = false,
        wirelessCancelledByUser = false,
    )

    private fun config(
        enabled: Boolean = true,
        target: String = "Pixel 8",
        cooldownMs: Long = 45_000L,
        minRssi: Int = BleProximityTriggerPolicy.DEFAULT_MIN_RSSI,
    ) = BleProximityTriggerPolicy.Config(
        enabled = enabled,
        target = target,
        cooldownMs = cooldownMs,
        minRssi = minRssi,
    )

    @Test
    fun `disabled by default never triggers or scans`() {
        val cfg = config(enabled = false)
        assertFalse(BleProximityTriggerPolicy.shouldScan(cfg.enabled, cfg.target, sessionBusy = false))
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("disabled"),
            BleProximityTriggerPolicy.evaluate(
                cfg,
                idleEnv,
                BleProximityTriggerPolicy.Sighting("Pixel 8", "AA:BB:CC:DD:EE:FF", -60, 10_000L),
                lastTriggerAtMs = null,
            )
        )
    }

    @Test
    fun `blank target never triggers or scans`() {
        assertFalse(BleProximityTriggerPolicy.shouldScan(enabled = true, target = "   ", sessionBusy = false))
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("no_target"),
            BleProximityTriggerPolicy.evaluate(
                config(target = "  "),
                idleEnv,
                BleProximityTriggerPolicy.Sighting("Pixel 8", null, -55, 10_000L),
                lastTriggerAtMs = null,
            )
        )
    }

    @Test
    fun `matches target by case-insensitive name or prefix and normalized MAC`() {
        assertTrue(BleProximityTriggerPolicy.matchesTarget("pixel 8", "Pixel 8", null))
        assertTrue(BleProximityTriggerPolicy.matchesTarget("Pixel 8", "Pixel 8 Pro", null))
        assertFalse(BleProximityTriggerPolicy.matchesTarget("Pixel 8", "Galaxy S23", null))

        assertTrue(BleProximityTriggerPolicy.matchesTarget("aa-bb-cc-dd-ee-ff", null, "AA:BB:CC:DD:EE:FF"))
        assertFalse(BleProximityTriggerPolicy.matchesTarget("AA:BB:CC:DD:EE:FF", null, "11:22:33:44:55:66"))
    }

    @Test
    fun `matching sighting above RSSI floor triggers`() {
        assertEquals(
            BleProximityTriggerPolicy.Decision.Trigger,
            BleProximityTriggerPolicy.evaluate(
                config(target = "Pixel 8"),
                idleEnv,
                BleProximityTriggerPolicy.Sighting("Pixel 8", "AA:BB:CC:DD:EE:FF", -65, 10_000L),
                lastTriggerAtMs = null,
            )
        )
    }

    @Test
    fun `weak RSSI below threshold is ignored`() {
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("rssi_too_weak"),
            BleProximityTriggerPolicy.evaluate(
                config(target = "Pixel 8", minRssi = -78),
                idleEnv,
                BleProximityTriggerPolicy.Sighting("Pixel 8", null, -90, 10_000L),
                lastTriggerAtMs = null,
            )
        )
    }

    @Test
    fun `cooldown prevents rapid re-trigger loops and allows trigger after window elapses`() {
        val cfg = config(cooldownMs = 45_000L)
        val sighting = BleProximityTriggerPolicy.Sighting("Pixel 8", null, -60, 50_000L)

        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("cooldown_active"),
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv, sighting, lastTriggerAtMs = 10_000L)
        )

        val afterCooldown = sighting.copy(nowMs = 55_000L)
        assertEquals(
            BleProximityTriggerPolicy.Decision.Trigger,
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv, afterCooldown, lastTriggerAtMs = 10_000L)
        )
    }

    @Test
    fun `active session, settings screen, user exit, and user cancel all suppress triggers`() {
        val cfg = config()
        val sighting = BleProximityTriggerPolicy.Sighting("Pixel 8", null, -58, 10_000L)

        assertFalse(BleProximityTriggerPolicy.shouldScan(cfg.enabled, cfg.target, sessionBusy = true))
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("session_busy"),
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv.copy(sessionBusy = true), sighting, null)
        )
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("settings_visible"),
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv.copy(settingsVisible = true), sighting, null)
        )
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("user_exit_cooldown"),
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv.copy(userExitActive = true), sighting, null)
        )
        assertEquals(
            BleProximityTriggerPolicy.Decision.Ignore("wireless_cancelled"),
            BleProximityTriggerPolicy.evaluate(cfg, idleEnv.copy(wirelessCancelledByUser = true), sighting, null)
        )
    }
}
