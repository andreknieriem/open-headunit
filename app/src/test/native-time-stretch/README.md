# Native time-stretch regression tool

This is a manual host tool. Android Gradle unit tests do not load the Android JNI library.
Run from the repository root with CMake 3.24+, a host C++17 compiler and a JDK (set JAVA_HOME if discovery fails):

```sh
cmake -S app/src/test/native-time-stretch -B build/native-time-stretch -DCMAKE_BUILD_TYPE=Release
cmake --build build/native-time-stretch --parallel 4
ctest --test-dir build/native-time-stretch --output-on-failure
```

The executable uses the same adapters and pinned library source as the APK. It checks
short inputs, native finalization and complete retrieval, including output returned only
at Stop, changing tempo, and nonzero source at the end of a stream. A separate JVM
smoke loads the production JNI library and calls all of its entry points for each engine,
including malformed buffer/count/tempo requests followed by valid playback on the same owner.
The linked-library scope check also requires SoundTouch/R3 while rejecting R2,
LiveShifter, the upstream C facade and unused BPM detection implementations.
It does not infer subjective audio quality or Android CPU performance from a
host pass. The Kotlin queue/control tests run with Gradle, and the production worker's
ordering/backpressure cases run through `app/src/test/host-audio/run.py`.

To also exercise the production Kotlin mixer/worker through the host JNI library:

```sh
OHU_TEST_NATIVE_DSP=1 \
JAVA_TOOL_OPTIONS="-Djava.library.path=$PWD/build/native-time-stretch" \
python3 app/src/test/host-audio/run.py
```

This opt-in run fails if the native library cannot load, rather than accepting an
OHU fallback. It checks music-only selection in a shared output with guidance
registered first, complete native tail delivery, and guidance duck/release for
all three engines. Android API calls still use test doubles, not a device HAL.

The `prompt` lines measure engine lookahead with 64 ms and 500 ms mono signals,
converted to 48 kHz stereo and supplied in blocks of at most 480 frames at unity
tempo. `firstOutputAfter=-1` means no output was returned before Stop. These are
source-frame counts, not wall-clock latency or an Android device benchmark.

With the pinned engines, the 500 ms fixture first returns output after 100 ms of
source for SoundTouch, 90 ms for R3 Standard and 50 ms for R3 Short. The 64 ms
fixture produces nothing before Stop with SoundTouch or R3 Standard. Final output
for the 500 ms fixture is 24,000, 28,160 and 26,368 frames respectively; R3 can
return synthesis output after the nominal source duration. The regression prints
the last sample exceeding magnitude 32 as well as the total length, since the extra
output is not necessarily all silence and must not be trimmed by a guessed duration.
These measurements explain why native processing cannot be made transparent to
short guidance simply by moving it onto a faster worker thread.

The JNI test binds reusable native-order direct blocks once per engine and checks
invalid/repeated binding, unbound calls, GC lifetime, final drain, and close. A
test-only array bridge feeds the same DSP implementation as a reference: 60
fixtures compare every returned PCM sample and block length for asymmetric
stereo, full-scale values, silence, partial input blocks and changing tempo.
The reference library is only built by this host test project, never into the APK.
`-Xcheck:jni` also checks reference and exception handling during the run.
