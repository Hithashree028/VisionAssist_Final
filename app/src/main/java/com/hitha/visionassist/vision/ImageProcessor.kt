package com.hitha.visionassist.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/**
 * Utility for memory-safe image processing, downsampling, EXIF orientation normalization,
 * and lifecycle cleanup of temporary files.
 */
class ImageProcessor(private val context: Context) {

    /**
     * Decodes and downsamples an image file safely without OutOfMemoryError,
     * correcting for EXIF rotation.
     */
    fun decodeAndNormalizeBitmap(file: File, maxDimension: Int = 1280): Bitmap? {
        if (!file.exists() || file.length() == 0L) return null

        try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)
            val origWidth = boundsOptions.outWidth
            val origHeight = boundsOptions.outHeight
            if (origWidth <= 0 || origHeight <= 0) return null

            var sampleSize = 1
            while (origWidth / sampleSize > maxDimension || origHeight / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null

            return correctExifOrientation(file.absolutePath, decoded)
        } catch (_: Throwable) {
            return null
        }
    }

    /**
     * Compresses the bitmap to JPEG byte array for network transmission.
     */
    fun compressBitmapToJpeg(bitmap: Bitmap, quality: Int = 80): ByteArray {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        return stream.toByteArray()
    }

    /**
     * Compresses file to JPEG byte array with safe downsampling.
     */
    fun compressFileForUpload(file: File, maxDimension: Int = 1280, quality: Int = 80): ByteArray {
        val bitmap = decodeAndNormalizeBitmap(file, maxDimension)
            ?: throw IllegalStateException("Failed to decode image file")
        return try {
            compressBitmapToJpeg(bitmap, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Processes an image from Android's photo picker / gallery Uri into a managed cache file.
     */
    fun processSelectedUri(uri: Uri, targetFile: File): Boolean {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri) ?: return false
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(inputStream, null, bounds)
            inputStream.close()

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > 1280 || bounds.outHeight / sampleSize > 1280) {
                sampleSize *= 2
            }

            val secondStream = context.contentResolver.openInputStream(uri) ?: return false
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeStream(secondStream, null, decodeOptions)
            secondStream.close()

            if (bitmap == null) return false

            targetFile.parentFile?.mkdirs()
            FileOutputStream(targetFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
            bitmap.recycle()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes temporary files in the visionassist cache folder to avoid storage clutter.
     */
    fun cleanupTemporaryFiles(vararg filesToKeep: File?) {
        try {
            val keepSet = filesToKeep.filterNotNull().map { it.absolutePath }.toSet()
            val cacheFolders = listOf(
                File(context.cacheDir, "visionassist_captures"),
                File(context.cacheDir, "visionassist_uploads")
            )
            for (folder in cacheFolders) {
                if (folder.exists() && folder.isDirectory) {
                    folder.listFiles()?.forEach { file ->
                        if (!keepSet.contains(file.absolutePath)) {
                            file.delete()
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Non-critical cleanup
        }
    }

    private fun correctExifOrientation(filePath: String, bitmap: Bitmap): Bitmap {
        try {
            val exif = ExifInterface(filePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                else -> return bitmap
            }

            val transformed = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
            )
            if (transformed != bitmap) {
                bitmap.recycle()
            }
            return transformed
        } catch (_: Throwable) {
            return bitmap
        }
    }
}
