OpenHeadunit - Implemented Improvements & Features Summary
===========================================================

1. Locked Phone Wireless Connection & Serverless Bluetooth HID (Keyboard + Mouse) Wake Trigger
----------------------------------------------------------------------------------------------
- Serverless Bluetooth HID Combo (Keyboard + Mouse) Wake Trigger (`BtHidMouseTrigger.kt`, `NativeAaHandshakeManager.kt`, `WirelessRearmPolicy.kt`, `WifiLauncherNative.kt`):
  * Registers the head unit / receiver phone as a composite Bluetooth HID Device
    (`BluetoothHidDevice.SUBCLASS1_COMBO`, Report ID 1 = 3-Button Mouse, Report ID 2 = Standard
    Boot Keyboard) on Android 9+ (`API 28+`) and keeps the SDP record ("OpenHeadunit HID Trigger")
    registered even while Settings or Android Bluetooth Settings is open for pairing.
  * Immediately connects (`hid.connect(device)`) to `nativePreferredDeviceMac`, `nativePokeBtMacs`,
    `pluggedDevice`, and paired candidate phones as soon as `onAppStatusChanged(..., registered = true)`
    fires, and runs a 6-second background auto-connect loop while waiting for a phone.
  * Upon `STATE_CONNECTED`, sends staggered Mouse (`+2, -2` X nudge) and Keyboard (`Left Shift`
    modifier press/release) wake reports at `0ms`, `300ms`, and `700ms` to wake the locked phone
    screen and establish an encrypted Bluetooth ACL link, then immediately triggers an HFP/HSP
    Service Level Connection poke (`onHidDeviceConnected`) and opens Android's `AA_UUID` + `HFP_UUID`
    RFCOMM listeners (even on FYT/ZLink external-BT head units).
  * Automatically disconnects active HID links (`releaseActiveConnections()`) once the Android Auto
    projection session goes live so the phone's on-screen keyboard is never suppressed while driving.
  * Exposed "Pair with Serverless Bluetooth Keyboard/Mouse" (`bt-hid-wake-trigger`, enabled by
    default), "Preferred device" (`native-preferred-device`), and "Devices to wake" (`native-poke-bt`)
    directly in the Basic Settings tab under "Wireless Connection -> Bluetooth" (with "Select the
    Bluetooth device to wake" listing only actual paired Bluetooth devices), keeping the HID profile
    retrying every 3 seconds until the selected device connects and maintaining the Bluetooth
    connection without auto-disconnecting.
- Auto-Background When Idle & Instant Foreground Pop on USB / Bluetooth Connect (`MainActivity.kt`, `AapService.kt`, `AutoStartReceiver.kt`, `UsbAttachedActivity.kt`, `BluetoothHelper.kt`, `UsbDeviceCompat.kt`, `Settings.kt`):
  * First-Time Setup Protection: If the onboarding wizard is pending OR no target Bluetooth device
    has been selected yet (`configuredBluetoothTargetMacs.isEmpty()`), `MainActivity` stays in the
    foreground so you can complete first-time Bluetooth pairing and device selection.
  * Auto-Background When Disconnected: Once a target Bluetooth device is configured, launching the
    app when neither a connectable USB device (phone/dongle) nor the selected Bluetooth device is
    connected automatically starts `AapService` (and the 3-second Serverless HID auto-connect loop)
    and moves the app to the background (`moveTaskToBack(true)` — immediately on auto-start, or
    after a 3.5-second grace window on manual tap that cancels if you touch the screen to open Settings).
  * Always-Accessible Settings: Added a "Settings" action button directly to the persistent
    `AapService` notification, and tapping the notification body opens `MainActivity` with
    `EXTRA_KEEP_IN_FOREGROUND = true`.
  * Instant Active Foreground over Other Apps: Enabled `autoStartOnUsb` and
    `autoConnectSingleUsbDevice` by default, unified `configuredBluetoothTargetMacs`
    (`autoStartBluetoothDeviceMacs` + `nativePokeBtMacs` + `nativePreferredDeviceMac`), and wired
    `UsbAttachedActivity`, `AutoStartReceiver`, `AapService.btAutoDisconnectReceiver`, and
    `BtHidMouseTrigger` (`STATE_CONNECTED`) into `AapService.bringAppToFrontForConnection()`
    (Direct -> Overlay Trampoline -> Full-Screen Intent) so the app immediately comes to the
    active foreground over other apps the moment a USB phone/dongle or selected Bluetooth device connects.
- Locked Phone USB Detection & AOA Switch Fixes (`UsbDeviceIdentityPolicy.kt`, `usb_device_filter.xml`, `UsbManifestFilter.kt`, `UsbAttachedActivity.kt`, `UsbLauncherListener.kt`, `UsbPermissionAsks.kt`, `UsbAccessoryMode.kt`, `UsbLauncherManager.kt`, `MainViewModel.kt`):
  * Locked Samsung (`0x04E8`) & Charge-Only USB Identity Detection: Added `KNOWN_ANDROID_PHONE_VIDS`
    in `UsbDeviceIdentityPolicy.kt` without restricting `deviceClass == 0x00`, and exempted known
    Android phone VIDs from `deviceClass == 0xFF` and `CDC_NETWORK_SUBCLASSES` rejections so locked
    Samsung Galaxy phones (`04E8:6860` / `04E8:685D`, which report `bDeviceClass = 0x02` `USB_CLASS_COMM`
    or `0xEF` IAD Misc and expose only CDC-ACM `02/02` or secondary `02/0D` NCM configurations when
    MTP `06/01/01` is blocked by One UI's lock screen) are always accepted as connectable phones.
  * Manifest Filter Auto-Grant for Locked Samsung & Android VIDs: Added `<usb-device vendor-id="1256" />`
    (`0x04E8` Samsung) and major Android phone Vendor IDs to `res/xml/usb_device_filter.xml` and
    `UsbManifestFilter.ENTRIES` so Android OS matches locked Samsung/Android phones on plug-in,
    launches `UsbAttachedActivity`, and auto-grants USB permission even when MTP (`6/1/1`) is hidden.
  * Permission Check & Multi-Retry AOA Switch with Null-Terminated Strings: Fixed `UsbAttachedActivity`
    to verify `usbManager.hasPermission(device)` for normal/locked devices and hand off to `AapService`
    if permission is needed or if all 3 switch attempts fail; updated `UsbAccessoryMode` to claim
    Interface 0 before control transfers, send null-terminated UTF-8 AOA identification strings
    (`string + "\u0000"`), retry `ACC_REQ_GET_PROTOCOL` (51) 3 times, and fall back to `libusb`.
  * Continuous 2.5s USB Bus Polling (`UsbLauncherManager.startUsbBusPolling`): Polls `usbManager.deviceList`
    every 2.5 seconds while disconnected so locked Samsung phones that enumerate without firing
    `ACTION_USB_DEVICE_ATTACHED` or activate USB data lines after lock-screen charging wake are
    automatically detected, brought to the foreground, and switched to AOA mode.

- Locked Phone Android Auto Projection Handshake Fixes (`AapControl.kt`, `ServiceDiscoveryResponse.kt`):
  * Responds to `VIDEO_FOCUS_PROJECTED` requests with `VideoFocusEvent(true, unsolicited = false)`
    so the phone starts H.264/H.265 video streaming immediately even when the phone lock screen is active.
  * Ignores `VIDEO_FOCUS_NATIVE` with `reason == PHONE_SCREEN_OFF` and re-asserts projected video
    focus (`VideoFocusEvent(true, unsolicited = true)`) instead of disconnecting when the phone
    screen turns off or stays locked.
  * Handles `BluetoothPairingRequest` (0x8001) on `Channel.ID_BTH` (Channel 8) by replying with
    `BluetoothPairingResponse` (0x8002, `STATUS_SUCCESS`, `already_paired = true`) so the phone's
    wireless projection state machine never stalls waiting for Bluetooth pairing confirmation.


2. Dynamic Parked Video (YouTube) <-> Moving Audio-Only (GPS Speed) Mode
------------------------------------------------------------------------
- AAP Vehicle Sensors (`DrivingStatusEvent.kt`, `ServiceDiscoveryResponse.kt`, `AapControl.kt`):
  * Announces `DRIVING_STATUS`, `PARKING_BRAKE`, and `CAR_SPEED` sensors during Android Auto
    Service Discovery.
  * Sends initial parked state (`DrivingStatus = UNRESTRICTED`, `ParkingBrake = true`,
    `CarSpeed = 0`) as soon as sensor streaming starts.

- Real-Time GPS Speed Switching (`AapBroadcastReceiver.kt`):
  * Evaluates GPS speed on every `LocationUpdateIntent` against a 1.5 m/s (~5.4 km/h / 3.4 mph)
    moving threshold (`MOVING_SPEED_THRESHOLD_MPS`).
  * When Parked (`speed < 1.5 m/s`): Sends `ParkingBrakeEvent(true)`, `CarSpeedEvent(0)`, and
    `DrivingStatusEvent(UNRESTRICTED)` so YouTube and Android Auto video apps can play video on
    the head unit display.
  * When Moving (`speed >= 1.5 m/s`): Sends `CarSpeedEvent(speedE3)`, `ParkingBrakeEvent(false)`,
    and `DrivingStatusEvent(NO_VIDEO)` so Android Auto automatically switches video apps to
    audio-only / music mode while driving and restores video when parked again.

- Settings Toggle (`SettingsFragment.kt`, `Settings.kt`, `SettingsPendingCopy.kt`, `SettingsBackupManager.kt`):
  * Added "Parked Video / Moving Audio-Only (GPS Speed)" (`auto-audio-only-when-moving`, enabled
    by default) under Video Settings.
  * Turning this toggle OFF keeps `DRIVING_STATUS = UNRESTRICTED` and `PARKING_BRAKE = true` at
    all times (even while driving).


3. Phone Call Audio & Microphone Routing Toggle
-----------------------------------------------
- Settings Toggle (`SettingsFragment.kt`, `MicSettingsFragment.kt`, `Settings.kt`):
  * Added "Route Phone Call Audio & Mic to Head Unit" (`route-call-audio-and-mic-to-headunit`,
    enabled by default) in both Audio Settings and Microphone Settings.

- Call Audio & Hardware Echo/Noise Processing (`MicRecorder.kt`, `ServiceDiscoveryResponse.kt`, `AapService.kt`):
  * Forces Head Unit Microphone capture ON when call & mic routing is enabled.
  * Uses `MediaRecorder.AudioSource.VOICE_COMMUNICATION` (source 3) with hardware
    `AcousticEchoCanceler` (AEC), `NoiseSuppressor` (NS), and `AutomaticGainControl` (AGC)
    automatically attached to the `AudioRecord` session.
  * Announces the head unit's Bluetooth MAC address (or fallback MAC) in `ServiceDiscoveryResponse`
    when enabled, and suppresses Bluetooth/HFP announcement when disabled so calls remain on the
    phone speaker/earpiece or car OEM Bluetooth.
  * Releases any silent stand-in HFP client sockets (`activeHfpClientSockets`) as soon as the
    Android Auto projection session goes live so call audio isn't swallowed by a dummy socket.


4. Default 4K (2160p) Resolution & Landscape Screen Orientation
---------------------------------------------------------------
- 4K Default Resolution (`Settings.kt`, `OnboardingActivity.kt`, `SettingsFragment.kt`, `HeadUnitScreenConfig.kt`):
  * Changed default `resolutionId` to `2160p (4K)` (`Resolution._3840x2160`, ID 5) across fresh
    installs, settings dialogs, and the Onboarding setup wizard (unless preflight explicitly
    detects an H.265 hardware decoder failure).
  * Updated `HeadUnitScreenConfig` to honor explicit 4K (`_3840x2160`) selection without clamping
    it to the panel ceiling, while keeping the automatic 720p codec fallback (`add720pFallbackEntry`)
    and 2.4 GHz narrow-band Wi-Fi protection.
- Landscape Default Screen Orientation (`Settings.kt`, `OnboardingActivity.kt`, `SettingsFragment.kt`, `QuickSettingsFragment.kt`):
  * Changed default `screenOrientation` (`KEY_SCREEN_ORIENTATION`) to `Landscape (0°)`
    (`ScreenOrientation.LANDSCAPE`, value 2) instead of `System default`.
  * Updated `OnboardingActivity` to lock orientation to `settings.screenOrientation` and seed
    its Display step orientation toggle from `settings.screenOrientation` (Landscape by default).


5. Architecture, Native C++, Security, and CI/CD Hardening
----------------------------------------------------------
- Structured Concurrency (`AppComponent.kt`, `App.kt`, `AutomationEffectRunner.kt`):
  * Added process-wide `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)` on
    `AppComponent` and replaced untracked `CoroutineScope(Dispatchers.IO).launch` calls.

- Native JNI & C++ Hardening (`usbhelper.c`, `CMakeLists.txt`, `proguard-project.txt`):
  * Added strict bounds checking (`size <= 0`) and null pointer checks after `GetByteArrayElements`
    in `app/src/main/cpp/usbhelper.c`.
  * Enabled `-Wall -Wextra -Werror -Wno-unused-parameter` compiler flags in `app/src/main/cpp/CMakeLists.txt`.
  * Added explicit ProGuard `-keep` rules for JNI classes (`UsbNative`, `NativeTimeStretch`,
    `XyAutoProjectionProperty`) in `app/proguard-project.txt`.
  * Removed dead 0-byte `AapSslNative.kt` and `keystore.jkc` files.

- IPC & Deep-Link Hardening (`AndroidManifest.xml`, `AutomationCommandPolicy.kt`):
  * Removed `android.intent.category.BROWSABLE` from `AutomationActivity`'s `headunit://` intent
    filter so web links cannot trigger internal automation intents.
  * Capped `ACTION_LOG_MARKER` message length (`MAX_LOG_MARKER_CHARS = 512`) in
    `AutomationCommandPolicy.kt` to prevent log flooding.

- Gradle & GitHub Actions CI Consolidation (`app/build.gradle.kts`, `gradle.properties`, `.github/workflows/android-ci.yml`):
  * Removed duplicate `androidx.activity:activity-ktx:1.8.2` and deprecated
    `androidx.lifecycle:lifecycle-extensions:2.2.0` dependencies.
  * Consolidated `.github/workflows/build-debug-apk.yml` into `.github/workflows/android-ci.yml`
    to build/test both `github` and `playstore` variants and execute the native C++ CTest suite.
