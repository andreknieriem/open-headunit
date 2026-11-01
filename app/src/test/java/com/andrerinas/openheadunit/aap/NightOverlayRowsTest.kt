package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.NightOverlayRows.Row
import org.junit.Assert.assertEquals
import org.junit.Test

class NightOverlayRowsTest {
    private val full = NightOverlayState.Content(
        navigation = NightOverlayState.Navigation("Turn left", 300, 20, road = "Main St"),
        title = "Song", artist = "Band", isPlaying = true
    )

    @Test fun defaultsSelectEveryRowThatHasText() {
        assertEquals(setOf(Row.CLOCK, Row.NAVIGATION, Row.TRACK), NightOverlayRows.select(full, NightOverlayOptions()))
    }

    @Test fun eachSwitchDropsItsRow() {
        assertEquals(setOf(Row.NAVIGATION, Row.TRACK), NightOverlayRows.select(full, NightOverlayOptions(showClock = false)))
        assertEquals(setOf(Row.CLOCK, Row.NAVIGATION), NightOverlayRows.select(full, NightOverlayOptions(showTrack = false)))
        assertEquals(setOf(Row.CLOCK, Row.TRACK), NightOverlayRows.select(full, NightOverlayOptions(showNavigation = false)))
    }

    @Test fun aRowWithNoTextIsDroppedEvenWhenOn() {
        val empty = NightOverlayState.Content()
        assertEquals(setOf(Row.CLOCK), NightOverlayRows.select(empty, NightOverlayOptions()))
        val paused = full.copy(isPlaying = false)
        assertEquals(setOf(Row.CLOCK, Row.NAVIGATION), NightOverlayRows.select(paused, NightOverlayOptions()))
        val blankNav = full.copy(navigation = NightOverlayState.Navigation("", null, null, road = " "))
        assertEquals(setOf(Row.CLOCK, Row.TRACK), NightOverlayRows.select(blankNav, NightOverlayOptions()))
    }
}
