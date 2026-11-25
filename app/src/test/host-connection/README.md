# Connection lifecycle regressions

Run `python3 app/src/test/host-connection/run.py` after running the GitHub debug Gradle unit tests.
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

## App exit and reentry

The app-exit suite separates one physical service teardown from the latest Run/Stop decision.
Reopening the app or accepting a replacement connection revokes older finish/kill authority.
A fresh Stop can join the existing teardown with its own flags; an older accepted relay cannot
become a new Stop when it is finally delivered. Completion keeps the latest Stop stopped or
restores the latest Run without replacing the physical completion gate. A later Stop permanently
cancels waiting connection tickets even if another UI Open follows it.

Public USB/IP/socket entry captures its Stop ticket before IO dispatch. Held-socket adoption
keeps that ticket and its original connection tier; guards precede claims and preemption, not
only final publication. A cancelled old attempt therefore cannot tear down a newer connection.
The actual transport quit callback also cannot downgrade an accepted explicit Stop to a
conditional kill-on-disconnect request.

An IP or socket request is admitted in one lifecycle-locked step, never on Main: Stop ticket and
Save, adoption of a socket discovery holds, the JOIN decision against the valid request, the
arbiter claim and the request's registration. One predicate decides validity (ticket current,
claim held, Save owning the state until it publishes, then its own attempt in flight), and
joining, a launch's wait, acceptance and publication all use it. Stop and Save revocation retire
the request in their own lock section, and a claim's end wakes a launch waiting on it. A
producer's end hands its claim to the SSL session only while the attempt that claim published
is still the live one, never because another session is connected. A logical USB request (a
scan, a permission reply, a recovery step, the attach screen's switch) is the same kind of
request: admitted once where its input runs, it carries its one claim through its first IO
entry, every retry, recovery and publication, and never claims again, so a request a newer one
displaced stops at its next step. Its retries stop on its own attempt's arrival, never on the
shared state SSL may already have moved on. It is never a JOIN target and no launch waits on it.
A USB scan that cannot run yet is one owed object (`UsbRecheck`) carried by whatever holds it (a
live attempt, the arbiter, the give-back, the queued scan, the settings hold, a permission
dialog by its reply address, the request admitted for it) and checked again before it scans and at
USB admission; a user exit, Stop or an end that holds automatic reconnection withdraws it
wherever it waits, retiring a request admitted for it. Deferred USB work (the attach fallback,
the accessory-detach re-sync, the reconnect after an end, Save's retry and fallback, the
handshake-end recovery, a permission reply) carries the object its input minted when it was
received and never mints one when it runs; only an input happening now (attach, wake, screen-on,
a tap, a service command) mints its own. Before first unlock the attach screen's switch keeps a
bare arbiter claim: no request authority can exist yet, and its worker checks that claim still
holds before its first USB call. The ingress fixtures
pause real producers inside
each former gap (claim before record, Stop or Save or another claim taking a waiting request's
authority, a discovery hand-off queued behind IO, a cancelled request that published nothing
while another session is connected, a late USB request, a handshake that fails after a
cancelled request released its claim); the Self fixture pins that a refused dial never waits on
an admitted USB request.

The ticket belongs to the logical input, not to one public call. UI, automation, server accept,
discovery scan, Nearby request, Self launch, VPN consent and service relays capture it when the
input is accepted and carry it through queued work, permission waits, countdowns and internal
retries. The service's own deferred work does the same: a session end's teardown-wait work
(Save's retry and fallback, the wired-session re-arm), the reconnect timers, the arbiter's
wireless give-back, the USB hand-off hold and the WiFi wait, the sleep and settings re-arms, a
Nearby Save peer, a discovery Save's find and a held Bluetooth arrival each use the ticket of
their input and hand that value to `setActive`/discovery/connect admission, never a newer one.
A session end carries the ticket and owed-scan epoch CommManager published it with; the service
collector does not read them after its hop. The hop after a fresh input is not fresh: the
service mints a ticket only for genuinely external starts (system restart, media button, a Run
owner's restore). Every internal relay carries the ticket of its input, and a USB check relay
carries its scan; one sent without them is refused, as is one whose ticket Stop has moved. The
Bluetooth arrival reaches MainActivity with its ticket, and the home's automatic Self start
keeps it. The wireless cleanup fixture runs the real disconnect callback with Stop inside
Save's teardown wait and before the end is received. The
caller fixtures extract those producers and run them against the real manager,
arbiter and service-ingress prefix: Stop B -> Open C rejects work begun before B, while a
compatible Open without Stop and genuinely new input remain accepted. A socket discovery holds
is owned only by the caller that adopts it, decided before anything can refuse it; background
discovery and the user's scan share one held hand-off, and a hand-off whose socket another
request adopted leaves it open. Only an owner ever closes a handed-over socket.
The USB unit regressions drive the actual scan, permission poll, retry loop and StandardUsb
open; physical opens are counted at `UsbManager.openDevice`.

A revoked in-flight scan, Self launch or Nearby attempt never absorbs a valid newer input:
the stale work is detached and the new input starts, while a same-ticket duplicate is still
folded into the running work. The overlap regressions submit the fresh input while the stale
work is still in flight. Nearby validates tickets and changes attempt state lifecycle-first
(lifecycle lock, then attempt monitor), the same order publication admission uses; SDK calls
and resource cleanup run outside both. The lock regression drives the actual bandwidth
callback against a held admission and checks for a JVM-detected deadlock.

Self Mode keeps no session-lifetime flag. CommManager owns each connection attempt from its
publication, before its connection object is registered, and settles it under the lifecycle lock
once it reaches its connection, ends or is replaced; a monotonic count dates every physical
arrival. The first arrival after a Self launch's acceptance answers that launch whether or not
any state was observed. While an attempt is forming, or the valid request has yet to publish
one, the launch waits on it, not on the conflated flow, and it fails only when nothing arrived
and nothing is in flight. Self is active
while a launch is unanswered or a loopback attempt is live, and a terminal's own route selects its
end policy. A refused request's diagnostic may replace only an idle state. `run.py` drives the
actual publishers through Connecting(A) -> ended(A) -> Connecting(B), ends hidden behind an Error,
repeated terminals and a refused USB report during an unregistered attempt. The Self fixture runs
the actual launch, deadline, observer and end branch against the extracted attempt primitives,
request authority and terminal publisher; its publication lock sections mirror `connectIp`.

Connection acceptance and publication share Main ordering with activity finish and process
exit effects. The scheduling suite holds task lookup and process-exit preparation while a
replacement queues, and exercises cancellation before and after resource transfer. Ordinary
publication rechecks the latest teardown job, not only the job seen before dispatching to Main.
Restricted handoff rejects an unfinished teardown at final transfer. The regressions queue a
new cleanup between an older join and publication/terminal capture, then verify old workers and
audio/video resources retire without closing the replacement. Android effects and dispatchers
are controlled doubles; native connection teardown stays outside publication locks.

The bootstrap fixture extracts the real command-validation prefix and destruction completion.
A stale Run/Stop retires an uninitialized service before `stopSelf`, so a queued valid start
cannot initialize that already-doomed instance; only the current Run is restored afterward.
Foreground policy tests distinguish actual Intent delivery, retained configuration identity and
one process-wide fully-stopped return episode across Main, Projection and Settings. They do not
model every focus-only, PiP, multi-window or config/Home-overlap return.
Automatic Home work starts only from a current Run and keeps that Stop ticket across both
auto blocks, the countdown, last-session IP/USB, single USB and Self/VPN consent. Completed
Stop followed by configuration recreation with screen-on start and single-USB auto-connect
enabled, with no USB attached, initializes no service; genuine owned Open still restores it.
Real Android instrumentation separately exercises task return, that configuration case and
delayed finish delivery.

Native group coordinator tests retain cleanup responsibility beyond service destruction and
hold new adoption/create work behind submitted removal callbacks. Actual active adoption clears
only detached cleanup authority, leaving active transport shutdown on the normal service path.
A late abandoned create records its framework removal boundary before submission, so failure
preserves responsibility for the next fresh Stop. An absent callback leaves the barrier closed
rather than asserting completion. A service Stop that arrives after logical Disconnected but
before the transport has closed waits on that actual retirement, so native removal cannot be
submitted first. These are recording framework boundaries, not proof of manufacturer radio
behavior. Activity callback ordering and actual task/process effects still require Android coverage.

The JVM settings-import regressions use the actual backup parser, preferences and device-protected
mirrors with a blocking Reader. Read/parse may outlive cancellation but has no write authority;
current-operation validation, apply, mirror synchronization and completion run in one Main turn.
Save/reset and view destruction invalidate older UI operations without relying on cancellable
join chains. Final preferences, mirror state and Stop effects are checked after blocked reads return.











