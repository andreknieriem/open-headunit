# Pinned source dependencies, built with the app instead of opaque prebuilt audio engines.
add_library(ohu_rubberband STATIC rubberband/single/RubberBandSingle.cpp)
target_compile_features(ohu_rubberband PRIVATE cxx_std_17)
target_compile_options(ohu_rubberband PRIVATE -O3)
set_target_properties(ohu_rubberband PROPERTIES POSITION_INDEPENDENT_CODE ON)

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
