package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDisplayPolicyTest {

    @Test
    fun `every size gives its own DPI on a 720p panel`() {
        val dpis = DisplaySizePreset.values().map {
            OnboardingDisplayPolicy.recommendedDpi(it, 1280, 720, portrait = false)
        }
        assertEquals(dpis.size, dpis.toSet().size)
    }

    @Test
    fun `a larger screen gets a lower DPI`() {
        val small = OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.SMALL_7_8, 1280, 720, false)
        val standard = OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.STANDARD_9_10, 1280, 720, false)
        assertTrue(small > standard)
    }

    @Test
    fun `DPI stays inside the caps and the floor`() {
        assertEquals(240, OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.PHONE_4_6, 1920, 1080, false))
        assertEquals(190, OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.PHONE_4_6, 1920, 1080, true))
        assertEquals(110, OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.LARGE_11_PLUS, 800, 480, false))
    }

    @Test
    fun `an untouched picker follows the size`() {
        assertEquals(215, OnboardingDisplayPolicy.pickerDpiAfterSizeChange(recommended = 215, current = 160, pickerTouched = false))
    }

    @Test
    fun `a picker the user moved keeps its value`() {
        assertEquals(160, OnboardingDisplayPolicy.pickerDpiAfterSizeChange(recommended = 215, current = 160, pickerTouched = true))
    }

    @Test
    fun `a stored size wins over the estimate`() {
        assertEquals(
            DisplaySizePreset.STANDARD_9_10,
            OnboardingDisplayPolicy.initialPreset("STANDARD_9_10", DisplaySizePreset.PHONE_4_6)
        )
    }

    @Test
    fun `no stored size or an unknown one falls back to the estimate`() {
        assertEquals(DisplaySizePreset.SMALL_7_8, OnboardingDisplayPolicy.initialPreset(null, DisplaySizePreset.SMALL_7_8))
        assertEquals(DisplaySizePreset.SMALL_7_8, OnboardingDisplayPolicy.initialPreset("BOGUS", DisplaySizePreset.SMALL_7_8))
    }

    @Test
    fun `the estimate reads the panel's physical size`() {
        assertEquals(DisplaySizePreset.SMALL_7_8, OnboardingDisplayPolicy.estimatePreset(1024, 600, 160f, 160f, 160))
        assertEquals(DisplaySizePreset.STANDARD_9_10, OnboardingDisplayPolicy.estimatePreset(1280, 720, 160f, 160f, 160))
        assertNotEquals(DisplaySizePreset.STANDARD_9_10, OnboardingDisplayPolicy.estimatePreset(1280, 720, 320f, 320f, 320))
    }

    @Test
    fun `an unreadable xdpi falls back to the density`() {
        assertEquals(DisplaySizePreset.SMALL_7_8, OnboardingDisplayPolicy.estimatePreset(1024, 600, 0f, 0f, 160))
    }
}
