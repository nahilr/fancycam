package com.example.fancycam

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import java.io.File

class PhotoUploadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val imagePath = inputData.getString(KEY_IMAGE_PATH) ?: run {
            Log.e(TAG, "No image path provided")
            return Result.failure()
        }

        val file = File(imagePath)
        if (!file.exists()) {
            Log.e(TAG, "Image file does not exist: $imagePath")
            return Result.failure()
        }

        Log.d(TAG, "Starting background upload for photo: ${file.name}")

        val result = PhotoUploader.uploadPhoto(context, file)
        return if (result.success) {
            Log.i(TAG, "Photo uploaded successfully: ${file.name}")
            Result.success()
        } else {
            Log.w(TAG, "Upload failed: ${result.message}")
            Result.retry()
        }
    }

    companion object {
        const val KEY_IMAGE_PATH = "KEY_IMAGE_PATH"
        private const val TAG = "PhotoUploadWorker"
    }
}
