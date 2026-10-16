package xyz.nahil.fancycam.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.annotation.StringRes
import androidx.exifinterface.media.ExifInterface
import xyz.nahil.fancycam.R
import java.io.File
import java.io.FileOutputStream

enum class PhotoFilter(@param:StringRes val labelRes: Int) {
    NORMAL(R.string.photo_filter_normal),
    VIBRANT(R.string.photo_filter_vibrant),
    COOL(R.string.photo_filter_cool),
    BLACK_AND_WHITE(R.string.photo_filter_bw)
}

object PhotoFilterProcessor {
    /** Applies a look to a saved JPEG while retaining its EXIF orientation. */
    fun apply(file: File, filter: PhotoFilter) {
        if (filter == PhotoFilter.NORMAL) return

        val orientation = ExifInterface(file.absolutePath)
            .getAttribute(ExifInterface.TAG_ORIENTATION)
        val source = BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        ) ?: throw IllegalArgumentException("Could not decode captured photo")

        val tempFile = File(file.parentFile, "${file.nameWithoutExtension}.filtering.jpg")
        val backupFile = File(file.parentFile, "${file.name}.unfiltered")
        var filtered: Bitmap? = null

        try {
            val outputBitmap = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
            filtered = outputBitmap
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(colorMatrix(filter))
            }
            Canvas(outputBitmap).drawBitmap(source, 0f, 0f, paint)

            FileOutputStream(tempFile).use { output ->
                check(outputBitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                    "Could not encode filtered photo"
                }
            }
            if (orientation != null) {
                ExifInterface(tempFile.absolutePath).apply {
                    setAttribute(ExifInterface.TAG_ORIENTATION, orientation)
                    saveAttributes()
                }
            }

            check(file.renameTo(backupFile)) { "Could not stage original photo for filtering" }
            if (!tempFile.renameTo(file)) {
                backupFile.renameTo(file)
                error("Could not replace captured photo with filtered photo")
            }
            backupFile.delete()
        } finally {
            source.recycle()
            filtered?.recycle()
            tempFile.delete()
            if (backupFile.exists() && !file.exists()) backupFile.renameTo(file)
        }
    }

    private fun colorMatrix(filter: PhotoFilter): ColorMatrix = when (filter) {
        PhotoFilter.NORMAL -> ColorMatrix()
        PhotoFilter.VIBRANT -> ColorMatrix().apply {
            setSaturation(1.4f)
            postConcat(ColorMatrix(floatArrayOf(
                1.08f, 0f, 0f, 0f, -8f,
                0f, 1.08f, 0f, 0f, -8f,
                0f, 0f, 1.08f, 0f, -8f,
                0f, 0f, 0f, 1f, 0f
            )))
        }
        PhotoFilter.COOL -> ColorMatrix(floatArrayOf(
            0.94f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1.12f, 0f, 8f,
            0f, 0f, 0f, 1f, 0f
        ))
        PhotoFilter.BLACK_AND_WHITE -> ColorMatrix().apply { setSaturation(0f) }
    }
}
