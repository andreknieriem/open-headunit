#include "audio_time_stretcher.h"
#include <jni.h>

// Test-only oracle: the former array/stack bridge feeding the same DSP code.
// Only valid fixture arguments enter here. Keep it outside Android libraries so
// comparisons exercise actual JNI transfer, without shipping a second audio path.
#define REF(name) Java_com_andrerinas_openheadunit_decoder_audio_NativeTimeStretch_reference##name
static AudioTimeStretcher& reference(jlong h) { return *reinterpret_cast<AudioTimeStretcher*>(h); }
extern "C" JNIEXPORT jlong JNICALL REF(Open)(JNIEnv*, jobject, jint engine) {
    return reinterpret_cast<jlong>(new AudioTimeStretcher(static_cast<AudioTimeStretcher::Engine>(engine)));
}
extern "C" JNIEXPORT void JNICALL REF(Process)(JNIEnv* env, jobject, jlong h, jshortArray data, jint frames, jdouble tempo) {
    int16_t pcm[960];
    env->GetShortArrayRegion(data, 0, frames * 2, pcm);
    reference(h).process(pcm, frames, tempo);
}
extern "C" JNIEXPORT jint JNICALL REF(Receive)(JNIEnv* env, jobject, jlong h, jshortArray data) {
    int16_t pcm[960];
    const auto frames = reference(h).receive(pcm, 480);
    env->SetShortArrayRegion(data, 0, static_cast<jsize>(frames * 2), pcm);
    return static_cast<jint>(frames);
}
extern "C" JNIEXPORT void JNICALL REF(Finish)(JNIEnv*, jobject, jlong h) { reference(h).finish(); }
extern "C" JNIEXPORT void JNICALL REF(Close)(JNIEnv*, jobject, jlong h) { delete &reference(h); }
