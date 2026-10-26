cmake_minimum_required(VERSION 3.22.1)

project("headunit")

# Loaded only when AAudio is available. Resolve libaaudio dynamically to retain minSdk 16.
add_library(hur_aaudio SHARED aaudio_output.cpp)
target_compile_features(hur_aaudio PRIVATE cxx_std_17)
target_compile_options(hur_aaudio PRIVATE -Wall -Wextra -Werror)
target_link_libraries(hur_aaudio PRIVATE log dl)

# Force 16 KB memory page size alignment for Android 15+ compatibility
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384")

# =========================================================================
# Target 1: usbhelper (Libusb Support)
# =========================================================================

# Define imported precompiled libusb1.0.so library (which is now 16 KB page-aligned)
add_library(usb1.0 SHARED IMPORTED)

set_target_properties(usb1.0 PROPERTIES
    IMPORTED_LOCATION "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libusb1.0.so"
)

# Define our JNI wrapper library
add_library(usbhelper SHARED usbhelper.c)

# Link wrapper with libusb and Android logging framework
target_link_libraries(usbhelper
    usb1.0
    log
)

# =========================================================================
# Target 2: hur_soft_video (FFmpeg H.264/H.265 Software Decoder Support)
# =========================================================================

add_library(hur_soft_video SHARED ffmpeg_video_decoder.cpp)

target_compile_features(hur_soft_video PRIVATE cxx_std_17)
target_compile_options(hur_soft_video PRIVATE -Wall -Wextra -Werror)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # FFmpeg dispatches NEON at runtime; the JNI bridge keeps the ARMv7 baseline.
    target_compile_options(hur_soft_video PRIVATE -mfpu=vfpv3-d16)
endif()

find_library(log-lib log)
find_library(android-lib android)

set(FFMPEG_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/ffmpeg")
set(FFMPEG_INCLUDE_DIR "${FFMPEG_ROOT}/include")
set(FFMPEG_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

set(FFMPEG_LIBS
    "${FFMPEG_LIB_DIR}/libavcodec.so"
    "${FFMPEG_LIB_DIR}/libavutil.so"
    "${FFMPEG_LIB_DIR}/libswscale.so"
)

set(HUR_FFMPEG_REQUIRED_FILES
    "${FFMPEG_INCLUDE_DIR}/libavcodec/avcodec.h"
    "${FFMPEG_INCLUDE_DIR}/libavutil/imgutils.h"
    "${FFMPEG_INCLUDE_DIR}/libswscale/swscale.h"
    ${FFMPEG_LIBS}
)

set(HUR_FFMPEG_READY TRUE)
foreach(required_file IN LISTS HUR_FFMPEG_REQUIRED_FILES)
    if(NOT EXISTS "${required_file}")
        set(HUR_FFMPEG_READY FALSE)
        break()
    endif()
endforeach()

message(STATUS "HUR FFmpeg for ${ANDROID_ABI}: ${HUR_FFMPEG_READY} (${FFMPEG_LIB_DIR})")

if(HUR_FFMPEG_READY)
    target_compile_definitions(hur_soft_video PRIVATE HUR_HAVE_FFMPEG=1)
    target_include_directories(hur_soft_video PRIVATE "${FFMPEG_INCLUDE_DIR}")
    target_link_libraries(hur_soft_video PRIVATE ${FFMPEG_LIBS})
else()
    target_compile_definitions(hur_soft_video PRIVATE HUR_HAVE_FFMPEG=0)
endif()

target_link_libraries(hur_soft_video PRIVATE ${log-lib} ${android-lib})

# Time correction is optional; the OHU matcher has no native dependency.
add_library(hur_time_stretch SHARED audio_time_stretcher.cpp audio_time_stretcher_jni.cpp)
target_compile_features(hur_time_stretch PRIVATE cxx_std_17)
target_compile_options(hur_time_stretch PRIVATE -O3 -Wall -Wextra -Werror -fno-exceptions -fno-rtti)
if(ANDROID_ABI STREQUAL "armeabi-v7a")
    # Android 4.x ARMv7 guarantees VFPv3-D16, not NEON.
    target_compile_options(hur_time_stretch PRIVATE -mfpu=vfpv3-d16)
endif()
# NDK 29's C++ exception/allocation runtime imports symbols absent on API 16.
# This target uses header-only C++ and checked malloc/placement ownership instead.
target_link_options(hur_time_stretch PRIVATE -nostdlib++)
target_link_libraries(hur_time_stretch PRIVATE m)
# The DSP and radix-2 FFT are independent implementations; no vendor libraries.
