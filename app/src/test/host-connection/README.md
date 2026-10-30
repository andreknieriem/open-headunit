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
The extracted automatic Home producer uses current Run ownership rather than minting a new
Open. Its controls cover enabled/disabled startup flags and config continuation after completed
Stop; genuine owned Open can still restore the service. Real Android instrumentation separately
exercises task return, config recreation with automatic startup enabled, and delayed finish delivery.

Native group coordinator tests retain cleanup responsibility beyond service destruction and
hold new adoption/create work behind submitted removal callbacks. Actual active adoption clears
only detached cleanup authority, leaving active transport shutdown on the normal service path.
A late abandoned create records its framework removal boundary before submission, so failure
preserves responsibility for the next fresh Stop. An absent callback leaves the barrier closed
rather than asserting completion. These are recording framework boundaries, not proof of
manufacturer radio behavior. Activity callback ordering and actual task/process effects still
require Android coverage.

The JVM settings-import regressions use the actual backup parser, preferences and device-protected
mirrors with a blocking Reader. Read/parse may outlive cancellation but has no write authority;
current-operation validation, apply, mirror synchronization and completion run in one Main turn.
Save/reset and view destruction invalidate older UI operations without relying on cancellable
join chains. Final preferences, mirror state and Stop effects are checked after blocked reads return.
