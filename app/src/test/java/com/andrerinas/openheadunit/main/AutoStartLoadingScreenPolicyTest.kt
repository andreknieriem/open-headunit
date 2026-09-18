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

    @Test
    fun `a self-opened attempt holds the loading screen while the stack reports`() {
        assertTrue(
            AutoStartLoadingScreenPolicy.holdsOverlay(
                ConnectionStage.WAKING_PHONE, automaticLaunch = true
            )
        )
    }

    @Test
    fun `a hand-opened attempt keeps its own bound`() {
        assertFalse(
            AutoStartLoadingScreenPolicy.holdsOverlay(
                ConnectionStage.WAKING_PHONE, automaticLaunch = false
            )
        )
    }

    @Test
    fun `a stack that has stopped reporting holds nothing`() {
        assertFalse(AutoStartLoadingScreenPolicy.holdsOverlay(null, automaticLaunch = true))
        assertFalse(AutoStartLoadingScreenPolicy.holdsOverlay(null, automaticLaunch = false))
    }

    @Test
    fun `every stage the stack can report holds a self-opened attempt`() {
        for (stage in ConnectionStage.values()) {
            assertTrue(stage.name, AutoStartLoadingScreenPolicy.holdsOverlay(stage, automaticLaunch = true))
        }
    }
}
