# Changelog

### v.3.3.1

- Added: Option to auto-resume media playback on quick reconnect if music was playing before disconnect
- Fixed: Errors shown in playconsole
- Big Improvements to native mode. Huge Thanks to @o-jcardenass for this!

### v.3.3.0

- Begin for theming of the App.
- Refactor WiFi-Code from AapService into their own classes for better maintenance, thanks to @MrEAlderson
- Refactor Self-Mode and USB-Mode from AapService into their own classes for better maintenance, thanks to @MrEAlderson
- Refactor Audio and Video Code from AapService into their own classes for better maintenance, thanks to @o-jcardenass
- USB-Blacklist Filter to prevent the app from asking for non Android phones
- Added: Option to disable the clock, thanks to @MrEAlderson
- Added: French translation 🇫🇷 thanks to @phiDu-fr
- Fix/session lifecycle and video concealment
- Video: pace the transport thread instead of shedding reference frames
- UI: a destroyed activity stops listening, and a recycled row keeps its subtitle
- Fix Android 4 Problems
- Fix Orientation issues
- Several Native Mode fixes and enhancements
- Mic: fix the uplink, and let the phone keep the microphone
- Fix: Close keyboard that stays open on older Android versions to prevent layout issues
- Per-channel audio stream selection, thanks to @nicoruy
- Added Android Auto 17.4 Notice for users, who don't know why their wireless setup broke
- Clean Up Dark Mode and Theming Option confusions
- Fix: Projection dies when a Bluetooth keyboard connects or disconnects

### v.3.2.6

- Fix settings UI crash and dpi input on older Android devices
- Fix video artifacts
- Fixing wireless stack where failures stay broken until restart
- Bringing back old style USB List
- Fix broken theme after import: Reapply Theme selection after Settings import
- Make HUD Mode for apply for the whole app

### v.3.2.5

- Fix black screen after backgrounding, and the washed-out picture a dropped frame leaves
- Fix headunit server socket leak
- Car GPS: deliver the head unit's fix when the phone asks, and keep it flowing

### v.3.2.4

- Fix: Video and audio never catch up after a wireless link stall, thanks to @o-jcardenass
- Fix: Native AA wake poke takes down the head unit's own hands-free link, thanks to @o-jcardenass
- Stop the setup wizard from overwriting the reported manufacturer, thanks to @o-jcardenass
- Added: Let the Bluetooth side keep the media buttons, thanks to @o-jcardenass

### v.3.2.3

- Adding custom log location (App folder or Download folder)
- additional fixing for the fps/freeze problems. Thanks to @o-jcardenass and @andrecuellar for helping
- Fix/hotspot unreadable config
- NativeAA: show when a P2P group that lands on channel 12 or 13

### v.3.2.2

- Fixing location jumping, especially on lower speeds
- Fixing screen flicker again in video decoder
- Fix/video throughput telemetry and keyframe lockout, thanks to @o-jcardenass
- Fix Steering Wheel Buttons not working anymore
- added new native mode without WiFi-Direct creation, thanks to @o-jcardenass

### v.3.2.1

- Fixing new welcome screen, thanks to @andrecuellar
- Fixing HW decoding on kitkat thanks to @o-jcardenass
- Fixing 2 Fatal errors shown in play console
- fix: move mic timestamp inside encrypted payload (byte 4), thanks to @bruno303
- making native mode work better and on more devices, thanks to @o-jcardenass

### v.3.2.0

- Don't grab audio focus on connect in dynamic mode, thanks to @bnayahu
- Acquire transient audio focus while AA audio plays, thanks to @bnayahu
- Add GitHub Actions CI (build + unit tests), thanks to @bnayahu
- Fix picture stuck if used within DUDU's PiP, thanks to @MrEAlderson
- Fix(wifi): use public API for frequency, thanks to @DerTeufel
- Added pixel aspect ratio setting, thanks to @axel92b
- Added more audio features to gain audio focus and work with dsp headunits as setting
- Added display over other apps permission for better compatibility with some headunits, espacially with self mode
- Fix transport get stucked on multiple reconnects, thanks to @notathf
- Add car keys support for FYT headunits, thanks to @MrEAlderson
- Added Option to flip projection horizontal for Headup-Displays
- Recover automatically from post-first-frame video display stalls, thanks to @andrecuellar
- Settings and Onboarding Wizard redesign for better usability, thanks to @andrecuellar
- Fixing mulitiple WiFi-Direct and Native AA connection issues, thanks to @o-jcardenass, @andrecuellar and @notathf
- Various fixes PR, thanks to @MrEAlderson
- Selfmode on AA 17.4 now connects to the headunit dev server or opens the AA settings to start it
- Rename the app to Open Headunit because of confusion with Headunit Reloaded (HUR)

### v.3.1.1

- Reduce pressure on sensor events like night and gps and start/stop these events in onConnected, onDisconnect and onDestroy
- Merged ffmpeg PR #625 by @mmwtl. This added ffmpeg software decoder for h265, which old devives could benefit a lot. Thank you!
- Wi-Fi Direct changes. Prevent duplicate start, graceful resets, cleaned up stale groups
- Remove Automatic Play Integrity Checks, so Playstore won't check the license and link to the playstore in headunits
- Added manual bssid for native Mode. This should help for users where the bssid is hidden for the app, thanks to @rakshan-kumr
- Added immediately network scan with wifi connection, thanks to @MrEAlderson for the PR

### v.3.1.0

- Added libusb as alternative to the native usb stack for better compatibility with some devices
- Fixed Layout in Portrait Mode in nearly square devices
- Added Scale Slider for loading screen media

### v.3.0.1

- Fixed: App Exit on Disconnect
- Enhanced: USB Workflow. This will hopefully eliminate some random usb disconnects
- Fixed keyboard input on Android < 6 Devices
- Enhanced Wi-Fi Direct-Mode
- Added: Bundled FFmpeg HEVC decoder with decoder source setting and FPS/performance overlay
- Enhanced: HEVC GLES/YUV rendering latency, audio buffering, and Wi-Fi Direct fallback reliability
- Enhanced File Selector for some devices
- Fixed some fatal errors, showing in play console

### v.3.0.0

- Added: Custom loading screen (image/GIF/video), thanks to @andrecuellar
- Added: Settings-Reset Button, if you mess up something in the settings, you can now reset them to default
- Removed: Old deprecated ssl library written in C-Code for better maintenance, stability and smaller file sizes
- Added: Direct Logging to file without logcat, thanks to @Anton111111
- try to fix connection lost on carrier lost again
- keep usb disconnection for 8s alive, for maybe restarts of usb dongles
- Implement car headlight signal mode (ILL+) for night theme management, thanks to @minhtuanact
- Added settings export and import functionality with backup options, thanks to @JanRi3D
- Added whitelist to usb connection for not interrupting with iPhones and other usb devices
- Added QR Code for easy connection with wireless helper
- Fixed: BT auto-connect dragging phone into wireless flow during USB session, thanks to @andrecuellar
- Persist Auto-Optimize wizard settings synchronously, thanks to @andrecuellar
- Added ability to swipe with two fingers from the right side to switch fullscreen mode and Normal (all bars) mode, thanks to @Anton111111
- Improved: usb button auto connect, thanks to @bezprobeloff
- Catched an fatal error listed in the play console
- Fixed: Audio Stutter on some devices since 2.1.1
- Fixed: USB device list duplicates and Android Auto projection launch on Android 10+, thanks to @jeancarloscc
- Enhanced: Google Nearby. It was buggy with 2 FPS video
- Fixed: Navigation Button mapping now working

### v.2.3.1

- Fixed a connection lost on for example borders
- Binding socket to Wi-Fi network if available to prevent connection drops on carrier lost
- Added Static Audio Focus Toggle to prevent audio focus loss on some devices
- Fixing samsung routines and modes
- Fixing wrong orientation on start if holding the phone wrong. Now uses the orientation from settings
- Fixing usb errors with AAwireless Dongles
- Added Audio Mixer to mix different audio tracks, thanks to @jeffdapaz for the idea
- Added Autostart on BT for multiple devices
- Fixed Microphone input source was wrong mapped
- Added Vietnamese translation 🇻🇳 thanks to @minhtuanact
- Merged PR #549 - implement back key routing and add keymap for back key, thanks to @JanRi3D

### v.2.3.0

- Added some new buttons for keymap
- Fixed 3 Fatal errors
- Fixed video decoder settings for allwinner devices
- Added new navigation intents
- Included PR #456
- Added new "Autostart on WiFi" Setting #324
- Fixed empty bssid on native AA. Should now work on more devices
- Fixed a new fatal with media sessions
- Readded fullscreen overlays system icons #351
- Remap Enter (66) to Dpad Center (23) for Rotary Knob #459
- Debounce multiple key events if key event is the same in 100ms #465
- Moved Mic settings to own fragment and added 3 new options for the new mic enhancement from version 2.2.2, which defaults to off for better compatibility
- Merged PR #481 - Apply MediaTek 60fps and audio optimizations, thanks to @mrkontrast-coder
- Some rewrite of the AudioTrackWrapper, to enhance stability and minimize stutters
- Merged PR #490 - Add UI scale settings, thanks to @Anton111111
- Merged PR #502 - Navigation Broadcast Updates. Thanks to @Bastel2020

### v.2.2.2

- Fixed: Exit on disconnect now stops the carmode too
- Fixed: Exit intent not closing the app
- Fixed: Orientation not working great on app switch, if you have "auto or sensor" enabled
- Again: Steering Wheel and Keymapping got some changes, maybe this will work on more devices
- Extend mic debugging and add NoiseSuppressor, AutomaticGainControl and AcousticEchoCanceler for better voice quality
- Fixed an issue where the Android USB system prompt wouldn't appear for phones. The prompt is now enabled by default and can be separately disabled for USB thumb drives. It calls "Listen for USB Devices" setting and it decouples the system USB prompt from the Auto-Start behavior. This will bring back the old functionality for all and can be disabled for those who are annoyed of the popup for non Android Auto devices
- Fixed: Rescale and UpdateUI if the useable area differs from the one negiotated. This happens on devices which lie about their navbars.
- Add separate audio streams setting and update related functionality thanks to @Anton111111
- Fixed: Auto-Night mode over 3 hours of in the UK and other countries, thanks to @BinarySimple17
- Enhanced: When audio sink is off, the app no longer tries to get media focus at all

### v.2.2.1

- **Fixed a fatal error in UBS conncetions since 2.2.0. This is important so releasing this version while not fixing all planned issues**
- Google Nearby Connection is now auto connecting if auto connect is enabled
- UI: Added Error Message for Android 10 and below for selfmode
- New Approach for scaling and touch to prevent offset
- Fixing App appears multiple times in App-Drawer
- Fixing Routines and intents not working

### v.2.2.0

- Added: Native AA. 🎉 Warning! This will only work on a limited amount of headunits! Most Android devices do not support connecting 2 Android devices via Bluetooth which is essential for this to work.
- Added: Google Nearby Support as connection method. Needs Wireless Helper 1.6.0 or later
- Added: Pip-Support
- Added: 4K in select
- Try to fix connection problems on WiFi
- Added: Intent and routine for starting the app directly to self mode
- Added: Force Scale Option for older devices on surface view
- Added: New Immersive Fullscreen with avoided notch area. This should fix problems for eg. Pixel Phones
- Enhanced: Video Decoder Error Handling
- Added: 2 new WiFi-Options for a WiFi-Direct. Thanks to @andrecuellar
- Added Japanese language 🇯🇵 thanks to @mattyann87
- Enhanced: Media Session Announcement. Thanks to @irwanrhmn
- New App-Icon without text for better visibility
- Fixed: USB modal appearing for non-Android Auto devices thanks to @andrecuellar
- Added: Create configurable audio queue and audio buffer in settings thanks to @irwanrhmn

### v.2.1.1

- Fixed: Layout crash on Android 4.2
- Added: Enable Hotspot option. Note: This will not work on every device. Especially after Android 13!
- Added: Fake VPN Handler for new Android Auto in offline mode. It is no longer possible to send a network to AA, so we need this hack, if your device is offline
- Enhanced: Audio-Focus is now more aggressive to hopefully fix the audio is not coming from my tablet/headunit errors
- Added: Auto-Boot Functionality. Thanks to @andrecuellar

### v.2.1.0

- Fixed: Exit Intent not working. Thanks to benyjr
- Added: Rotary Support
- Fixed: Crash in Android < 5
- Fixed: Double Button fire and enter/knob click as dpad center mapping
- Enhanced: Android Auto start for selfmode now tries all methods always and catches errors
- Fixed: Auto start, connection lost overlay, toasts and API17 Bluetooth crash. thanks to @andrecuellar
- Added: Exit App on disconnect feature/setting thanks to @Tilak-03 and @andrecuellar
- Enhanced: Wi-Fi Setting redirect is gone. Now only a toast message
- Fixed: styling errors

### v.2.0.2

- Fixed: 60FPS never applied
- Fixed: SSL Handshake fix for truncated messages
- Added: dark mode and xtreme dark mode setting for the app itself thanks to @andrecuellar!
- Removed: App category="maps" so nav buttons recognize the app again
- Fixed: Multiple Button Events and double/tripple skips
- Fixed: USB Permission Request thanks to @Bastel2020
- Added: Setting for Disable stretch to fit. This will fix wrong rendering on some devices @thanks to tsabaia
- Fixed: Touch screen accuracy when not in full screen mode for older devices
- Fixed: Big Icon-Button on main screen when the dpi is small and the screen is wide

### v.2.0.1

- Fixed: Multiple volume sliders appearing on modern devices (Pixel 9 fix)
- Added: Support for Media Button emulation (SWC improvement for MacroDroid etc.)
- Added: App shortcut and deep link for full app exit (headunit://exit)
- Added: Improved Wi-Fi Direct reliability with recursive discovery and ping handoff
- Added: Romanian translation 🇷🇴, thanks to @LeeWiu
- Merged PR #189: Adding navigation message handling, thanks to @Bastel2020
- Merged PR #215: Fix USB reconnect race and stale dongle data after AA exit, thanks to @andrecuellar
- Merged PR #205: Fix wireless dongle disconnect on network changes, thanks to @andrecuellar
- Merged PR #216: Add Bluetooth SCO microphone support, thanks to tgigli

### v.2.0.0

- Added Wi-Fi Direct (P2P): Support. Connect your phone to the headunit without a shared network or hotspot. The headunit now automatically becomes visible as a P2P peer.
- Refactored Connection Core: Complete rewrite of the internal connection handling using the new **CommManager**. Improved stability, faster handshakes, and better coroutine integration.
- Enhanced Fullscreen Logic: Choose between "Immersive" (hide all), "Status Only" (keep navigation bars), or "None". Improved compatibility for devices where buttons were previously obscured.
- Added Auto-Optimization Wizard: Automatically recommends the best Resolution, DPI, and Codec for your specific hardware.
- Added Early MediaSession Initialization: Fixes audio routing issues where the phone would sometimes play sound through its own speakers instead of the headunit.
- Added New Logging System: Integrated log level control and file capture for easier debugging.
- **IMPORTANT** Fixing Android Auto 16.4 intents for selfmode. In Wireless Helper too. Please update to 1.2.0

### v.1.15.1

- New Feature: Added Auto-Optimization Wizard to automatically find the best Resolution, DPI, and Codec settings for your hardware.
- Bugfix: Fixed Self Mode failing to start in offline/hotspot scenarios (Network ID 0 fix).
- Bugfix: Improved Audio Routing. The phone is now more likely to route audio to the headunit immediately upon connection by using an early-initialized MediaSession with remote playback metadata.
- Bugfix: Fixed GPS Speed calculation. Speeds were previously doubled due to an incorrect unit conversion (knots instead of mm/s).
- UI: Improved Settings readability on small screens by allowing multi-line descriptions.

### v.1.15.0

- Added arabic language thanks to A5H0
- Added new intent for setting day/night mode for maps
- Added new window flags for older devices to finally fix fullscreen issues
- Added new intents to make the headunit recognize the app as navigation app
- Added LegacyOptimizer which will handle things directly and faster for single core cpus. Should improve the performance on Android 4.1 - 4.4 Devices
- Fixed BT Permission Bug
- Changed the Twilight-Calculator for better switch to day/night on auto mode to prevent to bright display
- Added more mediasession logic to gain audio focus and audio routing
- Merged Retry Button on connect screen, thanks to @andrecuellar
- Merged auto connect usb feature, thanks to @andrecuellar

### v.1.14.3

- New Feature: Added **App Shortcuts** for quick Connect/Disconnect. Now compatible with Samsung Modes & Routines.
- New Feature: Registered OpenHU as an official system navigation provider (Category: Maps).
- Improvement: Added `headunit://disconnect` intent for remote session termination.
- Fixed: Resolved potential app freezes during long-running sessions by optimizing memory hygiene and FIFO handling.
- Compatibility: Improved support for Amazon Fire Tablets and devices without GPS hardware by marking hardware features as optional.

### v.1.14.2

- Bugfix: Resolved `SuperNotCalledException` crash in MainActivity.
- Bugfix: Notification and Exit Button do not close the app
- Improvement: Removed old legacy Invisible Bluetooth Setting to prevent Bluetooth from start on the whole time
- Localization: Corrected "Version" translations in multiple languages.

### v.1.14.1

- Improvement: Integrated USB Auto-Connect into "Auto-Connect Last Session". App now behaves like a native headunit and connects automatically on startup or USB plug-in.
- New Feature: Added USB Soft-Reset logic. Automatic recovery from USB "stalls" without needing to replug the cable.
- Major Improvement: Audio focus and routing overhaul. Added `MediaSession` support and immediate focus response to phone. Fixes issues where background apps on the tablet would block Android Auto audio.
- Improvement: Robust Task Switching. Leaving the app via Home button or clicking the Launcher icon no longer breaks the connection. Music continues in background, and clicking the icon/notification correctly returns to the projection.
- New Feature: Enhanced Key Debugger ("Key-Sniffer"). Prominent display of all key events, including special characters (ö, ü, ß) and proprietary steering wheel intents (MTC, FYT).
- New Feature: Official Navigation App Registration. OpenHU is now recognized as a navigation provider (`geo:`, `google.navigation:`, `android.intent.action.NAVIGATE`). Compatible with hardware "NAV" buttons.
- Bugfix: Removed redundant "Already connected" and "Reconnection required" alerts for a smoother user experience.
- Localization: All new strings translated into 10 languages.

### v.1.14.0

- Added Separate volume setting #91
- Added Auto-Start on Bluetooth Option
- Merged PR #134 - Fixing Connection on Mediathek Headunits
- Merged PR #131 - Fixes SystemUI on < Android 6 Devices
- Merged PR #127 - Fixing Audio Truncation

### v.1.13.3

- Fixed Screen Issues on Android 4 with header and navigations #114
- Fixed Night-Mode Bug #116
- Merged several PR for better Language Handling with a new language selector. Thanks to @andrecuellar

### v.1.13.2

- Fixed margins now working for devices prior Android 5 Lollipop
- Fixing warnings
- Fixing broken colors on mixed daynight values
- Fixed a bug where a message is bigger than thought after about 20 minutes and connections closes

### v.1.13.1

- Fixed Custom Insets Dialog with a Scrollview
- Fixed 4 app crashes listed in play console
- Fixed 2 warnings in play console for edge-to-edge display
- Fixed a race condition in ssl read/write
- Preventing disconnect if just one package was broken/corrupt in ssl transfer

### v.1.13.0

- Improvement: USB stability overhaul (implemented 16KB internal buffer)
- New Feature: Custom Insets (Margins) setting with live preview
- Fixed: Video decoder blackscreen on some AI-Boxes (H.264 NAL padding)
- Fixed: UI focus issues in Settings causing system bars to reappear
- Fixed: Native SIGABRT crashes during reconnection
- Cleaned up Debug settings

### v.1.12.0

- Major Improvement: Wireless Connectivity overhaul (Socket Reuse, better Handshake)
- New Feature: Wireless Mode Switch (Manual, Auto-Scan, Wireless Helper Support)
- Added: Support for Wireless Helper companion app
- Fixed: Android 15 (16KB page size) compatibility for native libraries

### v.1.11.1

- Improvement: 1440p and h265 are now checked both. Some old devices have more than 1080p but no h265 support and android auto crashes with Error 11
- Fixed bug in Kitkat Devices on search for wireless devices
- Merged PR #94 - Fixed blurry icon. Thanks to @nicoruy
- Merged PR #95 - Make Settings own View to apply directly. Thanks to @nicoruy

### v.1.11.0

- New Feature: Advanced Night Mode (Light Sensor, Screen Brightness, separate thresholds, manual time)
- Improvement: Audio Stuttering fixed (Optimized ACK handling)
- Improvement: USB Reconnection stability (Added "Reconnection Required" dialog for stuck sessions)
- Improvement: WiFi Discovery (Added Multi-Interface Scan and NSD/mDNS support)
- New Feature: Enhanced Service Notification (Reduced noise, added Exit button)
- Added: Spanish translation 🇪🇸 thanks to @andrecuellar
- Added: Ukraine translation 🇺🇦 thanks to welshi32
- Bugfix: Non-Fullscreen View was stretched, touch could be off
- Bugfix: Wifi with Headunit Server now works with hotspot

### v.1.10.4

- Added: Dutch translation 🇳🇱 thanks to safariking
- Several black screen and connection error enhancements
- Bugfix: Crash in Background if not started as foreground service

### v.1.10.3

- Bugfix: Force Software Decoder wasn't getting always the sw decoder
- Added: Russian translation 🇷🇺 thanks to @prostozema
- Enhancement: Fixing small issues in the video-decoder which should help lower spec devices to render properly (but act a little bit slower perhaps)

### v.1.10.2

- Bugfix - Button Mapping ignored #71
- New Feature: Screen-Orientation Feature to lock to a certain orientation (Landscape/Portrait) #69 thanks to @JanRi3D
- Enhancement: SSL will now attempt multiple times and not break instantly thanks to @MicaelJarniac
- Added: Chinese(Traditional) translation 🇹🇼 thanks to @GazCore
- Added: Polish translation 🇵🇱 thanks to @Kacper1263
- Added: Czech translation 🇨🇿 thanks to @teodortomas
- Fixed brazilian portuguese folder name

### v.1.10.1

- Bugfix: Added missing 3 Byte startcode which stops some devices to start the projection
- Added PR #68 - Fix Wifi Direct detection thanks to @rakshan-kumr
- Added PR #67 - Brazilian Portuguese translation 🇧🇷 thanks to @MicaelJarniac
- Added PR #66 - Add conscrypt to fix error 7 on lower Android versions 🚀, thanks to @JanRi3D
- The old jni files and c code can maybe be removed when PR #66 is performing great. So we can get rid of that again :)

### v.1.10.0

- New Feature: Portrait Mode Support (Dashboard & Projection) with smart resolution scaling Known Bug is, that map is unresponsive to touch. That is in all HU apps
- New Feature: Redesigned Keymap Screen (easier configuration)
- New Feature: Right Hand side driving setting (#63)
- New Feature: Auto-Connect last session (Thanks to @JanRi3D) (#21)
- New Feature: Auto-Selfmode if enabled in settings
- New Feature: Allow sideloaded apps (#57)
- Localization: Added German Translation 🇩🇪 Other translations are highly appreciated
- Improvement: TextureView is now the default renderer (better compatibility for most devices)
- Improvement: Fixed Dashboard layout rotation
- Rewrite: Completly Rewrite the Video-Decoder as it was undebuggable. Removed the async mode and more

### v.1.9.0

- New Feature: GLES20 Video Renderer (Fixes black screen/artifacts/scaling on older Head Units)
- New Feature: In-App Log Export (Save to file/Share) for easier debugging
- Improvement: Audio Sink Logic fixed (System Audio always advertised) -> Fixes black screen when Audio Sink is disabled
- Improvement: Video Decoder optimized for legacy devices (Buffer size adjustments, Overflow handling)
- Hopefully Fix: Audio Stuttering resolved (Buffer/Queue logic reverted to stable state)
- Fix: Video Fragmentation on some devices (Support for split frames/Offset 2 headers)
- Fix: Crash on Android 5.1 (NoSuchMethodError)
- Fix: Audio-Sink disable not working
- UI: Consistent Dialog Theme (Teal/Rounded) and improved list buttons
- Compatibility: Verified support for Android 4.1+ (minSdk 16)
- Compatibility: Bring back native SSL Support (JNI) for better performance on older devices (Toggle in Debug Settings)

### v.1.7.0

- Added WiFi Network Discovery (Port Scan) with Auto-Connect
- Added Intent Support (`headunit://connect?ip=...`) for automation
- Added Wifi-Launcher Support with new setting
- Updated README

### v.1.6.3

- Added mandatory Safety Disclaimer on first start
- Improved audio stability and fixed stuttering issues
- Enhanced full-screen stability with transparent system bars
- Fixed WiFi disconnection synchronization issues (ByeBye request)
- General UI and stability improvements

### v.1.6.2

- Fixed critical screen flickering during startup and fullscreen transitions
- Resolved video decoder freezing issues on tablets and older devices
- Improved system bar handling for a more stable projection experience

### v.1.6.1

- Added "About" page with version info, changelog, and license
- Added "Force Legacy Decoder" (synchronous mode) setting to fix issues on some devices (e.g., Mediatek)
- Improved surface handling to prevent crashes on decoder reconfiguration
- Fixed "Unsaved changes" dialog in settings
- Updated UI with consistent back arrows and theming
- Fixed black screen issues on some devices by optimizing decoder initialization

### v.1.6.0

- Fixed the selfmode not working outside the wifi bug
- Redesign of App, Look and feel with modern Material 3
- Better Settings
- Huge Android Auto Protocol Updates
- Clicking Exit in Android Auto now closes the projection

### v.1.5.0

- Complete Rewrite of the Video decoder for better Video-Performance
- Updated Android-Auto Protocol with the latest available codecs (h265 for example)
- Added 1440p Video-Option(Note this only works with h265!)
- Added FPS-Setting
- Added Codec-Setting
- Added Force to Use Software decoder Setting
- Merged the Android Native SSL Library and get rid of the old jni files

### v.1.4.1

- Fixing Touch-Events for devices with higher resolutions
- Removing file-log and logging is only enabled if debug is on

### v.1.4.0

- Added Selfmode
- Better Close App

### v.1.3.0

- Changed the Settings Layout Look and feel
- Added DPI Option
- Added full screen option
- Fixing Keymap Changes and button recognition

### v.1.2.1 - Resolution enhancement

- Just a minor enhancement for the resolution. Not yet perfect in my opinion but better than before
- The is the last release for this year. Happy Holidays to all and a happy new year

### v.1.2.0 - Bugfix Release

- Added Exit button to app
- Added resolution settings back for better compatibility with different screen sizes
- Added Option for which texture to use. Some devices perform better on SurfaceView, some on TextureView
- Fixed keymapping
- Fixed a lot of color issues
- Fixed a bug where the app crashed on startup on some devices
- Fixed Layout on wider screens
- Some rewrite, and small bugfixes

### v1.1.0 - New Design

- Changed the basic design to a modern look and bigger buttons
- Hopefully fixed audio-stutters with audio thread and some logs
- Removed some deprecations

### v1.0.0 - Initial Revived Release

- Updated dependencies to latest versions.
- Improved compatibility with newer Android versions.
- Added Multitouch-Support
- Some sort of wireless support with Headunit-Server on Phone
