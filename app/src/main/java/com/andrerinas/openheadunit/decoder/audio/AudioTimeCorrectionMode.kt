package com.andrerinas.openheadunit.decoder.audio

/** Stable saved values. The lightweight OHU matcher remains the default on every device. */
enum class AudioTimeCorrectionMode(val preferenceValue: String, val nativeEngine: Int) {
    OHU("ohu", 0), WSOLA("wsola", 1), PHASE_VOCODER("phase-vocoder", 2),
    PHASE_VOCODER_SHORT("phase-vocoder-short", 3), PHASE_GRADIENT("phase-gradient", 4);

    /** Native lookahead belongs to continuous music, not short SPEECH/SYSTEM prompts.
     * The phone cannot observe this receiver's added engine tail, so delaying a prompt
     * can move it past the phone's own playback/interaction boundary. Select by protocol
     * sink role: prompts may share STREAM_MUSIC with media in the static-focus mixer.
     * Keep the capability probe lazy so OHU, prompts and AAC never load the library.
     */
    internal fun forSink(isMediaSink: Boolean, isPcm: Boolean,
                         nativeAvailable: () -> Boolean): AudioTimeCorrectionMode =
        if (this == OHU || !isMediaSink || !isPcm || !nativeAvailable()) OHU else this

    companion object {
        /** Missing or unrecognized saved values use the lightweight OHU matcher. */
        fun fromPreference(value: String?): AudioTimeCorrectionMode =
            entries.firstOrNull { it.preferenceValue == value } ?: OHU
    }
}
