#include "audio_time_stretcher.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <iostream>
#include <limits>
#include <stdexcept>
#include <vector>

using Engine = AudioTimeStretcher::Engine;
constexpr double pi = 3.14159265358979323846;
constexpr std::array<double, 9> tempos{0.5, 0.8, 1.0, 1.01, 1.05, 1.1, 1.25, 1.5, 2.0};
struct CaseContext { Engine engine{}; size_t frames = 0; double tempo = 1; bool changing = false; };
static CaseContext context;
static void check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL engine=" << static_cast<int>(context.engine) << " frames=" << context.frames
                  << " tempo=" << context.tempo << " changing=" << context.changing << ": " << message << '\n';
        throw std::runtime_error(message);
    }
}
template<class Action> static void rejects(AudioTimeStretcher& engine, Action action) {
    check(!action(), "invalid request was accepted");
    check(engine.error() != nullptr, "rejection did not report an error");
}
static size_t receive(AudioTimeStretcher& engine, int16_t* output, size_t frames) {
    const auto count = engine.receive(output, frames);
    check(!engine.error(), engine.error());
    return count;
}
struct Render { std::vector<int16_t> pcm; int firstOutputAfter = -1; };

static Render render(Engine type, const std::vector<int16_t>& source, double tempo,
                     size_t inputBlock = 480, size_t outputBlock = 480, bool changing = false) {
    context = {type, source.size() / 2, tempo, changing};
    AudioTimeStretcher engine(type);
    check(!engine.error(), engine.error());
    Render result;
    std::array<int16_t, 962> output{};
    const size_t frames = source.size() / 2;
    size_t supplied = 0;
    auto drain = [&] {
        size_t count;
        do {
            output.fill(12345);
            count = receive(engine, output.data() + 1, outputBlock);
            check(count <= outputBlock, "receive exceeded capacity");
            check(output[0] == 12345 && output[outputBlock * 2 + 1] == 12345, "receive overwrote bounds");
            if (count && result.firstOutputAfter < 0) result.firstOutputAfter = static_cast<int>(supplied);
            result.pcm.insert(result.pcm.end(), output.begin() + 1, output.begin() + 1 + count * 2);
        } while (count);
    };
    double expectedLength = 0;
    while (supplied < frames) {
        const size_t boundary = changing ? 4800 - supplied % 4800 : frames - supplied;
        const size_t count = std::min({inputBlock, frames - supplied, boundary});
        const double speed = changing ? tempos[(supplied / 4800 * 5) % tempos.size()] : tempo;
        check(engine.process(source.data() + supplied * 2, count, speed), "valid input rejected");
        expectedLength += count / speed;
        supplied += count;
        drain();
    }
    check(engine.finish(), "finish failed");
    drain();
    check(engine.finished(), "final source did not fully drain");
    check(result.pcm.size() / 2 == static_cast<size_t>(std::llround(expectedLength)), "duration does not follow source tempo");
    check(engine.finish(), "repeated finish failed");
    check(receive(engine, output.data(), 480) == 0, "repeated finish duplicated audio");
    rejects(engine, [&] { return engine.process(output.data(), 1, 1); });
    return result;
}

static std::vector<int16_t> tone(size_t frames, double frequency, int stereo) {
    std::vector<int16_t> pcm(frames * 2);
    for (size_t i = 0; i < frames; ++i) {
        pcm[i * 2] = static_cast<int16_t>(12000 * std::cos(2 * pi * frequency * i / 48000));
        pcm[i * 2 + 1] = stereo == 0 ? 0 : static_cast<int16_t>(pcm[i * 2] * stereo);
    }
    return pcm;
}
static double frequency(const std::vector<int16_t>& pcm) {
    // Interpolated positive zero crossings avoid FFT-bin quantization. Ignore the
    // endpoint windows; a resampling impostor at 1.01 would measure about 444.4 Hz.
    const size_t frames = pcm.size() / 2;
    double first = -1, last = 0;
    size_t crossings = 0;
    for (size_t i = 4801; i < frames - 4800; ++i) {
        const double before = pcm[(i - 1) * 2], after = pcm[i * 2];
        if (before <= 0 && after > 0) {
            const double position = i - 1 + (-before) / (after - before);
            if (first < 0) first = position;
            last = position; ++crossings;
        }
    }
    check(crossings > 100, "pitched source lost its periodicity");
    return (crossings - 1) * 48000 / (last - first);
}

static void unityAndShortInputs(Engine type) {
    for (size_t frames : {size_t(1), size_t(17), size_t(240), size_t(479), size_t(480), size_t(3072), size_t(24000)}) {
        std::vector<int16_t> source(frames * 2);
        for (size_t i = 0; i < source.size(); ++i)
            source[i] = static_cast<int16_t>((i * 8191 + 7919) % 65536 - 32768);
        const auto result = render(type, source, 1.0, 239, 7);
        for (size_t i = 0; i < source.size(); ++i)
            check(std::abs(int(source[i]) - result.pcm[i]) <= 1, "unity did not preserve PCM");
        for (double tempo : tempos) {
            const auto corrected = render(type, tone(frames, 440, -1), tempo, 17, 1);
            check(!corrected.pcm.empty(), "short source disappeared");
            check(*std::max_element(corrected.pcm.begin(), corrected.pcm.end()) > 100, "short source became silent");
        }
    }
    for (double tempo : tempos) {
        const auto prompt = render(type, tone(24000, 440, 1), tempo);
        check(prompt.firstOutputAfter > 0 && prompt.firstOutputAfter <= 4800, "lookahead exceeds 100 ms");
        std::cout << "engine=" << static_cast<int>(type) << " tempo=" << tempo
                  << " firstOutputAfter=" << prompt.firstOutputAfter << '\n';
    }
}

static void pitchStereoAndTiming(Engine type) {
    for (double tempo : tempos) for (int stereo : {-1, 0, 1}) {
        const auto result = render(type, tone(96000, 440, stereo), tempo);
        const double hz = frequency(result.pcm);
        check(std::abs(hz - 440) < 1.0, "tempo correction shifted pitch");
        for (size_t i = 0; i < result.pcm.size(); i += 2)
            check(std::abs(int(result.pcm[i + 1]) - result.pcm[i] * stereo) <= 1, "stereo phase or isolation changed");
        std::cout << "engine=" << static_cast<int>(type) << " tempo=" << tempo
                  << " stereo=" << stereo << " pitch=" << hz << '\n';
    }
    auto source = tone(48137, 523.25, -1);
    const auto largeBlocks = render(type, source, 1, 480, 480, true);
    const auto smallBlocks = render(type, source, 1, 17, 113, true);
    check(largeBlocks.pcm == smallBlocks.pcm, "block boundaries reassigned source tempo");
    std::fill(source.begin(), source.end() - 960, 0);
    const auto late = render(type, source, 1, 239, 7, true);
    check(std::any_of(late.pcm.end() - 4000, late.pcm.end(), [](int16_t v) { return std::abs(int(v)) > 100; }),
          "last nonzero source was lost across tempo changes and Stop");
    for (double tempo : tempos) {
        const auto ending = render(type, source, tempo, 239, 7);
        check(std::any_of(ending.pcm.end() - 4000, ending.pcm.end(),
                         [](int16_t v) { return std::abs(int(v)) > 100; }),
              "last source block disappeared at constant tempo");
    }
    for (size_t length : {size_t(48000), size_t(48001), size_t(48137)}) {
        source.assign(length * 2, 0);
        source[source.size() - 2] = 20000;
        for (double tempo : tempos) {
            const auto lastFrame = render(type, source, tempo, 239, 1);
            check(std::any_of(lastFrame.pcm.end() - 4000, lastFrame.pcm.end(),
                             [](int16_t v) { return std::abs(int(v)) > 1; }),
                  "final source frame disappeared");
        }
    }
    // A sharp attack late in a long silent source must remain audible and localized,
    // including after the input ring has wrapped many times.
    source.assign(96000 * 2, 0);
    for (size_t frame = 94000; frame < 94032; ++frame) source[frame * 2] = source[frame * 2 + 1] = 20000;
    for (double tempo : tempos) {
        const auto transient = render(type, source, tempo);
        const auto peak = std::max_element(transient.pcm.begin(), transient.pcm.end());
        check(*peak > 1000, "transient was erased");
        const auto peakFrame = static_cast<size_t>(peak - transient.pcm.begin()) / 2;
        check(std::abs(static_cast<double>(peakFrame) - 94000 / tempo) < 2400, "transient timing drifted beyond 50 ms");
    }
}

static void validationAndBackpressure(Engine type) {
    context = {type, 0, 1, false};
    AudioTimeStretcher engine(type);
    check(!engine.error(), engine.error());
    int16_t input[960], output[960];
    std::fill_n(input, 960, 1000);
    check(receive(engine, nullptr, 0) == 0, "zero receive changed state");
    rejects(engine, [&] { return engine.receive(nullptr, 1); });
    rejects(engine, [&] { return engine.process(nullptr, 1, 1); });
    rejects(engine, [&] { return engine.process(input, 0, 1); });
    rejects(engine, [&] { return engine.process(input, 481, 1); });
    for (double tempo : {0.49, 2.01, std::numeric_limits<double>::infinity(), std::numeric_limits<double>::quiet_NaN()})
        rejects(engine, [&] { return engine.process(input, 1, tempo); });
    check(engine.process(input, 480, 1), "valid retry after invalid input failed");
    check(!engine.error(), "successful retry retained error");
    check(engine.finish(), "finish after invalid-input retry failed");
    const auto expected = render(type, std::vector<int16_t>(input, input + 960), 1).pcm;
    size_t validFrames = receive(engine, output, 1), validCount;
    check(validFrames == 1 && std::equal(output, output + 2, expected.begin()),
          "prefix before output rejection changed PCM");
    rejects(engine, [&] { return engine.receive(nullptr, 480); });
    while ((validCount = receive(engine, output, 480))) {
        check((validFrames + validCount) * 2 <= expected.size() &&
              std::equal(output, output + validCount * 2, expected.begin() + validFrames * 2),
              "output rejection corrupted queued PCM");
        validFrames += validCount;
    }
    check(engine.finished() && validFrames == 480, "invalid input changed accepted duration");
    for (double tempo : {0.5, 1.0, 2.0}) {
        context.tempo = tempo;
        AudioTimeStretcher blocked(type);
        check(!blocked.error(), blocked.error());
        size_t accepted = 0;
        bool full = false;
        for (int block = 0; block < 1000; ++block) {
            if (!blocked.process(input, 480, tempo)) {
                check(blocked.error() != nullptr, "backpressure did not report an error");
                full = true;
                break;
            }
            accepted += 480;
        }
        check(full && accepted > 4800, "input backpressure is absent or cannot cover lookahead");
        size_t received = 0, count;
        while ((count = receive(blocked, output, 480))) received += count;
        check(blocked.process(input, 480, tempo), "retry after draining failed"); accepted += 480;
        check(blocked.finish(), "backpressured finish failed");
        while ((count = receive(blocked, output, 7))) {
            received += count;
            if (tempo == 1) for (size_t i = 0; i < count * 2; ++i)
                check(std::abs(output[i] - 1000) <= 1, "backpressure corrupted source");
        }
        check(blocked.finished() && received == static_cast<size_t>(std::llround(accepted / tempo)),
              "rejected block was partially accepted or drain failed");
    }
    AudioTimeStretcher empty(type);
    check(!empty.error(), empty.error());
    check(empty.finish() && empty.finish(), "empty finish failed");
    check(empty.finished() && receive(empty, output, 480) == 0, "empty finalization emitted audio");
}

int main() {
    std::cout << std::unitbuf;
    for (int id : {0, 5}) {
        AudioTimeStretcher invalid(static_cast<Engine>(id));
        const char* error = invalid.error();
        check(error != nullptr, "invalid engine accepted");
        int16_t pcm[2] = {1234, 5678};
        check(!invalid.process(pcm, 1, 1) && !invalid.finish(), "failed engine accepted work");
        check(invalid.receive(pcm, 1) == 0 && pcm[0] == 1234 && pcm[1] == 5678,
              "failed engine published audio");
        check(!invalid.finished() && invalid.error() == error, "constructor failure was not permanent");
    }
    for (auto type : {Engine::Wsola, Engine::PhaseVocoder, Engine::PhaseVocoderShort, Engine::PhaseGradient}) {
        unityAndShortInputs(type);
        pitchStereoAndTiming(type);
        validationAndBackpressure(type);
    }
    std::cout << "PASS independent DSP duration/pitch/stereo/tail/tempo/limits/backpressure\n";
}
