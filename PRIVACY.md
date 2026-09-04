# Privacy Policy for Open Headunit

**Last updated:** September 1, 2026

This Privacy Policy applies to the mobile application **Open Headunit** (formerly known as **Headunit Revived**, package name: `com.andrerinas.headunitrevived`), developed and published by **André Rinas** ("we", "our", or "us").

We are committed to protecting your privacy. This document outlines how Open Headunit handles device data and permissions.

---

## 1. Developer and Application Identification

- **Application Name:** Open Headunit (formerly Headunit Revived)
- **Package Name:** `com.andrerinas.headunitrevived`
- **Developer / Publisher:** André Rinas
- **Contact Email:** headunit@andrerinas.com
- **Official Website:** https://headunit.andrerinas.com

---

## 2. No Collection of Personal Data

**Open Headunit does NOT collect, store, track, sell, or share any personal user data.**

The application functions strictly as a display and input receiver for your Android smartphone using the Android Auto Protocol (AAP). All data displayed on the screen (such as navigation maps, music metadata, messages, and contacts) remains on your connected phone and is processed directly by the official Android Auto application installed on that phone, subject to Google's Privacy Policy.

---

## 3. Permissions and Sensitive Data Usage

Open Headunit requests specific system permissions exclusively to fulfill its core automotive projection functions:

- **Microphone (`RECORD_AUDIO`, `FOREGROUND_SERVICE_MICROPHONE`):**
  - **Purpose:** Used solely when you initiate a voice query (e.g. Google Assistant, voice commands, or in-car messaging) to capture your voice via the head unit's microphone and stream it in real-time to Android Auto on your phone.
  - **No Recording or Storage:** Voice audio is streamed directly through the local protocol connection. Open Headunit **never** saves, records to disk, analyzes, or transmits audio data to any third-party or remote server.
- **Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`):**
  - **Purpose:** If the "GPS for Navigation" setting is enabled, Open Headunit reads the vehicle/tablet hardware GPS fix and speed to pass it to Android Auto on your phone, providing more accurate navigation in tunnels or poor reception areas.
  - **No Tracking:** Location data is processed strictly in real-time. We **never** store, log, track, or share your location or movement history.
- **Foreground Service (`FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `FOREGROUND_SERVICE_MICROPHONE`):**
  - Required by Android to maintain the active projection session, audio playback, and temporary microphone capture in the background while in use.
- **USB / Bluetooth / Wi-Fi (`BLUETOOTH_CONNECT`, `NEARBY_WIFI_DEVICES`, `CHANGE_NETWORK_STATE`):**
  - Used strictly to discover, pair, and maintain the local hardware connection (USB-OTG, Wi-Fi Direct, or Hotspot) between the head unit and your phone.

---

## 4. Third-Party Services and Tracking

- **No Analytics:** Open Headunit contains no analytics SDKs (no Google Analytics, Firebase, or third-party telemetry).
- **No Advertising:** The application is completely ad-free and contains no advertising libraries.
- **No Data Sharing:** No device information, usage metrics, or user telemetry is ever shared with or sold to third parties.

---

## 5. Data Security and Storage

All application settings, display configurations, and user preferences are stored strictly locally on your device in private application storage (`SharedPreferences`). No user data is ever sent to or stored on external servers. Uninstalling the app removes all stored configuration data immediately.

---

## 6. Changes to This Policy

We may update this Privacy Policy from time to time to reflect improvements or regulatory changes. Any updates will be published with a revised "Last updated" date.

---

## 7. Contact Us

If you have questions, feedback, or concerns regarding this Privacy Policy or the data practices of **Open Headunit**, please contact:

- **Developer:** André Rinas
- **Email:** headunit@andrerinas.com
- **Project Repository:** https://github.com/andreknieriem/open-headunit
