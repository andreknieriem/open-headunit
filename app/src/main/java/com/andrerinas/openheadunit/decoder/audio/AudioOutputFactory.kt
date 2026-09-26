package com.andrerinas.openheadunit.decoder.audio

import android.os.Build
import com.andrerinas.openheadunit.utils.AppLog

internal object AudioOutputFactory {
    fun create(stream: Int, attachHwDsp: Boolean, preferAAudio: Boolean): PcmOutput {
        val fallback = { AudioTrackPcmOutput(stream, attachHwDsp) }
        if (AudioOutputPolicy.useAAudio(Build.VERSION.SDK_INT, stream, attachHwDsp, preferAAudio)) {
            try {
                return FallbackPcmOutput(AAudioPcmOutput(), fallback, { AppLog.w(it) })
            } catch (e: LinkageError) {
                AppLog.w("AAudio unavailable; using AudioTrack: ${e.message}")
            } catch (e: Exception) {
                AppLog.w("AAudio could not open; using AudioTrack: ${e.message}")
            }
        }
        return fallback()
    }
}
