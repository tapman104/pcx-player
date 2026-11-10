package `is`.xyz.mpv.feature.library

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaStoreRepository(private val context: Context) {

    suspend fun getVideos(): List<MediaFile> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<MediaFile>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_ADDED,
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
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to query MediaStore for videos", e)
        }
        videos
    }

    companion object {
        private const val TAG = "MediaStoreRepository"
    }
}

