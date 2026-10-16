package com.example.fancycam.network

import android.content.Context
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object ScreenshotUploader {
    private const val TAG = "ScreenshotUploader"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UploadResult(
        val success: Boolean,
        val serverFilename: String = "",
        val message: String = ""
    )

    suspend fun uploadScreenshot(context: Context, file: File): UploadResult = withContext(Dispatchers.IO) {
        if (!file.exists() || file.length() == 0L) {
            return@withContext UploadResult(false, message = "File does not exist or is empty")
        }

        val endpoints = AppConfig.getScreenshotUploadEndpoints(context)
        var lastError = "Could not reach server"

        for (endpoint in endpoints) {
            try {
                val aid = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "screenshot",
                        file.name,
                        file.asRequestBody("image/png".toMediaType())
                    )
                    .apply {
                        if (aid.isNotEmpty()) {
                            addFormDataPart("android_id", aid)
                        }
                    }
                    .build()

                val requestBuilder = Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .addHeader("User-Agent", "FancyCam-Android/1.0")

                if (aid.isNotEmpty()) {
                    requestBuilder.addHeader("X-Android-ID", aid)
                }

                val response = httpClient.newCall(requestBuilder.build()).execute()
                response.use {
                    if (it.isSuccessful) {
                        val body = it.body?.string().orEmpty()
                        var serverFile = ""
                        try {
                            val json = JSONObject(body)
                            serverFile = json.optString("filename", "")
                        } catch (_: Exception) {
                        }
                        return@withContext UploadResult(true, serverFilename = serverFile, message = "Uploaded successfully")
                    }
                    lastError = "Server HTTP error ${it.code}"
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Connection error"
                Log.w(TAG, "Endpoint failed: $lastError")
            }
        }
        UploadResult(false, message = lastError)
    }
}
