package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.SecondaryVideoFocusPolicy.Answer
import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media.VideoFocusMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondaryVideoFocusPolicyTest {

    @Test
    fun `the main channel answers exactly as before`() {
        assertEquals(Answer.RUN_EXIT_ACTION, SecondaryVideoFocusPolicy.onFocusRequest(Channel.ID_VID, VideoFocusMode.VIDEO_FOCUS_NATIVE))
        assertEquals(Answer.NONE, SecondaryVideoFocusPolicy.onFocusRequest(Channel.ID_VID, VideoFocusMode.VIDEO_FOCUS_PROJECTED))
        assertEquals(Answer.NONE, SecondaryVideoFocusPolicy.onFocusRequest(Channel.ID_VID, VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT))
    }

    @Test
    fun `the phone releasing the second display never runs the exit action`() {
        for (mode in listOf(VideoFocusMode.VIDEO_FOCUS_NATIVE, VideoFocusMode.VIDEO_FOCUS_NATIVE_TRANSIENT)) {
            assertEquals(Answer.AUX_RELEASED, SecondaryVideoFocusPolicy.onFocusRequest(Channel.ID_VID2, mode))
        }
    }

    @Test
    fun `a projected request on the second display is granted on that channel`() {
        for (mode in listOf(VideoFocusMode.VIDEO_FOCUS_PROJECTED, VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS)) {
            assertEquals(Answer.GRANT_ON_CHANNEL, SecondaryVideoFocusPolicy.onFocusRequest(Channel.ID_VID2, mode))
        }
    }

    @Test
    fun `only the second display is granted focus by its setup`() {
        assertTrue(SecondaryVideoFocusPolicy.grantsFocusAfterSetup(Channel.ID_VID2))
        assertFalse(SecondaryVideoFocusPolicy.grantsFocusAfterSetup(Channel.ID_VID))
        assertFalse(SecondaryVideoFocusPolicy.grantsFocusAfterSetup(Channel.ID_AUD))
    }

    @Test
    fun `auxiliary cycles are spaced`() {
        assertTrue(SecondaryVideoFocusPolicy.mayCycleAux(nowMs = 1_000, lastCycleMs = 0))
        assertFalse(SecondaryVideoFocusPolicy.mayCycleAux(nowMs = 10_000, lastCycleMs = 6_000))
        assertTrue(SecondaryVideoFocusPolicy.mayCycleAux(nowMs = 11_000, lastCycleMs = 6_000))
    }
}
