package com.andrerinas.openheadunit.decoder.audio

import android.os.Process
import com.andrerinas.openheadunit.utils.AppLog

/** Scheduling is best effort: denied priority must not terminate capture, decode or playback. */
internal fun requestAudioThreadPriority(priority: Int) {
    try {
        Process.setThreadPriority(priority)
    } catch (e: RuntimeException) {
        AppLog.w("Audio thread ${Thread.currentThread().name}: priority $priority unavailable: ${e.message}")
    }
}
