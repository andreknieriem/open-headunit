cmake_minimum_required(VERSION 3.24)
project(ohu_time_stretch_tests LANGUAGES CXX)
enable_testing()
set(AUDIO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../main/cpp")
add_subdirectory(${AUDIO_ROOT}/third_party third_party)
add_executable(time_stretch_test time_stretch_test.cpp ${AUDIO_ROOT}/audio_time_stretcher.cpp)
target_compile_features(time_stretch_test PRIVATE cxx_std_17)
target_include_directories(time_stretch_test PRIVATE ${AUDIO_ROOT})
target_link_libraries(time_stretch_test PRIVATE ohu_rubberband ohu_soundtouch)
add_test(NAME time_stretch COMMAND time_stretch_test)

# Exercise the actual JNI symbols separately from C++ DSP and Kotlin fake-engine tests.
find_package(JNI REQUIRED COMPONENTS JVM)
find_package(Java REQUIRED COMPONENTS Development)
add_library(hur_time_stretch SHARED ${AUDIO_ROOT}/audio_time_stretcher.cpp ${AUDIO_ROOT}/audio_time_stretcher_jni.cpp)
target_compile_features(hur_time_stretch PRIVATE cxx_std_17)
target_include_directories(hur_time_stretch PRIVATE ${JNI_INCLUDE_DIRS})
target_link_libraries(hur_time_stretch PRIVATE ohu_rubberband ohu_soundtouch)
set(JNI_CLASSES "${CMAKE_CURRENT_BINARY_DIR}/jni-smoke")
set(JNI_CLASS "${JNI_CLASSES}/com/andrerinas/openheadunit/decoder/audio/NativeTimeStretch.class")
add_custom_command(OUTPUT "${JNI_CLASS}"
    COMMAND ${CMAKE_COMMAND} -E make_directory "${JNI_CLASSES}"
    COMMAND ${Java_JAVAC_EXECUTABLE} -d "${JNI_CLASSES}" "${CMAKE_CURRENT_SOURCE_DIR}/NativeTimeStretch.java"
    DEPENDS NativeTimeStretch.java VERBATIM)
add_custom_target(jni_smoke_classes ALL DEPENDS "${JNI_CLASS}")
add_test(NAME time_stretch_jni COMMAND ${Java_JAVA_EXECUTABLE}
    "-Djava.library.path=$<TARGET_FILE_DIR:hur_time_stretch>" -cp "${JNI_CLASSES}"
    com.andrerinas.openheadunit.decoder.audio.NativeTimeStretch)

# Inspect the linked library, not just the source list: the public facade can
# pull unused engines back in through references even when OHU selects only R3.
add_test(NAME time_stretch_library_scope COMMAND ${CMAKE_COMMAND}
    "-DNM=${CMAKE_NM}" "-DLIBRARY=$<TARGET_FILE:hur_time_stretch>"
    -P "${CMAKE_CURRENT_SOURCE_DIR}/check_library_scope.cmake")
