package com.andrerinas.openheadunit.utils

import com.andrerinas.openheadunit.utils.SystemOptimizer.DisplaySizePreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingDisplayPolicyTest {

    @Test
    fun `every size from 7_5 inch up gives its own DPI on a 720p panel`() {
        val dpis = DisplaySizePreset.values().filter { it != DisplaySizePreset.PHONE_4_6 }.map {
            OnboardingDisplayPolicy.recommendedDpi(it, 1280, 720, portrait = false)
        }
        assertEquals(listOf(240, 180, 144), dpis)
    }

    @Test
    fun `the 6 inch size at 800x480 gives 150`() {
        assertEquals(150, OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.PHONE_4_6, 800, 480, false))
    }

    @Test
    fun `the landscape table is pinned`() {
        val sizes = listOf(800 to 480, 1280 to 720, 1920 to 1080)
        val expected = listOf(
            listOf(150, 150, 113, 110),
            listOf(240, 240, 180, 144),
            listOf(240, 240, 240, 216)
        )
        sizes.forEachIndexed { i, (w, h) ->
            val got = DisplaySizePreset.values().map { OnboardingDisplayPolicy.recommendedDpi(it, w, h, false) }
            assertEquals("$w", expected[i], got)
        }
    }

    @Test
    fun `every landscape answer keeps the dp canvas at or above 748x450`() {
        val sizes = listOf(800 to 480, 1280 to 720, 1920 to 1080, 2560 to 1440, 3840 to 2160)
        for ((w, h) in sizes) for (p in DisplaySizePreset.values()) {
            val dpi = OnboardingDisplayPolicy.recommendedDpi(p, w, h, false)
            assertTrue("$p $w -> $dpi", w * 160 / dpi >= 748 && h * 160 / dpi >= 450)
        }
    }

    @Test
    fun `the 6 inch and 7_5 inch sizes share the 853 dp canvas`() {
        for (w in intArrayOf(800, 1280, 1920)) {
            assertEquals(
                OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.PHONE_4_6, w, w * 3 / 5, false),
                OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.SMALL_7_8, w, w * 3 / 5, false)
            )
        }
    }

    @Test
    fun `portrait keeps the physical formula`() {
        assertEquals(190, OnboardingDisplayPolicy.recommendedDpi(DisplaySizePreset.SMALL_7_8, 720, 1280, true))
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

    @Test
    fun `an untouched picker keeps Automatic on a fresh install`() {
        assertEquals(0, OnboardingDisplayPolicy.dpiToSave(150, false, stored = 0))
    }

    @Test
    fun `an untouched picker keeps an upgrader's typed value`() {
        assertEquals(300, OnboardingDisplayPolicy.dpiToSave(240, false, stored = 300))
        assertEquals(171, OnboardingDisplayPolicy.dpiToSave(150, false, stored = 171))
    }

    @Test
    fun `a moved picker saves its value`() {
        assertEquals(103, OnboardingDisplayPolicy.dpiToSave(103, true, stored = 0))
        assertEquals(150, OnboardingDisplayPolicy.dpiToSave(150, true, stored = 300))
    }

    @Test
    fun `Automatic at connect equals the wizard's recommendation`() {
        for (preset in DisplaySizePreset.values()) {
            for ((w, h) in listOf(800 to 480, 1280 to 720, 1920 to 1080)) {
                assertEquals(
                    "$preset ${w}x$h",
                    OnboardingDisplayPolicy.recommendedDpi(preset, w, h, false),
                    AnnouncedDensityPolicy.decide(0, preset, w, h, autoLayout = null).announced
                )
            }
        }
    }
}
