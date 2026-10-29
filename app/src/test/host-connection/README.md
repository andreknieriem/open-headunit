# Connection lifecycle regressions

Run `python3 app/src/test/host-connection/run.py` after populating Gradle's Kotlin dependencies.
The runner extracts production lifecycle methods and uses JVM queues and latches to exercise
cancellation, ownership publication, full worker termination and USB open/close races.
Android framework and I/O endpoints are test doubles; no device is needed.

When the playback host suite is present, the runner also exercises the settings/publication
boundary with the real AapAudio and AudioDecoder owners. This optional integration check
retains standalone execution of the connection PR without a playback dependency.
It also exercises scan-control calls from the extracted connection observer with the real
route policy and strategy enum. Recording doubles replace the launcher and Shizuku APIs:
Direct/Hotspot startup, skipped live phases, disconnect/error, replacement connection identity,
USB/loopback/non-native exclusion and the Android 8 SDK gate are covered without radio changes.

## CI and local execution

Android CI runs this tool after Gradle's unit tests in the **Unit tests (github debug)** job.
A harness failure fails that job. The `host-connection-diagnostics` artifact retains its log,
extracted Kotlin sources and method/toolchain metadata, including on failure.

For local lifecycle changes, run `./gradlew :app:testGithubDebugUnitTest` first to populate
the Kotlin dependency cache and compile the actual generated protocol classes used by the
audio integration fixture, then `python3 app/src/test/host-connection/run.py`.
The Gradle task alone does not run the harness. The full runner includes the Self launch
suite, which can also be run separately with `python3 app/src/test/host-connection/self-launch.py`
after the first full run.

Production member lookup uses exact declarations; a missing or renamed member raises an
error and must be updated together with its fixture. These tools complement JVM unit tests.

The wireless cleanup suite extracts the complete service disconnect callback. It checks
late notifications, rearm during teardown, session-resource release and Exit waiter ownership
with a queued Main dispatcher; platform services are recording doubles.

The HCT owner suite also checks selection before its IO runner starts, same-channel
reuse, callback replacement and stale-client cleanup. The native carrier is a recording
double; it does not open firmware sockets or simulate vendor Bluetooth timing.

The VPN command suite extracts the GitHub flavor's actual start/stop and service command
handlers, then delivers a queued configuration after stop while the instance stays bound.
It checks that the old request cannot recreate the tunnel or replace a newer configuration.

## Socket keepalive

Authenticated socket sessions send a channel-0 GAL ping every second, including during
media traffic; USB sessions do not start this timer. Only actual incoming messages refresh
link health. The existing socket-idle and partial-frame deadlines remain in force.
Retiring or closing a transport cancels its timer, and a dequeued stale callback cannot rearm it.

`IdlePingTest` covers the real timer body, protocol frames and reader-start admission with a
deterministic clock. The host harness covers retirement and completion of old workers before
reconnection; its delayed callback is a double. An end-to-end check should also leave the
AA developer-server connection idle, then suspend the peer without closing its socket to
verify that unanswered outbound pings cannot hide a dead connection.
