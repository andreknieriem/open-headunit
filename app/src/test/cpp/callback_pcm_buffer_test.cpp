#include "../../main/cpp/callback_pcm_buffer.h"
#include <cstdlib>
#include <iostream>
#include <limits>
#include <thread>

#define CHECK(expression) do { if (!(expression)) { \
    std::cerr << __LINE__ << ": " << #expression << std::endl; std::abort(); } } while (false)

static int16_t sample(uint32_t frame, uint32_t channel) {
    const auto value = static_cast<int16_t>(frame % 30000 + 1);
    return channel == 0 ? value : static_cast<int16_t>(-value);
}

static void writeLimitAndWrap() {
    CallbackPcmBuffer buffer;
    buffer.resetStopped(std::numeric_limits<uint32_t>::max() - 15);
    std::array<int16_t, 128> input{}, output{};
    for (uint32_t i = 0; i < input.size(); ++i) input[i] = sample(i / 2, i % 2);
    CHECK(buffer.write(input.data(), 64, 32) == 32);
    CHECK(buffer.available() == 32);
    CHECK(buffer.write(input.data(), 10, 32) == 0);
    CHECK(buffer.render(output.data(), 17));
    for (uint32_t i = 0; i < 34; ++i) CHECK(output[i] == input[i]);
    // Shrinking a watermark cannot overwrite unread frames or advance the reader.
    CHECK(buffer.write(input.data() + 64, 10, 8) == 0);
    CHECK(buffer.render(output.data(), 15));
    for (uint32_t i = 0; i < 30; ++i) CHECK(output[i] == input[34 + i]);
    CHECK(buffer.available() == 0);
    CHECK(buffer.starvationEvents() == 0);
}

static void gapsAndResume() {
    CallbackPcmBuffer buffer;
    std::array<int16_t, 768> input{}, output{};
    input.fill(10000);
    CHECK(buffer.write(input.data(), 4, 192) == 4);
    CHECK(buffer.render(output.data(), 16));
    CHECK(buffer.starvationEvents() == 1);
    CHECK(output[6] == 10000);
    CHECK(output[8] < 10000 && output[8] > 0);
    CHECK(output[30] == 0 && output[31] == 0);
    CHECK(buffer.render(output.data(), 192));
    CHECK(buffer.starvationEvents() == 1);
    for (uint32_t i = 0; i < 384; ++i) CHECK(output[i] == 0);
    CHECK(buffer.write(input.data(), 192, 192) == 192);
    CHECK(buffer.render(output.data(), 192));
    CHECK(output[0] > 0 && output[0] < 1000);
    CHECK(output[94] == 10000 && output[383] == 10000);
    CHECK(buffer.render(output.data(), 384));
    CHECK(buffer.starvationEvents() == 2);
    CHECK(buffer.callbackFrames() == 384);
    CHECK(!buffer.render(output.data(), CallbackPcmBuffer::capacity + 1));
    CHECK(buffer.write(input.data(), 64, 192) == 64);
    buffer.resetStopped();
    CHECK(buffer.available() == 0);
    CHECK(buffer.write(input.data(), 64, 192) == 64);
    CHECK(buffer.render(output.data(), 64));
    CHECK(output[0] == 10000); // no stale tail or gap state after a paused reset
}

static void concurrentOrder() {
    CallbackPcmBuffer buffer;
    constexpr uint32_t total = 2'000'000;
    std::thread producer([&] {
        uint32_t position = 0;
        while (position < total) {
            const auto frames = std::min(480u, total - position);
            position += buffer.writeWith(frames, 768,
                [&](int16_t* first, uint32_t firstFrames, int16_t* second, uint32_t secondFrames) {
                    for (uint32_t f = 0; f < firstFrames + secondFrames; ++f) {
                        auto* dest = f < firstFrames ? first + f * 2 : second + (f - firstFrames) * 2;
                        dest[0] = sample(position + f, 0);
                        dest[1] = sample(position + f, 1);
                    }
                    return true;
                });
            std::this_thread::yield();
        }
    });
    std::array<int16_t, 512> data{};
    uint32_t position = 0;
    while (position < total) {
        const auto frames = std::min(1u + position % 256, total - position);
        if (buffer.available() < frames) { std::this_thread::yield(); continue; }
        CHECK(buffer.render(data.data(), frames));
        for (uint32_t f = 0; f < frames; ++f) {
            CHECK(data[f * 2] == sample(position + f, 0));
            CHECK(data[f * 2 + 1] == sample(position + f, 1));
        }
        position += frames;
    }
    producer.join();
    CHECK(buffer.available() == 0);
    CHECK(buffer.starvationEvents() == 0);
}

static void failedFillIsNeverPublished() {
    CallbackPcmBuffer buffer;
    buffer.resetStopped(CallbackPcmBuffer::capacity - 12);
    std::array<int16_t, 64> initial{}, output{}, next{};
    initial.fill(1000);
    for (uint32_t i = 0; i < next.size(); ++i) next[i] = sample(i / 2, i % 2);
    CHECK(buffer.write(initial.data(), 8, 64) == 8);
    unsigned calls = 0;
    CHECK(buffer.writeWith(32, 64, [&](int16_t* first, uint32_t n, int16_t*, uint32_t rest) {
        ++calls;
        CHECK(n == 4 && rest == 28);
        std::memcpy(first, next.data(), n * 2 * sizeof(int16_t));
        CHECK(buffer.available() == 8); // first span is still invisible
        CHECK(buffer.render(output.data(), 8)); // consumer can progress during filling
        for (unsigned i = 0; i < 16; ++i) CHECK(output[i] == 1000);
        return false; // e.g. the second JNI region read fails
    }) == 0);
    CHECK(calls == 1 && buffer.available() == 0);
    CHECK(buffer.write(next.data(), 32, 32) == 32);
    CHECK(buffer.writeWith(1, 16, [&](int16_t*, uint32_t, int16_t*, uint32_t) {
        std::abort(); return true; // full or lowered limit must not invoke the filler
    }) == 0);
    CHECK(buffer.takePendingStopped(output.data(), 32) == 32);
    CHECK(output == next); // recovery sees only the retried, successfully committed PCM
    CHECK(buffer.available() == 0);
}

static void producerSchedulingGap() {
    // 192-frame callbacks consume 4ms at 48kHz. The old one-burst queue cannot
    // cover a 10ms producer scheduling gap; the new 768-frame minimum can.
    for (const auto limit : {192u, 768u}) {
        CallbackPcmBuffer buffer;
        std::array<int16_t, 1536> input{};
        std::array<int16_t, 384> output{};
        input.fill(10000);
        CHECK(buffer.write(input.data(), limit, limit) == limit);
        for (int callback = 0; callback < 3; ++callback) CHECK(buffer.render(output.data(), 192));
        CHECK(buffer.starvationEvents() == (limit == 192 ? 1u : 0u));
        if (limit == 768) CHECK(output.back() == 10000);
    }
}

static void transferOnlyUnreadAfterClose() {
    for (const auto consumed : {0u, 17u, 64u}) {
        CallbackPcmBuffer buffer;
        buffer.resetStopped(std::numeric_limits<uint32_t>::max() - 15);
        std::array<int16_t, 128> input{}, output{};
        for (uint32_t i = 0; i < input.size(); ++i) input[i] = sample(i / 2, i % 2);
        CHECK(buffer.write(input.data(), 64, 64) == 64);
        if (consumed > 0) CHECK(buffer.render(output.data(), consumed));
        CHECK(buffer.takePendingStopped(output.data(), 64) == 64 - consumed);
        for (uint32_t i = 0; i < (64 - consumed) * 2; ++i) CHECK(output[i] == input[consumed * 2 + i]);
        CHECK(buffer.available() == 0);
        CHECK(buffer.takePendingStopped(output.data(), 64) == 0);
    }
}

int main() {
    failedFillIsNeverPublished();
    transferOnlyUnreadAfterClose();
    producerSchedulingGap();
    writeLimitAndWrap();
    gapsAndResume();
    concurrentOrder();
    std::cout << "Callback PCM tests passed (including 2,000,000 concurrent stereo frames)\n";
}
