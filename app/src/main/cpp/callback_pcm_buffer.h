#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <cstring>

// Exactly one JNI producer and one AAudio consumer. No allocation, locks or JVM calls on read.
// Monotonic unsigned positions also work across wraparound; only a stopped stream may reset.
class CallbackPcmBuffer {
public:
    static constexpr uint32_t capacity = 16384;
    static constexpr uint32_t channels = 2;
    static_assert(std::atomic<uint32_t>::is_always_lock_free, "Audio positions must be lock-free");

    uint32_t available() const {
        return written_.load(std::memory_order_acquire) - read_.load(std::memory_order_acquire);
    }

    uint32_t write(const int16_t* source, uint32_t frames, uint32_t limit) {
        const auto tail = written_.load(std::memory_order_relaxed);
        const auto used = tail - read_.load(std::memory_order_acquire);
        const auto bound = std::min(limit, capacity);
        const auto count = std::min(frames, used < bound ? bound - used : 0u);
        const auto first = std::min(count, capacity - tail % capacity);
        std::memcpy(pcm_.data() + tail % capacity * channels, source, first * channels * sizeof(int16_t));
        std::memcpy(pcm_.data(), source + first * channels, (count - first) * channels * sizeof(int16_t));
        written_.store(tail + count, std::memory_order_release);
        return count;
    }

    // Fill every requested output frame, concealing a producer scheduling gap with a 1ms fade.
    // Software starvation must be counted separately: writing zeros keeps the HAL xrun count low.
    bool render(int16_t* destination, uint32_t frames) {
        if (frames > capacity) return false;
        callbackFrames_.store(std::max(frames, callbackFrames_.load(std::memory_order_relaxed)),
                              std::memory_order_relaxed);
        const auto head = read_.load(std::memory_order_relaxed);
        const auto count = std::min(frames, written_.load(std::memory_order_acquire) - head);
        const auto first = std::min(count, capacity - head % capacity);
        std::memcpy(destination, pcm_.data() + head % capacity * channels, first * channels * sizeof(int16_t));
        std::memcpy(destination + first * channels, pcm_.data(), (count - first) * channels * sizeof(int16_t));
        read_.store(head + count, std::memory_order_release);
        if (starved_ && count > 0) {
            const auto fade = std::min(count, 48u);
            for (uint32_t frame = 0; frame < fade; ++frame) {
                for (uint32_t ch = 0; ch < channels; ++ch) {
                    const auto index = frame * channels + ch;
                    destination[index] = static_cast<int16_t>((last_[ch] * static_cast<int32_t>(fade - frame - 1) +
                        destination[index] * static_cast<int32_t>(frame + 1)) / static_cast<int32_t>(fade));
                }
            }
        }
        if (count > 0) {
            for (uint32_t ch = 0; ch < channels; ++ch) last_[ch] = destination[(count - 1) * channels + ch];
        }
        if (count < frames) {
            if (!starved_) starvationEvents_.fetch_add(1, std::memory_order_relaxed);
            const auto fade = std::min(frames - count, 48u);
            for (uint32_t frame = 0; frame < fade; ++frame) {
                for (uint32_t ch = 0; ch < channels; ++ch) {
                    destination[(count + frame) * channels + ch] = static_cast<int16_t>(
                        last_[ch] * static_cast<int32_t>(fade - frame - 1) / static_cast<int32_t>(fade));
                }
            }
            std::memset(destination + (count + fade) * channels, 0,
                        (frames - count - fade) * channels * sizeof(int16_t));
            last_.fill(0);
        }
        starved_ = count < frames;
        return true;
    }

    uint32_t starvationEvents() const { return starvationEvents_.load(std::memory_order_relaxed); }
    uint32_t callbackFrames() const { return callbackFrames_.load(std::memory_order_relaxed); }
    void resetStopped(uint32_t position = 0) {
        written_.store(position, std::memory_order_relaxed);
        read_.store(position, std::memory_order_relaxed);
        starved_ = false;
        last_.fill(0);
    }

private:
    std::array<int16_t, capacity * channels> pcm_{};
    std::atomic<uint32_t> written_{0};
    std::atomic<uint32_t> read_{0};
    std::atomic<uint32_t> starvationEvents_{0};
    std::atomic<uint32_t> callbackFrames_{0};
    std::array<int16_t, channels> last_{}; // callback-owned until the stream is paused/closed
    bool starved_ = false;
};
