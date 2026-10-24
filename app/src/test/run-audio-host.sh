#!/usr/bin/env bash
# Linux host suites; run Gradle unit tests first to populate the Kotlin cache.
# This deliberately loads the real host JNI library instead of accepting OHU fallback.
set -euo pipefail
cd "$(dirname "$0")/../../.."
mkdir -p build/audio-host

{
    cmake --version
    java -version
    "${CXX:-c++}" --version
} 2>&1 | tee build/audio-host/toolchain.log

cmake -S app/src/test/cpp -B build/callback-audio -DCMAKE_BUILD_TYPE=Release \
    2>&1 | tee build/audio-host/callback-configure.log
cmake --build build/callback-audio --parallel 2 \
    2>&1 | tee build/audio-host/callback-build.log
ctest --test-dir build/callback-audio --output-on-failure --no-tests=error --timeout 120 \
    2>&1 | tee build/audio-host/callback-tests.log

# Host CMake 3.24+ is required for FindJNI's JVM component. This is NOT the
# Android SDK's pinned 3.22.1 CMake used in the separate APK build job.
cmake -S app/src/test/native-time-stretch -B build/native-time-stretch -DCMAKE_BUILD_TYPE=Release \
    2>&1 | tee build/audio-host/native-configure.log
cmake --build build/native-time-stretch --parallel 2 \
    2>&1 | tee build/audio-host/native-build.log
ctest --test-dir build/native-time-stretch --output-on-failure --no-tests=error --timeout 120 \
    2>&1 | tee build/audio-host/native-tests.log

OHU_TEST_NATIVE_DSP=1 \
JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.library.path=$PWD/build/native-time-stretch" \
    python3 -u app/src/test/host-audio/run.py 2>&1 | tee build/audio-host/kotlin-jni.log
