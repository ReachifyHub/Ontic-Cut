package com.boostlyte.vydra.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.util.Log
import android.util.LruCache
import com.boostlyte.vydra.models.EditOperation
import com.boostlyte.vydra.models.VideoProject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * ImagePreviewCache provides bounded, high-performance in-memory caching and downsampled
 * decoding for image clips during preview playback and timeline scrubbing.
 *
 * It prevents OutOfMemoryError and UI thread stalling when scrubbing or playing
 * projects with 100 to 500+ high-resolution camera photos.
 */
object ImagePreviewCache {

    private const val TAG = "ImagePreviewCache"

    // Max 32MB for full preview images (1280x720 RGB_565 is ~1.8MB per frame; holds ~16-20 frames)
    private val maxPreviewMemoryBytes = minOf(32 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 8).toInt())

    // Max 8MB for timeline strip thumbnails (96x96 RGB_565 is ~18KB per thumbnail; holds ~400+ thumbnails)
    private val maxThumbMemoryBytes = minOf(8 * 1024 * 1024, (Runtime.getRuntime().maxMemory() / 16).toInt())

    private val previewCache = object : LruCache<String, Bitmap>(maxPreviewMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private val thumbnailCache = object : LruCache<String, Bitmap>(maxThumbMemoryBytes) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun getPreviewBitmap(uri: Uri): Bitmap? {
        return previewCache.get(uri.toString())
    }

    fun getPreviewBitmap(
        context: Context,
        uri: Uri,
        targetWidth: Int = 1280,
        targetHeight: Int = 720
    ): Bitmap? {
        val cached = previewCache.get(uri.toString())
        if (cached != null) return cached
        val decoded = decodeDownsampledBitmap(context, uri, targetWidth, targetHeight)
        if (decoded != null) {
            previewCache.put(uri.toString(), decoded)
        }
        return decoded
    }

    fun putPreviewBitmap(uri: Uri, bitmap: Bitmap) {
        previewCache.put(uri.toString(), bitmap)
    }

    fun getThumbnailBitmap(uri: Uri): Bitmap? {
        return thumbnailCache.get(uri.toString())
    }

    fun putThumbnailBitmap(uri: Uri, bitmap: Bitmap) {
        thumbnailCache.put(uri.toString(), bitmap)
    }

    fun clear() {
        previewCache.evictAll()
        thumbnailCache.evictAll()
    }

    /**
     * Decodes an image downsampled to target dimensions using RGB_565 to conserve RAM.
     */
    fun decodeDownsampledBitmap(
        context: Context,
        uri: Uri,
        targetWidth: Int = 1280,
        targetHeight: Int = 720
    ): Bitmap? {
        var inputStream: InputStream? = null
        try {
            // First pass: decode bounds
            inputStream = openUriStream(context, uri) ?: return null
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()
            inputStream = null

            val rawWidth = options.outWidth
            val rawHeight = options.outHeight
            if (rawWidth <= 0 || rawHeight <= 0) return null

            // Calculate optimal sample size
            var inSampleSize = 1
            if (rawHeight > targetHeight || rawWidth > targetWidth) {
                val halfHeight = rawHeight / 2
                val halfWidth = rawWidth / 2
                while ((halfHeight / inSampleSize) >= targetHeight && (halfWidth / inSampleSize) >= targetWidth) {
                    inSampleSize *= 2
                }
            }

            // Second pass: decode with sample size and RGB_565 config
            inputStream = openUriStream(context, uri) ?: return null
            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = true
            }
            val sampledBitmap = BitmapFactory.decodeStream(inputStream, null, decodeOptions) ?: return null

            // Fix orientation using EXIF if necessary
            val rotatedBitmap = applyExifOrientation(context, uri, sampledBitmap)
            return rotatedBitmap
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode downsampled image from $uri: ${e.message}")
            return null
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Decodes a tiny thumbnail for timeline strip segments.
     */
    fun decodeThumbnail(context: Context, uri: Uri, targetSize: Int = 96): Bitmap? {
        return decodeThumbnail(context, uri, targetSize, targetSize)
    }

    fun decodeThumbnail(context: Context, uri: Uri, targetWidth: Int, targetHeight: Int): Bitmap? {
        val cached = getThumbnailBitmap(uri)
        if (cached != null) return cached

        val decoded = decodeDownsampledBitmap(context, uri, targetWidth, targetHeight)
        if (decoded != null) {
            putThumbnailBitmap(uri, decoded)
        }
        return decoded
    }

    /**
     * Prepares and warms preview assets in background for all image clips in the timeline.
     * Operates off the main thread and reports progress.
     */
    suspend fun preloadPreviewAssets(
        context: Context,
        imageUris: List<Uri>,
        targetWidth: Int = 1280,
        targetHeight: Int = 720,
        onProgress: ((completed: Int, total: Int) -> Unit)? = null
    ) = withContext(Dispatchers.IO) {
        val total = imageUris.size
        for ((index, uri) in imageUris.withIndex()) {
            if (previewCache.get(uri.toString()) == null) {
                val bmp = decodeDownsampledBitmap(context, uri, targetWidth, targetHeight)
                if (bmp != null) {
                    putPreviewBitmap(uri, bmp)
                }
            }
            onProgress?.invoke(index + 1, total)
        }
    }

    /**
     * Checks if a single media URI is accessible without throwing.
     */
    fun isUriAccessible(context: Context?, uri: Uri): Boolean {
        return try {
            val scheme = uri.scheme
            if (scheme == "file" || scheme == null) {
                val path = uri.path ?: return false
                File(path).exists()
            } else if (scheme == "content") {
                val cr = try { context?.contentResolver } catch (_: Exception) { null }
                if (cr != null) {
                    val cursor = try {
                        cr.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null,
                            null,
                            null
                        )
                    } catch (e: SecurityException) {
                        throw e
                    } catch (_: Exception) { null }
                    if (cursor != null) {
                        val count = cursor.count
                        cursor.close()
                        count > 0
                    } else {
                        try {
                            cr.openInputStream(uri)?.use { true } ?: false
                        } catch (e: SecurityException) {
                            throw e
                        } catch (_: Exception) { false }
                    }
                } else {
                    val path = uri.path
                    if (path != null) File(path).exists() else false
                }
            } else {
                true
            }
        } catch (e: SecurityException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    data class MediaAccessibilityReport(
        val missingUris: Set<Uri>,
        val hasPermissionRevocation: Boolean,
        val totalChecked: Int
    ) {
        val allValid: Boolean get() = missingUris.isEmpty()
    }

    /**
     * Validates all referenced media URIs across the project.
     */
    fun validateProjectMedia(context: Context, project: VideoProject): MediaAccessibilityReport {
        val missing = mutableSetOf<Uri>()
        var permissionRevoked = false
        var count = 0

        fun check(uri: Uri?) {
            if (uri == null) return
            count++
            try {
                if (!isUriAccessible(context, uri)) {
                    missing.add(uri)
                }
            } catch (e: SecurityException) {
                missing.add(uri)
                permissionRevoked = true
            } catch (_: Exception) {
                missing.add(uri)
            }
        }

        check(project.sourceUri)

        for (op in project.operations) {
            when (op) {
                is EditOperation.Merge -> {
                    for (item in op.items) {
                        check(item.uri)
                    }
                }
                is EditOperation.AddBackgroundAudio -> {
                    check(op.audioUri)
                }
                is EditOperation.AddImageOverlay -> {
                    check(op.imageUri)
                }
                is EditOperation.AddSubtitles -> {
                    check(op.subtitlesUri)
                }
                else -> {}
            }
        }

        // Also check if any content:// URI belongs to a revoked tree permission
        if (missing.isNotEmpty() && missing.any { it.scheme == "content" }) {
            try {
                val persistedPerms = context.contentResolver?.persistedUriPermissions
                val hasActivePerms = persistedPerms?.any { it.isReadPermission } == true
                if (!hasActivePerms) {
                    permissionRevoked = true
                }
            } catch (_: Exception) {}
        }

        return MediaAccessibilityReport(
            missingUris = missing,
            hasPermissionRevocation = permissionRevoked,
            totalChecked = count
        )
    }

    private fun openUriStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists()) FileInputStream(file) else null
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }

    private fun applyExifOrientation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        var inputStream: InputStream? = null
        try {
            val orientation = if (uri.scheme == "file") {
                val path = uri.path ?: return bitmap
                val exif = ExifInterface(path)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
                val exif = ExifInterface(inputStream)
                exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } else {
                ExifInterface.ORIENTATION_NORMAL
            }

            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            if (degrees != 0f) {
                val matrix = Matrix().apply { postRotate(degrees) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotated != bitmap) {
                    bitmap.recycle()
                }
                return rotated
            }
            return bitmap
        } catch (e: Exception) {
            return bitmap
        } finally {
            try {
                inputStream?.close()
            } catch (_: Exception) {}
        }
    }
}
