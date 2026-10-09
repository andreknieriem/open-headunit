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
Static focus is checked through its AapAudio session owner on API 16/19/25/33.
Settings cases retain the negotiated routing and codec snapshot until the session is replaced,
then require nonzero PCM from the new owner. Local output changes preserve the negotiated codec.
Mixed-codec cases in static and dynamic focus keep AAC music alive while 16 kHz PCM guidance/system tails drain,
then check repeated Setup, local restart and a per-sink codec override from the phone.
Connection publication and worker-retirement checks live in the independent host-connection suite.
Framing cases check ADTS codec configuration, repeated Setup and raw/ADTS transitions.
The codec doubles do not decode ADTS bitstreams. They complement JVM policy/PCM tests and native CTest. They do not emulate HAL playback clocks,
Binder scheduling, real codec output timing or acoustic latency; those require device validation.

Artifacts go under the ignored `build/host-audio` directory. The script uses the existing Gradle
compiler cache and does not download or execute a separately supplied compiler.
