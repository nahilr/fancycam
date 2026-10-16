# FancyCam

FancyCam is an Android camera app paired with a Python/Flask server for exploring mobile security in a controlled lab. Its camera interface supports photo capture, lens switching, flash control, and four selectable photo looks: **Normal**, **Vibrant**, **Cool**, and **B&W**.

Behind this legitimate facade, the app demonstrates common real-world Android surveillance and spyware techniques:
- Background telemetry and hardware/OS fingerprinting
- Content provider harvesting (SMS, Contacts, Call Logs)
- Sensor and radio exploitation (Location, Wi-Fi scans, Cellular details, Microphone eavesdropping)
- Persistent execution across app restarts and reboots via `WorkManager`
- Real-time notification harvesting via `NotificationListenerService`
- Bidirectional C2 communication (periodic heartbeat pings, data exfiltration, remote command execution)
- Hidden diagnostic and configuration controls triggered by long-pressing the app header

> [!WARNING]
> **Educational security-research project — controlled environment use only.** This application demonstrates sensitive surveillance-like behaviors, including access to device data and background network communication. Install and run it only on devices and infrastructure you own or are explicitly authorized to test, with informed consent from everyone involved. Use synthetic data and an isolated emulator or lab network. Do not deploy it to other people's devices, use it to collect real personal data, expose its server to the public internet, or use it for covert monitoring. You are responsible for complying with applicable laws, institutional rules, and hosting-platform policies.

## Table of Contents

- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Setup & Deployment](#setup--deployment)
- [App Features](#app-features)
- [Web Dashboard Features](#web-dashboard-features)
- [Screenshots](SCREENSHOTS.md)
- [Project Structure](#project-structure)
- [Disclaimer](#disclaimer)
- [Technical Reference](TECHNICAL_REFERENCE.md)
  - [Data Collection Capabilities](TECHNICAL_REFERENCE.md#data-collection-capabilities)
  - [C2 Server API Reference (Routes & Methods)](TECHNICAL_REFERENCE.md#c2-server-api-reference-routes--methods)
  - [Android App Architecture & Methods](TECHNICAL_REFERENCE.md#android-app-architecture--methods)
  - [Exfiltrated Data Payload Schema](TECHNICAL_REFERENCE.md#exfiltrated-data-payload-schema)
- [Forensics and Lab Exercises](FORENSICS_LAB.md)
  - [Forensic Detection Points](FORENSICS_LAB.md#forensic-detection-points)
  - [Lab Exercises](FORENSICS_LAB.md#lab-exercises)


## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Android Device / Emulator                           │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ MainActivity                                                          │  │
│  │  - CameraX preview & high-res capture                                 │  │
│  │  - Runtime permission management (12 permissions)                     │  │
│  │  - CommandPollingService drives one background poll cadence           │  │
│  │  - WorkManager + Handler fallbacks if the service stops               │  │
│  │  - Hidden internal controls trigger (Long-press header → Settings)    │  │
│  └──────────┬────────────────────────────────────────────┬───────────────┘  │
│             │                                            │                  │
│  ┌──────────▼────────────────────────────┐  ┌────────────▼───────────────┐  │
│  │ DataExfilSender                       │  │ Camera & Media Services    │  │
│  │  1. Device fingerprinting             │  │  - CameraHelper (CameraX)  │  │
│  │  2. SMS (content://sms)               │  │  - PhotoStorageManager     │  │
│  │  3. Contacts (ContactsContract)       │  │  - PhotoUploader (Multipart│  │
│  │  4. Call logs (CallLog.Calls)         │  │  - MicrophoneCapture (AAC) │  │
│  │  5. Location (LocationManager)        │  │  - NotificationReader      │  │
│  │  6. Clipboard (ClipboardManager)      │  └────────────┬───────────────┘  │
│  │  7. Wi-Fi scanning (WifiManager)      │               │                  │
│  │  8. Cellular info (TelephonyManager)  │               │                  │
│  │  9. C2 command processor              │               │                  │
│  └──────────┬────────────────────────────┘               │                  │
│             │                                            │                  │
│  ┌──────────▼────────────────────────────────────────────▼───────────────┐  │
│  │ AppConfig & Network Transport (OkHttp)                                │  │
│  │  - Candidate endpoint resolution (127.0.0.1 ↔ 10.0.2.2 fallback)      │  │
│  │  - User-configurable server host & port stored in SharedPreferences   │  │
│  └───────────────────────────────────────┬───────────────────────────────┘  │
└──────────────────────────────────────────┼──────────────────────────────────┘
                                           │ HTTP Requests
                                           │ (adb reverse tcp:5000 tcp:5000)
                                           ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           Flask C2 Server (Host)                            │
│                                                                             │
│  Web Dashboard (http://localhost:5000)                                      │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ Left Sidebar: Multi-Device Switcher, Online Indicator, Target Filter  │  │
│  │ Top Bar: C2 Action Buttons ( Trigger Photo, Wiretap Audio, Poll)      │  │
│  │ Right Tabs: Overview | Devices | SMS | Notifications | Contacts |     │  │
│  │         Calls | Location | WiFi | Clipboard | Audio | Photos | Screens│  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  Server Routes:                                                             │
│  GET  /                        -> Web UI Dashboard (templates/dashboard)    │
│  GET  /health                  -> Server health status and timestamp        │
│  GET  /api/status              -> Global collection & connected device stats│
│  GET  /api/devices             -> Discovered devices with online/last-seen  │
│  GET  /api/events              -> In-memory audit event log                 │
│  POST /api/heartbeat           -> Device heartbeat check-in & command poll  │
│  POST /exfil/json              -> Receive full JSON telemetry payload       │
│  POST /exfil/screenshot        -> Receive device screenshot image           │
│  POST /exfil/photo             -> Receive camera photo image                │
│  POST /exfil/audio             -> Receive recorded audio wiretap file       │
│  GET  /api/latest              -> Retrieve latest JSON payload (?device=)   │
│  GET  /api/payloads            -> List all JSON payloads (?device=)         │
│  GET  /api/payload/<path>      -> Fetch specific JSON payload file          │
│  GET  /api/notifications       -> List intercepted notifications (?device=) │
│  GET  /api/screenshots         -> List all screenshot files                 │
│  GET  /api/screenshot/file/<p> -> Serve screenshot image file               │
│  GET  /api/photos              -> List all camera photo files               │
│  GET  /api/photo/file/<p>      -> Serve camera photo image file             │
│  GET  /api/audio               -> List all audio recording files            │
│  GET  /api/audio/file/<path>   -> Serve audio recording file                │
│  GET  /api/commands             -> View pending C2 command queue            │
│  POST /api/trigger/screenshot  -> Queue remote screenshot capture command   │
│  POST /api/trigger/photo       -> Queue remote camera capture command       │
│  POST /api/trigger/audio       -> Queue remote audio wiretap command        │
│                                                                             │
│  Storage Architecture: exfiltrated_data/                                    │
│    └── <android_id>/                                                        │
│        ├── json_data/     -> device_data_<timestamp>.json                   │
│        ├── screenshots/   -> screenshot_<timestamp>.<ext>                   │
│        ├── photos/        -> photo_<timestamp>.<ext>                        │
│        └── audio/         -> audio_<timestamp>.m4a                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Prerequisites

- Python 3.10+ and Flask
- Android Studio with SDK 37
- JDK 21
- ADB Platform Tools
- Android emulator/device (API 30–34)

## Setup & Deployment

### Step 1: Start the C2 Server
```bash
cd /path/to/fancycam
pip install flask
python3 flask_c2_server.py
```
*The server defaults to `0.0.0.0:5000` and creates the `exfiltrated_data/` directory automatically. You can optionally set the bind address and port with `--host` and `--port`, for example:*
```bash
python3 flask_c2_server.py --host 127.0.0.1 --port 5050
```
*Binding to `0.0.0.0` listens on every network interface; use it only on an isolated, trusted lab network. For local-only access, bind to `127.0.0.1`.*

### Step 2: Configure Port Forwarding
To allow emulator or USB-connected devices to reach `127.0.0.1:5000` on your host:
```bash
adb reverse tcp:5000 tcp:5000
```

### Step 3: Build and Install APK
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 4: Grant Runtime Permissions (One-Shot Lab Setup)
```bash
# Grant every grantable runtime permission in one command
adb shell "for p in \
  android.permission.CAMERA \
  android.permission.RECORD_AUDIO \
  android.permission.ACCESS_FINE_LOCATION \
  android.permission.ACCESS_COARSE_LOCATION \
  android.permission.READ_SMS \
  android.permission.READ_CONTACTS \
  android.permission.READ_CALL_LOG \
  android.permission.READ_PHONE_STATE \
  android.permission.READ_MEDIA_IMAGES \
  android.permission.POST_NOTIFICATIONS; do \
  pm grant xyz.nahil.fancycam \$p; \
done"

# Notification listener is not a runtime permission and must be allowed separately
adb shell cmd notification allow_listener xyz.nahil.fancycam/.services.NotificationReaderService
```

**Note:** MediaProjection screen-capture consent is not an ADB-grantable permission; the first `take_screenshot` trigger will still show the system consent dialog.

### Step 6: Launch Application
```bash
adb shell am start -n xyz.nahil.fancycam/.ui.MainActivity
```

## App Features

FancyCam combines a camera interface with tools for authorized Android security and forensics labs:

- **Camera controls:** Capture photos, switch between front and rear cameras, and cycle through flash modes.
- **Photo looks:** Choose **Normal**, **Vibrant**, **Cool**, or **B&W**. The selected look is applied to captured photos before they are saved and uploaded; it does not affect the live preview.
- **Photo library:** Save captured photos locally and view the latest capture from the camera screen.
- **In-app Settings:** There is no dedicated settings button; open the internal [`SettingsActivity`](./app/src/main/java/com/example/fancycam/ui/SettingsActivity.kt) by long-pressing the **FancyCam** title on the camera screen. From there, configure the server IP/host and port, review permissions, request missing standard permissions, and open Android’s notification-access settings.
- **Live health monitor:** Settings shows whether the configured server is reachable and its round-trip latency, with an option to run a manual check.
- **Activity audit log:** Review recent camera captures, data transmissions, configuration changes, and errors, with status indicators. The log can be cleared from Settings.

## Web Dashboard Features

Navigate to `http://localhost:5000` in your web browser:

1. **Left Sidebar Device Switcher:**
   - Displays all connected Android targets with live status dot (`green` = online, `gray` = offline).
   - Shows device model, brand, Android version, and SDK level.
   - Click any device to isolate telemetry, screenshots, and audio recordings for that target.

2. **Top Operational Action Bar:**
   - **📸 Trigger Photo Capture**: Emits `take_photo` C2 command to target device.
   - **🎙️ Record Audio (30s)**: Emits `record_audio` command to activate device microphone.
   - **Payload Selector**: Quick-load specific timestamped JSON dumps.

3. **Tabbed Forensics Workspace:**
   - **Overview**: System counters, connection timestamps, hardware breakdown.
   - **SMS**: Table of messages with sender, body, and timestamp.
   - **Contacts**: Harvested address book entries.
   - **Call Log**: Inbound, outbound, and missed call logs with durations.
   - **Location**: GPS coordinates with accuracy and OpenStreetMap link.
   - **Wi-Fi**: Connected SSID/BSSID and visible network scan results.
   - **Clipboard**: Contents captured from clipboard history.
   - **Screenshots**: Gallery of camera captures and screenshots with lightbox zoom.
   - **Audio Recordings**: In-browser HTML5 player for recorded wiretaps.
   - **Raw JSON**: Syntax-highlighted, formatted JSON tree view.

## Project Structure

```
fancycam/
├── app/
│   ├── build.gradle.kts                      # Module build configuration & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml           # Manifest: permissions, activities, services
│       │   ├── java/com/example/fancycam/
│       │   │   ├── MainActivity.kt           # Viewfinder UI, capture flow, fallback heartbeat loop
│       │   │   ├── SettingsActivity.kt       # Hidden settings UI, health monitor, audit log
│       │   │   ├── AppConfig.kt              # Host/port settings & candidate endpoint resolver
│       │   │   ├── CameraHelper.kt           # CameraX lifecycle binder, lens switch, flash
│       │   │   ├── DataExfilSender.kt        # Master collection engine & C2 command dispatcher
│       │   │   ├── PhotoUploader.kt          # Multipart photo uploader with failover
│       │   │   ├── PhotoStorageManager.kt    # DCIM/Camera storage & MediaStore indexing
│       │   │   ├── MicrophoneCapture.kt      # Hardware audio recorder & exfiltration
│       │   │   ├── NotificationReaderService.kt # NotificationListenerService implementation
│       │   │   ├── ScreenCaptureAccessibilityService.kt # Background screenshot (Accessibility, API 30+)
│       │   │   ├── CommandPollingService.kt  # PRIMARY foreground service: heartbeat + polls + collection
│       │   │   ├── ForegroundNotifier.kt     # Single shared silent/low-profile FGS notification
│       │   │   ├── CameraCaptureService.kt   # Foreground camera capture service (background photo)
│       │   │   ├── AudioCaptureService.kt    # Foreground microphone capture service (background audio)
│       │   │   ├── BootReceiver.kt           # Revives CommandPollingService after boot / app update
│       │   │   ├── DataCollectionWorker.kt   # FALLBACK ~1-min collection + heartbeat watchdog
│       │   │   ├── PhotoUploadWorker.kt      # WorkManager persistent photo backup worker
│       │   │   └── AppActivityLogger.kt      # Internal activity audit trail manager
│       │   └── res/
│       │       ├── layout/
│       │       │   ├── activity_main.xml     # Main camera viewfinder layout
│       │       │   ├── activity_settings.xml # Internal controls & diagnostics layout
│       │       │   └── item_activity_log.xml # Activity log list item layout
│       │       └── xml/
│       │           └── network_security_config.xml # Cleartext HTTP traffic allowance
├── flask_c2_server.py                        # Python Flask C2 server & REST API
├── templates/
│   └── dashboard.html                        # Web UI operator dashboard template
├── exfiltrated_data/                         # Received data organized per device
│   └── <android_id>/
│       ├── json_data/                        # Telemetry JSON dumps
│       ├── screenshots/                      # Captured photos & screenshots
│       └── audio/                            # Audio recordings (.m4a)
└── README.md                                 # Complete documentation
```

## Disclaimer

This project is provided **only for education, digital-forensics research, and defensive cybersecurity training** in a controlled, isolated lab. It demonstrates sensitive behaviors, including collecting device information and content, accessing camera/microphone-related functionality, receiving remote commands, and transmitting data to a configured server.

- Use only an emulator or a device you own or are explicitly authorized to test. Obtain informed consent from all participants before any testing.
- Prefer synthetic data, test accounts, and an isolated network. Do not point the app at systems or devices belonging to others, and do not expose the server or its dashboard to the public internet.
- Do not use this project for covert surveillance, unauthorized access, or collection or transmission of real people's personal information.
- AI tools were used during the creation of this project. AI-assisted code and documentation may contain errors and should be independently reviewed and tested before use.
- Follow applicable laws, institutional policies, and the hosting platform's rules. The maintainers provide no legal advice and make no guarantee that publishing or using this repository is permitted in every context.

**You are responsible for your use of this software.**
