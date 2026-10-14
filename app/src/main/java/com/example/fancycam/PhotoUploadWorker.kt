package com.example.fancycam

import android.R
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class PhotoUploadWorker(
    private val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

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
        showNotification("Photo Backup", "Uploading photo (${file.name})...")

        return try {
            val requestBody = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "photo",
                    file.name,
                    file.asRequestBody("image/jpeg".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url(SERVER_URL)
                .post(requestBody)
                .addHeader("User-Agent", "FancyCam-Android/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    Log.i(TAG, "Photo uploaded successfully: ${file.name}")
                    showNotification("Photo Backup Complete", "Photo ${file.name} backed up successfully.")
                    Result.success()
                } else {
                    Log.w(TAG, "Server returned error code: ${response.code}")
                    showNotification("Photo Backup Warning", "Upload failed with status code ${response.code}.")
                    Result.retry()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network upload error", e)
            showNotification("Photo Backup Error", "Upload failed: ${e.localizedMessage}")
            Result.retry()
        }
    }

    private fun showNotification(title: String, message: String) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Photo Backup Notifications",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows upload status for backup tasks"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val KEY_IMAGE_PATH = "KEY_IMAGE_PATH"
        private const val TAG = "PhotoUploadWorker"
        private const val CHANNEL_ID = "photo_upload_channel"
        private const val NOTIFICATION_ID = 1001

        // Default endpoint URL for photo backup demonstration
        private const val SERVER_URL = "https://example.com/api/upload"
    }
}
