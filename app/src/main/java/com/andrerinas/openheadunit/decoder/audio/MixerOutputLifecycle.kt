package com.andrerinas.openheadunit.decoder.audio

/** Output-thread state: warm only after a playback request, and drain before parking. */
internal class MixerOutputLifecycle(private val keepOutputActive: Boolean) {
    enum class Action { WAIT, START, WRITE, PAUSE }
    private var started = false
    private var idleSinceMs = -1L

    fun update(nowMs: Long, idle: Boolean, warming: Boolean, receivedAudio: Boolean,
               drainMs: Long): Action {
        if (!started) {
            idleSinceMs = -1L
            if (idle && !warming) return Action.WAIT
            started = true
            return Action.START
        }
        if (idle && !warming && (!keepOutputActive || !receivedAudio)) {
            if (idleSinceMs < 0) idleSinceMs = nowMs
            if (nowMs - idleSinceMs >= drainMs) {
                started = false
                idleSinceMs = -1L
                return Action.PAUSE
            }
        } else idleSinceMs = -1L
        return Action.WRITE
    }
}
