package `is`.xyz.mpv.feature.library

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.ContextCompat
import `is`.xyz.mpv.Utils
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface MediaOperationResult {
    data object Success : MediaOperationResult
    data class WritePermissionRequired(val intentSender: IntentSender) : MediaOperationResult
    data class DeleteConfirmationRequired(val intentSender: IntentSender) : MediaOperationResult
    data object StoragePermissionRequired : MediaOperationResult
    data class Failure(val message: String, val cause: Throwable? = null) : MediaOperationResult
}

class MediaStoreRepository(private val context: Context) {

    suspend fun getVideos(): List<MediaFile> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION)
                val dateAddedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_ADDED)
                val mimeTypeColumn = cursor.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val displayName = cursor.getString(nameColumn) ?: "Unknown"
                    val durationMs = if (durationColumn >= 0 && !cursor.isNull(durationColumn)) {
                        cursor.getLong(durationColumn)
                    } else {
                        0L
                    }
                    val dateAdded = if (dateAddedColumn >= 0 && !cursor.isNull(dateAddedColumn)) {
                        cursor.getLong(dateAddedColumn)
                    } else {
                        0L
                    }
                    val mimeType = if (mimeTypeColumn >= 0 && !cursor.isNull(mimeTypeColumn)) {
                        cursor.getString(mimeTypeColumn)
                    } else {
                        null
                    }

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                        id
                    )

                    videos.add(
                        MediaFile(
                            id = id,
                            uri = contentUri,
                            displayName = displayName,
                            durationMs = durationMs,
                            dateAdded = dateAdded,
                            mimeType = mimeType,
                        )
                    )
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for videos", e)
        }
        videos
    }

    suspend fun copyVideoToTree(
        mediaFile: MediaFile,
        targetTreeUri: Uri,
    ): MediaOperationResult = withContext(Dispatchers.IO) {
        var destUri: Uri? = null
        val mimeType = resolveMimeType(mediaFile)
        try {
            val parentDocumentId = DocumentsContract.getTreeDocumentId(targetTreeUri)
            val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(targetTreeUri, parentDocumentId)

            destUri = DocumentsContract.createDocument(
                context.contentResolver,
                parentDocUri,
                mimeType,
                mediaFile.displayName,
            ) ?: return@withContext MediaOperationResult.Failure(
                "Could not create file in the selected folder"
            )

            context.contentResolver.openInputStream(mediaFile.uri)?.use { input ->
                context.contentResolver.openOutputStream(destUri)?.use { output ->
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                    }
                    output.flush()
                } ?: throw IOException("Could not open destination output stream")
            } ?: throw IOException("Could not open source video stream")
        } catch (e: CancellationException) {
            cleanupPartialDocument(destUri)
            throw e
        } catch (e: Exception) {
            cleanupPartialDocument(destUri)
            Log.e(TAG, "Failed to copy ${mediaFile.displayName} to $targetTreeUri", e)
            return@withContext MediaOperationResult.Failure(
                e.localizedMessage ?: "Failed to copy video",
                e,
            )
        }

        // Trigger and await MediaStore indexing outside partial-file cleanup so a completed copy is preserved.
        destUri?.let { scanCopiedDocument(it, mimeType) }
        MediaOperationResult.Success
    }

    suspend fun renameVideo(
        mediaFile: MediaFile,
        newDisplayName: String,
        allowPermissionPrompt: Boolean = true,
    ): MediaOperationResult = withContext(Dispatchers.IO) {
        val sanitizedName = newDisplayName.trim()
        if (!isValidFileName(sanitizedName)) {
            return@withContext MediaOperationResult.Failure("Invalid file name")
        }
        if (sanitizedName == mediaFile.displayName) {
            return@withContext MediaOperationResult.Success
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            renameVideoScoped(mediaFile, sanitizedName, allowPermissionPrompt)
        } else {
            renameVideoLegacy(mediaFile, sanitizedName, allowPermissionPrompt)
        }
    }

    suspend fun deleteVideo(
        mediaFile: MediaFile,
        allowPermissionPrompt: Boolean = true,
    ): MediaOperationResult = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return@withContext try {
                val pendingIntent = MediaStore.createDeleteRequest(
                    context.contentResolver,
                    listOf(mediaFile.uri),
                )
                MediaOperationResult.DeleteConfirmationRequired(pendingIntent.intentSender)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create MediaStore delete request for ${mediaFile.uri}", e)
                MediaOperationResult.Failure(e.localizedMessage ?: "Failed to delete video", e)
            }
        }

        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
            return@withContext try {
                val deletedRows = context.contentResolver.delete(mediaFile.uri, null, null)
                if (deletedRows > 0) {
                    MediaOperationResult.Success
                } else {
                    MediaOperationResult.Failure("Video could not be deleted (not found)")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SecurityException) {
                if (!allowPermissionPrompt) {
                    Log.e(TAG, "Delete permission denied after prompt for ${mediaFile.uri}", e)
                    return@withContext MediaOperationResult.Failure("Permission denied to delete video", e)
                }
                if (e is RecoverableSecurityException) {
                    MediaOperationResult.WritePermissionRequired(e.userAction.actionIntent.intentSender)
                } else {
                    MediaOperationResult.Failure("Permission denied to delete video", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to delete ${mediaFile.uri}", e)
                MediaOperationResult.Failure(e.localizedMessage ?: "Failed to delete video", e)
            }
        }

        if (!hasLegacyWritePermission()) {
            return@withContext if (allowPermissionPrompt) {
                MediaOperationResult.StoragePermissionRequired
            } else {
                MediaOperationResult.Failure("Storage write permission is required to delete videos")
            }
        }

        try {
            val deletedRows = context.contentResolver.delete(mediaFile.uri, null, null)
            if (deletedRows > 0) {
                MediaOperationResult.Success
            } else {
                MediaOperationResult.Failure("Video could not be deleted (not found)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete ${mediaFile.uri} on legacy storage", e)
            MediaOperationResult.Failure(e.localizedMessage ?: "Failed to delete video", e)
        }
    }

    private fun renameVideoScoped(
        mediaFile: MediaFile,
        newDisplayName: String,
        allowPermissionPrompt: Boolean,
    ): MediaOperationResult {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
        }
        return try {
            val updatedRows = context.contentResolver.update(mediaFile.uri, values, null, null)
            if (updatedRows > 0) {
                MediaOperationResult.Success
            } else {
                MediaOperationResult.Failure("Video could not be renamed (not found)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SecurityException) {
            if (!allowPermissionPrompt) {
                Log.e(TAG, "Rename permission denied after prompt for ${mediaFile.uri}", e)
                return MediaOperationResult.Failure("Permission denied to rename video", e)
            }
            buildWritePermissionRequest(mediaFile.uri, e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename ${mediaFile.uri} to $newDisplayName", e)
            MediaOperationResult.Failure(e.localizedMessage ?: "Failed to rename video", e)
        }
    }

    private fun renameVideoLegacy(
        mediaFile: MediaFile,
        newDisplayName: String,
        allowPermissionPrompt: Boolean,
    ): MediaOperationResult {
        if (!hasLegacyWritePermission()) {
            return if (allowPermissionPrompt) {
                MediaOperationResult.StoragePermissionRequired
            } else {
                MediaOperationResult.Failure("Storage write permission is required to rename videos")
            }
        }

        return try {
            val oldPath = queryLegacyFilePath(mediaFile.uri)
            if (oldPath != null) {
                val oldFile = File(oldPath)
                val parentDir = oldFile.parentFile
                if (oldFile.exists() && parentDir != null) {
                    val newFile = File(parentDir, newDisplayName)
                    if (newFile.exists()) {
                        return MediaOperationResult.Failure(
                            "A file named \"$newDisplayName\" already exists"
                        )
                    }
                    if (!oldFile.renameTo(newFile)) {
                        return MediaOperationResult.Failure("Failed to rename video file")
                    }
                    @Suppress("DEPRECATION")
                    val values = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
                        put(MediaStore.Video.Media.DATA, newFile.absolutePath)
                    }
                    val updatedRows = try {
                        context.contentResolver.update(mediaFile.uri, values, null, null)
                    } catch (e: CancellationException) {
                        rollbackLegacyRename(newFile, oldFile)
                        throw e
                    } catch (e: Exception) {
                        rollbackLegacyRename(newFile, oldFile)
                        throw e
                    }
                    if (updatedRows > 0) {
                        return MediaOperationResult.Success
                    }
                    rollbackLegacyRename(newFile, oldFile)
                    return MediaOperationResult.Failure("Failed to update media library entry")
                }
            }

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, newDisplayName)
            }
            val updatedRows = context.contentResolver.update(mediaFile.uri, values, null, null)
            if (updatedRows > 0) {
                MediaOperationResult.Success
            } else {
                MediaOperationResult.Failure("Video could not be renamed (not found)")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to rename ${mediaFile.uri} on legacy storage", e)
            MediaOperationResult.Failure(e.localizedMessage ?: "Failed to rename video", e)
        }
    }

    private fun buildWritePermissionRequest(
        uri: Uri,
        securityException: SecurityException,
    ): MediaOperationResult {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val pendingIntent = MediaStore.createWriteRequest(
                    context.contentResolver,
                    listOf(uri),
                )
                return MediaOperationResult.WritePermissionRequired(pendingIntent.intentSender)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "MediaStore.createWriteRequest failed for $uri, checking fallback", e)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            securityException is RecoverableSecurityException
        ) {
            return MediaOperationResult.WritePermissionRequired(
                securityException.userAction.actionIntent.intentSender
            )
        }

        return MediaOperationResult.Failure("Permission denied to modify video", securityException)
    }

    private fun hasLegacyWritePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
        ) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun queryLegacyFilePath(uri: Uri): String? {
        val projection = arrayOf(MediaStore.Video.Media.DATA)
        return try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                val dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
                if (dataColumn >= 0 && cursor.moveToFirst()) {
                    cursor.getString(dataColumn)
                } else {
                    null
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not query file path for $uri", e)
            null
        }
    }

    private fun rollbackLegacyRename(newFile: File, oldFile: File) {
        try {
            if (newFile.exists() && !oldFile.exists() && !newFile.renameTo(oldFile)) {
                Log.w(TAG, "Failed to roll back legacy rename from ${newFile.path} to ${oldFile.path}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error rolling back legacy rename from ${newFile.path} to ${oldFile.path}", e)
        }
    }

    private suspend fun scanCopiedDocument(destUri: Uri, mimeType: String) {
        val localPath = resolveLocalPathForScan(destUri) ?: return
        val mimeTypes = if (mimeType == "video/*") null else arrayOf(mimeType)
        try {
            withTimeoutOrNull(MEDIA_SCAN_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(localPath),
                        mimeTypes,
                    ) { _, _ ->
                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan copied video at $localPath", e)
        }
    }

    private fun resolveLocalPathForScan(destUri: Uri): String? {
        if (destUri.authority == EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY) {
            try {
                val docId = DocumentsContract.getDocumentId(destUri)
                if (docId.startsWith("primary:")) {
                    val relativePath = docId.removePrefix("primary:")
                    @Suppress("DEPRECATION")
                    val primaryRoot = Environment.getExternalStorageDirectory()
                    return if (relativePath.isEmpty()) {
                        primaryRoot.absolutePath
                    } else {
                        File(primaryRoot, relativePath).absolutePath
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not parse document ID for $destUri", e)
            }
        }

        return try {
            context.contentResolver.openFileDescriptor(destUri, "r")?.use { pfd ->
                Utils.findRealPath(pfd.fd)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve local path for $destUri", e)
            null
        }
    }

    private fun resolveMimeType(mediaFile: MediaFile): String {
        val ext = mediaFile.displayName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext != mediaFile.displayName.lowercase()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return mediaFile.mimeType?.takeIf { it.isNotBlank() }
            ?: context.contentResolver.getType(mediaFile.uri)
            ?: "video/*"
    }

    private fun cleanupPartialDocument(destUri: Uri?) {
        if (destUri == null) return
        try {
            DocumentsContract.deleteDocument(context.contentResolver, destUri)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clean up partial copied document $destUri", e)
        }
    }

    private fun isValidFileName(name: String): Boolean {
        if (name.isBlank() || name == "." || name == "..") return false
        return name.none { it == '/' || it == '\\' || it == '\u0000' }
    }

    companion object {
        private const val TAG = "MediaStoreRepository"
        private const val COPY_BUFFER_SIZE = 64 * 1024
        private const val MEDIA_SCAN_TIMEOUT_MS = 2_000L
        private const val EXTERNAL_STORAGE_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"
    }
}
