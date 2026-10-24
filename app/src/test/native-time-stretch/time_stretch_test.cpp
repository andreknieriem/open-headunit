#include "audio_time_stretcher.h"
#include <algorithm>
#include <cmath>
#include <iostream>
#include <stdexcept>
#include <vector>

static void check(bool condition, const char* message) { if (!condition) throw std::runtime_error(message); }

// Measure lookahead in supplied source frames, not host execution time. A 16 kHz
// mono capture block contains 1,024 frames; after conversion that is 3,072 frames
// here. Processing that block quickly cannot make an engine's future input exist.
static void promptLookahead(AudioTimeStretcher::Engine type) {
    for (int frames : {3072, 24000}) {
        AudioTimeStretcher engine(type);
        std::vector<int16_t> result;
        int16_t input[960]{}, output[960];
        int supplied = 0, firstOutputAfter = -1;
        bool finalizing = false;
        auto drain = [&] {
            size_t n;
            while ((n = engine.receive(output, 480))) {
                if (!finalizing && firstOutputAfter < 0) firstOutputAfter = supplied;
                result.insert(result.end(), output, output + n * 2);
            }
        };
        for (int pos = 0; pos < frames; pos += 480) {
            const int count = std::min(480, frames - pos);
            for (int f = 0; f < count; ++f) {
                const int position = pos + f;
                const double envelope = std::min(1.0, std::min((position + 1) / 240.0, (frames - position) / 240.0));
                const auto sample = static_cast<int16_t>(envelope *
                    (8000 * std::sin(position * .0576) + 4000 * std::sin(position * .1111)));
                input[f * 2] = input[f * 2 + 1] = sample;
            }
            engine.process(input, count, 1.0);
            supplied += count;
            drain();
        }
        const size_t beforeStop = result.size() / 2;
        finalizing = true;
        engine.finish(); drain();
        check(engine.finished(), "prompt did not finish");
        int lastAudible = -1;
        for (size_t f = 0; f < result.size() / 2; ++f) {
            check(result[f * 2] == result[f * 2 + 1], "mono prompt changed stereo balance");
            if (std::abs(int(result[f * 2])) > 32) lastAudible = static_cast<int>(f);
        }
        check(lastAudible >= frames - 240, "prompt ending disappeared");
        if (frames == 24000) {
            const int maximumLookahead = type == AudioTimeStretcher::Engine::SoundTouch ? 4800 :
                type == AudioTimeStretcher::Engine::R3Standard ? 4320 : 2400;
            check(firstOutputAfter > 0 && firstOutputAfter <= maximumLookahead, "native source lookahead increased");
        }
        std::cout << "prompt engine=" << static_cast<int>(type) << " input=" << frames
                  << " firstOutputAfter=" << firstOutputAfter << " beforeStop=" << beforeStop
                  << " total=" << result.size() / 2 << " lastAudible=" << lastAudible << '\n';
    }
}

int main() {
    for (auto type : {AudioTimeStretcher::Engine::SoundTouch, AudioTimeStretcher::Engine::R3Standard, AudioTimeStretcher::Engine::R3Short}) {
        promptLookahead(type);
        for (int frames : {1, 240, 480, 4800, 48000}) for (double speed : {1., 1.01}) {
            AudioTimeStretcher engine(type);
            std::vector<int16_t> result;
            int16_t input[960]{}, output[960];
            auto drain = [&] {
                size_t n;
                while ((n = engine.receive(output, 480))) result.insert(result.end(), output, output + n * 2);
            };
            for (int pos = 0; pos < frames; pos += 480) {
                const auto count = std::min(480, frames - pos);
                for (int f = 0; f < count; ++f) {
                    const auto value = static_cast<int16_t>(10000 * std::cos((pos + f) * 0.07));
                    input[f * 2] = value; input[f * 2 + 1] = -value;
                }
                engine.process(input, count, speed);
                drain();
            }
            const auto before = result.size();
            engine.finish(); drain();
            check(engine.finished(), "engine did not drain its tail");
            check(!result.empty(), "short prompt disappeared");
            check(result.size() >= before, "finalization removed emitted samples");
            const auto peak = *std::max_element(result.begin(), result.end());
            check(peak > 100, "non-silent prompt became silent");
            engine.finish();
            check(engine.receive(output, 480) == 0, "repeated finish duplicated the tail");
            std::cout << static_cast<int>(type) << " frames=" << frames << " speed=" << speed
                      << " emitted=" << result.size() / 2 << " tail=" << (result.size()-before)/2 << '\n';
        }
        // Corrections can expire between blocks. Late nonzero source must survive a
        // unity/accelerated transition and finalization, without an app length cap.
        AudioTimeStretcher changing(type);
        std::vector<int16_t> tail;
        int16_t input[960]{}, output[960];
        auto collect = [&] {
            size_t n;
            while ((n = changing.receive(output, 480))) tail.insert(tail.end(), output, output + n * 2);
        };
        for (int block = 0; block < 100; ++block) {
            for (int f = 0; f < 480; ++f) {
                input[f * 2] = block >= 98 ? static_cast<int16_t>(12000 * std::cos(f * .1)) : 0;
                input[f * 2 + 1] = -input[f * 2];
            }
            changing.process(input, 480, (block / 10) % 2 ? 1.01 : 1.0);
            collect();
        }
        changing.finish(); collect();
        check(changing.finished(), "ratio-changing stream did not finish");
        std::cerr << "dynamic engine=" << static_cast<int>(type) << " frames=" << tail.size()/2 << '\n';
        // R3 may return synthesis padding after source audio; keep it rather than
        // truncating the native tail to an estimated source/tempo length.
        check(tail.size() / 2 > 46000 && tail.size() / 2 < 56000, "unexpected output duration");
        check(std::any_of(tail.begin() + 88000, tail.end(), [](int16_t v) { return v > 100; }),
              "late source disappeared at finalization");
    }
}
