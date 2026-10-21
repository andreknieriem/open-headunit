#include <jni.h>
#include <sys/system_properties.h>

namespace {
constexpr const char* kProperty = "zj.phonelink.type";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_andrerinas_openheadunit_connection_bluetooth_xyauto_XyAutoProjectionProperty_nativeRead(
        JNIEnv* env, jobject) {
    char value[PROP_VALUE_MAX] = {};
    __system_property_get(kProperty, value);
    return env->NewStringUTF(value);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_andrerinas_openheadunit_connection_bluetooth_xyauto_XyAutoProjectionProperty_nativeWrite(
        JNIEnv* env, jobject, jstring value) {
    // Zlink's updateLinkType uses this libc property setter. Android's property service still
    // enforces device policy; a normal app can be denied. We never change SELinux mode.
    if (value == nullptr) return JNI_FALSE;
    const char* text = env->GetStringUTFChars(value, nullptr);
    if (text == nullptr) return JNI_FALSE;
    const int result = __system_property_set(kProperty, text);
    env->ReleaseStringUTFChars(value, text);
    return result == 0 ? JNI_TRUE : JNI_FALSE;
}
