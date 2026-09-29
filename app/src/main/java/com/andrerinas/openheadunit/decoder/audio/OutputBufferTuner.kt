package com.andrerinas.openheadunit.decoder.audio

/** Reapply a buffer request when the platform restores/resizes a live output. Remember the
 * granted size rather than the requested one: old HALs may clamp or ignore buffer requests. */
internal class OutputBufferTuner {
    private var requestedFrames = -1
    private var appliedFrames = -1

    fun update(output: PcmOutput, targetFrames: Int, force: Boolean = false): Boolean {
        if (!force && targetFrames == requestedFrames && output.bufferFrames == appliedFrames) return false
        output.setBufferFrames(targetFrames)
        requestedFrames = targetFrames
        appliedFrames = output.bufferFrames
        return true
    }
}
