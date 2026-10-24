execute_process(COMMAND "${NM}" -C --defined-only "${LIBRARY}"
    RESULT_VARIABLE result OUTPUT_VARIABLE symbols ERROR_VARIABLE error)
if(NOT result EQUAL 0)
    message(FATAL_ERROR "Cannot inspect native library: ${error}")
endif()

foreach(required "RubberBand::R3Stretcher::process" "soundtouch::SoundTouch::putSamples")
    string(FIND "${symbols}" "${required}" position)
    if(position EQUAL -1)
        message(FATAL_ERROR "Missing selected engine: ${required}")
    endif()
endforeach()

foreach(excluded "RubberBand::R2Stretcher::" "RubberBand::R3LiveShifter::"
        "RubberBand::RubberBandLiveShifter::" "RubberBand::RubberBandStretcher::"
        "soundtouch::BPMDetect::" "soundtouch::PeakFinder::" " rubberband_new")
    string(FIND "${symbols}" "${excluded}" position)
    if(NOT position EQUAL -1)
        message(FATAL_ERROR "Unused implementation linked into APK library: ${excluded}")
    endif()
endforeach()
