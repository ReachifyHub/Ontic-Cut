package com.boostlyte.vydra.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.boostlyte.vydra.models.EditRecipe
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ProjectManager manages the dedicated project library for Vydra (.lcprj files).
 * It provides app-specific storage access that works without broad storage permissions,
 * performs fast non-blocking filesystem queries, safe atomic saving, collision-free naming,
 * thumbnail generation/storage, and project actions (rename, duplicate, delete).
 */
object ProjectManager {

    private const val TAG = "ProjectManager"
    const val PROJECT_EXTENSION = ".lcprj"
    const val THUMBNAIL_EXTENSION = ".thumb.jpg"
    private const val PROJECTS_DIR_NAME = "Projects"

    data class SavedProjectItem(
        val file: File,
        val name: String,
        val lastModified: Long,
        val formattedDate: String,
        val sizeBytes: Long = 0L,
        val durationMs: Long = 0L,
        val formattedDuration: String = "00:00",
        val clipCount: Int = 1,
        val thumbnailFile: File? = null
    )

    /**
     * Formats milliseconds into mm:ss or h:mm:ss.
     */
    fun formatDuration(durationMs: Long): String {
        if (durationMs <= 0L) return "00:00"
        val totalSeconds = durationMs / 1000
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
        }
    }

    /**
     * Returns the dedicated directory for saved projects.
     * Prefers app-specific external files (getExternalFilesDir("Projects")),
     * falling back to internal files (filesDir/Projects) if external storage is unavailable.
     */
    fun getProjectsDirectory(context: Context): File {
        val dir = context.getExternalFilesDir(PROJECTS_DIR_NAME)
            ?: File(context.filesDir, PROJECTS_DIR_NAME)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Returns the associated thumbnail file for a given project file.
     */
    fun getThumbnailFile(projectFile: File): File {
        return File(projectFile.parentFile ?: projectFile.absoluteFile.parentFile, "${projectFile.nameWithoutExtension}$THUMBNAIL_EXTENSION")
    }

    /**
     * Lists all .lcprj projects in the dedicated folder, sorted by last modified (newest first).
     * Extracts duration, clip count, and lightweight thumbnail if available.
     */
    fun listSavedProjects(context: Context): List<SavedProjectItem> {
        val dir = getProjectsDirectory(context)
        val files = dir.listFiles { file ->
            file.isFile && file.name.endsWith(PROJECT_EXTENSION, ignoreCase = true)
        } ?: return emptyList()

        val dateFormat = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())

        return files.map { file ->
            var durationMs = 0L
            var clipCount = 1
            try {
                val content = file.readText()
                val json = JSONObject(content)
                durationMs = json.optLong("durationMs", 0L)
                clipCount = json.optInt("clipCount", 1)
                if (clipCount <= 0) {
                    val ops = json.optJSONArray("operations")
                    var count = 1
                    if (ops != null) {
                        for (i in 0 until ops.length()) {
                            val op = ops.getJSONObject(i)
                            if (op.optString("type") == "Merge") {
                                val items = op.optJSONArray("items")
                                if (items != null) count += items.length()
                            }
                        }
                    }
                    clipCount = count
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read recipe metadata for ${file.name}: ${e.message}")
            }

            val thumbFile = getThumbnailFile(file).takeIf { it.exists() && it.length() > 0 }

            SavedProjectItem(
                file = file,
                name = file.nameWithoutExtension,
                lastModified = file.lastModified(),
                formattedDate = dateFormat.format(Date(file.lastModified())),
                sizeBytes = file.length(),
                durationMs = durationMs,
                formattedDuration = formatDuration(durationMs),
                clipCount = maxOf(1, clipCount),
                thumbnailFile = thumbFile
            )
        }.sortedByDescending { it.lastModified }
    }

    /**
     * Generates a collision-free project file inside the dedicated folder.
     * Uses default "Project 1", "Project 2" or adds numeric suffixes to existing titles.
     */
    fun generateUniqueProjectFile(context: Context, desiredName: String? = null): File {
        val dir = getProjectsDirectory(context)
        val sanitized = desiredName?.trim()
            ?.replace(Regex("[/\\\\?%*:|\"<>]"), "_")
            ?.takeIf { it.isNotBlank() } ?: "Project"

        val baseName = if (sanitized.equals("Project", ignoreCase = true)) "Project 1" else sanitized

        var candidate = File(dir, "$baseName$PROJECT_EXTENSION")
        if (!candidate.exists()) {
            return candidate
        }

        val rootName = if (baseName.matches(Regex(".*\\s\\d+$"))) {
            baseName.substringBeforeLast(" ")
        } else {
            baseName
        }

        var index = 2
        while (candidate.exists()) {
            candidate = File(dir, "$rootName $index$PROJECT_EXTENSION")
            index++
        }
        return candidate
    }

    /**
     * Saves an EditRecipe to disk atomically.
     * If targetFile is null, a new unique file in the project folder is generated.
     */
    fun saveProject(context: Context, recipe: EditRecipe, targetFile: File? = null): File {
        val destination = targetFile ?: generateUniqueProjectFile(
            context,
            recipe.projectName.ifBlank { recipe.sourceName.substringBeforeLast(".") }
        )

        val json = ProjectSerializer.serialize(recipe)
        val tempFile = File.createTempFile("prj_save_", ".tmp", context.cacheDir)
        try {
            tempFile.writeText(json)
            if (destination.exists()) {
                destination.delete()
            }
            tempFile.copyTo(destination, overwrite = true)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
        Log.d(TAG, "Project saved successfully to: ${destination.absolutePath}")
        return destination
    }

    /**
     * Extracts a lightweight preview thumbnail (max 320x180) from the source video or image
     * and saves it alongside the project file as `<name>.thumb.jpg`.
     */
    fun extractAndSaveThumbnail(context: Context, sourceUri: Uri, projectFile: File) {
        try {
            val targetThumbFile = getThumbnailFile(projectFile)
            var bitmap: Bitmap? = null

            // Try ContentResolver thumbnail on Android Q+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && sourceUri.scheme == "content") {
                try {
                    bitmap = context.contentResolver.loadThumbnail(sourceUri, android.util.Size(320, 180), null)
                } catch (_: Exception) {}
            }

            // Fallback: MediaMetadataRetriever for video frame
            if (bitmap == null) {
                val retriever = MediaMetadataRetriever()
                try {
                    if (sourceUri.scheme == "file" || sourceUri.scheme == null) {
                        val path = sourceUri.path ?: sourceUri.toString()
                        retriever.setDataSource(path)
                    } else {
                        retriever.setDataSource(context, sourceUri)
                    }
                    bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                        ?: retriever.frameAtTime
                } catch (_: Exception) {
                } finally {
                    try {
                        retriever.release()
                    } catch (_: Exception) {}
                }
            }

            // Fallback: BitmapFactory in case source is an image
            if (bitmap == null) {
                val inputStream = if (sourceUri.scheme == "file") {
                    val path = sourceUri.path
                    if (path != null) FileInputStream(File(path)) else null
                } else {
                    context.contentResolver.openInputStream(sourceUri)
                }
                inputStream?.use { stream ->
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = 2
                    }
                    bitmap = BitmapFactory.decodeStream(stream, null, options)
                }
            }

            if (bitmap != null) {
                val maxDim = 320
                val scaledBitmap = if (bitmap.width > maxDim || bitmap.height > maxDim) {
                    val ratio = minOf(maxDim.toFloat() / bitmap.width, maxDim.toFloat() / bitmap.height)
                    val targetW = maxOf(1, (bitmap.width * ratio).toInt())
                    val targetH = maxOf(1, (bitmap.height * ratio).toInt())
                    Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
                } else {
                    bitmap
                }

                val tempFile = File.createTempFile("thumb_save_", ".tmp", context.cacheDir)
                FileOutputStream(tempFile).use { out ->
                    scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
                }
                if (targetThumbFile.exists()) {
                    targetThumbFile.delete()
                }
                tempFile.copyTo(targetThumbFile, overwrite = true)
                tempFile.delete()

                if (scaledBitmap != bitmap) {
                    scaledBitmap.recycle()
                }
                bitmap.recycle()
                Log.d(TAG, "Thumbnail generated for ${projectFile.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Thumbnail generation failed for ${projectFile.name}: ${e.message}")
        }
    }

    /**
     * Renames an existing project and its associated thumbnail.
     * Updates inner projectName JSON property when possible.
     */
    fun renameProject(context: Context, projectFile: File, newName: String): File? {
        val dir = getProjectsDirectory(context)
        val sanitized = newName.trim().replace(Regex("[/\\\\?%*:|\"<>]"), "_")
        if (sanitized.isBlank()) return null
        if (sanitized.equals(projectFile.nameWithoutExtension, ignoreCase = false)) {
            return projectFile
        }

        var targetFile = File(dir, "$sanitized$PROJECT_EXTENSION")
        if (targetFile.exists()) {
            targetFile = generateUniqueProjectFile(context, sanitized)
        }

        val oldThumb = getThumbnailFile(projectFile)
        val newThumb = getThumbnailFile(targetFile)

        try {
            val content = projectFile.readText()
            val json = JSONObject(content)
            json.put("projectName", targetFile.nameWithoutExtension)
            targetFile.writeText(json.toString())
            projectFile.delete()
        } catch (_: Exception) {
            val renamed = projectFile.renameTo(targetFile)
            if (!renamed) {
                projectFile.copyTo(targetFile, overwrite = true)
                projectFile.delete()
            }
        }

        if (oldThumb.exists()) {
            if (newThumb.exists()) newThumb.delete()
            oldThumb.renameTo(newThumb)
        }

        Log.d(TAG, "Renamed project from ${projectFile.name} to ${targetFile.name}")
        return targetFile
    }

    /**
     * Duplicates an existing project and its associated thumbnail.
     */
    fun duplicateProject(context: Context, projectFile: File): File? {
        val baseName = "${projectFile.nameWithoutExtension} Copy"
        val targetFile = generateUniqueProjectFile(context, baseName)
        try {
            val content = projectFile.readText()
            val json = JSONObject(content)
            json.put("projectName", targetFile.nameWithoutExtension)
            targetFile.writeText(json.toString())
        } catch (_: Exception) {
            projectFile.copyTo(targetFile, overwrite = true)
        }

        val oldThumb = getThumbnailFile(projectFile)
        if (oldThumb.exists()) {
            val newThumb = getThumbnailFile(targetFile)
            try {
                oldThumb.copyTo(newThumb, overwrite = true)
            } catch (_: Exception) {}
        }

        Log.d(TAG, "Duplicated project to ${targetFile.name}")
        return targetFile
    }

    /**
     * Deletes a project file and its associated thumbnail.
     */
    fun deleteProject(context: Context, projectFile: File): Boolean {
        val thumb = getThumbnailFile(projectFile)
        if (thumb.exists()) {
            thumb.delete()
        }
        val deleted = projectFile.delete()
        Log.d(TAG, "Deleted project ${projectFile.name}: $deleted")
        return deleted
    }

    /**
     * Obtains a secure content:// URI via FileProvider for a given project file,
     * with a fallback to Uri.fromFile.
     */
    fun getProjectUri(context: Context, file: File): Uri {
        return try {
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Log.w(TAG, "FileProvider failed for ${file.absolutePath}: ${e.message}, falling back to file://")
            Uri.fromFile(file)
        }
    }

    /**
     * Attempts to resolve a File inside the projects directory from a given URI.
     */
    fun findProjectFileByUri(context: Context, uri: Uri): File? {
        if (uri.scheme == "file") {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists()) return file
        }
        val dir = getProjectsDirectory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(PROJECT_EXTENSION, ignoreCase = true) }
            ?: return null

        val lastSegment = uri.lastPathSegment ?: return null
        for (f in files) {
            if (lastSegment.endsWith(f.name) || f.name == lastSegment) {
                return f
            }
            try {
                val providerUri = getProjectUri(context, f)
                if (providerUri == uri || providerUri.path == uri.path) {
                    return f
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Opens an input stream for reading a project from either file:// or content:// URI.
     */
    fun openProjectInputStream(context: Context, uri: Uri): InputStream? {
        return if (uri.scheme == "file") {
            val path = uri.path
            if (path != null && File(path).exists()) {
                FileInputStream(File(path))
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } else {
            context.contentResolver.openInputStream(uri)
        }
    }
}
