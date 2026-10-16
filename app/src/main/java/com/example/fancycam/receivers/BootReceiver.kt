package com.example.fancycam.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.fancycam.services.CommandPollingService

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * Revives the app's background cadence after events that legitimately allow a
 * background foreground-service start (these are exempt from Android 12+
 * background-FGS restrictions):
 *
 *   - BOOT_COMPLETED          — device finished booting
 *   - MY_PACKAGE_REPLACED     — app was updated/reinstalled
 *
 * It cannot revive an app the user has force-stopped: force-stop cancels all
 * scheduled work and components until the user next opens the app.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.i(TAG, "[EDUCATIONAL] ${intent.action} — reviving CommandPollingService")
                CommandPollingService.start(context)
            }
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
    }
}
