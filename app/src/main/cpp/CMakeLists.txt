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
# Target 2: hur_soft_hevc (FFmpeg HEVC Software Decoder Support)
# =========================================================================

add_library(hur_soft_hevc SHARED ffmpeg_hevc_decoder.cpp)

target_compile_features(hur_soft_hevc PRIVATE cxx_std_17)
target_compile_options(hur_soft_hevc PRIVATE -Wall -Wextra -Werror)

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
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=1)
    target_include_directories(hur_soft_hevc PRIVATE "${FFMPEG_INCLUDE_DIR}")
    target_link_libraries(hur_soft_hevc PRIVATE ${FFMPEG_LIBS})
else()
    target_compile_definitions(hur_soft_hevc PRIVATE HUR_HAVE_FFMPEG=0)
endif()

target_link_libraries(hur_soft_hevc PRIVATE ${log-lib} ${android-lib})
