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
        std::array<int16_t, 960> data{};
        uint32_t position = 0;
        while (position < total) {
            const auto frames = std::min(480u, total - position);
            for (uint32_t f = 0; f < frames; ++f) {
                data[f * 2] = sample(position + f, 0);
                data[f * 2 + 1] = sample(position + f, 1);
            }
            position += buffer.write(data.data(), frames, 768);
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

int main() {
    writeLimitAndWrap();
    gapsAndResume();
    concurrentOrder();
    std::cout << "Callback PCM tests passed (including 2,000,000 concurrent stereo frames)\n";
}
