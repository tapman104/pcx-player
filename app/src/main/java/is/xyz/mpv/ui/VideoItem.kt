@file:OptIn(ExperimentalFoundationApi::class)

package `is`.xyz.mpv.ui

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import `is`.xyz.mpv.feature.library.MediaFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private object VideoThumbnailLoader {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 8).coerceAtLeast(1024)
    private val cache = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int {
            return value.byteCount / 1024
        }
    }

    fun getCached(mediaFile: MediaFile): Bitmap? = cache.get(mediaFile.uri.toString())

    suspend fun loadThumbnail(context: Context, mediaFile: MediaFile): Bitmap? =
        withContext(Dispatchers.IO) {
            val uriString = mediaFile.uri.toString()
            cache.get(uriString)?.let { return@withContext it }

            val bitmap: Bitmap? = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    context.contentResolver.loadThumbnail(
                        mediaFile.uri,
                        Size(512, 288),
                        null
                    )
                } else {
                    @Suppress("DEPRECATION")
                    MediaStore.Video.Thumbnails.getThumbnail(
                        context.contentResolver,
                        mediaFile.id,
                        MediaStore.Video.Thumbnails.MINI_KIND,
                        null
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }

            if (bitmap != null) {
                cache.put(uriString, bitmap)
            }
            bitmap
        }
}

private data class VideoResolution(val width: Int, val height: Int) {
    fun format(): String? = if (width > 0 && height > 0) "${width}×${height}" else null
}

private object VideoMetadataLoader {
    private val EMPTY = VideoResolution(0, 0)
    private val cache = LruCache<String, VideoResolution>(512)

    fun getCached(mediaFile: MediaFile): VideoResolution? = cache.get(mediaFile.uri.toString())

    suspend fun loadResolution(context: Context, mediaFile: MediaFile): VideoResolution =
        withContext(Dispatchers.IO) {
            val uriString = mediaFile.uri.toString()
            cache.get(uriString)?.let { return@withContext it }

            val resolved = queryMediaStoreResolution(context, mediaFile)
                ?: extractRetrieverResolution(context, mediaFile)
                ?: EMPTY

            cache.put(uriString, resolved)
            resolved
        }

    private fun queryMediaStoreResolution(
        context: Context,
        mediaFile: MediaFile,
    ): VideoResolution? {
        val projection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            arrayOf(
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
                MediaStore.Video.Media.ORIENTATION,
            )
        } else {
            arrayOf(
                MediaStore.Video.Media.WIDTH,
                MediaStore.Video.Media.HEIGHT,
            )
        }

        return try {
            context.contentResolver.query(mediaFile.uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val widthCol = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT)
                val width = if (widthCol >= 0 && !cursor.isNull(widthCol)) cursor.getInt(widthCol) else 0
                val height = if (heightCol >= 0 && !cursor.isNull(heightCol)) cursor.getInt(heightCol) else 0
                if (width <= 0 || height <= 0) return@use null

                val orientation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val orientCol = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION)
                    if (orientCol >= 0 && !cursor.isNull(orientCol)) cursor.getInt(orientCol) else 0
                } else {
                    0
                }

                orientResolution(width, height, orientation)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    private fun extractRetrieverResolution(
        context: Context,
        mediaFile: MediaFile,
    ): VideoResolution? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, mediaFile.uri)
            val width = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            if (width <= 0 || height <= 0) return null

            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toIntOrNull() ?: 0

            orientResolution(width, height, rotation)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun orientResolution(width: Int, height: Int, degrees: Int): VideoResolution {
        val normalized = ((degrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) {
            VideoResolution(width = height, height = width)
        } else {
            VideoResolution(width = width, height = height)
        }
    }
}

@Composable
fun VideoItem(
    mediaFile: MediaFile,
    isMenuOpen: Boolean,
    onClick: (MediaFile) -> Unit,
    onMenuOpen: (MediaFile) -> Unit,
    onMenuDismiss: () -> Unit,
    onCopy: (MediaFile) -> Unit,
    onRename: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cardShape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(cardShape)
            .combinedClickable(
                onClick = { onClick(mediaFile) },
                onLongClick = { onMenuOpen(mediaFile) },
            ),
        shape = cardShape,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(modifier = Modifier.fillMaxWidth()) {
                VideoThumbnail(
                    mediaFile = mediaFile,
                    showDurationBadge = true,
                    shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
                    modifier = Modifier.fillMaxWidth(),
                )

                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp),
                ) {
                    IconButton(
                        onClick = { onMenuOpen(mediaFile) },
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.60f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.MoreVert,
                                contentDescription = "More actions for ${mediaFile.displayName}",
                                tint = Color.White,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }

                    VideoActionsMenu(
                        mediaFile = mediaFile,
                        expanded = isMenuOpen,
                        onDismiss = onMenuDismiss,
                        onCopy = onCopy,
                        onRename = onRename,
                        onDelete = onDelete,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                VideoTitle(
                    displayName = mediaFile.displayName,
                    minLines = 2,
                )
            }
        }
    }
}

@Composable
fun VideoListItem(
    mediaFile: MediaFile,
    isMenuOpen: Boolean,
    onClick: (MediaFile) -> Unit,
    onMenuOpen: (MediaFile) -> Unit,
    onMenuDismiss: () -> Unit,
    onCopy: (MediaFile) -> Unit,
    onRename: (MediaFile) -> Unit,
    onDelete: (MediaFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var resolution by remember(mediaFile.id, mediaFile.uri) {
        mutableStateOf(VideoMetadataLoader.getCached(mediaFile))
    }

    LaunchedEffect(mediaFile.id, mediaFile.uri) {
        if (resolution == null) {
            resolution = VideoMetadataLoader.loadResolution(context, mediaFile)
        }
    }

    val metadataText = remember(mediaFile.durationMs, resolution) {
        buildList {
            if (mediaFile.durationMs > 0) {
                add(TimeFormatter.formatTime(mediaFile.durationMs / 1000.0))
            }
            resolution?.format()?.let { add(it) }
        }.joinToString(" • ")
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = { onClick(mediaFile) },
                onLongClick = { onMenuOpen(mediaFile) },
            )
            .padding(start = 8.dp, end = 0.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VideoThumbnail(
            mediaFile = mediaFile,
            showDurationBadge = false,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.width(112.dp),
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            VideoTitle(displayName = mediaFile.displayName)

            if (metadataText.isNotEmpty()) {
                Text(
                    text = metadataText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        Box {
            IconButton(
                onClick = { onMenuOpen(mediaFile) },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = "More actions for ${mediaFile.displayName}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }

            VideoActionsMenu(
                mediaFile = mediaFile,
                expanded = isMenuOpen,
                onDismiss = onMenuDismiss,
                onCopy = onCopy,
                onRename = onRename,
                onDelete = onDelete,
            )
        }
    }
}

@Composable
private fun VideoThumbnail(
    mediaFile: MediaFile,
    showDurationBadge: Boolean,
    shape: Shape,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var thumbnail by remember(mediaFile.id, mediaFile.uri) {
        mutableStateOf(VideoThumbnailLoader.getCached(mediaFile))
    }

    LaunchedEffect(mediaFile.id, mediaFile.uri) {
        if (thumbnail == null) {
            thumbnail = VideoThumbnailLoader.loadThumbnail(context, mediaFile)
        }
    }

    Box(
        modifier = modifier
            .aspectRatio(16f / 9f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = thumbnail
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = mediaFile.displayName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Movie,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }

        if (showDurationBadge && mediaFile.durationMs > 0) {
            Surface(
                color = Color.Black.copy(alpha = 0.78f),
                shape = RoundedCornerShape(4.dp),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp),
            ) {
                Text(
                    text = TimeFormatter.formatTime(mediaFile.durationMs / 1000.0),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun VideoTitle(
    displayName: String,
    modifier: Modifier = Modifier,
    minLines: Int = 1,
) {
    Text(
        text = displayName,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium,
        color = MaterialTheme.colorScheme.onSurface,
        minLines = minLines,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

