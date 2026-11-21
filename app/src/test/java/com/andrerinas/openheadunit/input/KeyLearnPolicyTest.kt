package com.andrerinas.openheadunit.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLearnPolicyTest {
    private val down = KeyEvent.ACTION_DOWN
    private val up = KeyEvent.ACTION_UP
    private val next = KeyEvent.KEYCODE_MEDIA_NEXT

    @Test fun bothEdgesFeedTheScreen() {
        assertTrue(KeyLearnPolicy.feedsLearnScreen(down, 0))
        assertTrue(KeyLearnPolicy.feedsLearnScreen(up, 0))
    }

    @Test fun aRepeatDoesNotFeed() {
        assertFalse(KeyLearnPolicy.feedsLearnScreen(down, 1))
    }

    @Test fun learningHoldsEverySourceButAutoResume() {
        for (src in listOf("carkey", "key-broadcast", "mediasession", "remote-command", "unknown")) {
            assertTrue(src, KeyLearnPolicy.holdsFromWire(true, src))
        }
        assertFalse(KeyLearnPolicy.holdsFromWire(true, "auto-resume"))
        assertFalse(KeyLearnPolicy.holdsFromWire(false, "carkey"))
    }

    @Test fun sameCodeAndActionInsideTheWindowIsACopy() {
        val seen = mapOf((next to down) to 1000L)
        assertTrue(KeyLearnPolicy.isCopy(seen, next, down, 1000L + KeyLearnPolicy.COPY_WINDOW_MS - 1))
    }

    @Test fun interleavedEdgesStillCatchTheSecondDown() {
        val seen = mapOf((next to down) to 1000L, (next to up) to 1010L)
        assertTrue(KeyLearnPolicy.isCopy(seen, next, down, 1020L))
        assertFalse(KeyLearnPolicy.isCopy(mapOf((next to down) to 1000L), next, up, 1020L))
    }

    @Test fun outsideTheWindowIsNotACopy() {
        val seen = mapOf((next to down) to 1000L)
        assertFalse(KeyLearnPolicy.isCopy(seen, next, down, 1000L + KeyLearnPolicy.COPY_WINDOW_MS))
    }

    @Test fun aDifferentCodeIsNotACopy() {
        val seen = mapOf((next to down) to 1000L)
        assertFalse(KeyLearnPolicy.isCopy(seen, KeyEvent.KEYCODE_MEDIA_PREVIOUS, down, 1010L))
    }
}
