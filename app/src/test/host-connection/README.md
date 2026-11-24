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

## GAL profiles and presentation ownership

The transport requests GAL 6.1, a wire-protocol version, not an Android OS or APK version.
Inspected Android Auto builds (9.7 to 18.2) answer a request above 1.7 with their maximum
and keep the last request before TLS for their feature gates. A reply of 6.1 or higher keeps
the 6.1 profile; a lower reply is followed by one final request on the same connection,
1.6 for replies from 1.6, otherwise 1.2, and TLS starts only after that reply covers it.
A refused opening request ends the connection; later attempts in the process request 1.2.
With an opening request resent on USB, the final exchange skips as many repeats of the
opening reply as there were resends, since a reply carries no request ID.
Settings → Video → Compatibility mode (GAL 1.2) requests 1.2 only, as earlier releases did.
The setting and the advertised formats are captured for the transport; saving the setting
restarts the service, as a Video Codec change does.

Profile 1.6 adds the 48 kHz guidance offer. Profile 6.1 adds embedded media options,
standalone audio and video option updates and the modern display configuration; it shares
the 6.0 policy, as no separate 6.1 feature branch has been found. Raw peer options remain
separate, so sparse updates and new Starts keep their ownership semantics. The phone still
selects the actual audio format, and Start alone owns ACKless mode. Repeated
Start, including reuse of a wire session ID, creates a fresh owner; delayed data, feedback,
statistics and Stop from an older owner must not change the replacement.
Fresh CSD received between Stop/Setup and Start belongs to a separate preparation owner;
only the immediately following Start can adopt it without flushing the new configuration.
Each replacement output also gets a local epoch, so old surfaces cannot supply timing feedback.
Final output admission uses the current owner and options; native work already admitted may
finish after retirement, but no controller monitor is held during waits or native I/O.
Video Start activates its runtime owner before publishing work to the video handler, including
when that work runs before the reader returns. Format/handler validation still precedes activation.
Dimension notifications carry their decoder generation, revision and original listener, and
the UI validates them against the original view before mutation. Selected/configured fallback
dimensions cannot overwrite observed output dimensions on an unchanged live codec.
Read-only consumers, including PiP, take one immutable size snapshot without changing
the notification revision or invalidating a pending current-output notification.

For an end-to-end check, connect each profile to an AA developer server, verify real
projection and audio, then exercise option updates, disable, Stop and reconnect. Use both
PCM and AAC and all supported video output paths. SurfaceView may use timed MediaCodec
release and validated frame-rendered callbacks. TextureView, GLES and software output
wait on their output worker: a texture timestamp is not proof of physical presentation.
An active startup-repair frame may bypass a late deadline once within its bounded repair
window so timing control cannot strand the first-frame overlay.
GLES submissions reserve their order before either direct upload or queued fallback; an older
queued frame cannot replace a newer submitted image or its metadata.
Borrowed FFmpeg planes remain owned until the GL upload completes. Stop can detach a blocked
worker and start its replacement; the retired worker releases its own native decoder afterward.
A permanently hung upload retains those old resources rather than freeing memory still in use.

Duration limits, sparse option merging, positive
video offset direction and clock smoothing are explicit local receiver policies, not
claims of a recovered normative implementation. A/V control requires attributable audio
playout; no valid clock means bounded video-only timing rather than fabricated feedback.
