package com.andrerinas.openheadunit.main

import com.andrerinas.openheadunit.connection.ConnectionStage
import com.andrerinas.openheadunit.main.MainActivity.ConnectionUiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoStartLoadingScreenPolicyTest {

    // opensOnLaunch

    @Test
    fun `a receiver starting us opens on the loading screen`() {
        assertTrue(
            AutoStartLoadingScreenPolicy.opensOnLaunch(
                automaticSource = true, lastSession = false, selfMode = false, singleUsb = false
            )
        )
    }

    @Test
    fun `any auto-connect method opens on the loading screen with no launch source`() {
        for (flags in listOf(
            Triple(true, false, false), Triple(false, true, false), Triple(false, false, true)
        )) {
            assertTrue(
                "flags=$flags",
                AutoStartLoadingScreenPolicy.opensOnLaunch(
                    automaticSource = false,
                    lastSession = flags.first,
                    selfMode = flags.second,
                    singleUsb = flags.third
                )
            )
        }
    }

    @Test
    fun `a plain launch with nothing armed keeps the home screen`() {
        assertFalse(
            AutoStartLoadingScreenPolicy.opensOnLaunch(
                automaticSource = false, lastSession = false, selfMode = false, singleUsb = false
            )
        )
    }

    // modeFor

    @Test
    fun `an automatic launch takes the full screen only while the setting is on`() {
        assertEquals(
            ConnectionUiMode.OVERLAY,
            AutoStartLoadingScreenPolicy.modeFor(automatic = true, enabled = true)
        )
        assertEquals(
            ConnectionUiMode.PILL,
            AutoStartLoadingScreenPolicy.modeFor(automatic = true, enabled = false)
        )
    }

    @Test
    fun `a launch that is not automatic never takes the full screen from here`() {
        assertEquals(
            ConnectionUiMode.PILL,
            AutoStartLoadingScreenPolicy.modeFor(automatic = false, enabled = true)
        )
    }

    // showsHomePill

    @Test
    fun `the pill is up on the home screen whatever the setting says`() {
        assertTrue(AutoStartLoadingScreenPolicy.showsHomePill(overlayOwnsScreen = false, pillSettingOn = true))
        assertTrue(AutoStartLoadingScreenPolicy.showsHomePill(overlayOwnsScreen = false, pillSettingOn = false))
    }

    @Test
    fun `over the loading screen only the setting takes the pill down`() {
        assertTrue(AutoStartLoadingScreenPolicy.showsHomePill(overlayOwnsScreen = true, pillSettingOn = true))
        assertFalse(AutoStartLoadingScreenPolicy.showsHomePill(overlayOwnsScreen = true, pillSettingOn = false))
    }

    // showsProjectionPill

    @Test
    fun `the projection pill needs its overlay, a step and the setting`() {
        assertTrue(
            AutoStartLoadingScreenPolicy.showsProjectionPill(
                loadingOverlayVisible = true, stage = ConnectionStage.CONNECTING, pillSettingOn = true
            )
        )
        assertFalse(
            AutoStartLoadingScreenPolicy.showsProjectionPill(
                loadingOverlayVisible = false, stage = ConnectionStage.CONNECTING, pillSettingOn = true
            )
        )
        assertFalse(
            AutoStartLoadingScreenPolicy.showsProjectionPill(
                loadingOverlayVisible = true, stage = null, pillSettingOn = true
            )
        )
        assertFalse(
            AutoStartLoadingScreenPolicy.showsProjectionPill(
                loadingOverlayVisible = true, stage = ConnectionStage.CONNECTING, pillSettingOn = false
            )
        )
    }

    // holdsOverlay

    private fun holds(
        stage: ConnectionStage? = ConnectionStage.WAKING_PHONE,
        automaticLaunch: Boolean = true,
        attemptElapsedMs: Long = 0L,
    ) = AutoStartLoadingScreenPolicy.holdsOverlay(stage, automaticLaunch, attemptElapsedMs)

    @Test
    fun `a self-opened attempt holds the loading screen while the stack reports`() {
        assertTrue(holds())
    }

    @Test
    fun `the hold clears a 91 second wake with margin`() {
        // The escalated wake fires at 91.4s after arming, then holds the poke 20s and gives the
        // link 30s back. All of it has to fit inside the bound or the screen drops mid-bring-up.
        assertTrue(holds(attemptElapsedMs = 91_400L))
        assertTrue(holds(attemptElapsedMs = 141_400L))
    }

    @Test
    fun `a stack that reports a step forever still loses the screen`() {
        // A Native stack arms on every start and pokes unbounded, so WAKING_PHONE and
        // WAITING_FOR_PHONE alternate for as long as the app runs. Without the bound this held.
        assertTrue(holds(attemptElapsedMs = AutoStartLoadingScreenPolicy.MAX_HOLD_MS - 1))
        assertFalse(holds(attemptElapsedMs = AutoStartLoadingScreenPolicy.MAX_HOLD_MS))
        assertFalse(holds(attemptElapsedMs = 10 * AutoStartLoadingScreenPolicy.MAX_HOLD_MS))
        for (stage in ConnectionStage.values()) {
            assertFalse(stage.name, holds(stage = stage, attemptElapsedMs = AutoStartLoadingScreenPolicy.MAX_HOLD_MS))
        }
    }

    @Test
    fun `the bound is the attempt's own window plus one pill window`() {
        assertEquals(
            AutoConnectAttemptPolicy.OVERLAY_WATCHDOG_MS + AutoConnectAttemptPolicy.PILL_WATCHDOG_MS,
            AutoStartLoadingScreenPolicy.MAX_HOLD_MS
        )
    }

    @Test
    fun `a hand-opened attempt keeps its own bound`() {
        assertFalse(
            holds(automaticLaunch = false)
        )
    }

    @Test
    fun `a stack that has stopped reporting holds nothing`() {
        assertFalse(holds(stage = null, automaticLaunch = true))
        assertFalse(holds(stage = null, automaticLaunch = false))
    }

    @Test
    fun `every stage the stack can report holds a self-opened attempt`() {
        for (stage in ConnectionStage.values()) {
            assertTrue(stage.name, holds(stage = stage))
        }
    }
}
