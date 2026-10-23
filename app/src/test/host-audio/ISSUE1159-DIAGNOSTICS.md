# Temporary output timing build

This branch adds measurements to upstream main for issue #1159. It does not change
buffer sizes, thread priorities, write admission, retry sleeps, ACKs or recovery policy.

Before connecting, open **Settings → Advanced → Debug**, select **Log Level: Debug**
and **Log Source: Direct to file**, then tap **Start Capturing Logs**. Reconnect after
changing the log level. Start a new capture before each comparison run.
Record at least a minute of music with unchanged PCM/output/video settings, first
over USB and then wireless, and export each log separately. Note roughly when a
stutter was heard. Selecting Info and reconnecting disables the added probes for
an observer-effect comparison. This probe does not request any new permissions.

`AudioTiming` records are bounded, normally emitted once per second per active
owner. Counters are collected on the existing receive, feed and mixer paths;
formatting and `/proc` reads run on the bounded asynchronous diagnostic logger.
These periodic records are not retained in the small rare-event history, so
direct file capture must be enabled before the session starts.

- `eventElapsedMs` timestamps the snapshot, not the eventual log write.
- `wallUs` and `cpuUs` cover the same owner window. Their difference includes
  sleep, blocking and scheduling; it is **not** a measurement of runnable delay.
- Each phase is `count/totalUs/maxUs`. Nested phases overlap, so do not sum them.
  Bank entry includes preemption as well as monitor contention. `retry_sleep`
  measures the existing 1 ms sleep including timer/scheduling overshoot.
- `receive` includes socket waiting, decrypt and dispatch. Existing slow-message
  logs split those stages. Idle socket waiting alone does not indicate a fault.
- `writtenDelta` and `headDelta` count stereo frames over `outputSpanUs`, using
  only head reads the player already performed. `acceptedShorts` counts individual
  interleaved samples, not frames. Queue extrema are observations, not continuous
  coverage. A sampled head advance does not identify when space actually became free.
- `track` identifies an output progress epoch. Replacement and flush start new
  epochs. `continuous=false` and `-1` rate fields mean no trustworthy rate can be
  calculated for that window. The head is a framework counter, not a measured DAC clock.
- `admissionRejected` counts legacy writes withheld because the native ring was
  full. `zeroWrites` also includes native writes accepting nothing. Native-call
  timing and head-query timing separate those waits from the retry loop.
- `schedRuntimeNs`, `schedRunDelayNs` and `schedSlices` are cumulative kernel task
  counters, sampled later at `schedObservedElapsedMs`. Compare only the same live
  task. They cannot be assigned to a particular call/window. Missing, disabled or
  unreadable counters are `schedstat=N/A`, not evidence of zero scheduling delay.

Use the existing PCM reserve/overflow, output recovery, focus and receive-gap
records alongside these measurements. AAudio does not expose AudioTrack probe
fields; the issue's legacy AudioTrack route is the intended comparison target.
Dropped diagnostic records and logging itself limit conclusions from short windows.
