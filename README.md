# FancyCam - Educational Android Security & Forensics Demo

A Kotlin-based Android application for teaching Android malware analysis, data collection techniques, and forensic detection in a **sandboxed lab environment**.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Setup & Deployment](#setup--deployment)
- [Web Dashboard](#web-dashboard)
- [Data Collection Capabilities](#data-collection-capabilities)
- [Forensic Detection Points](#forensic-detection-points)
- [Lab Exercises](#lab-exercises)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Troubleshooting](#troubleshooting)

---

## Overview

FancyCam is a camera app that demonstrates common Android malware techniques for educational purposes. While appearing as a legitimate photo capture and backup application, it includes configurable data collection modules that show how malware harvests sensitive device information and exfiltrates it to a remote Command & Control (C2) server.

**Intended Use:** University courses, security workshops, and controlled lab environments. Do not deploy on any device without explicit authorization.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                     Android Device (Emulator)               │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │ MainActivity                                          │  │
│  │  - CameraX preview & photo capture                    │  │
│  │  - Permission management (12 permissions)             │  │
│  │  - WorkManager periodic task scheduling               │  │
│  │  - NotificationListenerService binding                │  │
│  │  - Long press on app title → Settings menu            │  │
│  └──────────┬────────────────────────────────────────────┘  │
│             │                                               │
│  ┌──────────▼────────────────────────────────────────────┐  │
│  │ DataExfilSender (Singleton)                           │  │
│  │  1. Device fingerprinting                             │  │
│  │  2. SMS retrieval (content://sms)                     │  │
│  │  3. Contact harvesting (ContactsContract)             │  │
│  │  4. Call log extraction (CallLog.Calls)               │  │
│  │  5. Location tracking (LocationManager)               │  │
│  │  6. Clipboard monitoring (ClipboardManager)           │  │
│  │  7. WiFi scanning (WifiManager)                       │  │
│  │  8. Microphone capture (MediaRecorder)                │  │
│  │  9. Notification listening (NotificationListener)     │  │
│  │ 10. Network operator info (TelephonyManager)          │  │
│  └──────────┬────────────────────────────────────────────┘  │
│             │                                               │
│  ┌──────────▼────────────────────────────────────────────┐  │
│  │ WorkManager Workers                                   │  │
│  │  - DataCollectionWorker (periodic, 15 min)            │  │
│  │  - ScreenCaptureWorker (placeholder)                  │  │
│  │  - PhotoUploadWorker (one-time photo upload)          │  │
│  └──────────┬────────────────────────────────────────────┘  │
│             │                                               │
└─────────────┼───────────────────────────────────────────────┘
              │ HTTP POST (JSON / multipart)
              │ adb reverse tcp:5000 tcp:5000
              ▼
┌─────────────────────────────────────────────────────────────┐
│                  Flask C2 Server (Host)                     │
│                                                             │
│  Web Dashboard (http://localhost:5000)                      │
│  ┌──────────────────────────────────────────────────────┐   │
│  │ Left Sidebar: Connected Devices & Stats              │   │
│  │ Right Tabs: Overview | JSON | Screenshots | Audio    │   │
│  │         | Events | Manual Triggers                   │   │
│  └──────────────────────────────────────────────────────┘   │
│                                                             │
│  API Endpoints:                                             │
│  GET  /                    -> HTML dashboard (from template)│
│  GET  /api/status          -> collection statistics         │
│  GET  /api/events          -> live event feed               │
│  GET  /api/latest          -> latest JSON payload           │
│  POST /api/trigger/screenshot -> manual screenshot trigger  │
│  POST /api/trigger/audio   -> manual audio recording trigger│
│  POST /exfil/json          -> receive device data           │
│  POST /exfil/screenshot    -> receive screenshot            │
│  POST /exfil/audio         -> receive audio                 │
│                                                             │
│  Storage: exfiltrated_data/                                 │
│    ├── json_data/     -> device_data_*.json                 │
│    ├── screenshots/   -> screenshot_*.png                   │
│    └── audio/         -> audio_*.m4a                        │
└─────────────────────────────────────────────────────────────┘
```

## Prerequisites

### Host Machine

| Requirement | Version | Purpose |
|---|---|---|
| Python | 3.10+ | Flask C2 server |
| Flask | 2.0+ | Web server framework |
| Android Studio | Latest | App development |
| Android SDK | API 24+ | Build target |
| Java (JDK) | 11+ | Kotlin compilation |
| ADB | Latest | Device communication |
| Web browser | Any modern | Dashboard access |

### Android Emulator / Device

| Requirement | Notes |
|---|---|
| Android emulator | Pixel 4 GAPI or similar |
| Android version | API 24+ (recommended API 30+) |
| Network | Same host via `adb reverse` |
| Permissions | 12 runtime permissions granted |

## Setup & Deployment

### Step 1: Start the C2 Server

```bash
cd /mnt/Work/AndroidStudioProjects/fancycam
pip install flask
python3 flask_c2_server.py
```

The server starts on `0.0.0.0:5000`. Verify:

```bash
curl http://localhost:5000/api/status
```

### Step 2: Configure Port Forwarding

For emulator-to-host communication:

```bash
adb reverse tcp:5000 tcp:5000
```

### Step 3: Configure Proxy (Important)

Check and clear any proxy that interferes with local connections:

```bash
# Check proxy setting
adb shell settings get global http_proxy

# Clear proxy if set
adb shell settings put global http_proxy :0
```

### Step 4: Build the Android App

```bash
cd /mnt/Work/AndroidStudioProjects/fancycam
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Step 5: Install on Emulator

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 6: Grant Permissions

```bash
# Core permissions
adb shell pm grant com.example.fancycam android.permission.CAMERA
adb shell pm grant com.example.fancycam android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.example.fancycam android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.example.fancycam android.permission.READ_SMS
adb shell pm grant com.example.fancycam android.permission.READ_CONTACTS
adb shell pm grant com.example.fancycam android.permission.READ_CALL_LOG
adb shell pm grant com.example.fancycam android.permission.RECORD_AUDIO
adb shell pm grant com.example.fancycam android.permission.READ_PHONE_STATE

# Enable notification listener
adb shell cmd notification allow_listener com.example.fancycam/.NotificationReaderService
```

### Step 7: Launch and Monitor

```bash
# Launch the app
adb shell am start -n com.example.fancycam/.MainActivity

# Monitor data collection in real-time
adb logcat -s "DataExfilSender:D"

# Watch the C2 server receive data
curl http://localhost:5000/api/events

# Or open the dashboard in your browser
# http://localhost:5000
```

## Web Dashboard

The C2 server provides a real-time monitoring dashboard at `http://localhost:5000`:

### Dashboard Layout (Two-Column Design)

**Left Sidebar - Connected Devices:**
- Device list with connection status
- Quick stats: JSON payloads, screenshots, audio files

**Right Panel - Tabbed Interface:**
| Tab | Content |
|---|---|
| **Overview** | Statistics cards, latest device info |
| **JSON Payloads** | Recent payloads list, raw JSON viewer |
| **Screenshots** | Thumbnail gallery with full-size view |
| **Audio Files** | Playable recordings with metadata |
| **Events** | Real-time event feed with timestamps |

### Manual Trigger Buttons (Dashboard)
| Button | Action | Endpoint |
|---|---|---|
| 📸 Take Screenshot | Triggers device screenshot capture | `POST /api/trigger/screenshot` |
| 🎙️ Record Audio (30s) | Triggers 30-second audio recording | `POST /api/trigger/audio` |

### Dashboard Features
- Auto-refreshes every 5 seconds
- Real-time event logging
- Clickable screenshots for full-size view
- Audio player with native controls
- Pretty-printed JSON payload viewer

## Data Collection Capabilities

### 1. Device Fingerprinting

Collects hardware and software identity information:

- `ANDROID_ID` — unique per-app-per-device identifier
- `Build.BRAND`, `Build.MODEL`, `Build.MANUFACTURER` — device identity
- `Build.FINGERPRINT` — full ROM signature
- `Build.VERSION.SDK_INT`, `Build.VERSION.RELEASE` — OS version
- Installed application list — all package names
- App version information

### 2. SMS Retrieval

Queries the SMS content provider (`content://sms`) for all text messages. Requires `READ_SMS` permission.

**Note:** On Android 10+ (API 29+), inserting SMS data into the emulator requires the app to be the default SMS app. Read access works with `READ_SMS` permission. Use `adb shell content insert` with root for test data population.

### 3. Contact Harvesting

Queries `ContactsContract` provider for:
- Contact names (first, last)
- Phone numbers (all types)
- Email addresses (all types)

Requires `READ_CONTACTS` permission.

### 4. Call Log Extraction

Queries `CallLog.Calls` provider for:
- Phone numbers
- Call duration
- Call type (incoming/outgoing/missed)
- Contact names
- Timestamps

Requires `READ_CALL_LOG` permission.

### 5. Location Tracking

Uses `LocationManager.getLastKnownLocation()` to obtain:
- GPS coordinates (latitude, longitude)
- Accuracy radius
- Altitude and speed
- Provider type (GPS, network, fused)
- Fix timestamp

Requires `ACCESS_FINE_LOCATION` or `ACCESS_COARSE_LOCATION` permission.

### 6. Clipboard Monitoring

Reads current clipboard contents via `ClipboardManager.getPrimaryClip()`:
- All clipboard items (text, URIs)
- Item count
- Timestamp

No permission required. Android 10+ shows a toast when accessed from background.

### 7. WiFi Network Scanning

Uses `WifiManager` to collect:
- Currently connected SSID/BSSID
- Signal strength
- Link speed
- Scan results for all visible networks
- Security capabilities per network

Requires `ACCESS_WIFI_STATE` permission. Location permission required for scan results on API 26+.

### 8. Microphone Capture

Records audio using `MediaRecorder`:
- Format: AAC in MPEG-4 container
- Bitrate: 128 kbps
- Sample rate: 44.1 kHz
- Default duration: 30 seconds (configurable)
- Auto-exfiltration to C2 after recording
- Local file deleted after upload

Requires `RECORD_AUDIO` permission. Android 11+ shows persistent recording notification.

### 9. Notification Listener

`NotificationListenerService` captures:
- App package name
- Notification title and text
- Sub-text, info text, big text
- Progress indicators
- Timestamp

Requires explicit user grant in Settings > Security > Notification access.

### 10. Network Operator Info

Collects cellular network data:
- Network operator name (SIM)
- SIM operator name
- Country codes (SIM and network)
- Network type (2G/3G/4G/5G)
- Roaming status

Requires `READ_PHONE_STATE` permission on some API levels.

## Forensic Detection Points

| Technique | Detection Command | What to Look For |
|---|---|---|
| **Device fingerprinting** | `adb logcat -s DataExfilSender:D` | Device info collection logs |
| **HTTP exfiltration** | Wireshark / `tcpdump` | POST requests to C2 IP |
| **SMS access** | `adb shell dumpsys dbinfo` | `content://sms` database queries |
| **Contact access** | `adb shell dumpsys dbinfo` | ContactsContract queries |
| **Call log access** | `adb shell dumpsys dbinfo` | CallLog provider queries |
| **Location access** | `adb shell dumpsys location` | Provider query timestamps |
| **WiFi scanning** | `adb shell dumpsys wifi` | Scan result timestamps |
| **Microphone** | `adb shell dumpsys audio` | Active recording sessions |
| **Notification access** | `adb shell settings get secure enabled_notification_listeners` | Listener service binding |
| **WorkManager persistence** | `adb shell dumpsys jobscheduler` | Scheduled periodic workers |
| **Proxy artifacts** | `adb shell settings get global http_proxy` | Unexpected proxy values |
| **Background execution** | `adb shell dumpsys activity services` | Hidden service execution |
| **Permission grants** | `adb shell dumpsys package com.example.fancycam` | Runtime permission audit |
| **File system** | `adb shell ls /data/data/com.example.fancycam/cache/` | Captured data files |
| **Network connections** | `adb shell netstat -tlnp` | Outbound connection tracking |
| **Battery drain** | `adb shell dumpsys batterystats` | Unusual power consumption patterns |

## Lab Exercises

### Exercise 1: Identify Data Collection Endpoints

**Objective:** Determine what data the app collects and how it is transmitted.

**Steps:**
1. Launch the app on the emulator
2. Monitor logcat: `adb logcat -s DataExfilSender:D`
3. Identify each data type collected
4. Capture network traffic with Wireshark (filter: `http POST`)
5. Document the destination IP, port, and payload structure

**Deliverable:** List all data collection methods with corresponding logcat evidence.

---

### Exercise 2: Network Traffic Analysis

**Objective:** Analyze the structure of exfiltrated data.

**Steps:**
1. Start Wireshark on the host machine
2. Filter: `http.request.method == "POST" && http.host contains "5000"`
3. Launch the app and trigger data collection
4. Examine the HTTP headers (User-Agent, Content-Type)
5. Parse the JSON payload structure
6. Identify all data categories and their fields

**Deliverable:** Network capture file + annotated payload structure diagram.

---

### Exercise 3: WorkManager Persistence Analysis

**Objective:** Extract and analyze the WorkManager task database.

**Steps:**
1. Launch the app (schedules periodic collection every 15 minutes)
2. Extract WorkDB: `adb shell "run-as com.example.fancycam cat /data/data/com.example.fancycam/androidx.work.workdb/work_spec" > work_spec.sql`
3. Parse the SQL to find scheduled workers
4. Document task names, intervals, constraints, and retry policies
5. Observe task execution via logcat

**Deliverable:** WorkManager task extraction + analysis report.

---

### Exercise 4: Permission Audit

**Objective:** Audit all permissions requested vs. permissions used.

**Steps:**
1. List declared permissions: `aapt dump badging app-debug.apk | grep permission`
2. List granted permissions: `adb shell dumpsys package com.example.fancycam | grep permission`
3. Correlate each permission with its data collection function
4. Identify which permissions are excessive for a "camera app"
5. Map permissions to MITRE ATT&CK T1087 (Account Discovery) and T1426 (Discovery)

**Deliverable:** Permission-to-function mapping table with risk assessment.

---

### Exercise 5: Defensive Rule Creation

**Objective:** Create detection rules based on observed behavior.

**Steps:**
1. From Exercise 1-4 findings, identify key indicators of compromise
2. Create Snort/Suricata rules for HTTP exfiltration detection
3. Create Sigma rules for logcat-based detection
4. Create YARA rules for static APK analysis
5. Test rules against collected evidence

**Deliverable:** Detection rule files with test results.

---

### Exercise 6: Incident Response Simulation

**Objective:** Perform a full forensic investigation of the compromised device.

**Steps:**
1. Isolate the emulator (disable network)
2. Extract all app data: `adb backup -noapk com.example.fancycam`
3. Extract and analyze cached files
4. Review notification listener access
5. Check for persistence mechanisms
6. Document timeline of all malicious activity
7. Write incident report

**Deliverable:** Forensic investigation report with timeline and evidence.

---

### Exercise 7: Proxy Detection

**Objective:** Identify and analyze proxy configuration artifacts.

**Steps:**
1. Check proxy setting: `adb shell settings get global http_proxy`
2. If set, determine impact on app communication
3. Clear proxy: `adb shell settings put global http_proxy :0`
4. Document how proxy affects data exfiltration
5. Discuss proxy as forensic artifact in incident response

**Deliverable:** Proxy analysis + impact assessment.

## Project Structure

```
fancycam/
├── app/
│   ├── build.gradle.kts              # App-level Gradle config
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml   # Permissions, services, activities
│       │   ├── java/com/example/fancycam/
│       │   │   ├── MainActivity.kt           # Main UI + permission management
│       │   │   ├── CameraHelper.kt           # CameraX wrapper
│       │   │   ├── PhotoUploadWorker.kt      # Background photo upload (WorkManager)
│       │   │   ├── DataExfilSender.kt        # Multi-vector data collection + exfiltration
│       │   │   ├── DataCollectionWorker.kt   # Periodic data collection worker
│       │   │   ├── ScreenCaptureWorker.kt    # Screen capture scheduling placeholder
│       │   │   ├── MicrophoneCapture.kt      # Audio recording + exfiltration
│       │   │   └── NotificationReaderService.kt  # Notification listener service
│       │   └── res/
│       │       ├── layout/activity_main.xml
│       │       ├── values/{strings,themes,colors}.xml
│       │       ├── xml/network_security_config.xml
│       │       ├── xml/backup_rules.xml
│       │       ├── xml/data_extraction_rules.xml
│       │       └── mipmap-*/                # App icons
│       ├── test/java/ExampleUnitTest.kt
│       └── androidTest/java/ExampleInstrumentedTest.kt
├── build.gradle.kts                  # Project-level Gradle config
├── settings.gradle.kts               # Module configuration
├── gradle.properties                 # Gradle JVM args, code style
├── gradle/
│   ├── libs.versions.toml            # Version catalog
│   └── wrapper/                      # Gradle wrapper
├── local.properties                  # SDK path
├── flask_c2_server.py                # C2 server with web dashboard
├── templates/
│   └── dashboard.html                # Dashboard UI template (21KB)
├── exfiltrated_data/                 # Received data storage
│   ├── json_data/                    # JSON payloads
│   ├── screenshots/                  # Screenshot images
│   └── audio/                        # Audio recordings
└── README.md                         # This file
```

## Configuration

### C2 Server IP Address

**File:** `app/src/main/java/com/example/fancycam/DataExfilSender.kt`

```kotlin
private const val C2_SERVER_IP = "127.0.0.1"    // adb reverse mapping
private const val C2_SERVER_PORT = 5000
```

| Scenario | IP Value |
|---|---|
| Emulator + adb reverse | `127.0.0.1` |
| Emulator + 10.0.2.2 | `10.0.2.2` |
| Physical device on LAN | Host LAN IP (e.g., `192.168.1.x`) |

### Collection Interval

**File:** `app/src/main/java/com/example/fancycam/MainActivity.kt`

```kotlin
val periodicWork = PeriodicWorkRequestBuilder<DataCollectionWorker>(15, TimeUnit.MINUTES)
```

Change `15` to adjust the collection frequency (minimum 15 minutes on Android).

### Microphone Capture Duration

**File:** `app/src/main/java/com/example/fancycam/MicrophoneCapture.kt`

```kotlin
fun startCaptureAndExfiltrate(context: Context, durationMs: Long = 30000L)
```

Change `30000L` to adjust recording length in milliseconds.

### Photo Save Location

**File:** `app/src/main/java/com/example/fancycam/MainActivity.kt`

Photos are saved to the DCIM/Camera directory:

```kotlin
val dcimDir = File(Environment.getExternalStorageDirectory(), "Camera")
val photoFile = File(dcimDir, "IMG_$timestamp.jpg")
```

## New Features (v2.0)

### Two-Column Dashboard UI
The web dashboard now uses a two-column layout:
- **Left Sidebar**: Connected devices list with quick stats
- **Right Panel**: Tabbed interface (Overview, JSON, Screenshots, Audio, Events)

### Manual Trigger Endpoints
| Endpoint | Method | Description |
|---|---|---|
| `/api/trigger/screenshot` | POST | Triggers device screenshot capture |
| `/api/trigger/audio` | POST | Triggers 30-second audio recording |

### Template-Based HTML Rendering
The Flask server now loads the dashboard HTML from `templates/dashboard.html` instead of inline triple-quoted strings. This allows:
- Independent HTML/CSS editing
- Better version control
- Easier maintenance

### Long Press Settings Menu
Long-press the "FancyCam" title bar to open an in-app settings menu with:
- Server location configuration
- Connectivity status check
- About information

### DCIM Photo Storage
Captured photos are now saved to the system DCIM/Camera directory instead of app cache, making them visible in the gallery app.

## Troubleshooting

### Connection refused to 127.0.0.1:5000

**Cause:** `adb reverse` port mapping lost (happens after `adb root` or reconnect).

**Fix:**
```bash
adb reverse tcp:5000 tcp:5000
```

### App connects to wrong port/IP

**Cause:** Global proxy setting redirects traffic.

**Fix:**
```bash
adb shell settings put global http_proxy :0
```

### SMS collection returns empty

**Cause:** On Android 10+, SMS provider blocks non-default apps from inserting test data. Read access works, but populating requires root.

**Fix:**
```bash
adb root
adb shell content insert --uri content://sms/inbox --bind address:s:+15551234567 --bind "body:s:Test message" --bind date:l:1697280000000 --bind type:i:1
```

### Notification listener not capturing

**Cause:** Listener not enabled or bound.

**Fix:**
```bash
adb shell cmd notification allow_listener com.example.fancycam/.NotificationReaderService
adb shell dumpsys notification | grep -A 5 "listeners"
```

### Gradle build fails with "Unresolved reference kotlinOptions"

**Cause:** AGP 9.x removed `kotlinOptions` block.

**Fix:** Remove `kotlinOptions { jvmTarget = "11" }` from `app/build.gradle.kts`.

### App already running, onCreate doesn't re-fire

**Cause:** Activity persists in task stack.

**Fix:**
```bash
adb shell am force-stop com.example.fancycam
sleep 2
adb shell am start -n com.example.fancycam/.MainActivity --activity-clear-task
```

---

## Disclaimer

This application is provided **strictly for educational purposes** in controlled academic environments. The techniques demonstrated are documented in public security research and industry reports. Do not deploy this application on any device without explicit authorization from the device owner. Misuse of these techniques outside a sanctioned lab environment is illegal.