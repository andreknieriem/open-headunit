package com.andrerinas.openheadunit.decoder.audio

internal object AudioOutputPolicy {
    // AAudio defaults to USAGE_MEDIA. Leave every other stream (including vendor ids) with
    // AudioTrack, which can preserve the user's legacy stream routing and attached effects.
    // API 26/27 also need AAudio state-machine workarounds this backend does not implement.
    fun useAAudio(sdk: Int, stream: Int, attachHwDsp: Boolean, enabled: Boolean): Boolean =
        enabled && sdk >= 28 && stream == 3 && !attachHwDsp
}
