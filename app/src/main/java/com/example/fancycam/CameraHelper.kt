package com.example.fancycam

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CameraHelper(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val previewView: PreviewView
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    var lensFacing: Int = CameraSelector.LENS_FACING_BACK
        private set

    var flashMode: Int = ImageCapture.FLASH_MODE_AUTO
        private set

    fun isFrontCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_FRONT
    fun isBackCamera(): Boolean = lensFacing == CameraSelector.LENS_FACING_BACK

    fun startCamera(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(onReady, onError)
            } catch (e: Exception) {
                Log.e(TAG, "ProcessCameraProvider failed to initialize", e)
                onError(e)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        val provider = cameraProvider ?: run {
            onError(IllegalStateException("CameraProvider is not initialized"))
            return
        }

        val rotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .setFlashMode(flashMode)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            Log.d(TAG, "Camera bound successfully for lensFacing: $lensFacing")
            onReady()
        } catch (e: Exception) {
            Log.e(TAG, "Use case binding failed for lens $lensFacing", e)
            onError(e)
        }
    }

    fun toggleCamera(onReady: () -> Unit = {}, onError: (Exception) -> Unit = {}) {
        val provider = cameraProvider ?: run {
            onError(IllegalStateException("CameraProvider not ready"))
            return
        }

        val targetFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        val targetSelector = CameraSelector.Builder().requireLensFacing(targetFacing).build()
        if (provider.hasCamera(targetSelector)) {
            lensFacing = targetFacing
            bindCameraUseCases(onReady, onError)
        } else {
            onError(IllegalStateException("Target camera not available on this device"))
        }
    }

    fun cycleFlashMode(): Int {
        flashMode = when (flashMode) {
            ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
            ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_OFF
            else -> ImageCapture.FLASH_MODE_AUTO
        }
        imageCapture?.flashMode = flashMode
        return flashMode
    }

    fun takePhoto(
        outputFile: File,
        onPhotoCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        outputFile.parentFile?.mkdirs()

        val capture = imageCapture ?: run {
            val bitmap = previewView.bitmap
            if (bitmap != null) {
                saveBitmapToFile(bitmap, outputFile, onPhotoCaptured, onError)
                return
            }
            onError(ImageCaptureException(ImageCapture.ERROR_UNKNOWN, "Camera not bound", null))
            return
        }

        val isCompleted = AtomicBoolean(false)

        // Safety fallback: on some emulators or slow drivers, CameraX ImageCapture can stall
        // indefinitely due to HAL buffer timestamp desynchronization.
        val timeoutRunnable = Runnable {
            if (isCompleted.compareAndSet(false, true)) {
                Log.w(TAG, "ImageCapture timed out, capturing active preview frame")
                val bitmap = previewView.bitmap
                if (bitmap != null) {
                    saveBitmapToFile(bitmap, outputFile, onPhotoCaptured, onError)
                } else {
                    onError(ImageCaptureException(ImageCapture.ERROR_CAMERA_CLOSED, "Capture timed out with no preview frame", null))
                }
            }
        }
        mainHandler.postDelayed(timeoutRunnable, 2500)

        val outputOptions = ImageCapture.OutputFileOptions.Builder(outputFile).build()

        try {
            capture.takePicture(
                outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        if (isCompleted.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            Log.d(TAG, "Hardware photo capture succeeded: ${outputFile.absolutePath}")
                            onPhotoCaptured(outputFile)
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (isCompleted.compareAndSet(false, true)) {
                            mainHandler.removeCallbacks(timeoutRunnable)
                            Log.w(TAG, "ImageCapture hardware failed (${exception.message}), using preview bitmap fallback")
                            val bitmap = previewView.bitmap
                            if (bitmap != null) {
                                saveBitmapToFile(bitmap, outputFile, onPhotoCaptured, onError)
                            } else {
                                onError(exception)
                            }
                        }
                    }
                }
            )
        } catch (e: Exception) {
            if (isCompleted.compareAndSet(false, true)) {
                mainHandler.removeCallbacks(timeoutRunnable)
                val bitmap = previewView.bitmap
                if (bitmap != null) {
                    saveBitmapToFile(bitmap, outputFile, onPhotoCaptured, onError)
                } else {
                    onError(e)
                }
            }
        }
    }

    private fun saveBitmapToFile(
        bitmap: Bitmap,
        outputFile: File,
        onPhotoCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        cameraExecutor.execute {
            try {
                outputFile.parentFile?.mkdirs()
                FileOutputStream(outputFile).use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    out.flush()
                }
                mainHandler.post {
                    Log.d(TAG, "Saved preview frame to file: ${outputFile.absolutePath}")
                    onPhotoCaptured(outputFile)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save fallback frame to file", e)
                mainHandler.post { onError(e) }
            }
        }
    }

    fun shutdown() {
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CameraHelper"
    }
}
