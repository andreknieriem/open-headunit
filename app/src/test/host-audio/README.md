# Audio owner regression checks

From the repository root, after the Gradle unit tests have populated the Kotlin 1.9.22 cache:

```sh
python3 app/src/test/host-audio/run.py
```

The runner compiles current production files listed in `sources.txt`, not copied implementations.
Android doubles provide controllable codec callbacks, delayed input slots and output failures.
The cases exercise registration ownership, initial open backoff, callback delivery, old API dispatch,
Stop during input backpressure, closed AAC tails, final-write/replay drain, terminal parking, focus
gating, codec retirement races, retirement before initial registration, and denied scheduler priority. Actual AapAudio posting is exercised on API 16 and 33, including
stale sleep releases, blocked protocol Binder calls and teardown of queued/in-flight acquisitions.
Session cases cover delayed Setup before/after reconnection and retired-session commands.
Queued and in-flight protocol focus are also checked during session activation.
The focus double keys system clients by listener, matching Android rather than request-object
identity. Repeated transient requests, a shared caller callback across sessions, old-client
abandonment and throwing once-only session closure are covered on the relevant boundaries.
It models the default AOSP permanent-GAIN eviction rule, with a transient control, retained
PCM handoff, NEVER mode, drained demand and session replacement during handoff. SDK 16/33
select the production API branches; the double does not implement every vendor focus policy.
Clock hooks preempt handoff before request publication to check that owner retirement's null
resource capture cannot strand the later request and that a fresh Start still acquires focus.
They also pause old AAC output-copy progress until a CSD/watchdog replacement accepts input,
then check that retired progress cannot change its watchdog, copy timestamp or PTS. Recovery
must still run without more DATA or Stop, and the old PCM must not reach the replacement's bank.
Static focus is held by the transport's AapAudio only; failed handshakes, static-to-NEVER
reconnects and retired requests are checked on API 16/19/25/33. The runner also extracts the
service connection observer and CommManager's transport-publication, settings-apply and
both disconnect entrypoints into a lifecycle fixture. Latches stop construction after the old
config is captured, and a queued cleanup dispatcher delivers EOF before teardown. These check
that saving is not lost, the first disconnect owns app-exit policy, retired callbacks leave a
replacement alone, and first-handshake connection resources are cleaned up. Socket I/O,
service side effects and the rest of the Android lifecycle are doubled.
Settings cases execute the connection manager's apply decision with a doubled disconnect,
then replace the real audio session in the same process. Focus/routing/PCM/AAC changes must
request renegotiation without closing the app, and the replacement must produce nonzero PCM.
Local output changes retain their session and current gain. This does not simulate automatic
reconnection or settings UI navigation.
The transport lifecycle runner additionally extracts the complete manager handshake/read,
connect and disconnect methods and the transport startup/quit/termination methods. Real JVM
threads and latch-controlled I/O doubles reproduce cancellation before startup, cancellation
inside the handshake, delayed duplicate starts and both orders of EOF/settings teardown.
Real worker queues also run bulk dispatch that ends its own session. Reconnect must wait for
both the old quit body and the remaining Poll call stack; the old bulk tail must not reach TLS.
Physical candidate construction is paused across cancellation, with and without a replacement
attempt. The retired candidate must close without changing the fresh connection. Failure,
partial startup and interrupted-wait controls preserve cleanup and error reporting. Service
destruction cancels both registered and unpublished candidates while allowing a fresh attempt.
The runner also compiles the complete production StandardUsbProjectionConnection and its base
classes with USB API doubles, checking explicit handle closure during and after openDevice.
Retired
transports cannot recreate workers or readers. These checks run with `run.py` and can also be
run alone with `python3 app/src/test/host-audio/transport-lifecycle.py`.
Framing cases check ADTS codec configuration, repeated Setup and raw/ADTS transitions.
The codec doubles do not decode ADTS bitstreams. They complement JVM policy/PCM tests and native CTest. They do not emulate HAL playback clocks,
Binder scheduling, real codec output timing or acoustic latency; those require device validation.

Artifacts go under the ignored `build/host-audio` directory. The script uses the existing Gradle
compiler cache and does not download or execute a separately supplied compiler.
