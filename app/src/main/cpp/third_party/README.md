# Audio time-stretch dependencies

- Rubber Band **4.0.0**: https://github.com/breakfastquay/rubberband/tree/v4.0.0
  `COPYING` carries GPL-2.0-or-later. Runtime engines use R3 Standard or R3 Short,
  stereo channels together, real-time mode and no internal threading. The included
  R3-only build uses its portable FFT and BQ resampler; processing arithmetic remains double.
- SoundTouch **2.4.0**: https://codeberg.org/soundtouch/soundtouch
  `COPYING.TXT` carries LGPL-2.1-or-later. Float samples, full search (no QuickSeek),
  no OpenMP, no blanket fast-math. ARM uses the portable code with compiler autovectorization; no hand-written NEON
  backend is selected. x86 uses the library's runtime-dispatched SSE path.

Only source/header dependencies of these builds and upstream license/readme files
are vendored. SoundTouch BPM detection, unused MMX code and unrelated build projects
are excluded. Rubber Band's R2, LiveShifter, C/JNI facades and unused external FFT,
resampler and vector-math implementations are excluded. The public
`RubberBandStretcher.h` remains for R3's option types and documented API contract;
its R2/R3-dispatching implementation is not built.

The OHU adapter constructs the pinned `R3Stretcher` directly with the same parameters
and logger as the 4.0.0 public facade. This is an internal API dependency: when
upgrading, compare construction, padding, process/retrieve and finalization against
the upstream facade again. No alternate engine selection is exposed by this subset.
CMake keeps the selected Rubber Band sources in one unity batch, preserving the
upstream single-file build's ability to eliminate disabled profiler calls. Build
definitions are shared with the adapter because R3 also has inline implementation.

The app builds these sources for each configured ABI. The engines adjust duration
while preserving pitch. This curated subset targets Android and the Linux host tests;
it is not an upstream standalone SDK or a general-purpose desktop distribution.

Original copyright headers and COPYING files are retained, including the notices
from removed optional external backends. Those retained notices do not imply the
backends are compiled. See the explicit source list and definitions in CMakeLists.txt.

Local Rubber Band change (2026-10-24): `src/finer/PhaseAdvance.h` skips only peak-map and
strongest-channel preparation when the actual input/output hop arguments are equal.
The phase/history path still runs. The next unequal-hop call rebuilds those maps.
No float precision change, fast-math, approximate trigonometry or skipped FFT is used.
