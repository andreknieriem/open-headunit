package com.andrerinas.openheadunit.decoder.audio

/** Stable saved values. The lightweight OHU matcher remains the default on every device. */
enum class AudioTimeCorrectionMode(val preferenceValue: String, val nativeEngine: Int) {
    OHU("ohu", 0), SOUNDTOUCH("soundtouch", 1), R3_STANDARD("r3-standard", 2), R3_SHORT("r3-short", 3);

    /** Native lookahead belongs to continuous music, not short SPEECH/SYSTEM prompts.
     * The phone cannot observe this receiver's added engine tail, so delaying a prompt
     * can move it past the phone's own playback/interaction boundary. Select by protocol
     * sink role: prompts may share STREAM_MUSIC with media in the static-focus mixer.
     * Keep the capability probe lazy so prompts, AAC and old devices never load the library.
     */
    internal fun forSink(isMediaSink: Boolean, isPcm: Boolean, sdkInt: Int,
                         nativeAvailable: () -> Boolean): AudioTimeCorrectionMode =
        if (this == OHU || !isMediaSink || !isPcm || sdkInt < 21 || !nativeAvailable()) OHU else this

    companion object {
        fun fromPreference(value: String?): AudioTimeCorrectionMode =
            entries.firstOrNull { it.preferenceValue == value } ?: OHU
    }
}
