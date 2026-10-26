#pragma once

#include <cstddef>
#include <cstdint>

// One worker owns each instance. PCM is 48 kHz interleaved stereo; counts are FRAMES.
// process() accepts 1..480 frames, tempo 0.5..2.0. Call receive() between blocks;
// bounded input exhaustion rejects the entire block without changing stream state.
// process()/finish() report success; after receive(), also check error(). A non-null
// error() is a static message, never an allocation. Admission/output-argument errors
// are cleared by the next process/receive/finish call, allowing drain and retry.
// Construction or internal DSP failures are permanent: no further output is
// published and finished() remains false. receive() returns zero on failure;
// its buffer must not be consumed when error() is non-null, even if a prefix
// was computed before a fatal fault. Queries do not clear errors.
// finish() is idempotent. Drain receive() until finished(). Final duration uses a
// bounded binary64 enclosure of source-frame 1/tempo: a certified below-half
// result rounds down; uncertainty overlapping a half-frame boundary rounds up.
// This is not unconditional exact rational rounding. More than 2^48 source frames
// or an error radius >= 0.25 frames is rejected before accepting the new block.
// IEEE binary64 round-to-nearest arithmetic without fast-math is required.
class AudioTimeStretcher {
public:
    enum class Engine { Wsola = 1, PhaseVocoder = 2, PhaseVocoderShort = 3, PhaseGradient = 4 };
    explicit AudioTimeStretcher(Engine engine);
    ~AudioTimeStretcher();
    AudioTimeStretcher(const AudioTimeStretcher&) = delete;
    AudioTimeStretcher& operator=(const AudioTimeStretcher&) = delete;
    bool process(const int16_t* pcm, size_t frames, double tempo);
    bool finish();
    size_t receive(int16_t* pcm, size_t capacityFrames);
    bool finished() const;
    const char* error() const;
private:
    struct Impl;
    Impl* impl;
    const char* operationError = nullptr;
    bool beginOperation();
};
