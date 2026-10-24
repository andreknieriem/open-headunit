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
smoke loads the production JNI library and calls all of its entry points for each engine.
It does not infer subjective audio quality or Android CPU performance from a
host pass. The Kotlin queue/control tests run with Gradle, and the production worker's
ordering/backpressure cases run through `app/src/test/host-audio/run.py`.
