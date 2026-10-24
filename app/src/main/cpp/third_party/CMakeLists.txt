# Pinned source dependencies, built with the app instead of opaque prebuilt audio engines.
# The adapter uses the pinned R3 implementation directly. The upstream public
# facade also constructs R2, and its single-file build adds LiveShifter/C APIs.
# Keep only R3 and its common dependencies, with the same numerical backends.
add_library(ohu_rubberband STATIC
    rubberband/src/finer/R3Stretcher.cpp
    rubberband/src/common/Log.cpp
    rubberband/src/common/Profiler.cpp
    rubberband/src/common/FFT.cpp
    rubberband/src/common/Resampler.cpp
    rubberband/src/common/BQResampler.cpp
    rubberband/src/common/Allocators.cpp
    rubberband/src/common/StretchCalculator.cpp
    rubberband/src/common/sysutils.cpp
    rubberband/src/common/mathmisc.cpp)
# R3 has inline implementation in its headers: share configuration with consumers
# so the adapter and the library see identical class definitions and backends.
target_include_directories(ohu_rubberband SYSTEM PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/rubberband/src)
target_compile_definitions(ohu_rubberband PUBLIC
    USE_BQRESAMPLER=1 USE_BUILTIN_FFT=1 NO_TIMING=1 NO_THREADING=1 NO_THREAD_CHECKS=1)
target_compile_features(ohu_rubberband PRIVATE cxx_std_17)
target_compile_options(ohu_rubberband PRIVATE -O3)
# Keep the single-translation-unit optimization of the upstream amalgamation:
# NO_TIMING profiler stubs can be inlined away across the selected source files.
set_target_properties(ohu_rubberband PROPERTIES POSITION_INDEPENDENT_CODE ON
    UNITY_BUILD ON UNITY_BUILD_BATCH_SIZE 32)

set(ST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/soundtouch")
add_library(ohu_soundtouch STATIC
    ${ST_ROOT}/source/SoundTouch/AAFilter.cpp
    ${ST_ROOT}/source/SoundTouch/cpu_detect_x86.cpp
    ${ST_ROOT}/source/SoundTouch/FIFOSampleBuffer.cpp
    ${ST_ROOT}/source/SoundTouch/FIRFilter.cpp
    ${ST_ROOT}/source/SoundTouch/InterpolateCubic.cpp
    ${ST_ROOT}/source/SoundTouch/InterpolateLinear.cpp
    ${ST_ROOT}/source/SoundTouch/InterpolateShannon.cpp
    ${ST_ROOT}/source/SoundTouch/RateTransposer.cpp
    ${ST_ROOT}/source/SoundTouch/SoundTouch.cpp
    ${ST_ROOT}/source/SoundTouch/sse_optimized.cpp
    ${ST_ROOT}/source/SoundTouch/TDStretch.cpp)
target_include_directories(ohu_soundtouch PUBLIC ${ST_ROOT}/include)
target_compile_features(ohu_soundtouch PRIVATE cxx_std_17)
target_compile_definitions(ohu_soundtouch PUBLIC SOUNDTOUCH_FLOAT_SAMPLES)
target_compile_options(ohu_soundtouch PRIVATE -O3)
set_target_properties(ohu_soundtouch PROPERTIES POSITION_INDEPENDENT_CODE ON)
