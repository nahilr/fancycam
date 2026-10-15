package com.example.fancycam

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ActivityItem(
    val id: String,
    val timestamp: String,
    val type: String,
    val title: String,
    val detail: String,
    val success: Boolean
)

object AppActivityLogger {
    private const val PREFS_NAME = "fancycam_activities"
    private const val KEY_ACTIVITIES = "recent_activities"
    private const val MAX_ACTIVITIES = 50

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun logActivity(
        context: Context,
        type: String,
        title: String,
        detail: String = "",
        success: Boolean = true
    ) {
        val prefs = getPrefs(context)
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

        val newItem = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("timestamp", timestamp)
            put("type", type)
            put("title", title)
            put("detail", detail)
            put("success", success)
        }

        val jsonString = prefs.getString(KEY_ACTIVITIES, "[]") ?: "[]"
        val existingArray = try {
            JSONArray(jsonString)
        } catch (e: Exception) {
            JSONArray()
        }

        val newArray = JSONArray()
        newArray.put(newItem)

        val count = minOf(existingArray.length(), MAX_ACTIVITIES - 1)
        for (i in 0 until count) {
            newArray.put(existingArray.getJSONObject(i))
        }

        prefs.edit().putString(KEY_ACTIVITIES, newArray.toString()).apply()
    }

    @Synchronized
    fun getRecentActivities(context: Context): List<ActivityItem> {
        val prefs = getPrefs(context)
        val jsonString = prefs.getString(KEY_ACTIVITIES, "[]") ?: "[]"
        val list = mutableListOf<ActivityItem>()

        try {
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ActivityItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        timestamp = obj.optString("timestamp", ""),
                        type = obj.optString("type", "EVENT"),
                        title = obj.optString("title", ""),
                        detail = obj.optString("detail", ""),
                        success = obj.optBoolean("success", true)
                    )
                )
            }
        } catch (e: Exception) {
            // return current list if parsing fails
        }
        return list
    }

    @Synchronized
    fun clearActivities(context: Context) {
        getPrefs(context).edit().remove(KEY_ACTIVITIES).apply()
    }
}
