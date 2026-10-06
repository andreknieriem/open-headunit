package com.andrerinas.openheadunit.decoder.video

/** Recovery belongs to a decoder run and session, not just to the Surface object's identity. */
internal class SurfaceRecoveryState {
    var cycleSpent = false
    var loggedKeyframelessPicture = false
    private var observedStopGeneration = 0L

    fun resetSession() {
        cycleSpent = false
        loggedKeyframelessPicture = false
    }

    fun onSurfaceChanged(targetChanged: Boolean, stopGeneration: Long): Boolean {
        // GLES can retain its Surface while onStop releases the codec's reference frames.
        val rearm = targetChanged || stopGeneration != observedStopGeneration
        observedStopGeneration = stopGeneration
        if (rearm) resetSession()
        return rearm
    }
}
