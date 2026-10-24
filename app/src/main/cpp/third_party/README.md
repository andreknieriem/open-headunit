# Audio time-stretch dependencies

- Rubber Band **4.0.0**: https://github.com/breakfastquay/rubberband/tree/v4.0.0
  `COPYING` carries GPL-2.0-or-later. Runtime engines use R3 Standard or R3 Short,
  stereo channels together, real-time mode and no internal threading. The included
  single-file build uses its portable FFT; processing arithmetic remains double.
- SoundTouch **2.4.0**: https://codeberg.org/soundtouch/soundtouch
  `COPYING.TXT` carries LGPL-2.1-or-later. Float samples, full search (no QuickSeek),
  no OpenMP, no blanket fast-math. ARM uses the portable code with compiler autovectorization; no hand-written NEON
  backend is selected. x86 uses the library's runtime-dispatched SSE path.

Only library source/header directories and their license/readme are vendored.
The app builds these sources for each configured ABI. Neither engine changes pitch.

Local Rubber Band change: `src/finer/PhaseAdvance.h` skips only peak-map and
strongest-channel preparation when the actual input/output hop arguments are equal.
The phase/history path still runs. The next unequal-hop call rebuilds those maps.
No float precision change, fast-math, approximate trigonometry or skipped FFT is used.
