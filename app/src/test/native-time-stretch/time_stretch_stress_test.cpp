#include "audio_time_stretcher.h"
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <iostream>
#include <optional>
#include <stdexcept>
#include <string>
#include <vector>

using Engine = AudioTimeStretcher::Engine;
namespace {
constexpr size_t guard = 4;
constexpr size_t maximumReceive = 4097;
constexpr int16_t canary = 23457;
constexpr double pi = 3.14159265358979323846;
std::string context;
size_t sourceOffset = 0;

void check(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAIL " << context << " sourceOffset=" << sourceOffset << ": " << message << '\n';
        throw std::runtime_error(message);
    }
}
size_t checkedReceive(AudioTimeStretcher& engine, int16_t* output, size_t frames) {
    const auto count = engine.receive(output, frames);
    check(!engine.error(), engine.error());
    return count;
}
void checkBackpressure(const AudioTimeStretcher& engine) {
    check(engine.error() != nullptr, "rejected block did not report an error");
    check(std::string(engine.error()) == "Time-stretch input full; receive output before retrying",
          "valid input failed for a reason other than backpressure");
}

// A fixed generator avoids implementation-dependent standard-library distributions.
struct Random {
    uint32_t state = 0x6d2b79f5U;
    uint32_t next() {
        state ^= state << 13;
        state ^= state >> 17;
        state ^= state << 5;
        return state;
    }
};

struct Segment { size_t end; double tempo; };
using Timeline = std::vector<Segment>;
enum class Schedule { Eager, Fragmented, Starved, SingleFrame };
const char* scheduleName(Schedule schedule) {
    switch (schedule) {
        case Schedule::Eager: return "eager";
        case Schedule::Fragmented: return "fragmented";
        case Schedule::Starved: return "starved";
        case Schedule::SingleFrame: return "single-frame-output";
    }
    throw std::logic_error("Unknown test schedule");
}

Timeline changingTimeline(size_t frames) {
    constexpr std::array<size_t, 11> lengths{1, 479, 3, 1021, 17, 4093, 257, 8191, 481, 2049, 61};
    constexpr std::array<double, 9> tempos{0.5, 2.0, 0.999, 1.001, 1.0, 2.0, 0.5, 1.001, 0.999};
    Timeline timeline;
    size_t end = 0, index = 0;
    while (end < frames) {
        end += std::min(lengths[index % lengths.size()], frames - end);
        timeline.push_back({end, tempos[index % tempos.size()]});
        ++index;
    }
    return timeline;
}

size_t expectedFrames(const Timeline& timeline) {
    long double duration = 0;
    size_t begin = 0;
    for (const auto& segment : timeline) {
        check(segment.end > begin, "invalid test timeline");
        duration += static_cast<long double>(segment.end - begin) / segment.tempo;
        begin = segment.end;
    }
    return static_cast<size_t>(std::llround(duration));
}

struct Render {
    std::vector<int16_t> pcm;
    size_t rejections = 0;
    size_t acceptedFrames = 0;
};

Render render(Engine type, const std::vector<int16_t>& source, const Timeline& timeline,
              Schedule schedule, const std::string& name, bool finishOnRejection = false,
              std::optional<size_t> explicitExpectedFrames = std::nullopt) {
    context = "engine=" + std::to_string(static_cast<int>(type)) + " case=" + name +
              " schedule=" + scheduleName(schedule);
    sourceOffset = 0;
    const size_t frames = source.size() / 2;
    check(source.size() % 2 == 0 && (timeline.empty() ? frames == 0 : timeline.back().end == frames),
          "test timeline does not cover source");
    size_t expected = explicitExpectedFrames ? *explicitExpectedFrames : expectedFrames(timeline);
    AudioTimeStretcher engine(type);
    check(!engine.error(), engine.error());
    Render result;
    result.pcm.reserve(expected * 2);
    std::array<int16_t, 480 * 2 + guard * 2> input{};
    std::array<int16_t, maximumReceive * 2 + guard * 2> output{};
    Random inputRandom, outputRandom;
    outputRandom.state = 0x9e3779b9U;
    size_t operations = 0, inputRequests = 0, receiveRequests = 0;
    const size_t operationLimit = (frames + expected + 1) * 8 + 10000;
    auto tick = [&] { check(++operations <= operationLimit, "consumer schedule made no bounded progress"); };
    auto capacity = [&]() -> size_t {
        ++receiveRequests;
        if (schedule == Schedule::Eager) return maximumReceive;
        if (schedule == Schedule::SingleFrame) return 1;
        if (schedule == Schedule::Fragmented) {
            if (receiveRequests % 11 == 0) return 1;
            return 1 + outputRandom.next() % 31;
        }
        switch (receiveRequests % 8) {
            case 0: return 1;
            case 1: return 2;
            case 2: return 7;
            case 3: return 31;
            case 4: return 480;
            case 5: return 481;
            case 6: return maximumReceive;
            default: return 1 + outputRandom.next() % 1024;
        }
    };
    auto receive = [&](size_t available) {
        tick();
        check(available <= maximumReceive, "invalid test output capacity");
        std::fill_n(output.begin(), available * 2 + guard * 2, canary);
        const size_t count = checkedReceive(engine, output.data() + guard, available);
        check(count <= available, "receive exceeded requested frame capacity");
        check(std::all_of(output.begin(), output.begin() + guard,
                          [](int16_t value) { return value == canary; }), "receive overwrote prefix guard");
        check(std::all_of(output.begin() + guard + count * 2,
                          output.begin() + guard * 2 + available * 2,
                          [](int16_t value) { return value == canary; }),
              "receive overwrote suffix guard or unreturned frames");
        check(result.pcm.size() / 2 + count <= expected, "receive exceeded integrated source duration");
        result.pcm.insert(result.pcm.end(), output.begin() + guard, output.begin() + guard + count * 2);
        return count;
    };
    check(!engine.finished(), "new engine is already finished");
    check(receive(1) == 0, "empty unfinished engine emitted audio");
    check(checkedReceive(engine, nullptr, 0) == 0 && receive(0) == 0, "zero capacity emitted audio");

    size_t segmentIndex = 0;
    while (sourceOffset < frames) {
        const auto& segment = timeline[segmentIndex];
        size_t requested = 480;
        if (schedule != Schedule::Eager) {
            // Force both extrema as well as deterministic arbitrary chunk boundaries.
            requested = inputRequests % 13 == 0 ? 1 :
                        inputRequests % 13 == 1 ? 480 : 1 + inputRandom.next() % 480;
        }
        ++inputRequests;
        const size_t count = std::min(requested, segment.end - sourceOffset);
        std::fill_n(input.begin(), count * 2 + guard * 2, canary);
        std::copy_n(source.begin() + sourceOffset * 2, count * 2, input.begin() + guard);
        auto accept = [&] {
            tick();
            const bool accepted = engine.process(input.data() + guard, count, segment.tempo);
            if (!accepted) { checkBackpressure(engine); ++result.rejections; }
            else check(!engine.error(), "accepted block retained an error");
            check(std::all_of(input.begin(), input.begin() + guard,
                              [](int16_t value) { return value == canary; }) &&
                  std::all_of(input.begin() + guard + count * 2, input.begin() + guard * 2 + count * 2,
                              [](int16_t value) { return value == canary; }), "process overwrote input guards");
            check(std::equal(input.begin() + guard, input.begin() + guard + count * 2,
                             source.begin() + sourceOffset * 2), "process modified borrowed input PCM");
            check(!engine.finished(), "unfinished stream reported finished");
            return accepted;
        };
        bool probed = false;
        while (!accept()) {
            if (!probed) {
                check(!accept(), "rejected block changed acceptance without a receive");
                check(checkedReceive(engine, nullptr, 0) == 0 && receive(0) == 0, "zero capacity emitted audio under pressure");
                check(!accept(), "zero-capacity receive released input backpressure");
                probed = true;
            }
            if (finishOnRejection) break;
            check(receive(capacity()) != 0, "full input ring cannot make receive progress");
        }
        if (finishOnRejection && probed) break;
        // Reusing the producer's buffer immediately must not corrupt queued audio.
        std::fill_n(input.begin(), count * 2 + guard * 2, canary);
        sourceOffset += count;
        if (sourceOffset == segment.end) ++segmentIndex;
        if (schedule == Schedule::Eager || schedule == Schedule::SingleFrame) {
            while (receive(capacity()) != 0) {}
        } else if (schedule == Schedule::Fragmented && inputRequests % 3 == 0) {
            // A consumer that wakes infrequently and does not empty available output.
            const size_t calls = 1 + outputRandom.next() % 3;
            for (size_t i = 0; i < calls; ++i) receive(capacity());
        }
    }
    result.acceptedFrames = sourceOffset;
    if (finishOnRejection) {
        check(result.rejections != 0, "Stop-on-rejection case never reached backpressure");
        if (!explicitExpectedFrames) {
            Timeline acceptedTimeline;
            size_t begin = 0;
            for (const auto& segment : timeline) {
                if (begin >= sourceOffset) break;
                acceptedTimeline.push_back({std::min(segment.end, sourceOffset), segment.tempo});
                begin = segment.end;
            }
            expected = expectedFrames(acceptedTimeline);
        }
    }
    check(engine.finish(), "finish failed");
    check(engine.finish(), "repeated finish failed");
    while (!engine.finished()) {
        check(receive(capacity()) != 0, "finalized stream stalled before expected duration");
        if (receiveRequests % 7 == 0) check(engine.finish(), "finish while draining failed");
    }
    if (result.pcm.size() / 2 != expected)
        std::cerr << "expectedFrames=" << expected << " actualFrames=" << result.pcm.size() / 2 << '\n';
    check(result.pcm.size() / 2 == expected, "duration does not follow source-assigned tempo");
    check(engine.finish(), "completed finish failed");
    check(receive(maximumReceive) == 0 && engine.finished(), "repeated finalization emitted or reopened audio");
    check(checkedReceive(engine, nullptr, 0) == 0 && receive(0) == 0, "zero capacity changed completed stream");
    return result;
}

void equalPcm(const Render& reference, const Render& actual) {
    check(reference.pcm.size() == actual.pcm.size(), "scheduling changed output duration");
    const auto mismatch = std::mismatch(reference.pcm.begin(), reference.pcm.end(), actual.pcm.begin());
    if (mismatch.first != reference.pcm.end()) {
        const size_t sample = static_cast<size_t>(mismatch.first - reference.pcm.begin());
        std::cerr << "firstDifferenceFrame=" << sample / 2 << " channel=" << sample % 2
                  << " expected=" << *mismatch.first << " actual=" << *mismatch.second << '\n';
        check(false, "input/receive scheduling or rejected retry changed PCM");
    }
}

enum class Signal { Silence, Limits, Independent, Antiphase, RightOnly, Mixed };
std::vector<int16_t> signal(size_t frames, Signal kind) {
    std::vector<int16_t> source(frames * 2);
    Random random;
    for (size_t frame = 0; frame < frames; ++frame) {
        const auto selected = kind == Signal::Mixed ? static_cast<Signal>((frame / 7919) % 5) : kind;
        int left = 0, right = 0;
        switch (selected) {
            case Signal::Silence: break;
            case Signal::Limits:
                left = frame % 4 < 2 ? 32767 - int(random.next() % 4) : -32768 + int(random.next() % 4);
                right = frame % 7 < 3 ? -32768 + int(random.next() % 4) : 32767 - int(random.next() % 4);
                break;
            case Signal::Independent:
                left = static_cast<int>(18000 * std::sin(2 * pi * 431 * frame / 48000) +
                                        7000 * std::cos(2 * pi * 1723 * frame / 48000));
                right = static_cast<int>(16000 * std::cos(2 * pi * 733 * frame / 48000) +
                                         9000 * std::sin(2 * pi * 97 * frame / 48000));
                break;
            case Signal::Antiphase:
                left = static_cast<int>(24000 * std::cos(2 * pi * 523.25 * frame / 48000));
                right = -left;
                break;
            case Signal::RightOnly:
                right = static_cast<int>(25000 * std::sin(2 * pi * 311 * frame / 48000));
                break;
            case Signal::Mixed: throw std::logic_error("Unresolved test signal");
        }
        source[frame * 2] = static_cast<int16_t>(left);
        source[frame * 2 + 1] = static_cast<int16_t>(right);
    }
    return source;
}

void signalProperties(const Render& result, Signal kind) {
    if (kind == Signal::Silence)
        check(std::all_of(result.pcm.begin(), result.pcm.end(), [](int16_t value) { return value == 0; }),
              "silence acquired nonzero output");
    for (size_t i = 0; i < result.pcm.size(); i += 2) {
        if (kind == Signal::Antiphase)
            check(std::abs(int(result.pcm[i]) + result.pcm[i + 1]) <= 1, "antiphase stereo changed under pressure");
        if (kind == Signal::RightOnly) check(result.pcm[i] == 0, "right-only source leaked into left channel");
    }
    if (kind == Signal::Independent || kind == Signal::RightOnly)
        check(std::any_of(result.pcm.begin(), result.pcm.end(), [](int16_t value) { return std::abs(int(value)) > 100; }),
              "nonzero source disappeared");
}

void schedulingAndWrap(Engine type) {
    // More than twelve 16K input-ring revolutions, with source-owned tempo changes
    // on both sides of ring wraps and changing signal/channel structure.
    const auto source = signal(196733, Signal::Mixed);
    const auto timeline = changingTimeline(source.size() / 2);
    const auto reference = render(type, source, timeline, Schedule::Eager, "long-wrap");
    for (auto schedule : {Schedule::Fragmented, Schedule::Starved}) {
        const auto actual = render(type, source, timeline, schedule, "long-wrap");
        equalPcm(reference, actual);
        check(actual.rejections > 10, "stress schedule did not repeatedly reach input backpressure");
    }
    for (auto kind : {Signal::Silence, Signal::Limits, Signal::Independent, Signal::Antiphase, Signal::RightOnly}) {
        const auto pcm = signal(17411, kind);
        const auto changes = changingTimeline(pcm.size() / 2);
        const auto name = "signal-" + std::to_string(static_cast<int>(kind));
        const auto expected = render(type, pcm, changes, Schedule::Eager, name);
        signalProperties(expected, kind);
        for (auto schedule : {Schedule::Fragmented, Schedule::Starved}) {
            const auto actual = render(type, pcm, changes, schedule, name);
            equalPcm(expected, actual);
            signalProperties(actual, kind);
        }
        if (kind == Signal::Limits || kind == Signal::Independent) {
            const auto unity = render(type, pcm, {{pcm.size() / 2, 1.0}}, Schedule::Starved, name + "-unity");
            for (size_t i = 0; i < pcm.size(); ++i)
                check(std::abs(int(unity.pcm[i]) - pcm[i]) <= 1, "unity changed full-range independent PCM");
        }
    }
}

void unityTransitions(Engine type) {
    // Long uncorrected playback must survive its first correction, backpressure,
    // and a later return to aligned unity independently of caller chunking.
    for (size_t prefix : {size_t{513}, size_t{32769}}) {
        const auto source = signal(prefix + 16384, Signal::Independent);
        for (double tempo : {0.5, 1.01, 2.0}) {
            const Timeline timeline{{prefix, 1.0}, {prefix + 4096, tempo},
                                    {prefix + 12288, tempo == 0.5 ? 2.0 : 1.0},
                                    {prefix + 16384, 1.0}};
            const auto name = "unity-transition-" + std::to_string(prefix) +
                              "-tempo-" + std::to_string(tempo);
            const auto expected = render(type, source, timeline, Schedule::Eager, name);
            for (auto schedule : {Schedule::Fragmented, Schedule::Starved, Schedule::SingleFrame})
                equalPcm(expected, render(type, source, timeline, schedule, name));
        }
    }
    // A brief compensated departure can hide inside the lookahead window while
    // still moving one rounded analysis center. It must retain the fallback history.
    for (size_t prefix : {size_t{510}, size_t{32766}}) {
        const auto source = signal(prefix + 8192, Signal::Independent);
        const Timeline timeline{{prefix, 1.0}, {prefix + 1, 0.5}, {prefix + 3, 2.0},
                                {prefix + 8192, 1.0}};
        const auto name = "compensated-unity-transition-" + std::to_string(prefix);
        const auto expected = render(type, source, timeline, Schedule::Eager, name);
        for (auto schedule : {Schedule::Fragmented, Schedule::Starved, Schedule::SingleFrame})
            equalPcm(expected, render(type, source, timeline, schedule, name));
    }
}

void unityRejectionAfterDrain(Engine type) {
    const auto source = signal(24000, Signal::Independent);
    for (bool retry : {false, true}) {
        const auto name = std::string("unity-full-after-drain-") + (retry ? "retry" : "stop");
        context = "engine=" + std::to_string(static_cast<int>(type)) + " case=" + name;
        AudioTimeStretcher engine(type);
        check(!engine.error(), engine.error());
        Render actual;
        std::array<int16_t, 960> output{};
        size_t accepted = 0;
        auto drain = [&] {
            size_t count;
            while ((count = checkedReceive(engine, output.data(), 480)) != 0) {
                check(count <= 480, "receive exceeded capacity");
                actual.pcm.insert(actual.pcm.end(), output.begin(), output.begin() + count * 2);
            }
        };
        while (accepted < 4096) {
            const auto count = std::min<size_t>(480, 4096 - accepted);
            check(engine.process(source.data() + accepted * 2, count, 1.0), "unity input rejected");
            accepted += count;
            drain();
        }
        for (;;) {
            check(accepted + 480 <= source.size() / 2, "input exhaustion was not reached");
            if (!engine.process(source.data() + accepted * 2, 480, 1.0)) {
                checkBackpressure(engine);
                break;
            }
            accepted += 480;
        }
        const auto unityFrames = accepted;
        check(!engine.process(source.data() + accepted * 2, 480, 0.5),
              "changed-tempo block bypassed input exhaustion");
        checkBackpressure(engine);
        if (retry) {
            drain();
            check(engine.process(source.data() + accepted * 2, 480, 0.5), "changed-tempo retry failed");
            accepted += 480;
        }
        check(engine.finish(), "rejected-change finish failed");
        drain();
        check(engine.finished(), "rejected-change Stop did not drain");
        const std::vector<int16_t> prefix(source.begin(), source.begin() + accepted * 2);
        Timeline timeline{{unityFrames, 1.0}};
        if (retry) timeline.push_back({accepted, 0.5});
        equalPcm(render(type, prefix, timeline, Schedule::Eager, name), actual);
    }
}

void rejectionThenStop(Engine type) {
    // A rejected block must not change even the final padding's tempo. Stop without
    // retrying it and compare only the accepted prefix with a normally drained run.
    const auto source = signal(18001, Signal::Independent);
    const std::vector<int16_t> prefix(source.begin(), source.begin() + 16384 * 2);
    for (double tempo : {0.5, 2.0}) {
        const double rejectedTempo = tempo == 0.5 ? 2.0 : 0.5;
        const auto name = "rejection-then-stop-tempo-" + std::to_string(tempo);
        const auto expected = render(type, prefix, {{16384, tempo}}, Schedule::Eager, name);
        const auto actual = render(type, source, {{16384, tempo}, {18001, rejectedTempo}},
                                   Schedule::Starved, name, true);
        check(actual.acceptedFrames == 16384, "unexpected accepted prefix before full-ring Stop");
        equalPcm(expected, actual);
    }
}

void halfFrameDuration(Engine type) {
    constexpr double exactTempo = 49.0 / 32.0;
    const std::array<double, 3> tempos{
        std::nextafter(exactTempo, 0.0), exactTempo, std::nextafter(exactTempo, 2.0)};
    // These are explicit portable oracles: 147*m/(49/32) + 1/2 = 96*m + 1/2.
    // The adjacent representable tempos lie strictly above/below that half;
    // no long-double calculation or tolerance decides the expected frame count.
    for (size_t multiplier : {size_t{1}, size_t{113}}) {
        const size_t runFrames = 147 * multiplier;
        const auto source = signal(runFrames + 1, Signal::Independent);
        for (size_t neighbor = 0; neighbor < tempos.size(); ++neighbor) {
            const size_t expected = 96 * multiplier + (neighbor == 2 ? 0 : 1);
            const Timeline timeline{{runFrames, tempos[neighbor]}, {runFrames + 1, 2.0}};
            const auto name = "half-frame-" + std::to_string(multiplier) +
                              "-neighbor-" + std::to_string(neighbor);
            const auto reference = render(type, source, timeline, Schedule::Eager, name, false, expected);
            for (auto schedule : {Schedule::Fragmented, Schedule::Starved, Schedule::SingleFrame}) {
                const auto actual = render(type, source, timeline, schedule, name, false, expected);
                equalPcm(reference, actual);
                if (multiplier == 113 && schedule == Schedule::Starved)
                    check(actual.rejections != 0, "half-frame run never reached input backpressure");
            }
            // Force every input frame into its own call, retaining exactly the
            // same tempo run despite both caller and timeline fragmentation.
            Timeline fragmented;
            for (size_t frame = 1; frame <= runFrames; ++frame)
                fragmented.push_back({frame, tempos[neighbor]});
            fragmented.push_back({runFrames + 1, 2.0});
            equalPcm(reference, render(type, source, fragmented, Schedule::Eager,
                                      name + "-single-frame-input", false, expected));
        }
    }

    std::vector<int16_t> tail(148 * 2, 0);
    tail[tail.size() - 2] = 30000;
    tail[tail.size() - 1] = -30000;
    const Timeline tailTimeline{{147, exactTempo}, {148, 2.0}};
    const auto reference = render(type, tail, tailTimeline, Schedule::Eager, "half-frame-tail", false, 97);
    check(std::abs(int(reference.pcm[reference.pcm.size() - 2])) > 1 &&
          std::abs(int(reference.pcm.back())) > 1, "half-frame final source impulse disappeared");
    for (auto schedule : {Schedule::Fragmented, Schedule::Starved, Schedule::SingleFrame})
        equalPcm(reference, render(type, tail, tailTimeline, schedule, "half-frame-tail", false, 97));

    // Fill the ring with an exact-half prefix, then reject a different tempo and
    // finish without retrying it. Neither the run anchor nor padding tempo may
    // change. 16236 + 147/(49/32) + 1/2 = 16332.5, rounded away from zero.
    const auto source = signal(18001, Signal::Independent);
    const std::vector<int16_t> prefix(source.begin(), source.begin() + 16384 * 2);
    const Timeline accepted{{16236, 1.0}, {16383, exactTempo}, {16384, 2.0}};
    const auto acceptedReference = render(type, prefix, accepted, Schedule::Eager,
                                          "half-frame-before-rejection", false, 16333);
    Timeline rejected = accepted;
    rejected.push_back({18001, 0.5});
    const auto stopped = render(type, source, rejected, Schedule::Starved,
                                "half-frame-rejection-then-stop", true, 16333);
    check(stopped.acceptedFrames == 16384, "unexpected accepted exact-half prefix before Stop");
    equalPcm(acceptedReference, stopped);
}

void boundedDurationCases(Engine type) {
    auto exercise = [&](const Timeline& timeline, const std::vector<size_t>& expectedCounts,
                        const std::string& name) {
        // Stop at each run prefix as well as at the final half-boundary. Explicit
        // integer oracles do not depend on the host's long-double precision.
        for (size_t prefixIndex = 0; prefixIndex < timeline.size(); ++prefixIndex) {
            const Timeline prefix(timeline.begin(), timeline.begin() + prefixIndex + 1);
            Timeline coalesced;
            for (const auto& segment : prefix) {
                if (!coalesced.empty() && coalesced.back().tempo == segment.tempo)
                    coalesced.back().end = segment.end;
                else
                    coalesced.push_back(segment);
            }
            const auto source = signal(prefix.back().end, Signal::Independent);
            const auto prefixName = name + "-prefix-" + std::to_string(prefixIndex);
            const auto reference = render(type, source, coalesced, Schedule::Eager,
                                           prefixName, false, expectedCounts[prefixIndex]);
            Timeline fragmented;
            size_t begin = 0;
            for (const auto& segment : prefix) {
                for (size_t frame = begin + 1; frame <= segment.end; ++frame)
                    fragmented.push_back({frame, segment.tempo});
                begin = segment.end;
            }
            for (auto schedule : {Schedule::Eager, Schedule::Fragmented, Schedule::Starved, Schedule::SingleFrame}) {
                if (schedule != Schedule::Eager)
                    equalPcm(reference, render(type, source, prefix, schedule,
                                              prefixName, false, expectedCounts[prefixIndex]));
                equalPcm(reference, render(type, source, fragmented, schedule,
                                          prefixName + "-single-frame-input", false, expectedCounts[prefixIndex]));
            }
        }
        // An integer-duration lead-in fills the ring at this same boundary.
        // Reject a different tempo, then Stop without accepting that transition.
        const size_t leadFrames = 16384 - timeline.back().end;
        Timeline accepted{{leadFrames, 1.0}};
        for (const auto& segment : timeline)
            accepted.push_back({leadFrames + segment.end, segment.tempo});
        const size_t expected = leadFrames + expectedCounts.back();
        const auto source = signal(18001, Signal::Independent);
        const std::vector<int16_t> prefix(source.begin(), source.begin() + 16384 * 2);
        const auto reference = render(type, prefix, accepted, Schedule::Eager,
                                      name + "-before-rejection", false, expected);
        accepted.push_back({18001, 0.5});
        const auto stopped = render(type, source, accepted, Schedule::Starved,
                                    name + "-rejection-then-stop", true, expected);
        check(stopped.acceptedFrames == 16384, "bounded-duration Stop accepted an unexpected prefix");
        equalPcm(reference, stopped);
    };

    constexpr double exactTempo = 49.0 / 32.0;
    const std::array<double, 3> tempos{
        std::nextafter(exactTempo, 0.0), exactTempo, std::nextafter(exactTempo, 2.0)};
    for (size_t neighbor = 0; neighbor < tempos.size(); ++neighbor) {
        const double tempo = tempos[neighbor];
        const size_t roundUp = neighbor == 2 ? 0 : 1;
        // (3 + 2*8 + 128)*32/49 + 1/2 = 96.5: rounded run
        // quotients plus Kahan summation previously selected 96.
        exercise({{3, tempo}, {11, tempo * 0.5}, {139, tempo}, {140, 2.0}},
                 {2, 12, 96, 96 + roundUp}, "split-rational-half-" + std::to_string(neighbor));
        // (2 + 2*1 + 45)*32/49 + 1/2 = 32.5: plain double-double
        // summation can retain a tiny negative remainder at this exact tie.
        exercise({{2, tempo}, {3, tempo * 0.5}, {48, tempo}, {49, 2.0}},
                 {1, 3, 32, 32 + roundUp}, "bounded-rational-half-" + std::to_string(neighbor));
    }
    const std::array<double, 3> decimalTempos{
        std::nextafter(1.6, 0.0), 1.6, std::nextafter(1.6, 2.0)};
    for (size_t neighbor = 0; neighbor < decimalTempos.size(); ++neighbor) {
        const double tempo = decimalTempos[neighbor];
        // Stored binary64 1.6 is strictly greater than 8/5, so 4/tempo
        // is below 2.5 even though ordinary binary64 division returns 2.5.
        exercise({{1, tempo}, {2, tempo}, {3, tempo}, {4, tempo}},
                 {1, 1, 2, neighbor == 0 ? size_t{3} : size_t{2}},
                 "binary64-decimal-half-" + std::to_string(neighbor));
    }
}

void endpoints(Engine type) {
    constexpr std::array<size_t, 23> lengths{
        0, 1, 2, 3, 17, 255, 256, 257, 479, 480, 481, 511, 512, 513,
        1023, 1024, 1025, 2047, 2048, 2049, 4095, 4096, 4097};
    for (size_t frames : lengths) {
        std::vector<int16_t> source(frames * 2, 0);
        if (frames) {
            source[source.size() - 2] = 30000;
            source[source.size() - 1] = -30000;
        }
        for (double tempo : {0.5, 2.0, 0.999, 1.001}) {
            const Timeline timeline = frames ? Timeline{{frames, tempo}} : Timeline{};
            const auto name = "endpoint-" + std::to_string(frames) + "-tempo-" + std::to_string(tempo);
            const auto expected = render(type, source, timeline, Schedule::Eager, name);
            if (frames)
                check(std::any_of(expected.pcm.begin(), expected.pcm.end(),
                                  [](int16_t value) { return std::abs(int(value)) > 1; }),
                      "final nonzero source frame disappeared");
            for (auto schedule : {Schedule::SingleFrame, Schedule::Starved}) {
                const auto actual = render(type, source, timeline, schedule, name);
                equalPcm(expected, actual);
                signalProperties(actual, Signal::Antiphase);
            }
        }
    }
}
} // namespace

int main(int argc, char** argv) {
    std::cout << std::unitbuf;
    try {
        const std::string selected = argc > 1 ? argv[1] : "all";
        if (argc > 2 || (selected != "all" && selected != "1" && selected != "2" && selected != "3" && selected != "4"))
            throw std::invalid_argument("usage: time_stretch_stress_test [all|1|2|3|4]");
        for (auto type : {Engine::Wsola, Engine::PhaseVocoder, Engine::PhaseVocoderShort, Engine::PhaseGradient}) {
            if (selected != "all" && selected != std::to_string(static_cast<int>(type))) continue;
            schedulingAndWrap(type);
            if (type != Engine::Wsola) unityTransitions(type);
            rejectionThenStop(type);
            unityRejectionAfterDrain(type);
            halfFrameDuration(type);
            boundedDurationCases(type);
            endpoints(type);
            std::cout << "PASS streaming stress engine=" << static_cast<int>(type) << '\n';
        }
    } catch (const std::exception& error) {
        std::cerr << context << " sourceOffset=" << sourceOffset << ": " << error.what() << '\n';
        return 1;
    }
}
