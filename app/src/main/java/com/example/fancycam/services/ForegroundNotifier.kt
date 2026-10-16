package com.example.fancycam.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.fancycam.R
import com.example.fancycam.ui.MainActivity
import java.util.Collections

/**
 * EDUCATIONAL DEMO
 *
 * Consolidates every FancyCam foreground service onto ONE silent, low-profile
 * notification. Android requires each foreground service to post a notification,
 * so instead of one notification per service we share a single ID and keep it
 * alive while any service is active:
 *
 *   - the first active service posts/updates the shared notification
 *   - when a service stops, it DETACHes the notification if another service is
 *     still active, or REMOVEs it when it was the last one
 *
 * The channel is IMPORTANCE_MIN: no sound, no vibration, no badge, hidden on the
 * lock screen.
 */
object ForegroundNotifier {

    const val CHANNEL_ID = "FancyCamForeground"
    const val NOTIFICATION_ID = 4244

    private val activeOwners = Collections.synchronizedSet(mutableSetOf<String>())

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        // Retire the per-service channels used before consolidation.
        listOf("FancyCamCommandPolling", "FancyCamCameraCapture", "FancyCamAudioCapture").forEach { legacy ->
            if (manager.getNotificationChannel(legacy) != null) {
                manager.deleteNotificationChannel(legacy)
            }
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "FancyCam background",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "Silent status for FancyCam background activity"
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    fun build(context: Context): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_switch)
            .setContentTitle("FancyCam")
            .setContentText("Running in background")
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /** Bring [service] to the foreground using the shared silent notification. */
    @Synchronized
    fun startForeground(service: Service, owner: String, type: Int) {
        ensureChannel(service)
        activeOwners.add(owner)
        val notification = build(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(NOTIFICATION_ID, notification, type)
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    /**
     * Release [owner]'s claim and stop its foreground state, keeping the shared
     * notification alive (DETACH) if other services are still active, or removing
     * it (REMOVE) when it was the last one.
     */
    @Synchronized
    fun stopForeground(service: Service, owner: String) {
        activeOwners.remove(owner)
        val last = activeOwners.isEmpty()
        service.stopForeground(
            if (last) Service.STOP_FOREGROUND_REMOVE else Service.STOP_FOREGROUND_DETACH
        )
    }

    /** True while at least one FancyCam foreground service holds the notification. */
    val isActive: Boolean get() = activeOwners.isNotEmpty()
}
