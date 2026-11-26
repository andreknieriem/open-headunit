package com.andrerinas.openheadunit.main

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeymapAssignPolicyTest {
    @Test fun backIsNeverOfferedDoNothing() {
        assertFalse(KeymapAssignPolicy.offersIgnore(KeyEvent.KEYCODE_BACK))
    }

    @Test fun otherKeysAreOfferedDoNothing() {
        for (code in listOf(KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_VOLUME_UP, 1001)) {
            assertTrue(KeymapAssignPolicy.offersIgnore(code))
        }
    }
}
