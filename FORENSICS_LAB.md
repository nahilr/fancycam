# Forensics and Lab Exercises

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
| **Notification Listener** | `adb shell settings get secure enabled_notification_listeners` | Presence of `xyz.nahil.fancycam/.services.NotificationReaderService` |
| **Foreground Polling Service** | `adb shell dumpsys activity services xyz.nahil.fancycam` | `CommandPollingService` with `isForeground=true` and an ongoing notification (id `4244`) |
| **Accessibility Screenshot Service** | `adb shell settings get secure enabled_accessibility_services` | Presence of `xyz.nahil.fancycam/.services.ScreenCaptureAccessibilityService` |
| **WorkManager Persistence** | `adb shell "run-as xyz.nahil.fancycam ls databases/"` | `androidx.work.workdb` with periodic task `edu_data_collection` |
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
  adb shell "run-as xyz.nahil.fancycam sqlite3 /data/data/xyz.nahil.fancycam/databases/androidx.work.workdb 'SELECT id, state, worker_class_name, interval_duration FROM workspec;'"
  ```

### Exercise 4: Forensic Timeline Reconstruction
- **Objective:** Correlate server exfiltration timestamps (`exfiltrated_data/`) with device MediaStore timestamps and system logs.
