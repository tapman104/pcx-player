package `is`.xyz.mpv.feature.library

import android.net.Uri

data class MediaFile(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val dateAdded: Long,
)

