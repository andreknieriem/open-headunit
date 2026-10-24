#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

// One worker owns each instance. PCM is 48 kHz interleaved stereo; counts are FRAMES.
// Input/output lengths are intentionally independent. finish() drains the engine's real
// output, never an estimated input/tempo length. Only documented startup delay is skipped.
class AudioTimeStretcher {
public:
    enum class Engine { SoundTouch = 1, R3Standard = 2, R3Short = 3 };
    explicit AudioTimeStretcher(Engine engine);
    ~AudioTimeStretcher();
    void process(const int16_t* pcm, size_t frames, double tempo);
    void finish();
    size_t receive(int16_t* pcm, size_t capacityFrames);
    bool finished() const;
private:
    struct Impl;
    std::unique_ptr<Impl> impl;
};
