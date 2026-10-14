package com.example.fancycam

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PhotoDetails(
    val name: String,
    val path: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int,
    val dateModified: Long
) {
    val formattedSize: String
        get() {
            return if (sizeBytes >= 1024 * 1024) {
                String.format(Locale.US, "%.2f MB", sizeBytes / (1024.0 * 1024.0))
            } else {
                String.format(Locale.US, "%.1f KB", sizeBytes / 1024.0)
            }
        }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(dateModified))
        }

    val dimensionsText: String
        get() = if (width > 0 && height > 0) "${width} × ${height}" else "Unknown"
}

object PhotoStorageManager {
    private const val TAG = "PhotoStorageManager"

    fun getStorageDirectories(context: Context): List<File> {
        val dirs = mutableListOf<File>()

        // 1. DCIM/Camera (system camera folder, visible in gallery)
        try {
            val dcim = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
            if (!dcim.exists()) dcim.mkdirs()
            if (dcim.canWrite() || dcim.exists()) dirs.add(dcim)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access DCIM/Camera: ${e.message}")
        }

        // 2. Pictures/FancyCam
        try {
            val pictures = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "FancyCam")
            if (!pictures.exists()) pictures.mkdirs()
            if (pictures.canWrite() || pictures.exists()) dirs.add(pictures)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot access Pictures/FancyCam: ${e.message}")
        }

        // 3. App-specific external storage fallback
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
            if (!it.exists()) it.mkdirs()
            dirs.add(it)
        }

        // 4. Cache fallback
        dirs.add(context.cacheDir)
        return dirs
    }

    fun createNewPhotoFile(context: Context): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "IMG_$timestamp.jpg"

        val availableDirs = getStorageDirectories(context)
        val targetDir = availableDirs.firstOrNull { it.canWrite() || it.exists() } ?: context.cacheDir
        targetDir.mkdirs()

        var photoFile = File(targetDir, fileName)
        var counter = 1
        while (photoFile.exists()) {
            photoFile = File(targetDir, "IMG_${timestamp}_$counter.jpg")
            counter++
        }

        return photoFile
    }

    fun indexPhotoInStorage(context: Context, file: File, onComplete: (Uri?) -> Unit = {}) {
        try {
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                arrayOf("image/jpeg")
            ) { path, uri ->
                Log.d(TAG, "MediaScanner indexed: $path -> $uri")
                onComplete(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan photo into MediaStore", e)
            onComplete(null)
        }
    }

    fun getLatestPhoto(context: Context): File? {
        val dirs = getStorageDirectories(context)
        var latest: File? = null
        var latestTime = 0L

        for (dir in dirs) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f ->
                    f.isFile && (f.extension.equals("jpg", ignoreCase = true) || f.extension.equals("jpeg", ignoreCase = true))
                } ?: continue

                for (f in files) {
                    val mtime = f.lastModified()
                    if (mtime > latestTime && f.length() > 0) {
                        latestTime = mtime
                        latest = f
                    }
                }
            }
        }
        return latest
    }

    fun getPhotoDetails(file: File): PhotoDetails {
        var width = 0
        var height = 0
        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            width = options.outWidth
            height = options.outHeight
        } catch (e: Exception) {
            Log.w(TAG, "Could not decode photo bounds: ${e.message}")
        }

        return PhotoDetails(
            name = file.name,
            path = file.absolutePath,
            sizeBytes = file.length(),
            width = width,
            height = height,
            dateModified = file.lastModified()
        )
    }
}
