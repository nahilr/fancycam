package com.example.fancycam

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
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

    override fun onResume() {
        super.onResume()
        startActiveHealthPolling()
        loadRecentActivities()
    }

    override fun onPause() {
        super.onPause()
        stopActiveHealthPolling()
    }

    companion object {
        private const val TAG = "SettingsActivity"
    }
}
