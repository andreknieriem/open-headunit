#include <jni.h>
#include <android/log.h>
#include <string.h>
#include <stdlib.h>
#include "libusb.h"

#define LOG_TAG "UsbNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_initContext(JNIEnv *env, jobject thiz) {
    libusb_set_option(NULL, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    libusb_context *ctx = NULL;
    int r = libusb_init(&ctx);
    if (r < 0) {
        LOGE("libusb_init failed: %d", r);
        return 0;
    }
    libusb_set_option(ctx, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    LOGI("libusb initialized successfully context=%p", ctx);
    return (jlong)ctx;
}

JNIEXPORT jlong JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_wrapDevice(JNIEnv *env, jobject thiz, jlong ctx_ptr, jint fd) {
    libusb_context *ctx = (libusb_context *)ctx_ptr;
    if (!ctx) {
        LOGE("wrapDevice: context is NULL");
        return 0;
    }
    libusb_device_handle *handle = NULL;
    int r = libusb_wrap_sys_device(ctx, (intptr_t)fd, &handle);
    if (r < 0) {
        LOGE("libusb_wrap_sys_device failed: %d", r);
        return 0;
    }
    LOGI("libusb wrapped system device fd=%d handle=%p", fd, handle);
    return (jlong)handle;
}

JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_detachKernel(JNIEnv *env, jobject thiz, jlong handle_ptr, jint interface_num) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (!handle) {
        LOGE("detachKernel: handle is NULL");
        return -1;
    }
    int r = libusb_detach_kernel_driver(handle, interface_num);
    if (r < 0 && r != LIBUSB_ERROR_NOT_FOUND) {
        LOGE("libusb_detach_kernel_driver failed: %d", r);
    }
    return r;
}

JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_claimInterface(JNIEnv *env, jobject thiz, jlong handle_ptr, jint interface_num) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (!handle) {
        LOGE("claimInterface: handle is NULL");
        return -1;
    }
    int r = libusb_claim_interface(handle, interface_num);
    if (r < 0) {
        LOGE("libusb_claim_interface failed: %d", r);
    }
    return r;
}

JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_nativeWrite(JNIEnv *env, jobject thiz, jlong handle_ptr, jbyteArray data, jint length, jint endpoint, jint timeout) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (!handle) {
        LOGE("nativeWrite: handle is NULL");
        return -1;
    }
    if (!data || length < 0) {
        LOGE("nativeWrite: invalid arguments (data=%p, length=%d)", (void *)data, length);
        return -3;
    }
    jsize array_len = (*env)->GetArrayLength(env, data);
    if (length > array_len) {
        length = array_len;
    }
    jbyte *buf = (*env)->GetByteArrayElements(env, data, NULL);
    if (!buf) {
        LOGE("nativeWrite: GetByteArrayElements returned NULL");
        return -2;
    }
    
    int transferred = 0;
    int r = libusb_bulk_transfer(handle, (unsigned char)endpoint, (unsigned char *)buf, length, &transferred, timeout);
    
    (*env)->ReleaseByteArrayElements(env, data, buf, JNI_ABORT);
    if (r < 0) {
        // A timeout can follow a partial write. Keep the error result: handshake callers
        // do not all require a full byte count before continuing. Log progress instead of
        // presenting an incomplete AAP frame as success or resending its prefix here.
        LOGE("libusb_bulk_transfer write failed: %d, transferred=%d/%d, endpoint=0x%02x, timeout=%dms",
             r, transferred, length, (unsigned char)endpoint, timeout);
        return r;
    }
    return transferred;
}

JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_nativeRead(JNIEnv *env, jobject thiz, jlong handle_ptr, jobject jbuf, jint endpoint, jint timeout) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (!handle) {
        LOGE("nativeRead: handle is NULL");
        return -1;
    }
    void *buffer = (*env)->GetDirectBufferAddress(env, jbuf);
    if (!buffer) {
        LOGE("nativeRead: GetDirectBufferAddress returned NULL");
        return -2;
    }
    jlong capacity = (*env)->GetDirectBufferCapacity(env, jbuf);
    if (capacity <= 0) {
        LOGE("nativeRead: GetDirectBufferCapacity returned <= 0");
        return -3;
    }
    
    int transferred = 0;
    int r = libusb_bulk_transfer(handle, (unsigned char)endpoint, (unsigned char *)buffer, (int)capacity, &transferred, timeout);
    
    if (r < 0) {
        if (r != LIBUSB_ERROR_TIMEOUT) {
            LOGE("libusb_bulk_transfer read failed: %d", r);
            return r;
        }
        // libusb reports bytes already delivered even when the remaining transfer times out.
        // They belong to the stream and must reach the Kotlin receive buffer exactly once.
        // Empty polling timeouts are routine; only log when data was preserved.
        if (transferred > 0) {
            LOGD("libusb_bulk_transfer read timeout: preserved=%d/%lld, endpoint=0x%02x, timeout=%dms",
                 transferred, (long long)capacity, (unsigned char)endpoint, timeout);
        }
        return transferred;
    }
    return transferred;
}

JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_nativeResetDevice(JNIEnv *env, jobject thiz, jlong handle_ptr) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (handle) {
        libusb_reset_device(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_closeDevice(JNIEnv *env, jobject thiz, jlong handle_ptr) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (handle) {
        libusb_close(handle);
    }
}

JNIEXPORT void JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_exitContext(JNIEnv *env, jobject thiz, jlong ctx_ptr) {
    libusb_context *ctx = (libusb_context *)ctx_ptr;
    if (ctx) {
        libusb_exit(ctx);
    }
}

JNIEXPORT jint JNICALL
Java_com_andrerinas_openheadunit_connection_usb_UsbNative_accModeSwitch(JNIEnv *env, jobject thiz, jlong handle_ptr) {
    libusb_device_handle *handle = (libusb_device_handle *)handle_ptr;
    if (!handle) return -1;
    
    unsigned char protocol_bytes[2];
    int r = libusb_control_transfer(handle, 0xC0, 51, 0, 0, protocol_bytes, 2, 1000);
    if (r < 0) {
        LOGE("accModeSwitch: Failed to get protocol version: %d", r);
        return r;
    }
    int protocol = (protocol_bytes[1] << 8) | protocol_bytes[0];
    LOGI("accModeSwitch: Protocol version is %d", protocol);
    if (protocol < 1) {
        LOGE("accModeSwitch: Accessory protocol not supported");
        return -2;
    }

    const char *strings[] = {
        "Android",
        "Android Auto",
        "Android Auto",
        "2.0.1",
        "https://www.aawireless.io",
        "0000000000000000"
    };

    for (int i = 0; i < 6; i++) {
        r = libusb_control_transfer(handle, 0x40, 52, 0, i, (unsigned char *)strings[i], strlen(strings[i]) + 1, 1000);
        if (r < 0) {
            LOGE("accModeSwitch: Failed to send string %d: %d", i, r);
            return r;
        }
    }

    LOGI("accModeSwitch: Sending start request...");
    r = libusb_control_transfer(handle, 0x40, 53, 0, 0, NULL, 0, 1000);
    if (r < 0) {
        LOGE("accModeSwitch: Failed to send start request: %d", r);
        return r;
    }

    return 0;
}
