# Detailed audio diagnostics

Enable **Detailed audio diagnostics** under **Settings → Advanced → Debug**, then disconnect
and reconnect Android Auto. The switch is off by default and applies to newly created audio
owners. Reconnect after turning it off as well. Keep the codec, matching mode and buffer
settings unchanged when comparing runs.

Reproduce the problem, note when it was audible, then export the OHU log. Include a smooth
USB run and a Wi-Fi run of the same material if possible. Selecting a queue setting can
restart playback even when the selected value is unchanged; that event is recorded separately
from a change in buffering. The AAC queue setting does not resize the PCM bank.

The recorder retains up to 2,048 records or approximately 2 MiB of text in process memory,
whichever limit is reached first. Stopping ordinary log capture does not clear this history;
export does not consume it. Closing the process does clear it. There is no guaranteed time
coverage: the number of audio owners and lifecycle events determines how quickly it wraps.
Audio data, device addresses and track names are not collected by this recorder. Other
sections of the normal exported log may still contain personal information.

## Reading the records

- `owner`, label and `seq` identify one recorder's history. Repeated exports contain the same
  records; deduplicate by owner and sequence **within the same process**, not by line count.
- `atNs` is a monotonic timestamp. Counts and phase totals are cumulative. Subtract adjacent
  records for rates; maxima are lifetime maxima. Compare thread CPU only when `tid` is unchanged. Zero values are omitted. CPU is whole-thread
  time, not exclusive time in a phase. Do not add CPU from owners sharing a thread.
- `boundary`: 0 is periodic, 1 closes an owner, 2 precedes protocol Stop, 3 precedes bank reset.
  Bank lifecycle snapshots omit thread CPU because producer and renderer share that bank.
- `lost` counts publications skipped because the four preallocated slots were full. The end
  record also reports this when the final snapshot could not fit. `overwritten` counts expired
  history records. Treat missing endpoints as missing evidence, not zero work.
- Within one bank, `OFFERED = REAL_RENDERED + COMPRESSED + DROPPED + RESET_DISCARDED + DEPTH`.
  These are PCM frames at the bank's 48 kHz rate. Rendered frames have left the bank; they are
  not proof of device playback. Concealment and synthetic ending samples are separate.
- `RENDER` includes bank access, mixing and limiting. `BANK_ENTER` measures monitor admission;
  `BANK_RENDER` includes overlap work. `WRITE_FULL` includes native writes and retry sleeps.
  Phase tuples are `count/totalNs/maxNs`. Nested phases must not be summed.
- `ADMISSION_REJECTED` means the pre-Android-5 output ring was full before a native write was
  attempted. `ZERO_WRITES` includes these rejections. `RETRY_SLEEP` includes requested sleep
  and scheduler delay; compare it with `SLEEP_REQUEST_MS`, not with zero. The existing 1/2/4 ms
  retry policy is unchanged.
- `TRACK_EPOCH`, `WRITTEN`, `PLAYED`, `RAW_HEAD`, `HEAD_AT_NS` and `QUEUED` describe existing
  AudioTrack head observations. No extra head reads are made. Compare endpoints only within
  one epoch. `ACCEPTED_SHORTS` counts samples returned by native writes, across epochs;
  it is not directly comparable with a single track's last observed head.
- `HEAD_EMPTY` includes startup and idle observations. A head is framework progress, not a
  DAC measurement. Android 4.2 has neither the modern timestamp nor underrun API. Unsupported
  measurements remain unavailable; an absent underrun count does not prove glitch-free sound.
- Correction counters distinguish eligibility, proof-window resets, proposals, waveform
  matches, deferral and actual compression. Reasons can overlap, so they are not disjoint
  amounts of lost audio. Native matching is explicitly marked and is not measured as OHU overlap.
- WPP TLS events separate elapsed and thread CPU time. Coincidence with a glitch alone does
  not establish causality. Existing transport arrival/source-timestamp logs remain necessary
  to separate late supply from output-side work.

The collector formats and writes records on a background thread. The audio path only updates
preallocated counters and publishes snapshots approximately every five seconds plus lifecycle
boundaries. Full diagnostic queues drop records, never wait for the collector. Instrumentation
still has some cost; compare enabled and disabled runs before attributing a regression to audio
policy. Buffer sizes, correction limits, packet ACKs and retry timing are not changed by this option.
