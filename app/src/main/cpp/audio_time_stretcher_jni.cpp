#include "audio_time_stretcher.h"
#include <jni.h>
#include <exception>
#include <stdexcept>

namespace {
AudioTimeStretcher& owner(jlong handle) {
    if (!handle) throw std::invalid_argument("Closed time stretcher");
    return *reinterpret_cast<AudioTimeStretcher*>(handle);
}
void failed(JNIEnv* env, const std::exception& error) {
    const auto cls = env->FindClass("java/lang/IllegalStateException");
    if (cls) env->ThrowNew(cls, error.what());
}
}
#define JNI_METHOD(name) Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_##name
extern "C" JNIEXPORT jlong JNICALL JNI_METHOD(open)(JNIEnv* env, jobject, jint engine) {
    try { return reinterpret_cast<jlong>(new AudioTimeStretcher(static_cast<AudioTimeStretcher::Engine>(engine))); }
    catch (const std::exception& e) { failed(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(process)(JNIEnv* env, jobject, jlong handle, jshortArray data, jint frames, jdouble tempo) {
    try {
        if (!data || frames < 0 || frames > 480 || env->GetArrayLength(data) < frames * 2)
            throw std::invalid_argument("Invalid time-stretch array");
        int16_t pcm[960];
        env->GetShortArrayRegion(data, 0, frames * 2, pcm);
        if (!env->ExceptionCheck()) owner(handle).process(pcm, frames, tempo);
    } catch (const std::exception& e) { failed(env, e); }
}
extern "C" JNIEXPORT jint JNICALL JNI_METHOD(receive)(JNIEnv* env, jobject, jlong handle, jshortArray data) {
    try {
        if (!data || env->GetArrayLength(data) < 960) throw std::invalid_argument("Invalid receive array");
        int16_t pcm[960];
        auto frames = owner(handle).receive(pcm, 480);
        env->SetShortArrayRegion(data, 0, static_cast<jsize>(frames * 2), pcm);
        return static_cast<jint>(frames);
    } catch (const std::exception& e) { failed(env, e); return 0; }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(finish)(JNIEnv* env, jobject, jlong handle) {
    try { owner(handle).finish(); } catch (const std::exception& e) { failed(env, e); }
}
extern "C" JNIEXPORT void JNICALL JNI_METHOD(close)(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<AudioTimeStretcher*>(handle);
}

extern "C" JNIEXPORT jboolean JNICALL JNI_METHOD(finished)(JNIEnv* env, jobject, jlong handle) {
    try { return owner(handle).finished(); } catch (const std::exception& e) { failed(env, e); return false; }
}
