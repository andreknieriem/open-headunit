package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

class KeyBindingSnapshotTest {
    private val up = KeyEvent.KEYCODE_VOLUME_UP
    private var stored: Map<Int, InputAction> = mapOf(up to InputAction.Local(LocalAction.DIM_SCREEN))
    private var reads = 0
    private val snapshot = KeyBindingSnapshot { reads++; stored }

    @Test fun firstReadLoadsWithoutAReload() {
        assertEquals(stored, snapshot.current)
    }

    @Test fun keystrokesReuseOneRead() {
        repeat(5) { snapshot.current }
        assertEquals(1, reads)
    }

    @Test fun aBindingRemovedMidSessionIsSeenAfterAResume() {
        snapshot.current
        stored = emptyMap()
        assertEquals(1, snapshot.current.size)
        snapshot.reload()
        assertEquals(emptyMap<Int, InputAction>(), snapshot.current)
    }
}
