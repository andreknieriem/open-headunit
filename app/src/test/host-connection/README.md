# Connection lifecycle regressions

Run `python3 app/src/test/host-connection/run.py` after populating Gradle's Kotlin dependencies.
An offline cache is supported with `GRADLE_USER_HOME=/path/to/gradle-cache`; every
sub-harness uses the compiler/runtime jars recorded in `build/host-connection/toolchain.json`.
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


## Effect entry authority

The authority belongs to the accepted input. `ConnectionOperation(ticket, save)` carries the
original Stop epoch and, when applicable, the exact settings Save until admission. Its arguments
are required. Wireless arming, discovery generations, native children, service relays, explicit
Stop issuance and connection end effects must not read a newer epoch when their queued work runs.
Native children retain the immutable operation supplied to their constructor. A genuine external
socket accept captures its receipt ticket once; a delayed handoff preserves that receipt.
Refreshing an already armed Native route carries a separate, required command operation through
credential reads, radio callbacks, listener reopening and queued module wakes. That command keeps
its original Save; it does not replace the arm's lifetime authority or borrow a later command.

A published `Attempt` contains its original `ConnectionRequest`. Service and projection observers
capture `TransportStart` before launching handshake/read work. The first effect validates the
captured attempt, physical connection, request, ticket and USB recheck/recovery owner. Completion
may settle only that same live transport. An entered handshake's failure still retires its own
connection when a competing request took the claim but never published a replacement. Stop and
replacement continue to reject stale work. A USB outcome carries its original recheck into recovery.

Shared cleanup uses exact objects. USB switch guards stake, release and record a switch by claim
identity; duplicate old cleanup cannot change a later guard. Each USB driver's open reserves a
local owner, checks admission before open/interface/native effects, and publishes only while that
owner is current. SDK calls and retirement waits run outside lifecycle/state monitors. Disconnect
joins an already-entered open's local cleanup before the next connection can open; a revoked late
handle is closed without claiming its interface. The locked attach path uses its existing bare
claim and does not initialize `App`, `CommManager` or credential-protected settings.
An old arbiter preempt cancels only the USB job whose request owns the losing claim. Wireless
stops require the existing cleanup owner captured before a hop and compare it atomically with
detaching the resources. Shared start reservations and native group owners prevent rejected or
late candidates from closing a successor's server or clearing its radio metadata.

Each WiFi wait owns its callback, timer and operation. Completion validates identity and authority
atomically before changing the active wait, and disposes only the captured wait. Settings import
validates connection authority before committing its prepared preferences; a commit that already
happened still updates its view, while later wireless start/stop and Stop effects retain the original
ticket. A forced Self decision is consumed directly once. It leaves no static Home handoff, and
VPN consent retains the exact pending decision so cancellation or an older result cannot replay it.

Authoritative shared-state decisions run under the lifecycle lock, then producer admission and
arbiter locks in that order. Checks are effect admissions, not a promise to retract a blocking
platform call that has already entered. SDK I/O and captured-resource cleanup run outside those
monitors. A late completion may close its own resource but cannot clear a successor's owner.

The full runner includes these behavioural suites:

- `service-ui-effects.py`: all three picker dismiss paths, import completion, wireless command
  and Stop effects, WiFi callback/timeout ownership, service bootstrap and one-shot Self/VPN consent.
- `wireless-authority.py`: Native's hotspot-teardown wait and each TCP probe (5277 and 5289),
  including cancellation before the first probe, between ports and before callback delivery.
- `wireless-entries.py`: actual Native listener entries, WPP bind, shared resource publication
  and cleanup ownership.
- `native-commands.py`: retained Native listener/reopen and SoftAP refresh commands keep Save,
  and a queued HFP worker checks its original arm before the initial protocol write.
- `direct-command.py`: Direct refresh/create/read/watchdog continuations keep the original Save
  command, and an old group's cleanup leaves its successor's metadata intact.
- `usb-effects.py`: queued handshake/read work, replacement requests, USB switch guard cleanup,
  StandardUsb permission/open boundaries and actual AOA switch/reset effect entries. Its
  `--libusb` mode compiles the full Libusb driver against JNI doubles. Both drivers exercise
  cancellation while the late local handle is still closing, before another open may begin.
- `usb-preemption.py`: a delayed arbiter preempt cannot cancel a replacement request's job.
- `native-carrier-effects.py`: full ZBT, BLINK and ZJ carrier/opening implementations, including
  permission/process/socket boundaries, reopen and the original Save on a queued wake.
- `EffectEntryFixture.kt`: stale end effects, same-epoch Save cancellation, exact session/claim
  cleanup and physical Stop ownership, exercised against the extracted manager.

The focused scripts accept a source-tree path. After running the existing host runner for
both trees, the same focused script can test each tree; test-only baseline adapters supply changed
parameter plumbing without adding the missing production guards. They record full compile/run
commands, extracted-source hashes and machine-readable scenario results. A baseline scenario must
fail from an observed unwanted effect, not from a compilation error. Normal and successor-owner
controls stay enabled. Android/JVM Gradle, APK, native firmware and actual task/permission behaviour
still require the SDK/NDK/device environment; these host checks do not substitute for those runs.
