package com.andrerinas.openheadunit.aap

import com.andrerinas.openheadunit.aap.protocol.Channel
import com.andrerinas.openheadunit.aap.protocol.proto.Media

/**
 * Video focus on the second display's channel, kept apart from the main picture's.
 *
 * The phone releasing the second sink is not the user leaving Android Auto, and that sink only
 * streams once focus is granted on its own channel.
 */
object SecondaryVideoFocusPolicy {

    enum class Answer { RUN_EXIT_ACTION, AUX_RELEASED, GRANT_ON_CHANNEL, NONE }

    /** Gap between the release and the regain of an auxiliary keyframe cycle. */
    const val AUX_CYCLE_GAP_MS = 400L

    /** At most one auxiliary cycle this often, so a decoder in trouble cannot flap the sink. */
    const val AUX_CYCLE_MIN_INTERVAL_MS = 5_000L

    fun onFocusRequest(channel: Int, mode: Media.VideoFocusMode): Answer = when {
        channel == Channel.ID_VID2 -> when (mode) {
            Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED,
            Media.VideoFocusMode.VIDEO_FOCUS_PROJECTED_NO_INPUT_FOCUS -> Answer.GRANT_ON_CHANNEL
            else -> Answer.AUX_RELEASED
        }
        mode == Media.VideoFocusMode.VIDEO_FOCUS_NATIVE -> Answer.RUN_EXIT_ACTION
        else -> Answer.NONE
    }

    /** The main channel's grant has its own path in [AapTransport.gainVideoFocus]. */
    fun grantsFocusAfterSetup(channel: Int): Boolean = channel == Channel.ID_VID2

    /** [lastCycleMs] of 0 means none yet this session. */
    fun mayCycleAux(nowMs: Long, lastCycleMs: Long): Boolean =
        lastCycleMs == 0L || nowMs - lastCycleMs >= AUX_CYCLE_MIN_INTERVAL_MS
}
