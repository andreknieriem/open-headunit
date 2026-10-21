#include <jni.h>
#include <sys/system_properties.h>

namespace {
constexpr const char* kProperty = "zj.phonelink.type";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_andrerinas_openheadunit_connection_bluetooth_xyauto_XyAutoProjectionProperty_nativeRead(
        JNIEnv* env, jobject) {
    // get() returns an empty string for both an empty value and an unavailable lookup.
    // Only an existing, readable property can establish an idle value or restoration.
    // A missing property is deliberately unavailable; do not create vendor policy here.
    const prop_info* property = __system_property_find(kProperty);
    if (property == nullptr) return nullptr;
    char value[PROP_VALUE_MAX] = {};
    // Keep the API-24 path: read_callback() is only available from API 26.
    if (__system_property_read(property, nullptr, value) < 0) return nullptr;
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
