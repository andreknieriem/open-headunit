package com.andrerinas.openheadunit.utils

import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyImmersivePolicyTest {

    @Test
    fun `applies to immersive modes before KitKat only`() {
        for (sdk in 16..18) {
            assertTrue(LegacyImmersivePolicy.applies(sdk, Settings.FullscreenMode.IMMERSIVE))
            assertTrue(LegacyImmersivePolicy.applies(sdk, Settings.FullscreenMode.IMMERSIVE_WITH_NOTCH))
        }
        assertFalse(LegacyImmersivePolicy.applies(19, Settings.FullscreenMode.IMMERSIVE))
        assertFalse(LegacyImmersivePolicy.applies(36, Settings.FullscreenMode.IMMERSIVE))
    }

    @Test
    fun `does not apply when the bars are meant to show`() {
        assertFalse(LegacyImmersivePolicy.applies(16, Settings.FullscreenMode.NONE))
        assertFalse(LegacyImmersivePolicy.applies(16, Settings.FullscreenMode.STATUS_ONLY))
    }

    @Test
    fun `bars count as shown once the hide flag is cleared`() {
        val hidden = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN
        assertFalse(LegacyImmersivePolicy.barsShown(hidden))
        assertTrue(LegacyImmersivePolicy.barsShown(View.SYSTEM_UI_FLAG_LAYOUT_STABLE))
        assertTrue(LegacyImmersivePolicy.barsShown(0))
    }
}
