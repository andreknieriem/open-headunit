#include "audio_time_stretcher.h"
#include <jni.h>
#include <cstdlib>
#include <new>

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
void failed(JNIEnv* env, const char* error) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, error);
        env->DeleteLocalRef(cls);
    }
}
NativeOwner* owner(JNIEnv* env, jlong handle) {
    if (!handle) failed(env, "Closed time stretcher");
    return reinterpret_cast<NativeOwner*>(handle);
}

int16_t* pcmBuffer(JNIEnv* env, jobject data, jint frames, bool writable) {
    if (!data || frames < 0 || frames > 480) {
        failed(env, "Invalid time-stretch buffer");
        return nullptr;
    }
    auto* pcm = static_cast<int16_t*>(env->GetDirectBufferAddress(data));
    if (env->ExceptionCheck()) return nullptr;
    const auto bytes = env->GetDirectBufferCapacity(data);
    if (env->ExceptionCheck()) return nullptr;
    if (!pcm || bytes < frames * 2 * static_cast<jlong>(sizeof(int16_t)) ||
        reinterpret_cast<uintptr_t>(pcm) % alignof(int16_t) != 0) {
        failed(env, "PCM must use aligned direct storage");
        return nullptr;
    }
    if (writable) {
        const auto cls = env->GetObjectClass(data);
        if (!cls) return nullptr;
        const auto method = env->GetMethodID(cls, "isReadOnly", "()Z");
        env->DeleteLocalRef(cls);
        if (!method) return nullptr;
        const auto readOnly = env->CallBooleanMethod(data, method);
        if (env->ExceptionCheck()) return nullptr;
        if (readOnly) { failed(env, "PCM output must be writable"); return nullptr; }
    }
    return pcm;
}
}
#define JNI_METHOD(name) Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jint engine) {
    if (env->ExceptionCheck()) return 0;
    void* storage = std::malloc(sizeof(NativeOwner));
    if (!storage) { failed(env, "Out of memory creating time stretcher"); return 0; }
    auto* p = ::new (storage) NativeOwner(engine);
    if (const char* error = p->dsp.error()) {
        p->~NativeOwner();
        std::free(p);
        failed(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(bind)(JNIEnv* env, jobject, jlong handle, jobject input, jobject output) {
    auto* p = owner(env, handle);
    if (!p) return;
    if (p->inputRef || p->outputRef) { failed(env, "Native buffers already bound"); return; }
    auto* inputPcm = pcmBuffer(env, input, 480, false);
    if (!inputPcm || env->ExceptionCheck()) return;
    auto* outputPcm = pcmBuffer(env, output, 480, true);
    if (!outputPcm || env->ExceptionCheck()) return;
    const auto inputRef = env->NewGlobalRef(input);
    if (!inputRef) { failed(env, "Out of memory binding time stretcher"); return; }
    const auto outputRef = env->NewGlobalRef(output);
    if (!outputRef) {
        env->DeleteGlobalRef(inputRef);
        failed(env, "Out of memory binding time stretcher");
        return;
    }
    // Commit only after both references exist. Failed binding leaves the owner
    // unbound and closeable; no partial registration or leaked global reference.
    p->inputRef = inputRef;
    p->outputRef = outputRef;
    p->input = inputPcm;
    p->output = outputPcm;
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(process)(JNIEnv* env, jobject, jlong handle, jint frames, jdouble tempo) {
    auto* p = owner(env, handle);
    if (!p) return;
    if (!p->input) { failed(env, "Native input is not bound"); return; }
    if (!p->dsp.process(p->input, frames, tempo)) failed(env, p->dsp.error());
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(receive)(JNIEnv* env, jobject, jlong handle) {
    auto* p = owner(env, handle);
    if (!p) return 0;
    if (!p->output) { failed(env, "Native output is not bound"); return 0; }
    const auto frames = p->dsp.receive(p->output, 480);
    if (const char* error = p->dsp.error()) { failed(env, error); return 0; }
    return static_cast<jint>(frames);
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(finish)(JNIEnv* env, jobject, jlong handle) {
    auto* p = owner(env, handle);
    if (p && !p->dsp.finish()) failed(env, p->dsp.error());
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv* env, jobject, jlong handle) {
    auto* p = reinterpret_cast<NativeOwner*>(handle);
    if (!p) return;
    if (p->inputRef) env->DeleteGlobalRef(p->inputRef);
    if (p->outputRef) env->DeleteGlobalRef(p->outputRef);
    p->~NativeOwner();
    std::free(p);
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(finished)(JNIEnv* env, jobject, jlong handle) {
    auto* p = owner(env, handle);
    if (!p) return false;
    if (const char* error = p->dsp.error()) { failed(env, error); return false; }
    return p->dsp.finished();
}
