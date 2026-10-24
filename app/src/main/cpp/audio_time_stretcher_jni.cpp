#include "audio_time_stretcher.h"
#include <jni.h>
#include <exception>
#include <new>
#include <stdexcept>

namespace {
// One worker owns the engine and its fixed blocks. Validate/bind once per segment;
// process/receive then need no array access, direct-buffer lookup or Java callbacks.
// Global references keep cached addresses alive, including during GC or teardown.
struct NativeOwner {
    explicit NativeOwner(jint engine) : dsp(static_cast<AudioTimeStretcher::Engine>(engine)) {}
    AudioTimeStretcher dsp;
    jobject inputRef = nullptr, outputRef = nullptr;
    const int16_t* input = nullptr;
    int16_t* output = nullptr;
};
NativeOwner& owner(jlong handle) {
    if (!handle) throw std::invalid_argument("Closed time stretcher");
    return *reinterpret_cast<NativeOwner*>(handle);
}
void failed(JNIEnv* env, const std::exception& error) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, error.what());
}

int16_t* pcmBuffer(JNIEnv* env, jobject data, jint frames, bool writable) {
    if (!data || frames < 0 || frames > 480)
        throw std::invalid_argument("Invalid time-stretch buffer");
    auto* pcm = static_cast<int16_t*>(env->GetDirectBufferAddress(data));
    const auto bytes = env->GetDirectBufferCapacity(data);
    if (!pcm || bytes < frames * 2 * static_cast<jlong>(sizeof(int16_t)) ||
        reinterpret_cast<uintptr_t>(pcm) % alignof(int16_t) != 0)
        throw std::invalid_argument("PCM must use aligned direct storage");
    if (writable) {
        const auto cls = env->GetObjectClass(data);
        if (!cls) return nullptr;
        const auto method = env->GetMethodID(cls, "isReadOnly", "()Z");
        env->DeleteLocalRef(cls);
        if (!method) return nullptr;
        const auto readOnly = env->CallBooleanMethod(data, method);
        if (env->ExceptionCheck()) return nullptr;
        if (readOnly) throw std::invalid_argument("PCM output must be writable");
    }
    return pcm;
}
}
#define JNI_METHOD(name) Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jint engine) {
    try { return reinterpret_cast<jlong>(new NativeOwner(engine)); }
    catch (const std::exception& e) { failed(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(bind)(JNIEnv* env, jobject, jlong handle, jobject input, jobject output) {
    try {
        auto& p = owner(handle);
        if (p.inputRef || p.outputRef) throw std::invalid_argument("Native buffers already bound");
        auto* inputPcm = pcmBuffer(env, input, 480, false);
        if (!inputPcm || env->ExceptionCheck()) return;
        auto* outputPcm = pcmBuffer(env, output, 480, true);
        if (!outputPcm || env->ExceptionCheck()) return;
        const auto inputRef = env->NewGlobalRef(input);
        if (!inputRef) throw std::bad_alloc();
        const auto outputRef = env->NewGlobalRef(output);
        if (!outputRef) { env->DeleteGlobalRef(inputRef); throw std::bad_alloc(); }
        // Commit only after both references exist. Failed binding leaves the owner
        // unbound and closeable; no partial registration or leaked global reference.
        p.inputRef = inputRef;
        p.outputRef = outputRef;
        p.input = inputPcm;
        p.output = outputPcm;
    } catch (const std::exception& e) { failed(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(process)(JNIEnv* env, jobject, jlong handle, jint frames, jdouble tempo) {
    try {
        auto& p = owner(handle);
        if (!p.input) throw std::invalid_argument("Native input is not bound");
        p.dsp.process(p.input, frames, tempo);
    } catch (const std::exception& e) { failed(env, e); }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(receive)(JNIEnv* env, jobject, jlong handle) {
    try {
        auto& p = owner(handle);
        if (!p.output) throw std::invalid_argument("Native output is not bound");
        return static_cast<jint>(p.dsp.receive(p.output, 480));
    } catch (const std::exception& e) { failed(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(finish)(JNIEnv* env, jobject, jlong handle) {
    try { owner(handle).dsp.finish(); } catch (const std::exception& e) { failed(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv* env, jobject, jlong handle) {
    auto* p = reinterpret_cast<NativeOwner*>(handle);
    if (!p) return;
    if (p->inputRef) env->DeleteGlobalRef(p->inputRef);
    if (p->outputRef) env->DeleteGlobalRef(p->outputRef);
    delete p;
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(finished)(JNIEnv* env, jobject, jlong handle) {
    try { return owner(handle).dsp.finished(); } catch (const std::exception& e) { failed(env, e); return false; }
}
