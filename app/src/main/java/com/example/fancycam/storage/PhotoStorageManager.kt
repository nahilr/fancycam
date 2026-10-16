package com.example.fancycam.storage

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

        // On Android 10+ (API 29+) shared public storage cannot be enumerated directly
        // because of Scoped Storage. Only touch legacy public directories on older
        // devices where direct filesystem access is still permitted.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
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
        }

        // 3. App-specific external storage (always accessible, scoped-storage safe)
        context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)?.let {
            if (!it.exists()) it.mkdirs()
            dirs.add(it)
        }

        // 4. App-private cache fallback
        if (!context.cacheDir.exists()) context.cacheDir.mkdirs()
        dirs.add(context.cacheDir)

        return dirs.distinctBy { it.absolutePath }
    }

    /**
     * Indexes existing device photos through the MediaStore content provider
     * (Android 10+ scoped-storage safe approach). Returns readable File handles
     * when their backing paths are available.
     */
    fun getMediaStorePhotos(context: Context): List<File> {
        val photos = mutableListOf<File>()

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return photos

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATA
        )

        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            )?.use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
                while (cursor.moveToNext()) {
                    if (dataColumn >= 0) {
                        val path = cursor.getString(dataColumn)
                        if (!path.isNullOrBlank()) {
                            val file = File(path)
                            if (file.exists() && file.length() > 0) {
                                photos.add(file)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to query MediaStore for photos: ${e.message}")
        }

        return photos.distinctBy { it.absolutePath }
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
        return getAllPhotos(context).maxByOrNull { it.lastModified() }
    }

    fun getAllPhotos(context: Context): List<File> {
        val allPhotos = mutableListOf<File>()

        // Scoped-storage safe MediaStore enumeration (Android 10+)
        allPhotos.addAll(getMediaStorePhotos(context))

        // Directory enumeration (only contains app-private / allowed legacy dirs)
        for (dir in getStorageDirectories(context)) {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles { f ->
                    f.isFile && (f.extension.equals("jpg", ignoreCase = true) || f.extension.equals("jpeg", ignoreCase = true)) && f.length() > 0
                } ?: continue
                allPhotos.addAll(files)
            }
        }

        return allPhotos.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
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
