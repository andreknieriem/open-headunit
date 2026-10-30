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
the Kotlin dependency cache, then `python3 app/src/test/host-connection/run.py`.
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
is still the live one, never because another session is connected. The ingress fixtures pause
real producers inside each former gap (claim before record, Stop or Save or another claim taking
a waiting request's authority, a discovery hand-off queued behind IO, a cancelled request that
published nothing while another session is connected).

The ticket belongs to the logical input, not to one public call. UI, automation, server accept,
discovery scan, Nearby request, Self launch, VPN consent and service relays capture it when the
input is accepted and carry it through queued work, permission waits, countdowns and internal
retries. The caller fixtures extract those producers and run them against the real manager,
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
