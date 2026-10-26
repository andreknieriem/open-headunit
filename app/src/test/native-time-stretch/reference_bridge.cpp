#include "audio_time_stretcher.h"
#include <jni.h>
#include <type_traits>
#include <cstdlib>
#include <new>

// Test-only oracle: the former array/stack bridge feeding the same DSP code.
// Only valid fixture arguments enter here. Keep it outside Android libraries so
// comparisons exercise actual JNI transfer, without shipping a second audio path.
#define REF(name) Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_reference##name
static void failed(JNIEnv* env, const char* error) {
    if (env->ExceptionCheck()) return;
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) {
        env->ThrowNew(cls, error);
        env->DeleteLocalRef(cls);
    }
}
static AudioTimeStretcher* reference(JNIEnv* env, jlong h) {
    if (env->ExceptionCheck()) return nullptr;
    if (!h) failed(env, "Closed reference time stretcher");
    return reinterpret_cast<AudioTimeStretcher*>(h);
}
extern "C" JNIEXPORT jlong JNICALL REF(Open)(JNIEnv* env, jobject, jint engine) {
    if (env->ExceptionCheck()) return 0;
    void* storage = std::malloc(sizeof(AudioTimeStretcher));
    if (!storage) { failed(env, "Out of memory creating reference time stretcher"); return 0; }
    auto* p = ::new (storage) AudioTimeStretcher(static_cast<AudioTimeStretcher::Engine>(engine));
    if (const char* error = p->error()) {
        p->~AudioTimeStretcher();
        std::free(p);
        failed(env, error);
        return 0;
    }
    return reinterpret_cast<jlong>(p);
}
extern "C" JNIEXPORT void JNICALL REF(Process)(JNIEnv* env, jobject, jlong h, jshortArray data, jint frames, jdouble tempo) {
    auto* p = reference(env, h);
    if (!p) return;
    if (!data || frames <= 0 || frames > 480) { failed(env, "Invalid reference input"); return; }
    int16_t pcm[960];
    env->GetShortArrayRegion(data, 0, frames * 2, pcm);
    if (env->ExceptionCheck()) return;
    if (!p->process(pcm, frames, tempo)) failed(env, p->error());
}
extern "C" JNIEXPORT jint JNICALL REF(Receive)(JNIEnv* env, jobject, jlong h, jshortArray data) {
    auto* p = reference(env, h);
    if (!p) return 0;
    int16_t pcm[960];
    const auto frames = p->receive(pcm, 480);
    if (const char* error = p->error()) { failed(env, error); return 0; }
    env->SetShortArrayRegion(data, 0, static_cast<jsize>(frames * 2), pcm);
    return static_cast<jint>(frames);
}
extern "C" JNIEXPORT void JNICALL REF(Finish)(JNIEnv* env, jobject, jlong h) {
    auto* p = reference(env, h);
    if (p && !p->finish()) failed(env, p->error());
}
extern "C" JNIEXPORT void JNICALL REF(Close)(JNIEnv*, jobject, jlong h) {
    auto* p = reinterpret_cast<AudioTimeStretcher*>(h);
    if (!p) return;
    p->~AudioTimeStretcher();
    std::free(p);
}

// Test-only fault injection calls the real production entry point. Replacing this
// thread's function table leaves normal JVM operations (including -Xcheck:jni)
// intact; neither the wrappers nor this bridge are included in the APK.
extern "C" JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_bind(
    JNIEnv*, jobject, jlong, jobject, jobject);

namespace {
using NativeInterface = std::remove_cv_t<std::remove_pointer_t<decltype(JNIEnv::functions)>>;
struct BindFailureCounts {
    jint attempts = 0;
    jint created = 0;
    jint released = 0;
    jint unexpectedReleases = 0;
};
thread_local BindFailureCounts lastBindFailure;

struct BindFailureScope;
thread_local BindFailureScope* activeBindFailure = nullptr;

struct BindFailureScope {
    JNIEnv* env;
    const NativeInterface* original;
    NativeInterface replacement;
    BindFailureScope* previous;
    jint failCall;
    jthrowable error;
    jobject retained = nullptr;
    BindFailureCounts counts;

    BindFailureScope(JNIEnv* env, jint failCall, jthrowable error)
        : env(env), original(env->functions), replacement(*original),
          previous(activeBindFailure), failCall(failCall), error(error) {
        replacement.NewGlobalRef = newGlobalRef;
        replacement.DeleteGlobalRef = deleteGlobalRef;
        activeBindFailure = this;
        env->functions = &replacement;
    }
    ~BindFailureScope() {
        // No JNI calls here: the production entry point may leave OOME pending.
        env->functions = original;
        activeBindFailure = previous;
        lastBindFailure = counts;
    }
    BindFailureScope(const BindFailureScope&) = delete;
    BindFailureScope& operator=(const BindFailureScope&) = delete;

    static jobject JNICALL newGlobalRef(JNIEnv* env, jobject object) {
        auto& scope = *activeBindFailure;
        if (++scope.counts.attempts == scope.failCall) {
            if (scope.error) scope.original->Throw(env, scope.error);
            return nullptr;
        }
        auto ref = scope.original->NewGlobalRef(env, object);
        if (ref) {
            ++scope.counts.created;
            scope.retained = ref;
        }
        return ref;
    }
    static void JNICALL deleteGlobalRef(JNIEnv* env, jobject ref) {
        auto& scope = *activeBindFailure;
        if (ref && ref == scope.retained) {
            ++scope.counts.released;
            scope.retained = nullptr;
        } else {
            ++scope.counts.unexpectedReleases;
        }
        // DeleteGlobalRef is explicitly permitted with a pending exception.
        scope.original->DeleteGlobalRef(env, ref);
    }
};
}

extern "C" JNIEXPORT void JNICALL REF(BindFail)(
    JNIEnv* env, jobject receiver, jlong handle, jobject input, jobject output,
    jint failCall, jthrowable error) {
    BindFailureScope scope(env, failCall, error);
    Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_bind(
        env, receiver, handle, input, output);
}

extern "C" JNIEXPORT jintArray JNICALL REF(BindFailureCounts)(JNIEnv* env, jobject) {
    // Queried by Java only after catching the original exception, with the real
    // function table restored. Never clear/rethrow or replace that exception.
    const jint values[] = {lastBindFailure.attempts, lastBindFailure.created,
                          lastBindFailure.released, lastBindFailure.unexpectedReleases};
    auto result = env->NewIntArray(4);
    if (result) env->SetIntArrayRegion(result, 0, 4, values);
    return result;
}
