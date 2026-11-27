package com.andrerinas.openheadunit.connection.carplaynative

import com.andrerinas.openheadunit.connection.carplaynative.CarPlayNativeSession.Phase

/** When the service raises the native screen and when the screen closes. Both follow the session phase, never the Android Auto state. */
object CarPlayNativeScreenPolicy {

    /**
     * Raise once per session, while it is live and holds the bus. [ends] is `CarPlayNativeManager.ends` and [raisedAt]
     * its value at the last raise: a session never sees the counter move, so a new value means a new session.
     */
    fun raise(phase: Phase, live: Boolean, ends: Int, raisedAt: Int?): Boolean =
        live && raisedAt != ends && CarPlayNativeSession.State(phase = phase).begun

    /** A close ended since the screen read [startEnds], so the session it showed is gone. */
    fun finishOnEnds(startEnds: Int, ends: Int): Boolean = ends != startEnds

    /**
     * Ended and Idle can arrive back to back when a queued attach replays, and a collector may see only Idle,
     * so an Idle phase with no live session closes the screen as well.
     */
    fun finish(phase: Phase, live: Boolean): Boolean = phase is Phase.Ended || (phase == Phase.Idle && !live)
}
