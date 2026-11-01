package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.utils.Settings
import org.junit.Assert.assertEquals
import org.junit.Test

class NightOverlayOptionsTest {
    @Test fun textSizeScalesTheText() {
        assertEquals(0.8f, NightOverlayOptions(textSize = Settings.NightOverlayTextSize.SMALL).textScale, 0f)
        assertEquals(1.0f, NightOverlayOptions(textSize = Settings.NightOverlayTextSize.NORMAL).textScale, 0f)
        assertEquals(1.3f, NightOverlayOptions(textSize = Settings.NightOverlayTextSize.LARGE).textScale, 0f)
        assertEquals(3, Settings.NightOverlayTextSize.values().size)
    }

    @Test fun backgroundDefaultsToBlack() {
        assertEquals(0xFF000000.toInt(), NightOverlayOptions().backgroundColor)
        assertEquals(0xFF202020.toInt(),
            NightOverlayOptions(background = Settings.NightOverlayBackground.DARK_GRAY).backgroundColor)
    }
}
