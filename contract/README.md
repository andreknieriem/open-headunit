# Automating Open Headunit

Open Headunit can be driven from Tasker, MacroDroid, a launcher shortcut or `adb`, and it reports
what the projection session is doing so a macro can react to it.

## The one thing that trips everybody up

The **package** and the **action prefix** are different, on purpose:

| | |
|---|---|
| package (applicationId) | `com.andrerinas.headunitrevived` |
| action prefix (namespace) | `com.andrerinas.openheadunit` |

The app kept its original Play Store listing when it was renamed, so the two never matched. Using
the wrong one silently does nothing — there is no error. Copy the lines below rather than typing
them.

## Sending a command

Everything goes to one receiver:

```
com.andrerinas.headunitrevived/com.andrerinas.openheadunit.automation.AutomationReceiver
```

**Tasker**: Action → Misc → Send Intent. Set *Target* to **Broadcast Receiver**, *Action* to the
action you want, *Package* to `com.andrerinas.headunitrevived`, *Class* to the receiver above.
Targeting a broadcast receiver is what lets this work without granting Tasker "Display over other
apps" — an activity target needs it on Android 10 and up.

**MacroDroid**: Action → Send Intent, target Broadcast, same package and class. It allows six
extras with explicit types; Tasker allows two.

**adb**:

```bash
PKG=com.andrerinas.headunitrevived
RX=$PKG/com.andrerinas.openheadunit.automation.AutomationReceiver

adb shell am broadcast -n $RX -a com.andrerinas.openheadunit.ACTION_QUERY_STATE
```

`am` sends ordered, so the reply comes back as JSON on the `data=` field. Tasker's Send Intent is
not ordered and cannot read a reply — a Tasker task watches the session broadcast below instead.

## Control

Open to any caller, like the steering-wheel key receivers already are.

| Action (prefix `com.andrerinas.openheadunit.`) | Extras | Does |
|---|---|---|
| `ACTION_CONNECT` | `ip` (optional), `no_ui` | With `ip`, opens a session to Android Auto's head unit server on 5277. Without, checks USB. |
| `ACTION_DISCONNECT` | | Ends the session. |
| `ACTION_START_SELF_MODE` | `no_ui` | Projects this device onto itself. |
| `ACTION_STOP_SERVICE` | | Ends the session and stops the service. `ACTION_EXIT` is an accepted alias. |
| `ACTION_SET_NIGHT_MODE` | `state` = `day`/`night`/`auto` | Day/night theme. |
| `ACTION_START_WIRELESS` | | Arms the stored wireless mode. |
| `ACTION_STOP_WIRELESS` | | Stops it. |
| `ACTION_START_WIRELESS_SCAN` | | One discovery pass. |
| `ACTION_NATIVE_AA_POKE` | `extra_mac` | Wakes a phone over Bluetooth. Native AA mode only. |
| `ACTION_NATIVE_AA_CANCEL_POKE` | | Cancels a wake in progress, as the driver selector's Cancel does. Native AA mode only, and only on a build carrying driver selection. |
| `ACTION_NEARBY_CONNECT` | `extra_endpoint_id` | Connects a Google Nearby endpoint. |
| `ACTION_CHECK_USB` | | Re-checks the USB port. |
| `ACTION_REFRESH_SENSORS` | | Re-reads sensors. Also answers to `aap.action.REFRESH_SENSORS`. |
| `ACTION_RESTART_AUDIO` | | Restarts the audio pipeline. Also answers to `aap.action.RESTART_AUDIO`. |
| `ACTION_RAISE_PROJECTION` | | Brings the projection to the front. Also answers to `aap.action.RAISE_PROJECTION`. |
| `ACTION_QUERY_STATE` | | Replies with build and session state. |
| `ACTION_LOG_MARKER` | `text` | Writes `AutomationMarker: <text>` into the log at WARN, so two runs can share one capture. |

`no_ui` asks for the session without taking the screen, for a macro that connects in the background.
It applies to the next raise only, so an ordinary reconnect still comes to the front.

### Exit ordering

Stop/Exit is accepted once at its first app ingress. Internal UI commands and automation relays
carry that same decision to the service; delayed delivery does not make an older accepted Stop
new again. A new Stop selected after reopening is still honored while an earlier service teardown
finishes. It reuses that teardown without reviving the older request's finish or process-kill flags.
Connection work started before a Stop (a queued connect, retry, permission wait or delayed
automatic connection) cannot resume after it, even if the app is reopened in between. Starting a
connection again after the Stop is a new request and is accepted.

Notification Exit is accepted when its action reaches the app, not when the notification was
created. The existing public commands carry no sender-order token, so the app cannot distinguish
a newly sent Stop from one delayed before its first receipt. If such a Stop first arrives after
a reopen, it is processed after that reopen even if an external sender originally sent it earlier.
The contract is first app acceptance order, not an inferred external click/send timestamp.

## Configuration

**Off by default.** Turn on *Allow external configuration* in Settings first, next to the log
options; without it these are refused and the reply says so. Connecting and disconnecting are
unaffected.

| Action (prefix `com.andrerinas.openheadunit.`) | Extras | Does |
|---|---|---|
| `ACTION_SET_SETTINGS` | `json` or `path` | Applies a settings backup. Same format the in-app export writes. |
| `ACTION_GET_SETTINGS` | `path` (optional) | Replies with the settings, or writes them to `path`. Credential-bearing keys are withheld; see below. |
| `ACTION_RESET_SETTINGS` | | Back to defaults. |
| `ACTION_SET_LOG_LEVEL` | `level` = `verbose`/`debug`/`info`/`warn`/`error`/`silent` | |
| `ACTION_START_LOG_CAPTURE` | | |
| `ACTION_STOP_LOG_CAPTURE` | | |
| `ACTION_EXPORT_LOG` | `path` (optional) | Writes the retained log out. |

### What a configuration command will not do

Two limits apply even with the switch on, because it is one switch that stays on after the round
that needed it, and any app on the device can send these:

- **An export withholds credentials.** `hotspot-password`, `hotspot-ssid`, `auto-start-wifi-ssid`,
  `auto-start-bt-macs`, `auto-start-bt-name` and `static-bssid` are removed, and the reply carries
  `withheld` with the count so a partial export is not mistaken for unset values. Reading those
  needs a shell and `settings.xml`.
- **`path` must be somewhere collectable.** Writes are allowed into the app's external files
  directory and the public Downloads directory only. The write runs with the app's own privileges,
  so an unconstrained path would let a caller put chosen bytes into the app's private storage.
  Anything else is refused with a reason. `/sdcard`, `/mnt/sdcard` and `/storage/self/primary` are
  accepted as the usual spellings of primary external storage.

## Reacting to the session

The app broadcasts `com.andrerinas.headunitrevived.SESSION_STATE` whenever the session changes.
It is implicit and needs no permission, so **Tasker's *Intent Received* event works directly** —
this is the answer to "how do I tell whether Android Auto is actually running on the head unit",
which nothing else on the device reports (`%UIMODE` does not change for a head unit).

| Extra | Values |
|---|---|
| `state` | `connecting`, `connected`, `projecting`, `disconnected`, `failed` |
| `transport` | `usb`, `wifi`, `self`, `unknown` |
| `reason` | `user_exit`, `link_lost`, `phone_left`, `handshake_failed`, `peer_silent`, `connect_failed`, or empty |
| `uptime_ms` | How long the session had been up, or 0 |

`projecting` is the one that means "Android Auto is on screen and carrying video". Treat `reason`
as an open string: new values get added, and an unrecognised one means "some other reason", not an
error.

Because any app on the device can read this, it deliberately carries no network credentials and
does not name the phone.

Watch it from a shell with:

```bash
adb shell am broadcast -a com.andrerinas.headunitrevived.SESSION_STATE --receiver-foreground
```

or just read the log — every event also prints as `AapService: session state <state>`.

## Deep links and shortcuts

Still supported and unchanged:

```bash
adb shell am start -a android.intent.action.VIEW -d "headunit://connect?ip=192.168.1.25"
adb shell am start -a android.intent.action.VIEW -d "headunit://exit"
```

`headunit://connect`, `disconnect`, `exit`, `nightmode?state=day|night|auto`, and
`headunit://selfmode`. The app also publishes seven launcher shortcuts, which is what Samsung
Modes and Routines picks up.

Deep links and shortcuts go through an activity, so an automation app sending them needs "Display
over other apps". The receiver above does not — prefer it.
