#include <aaudio/AAudio.h>
#include <android/log.h>
#include <jni.h>
#include <dlfcn.h>
#include <array>
#include <cstdint>
#include <new>
#include "callback_pcm_buffer.h"

// Resolve at runtime: this APK also supports Android 4.1, where linking libaaudio would prevent
// the entire JNI library from loading. No audio callbacks enter Java or hold a JVM array pinned.
#define AAUDIO_FUNCTIONS(X) \
    X(AAudio_createStreamBuilder, int32_t, (AAudioStreamBuilder**)) \
    X(AAudioStreamBuilder_delete, int32_t, (AAudioStreamBuilder*)) \
    X(AAudioStreamBuilder_setDirection, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setSharingMode, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setPerformanceMode, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setSampleRate, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setChannelCount, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setFormat, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setBufferCapacityInFrames, void, (AAudioStreamBuilder*, int32_t)) \
    X(AAudioStreamBuilder_setDataCallback, void, (AAudioStreamBuilder*, AAudioStream_dataCallback, void*)) \
    X(AAudioStreamBuilder_setErrorCallback, void, (AAudioStreamBuilder*, AAudioStream_errorCallback, void*)) \
    X(AAudioStreamBuilder_openStream, int32_t, (AAudioStreamBuilder*, AAudioStream**)) \
    X(AAudioStream_getSampleRate, int32_t, (AAudioStream*)) \
    X(AAudioStream_getChannelCount, int32_t, (AAudioStream*)) \
    X(AAudioStream_getFormat, int32_t, (AAudioStream*)) \
    X(AAudioStream_getPerformanceMode, int32_t, (AAudioStream*)) \
    X(AAudioStream_getBufferCapacityInFrames, int32_t, (AAudioStream*)) \
    X(AAudioStream_getBufferSizeInFrames, int32_t, (AAudioStream*)) \
    X(AAudioStream_setBufferSizeInFrames, int32_t, (AAudioStream*, int32_t)) \
    X(AAudioStream_getFramesPerBurst, int32_t, (AAudioStream*)) \
    X(AAudioStream_getXRunCount, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestStart, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestPause, int32_t, (AAudioStream*)) \
    X(AAudioStream_requestFlush, int32_t, (AAudioStream*)) \
    X(AAudioStream_waitForStateChange, int32_t, (AAudioStream*, aaudio_stream_state_t, aaudio_stream_state_t*, int64_t)) \
    X(AAudioStream_requestStop, int32_t, (AAudioStream*)) \
    X(AAudioStream_close, int32_t, (AAudioStream*))

struct Api {
// Explicit signatures avoid referencing declarations marked unavailable at the APK's minSdk.
#define DECLARE(name, result, args) using name##Fn = result (*) args; name##Fn name = nullptr;
    AAUDIO_FUNCTIONS(DECLARE)
#undef DECLARE
    bool ready = false;
    Api() {
        void* library = dlopen("libaaudio.so", RTLD_NOW | RTLD_LOCAL);
        if (!library) return;
#define LOAD(name, result, args) name = reinterpret_cast<name##Fn>(dlsym(library, #name)); if (!name) return;
        AAUDIO_FUNCTIONS(LOAD)
#undef LOAD
        ready = true;
        // Kept loaded for the process lifetime; every output stream shares these function pointers.
    }
};

static Api& api() { static Api instance; return instance; }
struct Output {
    AAudioStream* stream = nullptr;
    std::array<jshort, 960> scratch{}; // exactly one 10ms, stereo PCM16 block
    CallbackPcmBuffer buffer;
    std::atomic<int32_t> error{AAUDIO_OK};
    uint32_t burstFrames = 480;
    uint32_t queueLimit = 480;
    uint32_t deviceBufferFrames = 960;
    bool startWanted = false;
    bool started = false;

    uint32_t effectiveQueueLimit() const {
        return std::min(CallbackPcmBuffer::capacity, std::max(queueLimit, buffer.callbackFrames()));
    }
};
static Output* output(jlong handle) { return reinterpret_cast<Output*>(static_cast<intptr_t>(handle)); }

static aaudio_data_callback_result_t render(AAudioStream*, void* context, void* data, int32_t frames) {
    auto* sink = static_cast<Output*>(context);
    if (frames < 0 || !sink->buffer.render(static_cast<int16_t*>(data), static_cast<uint32_t>(frames))) {
        if (frames > 0) std::memset(data, 0, static_cast<size_t>(frames) * 2 * sizeof(int16_t));
        sink->error.store(AAUDIO_ERROR_OUT_OF_RANGE, std::memory_order_relaxed);
        return AAUDIO_CALLBACK_RESULT_STOP;
    }
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

static void onError(AAudioStream*, void* context, aaudio_result_t error) {
    // Closing here would deadlock. The owner observes this on its next write and falls back.
    static_cast<Output*>(context)->error.store(error, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_open(JNIEnv*, jobject) {
    auto& a = api();
    if (!a.ready) return 0;
    AAudioStreamBuilder* builder = nullptr;
    if (a.AAudio_createStreamBuilder(&builder) != AAUDIO_OK || !builder) return 0;
    a.AAudioStreamBuilder_setDirection(builder, AAUDIO_DIRECTION_OUTPUT);
    // Multiple AA channels and other Android apps must retain access to the output device.
    a.AAudioStreamBuilder_setSharingMode(builder, AAUDIO_SHARING_MODE_SHARED);
    a.AAudioStreamBuilder_setPerformanceMode(builder, AAUDIO_PERFORMANCE_MODE_LOW_LATENCY);
    a.AAudioStreamBuilder_setSampleRate(builder, 48000);
    a.AAudioStreamBuilder_setChannelCount(builder, 2);
    a.AAudioStreamBuilder_setFormat(builder, AAUDIO_FORMAT_PCM_I16);
    a.AAudioStreamBuilder_setBufferCapacityInFrames(builder, 19200);
    auto* sink = new (std::nothrow) Output();
    if (!sink) { a.AAudioStreamBuilder_delete(builder); return 0; }
    a.AAudioStreamBuilder_setDataCallback(builder, render, sink);
    a.AAudioStreamBuilder_setErrorCallback(builder, onError, sink);
    const auto result = a.AAudioStreamBuilder_openStream(builder, &sink->stream);
    a.AAudioStreamBuilder_delete(builder);
    if (result != AAUDIO_OK || !sink->stream) {
        __android_log_print(ANDROID_LOG_WARN, "NativeAAudio", "open failed: %d", result);
        delete sink;
        return 0;
    }
    const auto burst = a.AAudioStream_getFramesPerBurst(sink->stream);
    if (burst <= 0 || burst > static_cast<int32_t>(CallbackPcmBuffer::capacity / 3)) {
        a.AAudioStream_close(sink->stream);
        delete sink;
        return 0;
    }
    sink->burstFrames = static_cast<uint32_t>(burst);
    sink->queueLimit = sink->burstFrames;
    sink->deviceBufferFrames = static_cast<uint32_t>(a.AAudioStream_getBufferSizeInFrames(sink->stream));
    if (a.AAudioStream_getSampleRate(sink->stream) != 48000 ||
        a.AAudioStream_getChannelCount(sink->stream) != 2 ||
        a.AAudioStream_getFormat(sink->stream) != AAUDIO_FORMAT_PCM_I16) {
        a.AAudioStream_close(sink->stream);
        delete sink;
        return 0;
    }
    __android_log_print(ANDROID_LOG_INFO, "NativeAAudio", "opened shared callback PCM16, performance=%d, burst=%d, capacity=%d",
        a.AAudioStream_getPerformanceMode(sink->stream), a.AAudioStream_getFramesPerBurst(sink->stream),
        a.AAudioStream_getBufferCapacityInFrames(sink->stream));
    return static_cast<jlong>(reinterpret_cast<intptr_t>(sink));
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_start(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    if (sink->error.load(std::memory_order_relaxed) < 0) return sink->error.load(std::memory_order_relaxed);
    sink->startWanted = true;
    // Prime the callback queue first. Starting an empty stream would create a synthetic xrun.
    return AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_pause(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    sink->startWanted = false;
    if (!sink->started) { sink->buffer.resetStopped(); return AAUDIO_OK; }
    auto& a = api();
    auto result = a.AAudioStream_requestPause(sink->stream);
    if (result < 0) return result;
    aaudio_stream_state_t state = AAUDIO_STREAM_STATE_UNINITIALIZED;
    result = a.AAudioStream_waitForStateChange(sink->stream, AAUDIO_STREAM_STATE_PAUSING, &state, 100'000'000);
    if (result < 0) return result;
    if (state != AAUDIO_STREAM_STATE_PAUSED) return AAUDIO_ERROR_INVALID_STATE;
    result = a.AAudioStream_requestFlush(sink->stream);
    if (result < 0) return result;
    result = a.AAudioStream_waitForStateChange(sink->stream, AAUDIO_STREAM_STATE_FLUSHING, &state, 100'000'000);
    if (result < 0) return result;
    if (state != AAUDIO_STREAM_STATE_FLUSHED) return AAUDIO_ERROR_INVALID_STATE;
    sink->started = false;
    sink->buffer.resetStopped();
    return AAUDIO_OK;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_write(JNIEnv* env, jobject, jlong handle,
        jshortArray data, jint offset, jint count) {
    auto* sink = output(handle);
    if (!sink || !data) return AAUDIO_ERROR_INVALID_HANDLE;
    const auto error = sink->error.load(std::memory_order_relaxed);
    if (error < 0) return error;
    if (offset < 0 || count < 0 || count > 960 || (count % 2) != 0 ||
        offset > env->GetArrayLength(data) - count) return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    // Startup callbacks may arrive back-to-back while AAudio fills its device buffer. Prime the
    // combined budget once, then bound steady staging to its own small share of that budget.
    const auto limit = sink->started ? sink->effectiveQueueLimit() :
        std::min(CallbackPcmBuffer::capacity, sink->deviceBufferFrames + sink->effectiveQueueLimit());
    const auto used = sink->buffer.available();
    const auto accepted = std::min(static_cast<uint32_t>(count / 2), used < limit ? limit - used : 0u);
    env->GetShortArrayRegion(data, offset, static_cast<jint>(accepted * 2), sink->scratch.data());
    if (env->ExceptionCheck()) return AAUDIO_ERROR_INTERNAL;
    const auto frames = sink->buffer.write(sink->scratch.data(), accepted, limit);
    if (sink->startWanted && !sink->started && sink->buffer.available() >= limit) {
        const auto result = api().AAudioStream_requestStart(sink->stream);
        if (result < 0) return result;
        sink->started = true;
    }
    return static_cast<jint>(frames * 2);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_setQueueFrames(JNIEnv*, jobject, jlong handle, jint frames) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    if (frames <= 0 || frames > static_cast<jint>(CallbackPcmBuffer::capacity)) return AAUDIO_ERROR_OUT_OF_RANGE;
    sink->queueLimit = std::max(sink->burstFrames, static_cast<uint32_t>(frames));
    return static_cast<jint>(sink->effectiveQueueLimit());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_setBufferFrames(JNIEnv*, jobject, jlong handle, jint frames) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    const auto result = api().AAudioStream_setBufferSizeInFrames(sink->stream, frames);
    if (result > 0) sink->deviceBufferFrames = static_cast<uint32_t>(result);
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_stat(JNIEnv*, jobject, jlong handle, jint kind) {
    auto* sink = output(handle);
    if (!sink) return AAUDIO_ERROR_INVALID_HANDLE;
    auto& a = api();
    switch (kind) {
        case 0: return a.AAudioStream_getBufferCapacityInFrames(sink->stream);
        case 1: return a.AAudioStream_getBufferSizeInFrames(sink->stream);
        case 2: return a.AAudioStream_getFramesPerBurst(sink->stream);
        case 3: return a.AAudioStream_getXRunCount(sink->stream);
        case 4: return static_cast<jint>(sink->effectiveQueueLimit());
        case 5: return static_cast<jint>(sink->buffer.starvationEvents());
        case 6: return static_cast<jint>(sink->buffer.available());
        case 7: return static_cast<jint>(sink->buffer.callbackFrames());
        default: return AAUDIO_ERROR_ILLEGAL_ARGUMENT;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeAAudio_close(JNIEnv*, jobject, jlong handle) {
    auto* sink = output(handle);
    if (!sink) return;
    // Only the owner closes. AAudio close joins its callback before the user data is freed.
    api().AAudioStream_requestStop(sink->stream);
    api().AAudioStream_close(sink->stream);
    delete sink;
}
