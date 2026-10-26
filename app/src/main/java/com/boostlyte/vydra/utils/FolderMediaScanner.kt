package com.boostlyte.vydra.utils

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.Serializable
import java.util.Locale

/**
 * Utility for scanning a directory or Android Storage Access Framework (SAF) DocumentTree
 * for supported media files without loading full bitmaps into memory.
 */
object FolderMediaScanner {

    private const val TAG = "FolderMediaScanner"

    // Supported formats specified: JPEG, PNG, WebP, mp4 + standard image formats
    val SUPPORTED_IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif")
    val SUPPORTED_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "mov", "avi", "3gp", "webm")

    data class MediaFileItem(
        val uri: Uri,
        val displayName: String,
        val isImage: Boolean,
        val isVideo: Boolean
    ) : Serializable

    /**
     * Checks if the given filename or MIME type is a supported media format.
     */
    fun isSupportedFile(displayName: String, mimeType: String? = null): Boolean {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.getDefault())
        val isImg = (mimeType != null && mimeType.startsWith("image/")) || ext in SUPPORTED_IMAGE_EXTENSIONS
        val isVid = (mimeType != null && mimeType.startsWith("video/")) || ext in SUPPORTED_VIDEO_EXTENSIONS
        return isImg || isVid
    }

    /**
     * Scans a file directory directly and returns sorted media items.
     * Optional uriProvider allows passing mock/custom Uri in unit test environments.
     */
    fun scanAndSortDirectory(
        dir: File,
        uriProvider: (File) -> Uri = { Uri.fromFile(it) }
    ): List<MediaFileItem> {
        val items = mutableListOf<MediaFileItem>()
        try {
            if (dir.exists() && dir.isDirectory) {
                val files = dir.listFiles() ?: return emptyList()
                for (file in files) {
                    if (file.isDirectory || file.name.startsWith(".")) continue
                    val name = file.name
                    val ext = file.extension.lowercase(Locale.getDefault())
                    val isImg = ext in SUPPORTED_IMAGE_EXTENSIONS
                    val isVid = ext in SUPPORTED_VIDEO_EXTENSIONS
                    if (isImg || isVid) {
                        val resolvedUri = try {
                            uriProvider(file)
                        } catch (t: Throwable) {
                            null
                        }
                        if (resolvedUri != null) {
                            items.add(
                                MediaFileItem(
                                    uri = resolvedUri,
                                    displayName = name,
                                    isImage = isImg,
                                    isVideo = isVid
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error scanning directory: ${e.message}", e)
        }

        items.sortWith { a, b ->
            NaturalOrderComparator.compare(a.displayName, b.displayName)
        }
        return items
    }

    /**
     * Scans a DocumentTree URI or file:// URI for supported media files.
     * Collects URIs and display names off the main thread without decoding full images.
     * Sorts the files using natural alphanumeric sorting.
     */
    fun scanAndSortMedia(context: Context, treeUri: Uri): List<MediaFileItem> {
        val items = mutableListOf<MediaFileItem>()
        val contentResolver = context.contentResolver

        if (treeUri.scheme == "file") {
            val path = treeUri.path ?: return emptyList()
            return scanAndSortDirectory(File(path))
        } else {
            // Android SAF DocumentTree
            try {
                val docId = if (DocumentsContract.isDocumentUri(context, treeUri)) {
                    DocumentsContract.getDocumentId(treeUri)
                } else {
                    DocumentsContract.getTreeDocumentId(treeUri)
                }
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)

                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )

                contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val childId = if (idCol != -1) cursor.getString(idCol) else null ?: continue
                        val name = if (nameCol != -1) cursor.getString(nameCol) ?: "media" else "media"
                        val mime = if (mimeCol != -1) cursor.getString(mimeCol) ?: "" else ""

                        // Skip directories and hidden files
                        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        if (name.startsWith(".")) continue

                        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
                        val isImg = mime.startsWith("image/") || ext in SUPPORTED_IMAGE_EXTENSIONS
                        val isVid = mime.startsWith("video/") || ext in SUPPORTED_VIDEO_EXTENSIONS

                        if (isImg || isVid) {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, childId)
                            items.add(
                                MediaFileItem(
                                    uri = docUri,
                                    displayName = name,
                                    isImage = isImg,
                                    isVideo = isVid
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error querying SAF document tree: ${e.message}", e)
            }
        }

        // Sort items naturally by filename
        items.sortWith { a, b ->
            NaturalOrderComparator.compare(a.displayName, b.displayName)
        }

        return items
    }
}
