# Wi-Fi Direct + Android Auto Dev Server (Root)

## Status and Baselines

Implementation branch: `codex/wifi-direct-dev-root`.

- Wireless Helper baseline: `8ac36c9bcc80731b78949c04fdc63178b5904f2f`.
- Open Headunit baseline: `80a81099e678119a57d642cbe596c8a5b03bf29d`.
- The original checkouts and their custom commits are not replaced.
- No commits, merges or pushes are part of this change.
- Android builds, Gradle tests/lint and hardware acceptance have NOT been run on the implementation machine. There is no Android build environment; build and device verification are required before relying on this mode.

## Setup

1. Configure Android Auto on the phone, enable its developer settings and verify that its headunit server can be started manually.
2. Install both matching builds. Root is required only on the phone. Initially unlock the phone, grant helper permissions and grant persistent root access to the actual helper package you installed (debug and release are different packages).
3. On the head unit, select Wi-Fi, then **Headunit Server / Auto**, enable **Automatically create Wi-Fi Direct** and save. The setting defaults to off; Manual remains unchanged.
4. On the phone, select **Wi-Fi Direct / Dev server (root)**. Root and the expected Android Auto component are checked before saving the selection.
5. Select Bluetooth auto-start and the intended car/head-unit Bluetooth devices. Alternatively use Start, the tile, widget, or `wirelesshelper://start?mode=wifi-direct-dev-root`.
6. Start the head-unit app. It disables conflicting hotspot operation before creating the OpenHU P2P group. Do not also enable an external hotspot manager for this radio.
7. The phone starts the Dev server and searches for OpenHU. The head unit waits for a P2P client and a real interface address, scans that subnet for TCP 5277, and hands the already-connected socket to CommManager.

User-entered P2P target names are retained. Only the old default set containing exactly HURev is extended with OpenHU.

### Head-unit Waiting Screen

Auto + Wi-Fi Direct opens on Home with the bottom status pill. It does not dial the saved Wi-Fi
address from a previous session: only a current P2P client and interface can start server discovery.
Saved USB auto-connect and other wireless modes keep their existing behavior.

The top badge uses the local Wi-Fi Direct device name, not the group's `DIRECT-xx-*` SSID.
Before creating a group the app requests the name OpenHU and waits for confirmation, refusal,
or a two-second timeout. A refused rename does not prevent creation and does not claim success.
A confirmed group with an unknown device name shows "Wi-Fi Direct active". Temporary missing
snapshots do not flicker the badge; two negative snapshots at least two seconds apart hide it.
Stop or Wi-Fi off hides it immediately. Scanner input is cleared immediately on network loss,
independently of the display grace period.

For a manually opened Auto/P2P connection overlay, **In background** and Back return to Home
without stopping the attempt. That attempt does not reopen the overlay; successful AA projection
still opens normally. The pill's **X** and **Exit** remain full stops. Press **Wi-Fi** to re-arm after X;
merely resuming the activity does not re-arm it. Unexpected Auto/P2P transport failures keep
recovery armed, including when "close app on disconnect" is enabled; explicit exits still stop.

On head units running Android 10+, an ordinary non-root app may not be allowed to enable Wi-Fi. Enable Wi-Fi through the system UI in that case. The phone can request Wi-Fi through root. OEM P2P permission/location requirements still apply.

## Ownership and Recovery

This mode owns the developer server while running, including a server previously enabled manually. Do not run a second helper instance or another application managing the same server. It never force-stops all of Android Auto.

- Repeated Start or Bluetooth connect events do not restart an already requested root session.
- Peer discovery is retried every 10 seconds. An unanswered invitation is cancelled after 20 seconds.
- The last selected Bluetooth disconnect starts a 5-second grace period. A selected device returning cancels it. After the grace period the server and helper's P2P session stop.
- P2P loss has a 5-second grace period; afterwards the server stops while discovery continues. Joining again starts the server.
- Dev server state is sampled once per second between commands. A TCP close while P2P remains triggers restart after 2 seconds.
- P2P without TCP triggers recovery after 30 seconds, backing off to 60 seconds. Established TCP is never interrupted by the idle timer.
- Root shell commands are limited to 10 seconds, off the UI thread. Readiness/shutdown polling is also bounded.
- Stop in helper cancels monitoring/retries, confirms Dev-server shutdown, releases its P2P session, then stops the foreground service.
- Head-unit Stop/Exit closes AA before releasing P2P and holds automatic wireless bring-up down until an explicit user start.
- Persisted requested/managed flags support service recreation. An interrupted cleanup is retried when the app is next opened or started. Force Stop or power loss cannot execute cleanup at that instant; there is no separate root daemon.

The helper reports discovery, P2P, Dev-server readiness and TCP separately. **TCP is not proof of a working AA projection**; the head unit confirms the actual session.

## Developer Server Commands

Confirmed user startup command on Android 8+:

```sh
su -c 'am start-foreground-service -W com.google.android.projection.gearhead/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService'
su -c 'am startservice -a shutdown -n com.google.android.projection.gearhead/com.google.android.projection.gearhead.companion.DeveloperHeadUnitNetworkService'
```

Below Android 8 the start command uses `am startservice`.

Readiness is NOT inferred from `-W` or command exit status. The controller reads `dumpsys activity services` and `/proc/net/tcp` / `tcp6` through root, filters by the installed Android Auto UID and local port 5277, and distinguishes LISTEN from ESTABLISHED. Loopback-only listeners are not ready for the head unit. **Do not use nc/telnet or a test TCP connection to check readiness**: an accepted connection not handed to the AA transport can strand the developer server.

Shutdown uses the notification Stop action observed on the test phone: a `startService` PendingIntent with `action=shutdown` targeting the same component. Do not substitute `am stopservice`: on that phone it removed the service and notification but left AA's TCP 5277 listener alive, preventing subsequent startup. The shell command requests shutdown; passive service and socket state still confirm completion. The equivalent root shell command must also be verified on each supported AA version.

This is an internal Android Auto component, not a supported public API. Component renames, root restrictions, SELinux restrictions on /proc or changes in the dumpsys format must produce an explicit error rather than a claimed connection.

## Build and JVM Tests

Run in the corresponding checkout on a machine with that project's Android/JDK/Gradle prerequisites:

```sh
# Wireless Helper
./gradlew testDebugUnitTest assembleDebug lintDebug

# Open Headunit
./gradlew testGithubDebugUnitTest assembleGithubDebug lintGithubDebug
```

New helper tests cover command selection, nonzero successful stop, passive readiness, command timeout/cancellation, serialized operations, repeat Start, IPv4/IPv6 and UID filtering, idle/TCP/P2P deadlines, Bluetooth bounce and multiple selected devices. New head-unit tests cover actual P2P interface/subnet selection, no station or fixed-IP fallback, source-address binding, socket reuse, checkbox configuration/rearm and the Exit cancellation policy.

These tests do not replace framework/device tests of broadcast delivery, foreground-service restrictions, hotspot teardown, P2P negotiation or the actual Android Auto service.

## Device Acceptance Checklist

First verify the complete server start/stop cycle, including passive absence of its listener after Stop. Then record the following on the actual phone/head-unit pair:

| Scenario | Expected result |
| --- | --- |
| Head unit first, phone/BT second | OpenHU appears; one TCP dial becomes an AA session |
| Phone/BT first, head unit second | Helper waits and connects without Stop/Start |
| Phone Wi-Fi off at startup | Root requests Wi-Fi; connection proceeds or a clear Wi-Fi error appears |
| Head Wi-Fi off | Enable where the platform allows; otherwise system UI is required |
| Phone locked after initial grants | BT starts helper without another root/permission prompt |
| Ordinary Wi-Fi connected in parallel | Scan uses P2P local IP and prefix, not the station gateway |
| Server already enabled manually | Adopt it; helper Stop also stops it |
| Duplicate Start, tile/BT events | Healthy TCP/session is not restarted |
| One of two selected BT devices leaves | Keep session while the other remains connected |
| Last BT leaves for less than 5 seconds | Do not stop if it returns |
| Last BT stays away | Stop server and release P2P after grace |
| P2P interrupted | Stop server after grace; rediscover and resume |
| TCP interrupted with P2P retained | Restart server and reconnect automatically |
| P2P group disappears on head | Recreate group when the radio is available; client join resumes discovery |
| Head Stop/Exit during scan or connection | No late callback/scan may reconnect until user starts again |
| Change checkbox and save; export/import settings | Running configuration changes; flag is preserved |
| Kill helper process (not Force Stop) | Requested service can recover without overlapping sessions |
| Force Stop helper, then open it again | Stale managed server is cleaned up before another session |

Acceptance target: **10 full connect/disconnect cycles without manual Stop/Start recovery**, successful recovery from random P2P/TCP loss, no remaining Dev server after helper Stop, and no timer-driven resets of a healthy projection. This target is not yet hardware-verified.

Manual non-root Android Auto menu automation is intentionally out of scope for v1.
