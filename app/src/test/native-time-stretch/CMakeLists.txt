cmake_minimum_required(VERSION 3.24)
project(ohu_time_stretch_tests LANGUAGES CXX)
enable_testing()
set(AUDIO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
add_executable(time_stretch_test time_stretch_test.cpp ${AUDIO_ROOT}/audio_time_stretcher.cpp)
target_compile_features(time_stretch_test PRIVATE cxx_std_17)
target_include_directories(time_stretch_test PRIVATE ${AUDIO_ROOT})
add_test(NAME time_stretch COMMAND time_stretch_test)

add_executable(phase_reduction_test phase_reduction_test.cpp)
target_compile_features(phase_reduction_test PRIVATE cxx_std_17)
add_test(NAME phase_reduction COMMAND phase_reduction_test)

add_executable(time_stretch_stress_test time_stretch_stress_test.cpp ${AUDIO_ROOT}/audio_time_stretcher.cpp)
target_compile_features(time_stretch_stress_test PRIVATE cxx_std_17)
target_include_directories(time_stretch_stress_test PRIVATE ${AUDIO_ROOT})
add_test(NAME time_stretch_streaming_stress COMMAND time_stretch_stress_test)
set_tests_properties(time_stretch_streaming_stress PROPERTIES TIMEOUT 180)

# Exercise the actual JNI symbols separately from C++ DSP and Kotlin fake-engine tests.
if(NOT ANDROID)
    find_package(JNI REQUIRED COMPONENTS JVM)
    find_package(Java REQUIRED COMPONENTS Development)
endif()
add_library(hur_time_stretch SHARED ${AUDIO_ROOT}/audio_time_stretcher.cpp ${AUDIO_ROOT}/audio_time_stretcher_jni.cpp)
target_compile_features(hur_time_stretch PRIVATE cxx_std_17)
target_include_directories(hur_time_stretch PRIVATE ${JNI_INCLUDE_DIRS})
add_library(hur_time_stretch_reference SHARED reference_bridge.cpp ${AUDIO_ROOT}/audio_time_stretcher.cpp)
target_compile_features(hur_time_stretch_reference PRIVATE cxx_std_17)
target_include_directories(hur_time_stretch_reference PRIVATE ${JNI_INCLUDE_DIRS} ${AUDIO_ROOT})
target_link_libraries(hur_time_stretch_reference PRIVATE hur_time_stretch)
foreach(target hur_time_stretch hur_time_stretch_reference)
    target_compile_options(${target} PRIVATE -fno-exceptions -fno-rtti)
    if(ANDROID_ABI STREQUAL "armeabi-v7a")
        target_compile_options(${target} PRIVATE -mfpu=vfpv3-d16)
    endif()
    if(CMAKE_CXX_COMPILER_ID MATCHES "Clang")
        # Match production: the reference/fault-injection companion must load on API 16.
        target_link_options(${target} PRIVATE -nostdlib++)
    endif()
    target_link_libraries(${target} PRIVATE m)
endforeach()
if(NOT ANDROID)
set(JNI_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/jni-smoke")
set(JNI_CLASS "${JNI_CLASSES}/com/andrerinas/openheadunit/decoder/audio/NativeTimeStretch.class")
add_custom_command(OUTPUT "${JNI_CLASS}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${JNI_CLASSES}"
    COMMAND ${Java_JAVAC_EXECUTABLE} -d "${JNI_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/NativeTimeStretch.java"
    DEPENDS NativeTimeStretch.java VERBATIM)
add_custom_target(jni_smoke_classes ALL DEPENDS "${JNI_CLASS}")
add_test(NAME time_stretch_jni COMMAND ${Java_JAVA_EXECUTABLE}
    -Xcheck:jni "-Djava.library.path=$<TARGET_FILE_DIR:hur_time_stretch>" -cp "${JNI_CLASSES}"
    com.andrerinas.openheadunit.decoder.audio.NativeTimeStretch)
endif()
