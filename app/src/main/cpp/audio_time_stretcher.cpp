#include "audio_time_stretcher.h"
#include <finer/R3Stretcher.h>
#include "third_party/soundtouch/include/SoundTouch.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>

using RubberBand::RubberBandStretcher;
struct AudioTimeStretcher::Impl {
    std::unique_ptr<RubberBand::R3Stretcher> rb;
    std::unique_ptr<soundtouch::SoundTouch> st;
    float left[480]{}, right[480]{}, interleaved[960]{};
    float* planes[2]{left, right};
    const float* input[2]{left, right};
    size_t skip = 0;
    bool final = false;
    double tempo = 1.0;

    explicit Impl(Engine engine) {
        if (engine == Engine::SoundTouch) {
            st = std::make_unique<soundtouch::SoundTouch>();
            st->setSampleRate(48000);
            st->setChannels(2);
            st->setPitch(1);
            st->setRate(1);
            st->setTempo(1);
            st->setSetting(SETTING_USE_QUICKSEEK, 0);
        } else {
            if (engine != Engine::R3Standard && engine != Engine::R3Short)
                throw std::invalid_argument("Unknown time stretcher");
            auto options = RubberBandStretcher::OptionProcessRealTime |
                RubberBandStretcher::OptionEngineFiner | RubberBandStretcher::OptionThreadingNever |
                RubberBandStretcher::OptionChannelsTogether;
            if (engine == Engine::R3Short) options |= RubberBandStretcher::OptionWindowShort;
            // The pinned 4.0.0 public facade only forwards these operations to R3.
            // Construct that same implementation without linking its unused R2
            // branch. Preserve the default logger and all padding/drain behavior.
            // Recheck this boundary when upgrading Rubber Band's internal API.
            RubberBand::Log log(
                [](const char* message) { std::cerr << "RubberBand: " << message << '\n'; },
                [](const char* message, double value) {
                    auto precision = std::cerr.precision(10);
                    std::cerr << "RubberBand: " << message << ": " << value << '\n';
                    std::cerr.precision(precision);
                },
                [](const char* message, double first, double second) {
                    auto precision = std::cerr.precision(10);
                    std::cerr << "RubberBand: " << message << ": (" << first << ", " << second << ")\n";
                    std::cerr.precision(precision);
                });
            rb = std::make_unique<RubberBand::R3Stretcher>(
                RubberBand::R3Stretcher::Parameters(48000, 2, options), 1.0, 1.0, log);
            rb->setMaxProcessSize(480);
            // Official real-time alignment contract: prepend the recommended silent input
            // and discard only getStartDelay() output. Ratio changes do not re-prime a stream.
            skip = rb->getStartDelay();
            auto pad = rb->getPreferredStartPad();
            while (pad > 0) {
                auto count = std::min<size_t>(pad, 480);
                rb->process(input, count, false);
                pad -= count;
            }
        }
    }
    size_t available() const {
        return rb ? static_cast<size_t>(std::max(0, rb->available())) : st->numSamples();
    }
};
AudioTimeStretcher::AudioTimeStretcher(Engine engine) : impl(std::make_unique<Impl>(engine)) {}
AudioTimeStretcher::~AudioTimeStretcher() = default;
void AudioTimeStretcher::process(const int16_t* pcm, size_t frames, double tempo) {
    auto& p = *impl;
    if (p.final || frames == 0 || frames > 480 || !std::isfinite(tempo) || tempo < 1.0 || tempo > 1.01)
        throw std::invalid_argument("Invalid PCM time-stretch input");
    if (tempo != p.tempo) {
        if (p.rb) p.rb->setTimeRatio(1.0 / tempo); else p.st->setTempo(tempo);
        p.tempo = tempo;
    }
    for (size_t i = 0; i < frames; ++i) {
        p.left[i] = pcm[i * 2] / 32768.f;
        p.right[i] = pcm[i * 2 + 1] / 32768.f;
        p.interleaved[i * 2] = p.left[i];
        p.interleaved[i * 2 + 1] = p.right[i];
    }
    if (p.rb) p.rb->process(p.input, frames, false);
    else p.st->putSamples(p.interleaved, static_cast<unsigned>(frames));
}
void AudioTimeStretcher::finish() {
    auto& p = *impl;
    if (p.final) return;
    p.final = true;
    if (p.rb) p.rb->process(p.input, 0, true); else p.st->flush();
}
size_t AudioTimeStretcher::receive(int16_t* pcm, size_t capacityFrames) {
    auto& p = *impl;
    size_t written = 0;
    while (written < capacityFrames && p.available() > 0) {
        const auto count = std::min<size_t>({p.available(), 480, capacityFrames - written});
        const auto got = p.rb ? p.rb->retrieve(p.planes, count) : p.st->receiveSamples(p.interleaved, count);
        if (!got) break;
        for (size_t i = 0; i < got; ++i) {
            if (p.skip > 0) { --p.skip; continue; }
            for (size_t ch = 0; ch < 2; ++ch) {
                const float sample = p.rb ? p.planes[ch][i] : p.interleaved[i * 2 + ch];
                if (!std::isfinite(sample)) throw std::runtime_error("Non-finite time-stretch output");
                pcm[written * 2 + ch] = static_cast<int16_t>(std::clamp(std::lrint(sample * 32768.f), -32768L, 32767L));
            }
            ++written;
        }
    }
    return written;
}
bool AudioTimeStretcher::finished() const {
    return impl->final && (impl->rb ? impl->rb->available() == -1 : impl->st->numSamples() == 0);
}
